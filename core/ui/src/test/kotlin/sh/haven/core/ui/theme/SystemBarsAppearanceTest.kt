package sh.haven.core.ui.theme

import android.content.ContextWrapper
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import sh.haven.core.ui.findActivity
import sh.haven.core.ui.findWindow

@RunWith(RobolectricTestRunner::class)
class SystemBarsAppearanceTest {

    private fun drainMainLooper() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `findActivity and findWindow unwrap nested ContextWrapper layers (#898)`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val wrappedOnce = ContextWrapper(activity)
        val wrappedTwice = ContextWrapper(wrappedOnce)

        assertSame(activity, wrappedTwice.findActivity())
        assertSame(activity.window, wrappedTwice.findWindow())
    }

    @Test
    fun `HavenTheme updates status and navigation bar icon contrast across dynamic theme switches on wrapped Context (#898)`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val wrappedContext = ContextWrapper(ContextWrapper(activity))
        var darkTheme by mutableStateOf(true)
        var statusBarOverride by mutableStateOf<Boolean?>(null)

        val composeView = ComposeView(wrappedContext).apply {
            setContent {
                HavenTheme(darkTheme = darkTheme, dynamicColor = false) {
                    SyncSystemBarsAppearance(
                        statusBarDarkIcons = statusBarOverride ?: !darkTheme,
                        navigationBarDarkIcons = !darkTheme,
                    )
                    Surface {}
                }
            }
        }
        activity.setContentView(composeView, ViewGroup.LayoutParams(400, 800))
        composeView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
        )
        composeView.layout(0, 0, 400, 800)
        drainMainLooper()

        val controller = WindowCompat.getInsetsController(activity.window, composeView)
        assertNotNull(controller)

        // Initial Dark theme -> light (white) status and navigation bar icons.
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)

        // Switching to Light theme without recreating the Activity flips both bars to dark icons.
        darkTheme = false
        drainMainLooper()
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)

        // Opaque dark terminal surface (#523) under Light app theme keeps white status-bar icons
        // over the black terminal strip while preserving dark nav-bar icons on the light bottom bar.
        assertFalse(isLightSystemBarSurface(Color(0xFF0E0E0E)))
        assertTrue(isLightSystemBarSurface(Color(0xFFFDF6E3)))
        statusBarOverride = isLightSystemBarSurface(Color(0xFF0E0E0E))
        drainMainLooper()
        assertFalse(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)

        // Leaving the dark terminal tab (statusBarOverride = null) restores Light theme's dark status-bar icons.
        statusBarOverride = null
        drainMainLooper()
        assertEquals(true, controller.isAppearanceLightStatusBars)
        assertEquals(true, controller.isAppearanceLightNavigationBars)
    }
}
