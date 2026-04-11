plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.ffmpeg"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

// Copy pre-built FFmpeg binaries into jniLibs so Android extracts them
// to nativeLibraryDir. Same pattern as core/local's buildProot task.
val copyFfmpegBinaries by tasks.registering(Copy::class) {
    val buildDir = rootProject.file("build-ffmpeg/build-arm64-v8a/install/bin")
    val jniLibsDir = file("src/main/jniLibs/arm64-v8a")

    from(buildDir) {
        include("libffmpeg.so", "libffprobe.so", "libc++_shared.so")
    }
    into(jniLibsDir)

    // Only run when source files change
    inputs.dir(buildDir)
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") {
    dependsOn(copyFfmpegBinaries)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
