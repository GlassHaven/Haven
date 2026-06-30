plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.mail"
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
    // :core:rclone owns the gomobile binding (rclone-transport, which carries
    // the Proton MailBridge wrapper) and the libgojni.so jniLibs — depending on
    // it gives us MailBridge at compile time and the native lib in the APK.
    implementation(project(":core:rclone"))
    implementation(project(":core:data"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // JVM IMAP/SMTP engine (Stage 2a). The android-mail build of JavaMail is
    // purpose-built for Android: zero java.beans/java.awt refs (verified), the
    // javax.mail namespace, and the IMAP/SMTP providers. CDDL-1.1 / GPL-2.0
    // with classpath-exception — the exception is what permits linking from an
    // AGPL app. ImapMailClient registers providers explicitly via Properties
    // (mail.imap.class/…) because Android strips META-INF/javamail.* files.
    implementation("com.sun.mail:android-mail:1.6.8")
    implementation("com.sun.mail:android-activation:1.6.8")

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
