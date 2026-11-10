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

# Disable bubblewrap (glycin SVG loader), Chromium/Electron user-namespace,
# and Mozilla seccomp content sandboxes inside PRoot (#718, #780, #827).
export GLYCIN_DISABLE_SANDBOX="${GLYCIN_DISABLE_SANDBOX:-i-know-the-risks}"
export ELECTRON_DISABLE_SANDBOX="${ELECTRON_DISABLE_SANDBOX:-1}"
export MOZ_DISABLE_CONTENT_SANDBOX="${MOZ_DISABLE_CONTENT_SANDBOX:-1}"
export MOZ_DISABLE_GMP_SANDBOX="${MOZ_DISABLE_GMP_SANDBOX:-1}"
export MOZ_DISABLE_RDD_SANDBOX="${MOZ_DISABLE_RDD_SANDBOX:-1}"
export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX="${MOZ_DISABLE_SOCKET_PROCESS_SANDBOX:-1}"
export MOZ_DISABLE_UTILITY_SANDBOX="${MOZ_DISABLE_UTILITY_SANDBOX:-1}"
