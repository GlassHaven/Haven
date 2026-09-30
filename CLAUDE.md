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

## Submodule Checkout Discipline
The `termlib` submodule working tree drifts whenever a session checks out a PR or upstream branch (device tests, upstreaming). This has caused two silent build breakages: left on `resize-grow-cursor` (09-22), its AGP failed the parent's dependency verification so every gradle invocation died at configuration; left on `main` while parent sources referenced PR-only APIs (09-24), compile failed with `No parameter with name ... found` and the failure was masked by piped output.
- **Before any gradle invocation:** `git submodule status`. A leading `+` or `U` means the checkout does not match the parent gitlink — resolve before building.
- **When parent sources carry deliberate temporary edits** (e.g. device-test flag builds): confirm the submodule checkout is the commit those edits expect (`git -C termlib log -1`), and note both in `scratch/maintain-state.md`.
- **Before ending any session** that checked out a non-gitlink commit or left the submodule dirty: restore with `git submodule update <path>` (and `git -C <sub> checkout -- .` if dirty), rebuild once, and record the restore.
- **Never pipe build output to `tail`/`grep` alone** — the pipe's exit code masks gradle's failure. Write to a file, echo `GRADLE_EXIT=$?`, then grep for `BUILD (SUCCESSFUL|FAILED)`.

## Gradle & Compiler Invariants
- **Kotlin Classes:** Unit test Kotlin classes compile into `<module>/build/intermediates/built_in_kotlinc/debugUnitTest/` and `<module>/build/tmp/kotlin-classes/debugUnitTest/` (not javac).
- **Test Results:** XML test output is located at `<module>/build/test-results/testDebugUnitTest/` and HTML reports at `<module>/build/reports/tests/testDebugUnitTest/index.html`.
- **Task Caching:** AGP skips unchanged tasks (UP-TO-DATE). If testing newly modified code, use `--rerun-tasks --info`. Do not diagnose build script configurations unless a compilation error is explicitly thrown.
