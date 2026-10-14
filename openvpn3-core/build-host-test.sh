#!/usr/bin/env bash
# Build OpenVPN3 Core for host linux/amd64 -- NOT shipped in the app, exists
# solely so `go test` can produce a real linked test binary on the machine
# running dev/CI (a cross-compiled-for-Android-only cgo archive can compile
# but never link on host, see session.go's #cgo android,* LDFLAGS lines).
#
# Two things differ from build-android.sh's per-ABI loop:
#   1. No NDK / CMAKE_TOOLCHAIN_FILE -- plain native cmake against the host
#      toolchain (g++).
#   2. USE_TUN_BUILDER must be forced via CMAKE_CXX_FLAGS: client/ovpncli.cpp
#      only auto-enables it when OPENVPN_PLATFORM_ANDROID or _IPHONE is
#      defined (see its own `#if` guard), neither of which is true for a
#      host Linux build, so without this the client would silently link
#      against tunnull.hpp's no-op tun device instead of exercising
#      adapter.cpp's tun_builder_* overrides at all.
#
# Output: openvpn3-core/host-test-lib/libopenvpn3_host_amd64.a -- deliberately
# outside core/tunnel/jniLibs-openvpn3/ (that tree is only ever ABI-named
# Android output; mixing a host artifact in would be confusing even though
# AGP would just ignore an unrecognized subdirectory name).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CORE_DIR="$SCRIPT_DIR/openvpn3"
DEPS_DIR="$SCRIPT_DIR/.deps"
OUTPUT_DIR="${OPENVPN3_HOST_OUTPUT:-$SCRIPT_DIR/host-test-lib}"

# Same SDK-cmake auto-detect as build-android.sh, for the same reason: a
# Gradle daemon (or a fresh shell) doesn't inherit an interactive session's
# ad-hoc PATH exports, and this host environment has no system cmake/ninja.
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

MBEDTLS_INSTALL="$DEPS_DIR/install-host/mbedtls"
LZ4_INSTALL="$DEPS_DIR/install-host/lz4"

if [ ! -f "$MBEDTLS_INSTALL/lib/libmbedtls.a" ]; then
    echo "Building mbedtls (host)..."
    cmake -B "$DEPS_DIR/mbedtls/build-host" -G Ninja -S "$DEPS_DIR/mbedtls" \
        -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DMBEDTLS_FATAL_WARNINGS=OFF \
        -DCMAKE_INSTALL_PREFIX="$MBEDTLS_INSTALL"
    cmake --build "$DEPS_DIR/mbedtls/build-host" -j"$(nproc)"
    cmake --install "$DEPS_DIR/mbedtls/build-host"
fi

if [ ! -f "$LZ4_INSTALL/lib/liblz4.a" ]; then
    echo "Building lz4 (host)..."
    cmake -B "$DEPS_DIR/lz4/build-host" -G Ninja -S "$DEPS_DIR/lz4/build/cmake" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF -DLZ4_BUILD_CLI=OFF \
        -DCMAKE_INSTALL_PREFIX="$LZ4_INSTALL"
    cmake --build "$DEPS_DIR/lz4/build-host" -j"$(nproc)"
    cmake --install "$DEPS_DIR/lz4/build-host"
fi

echo "Building ovpncli_static (host, USE_TUN_BUILDER forced)..."
cmake -B "$SCRIPT_DIR/build-host" -G Ninja -S "$SCRIPT_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS=-DUSE_TUN_BUILDER \
    -DCORE_DIR="$CORE_DIR" \
    -DASIO_DIR="$DEPS_DIR/asio" \
    -DFMT_DIR="$DEPS_DIR/fmt" \
    -DMBEDTLS_INSTALL="$MBEDTLS_INSTALL" \
    -DLZ4_INSTALL="$LZ4_INSTALL" \
    -DOUTPUT_NAME=openvpn3_host_amd64
cmake --build "$SCRIPT_DIR/build-host" -j"$(nproc)"

mkdir -p "$OUTPUT_DIR"
cp "$SCRIPT_DIR/build-host/lib/libopenvpn3_host_amd64.a" "$OUTPUT_DIR/"
echo "Installed: $OUTPUT_DIR/libopenvpn3_host_amd64.a ($(stat -c %s "$OUTPUT_DIR/libopenvpn3_host_amd64.a") bytes)"
