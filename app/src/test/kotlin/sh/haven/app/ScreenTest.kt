package sh.haven.app

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.app.navigation.Screen

class ScreenTest {

    @Test
    fun `all screens have unique routes`() {
        val routes = Screen.entries.map { it.route }
        assertEquals(
            "All screen routes should be unique",
            routes.size, routes.toSet().size
        )
    }

    @Test
    fun `all screens have non-zero label resource`() {
        Screen.entries.forEach { screen ->
            assert(screen.labelRes != 0) {
                "Screen ${screen.name} has no label resource"
            }
        }
    }

    @Test
    fun `there are exactly 8 screens`() {
        assertEquals(
            "Navigation should have 8 tabs",
            8, Screen.entries.size
        )
    }

    @Test
    fun `connections is first screen`() {
        assertEquals(
            "Connections should be the first tab",
            Screen.Connections, Screen.entries.first()
        )
    }
}
