#!/bin/bash
set -euo pipefail

# The .so files under core/wayland/src/main/jniLibs are no longer committed —
# #493 moved them to core/wayland's buildWaylandNatives task, which builds them
# from the wayland-android submodule during preBuild, and .gitignore keeps them
# out. This checks whatever is in that directory, which now means build output.
#
# #469 was the committed liblabwc_android.so three weeks behind its source,
# leaving 51 symbols undefined, so ld.so refused to load it and every
# cage/app-window feature died with
#   dlopen failed: cannot locate symbol "wlr_output_is_drm"
# The app itself was fine; only the binary was bad. Nothing failed until a user
# hit the feature on a device.
#
# Building from source removes the *staleness* half of that and NOT the
# unlinkable half: wlroots is built with -Dbackends=[] and no libinput, so these
# symbols have no real implementation — gen-stubs.sh emits weak stubs for them
# at link time. A fresh build that misses the stubs is just as undlopenable as a
# stale copy was, and just as green at build time. If any of these is UND in a
# shipped library it will not load, and there is no case where shipping one is
# correct — which is what makes this checkable rather than a heuristic.
#
# Consequently this must run AFTER a build. It sat in CI's `checks` job, which
# does not build, and reported "nothing to check" on every run once the
# binaries stopped being committed. It now runs in ci.yml's `build` job and in
# release.yml's build job, both after the Gradle build.
#
# readelf reads foreign-architecture ELFs fine, so this needs no NDK.

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$REPO_ROOT/core/wayland/src/main/jniLibs"

# Prefixes that must always be resolved in a shipped library. Mirrors the
# pattern list in wayland-android/gen-stubs.sh.
MUST_RESOLVE='^(wlr_|libinput_|xcb_ewmh_)'

if ! command -v readelf > /dev/null 2>&1; then
    echo "check-native-libs: readelf not found; skipping" >&2
    exit 0
fi

if [ ! -d "$JNI_DIR" ]; then
    # Deliberately not `exit 0`. This used to return success here, which also
    # skipped the entirely independent rdp section below — so once the wayland
    # binaries stopped being committed, BOTH halves of this gate silently
    # stopped running while still reporting a pass. Fall through; whether
    # having checked nothing is acceptable is decided at the end.
    echo "check-native-libs: $JNI_DIR does not exist — no wayland libraries to check" >&2
fi

status=0
checked=0

while IFS= read -r so; do
    checked=$((checked + 1))
    # Dynamic symbol table: columns are
    #   Num: Value Size Type Bind Vis Ndx Name
    # An undefined symbol has Ndx == UND. Take the name and drop any @VERSION.
    undefined=$(readelf --dyn-syms -W "$so" 2>/dev/null \
        | awk '$7 == "UND" { sub(/@.*/, "", $8); if ($8 != "") print $8 }' \
        | grep -E "$MUST_RESOLVE" \
        | sort -u || true)

    if [ -n "$undefined" ]; then
        count=$(printf '%s\n' "$undefined" | wc -l | tr -d ' ')
        echo "FAIL: ${so#"$REPO_ROOT"/} has $count unresolved symbol(s) that must be stubbed at link time:"
        printf '%s\n' "$undefined" | sed 's/^/    /' | head -20
        [ "$count" -gt 20 ] && echo "    … and $((count - 20)) more"
        status=1
    fi
done < <(find "$JNI_DIR" -name '*.so' -type f 2>/dev/null | sort)

if [ "$status" -ne 0 ]; then
    cat >&2 <<'MSG'

This library will fail to dlopen at runtime (#469). It was almost certainly
linked before wayland-android/gen-stubs.sh ran, or copied from a stale build.

To fix, rebuild from the submodule and copy the result in:
    cd wayland-android && ABI=arm64-v8a ./build_liblabwc_android.sh
    cp wayland-android/jniLibs/<abi>/*.so core/wayland/src/main/jniLibs/<abi>/

Verify before committing:
    ./scripts/check-native-libs.sh
MSG
    exit 1
fi

if [ "$checked" -gt 0 ]; then
    echo "✓ $checked wayland native librar$([ "$checked" = 1 ] && echo y || echo ies) have no unresolved stub symbols."
fi

# ---------------------------------------------------------------------------
# rdp-kotlin/jniLibs — committed too, but a different failure mode.
#
# These are built from rdp-kotlin/rust by the buildRdpNative Gradle task. The
# task only asked cargo-ndk for arm64-v8a and x86_64 for a long time while all
# three ABIs were checked in, so a Rust change rebuilt two of the shipped
# libraries and left armv7 on whatever binary someone last produced by hand.
# The target list is fixed, which stops it recurring; these assertions catch
# the two ways a hand-copied library still goes wrong, and both are things
# that can never legitimately be true of a shipped file:
#
#   * a library placed in the wrong ABI directory — the ELF machine says what
#     it actually is, regardless of the path it sits under
#   * a library built without its JNI entry point, which loads and then fails
#     at the first call with UnsatisfiedLinkError
#
# Deliberately not checked here: whether the binary matches the current Rust
# source. The Gradle task rebuilds all three from source as part of the build,
# so a stale one cannot ship; and a source edit that changes no codegen (a
# comment) would leave nothing to re-commit, so a timestamp comparison would
# fail forever with no way to satisfy it.

RDP_DIR="$REPO_ROOT/rdp-kotlin/jniLibs"
RDP_ENTRY_POINT='Java_sh_haven_core_rdp_RdpBitmapBridge_blitRegion'

# ABI directory -> the machine name readelf prints for it.
rdp_expected_machine() {
    case "$1" in
        arm64-v8a) echo 'AArch64' ;;
        armeabi-v7a) echo 'ARM' ;;
        x86_64) echo 'Advanced Micro Devices X86-64' ;;
        x86) echo 'Intel 80386' ;;
        *) echo '' ;;
    esac
}

# Initialised out here so the "did this gate inspect anything?" assertion at the
# bottom can read it even when RDP_DIR is absent (set -u would otherwise abort).
rdp_status=0
rdp_checked=0

if [ -d "$RDP_DIR" ]; then
    for abi_dir in "$RDP_DIR"/*/; do
        [ -d "$abi_dir" ] || continue
        abi="$(basename "$abi_dir")"
        so="$abi_dir/librdp_transport.so"

        if [ ! -f "$so" ]; then
            echo "FAIL: rdp-kotlin/jniLibs/$abi has no librdp_transport.so"
            rdp_status=1
            continue
        fi
        rdp_checked=$((rdp_checked + 1))

        want="$(rdp_expected_machine "$abi")"
        got="$(readelf -h "$so" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
        if [ -n "$want" ] && [ "$got" != "$want" ]; then
            echo "FAIL: rdp-kotlin/jniLibs/$abi/librdp_transport.so is a '$got' binary, expected '$want'"
            rdp_status=1
        fi

        # Exact match on the symbol-name column. Matching the whole line with a
        # regex anchor is easy to get wrong — "\$" inside double quotes is a
        # literal dollar, not an end-of-line anchor, which silently made this
        # fail on correct libraries.
        if ! readelf --dyn-syms -W "$so" 2>/dev/null \
            | awk -v want="$RDP_ENTRY_POINT" '{ sub(/@.*/, "", $8); if ($8 == want) found = 1 } END { exit !found }'; then
            echo "FAIL: rdp-kotlin/jniLibs/$abi/librdp_transport.so does not export $RDP_ENTRY_POINT"
            rdp_status=1
        fi
    done

    if [ "$rdp_checked" -eq 0 ] && [ "$rdp_status" -eq 0 ]; then
        echo "check-native-libs: no rdp libraries found under $RDP_DIR" >&2
    elif [ "$rdp_status" -ne 0 ]; then
        cat >&2 <<'MSG'

Rebuild all three from source and commit the result:
    cd rdp-kotlin/rust && cargo ndk -o ../jniLibs \
        -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
MSG
        exit 1
    else
        echo "✓ $rdp_checked rdp native libraries match their ABI and export the JNI entry point."
    fi
fi

# ---------------------------------------------------------------------------
# core/prns/src/main/jniLibs — the Prns capsule, built from the prns submodule
# by :core:prns:buildPrnsNative. Same two assertions as rdp: right ELF machine
# per ABI directory, and the contract entry point actually exported (a capsule
# built from the wrong source tree loads and then dies on the first call).

PRNS_DIR="$REPO_ROOT/core/prns/src/main/jniLibs"
PRNS_ENTRY_POINT='prns_host_attach_supplied_pipe'

prns_status=0
prns_checked=0

if [ -d "$PRNS_DIR" ]; then
    for abi_dir in "$PRNS_DIR"/*/; do
        [ -d "$abi_dir" ] || continue
        abi="$(basename "$abi_dir")"
        so="$abi_dir/libprns_host.so"

        if [ ! -f "$so" ]; then
            echo "FAIL: core/prns/src/main/jniLibs/$abi has no libprns_host.so"
            prns_status=1
            continue
        fi
        prns_checked=$((prns_checked + 1))

        want="$(rdp_expected_machine "$abi")"
        got="$(readelf -h "$so" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
        if [ -n "$want" ] && [ "$got" != "$want" ]; then
            echo "FAIL: core/prns/src/main/jniLibs/$abi/libprns_host.so is a '$got' binary, expected '$want'"
            prns_status=1
        fi

        if ! readelf --dyn-syms -W "$so" 2>/dev/null \
            | awk -v want="$PRNS_ENTRY_POINT" '{ sub(/@.*/, "", $8); if ($8 == want) found = 1 } END { exit !found }'; then
            echo "FAIL: core/prns/src/main/jniLibs/$abi/libprns_host.so does not export $PRNS_ENTRY_POINT"
            prns_status=1
        fi
    done

    if [ "$prns_status" -ne 0 ]; then
        cat >&2 <<'MSG'

Rebuild all three from source:
    cd prns/prns-host/abi/c && cargo ndk -o ../../../../core/prns/src/main/jniLibs \
        -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
MSG
        exit 1
    elif [ "$prns_checked" -gt 0 ]; then
        echo "✓ $prns_checked prns native libraries match their ABI and export the contract entry point."
    fi
fi

# ---------------------------------------------------------------------------
# core/tunnel/jniLibs-openvpn3 — built from openvpn3-core/build-android.sh
# (see that script and openvpn3-core/CMakeLists.txt). Unlike RDP/PRNS these
# are static archives (.a), not shared libraries (.so), so the symbol check
# uses `nm` rather than `readelf --dyn-syms` (which only applies to a
# dynamic symbol table a static archive doesn't have). Same two assertions
# as RDP/PRNS otherwise: right ELF machine per ABI directory, and the
# contract entry point actually present (a library built from the wrong
# source tree, or missing the mbedtls/lz4 merge step, would still produce a
# file here but fail to link against downstream).

OPENVPN3_DIR="$REPO_ROOT/core/tunnel/jniLibs-openvpn3"
# start_session is one of adapter.cpp's own extern "C" functions (see
# rclone-android/go/ovpnbridge/openvpn3/bridge/library.h) -- unlike a raw
# OpenVPN3 Core C++ method name (which nm shows mangled without -C/demangle,
# so a literal match would always miss), this is guaranteed unmangled and
# additionally proves the adapter layer itself built and linked in, not
# just upstream OpenVPN3 Core.
OPENVPN3_ENTRY_POINT='start_session'

# ABI directory -> the suffix openvpn3-core/build-android.sh (and
# session.go's cgo LDFLAGS) use for the archive's file name.
openvpn3_expected_suffix() {
    case "$1" in
        arm64-v8a) echo 'arm64' ;;
        armeabi-v7a) echo 'armeabi-v7a' ;;
        x86) echo 'x86' ;;
        x86_64) echo 'amd64' ;;
        *) echo '' ;;
    esac
}

openvpn3_status=0
openvpn3_checked=0

if [ -d "$OPENVPN3_DIR" ]; then
    for abi_dir in "$OPENVPN3_DIR"/*/; do
        [ -d "$abi_dir" ] || continue
        abi="$(basename "$abi_dir")"
        suffix="$(openvpn3_expected_suffix "$abi")"
        if [ -z "$suffix" ]; then
            echo "FAIL: core/tunnel/jniLibs-openvpn3/$abi is not a recognised ABI"
            openvpn3_status=1
            continue
        fi
        a="$abi_dir/libopenvpn3_android_${suffix}.a"

        if [ ! -f "$a" ]; then
            echo "FAIL: core/tunnel/jniLibs-openvpn3/$abi has no libopenvpn3_android_${suffix}.a"
            openvpn3_status=1
            continue
        fi
        openvpn3_checked=$((openvpn3_checked + 1))

        want="$(rdp_expected_machine "$abi")"
        got="$(readelf -h "$a" 2>/dev/null | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' | head -1)"
        if [ -n "$want" ] && [ "$got" != "$want" ]; then
            echo "FAIL: core/tunnel/jniLibs-openvpn3/$abi/libopenvpn3_android_${suffix}.a is a '$got' binary, expected '$want'"
            openvpn3_status=1
        fi

        # nm's per-archive-member header lines shift the symbol-name column,
        # so match on the last field ($NF) rather than a fixed index.
        if ! nm --defined-only "$a" 2>/dev/null \
            | awk -v want="$OPENVPN3_ENTRY_POINT" '{ if ($NF == want) found = 1 } END { exit !found }'; then
            echo "FAIL: core/tunnel/jniLibs-openvpn3/$abi/libopenvpn3_android_${suffix}.a does not export $OPENVPN3_ENTRY_POINT"
            openvpn3_status=1
        fi
    done

    if [ "$openvpn3_status" -ne 0 ]; then
        cat >&2 <<'MSG'

Rebuild from source:
    cd openvpn3-core && OPENVPN3_OUTPUT=../core/tunnel/jniLibs-openvpn3 ./build-android.sh
MSG
        exit 1
    elif [ "$openvpn3_checked" -gt 0 ]; then
        echo "✓ $openvpn3_checked openvpn3 native libraries match their ABI and export the contract entry point."
    fi
fi

# The point of this block: for every run after #493 untracked the binaries, this
# script printed "nothing to check" and exited 0. A gate that passes because it
# found nothing to inspect is indistinguishable, in CI, from one that inspected
# everything and approved it — and this particular gate guards a failure (#469)
# whose entire character is that the build stays green.
#
# Both call sites now run after a Gradle build, so an empty tree means the build
# did not produce what it was supposed to.
if [ "$checked" -eq 0 ] && [ "$rdp_checked" -eq 0 ]; then
    cat >&2 <<MSG

check-native-libs: FAILED — no native libraries were inspected at all.

Nothing was found under either:
    $JNI_DIR
    $RDP_DIR

This checks BUILD OUTPUT, so it has to run after a build. By hand:
    ./gradlew :app:assembleArm64FullDebug -PtargetAbi=arm64
MSG
    exit 1
fi
