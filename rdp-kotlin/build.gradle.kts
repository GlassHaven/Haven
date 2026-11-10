plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Keep version in sync with [versions.jna] in gradle/libs.versions.toml
    compileOnly("net.java.dev.jna:jna:5.14.0")

    testImplementation("junit:junit:4.13.2")
}

// Generated Kotlin sources live under kotlin/ (see tools/build-android.sh)
sourceSets {
    main {
        kotlin.srcDir("kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build IronRDP native library from Rust source via cargo-ndk.
// Prerequisites: rustup, cargo-ndk, and the three Android targets below.
//
// The .so files under jniLibs/ are NOT committed — 4d096470 removed them and
// .gitignore keeps them out. Every shipped ABI is rebuilt from source by this
// task, which is what makes that safe: armeabi-v7a was once missing from the
// target list, so a Rust change rebuilt arm64 and x86_64 and left armv7 users
// on whatever binary happened to be checked in — the silent-staleness failure
// #469 was, in a different module. Adding an ABI to the APK means adding it
// here in the same change.
val buildRdpNative by tasks.registering(Exec::class) {
    val rustDir = file("rust")
    val jniDir = file("jniLibs")

    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
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

    workingDir = rustDir

    // Detect NDK from ANDROID_NDK_HOME or ANDROID_SDK_ROOT
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome)
    }

    commandLine(
        listOf("cargo", "ndk", "-o", jniDir.absolutePath) +
            abis.flatMap { listOf("-t", it) } +
            listOf("build", "--release"),
    )
}

// No publishing block needed — consumed via includeBuild() in settings.gradle.kts
