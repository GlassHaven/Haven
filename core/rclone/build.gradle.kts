plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.rclone"
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
    }

    // Include native libraries built from Go source by rclone-android:buildRcloneNative
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${rootProject.projectDir}/rclone-android/jniLibs")
        }
    }
}

// Ensure Go native library is built before this module compiles
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(gradle.includedBuild("rclone-android").task(":buildRcloneNative"))
    }
}

dependencies {
    api("sh.haven:rclone-transport:0.1.0")
    implementation(project(":core:data"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20260719")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
