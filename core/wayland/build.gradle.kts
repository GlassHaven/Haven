plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sh.haven.core.wayland"
    compileSdk = 37

    defaultConfig {
        minSdk = 26 // Runtime API check guards features needing 28+
    }

    // Same r29 pin as core/local and termlib/lib, and the same one F-Droid's
    // recipe requests. It is load-bearing here rather than incidental: wlroots
    // requires EGL extension headers newer than NDK 27 ships, and fails
    // configure with "EGL headers too old" against them.
    ndkVersion = "29.0.14206865"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            // Native binaries built from source by buildWaylandNatives below.
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// Build the five wayland native binaries from the wayland-android submodule,
// instead of shipping hand-compiled copies checked into the tree (#493).
//
// Why this exists: until now nothing rebuilt these. They were compiled by hand
// and committed, which produced #469 (a liblabwc_android.so three versions
// stale, left 51 symbols undefined, and every cage/app-window feature died at
// dlopen with nothing failing until a user hit it) and the F-Droid gap (their
// recipe scandeletes the directory and only rebuilt one of the five, so their
// APKs shipped without an XWayland wrapper or GPU renderer at all).
//
// arm64-v8a only: that is the entire committed set, so x64/armv7 APKs have
// never carried these. The matrix jobs for those ABIs skip via -PtargetAbi
// rather than spending ~20 minutes building binaries they do not package.
val waylandAbi = "arm64-v8a"
val targetAbi = providers.gradleProperty("targetAbi").orNull
val waylandScriptDir = rootProject.file("wayland-android")
val waylandOut = file("src/main/jniLibs/$waylandAbi")

// Resolve the pinned NDK explicitly rather than letting the scripts pick.
// Checked at execution time, not here, so a machine without it can still
// configure the build and run everything that doesn't need the NDK.
val pinnedNdk = "29.0.14206865"
val ndkDir: String = run {
    val sdk = System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
        ?: File(System.getProperty("user.home"), "Android/Sdk").absolutePath
    File(sdk, "ndk/$pinnedNdk").absolutePath
}

val buildWaylandNatives by tasks.registering {
    val labwc = File(waylandScriptDir, "build_liblabwc_android.sh")
    val helpers = File(waylandScriptDir, "build-native-helpers.sh")
    val virgl = File(waylandScriptDir, "build-virgl-android.sh")

    inputs.files(labwc, helpers, virgl).withPropertyName("buildScripts")
    // The submodule's HEAD is the real input — the sources are tens of
    // thousands of files across wlroots/labwc/virglrenderer, and hashing them
    // all would cost more than the build. Keying on the commit is what makes a
    // submodule bump reliably invalidate the binaries, which is precisely the
    // staleness #469 was.
    inputs.property(
        "waylandAndroidSha",
        providers.exec {
            commandLine("git", "-C", waylandScriptDir.absolutePath, "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.orElse("no-submodule"),
    )
    outputs.dir(waylandOut)

    // Skip when the submodule isn't checked out (the CI test job initialises an
    // explicit subset and leaves wayland-android out, because its freedesktop
    // chain intermittently 5xxs), and when this build is for an ABI these
    // binaries are not shipped for.
    onlyIf {
        // Unit-test jobs check out every submodule recursively but never
        // package an APK, so building these there is ~20 minutes spent on
        // binaries nothing consumes. They opt out explicitly rather than the
        // task guessing from task names.
        val skipped = providers.gradleProperty("skipWaylandNatives").orNull == "true"
        val abiWanted = when (targetAbi) {
            null -> true            // unqualified build: produce them
            "arm64" -> true
            else -> false
        }
        val present = labwc.exists() && helpers.exists() && virgl.exists()
        if (skipped) logger.lifecycle("[wayland] -PskipWaylandNatives — not building")
        if (!present) {
            logger.lifecycle("[wayland] submodule not checked out — skipping native build")
        }
        !skipped && abiWanted && present
    }

    doLast {
        require(File(ndkDir).isDirectory) {
            "NDK $pinnedNdk not found at $ndkDir — wlroots needs its EGL headers " +
                "(NDK 27's are too old); install with: sdkmanager \"ndk;$pinnedNdk\""
        }
        val built = File(waylandScriptDir, "jniLibs/$waylandAbi")
        // Pump the script's output through Gradle's logger rather than
        // inheritIO(). inheritIO inherits the *daemon's* stdio, which never
        // reaches the build console — on CI that turned a real failure into a
        // bare "failed with exit code 1" with the cause nowhere in the log, and
        // cost a round trip to discover the runner was missing meson/ninja.
        listOf(labwc, helpers, virgl).forEach { script ->
            val proc = ProcessBuilder("bash", script.absolutePath)
                .directory(waylandScriptDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["ABI"] = waylandAbi
                    // Pin the NDK explicitly. The scripts prefer ANDROID_NDK_HOME
                    // over the newest installed, and GitHub's runner image presets
                    // it to its own bundled NDK 27 — whose EGL extension headers
                    // (EGL_EGLEXT_VERSION 20181204) are too old for wlroots, which
                    // then fails configure with "Dependency is required but has no
                    // candidates". Local builds happened to have r29 first on the
                    // path, so this only ever failed on CI.
                    environment()["ANDROID_NDK_HOME"] = ndkDir
                }
                .start()
            val tail = ArrayDeque<String>()
            proc.inputStream.bufferedReader().forEachLine { line ->
                logger.info(line)
                tail.addLast(line)
                if (tail.size > 40) tail.removeFirst()
            }
            val code = proc.waitFor()
            if (code != 0) {
                // At default log level `info` is hidden, so surface enough of
                // the end to diagnose without needing --info and a re-run.
                logger.error("--- ${script.name} last ${tail.size} lines ---")
                tail.forEach { logger.error(it) }
            }
            require(code == 0) { "${script.name} failed with exit code $code" }
        }
        copy {
            from(built) { include("*.so") }
            into(waylandOut)
        }
        // Assert rather than assume. A silently-empty jniLibs dir is exactly how
        // the F-Droid APK shipped without a desktop for months.
        val expected = listOf(
            "liblabwc_android.so",
            "libxwayland_wrapper.so",
            "libbenchmark_gles.so",
            "libvirgl_test_server.so",
            "libvirgl_render_server.so",
        )
        val missing = expected.filterNot { File(waylandOut, it).isFile }
        require(missing.isEmpty()) {
            "wayland native build produced no $missing in $waylandOut"
        }
        logger.lifecycle("[wayland] built ${expected.size} native binaries into $waylandOut")
    }
}

tasks.named("preBuild") { dependsOn(buildWaylandNatives) }

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:toolbar"))
    implementation(project(":core:data"))

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.core)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
