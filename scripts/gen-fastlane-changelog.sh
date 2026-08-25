#!/usr/bin/env bash
# Generate the F-Droid fastlane "What's New" changelog for the CURRENT release
# from the matching CHANGELOG.md section.
#
# Run this as part of cutting a release — AFTER bumping versionCode/versionName in
# app/build.gradle.kts and adding the `## vX.Y.Z` CHANGELOG.md section, and BEFORE
# committing/tagging. F-Droid reads the changelog from the file at the *tagged*
# commit, so it must be in the release commit.
#
# Why this exists: Haven's F-Droid `Changelog:` metadata points at the GitHub
# Releases page, so notes reach F-Droid users regardless — but the per-version
# fastlane `.txt` is what fills the *inline* "What's New" blurb in the F-Droid
# client. We restart per-release fastlane changelogs from v5.82.0 onward.
#
# Filename = the F-Droid build's versionCode = gradle versionCode * 10 + 1 (arm64,
# the ABI F-Droid builds; see the abiCodes map in app/build.gradle.kts).
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="app/build.gradle.kts"
vname="$(grep -oE 'versionName = "[^"]+"' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
vcode="$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | head -1 | grep -oE '[0-9]+')"
[ -n "$vname" ] && [ -n "$vcode" ] || { echo "✖ couldn't read version from $GRADLE" >&2; exit 2; }

fdroid_code=$((vcode * 10 + 1))
out="fastlane/metadata/android/en-US/changelogs/${fdroid_code}.txt"

# Reuse the CHANGELOG extractor (heading stripped, blank lines trimmed) — the same
# text that becomes the GitHub Release body.
body="$(scripts/check-changelog.sh extract "v$vname")"

# F-Droid's inline "What's New" truncates past ~500 chars, and Haven's sections
# are prose that always overruns it. When a section opens with a bullet list —
# the summary added in v5.86.52 — that list alone is the blurb, and the prose
# stays on the Changelog page where there's room for it. Sections without one
# fall back to the whole body, as before.
# Blank lines between the summary bullets are ordinary markdown and must not end
# the scan — a `sed -n '/^- /!q;p'` here silently kept only the first bullet, so
# v5.87.57 and v5.87.58 shipped a one-line blurb with the rest dropped. The scan
# ends at the first detailed entry (`- **Title**`) or at any prose.
tldr="$(printf '%s\n' "$body" | awk '
  /^- \*\*/        { exit }
  /^- /            { print; found = 1; next }
  /^[[:space:]]*$/ { if (found) next; exit }
                   { exit }
')"
if [ -n "$tldr" ]; then
  body="$tldr"
  echo "  using the leading summary bullets for the F-Droid blurb"
fi

mkdir -p "$(dirname "$out")"
printf '%s\n' "$body" > "$out"

chars="$(printf '%s' "$body" | wc -m | tr -d ' ')"
echo "✓ wrote $out (${chars} chars) — v$vname, F-Droid versionCode $fdroid_code"
if [ "$chars" -gt 500 ]; then
  echo "  note: >500 chars — F-Droid's inline 'What's New' truncates the display;"
  echo "  the full text still shows on the app's Changelog page. Trim $out if you"
  echo "  want the inline blurb to read cleanly."
fi
