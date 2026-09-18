#!/bin/sh
# Cuts a release on the Mac: gate, push, bundle, upload to the Central Portal.
#
#   sh tools/release.sh            # version from build.gradle
#
# Needs, in ~/.gradle/gradle.properties or the environment:
#   centralUsername / centralPassword   (CENTRAL_USERNAME / CENTRAL_PASSWORD) — the Portal user token
#   signingInMemoryKey / signingInMemoryKeyPassword (SIGNING_KEY / SIGNING_PASSWORD)
# The bundle is written to build/central-bundle first and zipped from there, so
# what leaves the machine can be read before it does (build/atlas-android-<v>.zip).
set -eu
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION="$(sed -n "s/.*version = '\([^']*\)'.*/\1/p" build.gradle | head -n1)"
[ -n "$VERSION" ] || { echo "no version in build.gradle" >&2; exit 1; }
echo "== releasing $VERSION"

prop() {
    sed -n "s/^$1=//p" "$HOME/.gradle/gradle.properties" 2>/dev/null | head -n1
}
USER="${CENTRAL_USERNAME:-$(prop centralUsername)}"
PASS="${CENTRAL_PASSWORD:-$(prop centralPassword)}"
[ -n "$USER" ] && [ -n "$PASS" ] || { echo "no Central credentials (centralUsername/centralPassword)" >&2; exit 1; }

echo "== gate"
sh check-core.sh

echo "== tag and push"
git tag -a "v$VERSION" -m "v$VERSION"
git push origin main "v$VERSION"

echo "== bundle"
rm -rf build/central-bundle "build/atlas-android-$VERSION.zip"
./gradlew --no-daemon -q \
    :atlas-core:publishReleasePublicationToCentralBundleRepository \
    :atlas-links:publishReleasePublicationToCentralBundleRepository \
    :atlas-crash:publishReleasePublicationToCentralBundleRepository \
    :atlas-crash-ndk:publishReleasePublicationToCentralBundleRepository
(cd build/central-bundle && zip -q -r "../atlas-android-$VERSION.zip" dev)
unzip -l "build/atlas-android-$VERSION.zip" | grep -E '\.(aar|pom|module)$'

echo "== upload"
TOKEN="$(printf '%s:%s' "$USER" "$PASS" | base64 | tr -d '\n')"
DEPLOYMENT="$(curl --fail -sS -X POST 'https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC' \
    -H "Authorization: Bearer $TOKEN" \
    -F "bundle=@build/atlas-android-$VERSION.zip" \
    -F "name=atlas-android-$VERSION")"
echo "$DEPLOYMENT" > build/deployment-id.txt
echo "deployment $DEPLOYMENT"

# Wait for the Portal's verdict: VALIDATED then PUBLISHING/PUBLISHED, or FAILED with reasons.
for _ in $(seq 1 60); do
    sleep 10
    STATUS="$(curl -sS -X POST "https://central.sonatype.com/api/v1/publisher/status?id=$DEPLOYMENT" \
        -H "Authorization: Bearer $TOKEN")"
    STATE="$(printf '%s' "$STATUS" | sed -n 's/.*"deploymentState":"\([A-Z_]*\)".*/\1/p')"
    echo "state $STATE"

    case "$STATE" in
        PUBLISHED|PUBLISHING) echo "== $VERSION is on its way to Maven Central"; exit 0 ;;
        FAILED) echo "$STATUS"; exit 1 ;;
    esac
done

echo "still validating; check https://central.sonatype.com/publishing/deployments"
