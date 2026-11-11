pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack hosts mik3y/usb-serial-for-android (not on Maven Central).
        // Scoped to that group so the resolver doesn't fall back to JitPack
        // for any other dependency.
        maven {
            url = uri("https://jitpack.io")
            content {
                // mik3y/usb-serial-for-android — the USB-serial terminal driver
                // (CDC-ACM + CH34x/FTDI/CP21xx). JitPack-only, not on Central.
                includeGroup("com.github.mik3y")
            }
        }
    }
}

// Build termlib from source (submodule fork with popScrollbackLine fix).
// Drop this includeBuild once the fix is merged upstream and released.
includeBuild("termlib") {
    dependencySubstitution {
        substitute(module("org.connectbot:termlib")).using(project(":lib"))
    }
}

// Pure Kotlin ET transport library (submodule).
includeBuild("et-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:et-transport")).using(project(":"))
    }
}

// Pure Kotlin SSP transport library (submodule).
includeBuild("mosh-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:ssp-transport")).using(project(":"))
    }
}

// IronRDP + UniFFI Kotlin bindings (submodule).
includeBuild("rdp-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:rdp-transport")).using(project(":"))
    }
}

// Pure-Rust SPICE client + UniFFI Kotlin bindings (#286, submodule GlassOnTin/spice-kotlin).
includeBuild("spice-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:spice-transport")).using(project(":"))
    }
}

// Prns (Personal Reticulum, Rust engine) JVM SDK — gated behind -PenablePrns=true
// until PrnsEngine is wired into production callers so default builds and APKs
// don't compile 3-ABI Rust or bundle 6.6 MB of unused libprns_host.so.
if (providers.gradleProperty("enablePrns").orNull == "true") {
    includeBuild("prns/prns-host/bindings/jvm") {
        dependencySubstitution {
            substitute(module("rs.reticulum:personal-rns")).using(project(":"))
        }
    }
}

// Go bridge compiled via gomobile, single libgojni.so containing:
//   - rcbridge: rclone for cloud storage backends
//   - wgbridge: wireguard-go + gVisor netstack for per-app WireGuard (#102)
// Having both in one gomobile build avoids duplicate `go.Seq` runtime
// classes and duplicate `libgojni.so` collisions.
includeBuild("rclone-android") {
    dependencySubstitution {
        substitute(module("sh.haven:rclone-transport")).using(project(":"))
    }
}

// rnsh-kt: Kotlin rnsh client library (submodule).
includeBuild("rnsh-kt") {
    dependencySubstitution {
        substitute(module("tech.torlando:rnsh-core"))
            .using(project(":rnsh-core"))
    }
}

// reticulum-kt upstream (submodule, pinned to 83c92af). See issue #79.
includeBuild("reticulum-kt") {
    dependencySubstitution {
        substitute(module("network.reticulum:rns-core"))
            .using(project(":rns-core"))
        substitute(module("network.reticulum:rns-interfaces"))
            .using(project(":rns-interfaces"))
        substitute(module("network.reticulum:rns-android"))
            .using(project(":rns-android"))
    }
}

rootProject.name = "Haven"

include(":app")

include(":core:ui")
include(":core:terminal-haven")
include(":core:toolbar")
include(":core:ssh")
include(":core:security")
include(":core:data")
include(":core:redact")
include(":core:tunnel")
include(":core:knock")
include(":core:mcp")
include(":core:spa")
include(":core:stepca")

include(":feature:connections")
include(":feature:terminal")
include(":feature:sftp")
include(":feature:mail")
include(":feature:chat")
include(":feature:keys")
include(":feature:tunnel")
include(":core:reticulum")
include(":core:mosh")
include(":core:et")
include(":core:btserial")
include(":core:usbserial")
include(":core:bleserial")
include(":core:vnc")
include(":core:rdp")
if (providers.gradleProperty("enablePrns").orNull == "true") {
    include(":core:prns")
}
include(":core:spice")
include(":core:glkvm")
include(":core:smb")
include(":core:rclone")
include(":core:mail")
include(":core:openai")
include(":core:fido")
include(":core:usb")
include(":core:local")
include(":core:wayland")
include(":core:ffmpeg")
include(":core:scan")

include(":feature:settings")
include(":feature:editor")
include(":feature:imagetools")
include(":feature:vnc")
include(":feature:rdp")

include(":integration-tests")
