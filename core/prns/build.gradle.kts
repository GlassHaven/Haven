plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.prns"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Forward the host-capsule path into the forked test JVM so the live
        // engine test can run: ./gradlew :core:prns:testDebugUnitTest
        //   -Dpersonal.rns.library=/abs/path/libprns_host.so
        unitTests.all { test ->
            System.getProperty("personal.rns.library")?.let {
                test.systemProperty("personal.rns.library", it)
            }
        }
    }

    // libprns_host.so is built from the prns submodule's Rust source by
    // buildPrnsNative below — never committed (F-Droid builds it from source).
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// Cross-build the Prns C-ABI capsule for every shipped ABI. The crate at
// prns/prns-host/abi/c is its own detached cargo workspace with a committed
// Cargo.lock, so this builds exactly the pinned graph. The task lives HERE
// rather than in the included JVM build so the prns submodule stays pristine
// upstream trunk (same division as build-proot: Haven owns the build wiring,
// the submodule owns the source).
val buildPrnsNative by tasks.registering(Exec::class) {
    val crateDir = File(rootProject.projectDir, "prns/prns-host/abi/c")
    val jniDir = File(projectDir, "src/main/jniLibs")

    inputs.dir(File(crateDir, "src"))
    inputs.file(File(crateDir, "Cargo.toml"))
    inputs.file(File(crateDir, "Cargo.lock"))
    // The capsule's engine sources live outside the detached workspace.
    inputs.dir(File(rootProject.projectDir, "prns/prns-host/core/src"))
    inputs.dir(File(rootProject.projectDir, "prns/prns-host/impls/native/src"))
    outputs.dir(jniDir)

    // -PtargetAbi=arm64|x64|armv7 (release.yml's per-ABI matrix) builds only that
    // ABI, as core/wayland and core/local already do; without it every ABI
    // builds. targetAbi is a task input, so switching it re-runs the task.
    val targetAbi = providers.gradleProperty("targetAbi").orNull
    val abis = when (targetAbi) {
        null -> listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        "arm64" -> listOf("arm64-v8a")
        "x64" -> listOf("x86_64")
        "armv7" -> listOf("armeabi-v7a")
        else -> throw GradleException("Unknown targetAbi '$targetAbi' (expected arm64, x64 or armv7)")
    }
    inputs.property("abis", abis)

    workingDir = crateDir

    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: (System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME"))?.let { sdk ->
            File(sdk, "ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) environment("ANDROID_NDK_HOME", ndkHome)

    // prns/rust-toolchain.toml pins floating `stable` (with only an embedded
    // riscv target), which on CI resolves to a toolchain without the Android
    // std libs → E0463 "can't find crate for core". Override with the repo's
    // pinned Rust (the one setup-toolchain pre-installs Android targets for;
    // keep in sync with .github/actions/setup-toolchain and the F-Droid
    // recipe's rustup line). Also upgrades a ref-name "pin" to a real one.
    environment("RUSTUP_TOOLCHAIN", "1.94.1")

    // Adding an ABI to the APK means adding it here in the same change —
    // a missing target ships a silently absent (or stale) library.
    commandLine(
        listOf("cargo", "ndk", "-o", jniDir.absolutePath) +
            abis.flatMap { listOf("-t", it) } +
            listOf("build", "--release"),
    )
}

tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(buildPrnsNative)
    }
}

dependencies {
    // Upstream's JVM SDK, substituted from the included build inside the
    // prns submodule (settings.gradle.kts). Its declared JNA is the desktop
    // JAR; Android needs the AAR (same version, ships jnidispatch per ABI),
    // so the JAR is excluded here rather than patching the submodule.
    api("rs.reticulum:personal-rns:0.3.6") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // jar variant for host-JVM tests (raw libc dial in PrnsEngineTest).
    testImplementation(libs.jna)
}
