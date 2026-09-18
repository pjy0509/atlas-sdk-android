#!/bin/sh
# The native capture gate. The Android build compiles atlas_crash.c with the
# NDK; here it is built with the host cc so the handler runs against real
# signals — a crash in other code, from any thread, must land on disk with
# its frames. Skipped when no C compiler is present (the JVM gate still runs).
set +e
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/../../atlas-crash-ndk/src/main/cpp"
CC="${CC:-cc}"

if ! command -v "$CC" >/dev/null 2>&1; then
    echo "native: no C compiler, skipping the signal-handler tier"
    exit 0
fi

OUT="${TMPDIR:-/tmp}/atlas-native-gate"
rm -rf "$OUT"
mkdir -p "$OUT"

$CC -g -O1 -fno-omit-frame-pointer -shared -fPIC -Wl,--build-id=sha1 -o "$OUT/libvictim.so" "$HERE/victim.c" || exit 1
$CC -g -O1 -Wall -Wextra -fno-omit-frame-pointer -o "$OUT/host_test" "$HERE/host_test.c" "$CORE/atlas_crash.c" \
    -L"$OUT" -lvictim -lpthread -Wl,-rpath,"$OUT" -Wl,--build-id=sha1 || exit 1

status=0

for how in null abort overflow div thread; do
    report="$OUT/report-$how.txt"
    rm -f "$report"
    "$OUT/host_test" "$report" "$how" >/dev/null 2>&1

    if [ ! -s "$report" ]; then
        echo "native: [$how] wrote no report" >&2
        status=1
        continue
    fi

    if ! grep -q '^atlas-native-crash 1$' "$report"; then echo "native: [$how] no header" >&2; status=1; fi
    if ! tail -n1 "$report" | grep -q '^end$'; then echo "native: [$how] truncated" >&2; status=1; fi
    if [ "$(grep -c '^frame ' "$report")" -lt 2 ]; then echo "native: [$how] too few frames" >&2; status=1; fi
    # A build id and a module-relative address on the crashing frame: what the server symbolicates by.
    if ! grep -E '^frame [0-9a-f]+ [0-9a-f]+ [0-9a-f]{8,} ' "$report" >/dev/null; then
        echo "native: [$how] no build-id frame" >&2; status=1
    fi
done

# The crash on a worker thread must name that thread.
grep -q '^thread worker-7$' "$OUT/report-thread.txt" || { echo "native: the crashed thread was not named" >&2; status=1; }

[ "$status" -eq 0 ] && echo "native: the signal handler captures every signal, from any thread, with build-id frames"

exit $status
