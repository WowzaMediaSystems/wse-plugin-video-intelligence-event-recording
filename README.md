# wse-plugin-video-intelligence-&lt;template&gt;

> Template repository for building Wowza Streaming Engine (WSE) plugins for the
> Wowza Video Intelligence Framework (VIF). Click **Use this template** in
> GitHub (or fork), clone the new repo, then ask Claude Code to **"set up this
> template"** — see [CLAUDE.md](CLAUDE.md) for what it'll do.

&lt;Replace this paragraph with a one-paragraph description of what your plugin
does — the model it integrates with, the events it consumes, the data it
emits, and any external services it depends on.&gt;

> **Placeholders.** Anywhere you see `<template>` (kebab-case) or
> `<Template>` (PascalCase), substitute your plugin's name. Source-code
> identifiers (`template/` package, `TemplatePlugin` class) use the bare word
> `template` so the project builds before renaming — Claude's setup pass
> renames those too.

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
├── CLAUDE.md                   # Instructions for Claude to rename <template>
├── src/
│   ├── main/java/com/wowza/wms/plugin/videointelligence/event/<Template>Plugin.java
│   └── test/java/com/wowza/wms/plugin/videointelligence/event/<Template>PluginTest.java
├── examples/
│   └── <Template>Plugin.js     # Manager UI listener properties (deployed to wse.addon/listeners/)
└── .github/workflows/
    ├── ci.yml                  # Build + test on PR / push to main
    └── release.yml             # Publish to Maven Central on GitHub release
```

## Event methods

&lt;Document the VIF/WSE event methods your plugin overrides — when each fires,
what state is available, and what you do with it. Example template:&gt;

| Method        | When it fires                          | Notes |
| ------------- | -------------------------------------- | ----- |
| `onAppStart`  | Application start                       | Read configuration. |
| `onStreamCreate` | New stream is created on this app    | Attach per-stream listeners. |
| `onFrame`     | Each decoded video frame                | Hot path — keep work minimal. |
| `onShutdown`  | WSE shutdown / application unload       | Release resources. |

## Configuration

&lt;List configuration properties consumed by your plugin. The conventional
location is `Application.xml` &gt; `Properties`, or a VIF-managed config blob.&gt;

| Property       | Type    | Default | Description |
| -------------- | ------- | ------- | ----------- |
| `apiKey`       | String  | —       | Credential for the upstream service. |
| `threshold`    | Number  | `0.5`   | Confidence cutoff for emitted events. |

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

## License

Wowza Public License 1.0 — see [LICENSE](LICENSE).
