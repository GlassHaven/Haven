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

// rclone Go bridge compiled via gomobile for cloud storage backends.
includeBuild("rclone-android") {
    dependencySubstitution {
        substitute(module("sh.haven:rclone-transport")).using(project(":"))
    }
}

rootProject.name = "Haven"

include(":app")

include(":core:ui")
include(":core:ssh")
include(":core:security")
include(":core:data")

include(":feature:connections")
include(":feature:terminal")
include(":feature:sftp")
include(":feature:keys")
include(":core:reticulum")
include(":core:mosh")
include(":core:et")
include(":core:vnc")
include(":core:rdp")
include(":core:smb")
include(":core:rclone")
include(":core:fido")
include(":core:local")

include(":feature:settings")
include(":feature:vnc")
include(":feature:rdp")

include(":integration-tests")
