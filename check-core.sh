#!/bin/sh
# The parity gate. Two tiers, because this repo no longer holds the server:
#  - always: the SDK's own checks (queue, transport verdicts, links, crash, JSON) and
#    a byte comparison of the envelopes it writes against tools/golden.
#  - with ATLAS_SERVER pointing at an app-atlas checkout: the same envelopes
#    are parsed by the server's real parser and the claim body by its real
#    schema. That is the strong link, and it runs wherever both repos are.
set -e
cd "$(dirname "$0")"

OUT="${TMPDIR:-/tmp}/atlas-android-parity"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

# The JVM halves only: the android.* bindings compile under Gradle, not here.
javac -d "$OUT/classes" \
    atlas-core/src/main/java/dev/appatlas/sdk/core/*.java \
    atlas-links/src/main/java/dev/appatlas/sdk/links/AtlasLink.java \
    atlas-links/src/main/java/dev/appatlas/sdk/links/AtlasLinkListener.java \
    atlas-links/src/main/java/dev/appatlas/sdk/links/ClaimClient.java \
    atlas-links/src/main/java/dev/appatlas/sdk/links/LinkUrl.java \
    atlas-links/src/main/java/dev/appatlas/sdk/links/ReferrerParser.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/AnrTrace.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/CrashPlatform.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/CrashReport.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/CrashReporter.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/CrashScope.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/NativeReport.java \
    atlas-crash/src/main/java/dev/appatlas/sdk/crash/SessionItems.java \
    tools/ParityCheck.java tools/LinksParity.java tools/CrashParity.java
java -cp "$OUT/classes" ParityCheck "$OUT/envelopes"

sh tools/native-gate/check-native.sh

sh tools/check-golden.sh "$OUT/envelopes" atlas-android android referrer
