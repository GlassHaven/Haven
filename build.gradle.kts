plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Library modules that don't define the "store" dimension need a default
// so they can consume core:ssh (which has foss/full variants).
//
// Also downgrades `MissingTranslation` from error (default) to warning so
// it doesn't block CI when a new string lands ahead of its 10 locales,
// but still surfaces in lint reports — disabling it entirely caused
// ~120 strings to drift silently before issue #125 caught it.
subprojects {
    afterEvaluate {
        extensions.findByType<com.android.build.gradle.LibraryExtension>()?.apply {
            if (flavorDimensionList.none { it == "store" }) {
                defaultConfig {
                    missingDimensionStrategy("store", "full")
                }
            }
            lint.warning += "MissingTranslation"
        }
        extensions.findByType<com.android.build.gradle.AppExtension>()?.apply {
            lintOptions.warning("MissingTranslation")
        }
    }
}
