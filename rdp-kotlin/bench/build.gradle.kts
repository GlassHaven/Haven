// Test rig for the AVC decoder boundary cost (#466).
//
// Compiles the generated uniffi bindings from ../kotlin together with the
// stand-in decoder in this project, and runs them against the HOST build of
// librdp_transport. No Android, no device, no RDP server, no H.264 — the cost
// under investigation is the Rust/Kotlin call itself.
//
//   cd rdp-kotlin/rust && cargo build          # host lib + metadata
//   cd rdp-kotlin/rust && cargo run --bin uniffi-bindgen -- \
//       generate --library target/debug/librdp_transport.so \
//       --language kotlin --out-dir ../kotlin --config uniffi.toml
//   ./gradlew -p rdp-kotlin/bench run
//
// Read the caveat in BoundaryBench.kt before drawing conclusions: this is
// desktop HotSpot, not Android ART.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Runtime, not compileOnly as in the parent: the rig actually calls
    // through JNA rather than only compiling against it. Keep the version in
    // sync with [versions.jna] in gradle/libs.versions.toml so the rig
    // measures what ships.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}

sourceSets {
    main {
        // The generated bindings, shared with the parent build rather than
        // copied — a stale copy would measure a boundary that no longer
        // matches the one Haven ships.
        kotlin.srcDir("../kotlin")
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

application {
    mainClass.set("sh.haven.rdp.bench.BoundaryBench")
}

tasks.named<JavaExec>("run") {
    // JNA finds librdp_transport.so here. The debug build is deliberate: it is
    // what uniffi-bindgen reads metadata from, so the bindings and the library
    // are guaranteed to be the same revision.
    systemProperty("jna.library.path", file("../rust/target/debug").absolutePath)
    // `-Pargs="1920 1080 500"` to override width/height/iterations.
    (project.findProperty("args") as String?)?.let { args(it.split(" ")) }
}
