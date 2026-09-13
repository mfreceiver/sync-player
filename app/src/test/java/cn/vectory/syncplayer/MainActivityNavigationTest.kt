package cn.vectory.syncplayer

import android.content.Intent
import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.navOptions
import cn.vectory.syncplayer.navigation.Route
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainActivityNavigationTest {

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `isNotificationIntent detects presence of navigateTo extra`() {
        val withNav = mockk<Intent>()
        every { withNav.getStringExtra("navigateTo") } returns "browser"
        assertTrue(isNotificationIntent(withNav))

        val withoutNav = mockk<Intent>()
        every { withoutNav.getStringExtra("navigateTo") } returns null
        assertFalse(isNotificationIntent(withoutNav))

        assertFalse(isNotificationIntent(null))
    }

    @Test
    fun `handleNotificationNavigation for browser uses popUpTo browser root and singleTop`() {
        val navController = mockk<NavHostController>(relaxed = true)
        val intent = mockk<Intent>()
        every { intent.getStringExtra("navigateTo") } returns "browser"
        every { intent.getStringExtra("notification_local_base_path") } returns "Movies/SciFi"

        val routeSlot = slot<String>()
        val builderSlot = slot<NavOptionsBuilder.() -> Unit>()

        handleNotificationNavigation(intent, navController)

        verify {
            navController.navigate(capture(routeSlot), capture(builderSlot))
        }

        assertEquals("browser?folder=Movies/SciFi", routeSlot.captured)

        val navOptions = navOptions { builderSlot.captured.invoke(this) }
        assertEquals(Route.Browser.route, navOptions.popUpToRoute)
        assertFalse(navOptions.isPopUpToInclusive())
        assertTrue(navOptions.shouldLaunchSingleTop())
    }

    @Test
    fun `handleNotificationNavigation for syncPreview uses popUpTo browser root and singleTop`() {
        val navController = mockk<NavHostController>(relaxed = true)
        val intent = mockk<Intent>()
        every { intent.getStringExtra("navigateTo") } returns "syncPreview"
        every { intent.getLongExtra("notification_relation_id", 0L) } returns 42L

        val routeSlot = slot<String>()
        val builderSlot = slot<NavOptionsBuilder.() -> Unit>()

        handleNotificationNavigation(intent, navController)

        verify {
            navController.navigate(capture(routeSlot), capture(builderSlot))
        }

        assertEquals(Route.SyncPreview.create(42L), routeSlot.captured)

        val navOptions = navOptions { builderSlot.captured.invoke(this) }
        assertEquals(Route.Browser.route, navOptions.popUpToRoute)
        assertFalse(navOptions.isPopUpToInclusive())
        assertTrue(navOptions.shouldLaunchSingleTop())
    }

    @Test
    fun `handleNotificationNavigation with invalid or null intent does not navigate`() {
        val navController = mockk<NavHostController>(relaxed = true)

        handleNotificationNavigation(null, navController)

        val invalidIntent = mockk<Intent>()
        every { invalidIntent.getStringExtra("navigateTo") } returns null
        handleNotificationNavigation(invalidIntent, navController)

        val unknownIntent = mockk<Intent>()
        every { unknownIntent.getStringExtra("navigateTo") } returns "unknownScreen"
        handleNotificationNavigation(unknownIntent, navController)

        verify(exactly = 0) {
            navController.navigate(any<String>(), any<NavOptionsBuilder.() -> Unit>())
        }
    }

    @Test
    fun `KEY_INTENT_CONSUMED constant is defined and matches expected prefix`() {
        assertEquals("cn.vectory.syncplayer.INTENT_CONSUMED", MainActivity.KEY_INTENT_CONSUMED)
    }
}
