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
// Prerequisites: rustup, cargo-ndk, aarch64-linux-android + x86_64-linux-android targets.
// The .so files are NOT committed — they're built as part of the Gradle build.
val buildRdpNative by tasks.registering(Exec::class) {
    val rustDir = file("rust")
    val jniDir = file("jniLibs")

    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    outputs.dir(jniDir)

    workingDir = rustDir
    commandLine("cargo", "ndk",
        "-o", jniDir.absolutePath,
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "build", "--release")
}

// No publishing block needed — consumed via includeBuild() in settings.gradle.kts
