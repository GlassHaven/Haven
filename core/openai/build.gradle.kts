plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.openai"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ssh"))
    implementation(project(":core:tunnel"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    // Socket-level tunnel routing: HttpURLConnection can't take a SocketFactory
    // for plain http:, OkHttp's socketFactory() is the only path that lets a
    // profile's base URL stay untouched while TunnelResolver intercepts the
    // dial. Same catalog entry :core:tunnel already ships. api() not
    // implementation: OpenAiClient's public surface takes OkHttpClient, so
    // consumers (:app's tool provider) need the type on their classpath.
    api(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Android's android.jar stubs out org.json.JSONObject in unit tests; add
    // the real lib (same package/class) so OpenAiClient tests hit the real
    // parser. Same trick :core:tunnel's tests use.
    testImplementation("org.json:json:20260814")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}