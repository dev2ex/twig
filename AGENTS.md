# Twig — Engineering Working Guide

A size-first, dual-pane Android file manager: plain Kotlin + XML Views, no Material
library, minimal dependencies. Start with [README.md](README.md) for an introduction.
This file holds shared working rules and navigation to detailed references.
Machine-specific settings and overrides live in the uncommitted `AGENTS.local.md`;
read it when present. Keep one shared guide rather than per-agent copies.

## Documentation maintenance

- Keep this file around 150–200 lines: global rules, common commands and task links.
  Put feature behavior and implementation details in the matching
  [engineering reference](docs/engineering/); add a short task link here only when needed.
- Before changing a subsystem, read its engineering reference and matching lessons
  below. In particular, read the lessons before touching terminal, thumbnails,
  TS demuxing, privileged access or WiFi sharing code.
- Update references in place and remove superseded statements. Use Git history for
  the development timeline; do not append a feature summary here after each change.
- Add a lesson only for an independent, non-obvious failure with a lasting prevention
  rule. Refine the existing entry for a recurring incident. Routine implementation,
  requirement changes and successful tests do not need lesson entries.
- Put routine validation results in commit or PR descriptions. Retain a separate test
  report only for a reproducible experiment or substantial performance comparison.
- Release changelogs accumulate by version; ordinary guides are maintained references.
  Avoid duplicating explanations between this file, references, lessons and reports.

## Build and verify

```bash
# Full includes RAR; libre is the F-Droid flavor. Release uses R8/resource shrinking.
./gradlew :app:assembleFullRelease

# Optional NFS build (excluded from both flavors by default)
./gradlew :app:assembleFullRelease -PwithNfs=true

# Always verify the actual APK version before installing
"$ANDROID_HOME"/build-tools/36.1.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep -E "versionName|versionCode"

# Example JVM module tests; choose checks appropriate to the changed modules
./gradlew :fs-network:test
```

- Never trust a pipeline's exit code alone: a failed build can leave the previous APK.
  Confirm `versionName` is the build just made before installing.
- Test builds do not bump `versionCode` or `versionName`. Small UI iterations need
  a compile/build check, such as `:app:compileFullReleaseKotlin`.
- Default combined APKs contain `arm64-v8a` + `x86_64`; per-ABI releases also support
  `armeabi-v7a`. See the build reference for split options.
- Keep the configured signing key. Release reads `keystore.properties`; without it
  the APK is unsigned, with no silent debug-key fallback. `debugSign` in
  `local.properties` selects debug signing; `-PdebugSign=true|false` overrides it.
  Updating an installed app requires a matching signing key.
- Before release work, read [build and release](docs/engineering/build-and-release.md)
  and [fastlane rules](fastlane/README.md). Write the changelog first: new or newly
  possible behavior means a minor release; corrections or internal-only changes mean
  a patch. State crash, data-loss or security urgency in the first sentence.
- Bump versions only for a release that goes out. A `versionCode` bump must include
  `fastlane/metadata/android/{en-US,zh-CN}/changelogs/<versionCode>.txt` in the same
  commit, at most 500 characters per locale.
- Previews use a committed prerelease version and bilingual changelogs, then
  `tools/preview.sh`; stable releases use `tools/release.sh`. Both verify APKs and
  respect signing configuration. `--publish` pushes and publishes; preview publishing
  creates a Pre-release without changing Latest. `--offline` uses cached dependencies.
  Device, distribution and notification preferences belong in `AGENTS.local.md`.

## Architecture

Every backend presents `FileSystem` + `XFile`; cross-backend copy/move uses
`CopyEngine` with filesystem `openInput()/openOutput()` streams. Each `XFile` has a `scheme`, resolved through
`FsRegistry`; multiple servers of one kind have distinct schemes. Add a backend via
its filesystem implementation, keeping UI and copying independent of backend type.

Capabilities shared by every backend belong in `FileSystem`. Optional capabilities
use separate interfaces and safe casts with a fallback. See the
[filesystem contract and module map](docs/engineering/architecture.md) before changing
backends, capability interfaces or Unix metadata.

Main has two resident `PaneFragment`s; `PaneViewModel` owns navigation and expansion,
`FileAdapter` renders rows. Trees expand and collapse in place. Detailed behavior is
in the task references below.

## Conventions

- **Size first**: avoid heavyweight dependencies; measure APK impact before adding one.
- **Filesystem APIs**: `resolve()` navigates and may return an optimistic stub;
  `stat()` reads real metadata; `exists()` tests existence. For many entries in one
  directory, list once. Match paths rather than display names. Never interpret a
  failed listing as empty, especially for comparison, sync or deletion.
- **R8**: JNI binds by name; preserve native methods and class names. Read
  [app/proguard-rules.pro](app/proguard-rules.pro) before changing JNI, Termux or
  BouncyCastle calls.
- **Settings**: keep display preferences independent and expose them on the settings
  page. Only frequent toggles get a quick menu entry. Layout-affecting preferences
  participate in `Prefs.uiSignature`; include persistent configuration in backups.
  See [UI and settings](docs/engineering/ui-and-settings.md) for recreation behavior.
- **Commits**: English Conventional Commits, `type: task name — summary`.
- **Tests**: JVM tests live in the module's `test` source set. Run the checks appropriate
  to the change; Android UI testing follows the machine-local working conventions.

### Language and user-facing text

- Repository documentation, comments, commit messages and test names/messages use
  English. Conversation language follows the user's preference.
- In `:app`, use `strings.xml` with default English and all supported locale directories,
  keeping identical translatable `name` sets. This includes helpers with a Context,
  such as `FileInfo`, `GitVfs` and `SafFileSystem`.
- Native + English language labels (`language_zh`, `language_en`, etc.) are
  `translatable="false"` and live only in default `values/strings.xml`;
  `language_system` is translated. Keep `LanguagePref` and `xml/locales_config.xml`
  synchronized when adding a language.
- Pure JVM modules (`core-fs`, `fs-*`, `git-lite`) have no Context or R; messages are
  English. Keep fast JVM tests; future localization should use error codes and UI
  lookups rather than turning these modules into Android libraries.

## Engineering references — read for the task

| Area | Reference |
|---|---|
| Filesystem contracts, capabilities, Unix metadata, modules | [Architecture](docs/engineering/architecture.md) |
| Build, signing, versions, preview/stable release workflow | [Build and release](docs/engineering/build-and-release.md) |
| Pane layout, tree branches, counts, history, directory overrides, grid, properties, treemap | [Panes and rows](docs/engineering/panes.md) |
| Selection, relative paths, copy/move/compress, compare, pickers, intents, sharing files, trash, retries, feedback | [Operations](docs/engineering/operations.md) |
| Bookmarks, search, customization, domain settings, permissions, WebView task lifecycle | [Websites](docs/engineering/websites.md) |
| Viewers, playback, thumbnails, app icons, Git, terminal navigation, highlighting | [Media and tools](docs/engineering/media-and-tools.md) |
| Themes, action panel, settings, private space, root configuration, security entry points | [UI and settings](docs/engineering/ui-and-settings.md) |
| HTTP/WebDAV service, discovery, sharing scope, protocol behavior | [WiFi sharing](docs/engineering/wifi-sharing.md) |
| SQLite consistency, limits, future editing boundaries | [SQLite browser](docs/sqlite-browser.md) |
| User gestures and buttons | [English](docs/buttons-and-gestures.md) / [Chinese](docs/buttons-and-gestures.zh-CN.md) |

## Hard-won lessons — read before changing the matching area

Lessons record real failures, their causes and lasting prevention rules. Keep this
index to navigation hints; write incident details in the matching file.

| Area | Lessons |
|---|---|
| SSH/SFTP, SSHJ, remote terminal | [SSH and terminal](docs/lessons/ssh-and-terminal.md) |
| Local/privileged shell, fonts, zoom, directory reporting | [Local terminal](docs/lessons/terminal-local.md) |
| Root/Shizuku fallback and privileged processes | [Privileged access](docs/lessons/privileged-access.md) |
| Thumbnail caches, container parsing, network frame grabbing | [Thumbnails](docs/lessons/thumbnails.md) |
| Media playback, buffering, TS/M2TS/AVI, playlists, episodes | [Media playback](docs/lessons/media-playback.md) |
| Archive reading/writing, encryption, external opening, path safety | [Archives](docs/lessons/archives.md) |
| RAR licensing, full/libre, F-Droid, reproducibility | [RAR and F-Droid](docs/lessons/rar-and-fdroid.md) |
| HTTP/WebDAV serving and sharing safety | [WiFi sharing](docs/lessons/wifi-sharing.md) |
| Jellyfin/Emby tree, playback, lyrics and image differences | [Jellyfin and Emby](docs/lessons/jellyfin-emby.md) |
| S3 encoding, signatures, pagination, multipart uploads | [S3](docs/lessons/s3.md) |
| Encryption, configuration backup, app lock, exported entry points | [Security](docs/lessons/security.md) |
| SAF URI identity, grants, random access, external editors | [SAF](docs/lessons/saf.md) |
| Recoverable deletion, recovery records, purge safety | [Trash](docs/lessons/trash.md) |
| Shared-profile/removable storage, detection and capacity | [Storage](docs/lessons/storage.md) |
| Tree identity, async expansion, pinning, selection, adapter and window behavior | [Tree and adapter](docs/lessons/tree-and-adapter.md) |
| Website background execution and foreground-service ownership | [Website background](docs/lessons/website-background.md) |
| Text decoding/encoding, editor and highlighting limits | [Text handling](docs/lessons/text-handling.md) |
| Git layouts, worktrees, metadata and cache invalidation | [Git viewer](docs/lessons/git-viewer.md) |
| Other backends, connection order, launcher/provider compatibility | [Other backends](docs/lessons/other-backends.md) |
