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
        # NDK sysroot lib dir for libc++_shared.so (== TARGET here)
        SYSROOT_LIB="aarch64-linux-android"
        ;;
    x86_64)
        ARCH="x86_64"
        TARGET="x86_64-linux-android"
        CPU_FLAG=""
        SYSROOT_LIB="x86_64-linux-android"
        ;;
    armeabi-v7a)
        ARCH="arm"
        # Compiler triple is armv7a-linux-androideabi, but the NDK sysroot
        # lib dir for 32-bit ARM is arm-linux-androideabi (no "v7a").
        TARGET="armv7a-linux-androideabi"
        CPU_FLAG="--cpu=armv7-a"
        SYSROOT_LIB="arm-linux-androideabi"
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

# x265 4.1 and mbedtls's CMakeLists call cmake_policy(SET CMP00xx OLD) /
# cmake_minimum_required < 3.5, which CMake 4.x refuses. Prefer the SDK's
# bundled 3.x cmake (which still accepts those) over a host cmake 4.x.
SDK_ROOT="$(cd "$ANDROID_NDK_HOME/../.." && pwd)"
# `ls A B` where only one glob matches still exits non-zero (the unmatched
# glob is a "not found" arg), which under `set -euo pipefail` kills the
# script — the F-Droid build installs only cmake 3.31.x, so the 3.22.* glob
# never matches and the build died silently right here. `|| true` keeps the
# happy path (ls prints the found cmake) while tolerating the partial miss;
# the ${...:-cmake} fallback below covers the "neither found" case.
CMAKE_BIN="$(ls -d "$SDK_ROOT"/cmake/3.31.*/bin/cmake "$SDK_ROOT"/cmake/3.22.*/bin/cmake 2>/dev/null | head -1 || true)"
CMAKE_BIN="${CMAKE_BIN:-cmake}"
echo "CMake:      $CMAKE_BIN ($("$CMAKE_BIN" --version 2>/dev/null | head -1))"

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
#
# Everything fetched here is PINNED AND VERIFIED, because everything fetched
# here is compiled into a binary that ships in the APK.
#
# F-Droid's rule is that a binary in the APK must be reproducible from source in
# their builder; the accepted recipes pin their sources to do it (mpv-android:
# `srclibs: FFmpeg@a7425f7`, and for a plain download
# `curl -Lo lua.tar.gz … ; echo "<sha256>  lua.tar.gz" | sha256sum -c -`).
# Fetching an unpinned tarball over HTTPS satisfies neither half: TLS says who
# served the bytes, never which bytes, and an unpinned ref says nothing at all.
#
# The hashes below were recorded from each project's canonical HTTPS URL and
# cross-checked against an independent re-download. They are trust-on-first-use
# pins, NOT verification against upstream signatures — they prove the source has
# not changed since it was pinned, which is what catches a substitution or a
# silently re-rolled release. Verifying signatures would be strictly better and
# is not what is implemented here.
#
# To bump a dependency: change the version, run the build, take the hash from
# the failure message, and check it against upstream before committing it.

fetch_git_tag() {
    # fetch_git_tag <name> <repo> <ref> <expected-commit-sha> [mirror-url ...]
    local name="$1" repo="$2" ref="$3" want="$4"
    shift 4
    local mirrors=("$@")
    local dst="$SRC_DIR/$name"
    if [ ! -d "$dst/.git" ]; then
        echo "  [fetch] $name @ $ref"
        # Retry, for the same reason fetch_tarball retries and FFmpeg carries a
        # mirror list: one unreachable host aborts the entire build. bitbucket.org
        # stopped answering for 300s on CI run 30887507336 (2026-08-04) and took
        # the arm64 build down with it, on a change that touched no native code.
        #
        # git has no --connect-timeout, so each attempt is bounded externally.
        # A healthy --depth 1 clone of the largest of these is ~1s, so 60s is
        # slack rather than a limit, and three bounded attempts cost less on the
        # bad path than the single 300s hang did.
        local attempt
        for attempt in 1 2 3; do
            rm -rf "$dst"
            if timeout 60 git clone --depth 1 --branch "$ref" "$repo" "$dst" 2>&1 | tail -3; then
                break
            fi
            echo "  clone of $name failed (attempt $attempt/3) — retrying" >&2
            sleep 5
        done
        # Mirrors, for when retrying cannot help: an outage outlasts three
        # bounded attempts. code.videolan.org went unreachable on 2026-08-08 and
        # took the F-Droid build of 5.87.1 down at the x264 clone; measured at
        # 2 successes in 8 probes while it recovered, so a retry was a coin flip.
        #
        # Fetched by COMMIT, not by ref, and that is the whole point. A mirror's
        # branch tip is its own business — github.com/mirror/x264 has `stable` at
        # 31e19f92 while our pin is b35605ac — so asking for the branch would
        # fetch different source and fail the check below. Asking for the exact
        # object either produces that object or fails outright.
        #
        # This is why a mirror costs nothing in trust: the pin verification is
        # unchanged and runs against whatever we end up with, so a hostile or
        # stale mirror is caught exactly as a tampered primary would be. Every
        # mirror listed was confirmed to carry the pinned commit before being
        # added, rather than assumed to be a faithful copy.
        if [ ! -d "$dst/.git" ] && [ ${#mirrors[@]} -gt 0 ]; then
            local mirror
            for mirror in "${mirrors[@]}"; do
                echo "  [mirror] $name <- $mirror" >&2
                rm -rf "$dst"
                mkdir -p "$dst"
                if (
                    cd "$dst" &&
                    git init -q . &&
                    git remote add origin "$mirror" &&
                    timeout 120 git fetch --depth 1 -q origin "$want" &&
                    git checkout -q FETCH_HEAD
                ) 2>&1 | tail -3; then
                    break
                fi
                echo "  mirror failed for $name: $mirror" >&2
                rm -rf "$dst"
            done
        fi
        # Checked rather than trusted to the loop: the pin verification below
        # reads $dst, and must never run against a directory a failed clone left.
        if [ ! -d "$dst/.git" ]; then
            echo "ERROR: could not clone $name from $repo @ $ref" >&2
            if [ ${#mirrors[@]} -gt 0 ]; then
                echo "       ${#mirrors[@]} mirror(s) also failed" >&2
            fi
            exit 1
        fi
    else
        echo "  [cached] $name"
    fi
    # Verify the commit, not the ref name. A tag can be moved and a branch moves
    # by definition — x264 was tracked at `stable`, a branch, so every build was
    # free to compile different source with nothing to notice it by.
    local got
    got="$(cd "$dst" && git rev-parse HEAD)"
    if [ "$got" != "$want" ]; then
        echo "ERROR: $name is at $got, expected $want" >&2
        echo "       ($repo @ $ref)" >&2
        echo "       The ref moved. Confirm the new commit is legitimate, then update the pin." >&2
        exit 1
    fi
}

fetch_tarball() {
    # fetch_tarball <name> <url> <tarball-filename> <extracted-dir> <sha256> [mirror-url ...]
    local name="$1" url="$2" tarball="$3" extracted="$4" sha="$5"
    shift 5
    local mirrors=("$@")
    local dst="$SRC_DIR/$name"
    if [ -d "$dst" ]; then echo "  [cached] $name"; return; fi
    if [ ! -f "$DL_DIR/$tarball" ]; then
        # Retry on transient network blips, then fall back to mirrors verified
        # against the exact same pinned SHA-256 (e.g. when GitHub Releases
        # returns HTTP 504 on harfbuzz-8.5.0.tar.xz, Release run 42463693347).
        #
        # --retry-all-errors is doing the real work here and is not optional.
        # curl's --retry alone covers only "a timeout, an FTP 4xx response
        # code or an HTTP 408, 429, 500, 502, 503, 504, 522 or 524" — a TLS
        # handshake failure is not on that list.
        local candidate
        for candidate in "$url" "${mirrors[@]}"; do
            echo "  [download] $candidate"
            rm -f "$DL_DIR/$tarball"
            if curl -fsSL --retry 3 --retry-delay 5 --retry-connrefused --retry-all-errors \
                --connect-timeout 15 --max-time 600 \
                -o "$DL_DIR/$tarball" "$candidate"; then
                break
            fi
            echo "  download failed from $candidate — trying next mirror" >&2
            rm -f "$DL_DIR/$tarball"
        done
        if [ ! -f "$DL_DIR/$tarball" ]; then
            echo "ERROR: could not download $tarball from $url or ${#mirrors[@]} mirror(s)" >&2
            exit 22
        fi
    fi
    # Checked on every run, not just after a download: a cached tarball from an
    # earlier build is exactly as unverified as a fresh one.
    local got
    got="$(sha256sum "$DL_DIR/$tarball" | cut -d' ' -f1)"
    if [ "$got" != "$sha" ]; then
        echo "ERROR: $tarball sha256 mismatch" >&2
        echo "       expected $sha" >&2
        echo "       got      $got" >&2
        echo "       Refusing to build. Delete $DL_DIR/$tarball to re-download," >&2
        echo "       or update the pin if the release was legitimately re-rolled." >&2
        exit 1
    fi
    tar -xf "$DL_DIR/$tarball" -C "$SRC_DIR"
    if [ "$extracted" != "$name" ]; then
        mv "$SRC_DIR/$extracted" "$dst"
    fi
    echo "  [fetched+verified] $name"
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
    # NB: `stable` is a BRANCH — it moves. The pin is what makes this
    # reproducible; without it every build could compile different x264.
    fetch_git_tag "$name" "https://code.videolan.org/videolan/x264.git" "stable" \
        "b35605ace3ddf7c1a5d67a2eb553f034aef41d55" \
        "https://github.com/mirror/x264.git"

    local SRC="$SRC_DIR/$name"
    local LOG="$SCRIPT_DIR/dep-$name.log"
    # x264's configure miscomputes SRCPATH for out-of-tree builds and fails
    # to find config.sub, so we build in-tree. `make distclean` first to
    # avoid state from prior runs polluting this one.
    (
        cd "$SRC"
        make distclean >/dev/null 2>&1 || true
        ./configure \
            --host="${ARCH}-linux" \
            --cross-prefix="$TOOLCHAIN/bin/llvm-" \
            --prefix="$DEPS_SYSROOT" \
            --enable-static \
            --enable-pic \
            --disable-cli \
            --disable-opencl \
            --extra-cflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot -fPIC" \
            --extra-ldflags="--target=${TARGET}${API} --sysroot=$TOOLCHAIN/sysroot" \
            > "$LOG" 2>&1
        # x264's Makefile ignores --prefix for the install targets (hardwired
        # /usr/local paths in $(bindir)/$(libdir)/$(includedir)), which blows
        # up on unprivileged buildservers like F-Droid. Skip `make install`
        # entirely and copy the artifacts ourselves — this is all FFmpeg
        # actually needs to link against libx264.
        make -j"$(nproc)" lib-static >> "$LOG" 2>&1
        mkdir -p "$DEPS_SYSROOT/lib/pkgconfig" "$DEPS_SYSROOT/include" >> "$LOG" 2>&1
        cp libx264.a "$DEPS_SYSROOT/lib/" >> "$LOG" 2>&1
        cp x264.h x264_config.h "$DEPS_SYSROOT/include/" >> "$LOG" 2>&1
        cp x264.pc "$DEPS_SYSROOT/lib/pkgconfig/" >> "$LOG" 2>&1
    ) || { echo "ERROR: libx264 build failed, last 40 log lines:"; tail -40 "$LOG"; exit 1; }
    echo "  libx264: $(ls -lh "$DEPS_SYSROOT/lib/libx264.a" | awk '{print $5}')"
}

build_mp3lame() {
    fetch_tarball lame \
        "https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz" \
        "lame-3.100.tar.gz" "lame-3.100" \
        "ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e" \
        "https://distfiles.macports.org/lame/lame-3.100.tar.gz" \
        "https://sources.buildroot.net/lame/lame-3.100.tar.gz"
    build_autotools lame "$DEPS_SYSROOT/lib/libmp3lame.a" \
        --disable-frontend --disable-decoder
}

build_opus() {
    fetch_tarball opus \
        "https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz" \
        "opus-1.5.2.tar.gz" "opus-1.5.2" \
        "65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1" \
        "https://distfiles.macports.org/libopus/opus-1.5.2.tar.gz" \
        "https://sources.buildroot.net/opus/opus-1.5.2.tar.gz"
    build_autotools opus "$DEPS_SYSROOT/lib/libopus.a" \
        --disable-doc --disable-extra-programs
}

build_libogg() {
    fetch_tarball ogg \
        "https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.gz" \
        "libogg-1.3.5.tar.gz" "libogg-1.3.5" \
        "0eb4b4b9420a0f51db142ba3f9c64b333f826532dc0f48c6410ae51f4799b664"
    build_autotools ogg "$DEPS_SYSROOT/lib/libogg.a"
}

build_libvorbis() {
    fetch_tarball vorbis \
        "https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.gz" \
        "libvorbis-1.3.7.tar.gz" "libvorbis-1.3.7" \
        "0e982409a9c3fc82ee06e08205b1355e5c6aa4c36bca58146ef399621b0ce5ab" \
        "https://distfiles.macports.org/libvorbis/libvorbis-1.3.7.tar.gz"
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
    fetch_git_tag vpx "https://chromium.googlesource.com/webm/libvpx" "v1.14.1" \
        "12f3a2ac603e8f10742105519e0cd03c3b8f71dd" \
        "https://github.com/webmproject/libvpx.git"
    local SRC="$SRC_DIR/vpx"
    local LOG="$SCRIPT_DIR/dep-vpx.log"
    local VPX_TARGET VPX_AS
    case "$ABI" in
        arm64-v8a)
            VPX_TARGET=arm64-android-gcc
            # clang assembles NEON/inline-asm directly for ARM
            VPX_AS="$CC"
            ;;
        x86_64)
            VPX_TARGET=x86_64-android-gcc
            # libvpx's x86 assembly is nasm syntax; clang can't parse it
            # (fails with `clang: error: unknown argument: '-f'`).
            VPX_AS="nasm"
            ;;
        armeabi-v7a)
            VPX_TARGET=armv7-android-gcc
            # clang assembles NEON/inline-asm directly for ARM
            VPX_AS="$CC"
            ;;
    esac
    # armv7's hand-written NEON .asm (RVCT syntax) can't be assembled by clang;
    # disable just the asm and keep the NEON *intrinsics* so it still builds.
    local VPX_EXTRA=""
    [ "$ABI" = armeabi-v7a ] && VPX_EXTRA="--disable-neon_asm"
    (
        cd "$SRC"
        make distclean >/dev/null 2>&1 || true
        # libvpx's configure understands --force-target= to bypass the triple
        # whitelist, and uses CROSS= as the tool prefix.
        CROSS="$TOOLCHAIN/bin/llvm-" \
        CC="$CC" CXX="$CXX" \
        LD="$CC" AS="$VPX_AS" \
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
            $VPX_EXTRA \
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
        "freetype-2.13.3.tar.xz" "freetype-2.13.3" \
        "0550350666d427c74daeb85d5ac7bb353acba5f76956395995311a9c6f063289" \
        "https://download.savannah.gnu.org/releases/freetype/freetype-2.13.3.tar.xz" \
        "https://distfiles.macports.org/freetype/freetype-2.13.3.tar.xz" \
        "https://sources.buildroot.net/freetype/freetype-2.13.3.tar.xz"
    build_autotools freetype "$DEPS_SYSROOT/lib/libfreetype.a" \
        --without-harfbuzz --without-bzip2 --without-png \
        --without-brotli --without-zlib
}

build_fribidi() {
    fetch_tarball fribidi \
        "https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz" \
        "fribidi-1.0.16.tar.xz" "fribidi-1.0.16" \
        "1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c" \
        "https://distfiles.macports.org/fribidi/fribidi-1.0.16.tar.xz"
    build_autotools fribidi "$DEPS_SYSROOT/lib/libfribidi.a" \
        --disable-docs --disable-tests
}

build_harfbuzz() {
    # Harfbuzz 9.x dropped autotools — pinned to 8.5.0 (the last autotools
    # release) so we don't have to wire up a meson toolchain file.
    # Freetype must already be built so harfbuzz can find it via pkg-config.
    fetch_tarball harfbuzz \
        "https://github.com/harfbuzz/harfbuzz/releases/download/8.5.0/harfbuzz-8.5.0.tar.xz" \
        "harfbuzz-8.5.0.tar.xz" "harfbuzz-8.5.0" \
        "77e4f7f98f3d86bf8788b53e6832fb96279956e1c3961988ea3d4b7ca41ddc27" \
        "https://distfiles.macports.org/harfbuzz/harfbuzz-8.5.0.tar.xz"
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
        "mbedtls-3.6.2.tar.bz2" "mbedtls-3.6.2" \
        "8b54fb9bcf4d5a7078028e0520acddefb7900b3e66fec7f7175bb5b7d85ccdca" \
        "https://distfiles.macports.org/mbedtls3/mbedtls-3.6.2.tar.bz2"
    local SRC="$SRC_DIR/mbedtls"
    local LOG="$SCRIPT_DIR/dep-mbedtls.log"
    local BUILD="$SCRIPT_DIR/build-$ABI/deps/mbedtls"
    rm -rf "$BUILD"
    mkdir -p "$BUILD"
    (
        cd "$BUILD"
        "$CMAKE_BIN" "$SRC" \
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
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
        "libass-0.17.3.tar.xz" "libass-0.17.3" \
        "eae425da50f0015c21f7b3a9c7262a910f0218af469e22e2931462fed3c50959" \
        "https://sources.buildroot.net/libass/libass-0.17.3.tar.xz"
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
    # bitbucket.org is the one that stopped answering for 300s on CI run
    # 30887507336; videolan's GitHub mirror carries the same commit.
    # github.com/multicoreware/x265 does NOT — it was checked and rejected.
    fetch_git_tag x265 "https://bitbucket.org/multicoreware/x265_git.git" "4.1" \
        "1d117bed4747758b51bd2c124d738527e30392cb" \
        "https://github.com/videolan/x265.git"
    local SRC="$SRC_DIR/x265"
    local LOG="$SCRIPT_DIR/dep-x265.log"
    local BUILD="$SCRIPT_DIR/build-$ABI/deps/x265"
    rm -rf "$BUILD"
    mkdir -p "$BUILD"
    (
        cd "$BUILD"
        "$CMAKE_BIN" "$SRC/source" \
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM="android-$API" \
            -DCMAKE_INSTALL_PREFIX="$DEPS_SYSROOT" \
            -DENABLE_SHARED=OFF \
            -DENABLE_CLI=OFF \
            -DENABLE_LIBNUMA=OFF \
            -DENABLE_ASSEMBLY=OFF \
            -DCROSS_COMPILE_ARM64="$([ "$ABI" = arm64-v8a ] && echo ON || echo OFF)" \
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
    # git.ffmpeg.org is a single point of failure for the whole release: it
    # timed out from the F-Droid buildserver (134s, port 443) and failed the
    # 5.83.12 build, which is why F-Droid users sat on 5.81.7 while a dozen
    # releases went out. Try the official GitHub mirror first and fall back.
    # Both serve n8.0 at a4044e04 (verified), so which one answers changes
    # nothing about what gets built.
    FFMPEG_MIRRORS="${FFMPEG_MIRRORS:-https://github.com/FFmpeg/FFmpeg.git https://git.ffmpeg.org/ffmpeg.git}"
    rm -rf "$FFMPEG_SRC"
    for repo in $FFMPEG_MIRRORS; do
        echo "=== Cloning FFmpeg $FFMPEG_REF from $repo ==="
        if git clone --depth 1 --branch "$FFMPEG_REF" "$repo" "$FFMPEG_SRC"; then
            break
        fi
        echo "  clone from $repo failed — trying the next mirror" >&2
        rm -rf "$FFMPEG_SRC"
    done
    if [ ! -d "$FFMPEG_SRC/.git" ]; then
        echo "FFmpeg clone failed from every mirror: $FFMPEG_MIRRORS" >&2
        exit 1
    fi
else
    echo "=== FFmpeg source already present at $FFMPEG_SRC ==="
    (cd "$FFMPEG_SRC" && git fetch --depth 1 origin "$FFMPEG_REF" 2>/dev/null || true)
fi

# Verify the commit, not the tag. FFmpeg is the largest thing compiled into the
# APK, and until now the only thing identifying it was a tag name — which a
# repository can move, and which two mirrors need not agree on. Checked here
# rather than inside the clone loop so a cached tree from an earlier build is
# verified too. Skipped when FFMPEG_REF is overridden, so a developer can test
# another release without editing the pin.
FFMPEG_COMMIT="${FFMPEG_COMMIT:-140fd653aed8cad774f991ba083e2d01e86420c7}"   # n8.0
if [ "$FFMPEG_REF" = "n8.0" ]; then
    got="$(cd "$FFMPEG_SRC" && git rev-parse HEAD)"
    if [ "$got" != "$FFMPEG_COMMIT" ]; then
        echo "ERROR: FFmpeg $FFMPEG_REF is at $got, expected $FFMPEG_COMMIT" >&2
        echo "       The tag moved, or a mirror disagrees. Verify before updating the pin." >&2
        exit 1
    fi
    echo "=== FFmpeg commit verified: $got ==="
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
#
# -pie lives in ldexeflags, not ldflags: since the libav* libraries are built
# shared (see --enable-shared below), a -pie in the common link flags would be
# applied to `-shared` links too, which clang rejects.
EXTRA_CFLAGS="-fPIC -O2 -DANDROID -I$DEPS_SYSROOT/include"
EXTRA_LDFLAGS="-Wl,-z,max-page-size=16384 -L$DEPS_SYSROOT/lib"
EXTRA_LDEXEFLAGS="-pie"

# Full codec/format/filter build — enable all built-in components plus
# external libs (x264, x265, vpx, opus, vorbis, lame, ass, freetype,
# mbedtls). Only devices and hardware accelerators are disabled since
# they need platform-specific setup. This gives users broad format
# support without gating features behind build-time flags.
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
        --extra-ldexeflags="$EXTRA_LDEXEFLAGS" \
        --enable-shared \
        --disable-static \
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
        --disable-ffplay \
        --enable-ffmpeg \
        --enable-ffprobe \
        --enable-avcodec \
        --enable-avformat \
        --enable-avutil \
        --enable-swscale \
        --enable-swresample \
        --enable-avfilter \
        \
        --enable-decoders \
        --enable-encoders \
        --enable-demuxers \
        --enable-muxers \
        --enable-parsers \
        --enable-protocols \
        --enable-filters \
        --enable-bsfs \
        \
        --disable-devices \
        --disable-hwaccels \
        --disable-programs \
        --enable-ffmpeg \
        --enable-ffprobe \
        2>&1 | tee configure.log | tail -40
    echo "(full configure log: $BUILD_DIR/configure.log)"
)

# --- Android-compatible shared library names -----------------------------
# FFmpeg names its shared libraries libavcodec.so.62 with a libavcodec.so
# symlink. Neither survives an APK: the installer extracts only files matching
# lib*.so (so the .62 suffix is skipped), and APKs do not carry symlinks. So
# collapse the versioned names onto the plain one — libavcodec.so, SONAME
# libavcodec.so — which is what the executables' DT_NEEDED then asks for and
# what Android's linker finds in nativeLibraryDir.
CONFIG_MAK="$BUILD_DIR/ffbuild/config.mak"
if [ ! -f "$CONFIG_MAK" ]; then
    echo "ERROR: $CONFIG_MAK not produced — configure failed" >&2
    exit 1
fi
sed -i \
    -e 's|^SLIBNAME_WITH_VERSION=.*|SLIBNAME_WITH_VERSION=$(SLIBNAME)|' \
    -e 's|^SLIBNAME_WITH_MAJOR=.*|SLIBNAME_WITH_MAJOR=$(SLIBNAME)|' \
    -e 's|^SLIB_INSTALL_NAME=.*|SLIB_INSTALL_NAME=$(SLIBNAME)|' \
    -e 's|^SLIB_INSTALL_LINKS=.*|SLIB_INSTALL_LINKS=|' \
    "$CONFIG_MAK"
# The sed is only as good as the variable names — fail loudly if FFmpeg
# renames them rather than silently building unloadable libraries.
for v in SLIBNAME_WITH_VERSION SLIBNAME_WITH_MAJOR SLIB_INSTALL_NAME; do
    grep -q "^$v=\$(SLIBNAME)\$" "$CONFIG_MAK" || {
        echo "ERROR: $v not rewritten in $CONFIG_MAK — FFmpeg's config.mak layout changed" >&2
        grep -n "^$v=" "$CONFIG_MAK" >&2 || echo "       ($v absent entirely)" >&2
        exit 1
    }
done
echo "=== Shared library names collapsed to lib<name>.so ==="

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

# --- Collect the shared libav* libraries ---------------------------------
# With --enable-shared the codec/format/filter code lives here once instead of
# being linked into both executables. That is the point of the shared build:
# ffmpeg and ffprobe were ~23 MB each because they carried a private copy.
LIB_DIR="$INSTALL_DIR/lib"
SHARED_LIBS=""
for so in "$LIB_DIR"/lib*.so; do
    [ -f "$so" ] || continue
    "$STRIP" "$so"
    cp "$so" "$BIN_DIR/$(basename "$so")"
    SHARED_LIBS="$SHARED_LIBS $(basename "$so")"
done
if [ -z "$SHARED_LIBS" ]; then
    echo "ERROR: no shared libav* libraries in $LIB_DIR — is --enable-shared in effect?" >&2
    ls -la "$LIB_DIR" >&2 2>/dev/null || true
    exit 1
fi
echo "=== Shared libraries:$SHARED_LIBS ==="

# Every DT_NEEDED the executables name must be either one of ours, one of the
# NDK's, or libc++_shared.so — anything else will not resolve on the device,
# and a missing libav* here is the difference between a working build and one
# that fails at exec time with "library not found".
if command -v readelf >/dev/null 2>&1; then
    for exe in libffmpeg.so libffprobe.so; do
        for need in $(readelf -dW "$BIN_DIR/$exe" 2>/dev/null \
                | awk -F'[][]' '/NEEDED/ {print $2}'); do
            case "$need" in
                libav*|libsw*|libpostproc*)
                    [ -f "$BIN_DIR/$need" ] || {
                        echo "ERROR: $exe needs $need, which was not produced" >&2
                        exit 1
                    } ;;
                libc++_shared.so|libc.so|libm.so|libdl.so|liblog.so|libz.so|libandroid.so) ;;
                *) echo "  note: $exe also needs $need (expected to come from the NDK)" ;;
            esac
        done
    done
fi

echo ""
echo "=== Output ==="
ls -lh "$BIN_DIR/libffmpeg.so" "$BIN_DIR/libffprobe.so"
( cd "$BIN_DIR" && ls -lh $SHARED_LIBS )
file   "$BIN_DIR/libffmpeg.so" "$BIN_DIR/libffprobe.so" 2>/dev/null || true

# --- 16 KB page alignment check ------------------------------------------
echo ""
echo "=== 16 KB page alignment check ==="
if command -v readelf >/dev/null 2>&1; then
    for f in libffmpeg.so libffprobe.so $SHARED_LIBS; do
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
LIBCXX_SRC="$TOOLCHAIN/sysroot/usr/lib/${SYSROOT_LIB}/libc++_shared.so"
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
