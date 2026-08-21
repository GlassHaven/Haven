plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "sh.haven.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 805
        versionName = "5.87.42"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += listOf("abi", "features")
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
        create("armv7") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }

        // #510: an 80 MB download every few days is a real cost for someone
        // who only uses the terminal. `full` is what has always shipped.
        create("full") {
            dimension = "features"
            buildConfigField("boolean", "DESKTOP", "true")
        }
        create("terminal") {
            dimension = "features"
            buildConfigField("boolean", "DESKTOP", "false")
            // The native payload it drops is excluded per-variant below —
            // ProductFlavor has no `packaging` block, and putting one here
            // silently resolves against the `android` extension instead,
            // stripping the libraries from EVERY variant. Measured: it made
            // the full flavour 55 MB too.
        }
    }

    // The terminal flavour's Go library lives outside the module, next to the
    // full one that :core:rclone contributes. Both are build outputs and both
    // are gitignored; running rclone-android/tools/build-android.sh produces
    // the pair. A terminal build made without it would package :core:rclone's
    // full library instead — 20 MB larger, and correct, just not smaller.
    sourceSets {
        getByName("terminal") {
            jniLibs.srcDir("${rootProject.projectDir}/rclone-android/jniLibs-terminal")
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
        debug {
            // Sign debug with the release cert when the keystore env is present
            // (source ~/.haven-release.env), so a debuggable build installs over
            // a release-signed device build without a data-wiping uninstall.
            // Falls back to the default debug keystore when the env is absent.
            if (System.getenv("KEYSTORE_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
            // Apache Mime4j (email MIME parsing) ships these in both its core and
            // dom jars; drop the duplicates so the APK merge doesn't conflict.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
        jniLibs {
            useLegacyPackaging = true
            // #510: the terminal flavour ships a libgojni.so built without
            // rclone (see rclone-android/tools/build-android.sh) — ~8 MB
            // against ~28 MB, keeping tailscale, WireGuard and Proton mail.
            //
            // gomobile always names its output libgojni.so, so the two cannot
            // be told apart by name and both reach the merge: the app's own
            // flavour source set, and :core:rclone's copy of the full one.
            // pickFirsts resolves that in the app's favour. An exclude cannot
            // do this job — the pattern would match both and leave the flavour
            // with no Go library at all.
            //
            // The full flavour has no app-level copy, so it takes
            // :core:rclone's unchanged.
            pickFirsts += "**/libgojni.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Version code scheme: base * 10 + abiOffset; APK named
// haven-<version>-<abi>-<buildtype>.apk, with "-terminal" inserted for the
// stripped flavour. Rewritten from the removed applicationVariants API for
// AGP 9's Variant API; outputFileName still needs the impl cast — there is
// no public rename hook yet.
//
// ★ The terminal flavour deliberately carries the SAME versionCode as full:
// it is the same release of the same app, and changing the code would make
// one variant look like an update to the other. The consequence is that an
// updater comparing version codes will not offer a switch between them —
// switching is a deliberate sideload. Revisit if the variant ever gets its
// own F-Droid entry, which would want a distinct applicationId instead.
androidComponents {
    // #510: the terminal flavour ships the same code without the desktop
    // native payload. Excluded here rather than on the flavour because
    // ProductFlavor has no packaging block.
    //
    // The modules stay on the compile classpath and are simply inert, which
    // works because each entry point already degrades when its library is
    // absent — and did so before this flavour existed:
    //   WaylandBridge catches UnsatisfiedLinkError and reports available =
    //     false; every call site checks it before any native call.
    //   FfmpegExecutor.isAvailable() tests canExecute() on both binaries,
    //     and the SFTP UI reads it as ffmpegAvailable.
    // So this rides existing contracts rather than inventing failure modes.
    // BuildConfig.DESKTOP hides the entry points so nothing offers a feature
    // that cannot run.
    onVariants(selector().withFlavor("features" to "terminal")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            // Remote desktop transports
            "**/librdp_transport.so",
            "**/libspice_transport.so",
            // Native Wayland compositor + GPU renderer
            "**/liblabwc_android.so",
            "**/libvirgl_render_server.so",
            "**/libvirgl_test_server.so",
            "**/libxwayland_wrapper.so",
            "**/libbenchmark_gles.so",
            // Media conversion, preview and streaming. libc++_shared.so is
            // NOT here — tesseract and others link against it too.
            "**/libffmpeg.so",
            "**/libffprobe.so",
            "**/libavcodec.so",
            "**/libavdevice.so",
            "**/libavfilter.so",
            "**/libavformat.so",
            "**/libavutil.so",
            "**/libswresample.so",
            "**/libswscale.so",
        )
    }

    onVariants { variant ->
        val abiCodes = mapOf("arm64" to 1, "x64" to 2, "armv7" to 3)
        val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second
        val features = variant.productFlavors.firstOrNull { it.first == "features" }?.second
        val abiCode = abiCodes[abi]
        val base = android.defaultConfig.versionCode ?: 0
        val versionName = android.defaultConfig.versionName
        val suffix = if (features == "terminal") "-terminal" else ""
        variant.outputs.forEach { output ->
            if (abiCode != null) {
                output.versionCode.set(base * 10 + abiCode)
            }
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("haven-$versionName-$abi$suffix-${variant.buildType}.apk")
        }
    }
}

dependencies {
    implementation(project(":core:redact"))
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
    implementation(project(":core:btserial"))
    implementation(project(":core:bleserial"))
    implementation(project(":core:usbserial"))
    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:prns"))
    implementation(project(":core:spice"))
    implementation(project(":core:smb"))
    implementation(project(":core:rclone"))
    implementation(project(":core:ffmpeg"))
    implementation(project(":core:fido"))
    implementation(project(":core:usb"))
    implementation(project(":core:local"))
    implementation(project(":core:wayland"))
    implementation(project(":core:terminal-haven"))
    // Direct termlib pull-in so the MCP agent transport can name termlib
    // public types (TerminalEmulator, ScrollController, SelectionController,
    // SelectionRange, AgentSnapshot) by class. core:terminal-haven uses
    // `implementation(libs.termlib)` so the dependency doesn't leak.
    implementation(libs.termlib)
    implementation(project(":core:stepca"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:knock"))
    implementation(project(":core:mcp"))
    implementation(project(":core:spa"))

    implementation(project(":feature:connections"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sftp"))
    implementation(project(":feature:mail"))
    implementation(project(":core:mail"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:imagetools"))
    implementation(project(":feature:vnc"))
    implementation(project(":feature:rdp"))
    // The app manifest declares CloudflareAccessLoginActivity, so app must
    // depend on its module directly — it only arrived transitively before,
    // which AGP 9 lint flags as MissingClass on the compile classpath.
    implementation(project(":feature:tunnel"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
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

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
