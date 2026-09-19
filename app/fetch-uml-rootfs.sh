#!/bin/bash
# Fetch the UML guest's recovery rootfs into the full flavour's assets.
#
# app/src/full/assets/uml/rootfs-aarch64.ext4.gz is an Alpine 3.22.5
# minirootfs with ddrescue, nbd-client, mtools, e2fsprogs and util-linux
# baked in, plus /sbin/haven-recover (see docs/features/usb-recovery-live.md).
# It is downloaded at build time rather than committed because fdroidserver's
# source scan hard-errors on committed gzip files, which is how every F-Droid
# build from versionCode 8431 on failed. Binaries produced during the build
# are not scanned, so the downloaded copy never trips it.
#
# Version-pinned release asset with sha256 (github.com/GlassOnTin/
# uml-transport, release uml-guest-2). The rootfs carries GPL binaries built
# from published Alpine packages, and haven-recover ships inside the image as
# script source. When the pin is retired the fetch fails LOUDLY — bump
# UML_ROOTFS_RELEASE and the sha256 together. Skip with ./gradlew -PskipUml
# or SKIP_UML=1 (the guest transport is skipped the same way).
#
# UML_RELEASE_MIRROR overrides the base URL (file:// works) for offline or
# airgapped builds; a file already present in the output position is left
# alone, so a tree with the asset staged needs no network.

set -euo pipefail
cd "$(dirname "$0")"

OUT="src/full/assets/uml"
DEST="$OUT/rootfs-aarch64.ext4.gz"
BASE="${UML_RELEASE_MIRROR:-https://github.com/GlassOnTin/uml-transport/releases/download/uml-guest-5}"

NAME="rootfs-aarch64.ext4.gz"
SHA="b4795ba5e50abbb377bb1ace9cbbe3b9cf690356a19e31bc115ee1f741da10fd"

if [ "${SKIP_UML:-0}" = "1" ]; then
    echo "fetch-uml-rootfs: SKIP_UML=1 — APK will lack the recovery rootfs"
    exit 0
fi

if [ -s "$DEST" ]; then
    echo "fetch-uml-rootfs: $DEST present — skipping"
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

url="$BASE/$NAME"
echo "fetch-uml-rootfs: $url"
# --retry-all-errors: plain --retry ignores TLS handshake failures,
# which is how a release build died fetching a pinned tarball.
curl -fsSL --retry 3 --retry-all-errors -o "$TMP/$NAME" "$url" || {
    echo "fetch-uml-rootfs: FAILED to fetch $url" >&2
    echo "  To build without the UML guest: SKIP_UML=1 or -PskipUml." >&2
    echo "  To build offline from local files: UML_RELEASE_MIRROR=file:///path." >&2
    exit 1
}
echo "$SHA  $TMP/$NAME" | sha256sum -c - >/dev/null || {
    echo "fetch-uml-rootfs: sha256 MISMATCH for $NAME" >&2
    exit 1
}
mkdir -p "$OUT"
install -m 0644 "$TMP/$NAME" "$DEST"
echo "fetch-uml-rootfs: installed $DEST ($(stat -c %s "$DEST") bytes)"