#!/usr/bin/env bash
# Build and verify the full/libre preview APKs, then optionally publish a prerelease.
# Usage: tools/preview.sh [--publish] [--notes-file PATH] [--offline]
# The committed version must be X.Y.Z-{alpha,beta,rc,preview}.N with bilingual
# changelogs. Signing follows local.properties; verification rejects a debug key.
# --publish pushes the current branch and tag, then creates a GitHub Pre-release
# without changing Latest. Without it, nothing is pushed or published.
# Environment: TWIG_GH_REMOTE (github), TWIG_JAVA_HOME (JDK 21),
# TWIG_SIGNER_DN and TWIG_BUILD_TOOLS (same as tools/verify-apk.py).

set -euo pipefail

PUBLISH=0
NOTES_SOURCE=
GRADLE_ARGS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=1 ;;
    --offline) GRADLE_ARGS+=(--offline) ;;
    --notes-file)
      [ "$#" -ge 2 ] && [ -f "$2" ] || { echo 'missing notes file' >&2; exit 2; }
      NOTES_SOURCE=$(realpath "$2"); shift ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

cd "$(dirname "$0")/.."
die() { printf 'fatal: %s\n' "$*" >&2; exit 1; }
clean_tree() { [ -z "$(git status --porcelain)" ] || die 'working tree is dirty; commit or stash first'; }
clean_tree
COMMIT=$(git rev-parse HEAD)
BRANCH=$(git symbolic-ref --short HEAD) || die 'check out the branch to publish'
REMOTE=${TWIG_GH_REMOTE:-github}
# origin may be a private server; resolve the chosen GitHub remote explicitly.
REMOTE_URL=$(git remote get-url "$REMOTE")
[[ "$REMOTE_URL" =~ github\.com[:/]([^/]+/[^/]+)$ ]] || die "$REMOTE is not a GitHub remote"
REPO=${BASH_REMATCH[1]%.git}
VERSION_NAME=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
VERSION_CODE=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)
[[ "$VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+-(alpha|beta|rc|preview)\.[0-9]+$ ]] \
  || die 'preview version must be X.Y.Z-{alpha,beta,rc,preview}.N'
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die 'could not read versionCode'
TAG="v$VERSION_NAME"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  [ "$(git rev-parse "$TAG^{commit}")" = "$COMMIT" ] || die "$TAG points at another commit"
fi
for locale in en-US zh-CN; do
  file="fastlane/metadata/android/$locale/changelogs/$VERSION_CODE.txt"
  [ -s "$file" ] || die "missing $file"
  [ "$(wc -m < "$file")" -le 500 ] || die "$file exceeds 500 characters"
done
[ -f keystore.properties ] || die 'keystore.properties is missing; the APK would be unsigned'
JDK=${TWIG_JAVA_HOME:-}
if [ -z "$JDK" ]; then
  for candidate in /usr/lib/jvm/*21*; do
    if [ -x "$candidate/bin/java" ]; then JDK=$candidate; break; fi
  done
fi
[ -n "$JDK" ] && [ -x "$JDK/bin/java" ] || die 'set TWIG_JAVA_HOME to a JDK 21'

OUT="$PWD/build/preview/$VERSION_NAME"
mkdir -p "$OUT"
printf 'Building Twig %s (%s) from %s\n' "$VERSION_NAME" "$VERSION_CODE" "$COMMIT"
JAVA_HOME="$JDK" ./gradlew :app:assembleFullRelease :app:assembleLibreRelease "${GRADLE_ARGS[@]}"
APKS=()
for flavor in full libre; do
  apk="$OUT/Twig-$VERSION_NAME-$flavor.apk"
  cp "app/build/outputs/apk/$flavor/release/app-$flavor-release.apk" "$apk"
  APKS+=("$apk")
done
python3 tools/verify-apk.py --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "${APKS[@]}"

NOTES="$OUT/notes.md"
if [ -n "$NOTES_SOURCE" ]; then
  [ "$NOTES_SOURCE" != "$NOTES" ] || die 'notes input must be outside the generated notes.md'
  cp "$NOTES_SOURCE" "$NOTES"
else
  {
    printf 'Preview build for testing.\n\n'
    cat "fastlane/metadata/android/en-US/changelogs/$VERSION_CODE.txt"
    printf '\n---\n\n预览版，供测试使用。\n\n'
    cat "fastlane/metadata/android/zh-CN/changelogs/$VERSION_CODE.txt"
  } > "$NOTES"
fi
{
  printf '\n\n### Downloads\n\n'
  printf -- '- **Twig-%s-full.apk** — recommended; includes read-only RAR support.\n' "$VERSION_NAME"
  printf -- '- **Twig-%s-libre.apk** — without RAR.\n\n' "$VERSION_NAME"
  printf 'Both APKs support arm64-v8a and x86_64 and use the normal Twig release signing key.\n'
  printf 'See [LICENSE-EXCEPTIONS.md](https://github.com/%s/blob/%s/LICENSE-EXCEPTIONS.md) for the RAR license exception.\n' "$REPO" "$TAG"
  printf '\nSource commit: `%s`\n\n### SHA-256\n\n```text\n' "$COMMIT"
  (cd "$OUT"; sha256sum "Twig-$VERSION_NAME-full.apk" "Twig-$VERSION_NAME-libre.apk")
  printf '```\n'
} >> "$NOTES"

[ "$(git rev-parse HEAD)" = "$COMMIT" ] || die 'HEAD changed during the build'
clean_tree
if [ "$PUBLISH" -eq 0 ]; then
  printf '\nVerified preview: %s\nNotes: %s\nRe-run with --publish to publish %s.\n' "$OUT" "$NOTES" "$TAG"
  exit 0
fi

gh auth status --hostname github.com >/dev/null
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
  || git tag -a "$TAG" -m "Twig $VERSION_NAME preview (versionCode $VERSION_CODE)"
git push --atomic "$REMOTE" "HEAD:refs/heads/$BRANCH" "refs/tags/$TAG"
gh release create "$TAG" --repo "$REPO" --verify-tag --prerelease --latest=false \
  --title "Twig $VERSION_NAME (Preview)" --notes-file "$NOTES" "${APKS[@]}"
printf '\nhttps://github.com/%s/releases/tag/%s\n' "$REPO" "$TAG"
