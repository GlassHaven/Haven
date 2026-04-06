#!/usr/bin/env bash
# Build rclone Go bridge for Android via gomobile.
# Produces jniLibs/{arm64-v8a,x86_64}/libgojni.so
#
# Prerequisites:
#   - Go 1.26+ (on PATH, or /usr/local/go/bin)
#   - gomobile  (go install golang.org/x/mobile/cmd/gomobile@latest)
#   - gobind    (go install golang.org/x/mobile/cmd/gobind@latest)
#   - Android NDK (ANDROID_NDK_HOME env var, or auto-detected from ANDROID_HOME)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GO_DIR="$PROJECT_DIR/go"
JNI_DIR="$PROJECT_DIR/jniLibs"
AAR_DIR="$PROJECT_DIR/build"

# Ensure Go is on PATH (supports /usr/local/go and GOPATH/bin)
export PATH="/usr/local/go/bin:${GOPATH:-$HOME/go}/bin:${PATH}"

# gobind needs -mod=mod to resolve golang.org/x/mobile/bind from the module cache
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-mod=mod"

# Detect Android SDK/NDK from environment
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must be set" >&2
    exit 1
fi
export ANDROID_HOME

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Pick the newest NDK version available under ANDROID_HOME/ndk
    NDK_DIR="$ANDROID_HOME/ndk"
    if [ -d "$NDK_DIR" ]; then
        ANDROID_NDK_HOME="$(ls -d "$NDK_DIR"/*/ 2>/dev/null | sort -V | tail -1)"
        ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
    fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME must be set or an NDK must exist under \$ANDROID_HOME/ndk/" >&2
    exit 1
fi
export ANDROID_NDK_HOME

echo "=== rclone-android build ==="
echo "Go:           $(go version)"
echo "ANDROID_HOME: $ANDROID_HOME"
echo "NDK:          $ANDROID_NDK_HOME"
echo "Source:       $GO_DIR"
echo "Output:       $JNI_DIR"
echo ""

# Initialize gomobile (downloads NDK toolchain bindings)
echo ">>> gomobile init"
gomobile init

# Build AAR via gomobile bind
echo ">>> gomobile bind (arm64 + amd64)"
mkdir -p "$AAR_DIR"
cd "$GO_DIR"

gomobile bind \
    -target=android/arm64,android/amd64 \
    -javapkg=sh.haven.rclone.binding \
    -androidapi=26 \
    -o "$AAR_DIR/rcbridge.aar" \
    .

echo ">>> Extracting native libraries from AAR"
mkdir -p "$JNI_DIR"

# AAR is a zip; extract jni/ subdirectories
cd "$AAR_DIR"
unzip -o rcbridge.aar "jni/*" -d extracted

# Map Android ABI names
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/x86_64"
cp extracted/jni/arm64-v8a/libgojni.so "$JNI_DIR/arm64-v8a/"
cp extracted/jni/x86_64/libgojni.so    "$JNI_DIR/x86_64/"

# Also extract the Java/Kotlin bindings JAR from the AAR
unzip -o rcbridge.aar "classes.jar" -d extracted
cp extracted/classes.jar "$AAR_DIR/rcbridge-bindings.jar"

# Clean up
rm -rf extracted

echo ""
echo "=== Build complete ==="
ls -lh "$JNI_DIR/arm64-v8a/libgojni.so"
ls -lh "$JNI_DIR/x86_64/libgojni.so"
ls -lh "$AAR_DIR/rcbridge-bindings.jar"
