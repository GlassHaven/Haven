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
# nativeLibraryDir all work on real Android 14/15 hardware. Encoder libs are
# added incrementally (libx264 first, then the rest of the approved set) so
# each addition can be verified on device before layering the next.
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
FFMPEG_REF="${FFMPEG_REF:-n8.0}"

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

# --- Paths + shared env for dep builds -----------------------------------
# All external libs (libx264, libx265, libvpx, opus, vorbis, lame, libass,
# freetype, gnutls, …) install into this sysroot. FFmpeg's configure picks
# them up via --extra-cflags/--extra-ldflags and PKG_CONFIG_LIBDIR.
DEPS_SYSROOT="$SCRIPT_DIR/sysroot/$ABI"
DL_DIR="$SCRIPT_DIR/dl"
SRC_DIR="$SCRIPT_DIR/src"
mkdir -p "$DEPS_SYSROOT"/{lib/pkgconfig,include} "$DL_DIR" "$SRC_DIR"

export PKG_CONFIG_LIBDIR="$DEPS_SYSROOT/lib/pkgconfig"
export PKG_CONFIG_PATH="$DEPS_SYSROOT/lib/pkgconfig"
export CC CXX AR RANLIB STRIP NM

# --- Dep build helpers ---------------------------------------------------
fetch_git_tag() {
    # fetch_git_tag <name> <repo> <tag>
    local name="$1" repo="$2" tag="$3"
    local dst="$SRC_DIR/$name"
    if [ ! -d "$dst/.git" ]; then
        echo "  [fetch] $name @ $tag"
        rm -rf "$dst"
        git clone --depth 1 --branch "$tag" "$repo" "$dst" 2>&1 | tail -3
    else
        echo "  [cached] $name"
    fi
}

fetch_tarball() {
    # fetch_tarball <name> <url> <tarball-filename> <extracted-dir>
    local name="$1" url="$2" tarball="$3" extracted="$4"
    local dst="$SRC_DIR/$name"
    if [ -d "$dst" ]; then echo "  [cached] $name"; return; fi
    [ -f "$DL_DIR/$tarball" ] || {
        echo "  [download] $url"
        curl -fsSL -o "$DL_DIR/$tarball" "$url"
    }
    tar -xf "$DL_DIR/$tarball" -C "$SRC_DIR"
    if [ "$extracted" != "$name" ]; then
        mv "$SRC_DIR/$extracted" "$dst"
    fi
    echo "  [fetched] $name"
}

# Common flags used by every autotools dep.
_dep_cflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot -fPIC -O2 -I$DEPS_SYSROOT/include"
_dep_ldflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot -L$DEPS_SYSROOT/lib"

build_autotools() {
    # build_autotools <name> <marker-file> [extra configure args...]
    local name="$1" marker="$2"; shift 2
    if [ -f "$marker" ] && [ "${FORCE_REBUILD:-}" != "1" ]; then
        echo "=== $name already built ==="
        return
    fi
    echo "=== Building $name for $ABI ==="
    local SRC="$SRC_DIR/$name"
    local LOG="$SCRIPT_DIR/dep-$name.log"
    (
        cd "$SRC"
        make distclean >/dev/null 2>&1 || true
        if [ ! -x ./configure ] && [ -x ./autogen.sh ]; then
            NOCONFIGURE=1 ./autogen.sh >> "$LOG" 2>&1
        fi
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
        CFLAGS="$_dep_cflags" CXXFLAGS="$_dep_cflags" LDFLAGS="$_dep_ldflags" \
        ./configure \
            --host="${TARGET}" \
            --prefix="$DEPS_SYSROOT" \
            --disable-shared \
            --enable-static \
            "$@" \
            >> "$LOG" 2>&1
        make -j"$(nproc)" >> "$LOG" 2>&1
        make install >> "$LOG" 2>&1
    ) || { echo "ERROR: $name build failed, last 40 log lines:"; tail -40 "$LOG"; exit 1; }
    echo "  $name built"
}

build_x264() {
    local name=x264
    local marker="$DEPS_SYSROOT/lib/libx264.a"
    if [ -f "$marker" ] && [ "${FORCE_REBUILD:-}" != "1" ]; then
        echo "=== libx264 already built ==="
        return
    fi
    echo "=== Building libx264 for $ABI ==="
    # x264 moves slowly; "stable" branch is the recommended line for embedded.
    fetch_git_tag "$name" "https://code.videolan.org/videolan/x264.git" "stable"

    local SRC="$SRC_DIR/$name"
    local LOG="$SCRIPT_DIR/dep-$name.log"
    # x264's configure miscomputes SRCPATH for out-of-tree builds and fails
    # to find config.sub, so we build in-tree. `make distclean` first to
    # avoid state from prior runs polluting this one.
    (
        cd "$SRC"
        make distclean >/dev/null 2>&1 || true
        ./configure \
            --host=aarch64-linux \
            --cross-prefix="$TOOLCHAIN/bin/llvm-" \
            --prefix="$DEPS_SYSROOT" \
            --enable-static \
            --enable-pic \
            --disable-cli \
            --disable-opencl \
            --extra-cflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot -fPIC" \
            --extra-ldflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot" \
            > "$LOG" 2>&1
        make -j"$(nproc)" >> "$LOG" 2>&1
        make install >> "$LOG" 2>&1
    ) || { echo "ERROR: libx264 build failed, last 40 log lines:"; tail -40 "$LOG"; exit 1; }
    echo "  libx264: $(ls -lh "$DEPS_SYSROOT/lib/libx264.a" | awk '{print $5}')"
}

build_mp3lame() {
    fetch_tarball lame \
        "https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz" \
        "lame-3.100.tar.gz" "lame-3.100"
    build_autotools lame "$DEPS_SYSROOT/lib/libmp3lame.a" \
        --disable-frontend --disable-decoder
}

build_opus() {
    fetch_tarball opus \
        "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz" \
        "opus-1.5.2.tar.gz" "opus-1.5.2"
    build_autotools opus "$DEPS_SYSROOT/lib/libopus.a" \
        --disable-doc --disable-extra-programs
}

build_libogg() {
    fetch_tarball ogg \
        "https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.gz" \
        "libogg-1.3.5.tar.gz" "libogg-1.3.5"
    build_autotools ogg "$DEPS_SYSROOT/lib/libogg.a"
}

build_libvorbis() {
    fetch_tarball vorbis \
        "https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.gz" \
        "libvorbis-1.3.7.tar.gz" "libvorbis-1.3.7"
    build_autotools vorbis "$DEPS_SYSROOT/lib/libvorbis.a" \
        --with-ogg="$DEPS_SYSROOT" --disable-examples
}

build_vpx() {
    local marker="$DEPS_SYSROOT/lib/libvpx.a"
    if [ -f "$marker" ] && [ "${FORCE_REBUILD:-}" != "1" ]; then
        echo "=== libvpx already built ==="
        return
    fi
    echo "=== Building libvpx for $ABI ==="
    fetch_git_tag vpx "https://chromium.googlesource.com/webm/libvpx" "v1.14.1"
    local SRC="$SRC_DIR/vpx"
    local LOG="$SCRIPT_DIR/dep-vpx.log"
    local VPX_TARGET
    case "$ABI" in
        arm64-v8a) VPX_TARGET=arm64-android-gcc ;;
        x86_64)    VPX_TARGET=x86_64-android-gcc ;;
    esac
    (
        cd "$SRC"
        make distclean >/dev/null 2>&1 || true
        # libvpx's configure understands --force-target= to bypass the triple
        # whitelist, and uses CROSS= as the tool prefix.
        CROSS="$TOOLCHAIN/bin/llvm-" \
        CC="$CC" CXX="$CXX" \
        LD="$CC" AS="$CC" \
        ./configure \
            --target="$VPX_TARGET" \
            --prefix="$DEPS_SYSROOT" \
            --disable-examples \
            --disable-tools \
            --disable-docs \
            --disable-unit-tests \
            --enable-pic \
            --enable-static --disable-shared \
            --enable-vp8 --enable-vp9 \
            --extra-cflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot" \
            --extra-cxxflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot" \
            > "$LOG" 2>&1
        make -j"$(nproc)" >> "$LOG" 2>&1
        make install >> "$LOG" 2>&1
    ) || { echo "ERROR: libvpx build failed, last 40 log lines:"; tail -40 "$LOG"; exit 1; }
    echo "  libvpx built"
}

build_freetype() {
    fetch_tarball freetype \
        "https://downloads.sourceforge.net/project/freetype/freetype2/2.13.3/freetype-2.13.3.tar.xz" \
        "freetype-2.13.3.tar.xz" "freetype-2.13.3"
    build_autotools freetype "$DEPS_SYSROOT/lib/libfreetype.a" \
        --without-harfbuzz --without-bzip2 --without-png \
        --without-brotli --without-zlib
}

build_fribidi() {
    fetch_tarball fribidi \
        "https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz" \
        "fribidi-1.0.16.tar.xz" "fribidi-1.0.16"
    build_autotools fribidi "$DEPS_SYSROOT/lib/libfribidi.a" \
        --disable-docs --disable-tests
}

build_harfbuzz() {
    # Harfbuzz 9.x dropped autotools — pinned to 8.5.0 (the last autotools
    # release) so we don't have to wire up a meson toolchain file.
    # Freetype must already be built so harfbuzz can find it via pkg-config.
    fetch_tarball harfbuzz \
        "https://github.com/harfbuzz/harfbuzz/releases/download/8.5.0/harfbuzz-8.5.0.tar.xz" \
        "harfbuzz-8.5.0.tar.xz" "harfbuzz-8.5.0"
    build_autotools harfbuzz "$DEPS_SYSROOT/lib/libharfbuzz.a" \
        --with-freetype --without-glib --without-icu --without-cairo \
        --disable-introspection
}

build_mbedtls() {
    local marker="$DEPS_SYSROOT/lib/libmbedtls.a"
    if [ -f "$marker" ] && [ "${FORCE_REBUILD:-}" != "1" ]; then
        echo "=== mbedtls already built ==="
        return
    fi
    echo "=== Building mbedtls for $ABI ==="
    fetch_tarball mbedtls \
        "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.2/mbedtls-3.6.2.tar.bz2" \
        "mbedtls-3.6.2.tar.bz2" "mbedtls-3.6.2"
    local SRC="$SRC_DIR/mbedtls"
    local LOG="$SCRIPT_DIR/dep-mbedtls.log"
    local BUILD="$SCRIPT_DIR/build-$ABI/deps/mbedtls"
    rm -rf "$BUILD"
    mkdir -p "$BUILD"
    (
        cd "$BUILD"
        cmake "$SRC" \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM="android-$API" \
            -DCMAKE_INSTALL_PREFIX="$DEPS_SYSROOT" \
            -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
            -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
            -DENABLE_TESTING=OFF \
            -DENABLE_PROGRAMS=OFF \
            > "$LOG" 2>&1
        make -j"$(nproc)" >> "$LOG" 2>&1
        make install >> "$LOG" 2>&1
    ) || { echo "ERROR: mbedtls build failed, last 40 log lines:"; tail -40 "$LOG"; exit 1; }
    echo "  mbedtls built"
}

build_libass() {
    fetch_tarball libass \
        "https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.xz" \
        "libass-0.17.3.tar.xz" "libass-0.17.3"
    build_autotools libass "$DEPS_SYSROOT/lib/libass.a" \
        --disable-require-system-font-provider
}

build_x265() {
    local marker="$DEPS_SYSROOT/lib/libx265.a"
    if [ -f "$marker" ] && [ "${FORCE_REBUILD:-}" != "1" ]; then
        echo "=== libx265 already built ==="
        return
    fi
    echo "=== Building libx265 for $ABI ==="
    fetch_git_tag x265 "https://bitbucket.org/multicoreware/x265_git.git" "4.1"
    local SRC="$SRC_DIR/x265"
    local LOG="$SCRIPT_DIR/dep-x265.log"
    local BUILD="$SCRIPT_DIR/build-$ABI/deps/x265"
    rm -rf "$BUILD"
    mkdir -p "$BUILD"
    (
        cd "$BUILD"
        cmake "$SRC/source" \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM="android-$API" \
            -DCMAKE_INSTALL_PREFIX="$DEPS_SYSROOT" \
            -DENABLE_SHARED=OFF \
            -DENABLE_CLI=OFF \
            -DENABLE_LIBNUMA=OFF \
            -DENABLE_ASSEMBLY=OFF \
            -DCROSS_COMPILE_ARM64=ON \
            > "$LOG" 2>&1
        make -j"$(nproc)" >> "$LOG" 2>&1
        make install >> "$LOG" 2>&1
    ) || { echo "ERROR: libx265 build failed, last 40 log lines:"; tail -40 "$LOG"; exit 1; }

    # x265's cmake script emits a malformed Libs.private when cross-compiling
    # with clang (`-l-l:libunwind.a`). Strip the corruption so ffmpeg's
    # pkg-config --static check can parse it.
    local PC="$DEPS_SYSROOT/lib/pkgconfig/x265.pc"
    if [ -f "$PC" ]; then
        sed -i 's| -l-l:libunwind.a||g' "$PC"
    fi

    echo "  libx265 built"
}

# --- Build external deps -------------------------------------------------
# Order matters: later deps use pkg-config to discover earlier ones.
build_x264
build_mp3lame
build_opus
build_libogg
build_libvorbis
build_vpx
build_x265
build_freetype
build_fribidi
build_harfbuzz
build_libass
build_mbedtls

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
# -I / -L point at DEPS_SYSROOT so ffmpeg picks up libx264 and friends.
# Note: we do NOT use -Wl,-rpath,$ORIGIN here because ffmpeg's configure
# runs a shell eval on ldflags, which mangles $ORIGIN to empty. Instead,
# the caller must set LD_LIBRARY_PATH to point at nativeLibraryDir so the
# binary finds libc++_shared.so (pulled in by libx265's C++ code).
EXTRA_CFLAGS="-fPIE -O2 -DANDROID -I$DEPS_SYSROOT/include"
EXTRA_LDFLAGS="-pie -Wl,-z,max-page-size=16384 -L$DEPS_SYSROOT/lib"

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
        --pkg-config=pkg-config \
        --pkg-config-flags="--static" \
        --enable-gpl \
        --enable-version3 \
        --enable-libx264 \
        --enable-libx265 \
        --enable-libvpx \
        --enable-libmp3lame \
        --enable-libopus \
        --enable-libvorbis \
        --enable-libfreetype \
        --enable-libfribidi \
        --enable-libharfbuzz \
        --enable-libass \
        --enable-mbedtls \
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
        --enable-protocol=tcp \
        --enable-protocol=udp \
        --enable-protocol=tls \
        --enable-protocol=https \
        --enable-protocol=http \
        --enable-protocol=httpproxy \
        --enable-demuxer=mov \
        --enable-demuxer=matroska \
        --enable-demuxer=aac \
        --enable-demuxer=mp3 \
        --enable-demuxer=wav \
        --enable-demuxer=ogg \
        --enable-demuxer=flac \
        --enable-demuxer=avi \
        --enable-demuxer=mpegts \
        --enable-demuxer=webm_dash_manifest \
        --enable-muxer=mp4 \
        --enable-muxer=mov \
        --enable-muxer=matroska \
        --enable-muxer=webm \
        --enable-muxer=mp3 \
        --enable-muxer=wav \
        --enable-muxer=ogg \
        --enable-muxer=opus \
        --enable-muxer=hls \
        --enable-muxer=mpegts \
        --enable-muxer=null \
        --enable-decoder=h264 \
        --enable-decoder=hevc \
        --enable-decoder=mpeg4 \
        --enable-decoder=aac \
        --enable-decoder=mp3 \
        --enable-decoder=flac \
        --enable-decoder=vorbis \
        --enable-decoder=opus \
        --enable-decoder=mjpeg \
        --enable-decoder=png \
        --enable-decoder=pcm_s16le \
        --enable-encoder=mpeg4 \
        --enable-encoder=libx264 \
        --enable-encoder=libx265 \
        --enable-encoder=libvpx_vp8 \
        --enable-encoder=libvpx_vp9 \
        --enable-encoder=libmp3lame \
        --enable-encoder=libopus \
        --enable-encoder=libvorbis \
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
        --enable-filter=aresample \
        --enable-filter=ass \
        --enable-filter=subtitles \
        --enable-filter=drawtext \
        --enable-filter=eq \
        --enable-filter=curves \
        --enable-filter=colorbalance \
        --enable-filter=hue \
        --enable-filter=normalize \
        --enable-filter=unsharp \
        --enable-filter=deshake \
        --enable-filter=crop \
        --enable-filter=rotate \
        --enable-filter=transpose \
        --enable-filter=fade \
        --enable-filter=pad \
        --enable-filter=fps \
        --enable-filter=setpts \
        --enable-filter=atadenoise \
        --enable-filter=nlmeans \
        --enable-filter=overlay \
        --enable-filter=volume \
        --enable-filter=loudnorm \
        --enable-filter=afade \
        --enable-filter=atempo \
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
        # Android 15 requires >= 16 KB (0x4000). Bigger (e.g. 0x10000 = 64 KB)
        # is also fine since any multiple-of-64KB address is also 16KB-aligned.
        align_dec=$((align))
        if [ "$align_dec" -ge 16384 ]; then
            echo "  $f LOAD alignment: $align (OK: >= 16 KB)"
        else
            echo "  $f LOAD alignment: $align (FAIL: < 16 KB)" >&2
        fi
    done
else
    echo "  readelf not available; skipping alignment check"
fi

# --- Copy libc++_shared.so (runtime dep of libx265 C++ code) ------------
# libffmpeg.so and libffprobe.so link against libc++_shared.so because
# libx265 and a few other deps use C++. We rely on -Wl,-rpath,$ORIGIN so
# the binary finds it in its own directory at runtime — ship it alongside.
LIBCXX_SRC="$TOOLCHAIN/sysroot/usr/lib/${TARGET}/libc++_shared.so"
if [ ! -f "$LIBCXX_SRC" ]; then
    echo "ERROR: libc++_shared.so not found at $LIBCXX_SRC" >&2
    exit 1
fi
cp "$LIBCXX_SRC" "$BIN_DIR/libc++_shared.so"

# --- Populate spike module jniLibs (if present) --------------------------
SPIKE_JNI="$SCRIPT_DIR/spike/src/main/jniLibs/$ABI"
if [ -d "$SCRIPT_DIR/spike" ]; then
    mkdir -p "$SPIKE_JNI"
    cp "$BIN_DIR/libffmpeg.so"      "$SPIKE_JNI/libffmpeg.so"
    cp "$BIN_DIR/libffprobe.so"     "$SPIKE_JNI/libffprobe.so"
    cp "$BIN_DIR/libc++_shared.so"  "$SPIKE_JNI/libc++_shared.so"
    echo ""
    echo "=== Populated spike jniLibs ==="
    ls -lh "$SPIKE_JNI"/
fi

echo ""
echo "=== Phase 0 spike build complete ==="
echo "Next: ./gradlew :build-ffmpeg:spike:installDebug"
echo "      adb shell am start -n sh.haven.ffmpeg.spike/.SpikeActivity"
echo "      adb logcat -s FFmpegSpike:I"
