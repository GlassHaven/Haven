package sh.haven.core.ui.theme

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import sh.haven.core.ui.findWindow

private const val SYSTEM_BAR_LIGHT_LUMINANCE_THRESHOLD = 0.5f

/**
 * True when [surface] is bright enough to require dark status/navigation bar
 * foreground icons and text (#523, #898).
 */
fun isLightSystemBarSurface(surface: Color): Boolean =
    surface.luminance() > SYSTEM_BAR_LIGHT_LUMINANCE_THRESHOLD

@Suppress("DEPRECATION")
private fun applySystemBarsAppearance(
    window: Window,
    statusBarDarkIcons: Boolean,
    navigationBarDarkIcons: Boolean,
) {
    // Update legacy decorView flags across all API levels (matches
    // WindowInsetsControllerCompat.Impl30 so OEM skins and Robolectric
    // shadows that inspect systemUiVisibility stay in sync).
    val decorView = window.decorView
    var vis = decorView.systemUiVisibility
    vis = if (statusBarDarkIcons) {
        vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    } else {
        vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    }
    vis = if (navigationBarDarkIcons) {
        vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    } else {
        vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
    }
    decorView.systemUiVisibility = vis

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let { controller ->
            controller.setSystemBarsAppearance(
                if (statusBarDarkIcons) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
            controller.setSystemBarsAppearance(
                if (navigationBarDarkIcons) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }
}

/**
 * Synchronize Android's status and navigation bar icon contrast with the
 * active UI surface (#898). Needed because `MainActivity` retains its window
 * across `uiMode` configuration changes (`android:configChanges="...|uiMode|..."`),
 * so `enableEdgeToEdge()` in `onCreate()` never re-runs when switching between
 * Dark, Light, and System Default themes.
 */
@Composable
fun SyncSystemBarsAppearance(
    statusBarDarkIcons: Boolean,
    navigationBarDarkIcons: Boolean = statusBarDarkIcons,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findWindow() ?: return@SideEffect
        applySystemBarsAppearance(window, statusBarDarkIcons, navigationBarDarkIcons)
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = HavenGreen,
    secondary = HavenDarkGreen,
    background = HavenBackground,
    surface = HavenSurface,
    error = HavenError,
)

private val LightColorScheme = lightColorScheme(
    primary = HavenDarkGreen,
    secondary = HavenGreen,
)

@Composable
fun HavenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    SyncSystemBarsAppearance(
        statusBarDarkIcons = !darkTheme,
        navigationBarDarkIcons = !darkTheme,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HavenTypography,
        content = content,
    )
}
