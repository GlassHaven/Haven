// Deliberately dependency-free. LogRedact is needed by leaf modules (serial, SMB,
// Reticulum) that have no project dependencies at all, and making them pull in
// :core:data — Room, DataStore, Hilt — to redact a hostname would be a poor
// trade. Keep it that way: nothing in here should ever need a dependency.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.redact"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The #518 guard test scans the repo's Kotlin sources at runtime, so Gradle
// cannot infer its inputs. Declare them, or the task serves cached green when
// a scanned file changes (the DesktopManager username leak passed CI for two
// releases). Two constraints shape the declaration:
//  - Scope the files to .kt — that is all the test reads. Whole-tree inputs
//    overlap the native Exec tasks' outputs, which sit INSIDE these roots
//    under src/ (see the jniLibs/assets excludes below); Gradle's validation
//    then fails every build that schedules both sides with "Property has
//    implicit dependency" — the whole-tree input version shipped in
//    04798f3cb and broke CI exactly that way.
//  - Exclude build outputs under the same roots — without it every compile
//    would invalidate the guard and it would never serve an up-to-date
//    result.
tasks.withType<Test>().configureEach {
    val roots = listOf("app", "core", "feature", "termlib").mapNotNull { root ->
        val dir = rootProject.file(root)
        if (dir.exists()) rootProject.fileTree(dir) {
            include("**/*.kt")
            // Keep the walk out of the native Exec tasks' output dirs
            // (core/local build.gradle.kts): Gradle's overlap validation
            // fires on the traversed location, not the matched files, so an
            // exclude-based walk is what keeps the build schedulable.
            exclude("**/src/main/jniLibs", "**/src/main/jniLibs/**",
                    "**/src/main/assets/haven-usb", "**/src/main/assets/haven-usb/**",
                    "**/src/main/assets/wayvnc-shim", "**/src/main/assets/wayvnc-shim/**",
                    "**/build", "**/build/**", "**/.gradle", "**/.gradle/**",
                    "**/.kotlin", "**/.kotlin/**")
        } else null
    }
    inputs.files(roots)
}
