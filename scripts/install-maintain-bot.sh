#!/usr/bin/env bash
# install-maintain-bot.sh
# Comprehensive, idempotent setup script for the isolated Haven Maintainer Bot.
#
# Sets up:
# 1. Dedicated Linux user account 'haven-bot' with DAC isolation from 'ian'.
# 2. Hardened permissions on sensitive directories (~/.ssh, ~/.gnupg, ~/.config).
# 3. Passwordless sudoers rule for ian -> haven-bot.
# 4. Cloned Haven workspace with submodules in /home/haven-bot/Code/Haven.
# 5. Fine-grained GitHub PAT environment & isolated GH_CONFIG_DIR.
# 6. PreToolUse Bash security hooks blocking GitHub admin/auth manipulation.
# 7. Claude Code configuration with clean MCP settings (preventing schema bloat).
# 8. Complete project memory store sync & memory-lint hooks/skills.
# 9. Local llama.cpp inference endpoint mappings (Dual RTX 5090 / Qwen3.8-Flash-Next).
# 10. /home/ian/.local/bin/lclaude-bot launcher.
#
# Usage:
#   sudo bash scripts/install-maintain-bot.sh

set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  echo "Error: This installer requires root privileges. Please run with sudo:" >&2
  echo "  sudo bash $0" >&2
  exit 1
fi

BOT_USER="haven-bot"
BOT_HOME="/home/${BOT_USER}"
IAN_USER="${SUDO_USER:-ian}"
IAN_HOME=$(getent passwd "${IAN_USER}" | cut -d: -f6)
HAVEN_REPO_SRC="${IAN_HOME}/Code/Haven"

echo "=================================================================="
echo " Starting Haven Maintainer Bot Installation"
echo " Host user: ${IAN_USER} (${IAN_HOME})"
echo " Bot user:  ${BOT_USER} (${BOT_HOME})"
echo "=================================================================="

# -----------------------------------------------------------------------------
# 1. Create haven-bot system user
# -----------------------------------------------------------------------------
echo "--> 1. Creating ${BOT_USER} user..."
if id "${BOT_USER}" &>/dev/null; then
  echo "    User ${BOT_USER} already exists."
else
  useradd -m -s /bin/bash -c "Haven Maintainer Bot" "${BOT_USER}"
  echo "    Created user ${BOT_USER}."
fi

# -----------------------------------------------------------------------------
# 2. Secure host user directories and preserve toolchain access
# -----------------------------------------------------------------------------
echo "--> 2. Configuring host directory permissions..."
chmod 700 "${IAN_HOME}/.ssh" "${IAN_HOME}/.gnupg" "${IAN_HOME}/.config" 2>/dev/null || true
# Ensure shared build toolchains (Android SDK, Rust, Cargo) remain executable
chmod 755 "${IAN_HOME}" 2>/dev/null || true
[ -d "${IAN_HOME}/Android" ] && chmod 755 "${IAN_HOME}/Android" "${IAN_HOME}/Android/Sdk" 2>/dev/null || true
[ -d "${IAN_HOME}/.rustup" ] && chmod 755 "${IAN_HOME}/.rustup" 2>/dev/null || true
[ -d "${IAN_HOME}/.cargo" ] && chmod 755 "${IAN_HOME}/.cargo" "${IAN_HOME}/.cargo/bin" 2>/dev/null || true
[ -d "${IAN_HOME}/.local/bin" ] && chmod 755 "${IAN_HOME}/.local" "${IAN_HOME}/.local/bin" 2>/dev/null || true
echo "    Secured sensitive files (~/.ssh, ~/.gnupg, ~/.config)."

# -----------------------------------------------------------------------------
# 3. Passwordless Sudoers rule
# -----------------------------------------------------------------------------
echo "--> 3. Configuring passwordless sudoers entry..."
cat << SUDO_EOF > /etc/sudoers.d/haven-bot
# Allow host user '${IAN_USER}' to invoke commands as '${BOT_USER}' without password
${IAN_USER} ALL=(${BOT_USER}) NOPASSWD: ALL
SUDO_EOF
chmod 440 /etc/sudoers.d/haven-bot
echo "    Written /etc/sudoers.d/haven-bot."

# -----------------------------------------------------------------------------
# 4. Workspace & Directory Structure
# -----------------------------------------------------------------------------
echo "--> 4. Setting up ${BOT_USER} directories and workspace..."
sudo -u "${BOT_USER}" mkdir -p \
  "${BOT_HOME}/Code" \
  "${BOT_HOME}/.config/haven-agent-gh" \
  "${BOT_HOME}/.claude/hooks" \
  "${BOT_HOME}/.claude/skills" \
  "${BOT_HOME}/.claude/projects/-home-${BOT_USER}-Code-Haven/memory"

BOT_REPO="${BOT_HOME}/Code/Haven"
if [ ! -d "${BOT_REPO}/.git" ]; then
  echo "    Cloning Haven repository into ${BOT_REPO}..."
  sudo -u "${BOT_USER}" git clone --recurse-submodules https://github.com/GlassHaven/Haven.git "${BOT_REPO}"
else
  echo "    Haven repository already present in ${BOT_REPO}."
fi

# -----------------------------------------------------------------------------
# 5. GitHub Fine-Grained Token & Scratch State Sync
# -----------------------------------------------------------------------------
echo "--> 5. Syncing GitHub auth environment and state files..."
ENV_FILE="${IAN_HOME}/.haven-agent.env"

if [ ! -f "${ENV_FILE}" ] || ! grep -q 'GH_TOKEN="github_pat_' "${ENV_FILE}" 2>/dev/null; then
  cat << 'PAT_GUIDE'

================================================================================
 [GITHUB AUTH SETUP] Fine-Grained Personal Access Token (PAT) Required
================================================================================
 To isolate the maintainer bot with least-privilege permissions:

 1. Open in your browser:
    https://github.com/settings/personal-access-tokens/new

 2. Configure Token Settings:
    - Token name:        Haven Maintainer Agent
    - Expiration:        e.g. 90 days (or custom)
    - Resource owner:    GlassHaven (select organization)
    - Repository access: Only select repositories -> Haven

 3. Set Permissions (under Repository permissions):
    - Issues:           Read and write   (triage, label, reply, close)
    - Discussions:      Read and write   (triage and reply to discussions)
    - Contents:         Read and write   (commit & push to main, release assets)
    - Actions:          Read and write   (query status, rerun jobs, approve deployment gate)
    - Pull requests:    Read-only        (review diffs)
    - All others:       No access        (Administration, Secrets, Webhooks, Keys)

 4. Generate the token, copy the 'github_pat_...' string, and paste into:
    ~/.haven-agent.env
================================================================================

PAT_GUIDE

  if [ ! -f "${ENV_FILE}" ]; then
    cat << 'ENV_TEMPLATE' > "${ENV_FILE}"
# Haven Dedicated Agent Authentication Environment
export GH_CONFIG_DIR="$HOME/.config/haven-agent-gh"
export GH_TOKEN=""
export GITHUB_TOKEN="$GH_TOKEN"
export HAVEN_BOT_USER="GlassOnTin"
ENV_TEMPLATE
    chown "${IAN_USER}:${IAN_USER}" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
  fi
fi

if [ -f "${ENV_FILE}" ]; then
  cp "${ENV_FILE}" "${BOT_HOME}/.env"
  cp "${ENV_FILE}" "${BOT_REPO}/.env"
  chown "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/.env" "${BOT_REPO}/.env"
  chmod 600 "${BOT_HOME}/.env" "${BOT_REPO}/.env"
  echo "    Copied scoped GitHub auth .env."
fi

if [ -d "${HAVEN_REPO_SRC}/scratch" ]; then
  sudo -u "${BOT_USER}" mkdir -p "${BOT_REPO}/scratch"
  tar -cf - -C "${HAVEN_REPO_SRC}/scratch" . | sudo -u "${BOT_USER}" tar -xf - -C "${BOT_REPO}/scratch"
  echo "    Synchronized scratch/ files and maintain-state.md."
fi

# -----------------------------------------------------------------------------
# 6. Memory Store, Memory Skills & Hooks
# -----------------------------------------------------------------------------
echo "--> 6. Setting up Memory Store & Skills..."
IAN_PROJECT_MEMORY="${IAN_HOME}/.claude/projects/-home-${IAN_USER}-Code-Haven/memory"
BOT_PROJECT_MEMORY="${BOT_HOME}/.claude/projects/-home-${BOT_USER}-Code-Haven/memory"

if [ -d "${IAN_PROJECT_MEMORY}" ]; then
  tar -cf - -C "${IAN_PROJECT_MEMORY}" . | sudo -u "${BOT_USER}" tar -xf - -C "${BOT_PROJECT_MEMORY}"
  echo "    Synchronized project memory files to ${BOT_PROJECT_MEMORY}."
fi

if [ -d "${IAN_HOME}/Code/claude-memory-skills/skills" ]; then
  tar -cf - -C "${IAN_HOME}/Code/claude-memory-skills/skills" . | sudo -u "${BOT_USER}" tar -xf - -C "${BOT_HOME}/.claude/skills"
  echo "    Installed memory skills (memory-lint, memory-compact, recall, memory-reindex)."
fi

if [ -f "${IAN_HOME}/.claude/hooks/memory-usage.py" ]; then
  cp "${IAN_HOME}/.claude/hooks/memory-usage.py" "${BOT_HOME}/.claude/hooks/"
  chown "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/.claude/hooks/memory-usage.py"
fi

if [ -f "${IAN_HOME}/.claude/CLAUDE.md" ]; then
  cp "${IAN_HOME}/.claude/CLAUDE.md" "${BOT_HOME}/.claude/CLAUDE.md"
  chown "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/.claude/CLAUDE.md"
fi

# -----------------------------------------------------------------------------
# 7. Claude Code Config & PreToolUse Security Hook
# -----------------------------------------------------------------------------
echo "--> 7. Hardening Claude Code configuration..."
# Copy pre-tool hook with GitHub auth and keystore guards
if [ -f "${IAN_HOME}/.claude/hooks/haven-require-signing-env.sh" ]; then
  cp "${IAN_HOME}/.claude/hooks/haven-require-signing-env.sh" "${BOT_HOME}/.claude/hooks/"
  chown -R "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/.claude/hooks"
  chmod 755 "${BOT_HOME}/.claude/hooks/haven-require-signing-env.sh"
fi

# Initialize clean .claude.json without unneeded MCP schemas (saves ~80k tokens per prompt)
python3 - << PYEOF
import json, os

ian_config_path = '${IAN_HOME}/.claude.json'
bot_config_path = '${BOT_HOME}/.claude.json'

if os.path.exists(ian_config_path):
    with open(ian_config_path, 'r') as f:
        cfg = json.load(f)
    # Configure Haven device MCP server (strip other external MCP servers to prevent prompt schema bloat)
    cfg['mcpServers'] = {
        "haven": {
            "type": "http",
            "url": "http://127.0.0.1:8788/mcp"
        }
    }
    cfg['hasCompletedOnboarding'] = True
    with open(bot_config_path, 'w') as f:
        json.dump(cfg, f, indent=2)
    os.chown(bot_config_path, $(id -u ${BOT_USER}), $(id -g ${BOT_USER}))
    os.chmod(bot_config_path, 0o600)
PYEOF

# Copy settings.json and set clean mcpServers
if [ -f "${IAN_HOME}/.claude/settings.json" ]; then
  python3 - << PYEOF
import json
with open('${IAN_HOME}/.claude/settings.json', 'r') as f:
    s = json.load(f)
s['mcpServers'] = {}
with open('${BOT_HOME}/.claude/settings.json', 'w') as f:
    json.dump(s, f, indent=2)
PYEOF
  chown "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/.claude/settings.json"
fi

# Copy credentials if present
if [ -f "${IAN_HOME}/.claude/.credentials.json" ]; then
  cp "${IAN_HOME}/.claude/.credentials.json" "${BOT_HOME}/.claude/.credentials.json"
  chown "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/.claude/.credentials.json"
  chmod 600 "${BOT_HOME}/.claude/.credentials.json"
fi

# -----------------------------------------------------------------------------
# 8. Create /home/haven-bot/run-agent.sh runner
# -----------------------------------------------------------------------------
echo "--> 8. Creating agent execution script (${BOT_HOME}/run-agent.sh)..."
cat << 'RUNNER_EOF' > "${BOT_HOME}/run-agent.sh"
#!/usr/bin/env bash
set -e

export HOME="/home/haven-bot"
export CLAUDE_PROJECT_DIR="/home/haven-bot/Code/Haven"
export TMPDIR="/home/haven-bot/tmp"
export XDG_CACHE_HOME="/home/haven-bot/.cache"

# Toolchains
export ANDROID_HOME="/home/ian/Android/Sdk"
export RUSTUP_HOME="/home/ian/.rustup"
export CARGO_HOME="/home/ian/.cargo"
export PATH="/home/ian/.local/bin:/home/ian/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"

# GitHub Auth Isolation
export GH_CONFIG_DIR="/home/haven-bot/.config/haven-agent-gh"

# Local llama.cpp endpoint configuration (Dual RTX 5090 / Qwen3.8-Flash-Next)
export ANTHROPIC_BASE_URL="http://127.0.0.1:8090"
export ANTHROPIC_API_KEY="llama-local"
export ANTHROPIC_AUTH_TOKEN="llama-local"
export ANTHROPIC_MODEL="qwen3.8-flash-next"
export ANTHROPIC_SMALL_FAST_MODEL="qwen3.8-flash-next"
export ANTHROPIC_DEFAULT_OPUS_MODEL="qwen3.8-flash-next"
export ANTHROPIC_DEFAULT_SONNET_MODEL="qwen3.8-flash-next"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="qwen3.8-flash-next"

# Context & Behavior Flags (Exact match to ian bash_module.sh)
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
export CLAUDE_CODE_MAX_CONTEXT_TOKENS=220000

cd "$CLAUDE_PROJECT_DIR"

if [ -f "$HOME/.env" ]; then
  source "$HOME/.env"
fi

exec /home/ian/.local/bin/claude --allow-dangerously-skip-permissions --model qwen3.8-flash-next "$@"
RUNNER_EOF

chown "${BOT_USER}:${BOT_USER}" "${BOT_HOME}/run-agent.sh"
chmod 755 "${BOT_HOME}/run-agent.sh"

# -----------------------------------------------------------------------------
# 9. Create /home/ian/.local/bin/lclaude-bot wrapper
# -----------------------------------------------------------------------------
echo "--> 9. Creating host wrapper (${IAN_HOME}/.local/bin/lclaude-bot)..."
mkdir -p "${IAN_HOME}/.local/bin"
cat << 'WRAPPER_EOF' > "${IAN_HOME}/.local/bin/lclaude-bot"
#!/usr/bin/env bash
# lclaude-bot: Launches Claude / Qwen maintainer agent under the isolated haven-bot user account
exec sudo -u haven-bot /home/haven-bot/run-agent.sh "$@"
WRAPPER_EOF

chown "${IAN_USER}:${IAN_USER}" "${IAN_HOME}/.local/bin/lclaude-bot"
chmod 755 "${IAN_HOME}/.local/bin/lclaude-bot"

# -----------------------------------------------------------------------------
# 10. Health Check & Validation
# -----------------------------------------------------------------------------
echo "--> 10. Running health checks..."
sudo -u "${BOT_USER}" python3 "${BOT_HOME}/.claude/skills/memory-lint/lint.py" "${BOT_PROJECT_MEMORY}" --brief
sudo -u "${BOT_USER}" /home/ian/.local/bin/claude --version

echo ""
echo "=================================================================="
echo " Haven Maintainer Bot Setup Complete!"
echo " Launch the agent anytime with:"
echo "   lclaude-bot"
echo "=================================================================="
