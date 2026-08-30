# Haven Development Guide

Haven is an Android application with a multi-module Gradle architecture using Kotlin, Jetpack Compose, Coroutines, and Hilt.

## Build & Test Commands

### Unit Tests
- **Run all unit tests:**
  ```bash
  ./gradlew testDebugUnitTest :app:testArm64FullDebugUnitTest -PexcludeSshlibContractTests=true -PskipWaylandNatives=true -PskipFfmpegNatives=true
  ```
- **Run specific module tests (force fresh rerun):**
  ```bash
  ./gradlew :<module>:testDebugUnitTest --rerun-tasks -PskipWaylandNatives=true -PskipFfmpegNatives=true
  ```
- **Run a specific test class:**
  ```bash
  ./gradlew :<module>:testDebugUnitTest --tests "sh.haven.<module>.<TestClass>" --rerun-tasks -PskipWaylandNatives=true -PskipFfmpegNatives=true
  ```

### Build & Verification
- **Assemble Debug APK:**
  ```bash
  ./gradlew :app:assembleArm64FullDebug -PtargetAbi=arm64 -PskipWaylandNatives=true -PskipFfmpegNatives=true
  ```
- **Fast Source Checks:**
  ```bash
  ./scripts/check-changelog.sh
  ./scripts/check-i18n-hardcoded.sh
  python3 scripts/check-i18n-coverage.py
  ```

## Gradle & Compiler Invariants
- **Kotlin Classes:** Unit test Kotlin classes compile into `<module>/build/intermediates/built_in_kotlinc/debugUnitTest/` and `<module>/build/tmp/kotlin-classes/debugUnitTest/` (not javac).
- **Test Results:** XML test output is located at `<module>/build/test-results/testDebugUnitTest/` and HTML reports at `<module>/build/reports/tests/testDebugUnitTest/index.html`.
- **Task Caching:** AGP skips unchanged tasks (UP-TO-DATE). If testing newly modified code, use `--rerun-tasks --info`. Do not diagnose build script configurations unless a compilation error is explicitly thrown.
