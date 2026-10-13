plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

// Produced by tools/build-android.sh via gomobile; see buildRcloneNative below.
val rcbridgeJar: File = layout.buildDirectory.file("rcbridge-bindings.jar").get().asFile

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // gomobile-generated Java bindings (produced by build-android.sh).
    //
    // ★ #493: this used to be `api(files("build/rcbridge-bindings.jar"))`, and
    // that is why the jar could not be untracked. A raw file dependency has no
    // producing task, so it is demanded the moment a *consumer* resolves its
    // runtime classpath — :app:checkArm64DebugDuplicateClasses asks for it long
    // before buildRcloneNative has run, and `compileKotlin dependsOn(...)` is
    // far too late to help. Attaching builtBy to the file collection does not
    // survive AGP's artifact transform either.
    //
    // So: compile against it here (compileKotlin already depends on the task
    // that produces it), and fold its classes into this project's own jar
    // below. Consumers then get the bound classes through the ordinary project
    // dependency, with no raw file for them to resolve early.
    compileOnly(files(rcbridgeJar))
    testImplementation(files(rcbridgeJar))
    testImplementation("junit:junit:4.13.2")
}

// Kotlin wrapper sources generated alongside the Go → gomobile build
sourceSets {
    main {
        kotlin.srcDir("kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build rclone native library from Go source via gomobile.
// Prerequisites: Go 1.26+, gomobile, gobind, Android NDK.
// The .so files are NOT committed — they're built as part of the Gradle build.
val buildRcloneNative by tasks.registering(Exec::class) {
    val goDir = file("go")
    val jniDir = file("jniLibs")
    val toolsDir = file("tools")

    inputs.dir(goDir)
    inputs.file(toolsDir.resolve("build-android.sh"))
    outputs.dir(jniDir)
    outputs.file(rcbridgeJar)

    // Up-to-date checking is driven by Gradle's inputs/outputs above: the
    // expensive Go cross-compile is skipped when `go/` and build-android.sh are
    // unchanged AND the jniLibs outputs exist, and re-runs when the Go source
    // changes. (Do NOT gate with an `onlyIf { !so.exists() }` — that short-
    // circuits staleness so a source change leaves a stale .so on disk, e.g. a
    // pre-mailbridge build crashing at the first Mailbridge.mbRPC call.)

    workingDir = projectDir
    commandLine("bash", toolsDir.resolve("build-android.sh").absolutePath)

    // Ensure Go toolchain is on PATH for the build script
    val goRoot = "/usr/local/go"
    val goPath = System.getenv("GOPATH") ?: "${System.getProperty("user.home")}/go"
    environment("PATH", "$goRoot/bin:$goPath/bin:${System.getenv("PATH")}")

    // Pass SDK/NDK paths through environment (no hardcoded fallbacks)
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_HOME")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome)
    }
    val sdkRoot = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    if (sdkRoot != null) {
        environment("ANDROID_HOME", sdkRoot)
    }
}

// Ensure the Go native build completes before Kotlin compilation,
// because compileKotlin needs rcbridge-bindings.jar produced by gomobile.
tasks.named("compileKotlin") {
    dependsOn(buildRcloneNative)
}

// The gomobile classes, unpacked so they land in this project's **class output**
// and not only inside its jar.
//
// Folding them into the jar alone is not enough, and the failure is silent. A
// java-library exposes two things to a consumer: the packaged jar, and the raw
// class directories. AGP dexes a project dependency's *class directories* for a
// debug build, and only runs over the *jar* for a minified release. So classes
// added at jar-packaging time reach a release APK and never reach a debug one:
//
//     classes dir:  sh/haven/rclone/bridge  2 .class   wgbridge  0 .class
//     packaged jar: sh/haven/rclone/bridge  2 .class   wgbridge  6 .class
//
// The debug APK therefore *referenced* sh.haven.rclone.binding.wgbridge with
// zero methods defined, and died on launch the moment TunnelManager touched
// WireGuard: NoClassDefFoundError, with a green build and no warning anywhere.
// Registering the unpacked directory as class output puts them on both routes.
// Unpacked into the Kotlin compile output on purpose, rather than into a
// directory of its own registered with `sourceSets.output.dir(...)`. That was
// tried first and does nothing: `output.dir()` adds to the source set's *extra*
// output directories, which are not part of `classesDirs`, and it is
// `classesDirs` that backs the outgoing CLASSES variant a consumer sees. The
// classes sat on disk and AGP never looked at them.
//
// Copy, not Sync: Sync clears the destination, which here is where compileKotlin
// puts this project's own classes.
val unpackRcbridgeBindings by tasks.registering(Copy::class) {
    dependsOn(buildRcloneNative, tasks.named("compileKotlin"))
    from(provider { if (rcbridgeJar.isFile) zipTree(rcbridgeJar) else files() })
    into(layout.buildDirectory.dir("classes/kotlin/main"))
    // Metadata from the gomobile jar must not become this project's manifest.
    exclude("META-INF/**")
}

// `classes` is what both the jar and the CLASSES variant are built from, so
// hanging the unpack off it covers every consumer route.
tasks.named("classes") { dependsOn(unpackRcbridgeBindings) }

// #493: carry the gomobile classes in this project's own jar too, so consumers
// (core:rclone, core:tunnel) reach them through `project(":rclone-android")`
// rather than through a raw file path they would have to resolve themselves.
// That is what lets the jar stop being a committed binary.
tasks.named<Jar>("jar") {
    dependsOn(buildRcloneNative)
    from(provider { if (rcbridgeJar.isFile) zipTree(rcbridgeJar) else files() })
    // The unpacked directory above is already class output, so the jar now picks
    // these up twice; EXCLUDE keeps the first and makes that a no-op.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
