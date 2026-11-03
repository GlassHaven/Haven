# Haven PRoot system-wide shell snippet.
#
# Sourced automatically by /etc/profile for every login shell, so any
# user account created inside this rootfs inherits the baseline PATH
# without needing /root/.profile.
#
# Keep this file thin — per-user interactive defaults (prompt,
# aliases, history, rexec helper, welcome message) live in
# /root/.profile so root can override them freely. This file only
# carries universals that every account should share.

# Ensure ~/.local/bin is on PATH for tools users install via
# curl|sh-style installers.
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) export PATH="$HOME/.local/bin:$PATH" ;;
esac

# Disable bubblewrap (glycin SVG loader) and Chromium/Electron user-namespace
# sandboxes inside PRoot (#718, #780).
export GLYCIN_DISABLE_SANDBOX="${GLYCIN_DISABLE_SANDBOX:-i-know-the-risks}"
export ELECTRON_DISABLE_SANDBOX="${ELECTRON_DISABLE_SANDBOX:-1}"
