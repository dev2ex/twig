#!/usr/bin/env bash
#
# Tag the current commit and publish a GitHub release.
#
# Builds the five APKs a release carries, checks every one of them, writes the
# release notes from the changelog, and only then tags and publishes.
#
#   tools/release.sh              build, verify, show what would be published
#   tools/release.sh --publish    the same, then tag, push and create the release
#   tools/release.sh --tag v1.16.0  rebuild an existing tag without publishing
#   Add --offline to use downloaded dependencies without enabling build caches.
#
# The default is a rehearsal on purpose: a pushed tag and a published release are
# awkward to take back, and everything that can fail (a stale APK, a missing
# changelog, an unsigned build) fails before anything leaves the machine.
#
# The version comes from app/build.gradle.kts — bump it there, in its own commit,
# together with the changelog. This script never edits it.
#
# Optional environment:
#   TWIG_RELEASE_MIRROR     directory to also copy the APKs into
#   TWIG_RELEASE_URL_BASE   URL prefix printed for the mirrored copies
#   TWIG_SIGNER_DN          expected certificate subject (default CN=Twig, O=Twig)
#   TWIG_JAVA_HOME          JDK to build with (default: a JDK 21 if one is found)
#   TWIG_GH_REMOTE          git remote to push to (default: github)

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

PUBLISH=0
SOURCE_TAG=
GRADLE_ARGS=(--no-build-cache --no-configuration-cache --rerun-tasks -Pkotlin.incremental=false)
while [ "$#" -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1 ;;
    --offline) GRADLE_ARGS+=(--offline) ;;
    --tag)
      [ "$#" -ge 2 ] && [[ "$2" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] \
        || { echo '--tag requires a stable release tag such as v1.16.0' >&2; exit 2; }
      SOURCE_TAG=$2; shift ;;
    -h|--help) sed -n '2,/^set -/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

REMOTE=${TWIG_GH_REMOTE:-github}
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die()  { printf '\033[31mfatal:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preconditions

say "Checking the tree"

if [ -n "$SOURCE_TAG" ]; then
  [ "$PUBLISH" -eq 0 ] || die '--tag only rebuilds locally; it cannot publish or replace release assets'
  COMMIT=$(git rev-parse --verify "refs/tags/$SOURCE_TAG^{commit}") || die "missing tag $SOURCE_TAG"
else
  [ -z "$(git status --porcelain)" ] || die "working tree is dirty; commit or stash first"
  COMMIT=$(git rev-parse HEAD)
fi

# A real checkout preserves AGP's version-control metadata. An exported source
# archive would embed NO_VALID_GIT_FOUND and fail F-Droid's comparison.
mkdir -p "$ROOT/build/release-checkouts"
BUILD_ROOT=$(mktemp -d "$ROOT/build/release-checkouts/${SOURCE_TAG:-HEAD}.XXXXXX")
git clone --quiet --shared --no-checkout "$ROOT" "$BUILD_ROOT"
git -C "$BUILD_ROOT" checkout --quiet --detach "$COMMIT"
echo "  source       $COMMIT"
echo "  checkout     $BUILD_ROOT"
cd "$BUILD_ROOT"

VERSION_NAME=$(grep -oP '^\s*versionName = "\K[^"]+' app/build.gradle.kts)
VERSION_CODE=$(grep -oP '^\s*versionCode = \K\d+'    app/build.gradle.kts)
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || die "could not read the version from app/build.gradle.kts"
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'release version must be X.Y.Z'
TAG="v$VERSION_NAME"
[ -z "$SOURCE_TAG" ] || [ "$SOURCE_TAG" = "$TAG" ] || die "$SOURCE_TAG contains version $VERSION_NAME"
echo "  version      $VERSION_NAME ($VERSION_CODE)  →  tag $TAG"

# A tag that already points here means a previous run got interrupted; one that
# points elsewhere means the version was not bumped.
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  [ "$(git rev-parse "$TAG^{commit}")" = "$COMMIT" ] \
    || die "$TAG already exists and points at another commit — bump the version first"
  echo "  tag          $TAG already exists at HEAD, will reuse it"
fi

# F-Droid shows the changelog named after the versionCode, not the version name.
# A missing file is not an error there, it just ships with no release note.
for locale in en-US zh-CN; do
  f="fastlane/metadata/android/$locale/changelogs/$VERSION_CODE.txt"
  [ -f "$f" ] || die "missing $f"
  n=$(wc -m < "$f")
  [ "$n" -le 500 ] || die "$f is $n characters; F-Droid's limit is 500"
  echo "  changelog    $locale $n chars"
done

for f in README.md README.zh-CN.md THIRD_PARTY.md; do
  grep -q "versionCode $VERSION_CODE" "$f" \
    || die "$f does not mention versionCode $VERSION_CODE — the docs were not bumped with the version"
done
echo "  docs         README, README.zh-CN and THIRD_PARTY mention $VERSION_CODE"

[ -f "$ROOT/keystore.properties" ] || die "keystore.properties is missing; the build would come out unsigned"
# Keep the machine's signing choice and SDK path. Do not override debugSign.
for config in local.properties keystore.properties; do
  [ ! -f "$ROOT/$config" ] || ln -s "$ROOT/$config" "$BUILD_ROOT/$config"
done

if [ -n "${TWIG_JAVA_HOME:-}" ]; then
  JDK=$TWIG_JAVA_HOME
else
  JDK=$(ls -d /usr/lib/jvm/*21* 2>/dev/null | head -1 || true)
fi
[ -n "$JDK" ] && [ -x "$JDK/bin/java" ] || die "no JDK 21 found; set TWIG_JAVA_HOME"
echo "  jdk          $JDK"

OUT="$ROOT/build/release/$VERSION_NAME"
mkdir -p "$OUT"
if [ -n "$SOURCE_TAG" ]; then
  OUT=$(mktemp -d "$OUT/rebuild.XXXXXX")
fi
printf 'tag=%s\ncommit=%s\ncheckout=%s\n' "$TAG" "$COMMIT" "$BUILD_ROOT" > "$OUT/source.txt"

# ---------------------------------------------------------------------- build

# Two passes: AGP refuses to have both ndk.abiFilters and splits.abi set, so the
# combined APK and the per-ABI ones cannot come out of one invocation.
say "Building the combined APKs"
JAVA_HOME="$JDK" ./gradlew clean :app:assembleFullRelease :app:assembleLibreRelease "${GRADLE_ARGS[@]}"
cp app/build/outputs/apk/full/release/app-full-release.apk   "$OUT/Twig-$VERSION_NAME-full.apk"
cp app/build/outputs/apk/libre/release/app-libre-release.apk "$OUT/Twig-$VERSION_NAME-libre.apk"
# Preserve the combined APK mappings before the split build's clean task.
mkdir -p "$OUT/mapping"
for flavor in full libre; do
  mapping="app/build/outputs/mapping/${flavor}Release"
  [ ! -d "$mapping" ] || cp -R "$mapping" "$OUT/mapping/$flavor"
done

say "Building the per-ABI APKs"
JAVA_HOME="$JDK" ./gradlew clean :app:assembleFullRelease -PabiSplit=true "${GRADLE_ARGS[@]}"
for abi in arm64-v8a armeabi-v7a x86_64; do
  cp "app/build/outputs/apk/full/release/app-full-$abi-release.apk" \
     "$OUT/Twig-$VERSION_NAME-full-$abi.apk"
done

# --------------------------------------------------------------------- verify

say "Verifying"
python3 "$ROOT/tools/verify-apk.py" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT"/*.apk

# ---------------------------------------------------------------------- notes

LIBRE="Twig-$VERSION_NAME-libre.apk"
FULL="Twig-$VERSION_NAME-full.apk"
sha() { sha256sum "$OUT/$1" | cut -d' ' -f1; }
mb()  { printf '%.2f MB' "$(echo "scale=4; $(stat -c %s "$OUT/$1") / 1048576" | bc)"; }

NOTES="$OUT/notes.md"
{
  cat "fastlane/metadata/android/en-US/changelogs/$VERSION_CODE.txt"
  printf '\n\n'
  cat "fastlane/metadata/android/zh-CN/changelogs/$VERSION_CODE.txt"
  printf '\n'
} > "$NOTES"
cat >> "$NOTES" <<EOF

---

### Which download

| File | For | Size |
|---|---|---|
| **\`$FULL\`** | **Most people** — 64-bit ARM and x86_64 | $(mb "$FULL") |
| \`$LIBRE\` | Free software throughout, no RAR. What F-Droid builds | $(mb "$LIBRE") |
| \`Twig-$VERSION_NAME-full-arm64-v8a.apk\` | 64-bit ARM only, smaller download | $(mb "Twig-$VERSION_NAME-full-arm64-v8a.apk") |
| \`Twig-$VERSION_NAME-full-x86_64.apk\` | x86_64 only | $(mb "Twig-$VERSION_NAME-full-x86_64.apk") |
| \`Twig-$VERSION_NAME-full-armeabi-v7a.apk\` | ⚠️ 32-bit ARM — experimental, untested on hardware | $(mb "Twig-$VERSION_NAME-full-armeabi-v7a.apk") |

The \`full\` builds add read-only RAR through \`junrar\`, which is under the UnRAR licence and is not free software — see [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). The flavours differ in RAR alone.

All five are signed with the same key, so any one installs over any other. For automatic updates without a store, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) and set *Filter APKs by Regular Expression* to \`-full\\.apk\$\` (or \`-libre\\.apk\$\`).

### Verifying the download

\`\`\`
$(for f in "$LIBRE" "$FULL" "Twig-$VERSION_NAME-full-arm64-v8a.apk" "Twig-$VERSION_NAME-full-x86_64.apk" "Twig-$VERSION_NAME-full-armeabi-v7a.apk"; do
    printf '%-36s %s\n' "$f" "$(sha "$f")"
  done)

Signing cert   ${TWIG_SIGNER_DN:-CN=Twig, O=Twig}
Cert SHA-256   $("$ANDROID_HOME"/build-tools/"${TWIG_BUILD_TOOLS:-36.1.0}"/apksigner verify --print-certs "$OUT/$LIBRE" | grep -m1 'SHA-256 digest' | awk '{print $NF}')
\`\`\`
EOF

# ------------------------------------------------------------------- publish

if [ "$PUBLISH" -eq 0 ]; then
  say "Rehearsal only — nothing was pushed"
  echo "  APKs   $OUT"
  echo "  notes  $NOTES"
  if [ -n "$SOURCE_TAG" ]; then
    echo "  Rebuilt $TAG locally; existing release files were preserved."
    exit 0
  fi
  echo
  echo "  Publishing would: tag $TAG, push main and $TAG to '$REMOTE', create the release."
  echo "  Re-run with --publish when the notes above look right."
  exit 0
fi

say "Publishing"
cd "$ROOT"
[ "$(git rev-parse HEAD)" = "$COMMIT" ] || die 'HEAD changed during the build'
[ -z "$(git status --porcelain)" ] || die 'working tree changed during the build'
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
  || git tag -a "$TAG" -m "Twig $VERSION_NAME (versionCode $VERSION_CODE)"
git push "$REMOTE" HEAD
git push "$REMOTE" "$TAG"

gh release create "$TAG" --title "Twig $VERSION_NAME" --notes-file "$NOTES" \
  "$OUT/$FULL#Twig $VERSION_NAME (full — arm64-v8a + x86_64, start here)" \
  "$OUT/$LIBRE#Twig $VERSION_NAME (libre — free software, what F-Droid builds)" \
  "$OUT/Twig-$VERSION_NAME-full-arm64-v8a.apk#Twig $VERSION_NAME (full — arm64-v8a only)" \
  "$OUT/Twig-$VERSION_NAME-full-x86_64.apk#Twig $VERSION_NAME (full — x86_64 only)" \
  "$OUT/Twig-$VERSION_NAME-full-armeabi-v7a.apk#Twig $VERSION_NAME (full — armeabi-v7a, experimental)"

# The Binaries: URL in fdroiddata points at the libre APK by name; if that 404s,
# the reproducible-build comparison has nothing to compare against.
URL="https://github.com/dev2ex/twig/releases/download/$TAG/$LIBRE"
code=$(curl -sIL -o /dev/null -w '%{http_code}' "$URL")
[ "$code" = "200" ] || die "published, but $LIBRE is not downloadable (HTTP $code)"
echo "  libre APK downloadable (HTTP 200)"

if [ -n "${TWIG_RELEASE_MIRROR:-}" ]; then
  say "Mirroring"
  stamp=$(date +%y-%m-%d)
  for f in "$OUT"/*.apk; do
    base=$(basename "$f" .apk)
    dest="$TWIG_RELEASE_MIRROR/${base/Twig-$VERSION_NAME/Twig_${VERSION_NAME}}_release_$stamp.apk"
    cp "$f" "$dest"
    [ -n "${TWIG_RELEASE_URL_BASE:-}" ] && echo "  ${TWIG_RELEASE_URL_BASE%/}/$(basename "$dest")"
  done
fi

say "Done"
echo "  https://github.com/dev2ex/twig/releases/tag/$TAG"
echo
echo "  F-Droid automatically tracks stable tags and verifies the published libre APK."
echo "  Routine releases need no manual fdroiddata update or merge request."
echo "  Wait for F-Droid's update, build and publish jobs; inspect their logs if it fails."
