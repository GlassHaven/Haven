#!/bin/bash
# Fetch static qemu-user loaders for foreign-arch proot rootfses (#325).
#
# proot's --qemu option needs a HOST-runnable qemu-user binary; Android app
# storage is noexec and targetSdk 29+ forbids exec of downloaded files, so the
# loader must ship inside the APK as a jniLib (extracted to nativeLibraryDir,
# the one path our uid may exec from). Debian's qemu-user builds are
# static-pie — they run on Android with no linker — so we take those instead
# of carrying a qemu-against-bionic patch stack.
#
# Each APK carries only the loader(s) foreign to its own ABI:
#   arm64-v8a  gets qemu-x86_64  (run x86_64 rootfses on ARM phones)
#   x86_64     gets qemu-aarch64 (run aarch64 rootfses on x86 hosts/emulators)
#   armeabi-v7a gets none (no loader shipped; foreign-arch stays unavailable)
#
# The debs are version-pinned with sha256s. When Debian point-releases retire
# this version from the pool, the fetch fails LOUDLY — bump QEMU_DEB_VERSION
# and the sha256s together. Skip entirely (e.g. an offline / F-Droid build,
# where the APK then simply lacks foreign-arch support) with
# ./gradlew -PskipQemuLoaders or SKIP_QEMU_LOADERS=1.

set -euo pipefail
cd "$(dirname "$0")"

OUT="${QEMU_LOADER_OUTPUT:-src/main/jniLibs}"
MIRROR="${QEMU_DEB_MIRROR:-https://deb.debian.org/debian}"
VERSION="10.0.13+ds-0+deb13u1"

# abi | deb arch | qemu target | deb sha256
LOADERS=(
  "arm64-v8a|arm64|x86_64|c73711af02b97cd5e2667e735d9c06890c57165517110ca4e646c95a7083158d"
  "x86_64|amd64|aarch64|ca6ede739327a20ae5a3498230c8a3a9a072c586e131bc6bcc1201eb1725e336"
)

if [ "${SKIP_QEMU_LOADERS:-0}" = "1" ]; then
    echo "fetch-qemu-loaders: SKIP_QEMU_LOADERS=1 — APK will lack foreign-arch (#325) support"
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for spec in "${LOADERS[@]}"; do
    IFS='|' read -r abi debarch target sha <<< "$spec"
    dest="$OUT/$abi/libqemu_${target}.so"
    if [ -s "$dest" ]; then
        echo "fetch-qemu-loaders: $dest present — skipping"
        continue
    fi
    deb="qemu-user_${VERSION}_${debarch}.deb"
    url="$MIRROR/pool/main/q/qemu/$deb"
    echo "fetch-qemu-loaders: $url"
    # --retry-all-errors: plain --retry ignores TLS handshake failures, which
    # is how a release build died fetching a pinned tarball (see build-ffmpeg).
    curl -fsSL --retry 3 --retry-all-errors -o "$TMP/$deb" "$url" || {
        echo "fetch-qemu-loaders: FAILED to fetch $url" >&2
        echo "  If Debian retired this version, bump VERSION + sha256s here." >&2
        echo "  To build without foreign-arch support: SKIP_QEMU_LOADERS=1 or -PskipQemuLoaders." >&2
        exit 1
    }
    echo "$sha  $TMP/$deb" | sha256sum -c - >/dev/null
    dpkg-deb --fsys-tarfile "$TMP/$deb" | tar -xOf - "./usr/bin/qemu-${target}" > "$TMP/qemu-$target"
    [ -s "$TMP/qemu-$target" ] || { echo "fetch-qemu-loaders: empty qemu-$target from $deb" >&2; exit 1; }
    mkdir -p "$OUT/$abi"
    install -m 0755 "$TMP/qemu-$target" "$dest"
    echo "fetch-qemu-loaders: installed $dest ($(stat -c %s "$dest") bytes)"
done
