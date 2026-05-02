plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.tunnel"
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
}

dependencies {
    // JSch Proxy interface is the integration seam — tunnels expose themselves
    // to SshClient by implementing it.
    api(libs.jsch)
    implementation(project(":core:data"))
    // The gomobile wgbridge package (wireguard-go + gVisor netstack) lives
    // inside the rclone-transport jar — they share a single libgojni.so to
    // avoid runtime-class collisions. See rclone-android/go/wgbridge/.
    implementation("sh.haven:rclone-transport:0.1.0")
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    // Android's android.jar stubs out org.json.JSONObject in unit tests
    // (returns defaults), so add the real JSON lib for tests that
    // exercise TailscaleConfigBlob's JSON envelope. Same package, same
    // class — JVM resolves to the real impl when both are on the path.
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
