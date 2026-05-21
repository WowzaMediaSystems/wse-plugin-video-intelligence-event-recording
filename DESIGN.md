# EventRecordingPlugin - Design Document

## Overview

The `EventRecordingPlugin` implements `IVifEventListener` to create MP4 recordings of a live stream when specific object classes are detected. It uses the WSE `StreamRecorder` API to capture video segments that include a configurable back-buffer (pre-event) and post-event duration. Webhooks are sent when recordings start and stop.

## Core Behavior

### Recording Lifecycle

```
Detection event (matching class) arrives
        │
        ▼
  ┌─ Is a recorder already running for this stream? ──┐
  │                                                     │
  No                                                   Yes
  │                                                     │
  ▼                                                     ▼
  Start new StreamRecorder              Extend the stop timer
  (with backBufferTime)                 (push deadline forward)
  Send "recording.start" webhook
  │
  ▼
  ... time passes, no new matching detections ...
  │
  ▼
  Stop timer fires
  │
  ▼
  Stop StreamRecorder
  Send "recording.stop" webhook
```

### Key Design Decisions

1. **One recorder per stream** -- Multiple simultaneous detections (different objects, different classes) share a single recording rather than spawning separate recorders. The recording covers all overlapping detection activity.

2. **Extend, don't restart** -- If a new detection arrives while already recording, the stop deadline is pushed forward by `post_detection_duration`. This produces one continuous file instead of many short clips.

3. **Tracking-aware extension** -- While a tracked object's `trackingId` continues to appear in detection events, the recording keeps running. The stop timer only begins once the tracked object disappears from detections for a configurable duration.

4. **Use `immediate` mode** -- The plugin subscribes to `immediate` events for lowest latency between detection and recording start. The `batch` and `rollup` methods are not used.

## Configuration

Configuration properties are defined in `src/main/js/EventRecordingPlugin.js`, which provides UI field definitions for the VIF manager web interface. At runtime, the configured values are written to the `vif_event_listeners` section of `video-intelligence.json` and passed to the plugin via the `properties` map in `onInit()`.

### UI Definition (`src/main/js/EventRecordingPlugin.js`)

The file registers the listener's available methods and configurable fields in `window.VIF_LISTENER_PROPERTIES['EventRecordingPlugin']`. Each field specifies a `key`, `label`, `type`, `default`, and `tooltip`.

### Runtime Configuration (`video-intelligence.json`)

The UI-configured values are stored in the runtime config:

```json
"vif_event_listeners": {
  "EventRecordingPlugin": {
    "class_name": "EventRecordingPlugin",
    "methods": ["immediate"],
    "properties": {
      "class_names": ["person", "car"],
      "back_buffer_time": 5000,
      "post_detection_duration": 5000,
      "lost_object_duration_ms": 3000,
      "recording_name_template": "${SourceStreamName}_${RecordingStartTime}",
      "record_stream_name": "",
      "output_path": "",
      "webhooks": true
    }
  }
}
```

### Properties Reference

| Property | Type | Default | Description |
|---|---|---|---|
| `class_names` | string_list | `["all"]` | Object classes that trigger recording. `"all"` matches any class. |
| `back_buffer_time` | number | `5000` | Milliseconds of video to capture before the detection event. Uses the WSE incoming stream buffer (~8s available). |
| `post_detection_duration` | number | `5000` | Milliseconds to continue recording after the last qualifying detection. |
| `lost_object_duration_ms` | number | `3000` | Milliseconds since an object was last seen before it is considered lost. Wall-clock based, so independent of VIF's response cadence (which interleaves empty responses, detections with no tracking ID, and detections with a confirmed tracking ID). |
| `recording_name_template` | text | `${SourceStreamName}_${RecordingStartTime}` | File naming template. Standard tokens: `${SourceStreamName}`, `${BaseFileName}`, `${RecordingStartTime}`, `${SegmentNumber}`, `${SegmentTime}`. Custom tokens: `${ClassNames}`, `${TrackingIds}`. |
| `record_stream_name` | text | (empty) | Regex pattern to match the stream name to record. Empty records the source stream. To record the VIF transcoder output (with overlays), use `.*-vi$`. The pattern is matched against published streams in the application; the first match is used. |
| `output_path` | text | (empty) | Directory for recorded files. Empty uses the WSE application default. |
| `webhooks` | boolean | `true` | Send webhooks on recording start/stop. |

## Implementation Details

### Class: `EventRecordingPlugin`

```
EventRecordingPlugin implements IVifEventListener, IStreamRecorderActionNotify
```

#### State

```java
// Configuration (set in onInit)
private Set<String> classNames;
private long backBufferTime;
private long postDetectionDuration;
private long lostObjectDurationMs;    // wall-clock ms; an object is considered lost if not seen in this long
private String recordingNameTemplate;
private Pattern recordStreamNamePattern;  // null = record source stream
private String outputPath;
private boolean webhooksEnabled;

// Runtime state
private IStreamRecorder activeRecorder;        // null when not recording
private Timer stopTimer;                        // schedules delayed stop
private Map<Integer, ObjectInfo> activeObjects;  // trackingId -> last-seen info about the object
```

#### Lifecycle

**`onInit(appInstance, stream, methods, properties)`**
- Parse all configuration properties from the `properties` map
- Store `appInstance`, `stream`, and connection metadata (`vHostName`, `appName`, etc.)
- Obtain `WebhookListener` from `Server.getInstance().getProperties()` if webhooks enabled

**`onShutdown()`**
- If a recorder is active, stop and remove it via `LiveStreamRecordManager.stopRecording()`
- Cancel any pending stop timer

#### Event Processing

**`immediate(DetectionResponse response)`**

The VIF framework filters detections by `confidence_threshold` before delivering them to the listener, so this method only needs to filter by `class_names`. Each detection that passes the filter is stored in `activeObjects`, keyed by its tracking ID, with its `className`, last-observed `confidence`, the stream-relative timecode (`lastSeenTC` = `response.detectionWindow.fromTimeCode`), and the wall-clock time (`lastSeenAtMs` = `System.currentTimeMillis()`).

VIF emits a mixed stream of detection responses: responses with a confirmed tracking ID, responses where the tracking ID is `null` (detected but no tracking ID assigned yet), and outright empty responses. The eviction logic does **not** key off the response cadence — it uses wall-clock elapsed time, so noisy "non-confirming" responses neither advance nor suppress the eviction clock.

```
nowMs = System.currentTimeMillis()
detectionTC = response.detectionWindow.fromTimeCode

1. Build a `qualified` list: detections with non-null tracking ID and matching class_names
2. For each qualified detection:
     - Update/insert in activeObjects (set className, lastConfidence, lastSeenTC, lastSeenAtMs = nowMs)
     - Remember which tracking IDs are new
3. Eviction (regardless of whether qualified is empty):
     - For each entry in activeObjects where (nowMs - lastSeenAtMs) > lostObjectDurationMs:
       capture ObjectInfo, evict, fire detection.stop
4. If qualified non-empty:
     - If recorder is NOT running -> startRecording() (async; recording context populated by onSegmentStart)
     - Else -> cancel pending stop timer (extend recording)
     - For each new tracking ID, fire detection.start webhook
5. If activeObjects is now empty and recorder is running, scheduleStop()
6. Return !qualified.isEmpty()
```

**`batch(...)` / `rollup(...)`** -- Return `false` (unused).

#### Stream Name Resolution

The stream to record is determined by `record_stream_name`:

- **Empty (default):** Record the source stream (`this.streamName`).
- **Regex pattern:** Match against published stream names in the application. The first matching stream is recorded. For example, `.*-vi$` matches the VIF transcoder output stream (e.g., `myStream-vi`), which includes overlays.

**Transcoder dependency:** The transcoder output stream only exists when the VIF is configured with `use_transcoder: true` for the stream. If `use_transcoder` is `false`, no `-vi` stream is produced and `record_stream_name` cannot match it.

To handle this gracefully, the plugin queries the per-stream `useTranscoder` setting at `onInit()` via:

```java
ModuleVideoIntelligence vifModule = ModuleVideoIntelligence.getModule(
    vHostName, appName, appInstanceName);
if (vifModule != null) {
    VideoIntelligenceService vifService = vifModule.getVifService(streamName);
    if (vifService != null) {
        boolean useTranscoder = vifService.getDetectorInfo().useTranscoder;
    }
}
```

If `record_stream_name` is set but `useTranscoder` is `false`, the plugin ignores the pattern, logs a warning, and records the source stream instead.

At recording time, `resolveRecordStreamName()` iterates the application's published streams (`appInstance.getPublishStreamNames()`) and returns the first stream name matching the regex. If no match (e.g., transcoder hasn't started yet), it falls back to the source stream with a warning.

#### Recording Start

`startRecording()`:
1. Computes `recordingStartMs` and `earliestKeyTC` via the keyframe-walk algorithm (see below).
2. Resolves the target stream name via `resolveRecordStreamName()`.
3. Builds `StreamRecorderParameters`, passing `recordingStartMs` into `EventRecordingFileVersionDelegate` so the `${RecordingStartTime}` token reflects the actual first-frame wall clock.
4. Calls `LiveStreamRecordManager.startRecording()`. WSE emits a `recording.started` webhook automatically.

```java
computeRecordingStart();             // sets recordingStartMs and earliestKeyTC
String targetStream = resolveRecordStreamName();

StreamRecorderParameters params = new StreamRecorderParameters(appInstance);
params.fileFormat = IStreamRecorderConstants.FORMAT_MP4;
params.backBufferTime = this.backBufferTime;
params.startOnKeyFrame = true;
params.recordData = true;
params.fileTemplate = this.recordingNameTemplate;
params.segmentationType = IStreamRecorderConstants.SEGMENT_NONE;
params.notifyListener = this;
params.fileVersionDelegate = new EventRecordingFileVersionDelegate(
        trackingIds, classNamesSnapshot, recordingStartMs);

if (this.outputPath != null)
    params.outputPath = this.outputPath;

appInstance.getVHost().getLiveStreamRecordManager()
    .startRecording(appInstance, targetStream, params);
```

#### Computing the recording start time

The recorder's own `getStartTime()` is just `DateTime.now()` from when the start command was processed -- it does not account for the back buffer. The configured `backBufferTime` is a **maximum** lookback window; the actual first frame is the earliest **video keyframe** within that window, since the recorder requires a keyframe to begin writing.

The plugin replicates the recorder's logic to determine the true first-frame wall clock. `IMediaStream` exposes the two anchor packets directly (`getLastPacket()` and `getLastKeyFrame()`), so we only walk `getPlayPackets()` when there could be an earlier keyframe within the window:

```java
AMFPacket lastKey = stream.getLastKeyFrame();
AMFPacket lastPacket = stream.getLastPacket();
if (lastKey != null && lastPacket != null) {
    long firstKeyTC = lastKey.getAbsTimecode();
    long lastTC = lastPacket.getAbsTimecode();
    long targetTC = firstKeyTC - backBufferTime;
    earliestKeyTC = firstKeyTC;

    if (backBufferTime > 0) {
        for (int i = packets.size() - 1; i >= 0; i--) {
            AMFPacket p = packets.get(i);
            long tc = p.getAbsTimecode();
            if (tc >= firstKeyTC) continue;
            if (tc < targetTC) break;
            if (p.isVideo() && FLVUtils.isVideoKeyFrame(p))
                earliestKeyTC = tc;
        }
    }
    offsetMs = lastTC - earliestKeyTC;
}
recordingStartMs = System.currentTimeMillis() - offsetMs;
```

`recordingStartMs` is used:
- Directly by `EventRecordingFileVersionDelegate` to expand `${RecordingStartTime}`.
- Formatted as ISO-8601 in the `recording.started_at` field of detection webhooks.

`earliestKeyTC` is retained so subsequent detection webhooks can compute their `recording_offset_ms` (= `detectionTC - earliestKeyTC`).

#### Stop Timer

When the last tracked object is lost and no new qualifying detections arrive:

```java
stopTimer = new Timer();
stopTimer.schedule(new TimerTask() {
    public void run() {
        if (activeRecorder != null) {
            // Use the manager's stopRecording to both stop AND remove the recorder.
            // Calling activeRecorder.stopRecorder() directly leaves the recorder
            // registered in the manager, causing it to auto-restart on stream republish.
            appInstance.getVHost().getLiveStreamRecordManager()
                .stopRecording(appInstance, recordingStreamName);
        }
    }
}, postDetectionDuration);
```

If a new detection arrives before the timer fires, the timer is cancelled and recording continues.

#### IStreamRecorderActionNotify Callbacks

The plugin no longer emits its own recording start/stop webhooks -- WSE's built-in webhook framework already fires `recording.started`, `recording.stopped`, `recording.failed`, `recording.segment.started`, and `recording.segment.ended` automatically. Consumers correlate WSE recording events with the plugin's `detection.*` events via stream name and recording file path.

**`onStartRecorder(IStreamRecorder recorder)`**
- Set `activeRecorder = recorder`. The recorder may still be in WAITING state (waiting for a keyframe); the actual file may not yet exist. Detection webhooks are NOT flushed here.
- Log

**`onSegmentStart(IStreamRecorder recorder)`**
- Set `recordingActive = true`. At this point the segment file is open for writing and `getFilePath()` returns the path being written (see `LiveStreamRecorderMP4` line 213: `notifySegmentStart(this)` is fired right after `WowzaRandomAccessFile` is opened on the target path). The plugin reads `activeRecorder.getFilePath()` for the `recording.file` webhook field -- not `getCurrentFile()`, which returns null until later in the recorder lifecycle.
- Flush any queued `pendingDetectionEvents` -- they now carry the correct `recording.file` and `recording.started_at`.

**`onSegmentEnd(IStreamRecorder recorder)`**
- Set `recordingActive = false`.

**`onStopRecorder(IStreamRecorder recorder)`**
- Log file/duration/size
- Clear runtime state: `activeRecorder`, `recordingActive`, `recordingStreamName`, `recordingStartMs`, `earliestKeyTC`, `activeObjects`, `pendingDetectionEvents`, `stopTimer`

**`onRecorderError(IStreamRecorder recorder)`**
- Log
- Clear `pendingDetectionEvents` (so a failed start doesn't leak queued events)

Other callbacks (`onCreateRecorder`, `onSplitRecorder`, `onSwitchRecorder`) -- log only.

#### Detection webhook timing

`LiveStreamRecordManager.startRecording()` is **asynchronous** -- the actual recorder start is queued on `vhost.getHandlerThreadPool()` and runs on a worker thread. This means after `startRecording()` returns, `activeRecorder` is still null and no file path is available. If we fired `detection.start` webhooks at that point, they would lack the recording context.

To avoid this, detection webhooks go through `enqueueOrSendDetectionWebhook()`:
- If `recordingActive` is true (segment file is open), send the webhook immediately.
- Otherwise, snapshot the values (`PendingDetection`) and add to `pendingDetectionEvents`. The queue is flushed in `onSegmentStart`.

This ensures every detection webhook carries the correct `recording.file` and `recording.started_at`, even for the first tracked object that triggers the recording.

### Webhook Payloads

Uses the existing WSE webhook infrastructure, following the same pattern as `ObjectTracking.sendWebhook()`.

**Event name:** `WebhookEventName.VI_DETECTION` (`"video.intelligence.detection"`)

The plugin emits two webhook actions: `detection.start` when a new tracked object first qualifies (including the first tracked object that causes the recording to begin), and `detection.stop` when a tracked object has not been seen for `lost_object_duration_ms` of wall-clock time (including the last tracked object whose loss leads to recording stop). The recording start and stop are NOT emitted by this plugin -- WSE's built-in `recording.started` / `recording.stopped` webhooks cover those events.

The payload does not include its own timestamp -- WSE's `WebhookEvent` framework sets a top-level timestamp on the outer event automatically.

**Detection Start:**
```json
{
  "event_recording_data": {
    "action": "detection.start",
    "stream_name": "myStream",
    "detection": {
      "tracking_id": 7,
      "class_name": "person",
      "confidence": 0.81,
      "recording_offset_ms": 4870
    },
    "active_object_count": 3,
    "recording": {
      "file": "/path/to/myStream_2026-05-08-12.00.00.000-UTC.mp4",
      "started_at": "2026-05-08T12:00:00Z"
    }
  }
}
```

**Detection Stop:**
```json
{
  "event_recording_data": {
    "action": "detection.stop",
    "stream_name": "myStream",
    "detection": {
      "tracking_id": 5,
      "class_name": "car",
      "confidence": 0.92,
      "recording_offset_ms": 9540
    },
    "active_object_count": 0,
    "recording": {
      "file": "/path/to/myStream_2026-05-08-12.00.00.000-UTC.mp4",
      "started_at": "2026-05-08T12:00:00Z"
    }
  }
}
```

- `detection.confidence` for `detection.stop` is the LAST observed confidence before the object was lost.
- `detection.recording_offset_ms` is the offset of the event within the recording (0-based), computed as `detectionTC - earliestKeyTC`. For `detection.stop` it uses the `lastSeenTC` from the stored `ObjectInfo`.
- `active_object_count` reflects the size of `activeObjects` AFTER the change is applied.
- `recording.started_at` is the wall-clock time of the first frame in the recording, computed via the keyframe-walk algorithm (see "Computing the recording start time"). It matches the `${RecordingStartTime}` token in the recording file name.
- The `recording` block is omitted if no recorder is active or `getFilePath()` is not yet populated.

### Sending Webhooks

```java
private void sendWebhook(Map<String, Object> payload) {
    if (webhookListener == null) return;
    try {
        Map<String, Object> data = new HashMap<>();
        data.put("event_recording_data", payload);

        WebhookEvent event = new WebhookEvent(WebhookEventName.VI_DETECTION);
        event.setContext(WebhookEventContext.forEntity(
            vHostName, appName, appInstanceName, streamName,
            WebhookEventEntity.OBJECTS
        ));
        event.setData(data);
        webhookListener.addEvent(event);
    } catch (Exception e) {
        logger.error(logPrefix + "Webhook Error: " + e.getMessage(), e);
    }
}
```

### EventRecordingFileVersionDelegate

Custom file version delegate that extends `StreamRecorderFileVersionDelegate` to add detection-specific template tokens.

```
EventRecordingFileVersionDelegate extends StreamRecorderFileVersionDelegate
```

**File:** `src/main/java/com/wowza/wms/plugin/videointelligence/event/EventRecordingFileVersionDelegate.java`

Since we use `SEGMENT_NONE`, the base class's `createFileName` skips template replacement and returns the original base file path unchanged. This delegate must handle all token replacement itself, including both the standard tokens and our custom ones.

**All available tokens:**

| Token | Example | Description |
|---|---|---|
| `${SourceStreamName}` | `myStream` | Stream name (standard) |
| `${BaseFileName}` | `myStream` | Base filename before versioning (standard) |
| `${RecordingStartTime}` | `2026-05-01-12.00.00.000-UTC` | Recording start time (standard) |
| `${SegmentNumber}` | `0` | Segment number (standard) |
| `${SegmentTime}` | `2026-05-01-12.00.00.000-UTC` | Segment creation time (standard) |
| `${ClassNames}` | `person_car` | Underscore-joined detected class names that triggered the recording |
| `${TrackingIds}` | `2_5` | Underscore-joined tracking IDs active when recording started |

The delegate is instantiated at recording start and receives a snapshot of the active tracking IDs and detected class names at that point.

## File Structure

```
src/main/java/com/wowza/wms/plugin/videointelligence/event/
    EventRecordingPlugin.java                # IVifEventListener + IStreamRecorderActionNotify
    EventRecordingFileVersionDelegate.java   # Custom file naming with detection tokens
src/main/js/
    EventRecordingPlugin.js                  # VIF Manager UI listener properties
```

## Edge Cases

| Scenario | Behavior |
|---|---|
| Stream unpublishes while recording | WSE stops the recorder automatically; `onStopRecorder` fires and sends webhook |
| Detection flickers (object disappears for a fraction of a second, or VIF emits an empty response or one with no tracking ID) | `lost_object_duration_ms` provides hysteresis -- the object must not be seen for the full configured wall-clock duration before it is considered lost |
| Many objects detected simultaneously | All share one recording; `activeObjects` holds all of them; recording continues until ALL are lost + post-detection timer |
| Back buffer exceeds available buffer | WSE caps at what's available in the stream buffer (~8s); no error |
| Recorder already exists for stream | Reuse / extend rather than create a second recorder |
| `onShutdown` called during active recording | Stop recorder, cancel timer, send stop webhook |
