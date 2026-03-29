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
    // gomobile-generated Java bindings (produced by build-android.sh)
    implementation(files("build/rcbridge-bindings.jar"))
    testImplementation("junit:junit:4.13.2")
}

// Kotlin wrapper sources generated alongside the Go → gomobile build
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

// Build rclone native library from Go source via gomobile.
// Prerequisites: Go 1.26+, gomobile, gobind, Android NDK.
// The .so files are NOT committed — they're built as part of the Gradle build.
val buildRcloneNative by tasks.registering(Exec::class) {
    val goDir = file("go")
    val jniDir = file("jniLibs")
    val toolsDir = file("tools")

    inputs.dir(goDir)
    inputs.file(toolsDir.resolve("build-android.sh"))
    outputs.dir(jniDir)

    // Skip if native libs already exist (avoids expensive Go cross-compile on every build)
    onlyIf {
        !jniDir.resolve("arm64-v8a/libgojni.so").exists() ||
            !jniDir.resolve("x86_64/libgojni.so").exists()
    }

    workingDir = projectDir
    commandLine("bash", toolsDir.resolve("build-android.sh").absolutePath)

    // Ensure Go toolchain is on PATH for the build script
    val goRoot = "/usr/local/go"
    val goPath = System.getenv("GOPATH") ?: "${System.getProperty("user.home")}/go"
    environment("PATH", "$goRoot/bin:$goPath/bin:${System.getenv("PATH")}")

    // Pass SDK/NDK paths through environment (no hardcoded fallbacks)
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_HOME")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome)
    }
    val sdkRoot = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    if (sdkRoot != null) {
        environment("ANDROID_HOME", sdkRoot)
    }
}
