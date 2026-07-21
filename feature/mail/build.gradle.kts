plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.feature.mail"
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
    implementation(project(":core:ui"))
    implementation(project(":core:mail"))
    implementation(project(":core:data"))

    // RFC822/MIME parsing of the decrypted message blob the bridge returns.
    // Apache-2.0, F-Droid-friendly, no javax.activation pull.
    implementation("org.apache.james:apache-mime4j-core:0.8.14")
    implementation("org.apache.james:apache-mime4j-dom:0.8.14")

    implementation(libs.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Real org.json for unit tests (MailRuleJson round-trips); the android.jar stub
    // returns null from put(). Same artifact app/core:data already verify.
    testImplementation("org.json:json:20260719")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
