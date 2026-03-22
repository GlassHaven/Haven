# Release Process

## 1. Bump version

Edit `app/build.gradle.kts`:
```kotlin
versionCode = <increment>
versionName = "<x.y.z>"
```

## 2. Commit, tag, push

```bash
git add app/build.gradle.kts <changed files>
git commit -m "Bump to v<x.y.z>"
git tag v<x.y.z>
git push origin main v<x.y.z>
```

The `v*` tag triggers the **Release** workflow on GitHub Actions which:
- Builds a signed APK and AAB (keystore password from GitHub secrets)
- Creates a GitHub release with the APK attached

## 3. Download Play Store bundle

```bash
mkdir -p releases/v<x.y.z>
gh release download v<x.y.z> --repo GlassOnTin/Haven --pattern '*.aab' -D releases/v<x.y.z>/
```

Upload the AAB to Google Play Console.

## 4. F-Droid

F-Droid auto-detects new tags via `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`.
No manual update needed — the initial inclusion MR (`fdroid/fdroiddata!33920`) is merged.

## 5. Verify

- [ ] GitHub release page has APK
- [ ] CI workflow passes (lint + tests)
- [ ] Play Store bundle uploaded (if applicable)

## Signing

The release keystore `haven-release.jks` is in the repo root.
Passwords are stored in GitHub secrets:
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

- F-Droid builds from source using the tagged commit
- `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` means F-Droid auto-detects new tags
- Initial inclusion MR: `fdroid/fdroiddata!33920` (merged)
