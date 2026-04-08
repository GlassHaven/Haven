#!/bin/bash
#
# Cross-compile FFmpeg for Android arm64-v8a (Phase 0 spike).
#
# Produces libffmpeg.so and libffprobe.so as ELF executables, renamed with
# the lib*.so prefix so Android's package installer extracts them to
# nativeLibraryDir — the same W^X workaround Haven already uses for PRoot
# (see build-proot/build.sh:15-16).
#
# Phase 0 goal: prove the toolchain, 16 KB page alignment, and execve-from-
# nativeLibraryDir all work on real Android 14/15 hardware. This build uses
# only FFmpeg's built-in encoders (mpeg4 video, native aac audio) — no
# libx264/libx265/etc. Those come in Phase 1 after this spike is green.
#
# Prerequisites:
#   - Android NDK r27+ (auto-detected from ~/Android/Sdk/ndk/ or $ANDROID_NDK_HOME)
#   - Host build tools: make, git, pkg-config, perl
#   - FFmpeg source: will be cloned into build-ffmpeg/src/ffmpeg on first run
#
# Usage:
#   ABI=arm64-v8a ./build.sh    (default)
#   ABI=x86_64    ./build.sh    (not tested yet)
#
# Output:
#   build-ffmpeg/build-<abi>/install/bin/libffmpeg.so
#   build-ffmpeg/build-<abi>/install/bin/libffprobe.so

set -euo pipefail
cd "$(dirname "$0")"
SCRIPT_DIR="$PWD"

ABI="${ABI:-arm64-v8a}"
API="${API:-26}"
FFMPEG_REF="${FFMPEG_REF:-n7.1.1}"

echo "=== FFmpeg Phase 0 spike build ==="
echo "ABI:        $ABI"
echo "API level:  $API"
echo "FFmpeg ref: $FFMPEG_REF"

# --- NDK auto-detect (copied from build-proot/build.sh) ------------------
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
    echo "ERROR: ANDROID_NDK_HOME not set and no NDK found under ~/Android/Sdk/ndk/" >&2
    exit 1
fi
echo "NDK:        $ANDROID_NDK_HOME"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "ERROR: toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi

case "$ABI" in
    arm64-v8a)
        ARCH="aarch64"
        TARGET="aarch64-linux-android"
        CPU_FLAG="--cpu=armv8-a"
        ;;
    x86_64)
        ARCH="x86_64"
        TARGET="x86_64-linux-android"
        CPU_FLAG=""
        ;;
    *)
        echo "ERROR: unsupported ABI: $ABI" >&2
        exit 1
        ;;
esac

CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
NM="$TOOLCHAIN/bin/llvm-nm"

for tool in "$CC" "$CXX" "$AR" "$RANLIB" "$STRIP" "$NM"; do
    [ -x "$tool" ] || { echo "ERROR: toolchain tool missing: $tool" >&2; exit 1; }
done

# --- Fetch FFmpeg source (shallow clone, throwaway) ---------------------
mkdir -p "$SCRIPT_DIR/src"
FFMPEG_SRC="$SCRIPT_DIR/src/ffmpeg"
if [ ! -d "$FFMPEG_SRC/.git" ]; then
    echo "=== Cloning FFmpeg $FFMPEG_REF ==="
    rm -rf "$FFMPEG_SRC"
    git clone --depth 1 --branch "$FFMPEG_REF" \
        https://git.ffmpeg.org/ffmpeg.git "$FFMPEG_SRC"
else
    echo "=== FFmpeg source already present at $FFMPEG_SRC ==="
    (cd "$FFMPEG_SRC" && git fetch --depth 1 origin "$FFMPEG_REF" 2>/dev/null || true)
fi

# --- Configure + build ---------------------------------------------------
BUILD_DIR="$SCRIPT_DIR/build-$ABI"
INSTALL_DIR="$BUILD_DIR/install"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo "=== Configuring FFmpeg for $ABI ==="

# PIE is required for Android 23+ binaries.
# -z max-page-size=16384 is required for Android 15 16 KB page alignment.
EXTRA_CFLAGS="-fPIE -O2 -DANDROID"
EXTRA_LDFLAGS="-pie -Wl,-z,max-page-size=16384"

# Minimal set: just enough to prove encoding + probing works. No external libs.
# Built-in encoders used for smoke testing:
#   - mpeg4 video (built in, no libx264 dependency)
#   - aac audio (native FFmpeg encoder, no fdk-aac dependency)
#   - rawvideo (passthrough sanity check)
(
    cd "$BUILD_DIR"
    "$FFMPEG_SRC/configure" \
        --prefix="$INSTALL_DIR" \
        --target-os=android \
        --arch="$ARCH" \
        $CPU_FLAG \
        --enable-cross-compile \
        --cross-prefix="$TOOLCHAIN/bin/llvm-" \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --ranlib="$RANLIB" \
        --strip="$STRIP" \
        --nm="$NM" \
        --sysroot="$TOOLCHAIN/sysroot" \
        --extra-cflags="$EXTRA_CFLAGS" \
        --extra-ldflags="$EXTRA_LDFLAGS" \
        --enable-pic \
        --disable-doc \
        --disable-htmlpages \
        --disable-manpages \
        --disable-podpages \
        --disable-txtpages \
        --disable-debug \
        --enable-small \
        --disable-everything \
        --disable-ffplay \
        --enable-ffmpeg \
        --enable-ffprobe \
        --enable-avcodec \
        --enable-avformat \
        --enable-avutil \
        --enable-swscale \
        --enable-swresample \
        --enable-avfilter \
        --enable-protocol=file \
        --enable-protocol=pipe \
        --enable-demuxer=mov \
        --enable-demuxer=matroska \
        --enable-demuxer=aac \
        --enable-demuxer=mp3 \
        --enable-demuxer=wav \
        --enable-muxer=mp4 \
        --enable-muxer=mov \
        --enable-muxer=matroska \
        --enable-muxer=mp3 \
        --enable-muxer=wav \
        --enable-muxer=null \
        --enable-decoder=h264 \
        --enable-decoder=hevc \
        --enable-decoder=mpeg4 \
        --enable-decoder=aac \
        --enable-decoder=mp3 \
        --enable-decoder=pcm_s16le \
        --enable-encoder=mpeg4 \
        --enable-encoder=aac \
        --enable-encoder=pcm_s16le \
        --enable-encoder=rawvideo \
        --enable-parser=h264 \
        --enable-parser=hevc \
        --enable-parser=aac \
        --enable-parser=mpeg4video \
        --enable-filter=scale \
        --enable-filter=null \
        --enable-filter=anull \
        --enable-filter=aformat \
        --enable-filter=format \
        --enable-bsf=h264_mp4toannexb \
        --enable-bsf=hevc_mp4toannexb \
        --enable-bsf=aac_adtstoasc \
        2>&1 | tee configure.log | tail -40
    echo "(full configure log: $BUILD_DIR/configure.log)"
)

echo "=== Building FFmpeg for $ABI ==="
(
    cd "$BUILD_DIR"
    make -j"$(nproc)" 2>&1 | tail -30
    make install 2>&1 | tail -10
)

# --- Rename binaries to lib*.so for Android nativeLibraryDir extraction --
BIN_DIR="$INSTALL_DIR/bin"
if [ ! -x "$BIN_DIR/ffmpeg" ] || [ ! -x "$BIN_DIR/ffprobe" ]; then
    echo "ERROR: ffmpeg/ffprobe binaries not produced in $BIN_DIR" >&2
    ls -la "$BIN_DIR" 2>&1 >&2 || true
    exit 1
fi

"$STRIP" "$BIN_DIR/ffmpeg" "$BIN_DIR/ffprobe"
cp "$BIN_DIR/ffmpeg"  "$BIN_DIR/libffmpeg.so"
cp "$BIN_DIR/ffprobe" "$BIN_DIR/libffprobe.so"

echo ""
echo "=== Output ==="
ls -lh "$BIN_DIR/libffmpeg.so" "$BIN_DIR/libffprobe.so"
file   "$BIN_DIR/libffmpeg.so" "$BIN_DIR/libffprobe.so" 2>/dev/null || true

# --- 16 KB page alignment check ------------------------------------------
echo ""
echo "=== 16 KB page alignment check ==="
if command -v readelf >/dev/null 2>&1; then
    for f in libffmpeg.so libffprobe.so; do
        # -W = wide output, Align is the last column of the LOAD row
        align=$(readelf -lW "$BIN_DIR/$f" 2>/dev/null \
            | awk '/^  LOAD/ {print $NF; exit}')
        echo "  $f LOAD alignment: $align (need 0x4000 for Android 15)"
        if [ "$align" != "0x4000" ]; then
            echo "    WARNING: expected 0x4000" >&2
        fi
    done
else
    echo "  readelf not available; skipping alignment check"
fi

# --- Populate spike module jniLibs (if present) --------------------------
SPIKE_JNI="$SCRIPT_DIR/spike/src/main/jniLibs/$ABI"
if [ -d "$SCRIPT_DIR/spike" ]; then
    mkdir -p "$SPIKE_JNI"
    cp "$BIN_DIR/libffmpeg.so"  "$SPIKE_JNI/libffmpeg.so"
    cp "$BIN_DIR/libffprobe.so" "$SPIKE_JNI/libffprobe.so"
    echo ""
    echo "=== Populated spike jniLibs ==="
    ls -lh "$SPIKE_JNI"/
fi

echo ""
echo "=== Phase 0 spike build complete ==="
echo "Next: ./gradlew :build-ffmpeg:spike:installDebug"
echo "      adb shell am start -n sh.haven.ffmpeg.spike/.SpikeActivity"
echo "      adb logcat -s FFmpegSpike:I"
