import java.io.File

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
        applicationId = "com.hension.haven"
        minSdk = 26
        targetSdk = 35
        versionCode = 74
        versionName = "2.5.0"

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
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
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

    // Version code scheme: base * 10 + offset
    val variantCodes = mapOf("arm64" to 1, "x64" to 2)

    applicationVariants.all {
        val variant = this
        val code = variantCodes[variant.flavorName]
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            if (code != null) {
                output.versionCodeOverride = (defaultConfig.versionCode ?: 0) * 10 + code
            }
            // e.g., haven-2.0.17-arm64-release.apk
            val abi = variant.productFlavors.first { it.dimension == "abi" }.name
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
    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:smb"))

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

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        val buildPythonCandidates = listOf(
            System.getenv("CHAQUOPY_BUILD_PYTHON"),
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Python\\Python312\\python.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Python\\Python313\\python.exe" },
            "C:\\Python312\\python.exe",
            "C:\\Python313\\python.exe",
        ).filterNotNull()
        val buildPythonPath = buildPythonCandidates.firstOrNull { File(it).exists() }
        if (buildPythonPath != null) {
            buildPython(buildPythonPath)
        }

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
