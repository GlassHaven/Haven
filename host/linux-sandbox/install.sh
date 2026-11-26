#!/usr/bin/env bash
# install.sh — installer and non-destructive verifier for Haven's Linux host sandbox.
#
# Modes:
#   bash host/linux-sandbox/install.sh --check [--strict-deps]
#     Non-destructive bundle & host verification (requires NO root, writes NO files).
#   sudo bash host/linux-sandbox/install.sh [--user <username>] [--boundary-test]
#     Installs root-owned proxy/policy/helper/launcher + visudo-validated sudoers rule.

set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="install"
STRICT_DEPS=0
RUN_BOUNDARY_TEST=0
TARGET_USER="${SUDO_USER:-}"
LIB_DIR="/usr/local/lib/haven-sandbox"
AUDIT_DIR="/var/log/haven-sandbox"
BIN_DIR="/usr/local/bin"
SUDOERS_FILE="/etc/sudoers.d/haven-sandbox"

usage() {
  cat <<'EOF'
Usage:
  bash install.sh --check [--strict-deps]
  sudo bash install.sh [--user USERNAME] [--boundary-test]

Options:
  --check, --verify   Non-destructive check of bundle syntax, policy, isolated
                      Python startup, host dependencies, and any installed permissions.
                      Makes zero changes to the system and does not require root.
  --strict-deps       With --check, exit non-zero if runtime jail tools
                      (nft, slirp4netns, ip, unshare, runuser, visudo) are missing.
  --user USERNAME     Unprivileged desktop user granted NOPASSWD access to
                      /usr/local/bin/haven-sandbox-helper (defaults to SUDO_USER).
  --boundary-test     After installing, run a live phase1 netns+nft+proxy boundary check.
  -h, --help          Show this help message.

Security model & sudoers scope:
  - Installs proxy + policy root:root 0644 in /usr/local/lib/haven-sandbox and
    audit directory root:root 0700 in /var/log/haven-sandbox.
  - Grants USERNAME NOPASSWD execution ONLY on /usr/local/bin/haven-sandbox-helper
    (validated via `visudo -c -f` before placement in /etc/sudoers.d/haven-sandbox).
  - haven-sandbox-helper refuses root/empty SUDO_USER, validates endpoint and
    /tmp/haven-sb-env.XXXXXX ownership/non-symlink invariants, and drops privileges
    to USERNAME via `runuser -u` before executing the sandboxed command.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check|--verify)
      MODE="check"
      shift
      ;;
    --strict-deps)
      STRICT_DEPS=1
      shift
      ;;
    --user)
      TARGET_USER="${2:-}"
      shift 2
      ;;
    --boundary-test)
      RUN_BOUNDARY_TEST=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "install.sh: unknown option '$1' (see --help)" >&2
      exit 2
      ;;
  esac
done

check_bundle_sources() {
  local f
  for f in haven-egress-proxy.py policy.json haven-sandbox-helper haven-sandbox haven-egress-provision-key install.sh; do
    if [ ! -f "$SRC_DIR/$f" ]; then
      echo "FAIL: missing bundle file $SRC_DIR/$f" >&2
      return 1
    fi
  done

  for f in haven-sandbox-helper haven-sandbox haven-egress-provision-key install.sh; do
    bash -n "$SRC_DIR/$f"
  done
  echo "OK: bash syntax valid across all 4 shell scripts"

  env -i PATH=/usr/bin:/bin python3 -I -S "$SRC_DIR/haven-egress-proxy.py" \
    --self-check --policy "$SRC_DIR/policy.json"
}

check_host_deps() {
  local missing=()
  local cmd
  for cmd in python3 ip nft slirp4netns unshare runuser visudo; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      missing+=("$cmd")
    fi
  done
  if [ "${#missing[@]}" -eq 0 ]; then
    echo "OK: all runtime host dependencies present (python3, ip, nft, slirp4netns, unshare, runuser, visudo)"
    return 0
  fi
  if [ "$STRICT_DEPS" -eq 1 ]; then
    echo "FAIL: missing runtime host dependencies: ${missing[*]}" >&2
    return 1
  fi
  echo "NOTE: optional runtime host dependencies not installed on this machine: ${missing[*]}"
  return 0
}

check_installed_permissions() {
  if [ ! -d "$LIB_DIR" ] && [ ! -f "$BIN_DIR/haven-sandbox-helper" ]; then
    echo "NOTE: haven-sandbox is not currently installed under /usr/local (source check only)"
    return 0
  fi
  local p owner mode
  for p in "$LIB_DIR" "$LIB_DIR/haven-egress-proxy.py" "$LIB_DIR/policy.json" "$BIN_DIR/haven-sandbox-helper"; do
    if [ -e "$p" ]; then
      if [ -L "$p" ]; then
        echo "FAIL: installed path $p must not be a symlink" >&2
        return 1
      fi
      owner="$(stat -c '%u:%g' "$p")"
      mode="$(stat -c '%a' "$p")"
      if [ "$owner" != "0:0" ]; then
        echo "FAIL: $p owned by $owner (expected 0:0)" >&2
        return 1
      fi
      echo "OK: installed $p root:root mode $mode"
    fi
  done
}

if [ "$MODE" = "check" ]; then
  check_bundle_sources
  check_host_deps
  check_installed_permissions
  exit 0
fi

[ "$(id -u)" = 0 ] || { echo "install.sh: install mode must run as root (use --check for non-root verification)" >&2; exit 1; }

if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
  echo "install.sh: specify a non-root desktop user via --user USERNAME (or invoke via sudo)" >&2
  exit 2
fi
if [[ ! "$TARGET_USER" =~ ^[a-z_][a-z0-9_-]*[$]?$ ]] || ! id -u "$TARGET_USER" >/dev/null 2>&1; then
  echo "install.sh: user '$TARGET_USER' does not exist or has invalid syntax" >&2
  exit 2
fi
TARGET_UID="$(id -u "$TARGET_USER")"
TARGET_GID="$(id -g "$TARGET_USER")"
[ "$TARGET_UID" != "0" ] || { echo "install.sh: target user must not have uid 0" >&2; exit 2; }

STRICT_DEPS=1
check_bundle_sources
check_host_deps

install -d -o root -g root -m 755 "$LIB_DIR"
install -d -o root -g root -m 700 "$AUDIT_DIR"
install -m 644 -o root -g root "$SRC_DIR/haven-egress-proxy.py" "$LIB_DIR/haven-egress-proxy.py"
install -m 644 -o root -g root "$SRC_DIR/policy.json" "$LIB_DIR/policy.json"
install -m 755 -o root -g root "$SRC_DIR/haven-sandbox-helper" "$BIN_DIR/haven-sandbox-helper"
install -m 755 -o root -g root "$SRC_DIR/haven-sandbox" "$BIN_DIR/haven-sandbox"
install -m 755 -o root -g root "$SRC_DIR/haven-egress-provision-key" "$BIN_DIR/haven-egress-provision-key"

STAGE_SUDOERS="$(umask 077 && mktemp)"
trap 'rm -f -- "$STAGE_SUDOERS"' EXIT
printf '%s ALL=(root) NOPASSWD: %s/haven-sandbox-helper\n' "$TARGET_USER" "$BIN_DIR" > "$STAGE_SUDOERS"
chmod 440 "$STAGE_SUDOERS"
chown root:root "$STAGE_SUDOERS"
visudo -c -f "$STAGE_SUDOERS"
mv -f "$STAGE_SUDOERS" "$SUDOERS_FILE"

echo "OK: haven-sandbox installed for user '$TARGET_USER' ($BIN_DIR/haven-sandbox)."

if [ "$RUN_BOUNDARY_TEST" -eq 1 ]; then
  echo "=== Running live phase1 boundary test as $TARGET_USER ==="
  SUDO_USER="$TARGET_USER" SUDO_UID="$TARGET_UID" SUDO_GID="$TARGET_GID" SB_EGRESS_MODE=phase1 \
    "$BIN_DIR/haven-sandbox-helper" example.com -- bash -c '
      set -e
      echo "uid=$(id -u) HTTP_PROXY=$HTTP_PROXY"
      [ "$(id -u)" != "0" ]
      code_tele=$(curl -s -m 10 -o /dev/null -w "%{http_code}" https://a-api.anthropic.com 2>/dev/null || echo 000)
      code_unlisted=$(curl -s -m 10 -o /dev/null -w "%{http_code}" https://example.net 2>/dev/null || echo 000)
      code_bypass=$(HTTPS_PROXY= HTTP_PROXY= curl -s -m 6 -o /dev/null -w "%{http_code}" https://1.1.1.1 2>/dev/null || echo 000)
      echo "telemetry=$code_tele unlisted=$code_unlisted direct_bypass=$code_bypass"
      [ "$code_tele" = "000" ] || [ "$code_tele" = "403" ]
      [ "$code_unlisted" = "000" ] || [ "$code_unlisted" = "403" ]
      [ "$code_bypass" = "000" ]
    '
  echo "OK: live boundary test passed."
fi
