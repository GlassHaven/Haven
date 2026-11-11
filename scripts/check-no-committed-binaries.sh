#!/usr/bin/env bash
# Fail if any tracked file (including inside initialized submodules) is a compiled binary.
#
# Why this gate exists
# -------------------
# A committed binary is unauditable: nobody reviewing a diff can tell what is
# inside it, it does not correspond to any reviewable source change, and it
# ships straight into the APK. Three separate failures came out of that:
#
#   #469  a committed liblabwc_android.so went three versions stale because
#         nothing rebuilt it — the cage crashed and the source looked correct.
#   rdp   armeabi-v7a was missing from the cargo target list, so armv7 users
#         ran whatever binary happened to be checked in.
#   wayland  F-Droid `scandelete`d core/wayland/src/main/jniLibs and rebuilt
#         only liblabwc_android.so, so their APK silently shipped without the
#         GPU renderer, the XWayland wrapper and the GLES benchmark.
#
# Every native artefact must therefore be produced by a build step from source
# in the tree. Detection is by content (ELF/Mach-O/PE/dex/zip magic), not by
# file extension, so renaming a blob does not get it past this.
#
# Usage:
#   scripts/check-no-committed-binaries.sh            # check tracked files (+ submodules)
#   scripts/check-no-committed-binaries.sh --staged   # check the index (hooks)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Pinned rather than fetched from services.gradle.org on purpose: a gate that
# needs the network fails when the network does, which is exactly how an
# unreachable bitbucket.org took down CI run 30887507336.
#
# To bump Gradle, regenerate the jar rather than editing the URL by hand:
#   ./gradlew wrapper --gradle-version <v> --distribution-type bin \
#       --gradle-distribution-sha256-sum <sha of gradle-<v>-bin.zip>
# then set both values here from
#   https://services.gradle.org/distributions/gradle-<v>-wrapper.jar.sha256
WRAPPER_VERSION="9.7.1"
WRAPPER_JAR_SHA256="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"

check_gradle_wrapper() {
    local props="gradle/wrapper/gradle-wrapper.properties"
    local jar="gradle/wrapper/gradle-wrapper.jar"
    [ -f "$props" ] && [ -f "$jar" ] || return 0

    local declared
    declared="$(sed -n 's|^distributionUrl=.*/gradle-\(.*\)-bin\.zip$|\1|p' "$props")"
    if [ "$declared" != "$WRAPPER_VERSION" ]; then
        echo "✗ gradle-wrapper.properties declares Gradle '$declared', this gate pins '$WRAPPER_VERSION'" >&2
        echo "  Bumping the version means regenerating the jar — see the comment in $0" >&2
        return 1
    fi

    local got
    got="$(sha256sum "$jar" | cut -d' ' -f1)"
    if [ "$got" != "$WRAPPER_JAR_SHA256" ]; then
        echo "✗ gradle-wrapper.jar is not the Gradle $WRAPPER_VERSION wrapper jar" >&2
        echo "    expected $WRAPPER_JAR_SHA256" >&2
        echo "    got      $got" >&2
        echo "  Regenerate it with the ./gradlew wrapper command in $0 rather than" >&2
        echo "  updating this hash — a jar that does not match its own release is" >&2
        echo "  the failure this check exists for." >&2
        return 1
    fi
}

# Scanning 30k+ tracked files across `--recurse-submodules` in a single Python
# process runs in ~0.1s vs ~45s of per-file subshells.
python3 - "${1:-}" <<'PY'
import configparser
import subprocess
import sys
from pathlib import Path

staged = len(sys.argv) > 1 and sys.argv[1] == "--staged"

ALLOWLIST = {
    "gradle/wrapper/gradle-wrapper.jar",
}

# Pre-existing binaries tracked inside submodules that predate `--recurse-submodules`
# enforcement (#493). This list may ONLY SHRINK: if an entry stops being tracked
# in an initialized submodule, the gate fails until the entry is removed here too.
GRANDFATHERED = [
    "spice-kotlin/jniLibs/arm64-v8a/libspice_transport.so",
    "spice-kotlin/jniLibs/armeabi-v7a/libspice_transport.so",
    "spice-kotlin/jniLibs/x86_64/libspice_transport.so",
]

def is_allowed(path: str) -> bool:
    return path in ALLOWLIST or path.endswith("/gradle/wrapper/gradle-wrapper.jar")

if staged:
    out = subprocess.check_output(
        ["git", "diff", "--cached", "-z", "--name-only", "--diff-filter=ACM"]
    ).decode("utf-8", "surrogateescape")
    files = [f for f in out.split("\0") if f]
else:
    out = subprocess.check_output(
        ["git", "ls-files", "-z", "--recurse-submodules"]
    ).decode("utf-8", "surrogateescape")
    files = [f for f in out.split("\0") if f]

tracked_set = set(files)
grandfathered_set = set(GRANDFATHERED)

def read_magic(path: str) -> bytes:
    if staged:
        p = subprocess.run(
            ["git", "show", f":{path}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        return p.stdout[:4]
    try:
        with open(path, "rb") as fp:
            return fp.read(4)
    except OSError:
        return b""

violations = []
for f in files:
    if is_allowed(f) or f in grandfathered_set:
        continue
    m = read_magic(f)
    if len(m) < 2:
        continue
    if m == b"\x7fELF":
        violations.append(f"{f}  (ELF)")
    elif m in (b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf", b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe"):
        violations.append(f"{f}  (Mach-O or Java class)")
    elif m[:2] == b"MZ":
        violations.append(f"{f}  (PE/DLL)")
    elif m == b"dex\n":
        violations.append(f"{f}  (Android dex)")
    elif m == b"PK\x03\x04" and f.endswith((".jar", ".aar", ".apk", ".aab", ".war")):
        violations.append(f"{f}  (Java/Android archive)")

if not staged:
    submodule_paths = set()
    gm = Path(".gitmodules")
    if gm.is_file():
        cp = configparser.ConfigParser()
        cp.read(gm, encoding="utf-8")
        for sec in cp.sections():
            if cp.has_option(sec, "path"):
                submodule_paths.add(cp.get(sec, "path"))

    stale = []
    for g in GRANDFATHERED:
        top = g.split("/", 1)[0]
        if top in submodule_paths and not (Path(top) / ".git").exists():
            continue
        if g not in tracked_set:
            stale.append(g)
    if stale:
        print("✗ GRANDFATHERED entry no longer tracked — remove it from GRANDFATHERED in scripts/check-no-committed-binaries.sh:", file=sys.stderr)
        for s in stale:
            print(f"    {s}", file=sys.stderr)
        sys.exit(1)

if violations:
    print(f"✗ {len(violations)} committed binary file(s) found:", file=sys.stderr)
    for v in violations:
        print(f"    {v}", file=sys.stderr)
    print(
        """
Binaries must not be committed — they are unauditable in review and go stale
silently (#469). Build them from source instead:

    core/local/src/main/jniLibs/   build-proot/build.sh
    core/ffmpeg/src/main/jniLibs/  build-ffmpeg/build.sh
    core/wayland/src/main/jniLibs/ wayland-android/build_liblabwc_android.sh
                                   wayland-android/build-native-helpers.sh
    rdp-kotlin/jniLibs/            cargo-ndk (rdp-kotlin:buildRdpNative)
    spice-kotlin/jniLibs/          cargo-ndk (spice-kotlin:buildSpiceNative)
    core/prns/src/main/jniLibs/    cargo-ndk (:core:prns:buildPrnsNative)
    rclone-android/build/          gomobile (buildRcloneNative)

then add the output path to .gitignore. If a binary genuinely must be tracked,
add it to ALLOWLIST in this script with a comment saying why.""",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"✓ no committed binaries ({len(files)} tracked files checked)")
PY

check_gradle_wrapper
echo "✓ gradle-wrapper.jar is the Gradle $WRAPPER_VERSION wrapper jar"
