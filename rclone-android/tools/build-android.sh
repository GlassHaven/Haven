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
JNI_TERMINAL_DIR="$PROJECT_DIR/jniLibs-terminal"
AAR_DIR="$PROJECT_DIR/build"

# Ensure Go is on PATH (supports /usr/local/go and GOPATH/bin)
export PATH="/usr/local/go/bin:${GOPATH:-$HOME/go}/bin:${PATH}"

# gobind needs -mod=mod to resolve golang.org/x/mobile/bind from the module cache
# -buildvcs=false: this module sits inside the Haven git repo, and gomobile
# builds it from a temp dir, so Go's VCS stamping shells out to git and fails
# with "error obtaining VCS status: exit status 128" — the whole bind aborts on
# a checkout that is otherwise fine. The stamp is of no use to us and embedding
# git state in a shipped binary works against reproducible builds anyway (#493).
# -ldflags=-checklinkname=0: netbird's interface-enumeration dependency
# (wlynxg/anet, also under tailscale) does //go:linkname into Go's net
# package internals (net.zoneCache), which Go 1.23+ refuses to link by
# default; without this the libgojni link dies with "invalid reference to
# net.zoneCache". anet is maintained with upstream Go's cooperation, so the
# check is release-valved rather than a real hazard.
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-mod=mod -buildvcs=false -ldflags=-checklinkname=0"

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
    -target=android/arm64,android/amd64,android/arm \
    -javapkg=sh.haven.rclone.binding \
    -androidapi=26 \
    -o "$AAR_DIR/rcbridge.aar" \
    . ./wgbridge ./tsbridge ./mailbridge ./nbbridge

# Second bind WITHOUT the root package (`.` == rcbridge == rclone), for the
# terminal build flavour (#510). rclone is the overwhelming bulk of this
# library: dropping it takes the stripped, compressed .so from ~28 MB to
# ~8 MB while tailscale, WireGuard and the Proton mail bridge stay.
#
# The bindings JAR from the FULL bind is the one everything compiles
# against, so `sh.haven.rclone.bridge.RcloneBridge` still exists in a
# terminal build — it just has no native code behind it, which is why
# callers gate on NativeFeatures.rclone rather than catching a link error.
echo ">>> gomobile bind (terminal flavour: no rclone)"
gomobile bind \
    -target=android/arm64,android/amd64,android/arm \
    -javapkg=sh.haven.rclone.binding \
    -androidapi=26 \
    -o "$AAR_DIR/rcbridge-terminal.aar" \
    ./wgbridge ./tsbridge ./mailbridge ./nbbridge

echo ">>> Extracting native libraries from AAR"
mkdir -p "$JNI_DIR"

# AAR is a zip; extract jni/ subdirectories
cd "$AAR_DIR"
unzip -o rcbridge.aar "jni/*" -d extracted

# Map Android ABI names
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/x86_64" "$JNI_DIR/armeabi-v7a"
cp extracted/jni/arm64-v8a/libgojni.so "$JNI_DIR/arm64-v8a/"
cp extracted/jni/x86_64/libgojni.so    "$JNI_DIR/x86_64/"
cp extracted/jni/armeabi-v7a/libgojni.so "$JNI_DIR/armeabi-v7a/"

# Terminal-flavour natives, kept in their own tree so the app can choose
# between them per flavour. Same file name — gomobile always emits
# libgojni.so — so they cannot share a directory.
unzip -o rcbridge-terminal.aar "jni/*" -d extracted-terminal
mkdir -p "$JNI_TERMINAL_DIR/arm64-v8a" "$JNI_TERMINAL_DIR/x86_64" "$JNI_TERMINAL_DIR/armeabi-v7a"
cp extracted-terminal/jni/arm64-v8a/libgojni.so   "$JNI_TERMINAL_DIR/arm64-v8a/"
cp extracted-terminal/jni/x86_64/libgojni.so      "$JNI_TERMINAL_DIR/x86_64/"
cp extracted-terminal/jni/armeabi-v7a/libgojni.so "$JNI_TERMINAL_DIR/armeabi-v7a/"

# Also extract the Java/Kotlin bindings JAR from the AAR
unzip -o rcbridge.aar "classes.jar" -d extracted
cp extracted/classes.jar "$AAR_DIR/rcbridge-bindings.jar"

# Clean up
rm -rf extracted extracted-terminal

echo ""
echo "=== Build complete ==="
ls -lh "$JNI_DIR/arm64-v8a/libgojni.so"
ls -lh "$JNI_DIR/x86_64/libgojni.so"
ls -lh "$JNI_TERMINAL_DIR/arm64-v8a/libgojni.so"
ls -lh "$AAR_DIR/rcbridge-bindings.jar"
