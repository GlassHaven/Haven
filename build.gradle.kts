plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Downgrades `MissingTranslation` from error (default) to warning so
// it doesn't block CI when a new string lands ahead of its locales,
// while scripts/check-i18n-coverage.py enforces translation parity on main.
subprojects {
    afterEvaluate {
        // AGP 9 removed `lintOptions` and the old-extension `lint.warning +=` path
        // no longer downgrades severity, so MissingTranslation started blocking CI
        // again (4 errors slipped onto main because lint only runs on PRs, not push).
        // Configure via the current DSL lint {} block on both module types instead.
        extensions.findByType<com.android.build.api.dsl.LibraryExtension>()?.lint {
            warning += "MissingTranslation"
        }
        extensions.findByType<com.android.build.api.dsl.ApplicationExtension>()?.lint {
            warning += "MissingTranslation"
        }
    }
}
