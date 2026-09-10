#!/bin/bash
# Fetch the UML guest transport binaries (PROTOTYPE.md in the uml-transport
# workspace) into jniLibs.
#
# The UML guest transport runs a real Linux kernel (user-mode linux) as the
# pty child, wired to passt for networking. Four binaries are involved:
#   libvmlinux.so   the UML kernel, built against bionic, static (79 MB)
#   libuml-stub.so  UML's SKAS stub (memfd exec is refused on Android, so
#                   the kernel loads it from disk via stub_exe=)
#   libuml-passt.so passt, musl static, with the PASST_RAW_L2 /
#                   PASST_NO_SANDBOX patch set (integration/passt/)
#   libuml-net.so   the socketpair launcher — built in-repo from
#                   src/main/cpp/uml_net.c (CMake), NOT fetched here
#
# Android app storage is noexec and exec is only allowed from
# nativeLibraryDir, so they ship as jniLibs. arm64-v8a only: there is no
# bionic build of this kernel for the other ABIs. Builds for other ABIs
# (and F-Droid, via -PskipUml) simply lack the guest transport; the
# NativeFeatures.uml probe hides the UI.
#
# Version-pinned release assets with sha256s. The kernel is GPL-2.0; its
# source (branch um-arm64 of zalexdev/linux-um-arm64 plus
# stub-execve-fallback.patch) is published, which satisfies the
# distribution terms. When the pin is retired, the fetch fails LOUDLY —
# bump UML_RELEASE and the sha256s together. Skip with
# ./gradlew -PskipUml or SKIP_UML=1 (F-Droid).
#
# UML_RELEASE_MIRROR overrides the base URL (file:// works) for
# offline/airgapped builds.

set -euo pipefail
cd "$(dirname "$0")"

OUT="${UML_OUTPUT:-src/main/jniLibs}"
BASE="${UML_RELEASE_MIRROR:-https://github.com/GlassOnTin/uml-transport/releases/download/uml-guest-1}"

# file | sha256
# Sizes: libvmlinux.so 79307152, libuml-stub.so 1920, libuml-passt.so 608472
FILES=(
  "libvmlinux.so|7c557572db754b4794ecf7a9cfcfec58d69977d8f09dc1c71f4d9361e032271f"
  "libuml-stub.so|83f51f7c45133daa135b595562b09c5e2829f1d0f1e00b7fce2a7695370781fc"
  "libuml-passt.so|e78fa0a504994f94423085f2b7ddfc891ab27b21a2e9150ec264ed59441c1c44"
)

if [ "${SKIP_UML:-0}" = "1" ]; then
    echo "fetch-uml: SKIP_UML=1 — APK will lack the UML guest transport"
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for spec in "${FILES[@]}"; do
    IFS='|' read -r name sha <<< "$spec"
    dest="$OUT/arm64-v8a/$name"
    if [ -s "$dest" ]; then
        echo "fetch-uml: $dest present — skipping"
        continue
    fi
    url="$BASE/$name"
    echo "fetch-uml: $url"
    # --retry-all-errors: plain --retry ignores TLS handshake failures,
    # which is how a release build died fetching a pinned tarball.
    curl -fsSL --retry 3 --retry-all-errors -o "$TMP/$name" "$url" || {
        echo "fetch-uml: FAILED to fetch $url" >&2
        echo "  To build without the UML guest: SKIP_UML=1 or -PskipUml." >&2
        echo "  To build offline from local files: UML_RELEASE_MIRROR=file:///path." >&2
        exit 1
    }
    echo "$sha  $TMP/$name" | sha256sum -c - >/dev/null || {
        echo "fetch-uml: sha256 MISMATCH for $name" >&2
        exit 1
    }
    mkdir -p "$OUT/arm64-v8a"
    install -m 0755 "$TMP/$name" "$dest"
    echo "fetch-uml: installed $dest ($(stat -c %s "$dest") bytes)"
done