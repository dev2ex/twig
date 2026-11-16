# Build, signing and release reference

Shared rules and the task index are in [AGENTS.md](../../AGENTS.md).
Read the matching [lessons](../lessons/) before changing this area.
Update this reference in place when behavior changes.

All commands and code paths below are relative to the repository root.

## Build and verify

```bash
# Build release (R8 + resource shrinking; build-tools pinned to 36.1.0)
#    ★ There are flavors: full includes RAR and is the one you install; libre is the
#      F-Droid build.
./gradlew :app:assembleFullRelease

# Optional NFS build (full/libre both exclude NFS by default; APK suffix: -nfs.apk)
./gradlew :app:assembleFullRelease -PwithNfs=true

# Verify the version with aapt (★ never trust the pipeline's exit code —
# a failed build can still leave you installing the previous APK)
"$ANDROID_HOME"/build-tools/36.1.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep -E "versionName|versionCode"
```

- **Verification discipline**: before installing, always confirm with aapt that
  `versionName` is the build you just made. A failed build once got masked by a
  pipeline `exit 0`, and an old APK shipped as a new one (the 0.20.3 == 0.20.2 incident).
- **Version numbers**: `versionCode` (+1) and `versionName` in `app/build.gradle.kts`
  move **only for a release that actually goes out**, never for a test build.
  Test builds need no version of their own. Keep the configured signing key;
  installing over an existing app requires the same key. Spending version numbers on
  builds nobody receives is how 1.9.1 and 1.9.2 became versions that never existed.
  Small intermediate
  iterations within one round of polishing (tweaking a corner radius or spacing back and
  forth) only need a compile check
  (`compileFullReleaseKotlin` / `assembleFullRelease`), not a version bump each time.
  ★ **Which component moves is decided by the changelog, not by the commit types.**
  Write the changelog first, then read it back: any sentence saying something is *new* or
  *now possible* makes it a **minor**; a changelog that only corrects behaviour, or only
  records an internal change, makes it a **patch**. The rule used to be "fixes bump the
  patch component, features bump the minor one", and it judged the wrong thing — Twig is
  an app with no callers, so "what kind of work was this" tells a user nothing. It also
  had no answer for a `build:` or `chore:` release, and it filed 1.8.5 (whose changelog
  says "No user-visible change") in the same tier as 1.9.3 (a crash on Android 9 and 10).
  Reading the changelog instead costs nothing: it has to be written anyway, it is the one
  text the user actually sees, and build or chore work lands on patch by itself because it
  cannot produce a "this is new" sentence. What no component can express is **urgency** —
  F-Droid has no "update now" flag, so a crash, data-loss or security fix has to say so in
  the changelog's first sentence.
  ★ **Bumping `versionCode` means writing a changelog in the same commit**:
  `fastlane/metadata/android/{en-US,zh-CN}/changelogs/<the new versionCode>.txt`,
  500 characters or less, and the file is named after the **versionCode, not the version
  name**. That is what F-Droid shows as the release note — miss it and that release ships
  with no description. The rest of the store copy and images live under `fastlane/`,
  rules in `fastlane/README.md`.

What happens after that — which device to install on, where builds get published, how
you get told about it — is every developer's own preference, so it is deliberately not
in this file. The setup used on the machine this was written on lives in
`AGENTS.local.md`, which is not committed.

For GitHub previews, commit a prerelease version such as `1.16.0-beta.1` and its
English/Chinese changelogs, then run `tools/preview.sh` to build and verify full/libre
APKs. Add `--publish` to push the current branch/tag and publish a Pre-release without
changing Latest. `--notes-file PATH` supplies detailed release notes; `--offline`
uses cached Gradle dependencies. The script respects the configured signing key and
uses `tools/verify-apk.py` before publishing. Stable releases use `tools/release.sh`.

## Build environment

- **Release is signed with our own keystore**, whose credentials are read from
  `keystore.properties` in the repository root (path and passwords are in
  `AGENTS.local.md`; neither the key nor that file is committed).
  ★ When the file is missing, release produces an **unsigned** APK rather than silently
  falling back to the debug config: the AOSP debug key is public, so anyone could sign an
  APK that installs over the real one, and a silent fallback would bury that trap again
  with no warning. An unsigned APK is also exactly what F-Droid wants (it signs its own,
  or does a reproducible-build comparison).
  ★ The `import java.util.Properties` at the top of `app/build.gradle.kts` cannot be
  omitted: in the Kotlin DSL, `java` resolves to Gradle's java extension, so writing
  `java.util.Properties` inline fails with "Unresolved reference: util".
  ★ `INSTALL_PARSE_FAILED_NO_CERTIFICATES` when installing means exactly one thing:
  the build found no `keystore.properties` and produced an unsigned APK.
  ★ **Changing which key signs a build makes it refuse to install over the old one**
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — the previous install has to be uninstalled
  first, taking its data with it. For a debug-signed installation,
  `debugSign=true` in `local.properties`
  selects the AOSP debug key. Keep the existing signing configuration unless a key
  change is requested; a debug key cannot update a release-signed installation.
  `-PdebugSign=true|false` overrides the file.
- Default combined APKs contain `arm64-v8a` + `x86_64`. `-PabiSplit=true` produces
  separate APKs for those ABIs plus `armeabi-v7a`; `-Pabis=<comma-separated ABIs>`
  restricts the architectures to build. The stable release script includes the
  separate full-flavor APKs. For a store listing, use the appropriate flavor's
  bundle task to produce an AAB for device-specific delivery.
