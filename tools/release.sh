#!/bin/sh
# Cuts a release on a machine with the Android SDK and NDK:
#
#   sh tools/release.sh            # version from build.gradle
#
# Needs, in ~/.gradle/gradle.properties or the environment:
#   centralUsername / centralPassword   (CENTRAL_USERNAME / CENTRAL_PASSWORD) — the Portal user token
#   signingInMemoryKey / signingInMemoryKeyPassword (SIGNING_KEY / SIGNING_PASSWORD)
#
# Order matters: gate, bundle, upload, and only then tag. A tag is a promise
# that a version is out there, so nothing is tagged until the Portal has taken
# it. The bundle is written to build/central-bundle and zipped from there, so
# what leaves the machine can be read before it does.
set -eu
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION="$(sed -n "s/.*version = '\([^']*\)'.*/\1/p" build.gradle | head -n1)"
[ -n "$VERSION" ] || { echo "no version in build.gradle" >&2; exit 1; }

prop() {
    sed -n "s/^$1=//p" "$HOME/.gradle/gradle.properties" 2>/dev/null | head -n1
}
USER_TOKEN="${CENTRAL_USERNAME:-$(prop centralUsername)}"
PASS_TOKEN="${CENTRAL_PASSWORD:-$(prop centralPassword)}"
[ -n "$USER_TOKEN" ] && [ -n "$PASS_TOKEN" ] || { echo "no Central credentials (centralUsername/centralPassword)" >&2; exit 1; }

# Everything that would go into the release must already be committed: the
# artifact is built from the tree, and the tag will name the commit.
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "working tree is dirty; commit first" >&2; exit 1; }
! git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || { echo "v$VERSION is already tagged" >&2; exit 1; }

echo "== releasing $VERSION from $(git rev-parse --short HEAD)"

echo "== gate"
sh check-core.sh

echo "== bundle"
rm -rf build/central-bundle "build/atlas-android-$VERSION.zip"
./gradlew --no-daemon -q \
    :atlas-core:publishReleasePublicationToCentralBundleRepository \
    :atlas-links:publishReleasePublicationToCentralBundleRepository \
    :atlas-crash:publishReleasePublicationToCentralBundleRepository \
    :atlas-crash-ndk:publishReleasePublicationToCentralBundleRepository
(cd build/central-bundle && zip -q -r "../atlas-android-$VERSION.zip" dev)
unzip -l "build/atlas-android-$VERSION.zip" | grep -E '\.(aar|pom)$'

# The native artifact is the one worth looking at twice: it must carry a .so
# for every ABI it claims, or a whole architecture ships blind.
NDK_AAR="atlas-crash-ndk/build/outputs/aar/atlas-crash-ndk-release.aar"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    unzip -l "$NDK_AAR" | grep -q "jni/$abi/libatlas-native.so" \
        || { echo "the native aar is missing $abi" >&2; exit 1; }
done
echo "== native aar carries every abi"

echo "== upload"
TOKEN="$(printf '%s:%s' "$USER_TOKEN" "$PASS_TOKEN" | base64 | tr -d '\n')"
DEPLOYMENT="$(curl --fail -sS -X POST 'https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC' \
    -H "Authorization: Bearer $TOKEN" \
    -F "bundle=@build/atlas-android-$VERSION.zip" \
    -F "name=atlas-android-$VERSION")"
echo "$DEPLOYMENT" > build/deployment-id.txt
echo "deployment $DEPLOYMENT"

# The Portal's verdict: VALIDATED, then PUBLISHING/PUBLISHED, or FAILED with reasons.
STATE=""

for _ in $(seq 1 60); do
    sleep 10
    STATUS="$(curl -sS -X POST "https://central.sonatype.com/api/v1/publisher/status?id=$DEPLOYMENT" \
        -H "Authorization: Bearer $TOKEN" || true)"
    STATE="$(printf '%s' "$STATUS" | sed -n 's/.*"deploymentState":"\([A-Z_]*\)".*/\1/p')"
    echo "state ${STATE:-?}"

    case "$STATE" in
        PUBLISHED|PUBLISHING) break ;;
        FAILED) echo "$STATUS" >&2; exit 1 ;;
    esac
done

case "$STATE" in
    PUBLISHED|PUBLISHING) ;;
    *) echo "still validating; tag by hand once it publishes: https://central.sonatype.com/publishing/deployments" >&2; exit 1 ;;
esac

echo "== tag"
git tag -a "v$VERSION" -m "v$VERSION"
git push origin "v$VERSION"

echo "== $VERSION is on its way to Maven Central"
