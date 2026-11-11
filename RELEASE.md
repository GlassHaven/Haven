# Release Process

Each release needs **three pieces of release-note content** written before the tag is pushed. Missing any of them is why previous releases shipped with bare "Full Changelog" links or empty F-Droid changelogs.

| Surface | Location | Limit |
|---|---|---|
| F-Droid | `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt` | 500 chars (`wc -m`) |
| GitHub release body | `CHANGELOG.md` `## v<x.y.z>` section (extracted automatically by `publish`) | no limit |
| Commit message body | Second line + body of the bump commit | keep short |

The arm64 versionCode is `versionCode × 10 + 1` (see `app/build.gradle.kts`: `output.versionCodeOverride = (defaultConfig.versionCode ?: 0) * 10 + abiCode`). For versionCode 246 → `2461.txt`.

## 0. Upstream any generic termlib fixes

Before tagging, look over the termlib commits since the last Haven release. Anything generic (no Haven-module dependency, no Haven-specific config, no references to `sh.haven.*`) belongs upstream in `connectbot/termlib` — carrying it on our fork long-term is rebase hazard (see `termlib/REBASE.md`).

```bash
cd /home/ian/Code/Haven/termlib
# Commits since the last upstream-synced point
git log upstream/main.. --oneline
```

For each candidate, decide:

- **Upstream-worthy** → open a PR against `connectbot/termlib` **before** tagging the Haven release. Even if it's not merged by release time, open it now so the conversation starts. Paste the PR URL into the Haven release notes so users can track it. Examples that should be upstreamed: IME robustness fixes, scroll/URL-detector fixes, terminal-core API additions (like `getSnapshotLineTexts`), the `replaceText` / `updateSelection` / internal-DEL fixes shipped in v5.21.0 (#99).
- **Haven-only, correctly** → the allowed list is in `termlib/REBASE.md` (`rawKeyboardMode`, `allowStandardKeyboard`, `composingText: StateFlow<String>`). Anything else on that list is either still awaiting upstream review or needs to be reconsidered.
- **Blocked on upstream review** → note the upstream PR number in the Haven release notes so users know the patch is in-flight, not divergent.

Don't block the Haven release on upstream acceptance — open the PR, link it, and ship. The rebase checklist (`termlib/REBASE.md`) picks up accepted PRs on the next monthly rebase and drops them from the fork.

## 1. Bump version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <increment>
versionName = "<x.y.z>"
```

★ **Reserved major version:**

- **`6.0.0` is reserved for the SSH engine default flip (#58)** — JSch →
  sshlib. That version was promised in the issue thread and is the one change
  that alters the default behaviour of every SSH connection, so don't spend it
  on anything else. It is blocked on connectbot/sshlib#238.

Also update the static release badge in `README.md` to match — the
`shields.io/badge/release-v<x.y.z>-blue` URL hard-codes the version
because the dynamic shields lookup is unreliable behind GitHub's
camo image proxy.

## 2. Write the changelog — BOTH files

A release needs release notes in **two** places. Missing either is a broken release:

1. **F-Droid** — `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt`, the short note the F-Droid client shows. One summary line + a paragraph or two, under ~500 bytes. Miss it and F-Droid publishes the *previous* version's text.

2. **GitHub release body** — a `## v<x.y.z>` section at the top of the top-level `CHANGELOG.md` (newest first; the heading may carry a ` — title` suffix). The `publish` job runs `scripts/check-changelog.sh extract v<x.y.z>` and uses that section verbatim as the GitHub Release body (so there's no manual `gh release edit --notes` step). **Miss it and the `publish` job FAILS — *after* the full ~40-minute build — with `✖ No CHANGELOG.md section`, and no release is created.** House style: emoji + bold headline + prose, `(#NNN, thanks <user>)`.

★ **Before tagging**, run `bash scripts/check-changelog.sh check` (asserts the current `versionName` has a `CHANGELOG.md` section) and `bash scripts/check-changelog.sh extract v<x.y.z>` (prints it) — both must pass. If `publish` fails post-build for a missing section, add it, commit, then **move the tag** (`git tag -d v<x.y.z>; git push origin :refs/tags/v<x.y.z>; git tag v<x.y.z>; git push origin v<x.y.z>`) to re-trigger — re-running just the failed `publish` job re-uses the old tagged SHA and fails again.

## 3. Commit, tag, push

```bash
git add app/build.gradle.kts CHANGELOG.md fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt <other changed files>
git commit -m "Bump to v<x.y.z>"
git tag v<x.y.z>
git push origin main v<x.y.z>
```

The `v*` tag triggers the **Release** workflow (`.github/workflows/release.yml`),
a gated pipeline hardened under #211. It runs four jobs in sequence:

1. **`verify-ci`** — reads the CI workflow run status for the tagged SHA. If
   `main` CI already went green for this exact commit, the test job is skipped
   (same commit ⇒ same result); otherwise the pipeline runs the tests itself.
2. **`test`** — `./gradlew testDebugUnitTest :app:testArm64FullDebugUnitTest`.
   The build is gated on this, so a red test stops the release here. (Only tag
   a commit whose `main` CI run has already passed.)
3. **`build`** — signed `full` APK, `terminal` APK, and `full` AAB across all
   three ABIs (`arm64`, `x64`, `armv7`); keystore + passwords come from repo
   secrets.
4. **`publish`** — ⏸ **reviewer sign-off gate (#211).** This job runs under the
   protected **`signing` GitHub Environment**, which requires an explicit
   approval from a designated reviewer (currently **@GlassOnTin**) and is
   restricted to `v*` tags / `main`. **The run pauses here until the reviewer
   approves** — so no single account can ship with the signing identity
   unilaterally. On approval it extracts `CHANGELOG.md` via
   `scripts/check-changelog.sh extract v<x.y.z>`, writes a build-provenance
   attestation, generates `SHA256SUMS`, and publishes the GitHub Release with
   all 10 assets (6 APKs + 3 AABs + `SHA256SUMS`).

### Approve the publish (the sign-off)

Until the reviewer approves, the run sits at `publish` and **the release does not
exist yet**. Approve it one of two ways:

- **GitHub UI (simplest):** *Actions → Release → (this run)* shows a
  **"Review deployments"** prompt → tick **signing** → **Approve and deploy**.
  Rejecting cancels the publish without shipping anything.
- **CLI:**
  ```bash
  RUN=$(gh run list --workflow=Release --limit 1 --json databaseId --jq '.[0].databaseId')
  ENV=$(gh api repos/GlassHaven/Haven/actions/runs/$RUN/pending_deployments --jq '.[0].environment.id')
  gh api repos/GlassHaven/Haven/actions/runs/$RUN/pending_deployments \
    -F "environment_ids[]=$ENV" -f state=approved -f comment="release v<x.y.z>"
  ```

The same `signing` Environment also gates `extract-secrets.yml`, so exporting the
keystore bundle needs the identical reviewer approval. Manage the reviewer list
under *Settings → Environments → signing*.

## 4. Verify published release assets & signatures

Once `publish` completes, verify that `isDraft=false`, the release body matches `CHANGELOG.md`, all 10 assets are present, `SHA256SUMS` matches the downloaded APKs/AABs, and `apksigner verify --verbose` succeeds on the APKs.

## 5. F-Droid

F-Droid auto-detects new tags via `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`. The bot opens an update MR against `fdroid/fdroiddata`; linsui merges after the build succeeds. The fastlane changelog you wrote in step 2 is the text the F-Droid client displays.

### Tool-version alignment

F-Droid's buildserver is the authoritative build environment — failing there blocks the public release. The recipe at [fdroiddata/metadata/sh.haven.app.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/sh.haven.app.yml) pins specific tool versions, and our CI mirrors them in `.github/actions/setup-toolchain/action.yml`. When you bump a pin in one place, bump it in the other — otherwise CI passes on a version F-Droid doesn't have and vice-versa.

> **Cross-compiler apt deps (`sudo:` block ↔ setup-toolchain):** the wayvnc-shim (`buildWayvncShim`) cross-builds a glibc `.so` for **every** ABI on any build, so the buildserver needs each target's cross-gcc + multiarch libc headers. x86_64 is native on the buildserver (no package). The recipe's `sudo:` install line must carry:
> `gcc-aarch64-linux-gnu libc6-dev-arm64-cross gcc-arm-linux-gnueabihf libc6-dev-armhf-cross`
> The **armhf** pair (`gcc-arm-linux-gnueabihf libc6-dev-armhf-cross`) is **new in v5.68.1** (the armv7 wayvnc shim, #327) — every existing recipe entry only has the arm64 pair, so the v5.68.x build entry must add armhf or the whole build fails at `buildWayvncShim`.

The buildserver no longer pre-installs NDKs in its Docker image — `fdroidserver.common.auto_install_ndk` fetches whatever the build entry's `ndk:` field requests via `sdkmanager` on demand. So the NDK pin lives in our gradle modules; the F-Droid manifest's `ndk:` line just tells the buildserver to install the same one.

| Tool | F-Droid pin | Our CI |
|---|---|---|
| JDK | `21` | Temurin `21` (`actions/setup-java`) |
| NDK | `r29` (`29.0.14206865`) — declared per-module in `core/local`, `termlib/lib` | `sdkmanager "ndk;29.0.14206865"` in the composite action |
| Rust | `rustup default 1.94.1` | `rustup default 1.94.1` (`rdp-kotlin/rust/rust-toolchain.toml` pins `1.94.1`) |
| `cargo-ndk` | `3.5.4` | `3.5.4` |
| CMake | `3.31.6` | `3.31.6` |
| `gomobile` / `gobind` | `@v0.0.0-20251021151156-188f512ec823` | **pinned** `@v0.0.0-20251021151156-188f512ec823` (matches `rclone-android/go/go.sum`) |
| Go | `1.27` | `1.27` (`actions/setup-go`) |

> Supply-chain hardening (#211) pinned our CI's `gomobile`/`gobind` to the
> `go.sum` pseudo-version instead of `@latest`. The F-Droid recipe still uses
> `@latest`; a follow-up should pin it there too so both build the binding
> generator from the same commit.

Things Haven's CI does **not** exercise but F-Droid does:

- `build-proot/build.sh`, `build-ffmpeg/build.sh`, `wayland-android/build_liblabwc_android.sh`, `wayland-android/build-native-helpers.sh` — **this gap has narrowed** (2026-08-07): #493 removed the committed `.so`s, so no `jniLibs` directory has tracked files any more and each script is now invoked by a Gradle task (`:core:local:buildProotNatives`, `:core:ffmpeg:buildFfmpegNatives`, `:core:wayland:buildWaylandNatives`). Our packaging jobs build them from source like F-Droid does.

  Two differences remain, and both can still hide a broken script:
  - **F-Droid builds arm64 only, always cold.** CI keys these tasks on script contents and the submodule SHA and restores from the Gradle build cache, so an unchanged script's output can arrive without compiling. A newly missing build-time tool stays invisible until a cache miss; F-Droid hits it immediately.
  - **A binary produced by a step the fdroiddata recipe does not name is `scandelete`d and never replaced.** Adding an output here means adding the build step there in the same change.

  So if you touch those scripts or their deps, smoke-test locally before tagging:
  ```bash
  rm -rf core/ffmpeg/src/main/jniLibs core/wayland/src/main/jniLibs core/local/src/main/jniLibs
  ABI=arm64-v8a bash build-ffmpeg/build.sh
  bash build-proot/build.sh
  pushd wayland-android && ABI=arm64-v8a ./build_liblabwc_android.sh && ABI=arm64-v8a ./build-native-helpers.sh && popd
  ```
  Anything in `core/wayland/src/main/jniLibs/` that no build step regenerates is **deleted from the F-Droid APK and never replaced** — that is how their builds shipped with no XWayland wrapper, no GLES benchmark and no GPU renderer. Whenever you add a binary there, add the build step to the fdroiddata recipe in the same change. The two `libvirgl_*_server.so` binaries still have no build script and are still missing from F-Droid builds.

## Native binaries: the rules

Haven ships native code that F-Droid must be able to rebuild from source. Three
rules follow from that, and all three came from failures rather than theory.

**1. Never commit a binary.** `scripts/check-no-committed-binaries.sh` enforces
it, in CI and in `preflight.sh` (so the pre-push hook catches it first). Detection
is by content magic — ELF, Mach-O, PE, dex, Java/Android archive — not by file
extension, so renaming a blob does not get it past. `gradle-wrapper.jar` is the
only allowlist entry.

A committed binary is unauditable in review, corresponds to no source change,
and goes stale in silence. It has bitten us three times: #469 (a
`liblabwc_android.so` three versions behind the source — the cage crashed while
the code looked right), rdp (armv7 missing from the cargo target list, so armv7
users ran whatever was checked in), and the wayland set below.

The `GRANDFATHERED` list in that script holds the binaries that predate the gate.
It may only shrink — the check fails if an entry stops being tracked, so
completing a migration forces its own cleanup and the list cannot quietly become
a second allowlist. Tracked in #493.

**2. A binary with no build step is deleted from the F-Droid APK and never
replaced.** Their recipe `scandelete`s the jniLibs directories and rebuilds what
its `build:` block names. `core/wayland/src/main/jniLibs` was scandeleted while
only `liblabwc_android.so` was rebuilt, so F-Droid's APK shipped with **no
XWayland wrapper, no GLES benchmark and no GPU renderer** — for months, silently,
because our own APK carried the committed copies and nothing compared the two.
**Whenever you add a binary to a jniLibs directory, add its build step to the
fdroiddata recipe in the same change**, on the *last* `Builds` entry —
`AutoUpdateMode: Version` clones that entry for every new tag, so an omission
propagates forever. The two `libvirgl_*_server.so` binaries still have no build
script and are still missing from F-Droid builds.

**3. Every fetched source is pinned and verified.** F-Droid's rule is that a
binary in the APK must be reproducible from source in their builder, and the
accepted recipes pin their sources to achieve it (mpv-android:
`srclibs: FFmpeg@a7425f7`; for a plain download,
`curl -Lo lua.tar.gz … ; echo "<sha256>  lua.tar.gz" | sha256sum -c -`). So:

- **source may be downloaded when pinned and hash-checked; a binary may not be,
  at any hash.** A checksum proves a blob is unchanged, never that it
  corresponds to any source — which is why "just pin the sha256 of the committed
  `.so`" is not a fix.
- `build-ffmpeg/build.sh` verifies all nine dependency tarballs by sha256 and all
  four git sources by **commit**, not by ref name. Ref names are not pins: x264
  was tracked at `stable`, a *branch*, so every build was free to compile
  different source with nothing to notice it by.
- Bumping a dependency means changing the version, running the build, taking the
  hash from the failure message, and checking it against upstream before
  committing it.

Note what is and is not claimed for those hashes: they were recorded from each
project's canonical HTTPS URL and cross-checked against an independent
re-download. They are trust-on-first-use pins, **not** verification against
upstream signatures. They catch a substitution or a silently re-rolled release;
they do not establish that the original tarball was genuine.

### If the F-Droid build fails

Watch [fdroid/fdroiddata merge requests tagged with our app](https://gitlab.com/fdroid/fdroiddata/-/merge_requests?scope=all&search=sh.haven.app). The bot MR shows the build log; common causes are tool-version skew vs what's above, new apt deps the recipe's `sudo:` block doesn't install, or submodule commits the buildserver can't reach. When fixed, comment `@fdroidbot rebuild` on the MR.

## Dependency verification

`gradle/verification-metadata.xml` pins a SHA-256 for every external Gradle/Maven artifact (sha256 only — `verify-signatures=false`). Any Gradle invocation now fails if a resolved artifact's checksum isn't listed, so a poisoned mirror or swapped artifact is caught.

**This means every dependency change must regenerate the file**, or the build fails with `Dependency verification failed ... is not in verification-metadata.xml`. After bumping any Gradle dependency / plugin (incl. merging a Dependabot `gradle` PR):

```bash
./gradlew --write-verification-metadata sha256 \
  :app:assembleArm64FullDebug testDebugUnitTest :app:testArm64FullDebugUnitTest lintDebug \
  --console=plain
git diff gradle/verification-metadata.xml   # review the added checksums on a clean checkout
```

The task set above is a superset of what CI and the release build resolve (no release-only or ABI-specific deps). It captures composite-build deps and the JitPack Tesseract4Android AAR. Native deps (Go modules, Rust crates) are out of scope — they're integrity-pinned by `go.sum` / `Cargo.lock`. `androidTest`/connected-test deps are not covered (CI doesn't run them); add them to the task list if that changes.

## 6. Verify

- [ ] CI workflow is green on the tagged commit (the `publish` sign-off gate
      assumes it — see `verify-ci`)
- [ ] `publish` deployment approved by a `signing`-environment reviewer (the
      release won't exist until then — see "Approve the publish" above)
- [ ] GitHub release page has the APK + AAB, `SHA256SUMS`, and a non-empty body
- [ ] `fastlane/.../changelogs/<code>.txt` exists and is committed

## Signing

The release keystore is decoded from `KEYSTORE_BASE64` in CI (and stored outside the repo locally, e.g. via `KEYSTORE_FILE` in `~/.haven-release.env`). Passwords are stored in GitHub secrets:

- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

The signing identity is **reviewer-gated** in CI (#211): the `publish` job and
the `extract-secrets.yml` bundle export both run under the protected `signing`
GitHub Environment, so neither ships/exports without an approval from a required
reviewer. See "Approve the publish (the sign-off)" under step 3.

Local release builds require these as environment variables:

```bash
export KEYSTORE_PASSWORD=<password>
export KEY_PASSWORD=<password>
./gradlew :app:bundleRelease
```

## F-Droid details

- F-Droid builds from source using the tagged commit.
- `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` means F-Droid auto-detects new tags.
- Initial inclusion MR: `fdroid/fdroiddata!33920` (merged).
