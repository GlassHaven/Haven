#!/usr/bin/env bash
# Guard against the "R8 renamed a reflectively-loaded class" class of bug — the
# kind that ships a feature 100% broken in *release* builds while every *debug*
# test passes, because debug doesn't run R8. The motivating case: JavaMail's
# getStore("imaps") threw NoSuchProviderException because R8 renamed
# com.sun.mail.imap.IMAPSSLStore -> s4.c, breaking every email account in
# release builds (fixed in v5.59.45).
#
# After R8 runs on the release variant, assert that every class we load
# reflectively by fully-qualified name kept that name (identity mapping in
# mapping.txt). A renamed or removed class means a -keep rule in
# app/proguard-rules.pro is missing or broke.
#
# Usage: scripts/check-r8-kept-classes.sh [path/to/mapping.txt]
#   With no argument it auto-locates the arm64FullRelease mapping. Which path
#   exists depends on AGP and on how much of the pipeline ran:
#   - AGP <=9.2: the standalone :app:minifyArm64FullReleaseWithR8 task writes
#     intermediates/mapping/<variant>/minify<Variant>WithR8/mapping.txt.
#   - AGP 9.4 (coreLibraryDesugaring): the minify task writes only unmerged
#     .dat partitions; :app:l8DexDesugarLib<Variant> merges them into
#     intermediates/mapping/<variant>/l8DexDesugarLib<Variant>/mapping.txt.
#   - a full assemble also copies the final one to outputs/mapping/<variant>/.
#   Prefer the freshest; fall back to scanning app/build so a future AGP
#   layout change degrades to a search instead of a hard failure.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -ge 1 ]; then
  MAP="$1"
else
  MAP=""
  for cand in \
    app/build/intermediates/mapping/arm64FullRelease/l8DexDesugarLibArm64FullRelease/mapping.txt \
    app/build/outputs/mapping/arm64FullRelease/mapping.txt \
    app/build/intermediates/mapping/arm64FullRelease/minifyArm64FullReleaseWithR8/mapping.txt; do
    if [ -f "$cand" ]; then
      if [ -z "$MAP" ] || [ "$cand" -nt "$MAP" ]; then MAP="$cand"; fi
    fi
  done
  if [ -z "$MAP" ]; then
    MAP=$(find app/build -name 'mapping.txt' -path '*arm64FullRelease*' -printf '%T@ %p\n' 2>/dev/null \
            | sort -rn | head -1 | cut -d' ' -f2- || true)
  fi
  [ -n "$MAP" ] || MAP="app/build/intermediates/mapping/arm64FullRelease/l8DexDesugarLibArm64FullRelease/mapping.txt"
fi
LIST="scripts/r8-must-keep-classes.txt"
IMAP_CLIENT="core/mail/src/main/kotlin/sh/haven/core/mail/ImapMailClient.kt"

if [ ! -f "$MAP" ]; then
  echo "✖ mapping.txt not found (searched known paths + app/build)." >&2
  echo "  Run ./gradlew :app:minifyArm64FullReleaseWithR8 :app:l8DexDesugarLibArm64FullRelease first." >&2
  exit 2
fi

# Curated list (one FQN per line; #-comments and blanks ignored).
fqns=()
while IFS= read -r line; do
  [ -n "$line" ] && fqns+=("$line")
done < <(grep -vE '^\s*(#|$)' "$LIST" || true)

# Auto-derive the JavaMail provider classes our code loads via mail.<proto>.class,
# so the email path stays checked even if the curated list isn't updated.
if [ -f "$IMAP_CLIENT" ]; then
  while IFS= read -r c; do
    [ -n "$c" ] && fqns+=("$c")
  done < <(grep -oE '"mail\.[a-z]+\.class"\] = "[^"]+"' "$IMAP_CLIENT" \
             | grep -oE '"[A-Za-z0-9_.]+"$' | tr -d '"' || true)
fi

# Deduplicate.
mapfile -t fqns < <(printf '%s\n' "${fqns[@]}" | sort -u)

failures=()
for fqn in "${fqns[@]}"; do
  [ -n "$fqn" ] || continue
  esc=${fqn//./\\.}
  if grep -qE "^${esc} -> ${esc}:" "$MAP"; then
    continue  # identity-mapped == kept
  fi
  actual=$(grep -E "^${esc} -> " "$MAP" | head -1 || true)
  if [ -n "$actual" ]; then
    failures+=("$fqn  →  RENAMED to ${actual##*-> }")
  else
    failures+=("$fqn  →  ABSENT (shrunk away or never compiled in)")
  fi
done

if [ ${#failures[@]} -gt 0 ]; then
  echo "✖ ${#failures[@]} reflectively-loaded class(es) did NOT survive R8 intact:"
  printf '   %s\n' "${failures[@]}"
  echo
  echo "These are resolved at runtime by string name (Class.forName / JavaMail"
  echo "mail.<proto>.class / JNI). A rename breaks them in release builds only."
  echo "Add or fix a -keep rule in app/proguard-rules.pro, then re-run."
  exit 1
fi

echo "✓ All ${#fqns[@]} reflectively-loaded classes kept their names through R8."
