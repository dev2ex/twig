#!/usr/bin/env bash
#
# Tag the current commit and publish a GitHub release.
#
# Builds the five APKs a release carries, checks every one of them, writes the
# release notes from the changelog, and only then tags and publishes.
#
#   tools/release.sh              build, verify, show what would be published
#   tools/release.sh --publish    the same, then tag, push and create the release
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
case "${1:-}" in
  --publish) PUBLISH=1 ;;
  -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  "") ;;
  *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
esac

REMOTE=${TWIG_GH_REMOTE:-github}
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die()  { printf '\033[31mfatal:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preconditions

say "Checking the tree"

[ -z "$(git status --porcelain)" ] || die "working tree is dirty; commit or stash first"

VERSION_NAME=$(grep -oP '^\s*versionName = "\K[^"]+' app/build.gradle.kts)
VERSION_CODE=$(grep -oP '^\s*versionCode = \K\d+'    app/build.gradle.kts)
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || die "could not read the version from app/build.gradle.kts"
TAG="v$VERSION_NAME"
echo "  version      $VERSION_NAME ($VERSION_CODE)  →  tag $TAG"

# A tag that already points here means a previous run got interrupted; one that
# points elsewhere means the version was not bumped.
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  [ "$(git rev-parse "$TAG^{commit}")" = "$(git rev-parse HEAD)" ] \
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

[ -f keystore.properties ] || die "keystore.properties is missing; the build would come out unsigned"

if [ -n "${TWIG_JAVA_HOME:-}" ]; then
  JDK=$TWIG_JAVA_HOME
else
  JDK=$(ls -d /usr/lib/jvm/*21* 2>/dev/null | head -1 || true)
fi
[ -n "$JDK" ] || die "no JDK 21 found; set TWIG_JAVA_HOME"
echo "  jdk          $JDK"

OUT="$ROOT/build/release/$VERSION_NAME"
rm -rf "$OUT"; mkdir -p "$OUT"

# ---------------------------------------------------------------------- build

# Two passes: AGP refuses to have both ndk.abiFilters and splits.abi set, so the
# combined APK and the per-ABI ones cannot come out of one invocation.
say "Building the combined APKs"
JAVA_HOME="$JDK" ./gradlew :app:assembleFullRelease :app:assembleLibreRelease -PdebugSign=false
cp app/build/outputs/apk/full/release/app-full-release.apk   "$OUT/Twig-$VERSION_NAME-full.apk"
cp app/build/outputs/apk/libre/release/app-libre-release.apk "$OUT/Twig-$VERSION_NAME-libre.apk"

say "Building the per-ABI APKs"
JAVA_HOME="$JDK" ./gradlew :app:assembleFullRelease -PabiSplit=true -PdebugSign=false
for abi in arm64-v8a armeabi-v7a x86_64; do
  cp "app/build/outputs/apk/full/release/app-full-$abi-release.apk" \
     "$OUT/Twig-$VERSION_NAME-full-$abi.apk"
done

# --------------------------------------------------------------------- verify

say "Verifying"
python3 tools/verify-apk.py \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT"/*.apk

# ---------------------------------------------------------------------- notes

LIBRE="Twig-$VERSION_NAME-libre.apk"
FULL="Twig-$VERSION_NAME-full.apk"
sha() { sha256sum "$OUT/$1" | cut -d' ' -f1; }
mb()  { printf '%.2f MB' "$(echo "scale=4; $(stat -c %s "$OUT/$1") / 1048576" | bc)"; }

NOTES="$OUT/notes.md"
cat "fastlane/metadata/android/en-US/changelogs/$VERSION_CODE.txt" > "$NOTES"
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
  echo
  echo "  Publishing would: tag $TAG, push main and $TAG to '$REMOTE', create the release."
  echo "  Re-run with --publish when the notes above look right."
  exit 0
fi

say "Publishing"
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
echo "  Not done by this script: the fdroiddata MR still points at the previous"
echo "  release. Update metadata/com.twig.app.yml there when you want F-Droid to"
echo "  test this one — each push spends about twelve minutes of their shared CI."
