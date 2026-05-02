#!/usr/bin/env bash
# Smoke test for Haven's MCP agent transport.
#
# Assumes:
#   - Haven is running on a connected ADB device
#   - Settings → Agent endpoint is enabled
#   - This host can reach 127.0.0.1:8730 directly (loopback) OR via
#     `adb reverse tcp:8730 tcp:8730` first.
#
# Usage:
#   ./dev/agent-smoke.sh                # uses 127.0.0.1:8730/mcp
#   MCP_URL=http://1.2.3.4:8730/mcp ./dev/agent-smoke.sh
#
# Exit code is non-zero if any read-only call fails.

set -u

MCP_URL="${MCP_URL:-http://127.0.0.1:8730/mcp}"
JQ="${JQ:-jq}"

call() {
    local label="$1" body="$2"
    echo
    echo "=== $label"
    local response
    response=$(curl -sS -X POST -H 'Content-Type: application/json' -d "$body" "$MCP_URL")
    if [[ -z "$response" ]]; then
        echo "  ERROR: empty response (server unreachable?)"
        return 1
    fi
    if command -v "$JQ" >/dev/null 2>&1; then
        echo "$response" | "$JQ" .
    else
        echo "$response"
    fi
}

# --- Read-only handshake + tool inventory ---

call "initialize" \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"haven-smoke","version":"0.1.0"}}}'

call "notifications/initialized" \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}'

call "tools/list" \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# --- NEVER-level tools (no consent) ---

call "get_app_info" \
    '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_app_info","arguments":{}}}'

call "list_connections" \
    '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_connections","arguments":{}}}'

call "list_sessions" \
    '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_sessions","arguments":{}}}'

call "list_rclone_remotes" \
    '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"list_rclone_remotes","arguments":{}}}'

# --- Consent-gated tools (each will pop a bottom sheet on the device) ---
# Tip: if the device app is backgrounded, expect Outcome.DENIED and a
# JSON-RPC error with code -32000.

# Pick a profileId for these — discover from list_connections. Defaulted
# to env var so you can override.
PROFILE_ID="${PROFILE_ID:-}"

if [[ -n "$PROFILE_ID" ]]; then
    call "add_port_forward (LOCAL 9999 → localhost:22)" \
        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"add_port_forward\",\"arguments\":{\"profileId\":\"$PROFILE_ID\",\"type\":\"LOCAL\",\"bindPort\":9999,\"targetHost\":\"localhost\",\"targetPort\":22}}}"

    # Pause so the user can tap Allow / Deny on the device. The smoke
    # exists to drive prompts, not to script around them.
    echo
    echo "  ↑ Tap Allow on the device to proceed, or Deny to test the DENIED path."

    call "disconnect_profile" \
        "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"disconnect_profile\",\"arguments\":{\"profileId\":\"$PROFILE_ID\"}}}"
else
    echo
    echo "Set PROFILE_ID=<id from list_connections> to exercise the consent-gated tools."
fi
