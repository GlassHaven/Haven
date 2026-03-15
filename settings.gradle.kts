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

// Pure Kotlin SSP transport library (submodule).
includeBuild("mosh-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:ssp-transport")).using(project(":"))
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
include(":core:vnc")

include(":feature:settings")
include(":feature:vnc")
