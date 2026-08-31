plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.ssh"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The SSH Authentication API is a bound AIDL service (#487). The
    // interface is vendored rather than pulled from JitPack, but the binder
    // still resolves it by its original name, so it has to be compiled here.
    buildFeatures {
        aidl = true
    }
}

// CI runs the sshlib contract tests in their own non-gating step, because the
// opt-in preview engine inherits an upstream race in sshlib 0.4.1 that drops a
// command's output at random (connectbot/cbssh#245, tracked in #448). Passing
// -PexcludeSshlibContractTests=true takes them out of the gating run; a plain
// local `test` still runs everything, so the exclusion cannot hide anything
// from someone working on this module.
if (providers.gradleProperty("excludeSshlibContractTests").orNull == "true") {
    tasks.withType<Test>().configureEach {
        exclude("sh/haven/core/ssh/sshlib/**")
    }
}

dependencies {
    implementation(project(":core:redact"))
    api(libs.jsch)
    // Second SSH engine (#58). implementation, NOT api — no module outside
    // core:ssh may see sshlib types, same rule as ChannelSftp before it.
    implementation(libs.sshlib) {
        // sshlib ships JVM tink; Haven already carries tink-android (same
        // classes, same package) — keeping both is a duplicate-class error.
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    // JSch optional deps — compileOnly so R8 doesn't error on missing classes
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    compileOnly("net.java.dev.jna:jna:5.14.0")
    implementation(libs.core.ktx)
    implementation(project(":core:data"))
    implementation(project(":core:security"))
    implementation(project(":core:fido"))
    implementation(libs.bouncycastle)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.process)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    // Real org.json for unit tests (android.jar's is stubbed under
    // unitTests.isReturnDefaultValues) — same recipe as core:mcp.
    testImplementation("org.json:json:20260814")
    // Embedded SSH server used to reproduce the v4.51.0 TOFU bypass bug (#75 follow-up)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testRuntimeOnly(libs.slf4j.simple)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
