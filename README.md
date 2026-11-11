# wse-plugin-video-intelligence-event-recording

A Wowza Streaming Engine plugin that creates MP4 recordings when objects are detected by the Video Intelligence Framework (VIF) — on live streams and on VOD file jobs. It implements `IVifEventListener`; one continuous file per object-presence event, with a configurable back-buffer before the first detection and a post-detection window after the last.

- **Live stream**: the WSE `StreamRecorder` API records the stream as the event unfolds; the back-buffer comes from the stream's packet buffer. Webhooks fire on detection start and stop.
- **VOD file job**: the event is cut out of the job's source file (video and audio, no re-encode) once its bounds are known; the back-buffer is any length. A webhook per clip reports the file.

The trigger is the same on both hosts and runs on the media timeline (stream time live, file time on a job), so one listener configuration produces the same clips whether a file is analysed faster or slower than real time.

## Requirements

- JDK 17+
- Wowza Streaming Engine with the Video Intelligence Framework enabled
- `wse-plugin-video-intelligence` 1.2.x (the VIF controller jar; `compileOnly`, provided by WSE at runtime)
- mp4parser `isoparser`/`muxer` 1.9.56 at runtime for VOD clips — the VIF controller already ships them in the Engine's `lib/`

## Repository structure

```
.
├── build.gradle                # Gradle build (Java 17, maven-publish, signing)
├── settings.gradle
├── gradle.properties           # title / projectName for jar naming + POM
├── gradle/wrapper/             # Gradle wrapper
├── gradlew, gradlew.bat
├── build.sh                    # Wraps gradle in wowza/wse-plugin-builder Docker
├── libs/                       # (untracked) a local wse-plugin-video-intelligence-*.jar to compile against
├── VERSION                     # Single source of truth for project version
├── DESIGN.md                   # Architecture notes (trigger, live recorder, VOD cut, webhooks)
├── src/
│   ├── main/java/com/wowza/wms/plugin/videointelligence/event/EventRecordingPlugin.java
│   ├── main/java/com/wowza/wms/plugin/videointelligence/event/EventRecordingFileVersionDelegate.java
│   ├── main/java/com/wowza/wms/plugin/videointelligence/eventrecording/   # trigger, backends, naming
│   ├── main/java/com/wowza/wms/plugin/videointelligence/eventrecording/clip/   # the MP4 cut
│   ├── main/js/EventRecordingPlugin.js   # Manager UI listener properties (deployed to wse.addon/listeners/)
│   └── test/                             # JUnit 5; the 4 s MP4 fixture the VOD tests cut
└── .github/workflows/
    ├── ci.yml                  # Build + test on PR / push to main
    └── release.yml             # Publish to Maven Central on GitHub release
```

## Configuration

The plugin is configured as a listener of a VIF stream configuration. The easiest path is the VIF Manager UI, which offers `EventRecordingPlugin` in the listener editor once the `.js` file is installed, and writes the configuration into `video-intelligence.json` under `vif_event_listeners`:

```json
"vif_event_listeners": {
  "EventRecordingPlugin": {
    "class_name": "EventRecordingPlugin",
    "methods": ["immediate"],
    "properties": {
      "class_names": ["person", "car"],
      "min_count": 1,
      "require_track": false,
      "back_buffer_time": 5000,
      "post_detection_duration": 5000,
      "lost_object_duration_ms": 3000,
      "merge_gap_ms": 0,
      "max_clip_ms": null,
      "recording_name_template": "${SourceStreamName}_${RecordingStartTime}",
      "record_stream_name": "",
      "output_path": "",
      "webhooks": true
    }
  }
}
```

Over the v2 REST API the same listener is a `custom` entry of the config's `listeners`:

```json
"EventRecording": {
  "type": "custom", "class_name": "EventRecordingPlugin", "trigger": "immediate",
  "enabled": true, "min_confidence": 0.5,
  "properties": { "class_names": ["person", "car"], "back_buffer_time": 5000,
                  "post_detection_duration": 5000, "lost_object_duration_ms": 3000 }
}
```

### Properties Reference

| Property | Type | Default | Description |
|---|---|---|---|
| `class_names` | string_list | `["all"]` | Object classes that trigger recording. `"all"` matches any class. |
| `min_count` | number | `1` | How many detections of those classes one response needs before it counts as presence. |
| `require_track` | boolean | `false` | Count only detections that carry a tracking ID (needs tracking on the detector). Off, any detection of the classes counts. |
| `lost_object_duration_ms` | number | `3000` | How long the classes must be absent before the event ends. Measured on the media timeline, so it is the same length of video live and on a job. |
| `merge_gap_ms` | number | `0` | Two events closer than this become one recording. |
| `max_clip_ms` | number | (none) | Ends an event that never stops after this long and starts the next one. |
| `back_buffer_time` | number | `5000` | Milliseconds of video before the first detection. Live it comes from the WSE incoming stream buffer (~8s available) and starts on a keyframe; on a job it is cut from the file and can be any length. |
| `post_detection_duration` | number | `5000` | Milliseconds to keep recording after the event ends. |
| `recording_name_template` | text | `${SourceStreamName}_${RecordingStartTime}` | File naming template. See [Recording File Name Tokens](#recording-file-name-tokens) below. |
| `record_stream_name` | text | (empty) | Live only. Regex pattern to match the stream name to record. Empty records the source stream. To record the VIF transcoder output (with overlays), use `.*-vi$`. Ignored on a job. |
| `output_path` | text | (empty) | Directory for recorded files. Empty uses the WSE application default live, and the source file's directory on a job. |
| `webhooks` | boolean | `true` | Send webhooks: `detection.start`/`detection.stop` per tracked object live, `recording.started`/`recording.stopped` per clip on a job. |

The listener's own `min_confidence` (the `confidence_threshold` of the `vif_event_listeners` entry) filters detections before they reach the plugin.

### Recording File Name Tokens

The `recording_name_template` supports the following tokens:

| Token | Live | VOD job | Description |
|---|---|---|---|
| `${SourceStreamName}` | `myStream` | `race1` | Stream name, or the source file's name without extension |
| `${BaseFileName}` | `myStream` | `race1` | Base filename before versioning |
| `${RecordingStartTime}` | `2026-05-01-12.00.00.000-UTC` | `00.03.39.800` | Wall-clock time of the first frame live (accounts for `back_buffer_time`); the clip's offset into the file on a job |
| `${SegmentNumber}` | `0` | `0` | Segment number |
| `${SegmentTime}` | `2026-05-01-12.00.00.000-UTC` | (now) | Segment creation time |
| `${ClassNames}` | `person_car` | `person_car` | Underscore-joined classes seen during the event |
| `${TrackingIds}` | `2_5` | `2_5` | Underscore-joined tracking IDs seen during the event |

A name that already exists gets a `_1`, `_2` suffix rather than overwriting the file.

### VOD file jobs

A job submitted to `/v2/vif/vod/jobs` runs the listeners of the configuration it names (a stream group config, or one given inline). The plugin needs nothing beyond that:

- The clip is cut from the job's source file by media time: `[first detection − back_buffer_time, last detection + lost_object_duration_ms + post_detection_duration]`, the start snapped back to the previous keyframe, clamped to the file. Video and audio are copied sample for sample.
- Nothing is written while an event is open. The cut happens once the job has analysed past the post-detection window, or when the job completes; a job that fails or is cancelled writes nothing.
- Clips land next to the source file unless `output_path` says otherwise, and are reported by a log line and, with `webhooks` on, a `recording.started` + `recording.stopped` pair carrying `job_id`, `source_file` and the file path.
- A job resumed after an interruption re-initialises the listener; an event that straddled the interruption yields two clips.
- Overlays are a live transcoder feature; a VOD clip is the plain source.

## Installation

The plugin consists of two files:

- **`wse-plugin-video-intelligence-event-recording-<version>.jar`** -- the compiled plugin, placed in WSE's lib path.
- **`EventRecordingPlugin.js`** -- the VIF Manager UI definition (source at `src/main/js/EventRecordingPlugin.js`), placed in the VIF listeners path.

Both files are produced by the Gradle build (see [Building](#building)) and can also be downloaded from the project releases.

### Docker Compose (Wowza Video Intelligence Framework runtime)

The stock `docker-compose.yaml` in the VIF runtime repo does **not** include volume mounts for `./wse.addon/`. If you have not added any plugins before, the `wse.addon` folder won't exist yet and the volume mounts will be missing -- you'll need to create the folder structure and add the mounts.

1. From the runtime repo root (next to `docker-compose.yaml`), create the directory structure:

   ```bash
   mkdir -p wse.addon/lib wse.addon/listeners
   ```

2. Copy the plugin files into those directories:

   ```bash
   cp build/libs/wse-plugin-video-intelligence-event-recording-<version>.jar wse.addon/lib/
   cp src/main/js/EventRecordingPlugin.js                                       wse.addon/listeners/
   ```

3. Edit `docker-compose.yaml` and add the two volume mounts. The first goes under the `wse` service's `volumes:` list, the second under the `manager` service's `volumes:` list:

   ```yaml
   services:
     wse:
       volumes:
         # ... existing mounts ...
         - ./wse.addon/lib:/usr/local/WowzaStreamingEngine/lib.addon
     manager:
       volumes:
         # ... existing mounts ...
         - ./wse.addon/listeners:/usr/local/tomcat/webapps/ROOT/wse-plugins/server/vif/listeners.addon
   ```

   (If you've already added other plugins, these mounts may already be present -- in which case just drop the files into the existing folders and skip this step.)

4. Restart the stack so the new mounts take effect:

   ```bash
   docker compose up -d --force-recreate wse manager
   ```

5. Open the VIF Manager UI, edit the stream's VIF configuration, and add `EventRecordingPlugin` to the event listeners. The fields from the Properties Reference table will be available in the UI.

### Standalone Wowza Streaming Engine

For a non-Docker WSE install:

1. Copy the JAR into the WSE lib directory:

   ```bash
   cp build/libs/wse-plugin-video-intelligence-event-recording-<version>.jar ${WSE_HOME}/lib/
   ```

2. Copy the JS file into the VIF Manager listeners directory of your WSE Manager installation:

   ```bash
   cp src/main/js/EventRecordingPlugin.js ${WSEM_HOME}/temp/webapps/enginemanager/wse-plugins/server/vif/listeners
   ```
   (The `temp` directory may not exist yet if the WSE Manager service has not been started yet.)


3. Restart Wowza Streaming Engine and Wowza Streaming Engine Manager.

4. Open the Manager UI, edit the stream's VIF configuration, and add `EventRecordingPlugin` to the event listeners.

## Building

The canonical build runs inside the `wowza/wse-plugin-builder` Docker image, which provides the Wowza Streaming Engine libs needed at compile time (`wms-server`, etc.):

```sh
./build.sh                 # equivalent to: ./build.sh . build
./build.sh . clean         # other gradle tasks: ./build.sh <dir> <task>
```

The VIF controller jar the listener API lives in is resolved in this order: `-PvicJar=/path/to/wse-plugin-video-intelligence-<version>.jar`, a jar dropped in `libs/` (untracked), or the published `wse-plugin-video-intelligence:1.2.0` artifact.

To build outside Docker, point `wseLibDir` at a local WSE installation's `lib/` directory:

```sh
./gradlew build -PwseLibDir=/path/to/WowzaStreamingEngine/lib -PvicJar=/path/to/wse-plugin-video-intelligence-1.2.1.jar
```

`build` runs the unit tests: the trigger, the MP4 cut and the listener on a file job, over the 4 s fixture in `src/test/resources`. The Engine's own jars from `wseLibDir` are on the test runtime classpath because the tests stand in for Engine interfaces.

Either way, artifacts land in `build/libs/`. Drop the resulting JAR into `<WSE>/lib/` and restart WSE.

## Releasing

Releases are cut by creating a GitHub Release (which fires
`release: published`). The `release.yml` workflow builds the artifacts in
the `wse-plugin-builder` Docker image, then runs `./gradlew publish`
natively against the Maven Central Portal.

Required GitHub secrets:

- `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` — user token generated
  at <https://central.sonatype.com/> under **View Account → Generate User
  Token**
- `MAVEN_CENTRAL_GPG_SIGNING_KEY` — ASCII-armored GPG private key
- `MAVEN_CENTRAL_GPG_SIGNING_PASSWORD` — GPG key passphrase

After the workflow uploads, the deployment shows up under **Publishing →
Deployments** in the Central Portal. Enable auto-publish on the
`com.wowza` namespace (or click **Publish** there manually) to push it to
Maven Central.

To cut a release:

1. Bump `VERSION` on `main`:
   ```sh
   echo "1.0.0" > VERSION
   git commit -am "Release 1.0.0" && git push
   ```
2. On GitHub, **Releases → Draft a new release**, create tag `v1.0.0`,
   publish. The workflow handles the rest.

## See Also

- [`DESIGN.md`](DESIGN.md) -- design document with internal architecture, the `immediate()` flow, webhook payload structure, and the keyframe-walk algorithm used to align the recording start time with the back buffer.

## License

Wowza Public License 1.0 — see [LICENSE](LICENSE).
