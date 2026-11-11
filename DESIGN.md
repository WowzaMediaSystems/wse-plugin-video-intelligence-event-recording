# EventRecordingPlugin - Design Document

## Overview

The `EventRecordingPlugin` implements `IVifEventListener` to create one MP4 per presence event of the
configured object classes, on a live stream and on a VOD file job alike. The listener is one class
with one configuration; what differs per host is the backend that turns an event into a file:

- **Live stream** — the WSE `StreamRecorder` API, as before: the stream's packet buffer supplies the
  back-buffer, a scheduled stop the post-detection window, and the recorder's callbacks say when the
  file exists and when it is complete.
- **VOD file job** — a cut of the job's source file by media time, video and audio copied sample for
  sample with mp4parser, issued once the event's bounds are known.

The trigger is shared: `PresenceGate` turns the stream of detection responses into presence events
on the responses' own timeline (stream timecode live, media milliseconds on a job).

## Core Behavior

### Presence events

```
responses (immediate) ──► PresenceGate.observe(atMs, detections)
                              │
                              ├─ Start(fromMs, classes, trackIds)   first matching response
                              │
                              └─ End(fromMs, toMs, ...)             no match for lost_object_duration_ms
                                                                    (toMs = last match + lost_object_duration_ms),
                                                                    or max_clip_ms reached
```

- A response **matches** when at least `min_count` detections carry one of `class_names` (`all`
  matches every class). With `require_track`, only detections with a tracking ID count.
- **Every response advances the clock**, empty ones included — VIF emits one per analysis cycle —
  so a loss is noticed without a timer of the plugin's own. On a job the clock is the file's media
  time, which is what makes the clips identical whether the file is analysed faster or slower than
  real time; the 1.0.0 wall-clock eviction would have cut clips of the wrong length there.
- A match inside `merge_gap_ms` after a loss **continues** the same event: the end is held back for
  the gap and withdrawn when a match arrives.
- A clock that jumps backwards (a timecode discontinuity) ends the open event where it was last
  seen and starts the clock again.
- The gate has no Wowza types and is unit-tested on its own.

### Recording lifecycle

```
Start ──► backend.onStart          live: StreamRecorder.start(backBufferTime = back_buffer_time)
                                   vod:  remember fromMs
  │
End ───► backend.onEnd             live: schedule stop at toMs + post_detection_duration
                                   vod:  cut [fromMs − back_buffer_time, toMs + post_detection_duration]
                                         once the job's clock passes toMs + post_detection_duration
  │
host shuts the listener down ──► backend.finish(reason)
                                   live: stop the recorder now
                                   vod:  JOB_COMPLETED: close the open event and cut everything owed,
                                         waiting up to 60 s for the cuts; anything else: cut nothing
```

Key decisions carried over from 1.0.0: one recorder per stream, extend rather than restart, no
`batch`/`rollup` (the plugin subscribes to `immediate` for the lowest latency).

## Host selection

`requires()` returns the empty set, so the controller initialises the listener on any host. `onInit`
picks the backend from what it is handed:

| Host | What `onInit` sees | Backend |
|---|---|---|
| VOD job | `job_id` and `source_file` in the properties, a stream stub that answers only `getName()`, no application instance | `VodClipBackend` |
| Live stream | an `IMediaStream` and an `IApplicationInstance` | `LiveRecorderBackend` |
| Neither | | one WARN; the listener is inert |

On a job, `ModuleVideoIntelligence.getModule(...)` is never called (there is no application
instance) and `record_stream_name` is ignored with one INFO line.

## Implementation Details

### Package layout

- `com.wowza.wms.plugin.videointelligence.event.EventRecordingPlugin` — the listener (the class the
  controller resolves by its short name) and `EventRecordingFileVersionDelegate`, the live file
  namer. Both keep their 1.0.0 names.
- `com.wowza.wms.plugin.videointelligence.eventrecording` — `PresenceGate`, `RecordingBackend`,
  `LiveRecorderBackend`, `VodClipBackend`, `RecordingNames`. A sub-namespace of the controller's,
  so a future controller class of the same simple name cannot shadow the plugin's.
- `com.wowza.wms.plugin.videointelligence.eventrecording.clip` — `Mp4FileClipper`, `IsoBoxMapping`.

### `EventRecordingPlugin`

- `onInit` parses the properties (1.0.0 names unchanged; `min_count`, `require_track`,
  `merge_gap_ms`, `max_clip_ms` added), builds the gate and the backend, and resolves the Engine's
  `WebhookListener` when `webhooks` is on.
- `immediate` takes the response's `detectionWindow.fromTimeCode` as the clock, maps the detections
  to what the gate needs (class, tracking ID), feeds the gate under a lock and hands each transition
  to the backend, then `onClock`. Live, it also maintains the per-track table behind the
  `detection.start`/`detection.stop` webhooks, evicted on the same media clock.
- `onShutdown(ShutdownEvent)` closes the gate on `JOB_COMPLETED`, lets the backend finish, and shuts
  the executor down — waiting for the owed cuts on a completed job, abandoning them otherwise.
- As the backends' `Reporter` it logs every file and sends the webhooks.

**Threading.** The controller invokes listeners on the thread that paces the frame/response cycle
(live) or the job's pump (VOD). Recorder calls block and a cut of a long event is seconds of IO, so
both backends run their side effects on the listener's single-thread executor. The gate and the
backend state are guarded by one lock.

### `LiveRecorderBackend`

The 1.0.0 recorder code, with two changes:

- **Start race closed.** `LiveStreamRecordManager.startRecording()` is asynchronous and
  `activeRecorder` is only known from `onStartRecorder`; 1.0.0 could ask for a second recorder from
  the next response. A `starting` flag set synchronously in `onStart` keeps the second request out.
- **Stop by the clock.** The post-detection stop is scheduled from the gate's `End` (`toMs +
  post_detection_duration − now`, on the stream's timeline) instead of from a wall-clock eviction;
  a new `Start` cancels it.

The keyframe walk that computes the first-frame wall clock for `${RecordingStartTime}` is
unchanged (`getLastKeyFrame`, `getPlayPackets`, back to `backBufferTime` before the latest
keyframe), and so are the recorder parameters (`FORMAT_MP4`, `startOnKeyFrame`, `SEGMENT_NONE`,
the file version delegate). `onSegmentStart` is where the file is known (`getFilePath()`), and
what the reporter is told; `onStopRecorder` reports the finished file.

Limits inherited: the back-buffer is bounded by the Engine's live packet buffer (~8 s) and starts
on a keyframe; a stream younger than `back_buffer_time` records from its start.

### `VodClipBackend`

- `onEnd` queues a cut owed at `toMs + post_detection_duration`; `onClock` issues every cut whose
  time has come. Nothing is cut while an event is open, so the clip is written once, with exact
  bounds.
- `finish(JOB_COMPLETED)` issues what is still owed; `finish(JOB_FAILED | JOB_CANCELLED |
  LISTENER_DISABLED)` drops it with one INFO line.
- Output directory: `output_path`, else the source file's directory. The name comes from the same
  template, `${RecordingStartTime}` rendered as the clip's media offset `HH.mm.ss.SSS` and
  `${SourceStreamName}` as the source file's name without extension; a taken name gets `_1`, `_2`.
- `max_clip_ms` caps an event that never closes: the gate ends it at `fromMs + max_clip_ms` and
  starts the next one.

Why a deferred cut rather than writing as the job advances: an MP4 written incrementally has no
`moov` until the end and needs a second pass anyway; the source is on disk, and cutting once with
exact bounds costs only that the clip arrives at `to + post_detection_duration` instead of at `to`.

### `Mp4FileClipper`

mp4parser (`Movie` → `ClippedTrack` per `vide`/`soun` track → `DefaultMp4Builder`): the cut starts
on the video sync sample at or before the requested start, so a clip may begin up to one GOP early
and never late, and ends at the first sample at or after the requested end, or at the file's end.
Other tracks (timecode, data) are dropped. No decoding, any codec the container carries.

**Box mapping inside the Engine.** mp4parser loads its box-type mapping
(`isoparser2-default.properties`) through the JVM's system classloader, which cannot see the
Engine's `lib/` jars: the lookup returns null and the parser dies in `Properties.load` with
"inStream parameter is null" before touching the file. `IsoBoxMapping.ensure()` fills the parser's
cache from the plugin's own classloader, which sits next to `isoparser`, before any file is opened.
Unit tests never need it; there mp4parser is on the system classpath.

### Webhook payloads

Uses the Engine's webhook framework (`WebhookListener`), event name `video.intelligence.detection`,
data under `event_recording_data`. A job has no stream context: the context is built with the
default vhost, the `vod` application and `_definst_`, and every payload carries `job_id` and
`source_file`.

**Live** — unchanged from 1.0.0: `detection.start` when a tracked object of the classes first appears
in the event (including the one that started the recording), `detection.stop` when it has not been
seen for `lost_object_duration_ms`; queued until the segment file is open so they carry the
`recording` block (`file`, `started_at`). WSE's own `recording.started`/`recording.stopped` cover the
file itself.

**VOD** — one pair per clip, sent when the clip is written (the file does not exist earlier):

```json
{ "event_recording_data": { "action": "recording.started", "stream_name": "vod-<job>",
    "job_id": "...", "source_file": "/usr/local/WowzaStreamingEngine/content/race1.mp4",
    "class_names": ["person"], "tracking_ids": [7],
    "recording": { "file": "/usr/local/WowzaStreamingEngine/content/race1_00.03.39.800.mp4", "from_ms": 219800 } } }
{ "event_recording_data": { "action": "recording.stopped", "stream_name": "vod-<job>",
    "job_id": "...", "source_file": "...", "to_ms": 233000, "size_bytes": 2012929,
    "recording": { "file": "...", "from_ms": 219800 } } }
```

### `EventRecordingFileVersionDelegate`

As in 1.0.0: with `SEGMENT_NONE` the base delegate returns the base file path untouched, so every
token is expanded here, `${RecordingStartTime}` from the first-frame wall clock computed by the
keyframe walk. The token vocabulary is `RecordingNames`, shared with the VOD naming.

## Edge Cases

| Scenario | Behavior |
|---|---|
| Stream unpublishes while recording | WSE stops the recorder; `onStopRecorder` reports the file |
| Detection flickers (empty response, or one with no tracking ID) | `lost_object_duration_ms` is the hysteresis, on the media clock, so the mix of response shapes neither advances nor suppresses it |
| Many objects at once | One event, one file, until all are absent for `lost_object_duration_ms` plus the post-detection window |
| Back-buffer exceeds the live buffer | WSE caps at what the buffer holds (~8 s), from the earliest keyframe inside it |
| Job stops before the event closes | `JOB_COMPLETED`: the event ends `lost_object_duration_ms` after its last match and is cut; failed/cancelled: nothing is written |
| Job resumed after an interruption | The listener is re-created; an event that straddled the interruption yields two clips |
| Clip name already taken | `_1`, `_2` suffix, never an overwrite |
| Event that never ends (`max_clip_ms`) | Closed at the cap, cut, and a new event opened from the next match |
| Listener disabled live with a recorder running | The recorder is stopped on the way out — left registered, the manager would restart it on the next publish |
