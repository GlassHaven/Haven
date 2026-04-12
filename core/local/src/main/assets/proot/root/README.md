# Haven PRoot Alpine environment

You are in Alpine Linux running inside Haven's PRoot on your device.
This is a regular Linux userland — `apk` works, most small tools
work, long-lived processes survive across Haven sessions, and you
are root inside the chroot (no root on the host). The rootfs is
stored in Haven's private data directory and is removed if Haven
is uninstalled.

## What's already here

Alpine's minirootfs with `busybox` as `/bin`, plus a tiny baseline
that makes the environment immediately usable without any further
setup:

- `bash`             — interactive shell with readline support
- `curl`             — for fetching install scripts and testing endpoints
- `ca-certificates`  — so HTTPS to the public internet just works
- `openssh-client`   — `ssh`, `scp`, `ssh-keygen`, `~/.ssh/config`

That's deliberately minimal. Haven does not impose a toolchain
preference, so anything beyond these four is one `apk add` away.

## Installing tools

Refresh the package index first:

```sh
apk update
```

Then add what you need. Some useful starter sets:

```sh
# Generally useful interactive tools
apk add bash git openssh-client vim nano less ripgrep fd jq curl htop

# Build chains you might want
apk add build-base make
apk add python3 py3-pip
apk add nodejs npm
apk add go
apk add rust cargo

# Services you can run locally
apk add tmux screen zellij
apk add sqlite postgresql-client mariadb-client redis
```

Alpine uses `musl libc`, not `glibc`. Most open-source tools work,
a handful of closed-source binaries do not.

## The composition pattern: point of presence, not computer

Haven's whole design idea is that the phone is where you *are* and
distant machines are where compute happens. This PRoot is where a
persistent agent (editor, shell, small script, AI CLI — your choice)
lives on the phone, and SSH is how that agent reaches any bigger
machine that has the real toolchain.

### Setting up a remote workstation

Inside this PRoot — `~/.ssh` is already created for you with 0700
permissions, `openssh-client` is already installed:

```sh
ssh-keygen -t ed25519 -C "proot@$(hostname)" -N "" -f ~/.ssh/id_ed25519
cat ~/.ssh/id_ed25519.pub  # copy this output
```

On your workstation or VPS:

```sh
# Paste the public key into ~/.ssh/authorized_keys
```

Back in this PRoot, add the host to your SSH config:

```sh
cat >> ~/.ssh/config <<'EOF'
Host myworkstation
    HostName 192.168.x.y
    User yourname
    Port 22
    IdentityFile ~/.ssh/id_ed25519
    ServerAliveInterval 30
EOF
chmod 600 ~/.ssh/config
```

Test it:

```sh
ssh myworkstation "uname -a"
```

### Remote-build workflow

With `REMOTE_HOST` set, the `rexec` helper in `~/.profile` is the
bridge. Put project-specific helpers in `~/.profile.local`:

```sh
cat > ~/.profile.local <<'EOF'
export REMOTE_HOST=myworkstation
export REMOTE_DIR=/home/yourname/code/yourproject

rbuild() {
    git push --force-with-lease origin "$(git rev-parse --abbrev-ref HEAD)"
    rexec "cd $REMOTE_DIR && make ${1:-all}"
}
EOF
. ~/.profile.local
```

Now a single `rbuild` pushes the current branch to the workstation
and runs `make` there. Adapt to your own project's build command.

### Allowing pushes to the workstation's current branch

If your workstation's working tree is the git remote (as in "the
PRoot pushes its commits directly into my laptop's checkout"),
configure the receiving repo once:

```sh
# Run on the workstation
git -C /path/to/repo config receive.denyCurrentBranch updateInstead
```

Then pushing from the PRoot updates the workstation's working tree
in place — no intermediate bare repo, no GitHub round trip.

## The MCP agent endpoint

If you toggled **"Agent endpoint (MCP)"** on in Haven's Settings,
there's a local JSON-RPC endpoint at `http://127.0.0.1:8730/mcp`
that any MCP-aware client (including anything running in this
PRoot) can query to read Haven's state — saved connections,
active sessions, rclone directories, etc. The endpoint is
loopback-only and read-only. See Haven's Settings for details.

## Files you can edit

- `~/.profile` — default shell profile written by Haven. Haven may
  refresh this on future installs, so prefer editing
  `~/.profile.local` for personal customisation.
- `~/.profile.local` — your private additions. Never touched by
  Haven.
- `~/.ssh/config`, `~/.ssh/authorized_keys`, `~/.ssh/id_ed25519*`
  — your SSH identity and known hosts.

## Things this environment is NOT

- **Not a specific agent's sandbox.** Haven is neutral about what
  you run here. Any CLI that runs on Alpine arm64 (musl) runs here.
- **Not shipped with any cloud account, API key, or service
  credentials.** You bring your own.
- **Not linked to Haven's own credential store.** Keys here are
  separate from the connection profiles you saved in the Haven UI.

Have fun.
