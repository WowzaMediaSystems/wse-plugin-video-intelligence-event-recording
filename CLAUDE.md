# Claude Code instructions for this template

This repository is a **template** for Wowza Streaming Engine (WSE) plugins
that integrate with the Wowza Video Intelligence Framework (VIF). When a user
clones a copy and opens Claude Code in it, this file is loaded automatically.

## Trigger

If the user says any of:

- "set up this template"
- "rename the template"
- "scaffold a new plugin"
- "initialize this repo for <name>"
- anything else that clearly means "turn this template into a real plugin"

…run the **Setup procedure** below.

## Setup procedure

1. **Ask for the plugin name in kebab-case** if not already provided. Example:
   `captions-google-stt`, `moderation-aws-rekognition`, `analytics-internal`.
   Reject names containing uppercase, spaces, or characters outside
   `[a-z0-9-]`. The name must not start or end with a hyphen.

2. **Derive name variants:**

   | Variant     | Example for `captions-google-stt` | Used in |
   | ----------- | --------------------------------- | ------- |
   | kebab       | `captions-google-stt`             | repo name, artifactId, VIF id |
   | PascalCase  | `CaptionsGoogleStt`               | Java class names |
   | package     | `captionsgooglestt`               | Java package segment (lowercase, no hyphens) |

3. **Confirm the variants with the user before editing** (one-line summary,
   not a multi-question form). If they want a different package segment
   (e.g., `captions` instead of `captionsgooglestt`), honor it.

4. **Replace placeholders** across the repo. Use ripgrep to find every
   occurrence so nothing is missed:

   ```sh
   rg -l '<template>|<Template>|\btemplate\b|TemplatePlugin'
   ```

   Then apply these substitutions (case-sensitive):

   | Find                                                                                | Replace with                                                            |
   | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
   | `<template>` (documentation placeholders)                                           | `<kebab>`                                                               |
   | `<Template>` (documentation placeholders)                                           | `<Pascal>`                                                              |
   | `wse-plugin-video-intelligence-template` (in `settings.gradle`, examples, etc.)     | `wse-plugin-video-intelligence-<kebab>`                                 |
   | `video-intelligence-template` (in `gradle.properties` `projectName`)                | `video-intelligence-<kebab>`                                            |
   | `Wowza Video Intelligence Template` (in `gradle.properties` `title`)                | `Wowza Video Intelligence <Pascal>` (or a human-readable name per user) |
   | `com.wowza.wms.plugin.videointelligence.template` (ReleaseInfo import + `group` in `build.gradle` — **not** the plugin class's own package, which must stay `…event` so VIF short-names it) | `com.wowza.wms.plugin.videointelligence.<package>`                      |
   | `TemplatePluginTest` (do this row **before** `TemplatePlugin` to avoid double-edit) | `<Pascal>PluginTest`                                                    |
   | `TemplatePlugin` (class name and references)                                        | `<Pascal>Plugin`                                                        |

   Do **not** edit `LICENSE`, `gradle/wrapper/gradle-wrapper.jar`,
   `gradlew`, `gradlew.bat`, or anything under `.gradle/` / `build/`.

5. **Rename only the class files — keep them in the `…event` package.**
   The plugin class MUST stay in `com.wowza.wms.plugin.videointelligence.event`
   so VIF short-names it in `available_event_listeners`. Only `ReleaseInfo`'s
   namespace (driven by `build.gradle`'s `group`) is per-plugin.

   ```sh
   git mv src/main/java/com/wowza/wms/plugin/videointelligence/event/TemplatePlugin.java \
          src/main/java/com/wowza/wms/plugin/videointelligence/event/<Pascal>Plugin.java
   git mv src/test/java/com/wowza/wms/plugin/videointelligence/event/TemplatePluginTest.java \
          src/test/java/com/wowza/wms/plugin/videointelligence/event/<Pascal>PluginTest.java
   ```

6. **Update the README description.** Replace the `<Replace this paragraph
   with…>` block with a one-paragraph summary of the plugin (ask the user
   for the gist if you don't already have it).

7. **Update `gradle.properties`:** set `title` to a human-readable plugin
   name (e.g., `Wowza Video Intelligence Bird Watching`) and
   `projectName` to the kebab segment that follows `wse-plugin-` in the jar
   name. Use the `video-intelligence-<kebab>` form so the produced jar is
   `wse-plugin-video-intelligence-<kebab>.jar` (e.g.,
   `video-intelligence-bird-watching` →
   `wse-plugin-video-intelligence-bird-watching.jar`).

8. **Verify:** run `./build.sh` (requires Docker). If it fails, fix and
   re-run before reporting done. Note: `./gradlew build` without
   `-PwseLibDir=...` will fail — that's expected; the WSE libs live inside
   the builder image.

9. **Self-destruct:** delete this `CLAUDE.md` and remove the "Placeholders"
   callout from `README.md` — both are template-only. Tell the user the
   files are gone and the repo is ready for a first real commit.

## What NOT to do

- Don't bump `VERSION` away from `0.1.0` — that's the user's first release
  decision, not yours.
- Don't touch `LICENSE`. The Wowza Public License 1.0 stays.
- Don't add OSSRH/GPG secrets, configure GitHub Actions environments, or
  push tags. Those are deploy-time steps the user owns.
- Don't generate the user's plugin logic. The skeleton class is intentionally
  empty so the user knows where to start.
