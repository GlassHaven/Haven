plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.local"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:wayland"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

val buildProot by tasks.registering(Exec::class) {
    val prootScript = rootProject.file("build-proot/build.sh")
    val prootSrc = rootProject.file("build-proot/proot-termux/src")
    val tallocSrc = rootProject.file("build-proot/talloc")
    val jniLibsDir = file("src/main/jniLibs")

    inputs.file(prootScript)
    inputs.dir(prootSrc)
    inputs.dir(tallocSrc)
    outputs.dir(jniLibsDir)

    workingDir = rootProject.file("build-proot")
    commandLine("bash", "build.sh")
    // Let build.sh auto-detect the newest NDK (needs r28+ for the ARM64
    // TLS alignment fix; we ship against r29 stable in CI).
    environment("PROOT_OUTPUT", jniLibsDir.absolutePath)
}

tasks.named("preBuild") {
    dependsOn(buildProot)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
