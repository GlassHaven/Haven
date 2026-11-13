#!/bin/bash
#
# Cross-compile PRoot for Android arm64-v8a and x86_64.
#
# Prerequisites:
#   - Android NDK r28+ (tested against r29 stable; r28+ is required for the
#     ARM64 TLS alignment fix the build_for_arch step relies on). Set
#     ANDROID_NDK_HOME, or let the script auto-detect from ~/Android/Sdk/ndk/.
#   - Standard build tools: make, git
#   - PRoot source: build-proot/proot-termux submodule (git submodule update --init)
#   - Talloc source: build-proot/talloc/ (vendored)
#
# Output (default: core/local/src/main/jniLibs/):
#   <ABI>/libproot.so
#   <ABI>/libproot_loader.so
#
# PRoot is named libproot.so so Android extracts it to nativeLibraryDir,
# making it executable on Android 14+ (which blocks exec from app data dirs).

set -euo pipefail
cd "$(dirname "$0")"

PROJECT_ROOT="$(cd .. && pwd)"
PROOT_OUTPUT="${PROOT_OUTPUT:-$PROJECT_ROOT/core/local/src/main/jniLibs}"

# Verify sources exist
if [ ! -f "proot-termux/src/GNUmakefile" ]; then
    echo "ERROR: proot-termux submodule not initialised."
    echo "Run: git submodule update --init build-proot/proot-termux"
    exit 1
fi
if [ ! -f "talloc/talloc.c" ]; then
    echo "ERROR: talloc/talloc.c not found. Vendored talloc sources missing."
    exit 1
fi

# Auto-detect NDK (pick newest available; needs r28+ for the ARM64 TLS
# alignment fix below — tested against r29 stable).
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    for NDK_BASE in "$HOME/Android/Sdk/ndk" "${ANDROID_HOME:-/nonexistent}/ndk" "${ANDROID_SDK_ROOT:-/nonexistent}/ndk"; do
        if [ -d "$NDK_BASE" ]; then
            ANDROID_NDK_HOME=$(ls -d "$NDK_BASE"/*/ 2>/dev/null | sort -V | tail -1)
            ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
            [ -d "$ANDROID_NDK_HOME" ] && break
        fi
    done
fi

if [ ! -d "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set and NDK not found in ~/Android/Sdk/ndk/ or \$ANDROID_HOME/ndk/"
    exit 1
fi
echo "Using NDK: $ANDROID_NDK_HOME"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
API=26  # minSdk

build_for_arch() {
    local ARCH="$1"
    local TARGET="$2"
    local ABI="$3"

    echo ""
    echo "=== Building for $ABI ($TARGET) ==="

    local BUILD_DIR="build-$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    local CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
    local AR="$TOOLCHAIN/bin/llvm-ar"
    local RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    local STRIP="$TOOLCHAIN/bin/llvm-strip"

    # Build talloc as static library (bypass waf — just compile talloc.c directly)
    echo "Building talloc..."
    local TALLOC_SRC="$BUILD_DIR/talloc"
    cp -a talloc/ "$TALLOC_SRC"

    local TALLOC_INSTALL="$PWD/$BUILD_DIR/talloc-install"
    mkdir -p "$TALLOC_INSTALL/lib" "$TALLOC_INSTALL/include"

    (
        cd "$TALLOC_SRC"
        $CC -c -I. -Ilib/replace \
            -include config.h \
            -DHAVE_CONFIG_H \
            -DTALLOC_BUILD_VERSION_MAJOR=2 \
            -DTALLOC_BUILD_VERSION_MINOR=4 \
            -DTALLOC_BUILD_VERSION_RELEASE=2 \
            -D_GNU_SOURCE \
            -fPIC \
            talloc.c -o talloc.o

        $AR rcs libtalloc.a talloc.o
        cp libtalloc.a "$TALLOC_INSTALL/lib/"
        cp talloc.h "$TALLOC_INSTALL/include/"
    )
    echo "talloc built: $TALLOC_INSTALL/lib/libtalloc.a"

    # Create TLS alignment fix object — Android 15 requires 64-byte TLS
    # alignment on ARM64. Adding an aligned TLS variable forces the linker
    # to produce a TLS segment with the correct alignment.
    local TLS_FIX="$PWD/$BUILD_DIR/tls_align.o"
    echo "__thread int __tls_align_fix __attribute__((aligned(64))) = 0;" | \
        $CC -fno-emulated-tls -c -x c - -o "$TLS_FIX"

    # Build PRoot (Termux fork)
    echo "Building PRoot (Termux fork)..."
    local PROOT_SRC="$BUILD_DIR/proot-termux"
    cp -a proot-termux "$PROOT_SRC"

    # Patch missing includes for NDK
    sed -i '1i #include <string.h>' "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c" 2>/dev/null || true

    (
        cd "$PROOT_SRC/src"

        # PRoot's Makefile uses pkg-config for talloc — override the shell commands
        # to return our cross-compiled paths instead
        make -j$(nproc) \
            CC="$CC" \
            LD="$CC" \
            STRIP="$STRIP" \
            OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" \
            OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
            CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I\$(VPATH) -I\$(VPATH)/../lib/uthash/include -I$TALLOC_INSTALL/include" \
            CFLAGS="-g -Wall -O2 -I$TALLOC_INSTALL/include" \
            LDFLAGS="-L$TALLOC_INSTALL/lib -ltalloc -static $TLS_FIX" \
            CARE_LDFLAGS="" \
            HAS_SWIG="" \
            HAS_PYTHON_CONFIG="" \
            V=1 \
            proot 2>&1 | tail -15

        "$STRIP" proot
        file proot 2>/dev/null || true
        ls -la proot
    )

    # Install proot + loader
    mkdir -p "$PROOT_OUTPUT/$ABI"
    cp "$PROOT_SRC/src/proot" "$PROOT_OUTPUT/$ABI/libproot.so"
    if [ -f "$PROOT_SRC/src/loader/loader" ]; then
        "$STRIP" "$PROOT_SRC/src/loader/loader"
        cp "$PROOT_SRC/src/loader/loader" "$PROOT_OUTPUT/$ABI/libproot_loader.so"
    fi
    echo "Installed: $PROOT_OUTPUT/$ABI/libproot.so ($(stat -c %s "$PROOT_OUTPUT/$ABI/libproot.so") bytes)"
}

build_for_arch "aarch64" "aarch64-linux-android" "arm64-v8a"
build_for_arch "x86_64" "x86_64-linux-android" "x86_64"
build_for_arch "armv7a" "armv7a-linux-androideabi" "armeabi-v7a"

# ---------------------------------------------------------------------------
# chroot-ng (sylirre/fake-chroot-ng, Apache-2.0) — opt-in arm64-v8a container
# engine (#573). Freestanding AArch64 C and assembly (no libc, -nostdlib),
# built from pinned source verified by SHA256 on every run and installed as
# arm64-v8a/libchrootng.so so Android's installer extracts it executable in
# nativeLibraryDir.
# ---------------------------------------------------------------------------
CHROOTNG_VERSION="1.6.0"
CHROOTNG_COMMIT="869c42b705e889a487a9503e219fedfab71f4d88"
CHROOTNG_TARBALL_SHA256="cf289100ce2bf3de23b8a1ff273963f4d2b3a5ad20ff4a710bd5c397408b080a"
CHROOTNG_TARBALL_URL="https://github.com/sylirre/fake-chroot-ng/archive/${CHROOTNG_COMMIT}.tar.gz"
CHROOTNG_GIT_URL="https://github.com/sylirre/fake-chroot-ng.git"

build_chrootng_arm64() {
    if [ "${SKIP_CHROOTNG:-0}" = "1" ]; then
        echo "Skipping chroot-ng build (SKIP_CHROOTNG=1)"
        return 0
    fi

    echo ""
    echo "=== Building chroot-ng ${CHROOTNG_VERSION} (${CHROOTNG_COMMIT:0:12}) for arm64-v8a ==="

    local DL_DIR="dl"
    local TARBALL="$DL_DIR/fake-chroot-ng-${CHROOTNG_COMMIT}.tar.gz"
    local WORK_DIR="build-chrootng"
    local SRC_DIR="$WORK_DIR/src"
    mkdir -p "$DL_DIR"
    rm -rf "$WORK_DIR"
    mkdir -p "$SRC_DIR"

    if [ ! -f "$TARBALL" ]; then
        echo "  [fetch] $CHROOTNG_TARBALL_URL"
        if ! curl -fsSL --retry 3 --retry-delay 3 --retry-connrefused --retry-all-errors \
                --connect-timeout 20 --max-time 120 -o "$TARBALL.tmp" "$CHROOTNG_TARBALL_URL"; then
            rm -f "$TARBALL.tmp"
            echo "  tarball download failed — falling back to pinned git commit $CHROOTNG_COMMIT" >&2
            if ! (
                cd "$SRC_DIR" &&
                git init -q . &&
                git remote add origin "$CHROOTNG_GIT_URL" &&
                timeout 120 git fetch --depth 1 -q origin "$CHROOTNG_COMMIT" &&
                git checkout -q FETCH_HEAD
            ); then
                echo "ERROR: failed to fetch fake-chroot-ng $CHROOTNG_COMMIT via HTTPS tarball and git fallback" >&2
                exit 1
            fi
            local GOT_COMMIT
            GOT_COMMIT="$(git -C "$SRC_DIR" rev-parse HEAD)"
            if [ "$GOT_COMMIT" != "$CHROOTNG_COMMIT" ]; then
                echo "ERROR: fake-chroot-ng git commit mismatch: expected $CHROOTNG_COMMIT, got $GOT_COMMIT" >&2
                exit 1
            fi
            rm -rf "$SRC_DIR/.git"
        else
            mv "$TARBALL.tmp" "$TARBALL"
        fi
    fi

    # Verify SHA256 on EVERY run (cached or freshly downloaded) before unpacking.
    if [ -f "$TARBALL" ]; then
        local GOT_SHA
        GOT_SHA="$(sha256sum "$TARBALL" | awk '{print $1}')"
        if [ "$GOT_SHA" != "$CHROOTNG_TARBALL_SHA256" ]; then
            echo "ERROR: SHA256 mismatch for $TARBALL" >&2
            echo "  expected: $CHROOTNG_TARBALL_SHA256" >&2
            echo "  got:      $GOT_SHA" >&2
            exit 1
        fi
        tar -xzf "$TARBALL" -C "$SRC_DIR" --strip-components=1
    fi

    for p in "$PWD"/patches/chroot-ng/*.patch; do
        [ -f "$p" ] || continue
        echo "  [patch] $(basename "$p")"
        if command -v patch >/dev/null 2>&1; then
            patch -d "$SRC_DIR" -p1 < "$p"
        else
            (cd "$SRC_DIR" && git init -q && git apply "$p" && rm -rf .git)
        fi
    done

    local CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
    local OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
    local STRIP="$TOOLCHAIN/bin/llvm-strip"

    make -C "$SRC_DIR" -j"$(nproc)" \
        CC="$CC" \
        OBJCOPY="$OBJCOPY" \
        2>&1 | tail -10

    "$STRIP" "$SRC_DIR/build/chroot-ng"
    mkdir -p "$PROOT_OUTPUT/arm64-v8a"
    cp "$SRC_DIR/build/chroot-ng" "$PROOT_OUTPUT/arm64-v8a/libchrootng.so"
    echo "Installed: $PROOT_OUTPUT/arm64-v8a/libchrootng.so ($(stat -c %s "$PROOT_OUTPUT/arm64-v8a/libchrootng.so") bytes)"
}

build_chrootng_arm64

echo ""
echo "Done. Guest runtime binaries installed to $PROOT_OUTPUT/"
ls -la "$PROOT_OUTPUT"/*/libproot*.so "$PROOT_OUTPUT"/arm64-v8a/libchrootng.so 2>/dev/null || true
