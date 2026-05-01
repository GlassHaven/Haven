# Release Process

Each release needs **three pieces of release-note content** written before the tag is pushed. Missing any of them is why previous releases shipped with bare "Full Changelog" links or empty F-Droid changelogs.

| Surface | Location | Limit |
|---|---|---|
| F-Droid | `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt` | ~500 chars |
| GitHub release body | Written below via `gh release edit` (or the UI) at tag time | no limit |
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

Also update the static release badge in `README.md` to match — the
`shields.io/badge/release-v<x.y.z>-blue` URL hard-codes the version
because the dynamic shields lookup is unreliable behind GitHub's
camo image proxy.

## 2. Write the changelog

Create `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt` — the short release note that F-Droid displays. Aim for one short summary line followed by a paragraph or two of what actually changed. Keep under ~500 bytes.

Every tag must have one. F-Droid will publish with the previous version's text if you forget.

## 3. Commit, tag, push

```bash
git add app/build.gradle.kts fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt <other changed files>
git commit -m "Bump to v<x.y.z>"
git tag v<x.y.z>
git push origin main v<x.y.z>
```

The `v*` tag triggers the **Release** workflow on GitHub Actions which:

- Builds a signed APK and AAB (keystore password from GitHub secrets)
- Creates a GitHub release with the APK attached

## 4. Fill in the GitHub release body

Once the release exists, edit its body so users see what changed instead of just a compare link. The fastlane changelog is a solid seed — copy it and expand with any context worth highlighting.

```bash
gh release edit v<x.y.z> --repo GlassHaven/Haven --notes-file fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt
```

Or open the release on GitHub and paste the notes into the body field.

## 5. F-Droid

F-Droid auto-detects new tags via `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`. The bot opens an update MR against `fdroid/fdroiddata`; linsui merges after the build succeeds. The fastlane changelog you wrote in step 2 is the text the F-Droid client displays.

### Tool-version alignment

F-Droid's buildserver is the authoritative build environment — failing there blocks the public release. The recipe at [fdroiddata/metadata/sh.haven.app.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/sh.haven.app.yml) pins specific tool versions, and our CI mirrors them in `.github/actions/setup-toolchain/action.yml`. When you bump a pin in one place, bump it in the other — otherwise CI passes on a version F-Droid doesn't have and vice-versa.

The buildserver no longer pre-installs NDKs in its Docker image — `fdroidserver.common.auto_install_ndk` fetches whatever the build entry's `ndk:` field requests via `sdkmanager` on demand. So the NDK pin lives in our gradle modules; the F-Droid manifest's `ndk:` line just tells the buildserver to install the same one.

| Tool | F-Droid pin | Our CI |
|---|---|---|
| NDK | `r29` (`29.0.14206865`) — declared per-module in `core/local`, `termlib/lib` | `sdkmanager "ndk;29.0.14206865"` in the composite action |
| Rust | `rustup default 1.85.0` | `rustup default 1.85.0` |
| `cargo-ndk` | `3.5.4` | `3.5.4` |
| CMake | `3.31.6` | `3.31.6` |
| `gomobile` / `gobind` | `@latest` | `@latest` (cached, refreshes via key bump) |
| Go | `1.26` (implicit — matches our `actions/setup-go`) | `1.26` |

Things Haven's CI does **not** exercise but F-Droid does:

- `build-proot/build.sh`, `build-ffmpeg/build.sh`, `wayland-android/build_liblabwc_android.sh` — F-Droid has these in its `scandelete` list (it deletes the committed `.so`s and rebuilds from source). Our CI uses the committed pre-built binaries. A regression in any of those scripts won't show up until the F-Droid bot MR's build step fails on GitLab. If you touch those scripts or their deps, smoke-test locally before tagging:
  ```bash
  rm -rf core/ffmpeg/src/main/jniLibs core/wayland/src/main/jniLibs core/local/src/main/jniLibs
  ABI=arm64-v8a bash build-ffmpeg/build.sh
  bash build-proot/build.sh
  pushd wayland-android && ABI=arm64-v8a ./build_liblabwc_android.sh && popd
  ```

### If the F-Droid build fails

Watch [fdroid/fdroiddata merge requests tagged with our app](https://gitlab.com/fdroid/fdroiddata/-/merge_requests?scope=all&search=sh.haven.app). The bot MR shows the build log; common causes are tool-version skew vs what's above, new apt deps the recipe's `sudo:` block doesn't install, or submodule commits the buildserver can't reach. When fixed, comment `@fdroidbot rebuild` on the MR.

## 6. Verify

- [ ] GitHub release page has APK and a non-empty body
- [ ] `fastlane/.../changelogs/<code>.txt` exists and is committed
- [ ] CI workflow passes (lint + tests)

## Signing

The release keystore `haven-release.jks` is in the repo root. Passwords are stored in GitHub secrets:

- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

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
