plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.rdp"
    compileSdk = 36

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

    // Include native libraries built from Rust source by rdp-kotlin:buildRdpNative
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${rootProject.projectDir}/rdp-kotlin/jniLibs")
        }
    }
}

// Ensure Rust native library is built before this module compiles
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(gradle.includedBuild("rdp-kotlin").task(":buildRdpNative"))
    }
}

dependencies {
    api("sh.haven:rdp-transport:0.1.0")
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
