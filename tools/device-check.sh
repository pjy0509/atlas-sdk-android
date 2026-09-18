#!/bin/sh
# Runs on the Mac: builds the SDK and the sample, boots an emulator, drives
# every death the sample can die, and pulls the envelopes each left behind.
# Run from anywhere: sh tools/device-check.sh [AVD=name]. Log: build/device-check.log,
# queue tarballs: build/device-queue/<case>.tar, verified by the server repo's
# scripts/sdk/verify_device_queue.py.
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$REPO/build/device-check.log"
OUT="$REPO/build/device-queue"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$JAVA_HOME/bin:$PATH"
PKG=dev.appatlas.sample
AVD="${AVD:-Pixel_2_API_34}"

exec > "$LOG" 2>&1
echo "== $(date) start"
rm -rf "$OUT"; mkdir -p "$OUT"
cd "$REPO"

echo "== build"
./gradlew --no-daemon -q :atlas-crash-ndk:assembleRelease :sample:assembleDebug || { echo "BUILD FAILED"; exit 1; }
ls -la atlas-crash-ndk/build/outputs/aar/ sample/build/outputs/apk/debug/
echo "== native lib sizes"
unzip -l atlas-crash-ndk/build/outputs/aar/atlas-crash-ndk-release.aar | grep '\.so'

if ! adb get-state >/dev/null 2>&1; then
    echo "== booting $AVD"
    nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >/dev/null 2>&1 &
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
    sleep 5
fi
echo "== device: $(adb shell getprop ro.build.version.sdk | tr -d '\r') $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

adb install -r -t sample/build/outputs/apk/debug/sample-debug.apk
adb shell pm clear $PKG >/dev/null

# -S: stop the running process first, or a live activity keeps its old intent.
launch() { adb shell am start -S -W -n $PKG/.MainActivity --es case "$1" >/dev/null 2>&1; }
pull() {
    adb exec-out run-as $PKG tar -cf - cache/atlas files/atlas 2>/dev/null > "$OUT/$1.tar"
    echo "-- $1: $(tar -tf "$OUT/$1.tar" 2>/dev/null | grep -c envelope) envelopes"
    adb shell run-as $PKG rm -rf cache/atlas/queue >/dev/null 2>&1
}
logmark() { adb logcat -d -s AtlasSample:I | tail -n 4; }

for case in jvm-main jvm-thread oom native; do
    echo "== case $case"
    adb logcat -c
    launch "$case"; sleep 6
    launch none; sleep 4
    logmark
    pull "$case"
done

echo "== case error"
launch error; sleep 4; pull error

echo "== case anr (background broadcast wedge)"
launch none; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 2
# A foreground-priority broadcast: its ANR timeout is 10 seconds, and a
# background app that trips it is killed without a dialog (REASON_ANR).
adb shell am broadcast --receiver-foreground -n $PKG/.BlockReceiver >/dev/null 2>&1 &
sleep 30
echo "anr records: $(adb shell dumpsys activity exit-info $PKG | grep -c ANR)"
adb shell logcat -d | grep -i "ANR in $PKG" | tail -n 2
launch none; sleep 5; logmark; pull anr

echo "== case kill (SIGKILL from outside)"
launch kill; sleep 3
PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid=$PID"
adb shell run-as $PKG kill -9 $PID; sleep 2
launch none; sleep 5; logmark; pull kill

echo "== exit reasons the OS kept"
adb shell dumpsys activity exit-info $PKG | grep -E "reason=|timestamp" | head -n 20

echo "== $(date) done"
