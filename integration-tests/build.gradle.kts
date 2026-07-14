plugins {
    kotlin("jvm")
}

group = "sh.haven"
version = "0.1.0"

dependencies {
    testImplementation("sh.haven:et-transport")
    testImplementation("sh.haven:ssp-transport")
    testImplementation("com.github.mwiede:jsch:2.28.4")
    testImplementation("com.google.protobuf:protobuf-javalite:4.29.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("junit:junit:4.13.2")
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

tasks.test {
    // Only run when explicitly targeted and server is specified
    val testHost = System.getProperty("test.host") ?: System.getenv("TEST_HOST")
    if (testHost.isNullOrBlank()) {
        enabled = false
    }

    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }

    // Pass system properties to test JVM
    systemProperty("test.host", System.getProperty("test.host") ?: "")
    systemProperty("test.port", System.getProperty("test.port") ?: "22")
    systemProperty("test.user", System.getProperty("test.user") ?: "")
    systemProperty("test.key", System.getProperty("test.key") ?: "")
    systemProperty("test.password", System.getProperty("test.password") ?: "")
    systemProperty("test.et.port", System.getProperty("test.et.port") ?: "2022")
}
