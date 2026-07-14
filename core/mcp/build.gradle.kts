plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.mcp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    // Real org.json for unit tests (android.jar's is stubbed under
    // the default unit-test configuration).
    testImplementation("org.json:json:20260522")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
