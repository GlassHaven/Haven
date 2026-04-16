# Release Process

Each release needs **three pieces of release-note content** written before the tag is pushed. Missing any of them is why previous releases shipped with bare "Full Changelog" links or empty F-Droid changelogs.

| Surface | Location | Limit |
|---|---|---|
| F-Droid / Play Store | `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt` | ~500 chars |
| GitHub release body | Written below via `gh release edit` (or the UI) at tag time | no limit |
| Commit message body | Second line + body of the bump commit | keep short |

The arm64 versionCode is `versionCode × 10 + 1` (see `app/build.gradle.kts`: `output.versionCodeOverride = (defaultConfig.versionCode ?: 0) * 10 + abiCode`). For versionCode 246 → `2461.txt`.

## 1. Bump version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <increment>
versionName = "<x.y.z>"
```

## 2. Write the changelog

Create `fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt` — the short release note that F-Droid and Google Play display. Aim for one short summary line followed by a paragraph or two of what actually changed. Keep under ~500 bytes.

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
gh release edit v<x.y.z> --repo GlassOnTin/Haven --notes-file fastlane/metadata/android/en-US/changelogs/<arm64VersionCode>.txt
```

Or open the release on GitHub and paste the notes into the body field.

## 5. Download Play Store bundle

```bash
mkdir -p releases/v<x.y.z>
gh release download v<x.y.z> --repo GlassOnTin/Haven --pattern '*.aab' -D releases/v<x.y.z>/
```

Upload the AAB to Google Play Console.

## 6. F-Droid

F-Droid auto-detects new tags via `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`. The bot opens an update MR against `fdroid/fdroiddata`; linsui merges after the build succeeds. The fastlane changelog you wrote in step 2 is the text the F-Droid client displays.

## 7. Verify

- [ ] GitHub release page has APK and a non-empty body
- [ ] `fastlane/.../changelogs/<code>.txt` exists and is committed
- [ ] CI workflow passes (lint + tests)
- [ ] Play Store bundle uploaded (if applicable)

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
