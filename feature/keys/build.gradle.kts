plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.feature.keys"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }

    testOptions {
        // ViewModel tests exercise paths that call android.util.Log; the
        // unit-test android.jar stubs it out and would throw "not mocked"
        // (same recipe as feature/connections, core/security, …).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:redact"))
    implementation(project(":core:ui"))
    implementation(project(":core:security"))
    implementation(project(":core:ssh"))
    implementation(project(":core:data"))
    implementation(project(":core:fido"))
    implementation(project(":core:stepca"))
    implementation(project(":core:scan"))
    implementation(libs.coroutines.core)

    implementation(libs.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
