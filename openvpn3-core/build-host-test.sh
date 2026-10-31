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
DEPS_DIR="$SCRIPT_DIR/.deps"
SOURCES_DIR="$DEPS_DIR/sources"
CORE_DIR="$SOURCES_DIR/openvpn3"
OUTPUT_DIR="${OPENVPN3_HOST_OUTPUT:-$SCRIPT_DIR/host-test-lib}"

bash "$SCRIPT_DIR/fetch-deps.sh" --check

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

MBEDTLS_INSTALL="$DEPS_DIR/install-host/mbedtls"
LZ4_INSTALL="$DEPS_DIR/install-host/lz4"

echo "Building mbedtls (host)..."
cmake -B "$DEPS_DIR/build-host/mbedtls" -G Ninja -S "$SOURCES_DIR/mbedtls" \
    -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DMBEDTLS_FATAL_WARNINGS=OFF \
    -DCMAKE_INSTALL_PREFIX="$MBEDTLS_INSTALL"
cmake --build "$DEPS_DIR/build-host/mbedtls" -j"$(nproc)"
cmake --install "$DEPS_DIR/build-host/mbedtls"

echo "Building lz4 (host)..."
cmake -B "$DEPS_DIR/build-host/lz4" -G Ninja -S "$SOURCES_DIR/lz4/build/cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DLZ4_BUILD_CLI=OFF \
    -DCMAKE_INSTALL_PREFIX="$LZ4_INSTALL"
cmake --build "$DEPS_DIR/build-host/lz4" -j"$(nproc)"
cmake --install "$DEPS_DIR/build-host/lz4"

echo "Building ovpncli_static (host, USE_TUN_BUILDER forced)..."
cmake -B "$SCRIPT_DIR/build-host" -G Ninja -S "$SCRIPT_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS=-DUSE_TUN_BUILDER \
    -DCORE_DIR="$CORE_DIR" \
    -DASIO_DIR="$SOURCES_DIR/asio" \
    -DFMT_DIR="$SOURCES_DIR/fmt" \
    -DMBEDTLS_INSTALL="$MBEDTLS_INSTALL" \
    -DLZ4_INSTALL="$LZ4_INSTALL" \
    -DOUTPUT_NAME=openvpn3_host_amd64
cmake --build "$SCRIPT_DIR/build-host" -j"$(nproc)"

mkdir -p "$OUTPUT_DIR"
cp "$SCRIPT_DIR/build-host/lib/libopenvpn3_host_amd64.a" "$OUTPUT_DIR/"
echo "Installed: $OUTPUT_DIR/libopenvpn3_host_amd64.a ($(stat -c %s "$OUTPUT_DIR/libopenvpn3_host_amd64.a") bytes)"
