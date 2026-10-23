plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Room migration tests read the committed schema JSONs from
        // test assets via MigrationTestHelper under Robolectric.
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Exported Room schemas double as test assets for MigrationTestHelper.
        getByName("test").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core:redact"))
    implementation(project(":core:security"))
    // The Keystore aggregator surfaces FIDO SK credentials alongside
    // regular SSH keys, so the SshKeySection adapter needs SkKeyData
    // to detect and parse SK blobs stored in the ssh_keys table.
    implementation(project(":core:fido"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    implementation(project(":core:security"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    // MigrationTestHelper + InstrumentationRegistry/ApplicationProvider
    // for the Room migration suite.
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    // Real org.json for unit tests (android.jar's is stubbed under
    // isReturnDefaultValues), so MailRuleJson round-trips can be asserted.
    testImplementation("org.json:json:20260814")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
