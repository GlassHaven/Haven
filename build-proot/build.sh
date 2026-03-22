#!/bin/bash
#
# Cross-compile PRoot for Android arm64-v8a and x86_64.
#
# Prerequisites:
#   - Android NDK r27+ (set ANDROID_NDK_HOME or auto-detected from ~/Android/Sdk/ndk/)
#   - Standard build tools: make, git
#
# Output:
#   core/local/src/main/jniLibs/arm64-v8a/libproot.so
#   core/local/src/main/jniLibs/x86_64/libproot.so
#
# PRoot is named libproot.so so Android extracts it to nativeLibraryDir,
# making it executable on Android 14+ (which blocks exec from app data dirs).

set -euo pipefail
cd "$(dirname "$0")"

PROOT_REPO="https://github.com/termux/proot"
PROOT_COMMIT="ab2e3464d04483b98a0614b470f3f8950d5a6468"
TALLOC_VERSION="2.4.2"

# Auto-detect NDK
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    NDK_BASE="$HOME/Android/Sdk/ndk"
    if [ -d "$NDK_BASE" ]; then
        ANDROID_NDK_HOME=$(ls -d "$NDK_BASE"/*/ 2>/dev/null | sort -V | tail -1)
        ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
    fi
fi

if [ ! -d "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set and NDK not found in ~/Android/Sdk/ndk/"
    exit 1
fi
echo "Using NDK: $ANDROID_NDK_HOME"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
API=26  # minSdk

# Download sources
mkdir -p dl
if [ ! -d "dl/proot-termux" ]; then
    echo "Cloning Termux PRoot..."
    git clone --depth 1 "$PROOT_REPO" "dl/proot-termux"
    (cd "dl/proot-termux" && git checkout "$PROOT_COMMIT" 2>/dev/null || true)
fi

if [ ! -f "dl/talloc-$TALLOC_VERSION.tar.gz" ]; then
    echo "Downloading talloc $TALLOC_VERSION..."
    curl -L -o "dl/talloc-$TALLOC_VERSION.tar.gz" \
        "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
fi

PROJECT_ROOT="$(cd .. && pwd)"
JNILIBS="$PROJECT_ROOT/core/local/src/main/jniLibs"

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
    local TALLOC_SRC="$BUILD_DIR/talloc-$TALLOC_VERSION"
    tar xzf "dl/talloc-$TALLOC_VERSION.tar.gz" -C "$BUILD_DIR"

    local TALLOC_INSTALL="$PWD/$BUILD_DIR/talloc-install"
    mkdir -p "$TALLOC_INSTALL/lib" "$TALLOC_INSTALL/include"

    # talloc is essentially one .c file — compile directly
    (
        cd "$TALLOC_SRC"
        # Generate config.h with version defines and feature flags
        cat > config.h << 'CFG_EOF'
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_FUNCTION_ATTRIBUTE_FORMAT 1
CFG_EOF
        # Generate lib/replace/replace.h stub
        mkdir -p lib/replace
        cat > lib/replace/replace.h << 'REP_EOF'
/* stub — no replacements needed on Android */
#ifndef _REPLACE_H
#define _REPLACE_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#endif
REP_EOF

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

    # Build PRoot (Termux fork)
    echo "Building PRoot (Termux fork)..."
    local PROOT_SRC="$BUILD_DIR/proot-termux"
    cp -a "dl/proot-termux" "$PROOT_SRC"

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
            LDFLAGS="-L$TALLOC_INSTALL/lib -ltalloc -static" \
            CARE_LDFLAGS="" \
            HAS_SWIG="" \
            HAS_PYTHON_CONFIG="" \
            V=1 \
            proot 2>&1 | tail -15

        "$STRIP" proot
        file proot
        ls -la proot
    )

    # Install proot + loader
    mkdir -p "$JNILIBS/$ABI"
    cp "$PROOT_SRC/src/proot" "$JNILIBS/$ABI/libproot.so"
    if [ -f "$PROOT_SRC/src/loader/loader" ]; then
        "$STRIP" "$PROOT_SRC/src/loader/loader"
        cp "$PROOT_SRC/src/loader/loader" "$JNILIBS/$ABI/libproot_loader.so"
    fi
    echo "Installed: $JNILIBS/$ABI/libproot.so ($(stat -c %s "$JNILIBS/$ABI/libproot.so") bytes)"
}

build_for_arch "aarch64" "aarch64-linux-android" "arm64-v8a"
build_for_arch "x86_64" "x86_64-linux-android" "x86_64"

echo ""
echo "Done. PRoot binaries installed to core/local/src/main/jniLibs/"
ls -la "$JNILIBS"/*/libproot.so
