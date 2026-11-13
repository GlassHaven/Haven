#!/bin/sh
# Build QEMU 11.1.3 + virglrenderer (with Venus socket bridge) from pinned
# source into /opt/qemu11 inside the active PRoot guest (#829).
#
# Why from pinned source:
#   1. Windows 11 DirectX 11 / Vulkan under virtio-vga-gl (blob=on, venus=on,
#      hostmem=4G) requires QEMU 11 + post-1.3 virglrenderer Venus SHM resource
#      support plus Haven's VIRGL_RENDER_SERVER_SOCKET bridge so QEMU inside
#      PRoot talks to Android's native libvirgl_render_server.so over /tmp.
#   2. PRoot guests have no /dev/dri render node; qemu-egl-surfaceless.patch
#      lets `-display egl-headless` fall back cleanly to Mesa's
#      EGL_PLATFORM_SURFACELESS_MESA (PBUFFER config + memfd dma-buf import)
#      while keeping GBM untouched when /dev/dri is present.
#
# Idempotent: exits 0 immediately when /opt/qemu11 already matches both pins
# unless FORCE_REBUILD=1 is set.

set -eu

PREFIX=/opt/qemu11
WORK=/opt/qemu11-src
PATCH=/usr/local/share/haven/qemu11-build/qemu-egl-surfaceless.patch
LOG_TAG="[qemu11-build]"

VIRGL_REPO=https://github.com/GlassOnTin/virglrenderer.git
VIRGL_COMMIT=eb4f3107e761af63d9b3b18081f134766d121b90

QEMU_VERSION=11.1.3
QEMU_TARBALL="qemu-${QEMU_VERSION}.tar.xz"
QEMU_URL="https://download.qemu.org/${QEMU_TARBALL}"
QEMU_SHA256=5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22

log() { echo "$LOG_TAG $*"; }

# --- Gate 1: skip when matching /opt/qemu11 build is already installed --------
if [ "${FORCE_REBUILD:-0}" != "1" ] &&
   [ -x "$PREFIX/bin/qemu-system-x86_64" ] &&
   [ -x "$PREFIX/bin/qemu-system-aarch64" ] &&
   [ -f "$PREFIX/virgl-commit.txt" ] &&
   [ -f "$PREFIX/qemu-sha256.txt" ]; then
    INSTALLED_VIRGL="$(tr -d '[:space:]' < "$PREFIX/virgl-commit.txt" || true)"
    INSTALLED_QEMU="$(awk '{print $1}' "$PREFIX/qemu-sha256.txt" || true)"
    if [ "$INSTALLED_VIRGL" = "$VIRGL_COMMIT" ] && [ "$INSTALLED_QEMU" = "$QEMU_SHA256" ]; then
        log "QEMU ${QEMU_VERSION} + virglrenderer ${VIRGL_COMMIT} already installed at $PREFIX"
        exit 0
    fi
fi

if [ ! -f "$PATCH" ]; then
    log "FATAL: missing patch asset at $PATCH"
    exit 1
fi

# --- Gate 2: install build + UEFI/TPM2 dependencies ---------------------------
if command -v apt-get >/dev/null 2>&1; then
    log "installing APT build dependencies (QEMU 11 + virglrenderer + OVMF + swtpm)"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq || log "apt-get update warned — continuing"
    apt-get install -y --no-install-recommends \
        build-essential git curl ca-certificates xz-utils meson ninja-build pkg-config \
        python3 python3-venv python3-packaging python3-mako python3-yaml \
        libepoxy-dev libdrm-dev libgbm-dev libegl-dev libgles-dev \
        libvulkan-dev libglib2.0-dev libpixman-1-dev libfdt-dev zlib1g-dev \
        libslirp-dev libusb-1.0-0-dev libusbredirparser-dev \
        libgnutls28-dev libjpeg-dev libpng-dev libzstd-dev \
        ovmf qemu-efi-aarch64 swtpm swtpm-tools
elif command -v pacman >/dev/null 2>&1; then
    log "installing pacman build dependencies (QEMU 11 + virglrenderer + OVMF + swtpm)"
    pacman -Sy --noconfirm --needed \
        base-devel git curl ca-certificates xz meson ninja pkgconf \
        python python-packaging python-mako python-yaml \
        libepoxy libdrm mesa vulkan-headers vulkan-icd-loader glib2 pixman dtc zlib \
        libslirp libusb usbredir gnutls libjpeg-turbo libpng zstd \
        edk2-ovmf edk2-aarch64 swtpm
else
    log "FATAL: unsupported distro package manager (use a Debian/Ubuntu or Arch guest)"
    exit 2
fi

mkdir -p "$WORK" "$PREFIX"

# --- Step 1: fetch & verify pinned virglrenderer ------------------------------
VIRGL_SRC="$WORK/virglrenderer"
if [ ! -d "$VIRGL_SRC/.git" ]; then
    rm -rf "$VIRGL_SRC"
    mkdir -p "$VIRGL_SRC"
    git -C "$VIRGL_SRC" init -q
    git -C "$VIRGL_SRC" remote add origin "$VIRGL_REPO"
fi
log "fetching virglrenderer at $VIRGL_COMMIT"
git -C "$VIRGL_SRC" fetch -q --depth 1 origin "$VIRGL_COMMIT"
git -C "$VIRGL_SRC" checkout -q --force FETCH_HEAD
GOT_VIRGL="$(git -C "$VIRGL_SRC" rev-parse HEAD)"
if [ "$GOT_VIRGL" != "$VIRGL_COMMIT" ]; then
    log "FATAL: virglrenderer checked out $GOT_VIRGL, expected $VIRGL_COMMIT"
    exit 3
fi

log "building virglrenderer (-Dvenus=true)"
rm -rf "$WORK/virgl-build"
meson setup "$WORK/virgl-build" "$VIRGL_SRC" \
    --prefix="$PREFIX" --libdir=lib --buildtype=release \
    -Dvenus=true -Dplatforms=egl -Dtests=false -Dfuzzer=false
ninja -C "$WORK/virgl-build"
ninja -C "$WORK/virgl-build" install

# --- Step 2: fetch & verify pinned QEMU 11.1.3 tarball ------------------------
TARBALL_PATH="$WORK/$QEMU_TARBALL"
if [ ! -f "$TARBALL_PATH" ]; then
    log "downloading $QEMU_URL"
    curl -fL --retry 3 -o "$TARBALL_PATH" "$QEMU_URL"
fi
log "verifying SHA-256 of $QEMU_TARBALL"
if ! printf '%s  %s\n' "$QEMU_SHA256" "$TARBALL_PATH" | sha256sum -c -; then
    rm -f "$TARBALL_PATH"
    log "FATAL: SHA-256 mismatch for $QEMU_TARBALL"
    exit 4
fi

QEMU_SRC="$WORK/qemu-${QEMU_VERSION}"
rm -rf "$QEMU_SRC"
log "extracting $QEMU_TARBALL"
tar -xf "$TARBALL_PATH" -C "$WORK"

log "applying PRoot surfaceless egl-headless patch"
patch -d "$QEMU_SRC" -p1 < "$PATCH"

log "configuring QEMU ${QEMU_VERSION} (x86_64-softmmu, aarch64-softmmu)"
rm -rf "$WORK/qemu-build"
mkdir -p "$WORK/qemu-build"
(
    cd "$WORK/qemu-build"
    PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}" \
        "$QEMU_SRC/configure" \
        --prefix="$PREFIX" \
        --libdir="$PREFIX/lib" \
        --target-list=x86_64-softmmu,aarch64-softmmu \
        --enable-opengl \
        --enable-virglrenderer \
        --enable-vnc \
        --enable-slirp \
        --enable-libusb \
        --enable-usb-redir \
        --enable-tpm \
        --enable-tools \
        --disable-werror \
        --disable-docs \
        --disable-download \
        --extra-ldflags="-Wl,-rpath,$PREFIX/lib"
    ninja
    ninja install
)

printf '%s\n' "$VIRGL_COMMIT" > "$PREFIX/virgl-commit.txt"
printf '%s  %s\n' "$QEMU_SHA256" "$QEMU_TARBALL" > "$PREFIX/qemu-sha256.txt"

# Clean bulky source/build trees while keeping /opt/qemu11 intact
rm -rf "$WORK/virgl-build" "$WORK/qemu-build" "$QEMU_SRC"

"$PREFIX/bin/qemu-system-x86_64" --version
"$PREFIX/bin/qemu-system-aarch64" --version
log "installed QEMU ${QEMU_VERSION} + virglrenderer ${VIRGL_COMMIT} into $PREFIX"
