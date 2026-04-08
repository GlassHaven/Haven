// Throwaway Phase 0 spike: standalone Android app that bundles libffmpeg.so
// and libffprobe.so in jniLibs, then exec's them from applicationInfo.nativeLibraryDir
// to verify the W^X workaround end-to-end.
//
// This module is intended to be deleted (or replaced by core/ffmpeg) once
// Phase 0 is signed off.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "sh.haven.ffmpeg.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "sh.haven.ffmpeg.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Same as Haven's app module — required so Android extracts
            // lib*.so files to nativeLibraryDir where exec is allowed.
            useLegacyPackaging = true
        }
    }

    // Only build for arm64-v8a in the spike (that's all we cross-compiled).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
