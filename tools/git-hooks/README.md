# Git hooks

Project-tracked hooks live here. To enable them in your local clone:

```sh
git config core.hooksPath tools/git-hooks
```

That points git at this directory for all hook lookups. Run it once
per fresh clone; thereafter any updates to scripts here apply to
everyone who's set the config.

## Hooks in this directory

- **`pre-push`** — catches the submodule footgun where the parent
  repo pushes a commit referencing a submodule SHA that CI can't
  compile against (because the submodule had uncommitted or unpushed
  work the parent's working tree silently relied on). Bit us during
  v5.24.50 / #94. Override with `git push --no-verify` if you really
  mean it.

## Why not symlinks into `.git/hooks/`?

`.git/` isn't tracked, so symlinks would still need a one-time
install step per clone. `core.hooksPath` is the cleanest equivalent
and doesn't require fiddling with symlink permissions on Windows.
