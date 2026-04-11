plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "sh.haven.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "sh.haven.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 209
        versionName = "4.56.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += listOf("abi")
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("haven-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo.include = false
        }
    }

    // Version code scheme: base * 10 + abiOffset
    val abiCodes = mapOf("arm64" to 1, "x64" to 2)

    applicationVariants.all {
        val variant = this
        val abi = variant.productFlavors.first { it.dimension == "abi" }.name
        val abiCode = abiCodes[abi]
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (abiCode != null) {
                output.versionCodeOverride = (defaultConfig.versionCode ?: 0) * 10 + abiCode
            }
            output.outputFileName = "haven-${variant.versionName}-$abi-${variant.buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}


dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:ssh"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(project(":core:reticulum"))

    // Native Kotlin Reticulum transport (rnsh-kt + reticulum-kt)
    implementation("tech.torlando:rnsh-core:0.1.0-SNAPSHOT")
    implementation("network.reticulum:rns-core:0.1.0-SNAPSHOT")
    implementation("network.reticulum:rns-interfaces:0.1.0-SNAPSHOT")
    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:smb"))
    implementation(project(":core:rclone"))
    implementation(project(":core:ffmpeg"))
    implementation(project(":core:fido"))
    implementation(project(":core:local"))
    implementation(project(":core:wayland"))

    implementation(project(":feature:connections"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sftp"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:vnc"))
    implementation(project(":feature:rdp"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.biometric)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

chaquopy {
    defaultConfig {
        version = "3.13"

        pip {
            install("rns")
            install("rnsh")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
