plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.tunnel"
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
}

dependencies {
    // JSch Proxy interface is the integration seam — tunnels expose themselves
    // to SshClient by implementing it. JSch stays an `implementation` dep so it
    // doesn't leak through `core/tunnel`'s public surface; consumers see only
    // `sh.haven.core.ssh.HavenProxy`.
    implementation(libs.jsch)
    implementation(project(":core:ssh"))
    implementation(project(":core:data"))
    // The gomobile wgbridge package (wireguard-go + gVisor netstack) lives
    // inside the rclone-transport jar — they share a single libgojni.so to
    // avoid runtime-class collisions. See rclone-android/go/wgbridge/.
    implementation("sh.haven:rclone-transport:0.1.0")
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    // OkHttp WebSocket client for the Cloudflare Access SSH gateway (#154).
    implementation(libs.okhttp)
    implementation(project(":core:security"))
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Android's android.jar stubs out org.json.JSONObject in unit tests
    // (returns defaults), so add the real JSON lib for tests that
    // exercise TailscaleConfigBlob's JSON envelope. Same package, same
    // class — JVM resolves to the real impl when both are on the path.
    testImplementation("org.json:json:20260814")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build OpenVPN3 Core (client/ovpncli.cpp + Haven's own adapter.cpp,
// mbedtls/lz4 merged in) for all 4 Android ABIs into jniLibs-openvpn3/.
// See openvpn3-core/CMakeLists.txt and openvpn3-core/build-android.sh for
// the why (no maintained upstream Android build path exists) and how.
// NDK is auto-detected by build-android.sh itself (same as buildProot's
// build.sh) -- deliberately not hardcoded via Gradle environment() here,
// since that would override the script's own portable fallback search on
// any machine where the SDK/NDK isn't at this exact path. Needs network
// access to clone 4 dependency repos (mbedtls/lz4/asio/fmt) the first
// time it runs; -PskipOpenvpn3Native mirrors fetchQemuLoaders/fetchUml's
// existing offline/F-Droid-buildserver escape hatch in core/local.
val buildOpenvpn3Native by tasks.registering(Exec::class) {
    val script = rootProject.file("openvpn3-core/build-android.sh")
    val src = rootProject.file("openvpn3-core/openvpn3/client/ovpncli.cpp")
    val outDir = file("jniLibs-openvpn3")

    inputs.file(script)
    inputs.dir(rootProject.file("openvpn3-core/openvpn3"))
    inputs.file(rootProject.file("openvpn3-core/adapter.cpp"))
    inputs.file(rootProject.file("openvpn3-core/CMakeLists.txt"))
    outputs.dir(outDir)
    onlyIf { script.exists() && src.exists() && !project.hasProperty("skipOpenvpn3Native") }

    workingDir = rootProject.file("openvpn3-core")
    commandLine("bash", "build-android.sh")
    environment("OPENVPN3_OUTPUT", outDir.absolutePath)
}

tasks.named("preBuild") {
    dependsOn(buildOpenvpn3Native)
}
