#!/bin/sh
# The native capture gate, in whichever tier this host can reach.
#
#   Linux + a C compiler: the real tier. The handler is built for the host and
#   fired at with real signals — a crash in other code, from any thread, must
#   land on disk with its frames.
#
#   Anywhere else (a Mac, say): the core targets Linux — /proc/self/maps, ELF
#   headers, the GNU unwinder — so it cannot be built or run for this host at
#   all. The NDK's clang, which targets what the product ships to, compiles it
#   under -Werror for every ABI instead. That proves the source, not the
#   behaviour; the behaviour is proven wherever Linux is (CI, a container).
set +e
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/../../atlas-crash-ndk/src/main/cpp"

# --- the compile-only tier ------------------------------------------------------

ndk_clang() {
    for root in "$ANDROID_NDK_HOME" "$ANDROID_NDK" "$ANDROID_HOME"/ndk/* "$ANDROID_SDK_ROOT"/ndk/* \
                "$HOME/Library/Android/sdk/ndk"/* "$HOME/Android/Sdk/ndk"/*; do
        [ -d "$root" ] || continue

        for bin in "$root"/toolchains/llvm/prebuilt/*/bin/clang; do
            [ -x "$bin" ] && echo "$bin" && return 0
        done
    done

    return 1
}

compile_only() {
    CLANG="$(ndk_clang)" || {
        echo "native: not Linux and no NDK found, skipping the signal-handler tier"
        exit 0
    }

    OUT="${TMPDIR:-/tmp}/atlas-native-gate"
    rm -rf "$OUT"
    mkdir -p "$OUT"
    status=0

    for target in aarch64-linux-android21 armv7a-linux-androideabi21 x86_64-linux-android21 i686-linux-android21; do
        if ! "$CLANG" --target="$target" -c -O2 -Wall -Wextra -Werror -fno-omit-frame-pointer \
                -o "$OUT/$target.o" "$CORE/atlas_crash.c" 2>"$OUT/$target.log"; then
            echo "native: [$target] did not compile" >&2
            cat "$OUT/$target.log" >&2
            status=1
        fi
    done

    [ "$status" -eq 0 ] && echo "native: the handler compiles clean for every ABI (run the signal tier on Linux)"

    exit $status
}

[ "$(uname -s)" = "Linux" ] || compile_only
command -v "${CC:-cc}" >/dev/null 2>&1 || compile_only

# --- the real tier --------------------------------------------------------------

CC="${CC:-cc}"
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
