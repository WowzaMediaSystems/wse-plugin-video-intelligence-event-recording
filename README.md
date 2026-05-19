# wse-plugin-video-intelligence-event-recording

A Wowza Streaming Engine plugin that creates MP4 recordings when objects are detected by the Video Intelligence Framework (VIF). It implements `IVifEventListener` and uses the WSE `StreamRecorder` API to capture a configurable back-buffer plus post-detection window, with one continuous file per object-presence event. Webhooks fire on detection start and stop.

## Requirements

- JDK 17+
- Wowza Streaming Engine with the Video Intelligence Framework enabled
- `com.wowza.wms.plugin.videointelligence:wse-plugin-video-intelligence:0.5.2`
  (declared as a `compileOnly` dependency; provided by WSE at runtime)

## Repository structure

```
.
├── build.gradle                # Gradle build (Java 17, maven-publish, signing)
├── settings.gradle
├── gradle.properties           # title / projectName for jar naming + POM
├── gradle/wrapper/             # Gradle wrapper
├── gradlew, gradlew.bat
├── build.sh                    # Wraps gradle in wowza/wse-plugin-builder Docker
├── VERSION                     # Single source of truth for project version
├── DESIGN.md                   # Architecture notes (immediate flow, webhook timing, keyframe walk)
├── src/
│   ├── main/java/com/wowza/wms/plugin/videointelligence/event/EventRecordingPlugin.java
│   ├── main/java/com/wowza/wms/plugin/videointelligence/event/EventRecordingFileVersionDelegate.java
│   └── main/js/EventRecordingPlugin.js   # Manager UI listener properties (deployed to wse.addon/listeners/)
└── .github/workflows/
    ├── ci.yml                  # Build + test on PR / push to main
    └── release.yml             # Publish to Maven Central on GitHub release
```

## Configuration

The plugin is configured in `video-intelligence.json` under `vif_event_listeners`. **This configuration is only required if you're not initially configuring the plugin via the VIF Manager UI** -- the easiest path is to add the listener through the UI, which writes the configuration into `video-intelligence.json` automatically.

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
| `recording_name_template` | text | `${SourceStreamName}_${RecordingStartTime}` | File naming template. See [Recording File Name Tokens](#recording-file-name-tokens) below. |
| `record_stream_name` | text | (empty) | Regex pattern to match the stream name to record. Empty records the source stream. To record the VIF transcoder output (with overlays), use `.*-vi$`. The pattern is matched against published streams in the application; the first match is used. |
| `output_path` | text | (empty) | Directory for recorded files. Empty uses the WSE application default. |
| `webhooks` | boolean | `true` | Send webhooks on detection start/stop. |

### Recording File Name Tokens

The `recording_name_template` supports the following tokens:

| Token | Example | Description |
|---|---|---|
| `${SourceStreamName}` | `myStream` | Stream name |
| `${BaseFileName}` | `myStream` | Base filename before versioning |
| `${RecordingStartTime}` | `2026-05-01-12.00.00.000-UTC` | Wall-clock time of the first frame in the recording (accounts for `back_buffer_time`) |
| `${SegmentNumber}` | `0` | Segment number |
| `${SegmentTime}` | `2026-05-01-12.00.00.000-UTC` | Segment creation time |
| `${ClassNames}` | `person_car` | Underscore-joined detected class names that triggered the recording |
| `${TrackingIds}` | `2_5` | Underscore-joined tracking IDs active when recording started |

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

2. Copy the JS file into the VIF Manager listeners directory of your WSE Manager install:

   ```bash
   cp src/main/js/EventRecordingPlugin.js ${WSEM_HOME}/webapps/ROOT/wse-plugins/server/vif/listeners/
   ```

3. Restart Wowza Streaming Engine and Wowza Streaming Engine Manager.

4. Open the Manager UI, edit the stream's VIF configuration, and add `EventRecordingPlugin` to the event listeners.

## Building

The canonical build runs inside the `wowza/wse-plugin-builder:4.10.0` Docker
image, which provides the Wowza Streaming Engine libs needed at compile
time (`wms-server`, etc.):

```sh
./build.sh                 # equivalent to: ./build.sh . build
./build.sh . clean         # other gradle tasks: ./build.sh <dir> <task>
```

To build outside Docker, point `wseLibDir` at a local WSE installation's
`lib/` directory:

```sh
./gradlew build -PwseLibDir=/path/to/WowzaStreamingEngine/lib
```

Either way, artifacts land in `build/libs/`. Drop the resulting JAR into
`<WSE>/lib/` and restart WSE.

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
