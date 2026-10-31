#!/usr/bin/env bash
# Prepare pinned source dependencies; compilation scripts only use --check.
# OPENVPN3_DEPS_MIRROR may point to an HTTPS or file:// archive directory.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPS_DIR="$SCRIPT_DIR/.deps"
SOURCES_DIR="$DEPS_DIR/sources"
MODE="${1:---prepare}"
case "$MODE" in
    --prepare|--offline|--check) ;;
    *) echo "Usage: $0 [--prepare|--offline|--check]" >&2; exit 1 ;;
esac

# The lock also pins OpenVPN3 and its Asio patches by archive checksum.
# Use file contents, not paths: F-Droid relocates the prepared source tree.
FINGERPRINT="$(cat "$SCRIPT_DIR/deps.lock" "$SCRIPT_DIR/fetch-deps.sh" | sha256sum | cut -d ' ' -f1)"
if [ -f "$SOURCES_DIR/.prepared" ] && [ "$(cat "$SOURCES_DIR/.prepared")" = "$FINGERPRINT" ] \
    && [ -f "$SOURCES_DIR/openvpn3/client/ovpncli.cpp" ] \
    && [ -f "$SOURCES_DIR/openvpn3/client/ovpncli.hpp" ] \
    && [ -f "$SOURCES_DIR/openvpn3/openvpn/crypto/data_epoch.cpp" ] \
    && [ -f "$SOURCES_DIR/mbedtls/CMakeLists.txt" ] \
    && [ -f "$SOURCES_DIR/mbedtls/framework/CMakeLists.txt" ] \
    && [ -f "$SOURCES_DIR/lz4/build/cmake/CMakeLists.txt" ] \
    && [ -f "$SOURCES_DIR/fmt/include/fmt/format.h" ] \
    && [ -f "$SOURCES_DIR/asio/include/asio.hpp" ]; then
    echo "OpenVPN3 dependency sources are prepared."
    exit 0
fi
if [ "$MODE" = --check ]; then
    echo "ERROR: OpenVPN3 dependency sources are missing or stale. Run bash openvpn3-core/fetch-deps.sh during source preparation, before the offline build." >&2
    exit 1
fi

mkdir -p "$DEPS_DIR/downloads"
# Stage outside the repository so git apply cannot discover the parent repo
# and interpret Asio's patch paths relative to it. Install only on success.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
while IFS='|' read -r name repo revision sha; do
    [[ -z "$name" || "$name" = \#* ]] && continue
    archive="$name-$revision.tar.gz"
    cached="$DEPS_DIR/downloads/$archive"
    if [ ! -f "$cached" ]; then
        if [ "$MODE" = --offline ]; then
            echo "ERROR: missing cached source archive $cached (offline mode). Prepare sources before building." >&2
            exit 1
        fi
        url="https://codeload.github.com/$repo/tar.gz/$revision"
        if [ -n "${OPENVPN3_DEPS_MIRROR:-}" ]; then
            url="${OPENVPN3_DEPS_MIRROR%/}/$archive"
        fi
        echo "Fetching $url"
        curl -fsSL --retry 3 --retry-all-errors --connect-timeout 30 --max-time 300 \
            -o "$STAGE/$archive" "$url"
        echo "$sha  $STAGE/$archive" | sha256sum -c -
        mv "$STAGE/$archive" "$cached"
    fi
    # Verify cached archives too, including ones supplied by source prep.
    echo "$sha  $cached" | sha256sum -c -
    mkdir -p "$STAGE/$name"
    tar -xzf "$cached" -C "$STAGE/$name" --strip-components=1
done < "$SCRIPT_DIR/deps.lock"

mv "$STAGE/mbedtls-framework" "$STAGE/mbedtls/framework-prepared"
rmdir "$STAGE/mbedtls/framework"
mv "$STAGE/mbedtls/framework-prepared" "$STAGE/mbedtls/framework"
PATCHES=("$STAGE"/openvpn3/deps/vcpkg-ports/asio/*.patch)
if [ ! -f "${PATCHES[0]}" ]; then
    echo "ERROR: pinned OpenVPN3 archive is missing its Asio patches." >&2
    exit 1
fi
for patch in "${PATCHES[@]}"; do
    git -C "$STAGE/asio" apply "$patch"
done
echo "$FINGERPRINT" > "$STAGE/.prepared"
rm -rf "$SOURCES_DIR"
mv "$STAGE" "$SOURCES_DIR"
echo "Prepared OpenVPN3 sources in $SOURCES_DIR"
