import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.feature.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }
}

@DisableCachingByDefault(because = "Copies local user-facing Markdown docs into generated Android assets")
abstract class SyncUserGuideAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val docsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val srcRoot = docsDir.get().asFile
        val destRoot = outputDir.get().asFile
        destRoot.deleteRecursively()
        val guideDir = destRoot.resolve("user-guide")
        val featuresDest = guideDir.resolve("features")
        featuresDest.mkdirs()

        val rootDocs = listOf("FEATURES.md", "index.md", "backup-format.md", "gpu-acceleration.md")
        val copied = mutableListOf<String>()
        for (name in rootDocs) {
            val f = srcRoot.resolve(name)
            if (f.isFile) {
                f.copyTo(guideDir.resolve(name), overwrite = true)
                copied += name
            }
        }
        val featureFiles = srcRoot.resolve("features")
            .listFiles { f -> f.isFile && f.extension == "md" }
            ?.sortedBy { it.name }
            .orEmpty()
        for (f in featureFiles) {
            f.copyTo(featuresDest.resolve(f.name), overwrite = true)
            copied += "features/${f.name}"
        }
        guideDir.resolve("manifest.txt").writeText(copied.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}

val syncUserGuideAssets = tasks.register<SyncUserGuideAssetsTask>("syncUserGuideAssets") {
    docsDir.set(rootProject.layout.projectDirectory.dir("docs"))
    outputDir.set(layout.buildDirectory.dir("generated/userGuideAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncUserGuideAssets,
            SyncUserGuideAssetsTask::outputDir,
        )
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:local"))
    implementation(project(":core:security"))

    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.lifecycle.viewmodel)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
