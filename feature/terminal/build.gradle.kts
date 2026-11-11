plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.feature.terminal"
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


    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:redact"))
    implementation(project(":core:ui"))
    implementation(project(":core:terminal-haven"))
    implementation(project(":core:toolbar"))
    implementation(project(":core:data"))
    implementation(project(":core:ssh"))
    // Security-key (FIDO2/SK) authenticator, wired onto new-tab SshClients.
    implementation(project(":core:fido"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:reticulum"))
    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:btserial"))
    implementation(project(":core:bleserial"))
    implementation(project(":core:usbserial"))
    implementation(project(":core:usb"))
    implementation(project(":core:local"))
    implementation(project(":core:security"))
    // QR / OCR for the paperclip → camera/photo attach flow.
    implementation(project(":core:scan"))
    // The terminal's paperclip / attach feature reuses the SFTP feature's
    // upload pipeline (TerminalAttachCoordinator) — depending on the SFTP
    // module is therefore intentional, not a layering accident.
    implementation(project(":feature:sftp"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.termlib)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
