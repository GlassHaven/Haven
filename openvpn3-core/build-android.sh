#!/usr/bin/env bash
# Build OpenVPN3 Core (client/ovpncli.cpp + adapter.cpp) as a static
# library for all 4 Android ABIs. See CMakeLists.txt's header comment for
# why this is a standalone recipe rather than using upstream's own build
# system (upstream has none for Android as of this writing).
#
# Prerequisites: Android NDK (ANDROID_NDK_HOME, or auto-detected from
# ANDROID_HOME/ndk/<newest>), CMake+Ninja (on PATH).
#
# Output (default: core/tunnel/jniLibs-openvpn3/):
#   <ABI>/libopenvpn3_android_<suffix>.a
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CORE_DIR="$SCRIPT_DIR/openvpn3"
DEPS_DIR="$SCRIPT_DIR/.deps"

OPENVPN3_OUTPUT="${OPENVPN3_OUTPUT:-$PROJECT_ROOT/core/tunnel/jniLibs-openvpn3}"

# Auto-detect NDK the same way build-proot/build.sh does, unless the
# caller already exported ANDROID_NDK_HOME.
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo "ERROR: ANDROID_NDK_HOME or ANDROID_HOME must be set" >&2
        exit 1
    fi
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
    ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
fi
if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: could not find an NDK under \$ANDROID_HOME/ndk/" >&2
    exit 1
fi
echo "Using NDK: $ANDROID_NDK_HOME"

# Auto-detect CMake+Ninja from the Android SDK's cmake package (same
# family as the NDK auto-detect above) unless both are already on PATH.
# Needed because a Gradle daemon's PATH does not inherit ad-hoc shell
# exports from an interactive session that installed the SDK cmake.
if ! command -v cmake >/dev/null 2>&1 || ! command -v ninja >/dev/null 2>&1; then
    if [ -n "${ANDROID_HOME:-}" ]; then
        SDK_CMAKE_BIN="$(ls -d "$ANDROID_HOME"/cmake/*/bin/ 2>/dev/null | sort -V | tail -1)"
        if [ -n "$SDK_CMAKE_BIN" ]; then
            export PATH="${SDK_CMAKE_BIN%/}:$PATH"
        fi
    fi
fi
command -v cmake >/dev/null 2>&1 || { echo "ERROR: cmake not found on PATH or under \$ANDROID_HOME/cmake/" >&2; exit 1; }
command -v ninja >/dev/null 2>&1 || { echo "ERROR: ninja not found on PATH or under \$ANDROID_HOME/cmake/" >&2; exit 1; }
echo "Using cmake: $(command -v cmake)"

clone_if_missing() {
    local dir="$1" url="$2" branch="$3"
    if [ ! -d "$dir/.git" ]; then
        git clone --depth 1 --branch "$branch" "$url" "$dir"
    fi
}

mkdir -p "$DEPS_DIR"
clone_if_missing "$DEPS_DIR/mbedtls" https://github.com/Mbed-TLS/mbedtls.git v3.6.7
if [ ! -d "$DEPS_DIR/mbedtls/framework/.git" ]; then
    (cd "$DEPS_DIR/mbedtls" && git submodule update --init --depth 1 framework)
fi
clone_if_missing "$DEPS_DIR/lz4" https://github.com/lz4/lz4.git v1.10.0
clone_if_missing "$DEPS_DIR/fmt" https://github.com/fmtlib/fmt.git 11.2.0

if [ ! -d "$DEPS_DIR/asio/.git" ]; then
    git clone --depth 1 --branch asio-1-38-2 https://github.com/chriskohlhoff/asio.git "$DEPS_DIR/asio"
    for p in "$CORE_DIR"/deps/vcpkg-ports/asio/*.patch; do
        git -C "$DEPS_DIR/asio" apply -p1 "$p"
    done
fi

# ABI directory name : go-openvpn's existing cgo -l flag suffix convention
# (session.go's #cgo android,<arch> LDFLAGS lines expect exactly these).
ABIS=(
    "arm64-v8a:arm64"
    "armeabi-v7a:armeabi-v7a"
    "x86:x86"
    "x86_64:amd64"
)

for entry in "${ABIS[@]}"; do
    ABI="${entry%%:*}"
    SUFFIX="${entry##*:}"
    echo "=== Building for $ABI (suffix: $SUFFIX) ==="

    MBEDTLS_INSTALL="$DEPS_DIR/install-$ABI/mbedtls"
    LZ4_INSTALL="$DEPS_DIR/install-$ABI/lz4"

    if [ ! -f "$MBEDTLS_INSTALL/lib/libmbedtls.a" ]; then
        echo "Building mbedtls..."
        cmake -B "$DEPS_DIR/mbedtls/build-$ABI" -G Ninja -S "$DEPS_DIR/mbedtls" \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
            -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DMBEDTLS_FATAL_WARNINGS=OFF \
            -DCMAKE_INSTALL_PREFIX="$MBEDTLS_INSTALL"
        cmake --build "$DEPS_DIR/mbedtls/build-$ABI" -j"$(nproc)"
        cmake --install "$DEPS_DIR/mbedtls/build-$ABI"
    fi

    if [ ! -f "$LZ4_INSTALL/lib/liblz4.a" ]; then
        echo "Building lz4..."
        cmake -B "$DEPS_DIR/lz4/build-$ABI" -G Ninja -S "$DEPS_DIR/lz4/build/cmake" \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
            -DBUILD_SHARED_LIBS=OFF -DLZ4_BUILD_CLI=OFF \
            -DCMAKE_INSTALL_PREFIX="$LZ4_INSTALL"
        cmake --build "$DEPS_DIR/lz4/build-$ABI" -j"$(nproc)"
        cmake --install "$DEPS_DIR/lz4/build-$ABI"
    fi

    echo "Building ovpncli_static..."
    cmake -B "$SCRIPT_DIR/build-$ABI" -G Ninja -S "$SCRIPT_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
        -DCORE_DIR="$CORE_DIR" \
        -DASIO_DIR="$DEPS_DIR/asio" \
        -DFMT_DIR="$DEPS_DIR/fmt" \
        -DMBEDTLS_INSTALL="$MBEDTLS_INSTALL" \
        -DLZ4_INSTALL="$LZ4_INSTALL" \
        -DOUTPUT_NAME="openvpn3_android_$SUFFIX"
    cmake --build "$SCRIPT_DIR/build-$ABI" -j"$(nproc)"

    mkdir -p "$OPENVPN3_OUTPUT/$ABI"
    cp "$SCRIPT_DIR/build-$ABI/lib/libopenvpn3_android_$SUFFIX.a" "$OPENVPN3_OUTPUT/$ABI/"
    echo "Installed: $OPENVPN3_OUTPUT/$ABI/libopenvpn3_android_$SUFFIX.a ($(stat -c %s "$OPENVPN3_OUTPUT/$ABI/libopenvpn3_android_$SUFFIX.a") bytes)"
done

echo "Done. OpenVPN3 Core static libraries installed to $OPENVPN3_OUTPUT/"
find "$OPENVPN3_OUTPUT" -name '*.a' -exec ls -la {} \;
