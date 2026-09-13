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

    @Test
    fun `handleNotificationNavigation with non positive relation id does not navigate`() {
        val navController = mockk<NavHostController>(relaxed = true)

        val zeroId = mockk<Intent>()
        every { zeroId.getStringExtra("navigateTo") } returns "syncPreview"
        every { zeroId.getLongExtra("notification_relation_id", 0L) } returns 0L
        handleNotificationNavigation(zeroId, navController)

        val negativeId = mockk<Intent>()
        every { negativeId.getStringExtra("navigateTo") } returns "syncPreview"
        every { negativeId.getLongExtra("notification_relation_id", 0L) } returns -1L
        handleNotificationNavigation(negativeId, navController)

        // Malformed static intents can never navigate; callers still mark them consumed once
        // so the no-op delivery cannot re-fire on every unlock.
        verify(exactly = 0) {
            navController.navigate(any<String>(), any<NavOptionsBuilder.() -> Unit>())
        }
    }

    @Test
    fun `notificationIdentityOf returns stable identity per navigation content`() {
        val syncPreview = mockk<Intent>()
        every { syncPreview.getStringExtra("navigateTo") } returns "syncPreview"
        every { syncPreview.getLongExtra("notification_relation_id", 0L) } returns 42L
        every { syncPreview.getStringExtra("notification_local_base_path") } returns null

        val sameContent = mockk<Intent>()
        every { sameContent.getStringExtra("navigateTo") } returns "syncPreview"
        every { sameContent.getLongExtra("notification_relation_id", 0L) } returns 42L
        every { sameContent.getStringExtra("notification_local_base_path") } returns null

        val otherRelation = mockk<Intent>()
        every { otherRelation.getStringExtra("navigateTo") } returns "syncPreview"
        every { otherRelation.getLongExtra("notification_relation_id", 0L) } returns 43L
        every { otherRelation.getStringExtra("notification_local_base_path") } returns null

        val otherTarget = mockk<Intent>()
        every { otherTarget.getStringExtra("navigateTo") } returns "browser"
        every { otherTarget.getLongExtra("notification_relation_id", 0L) } returns 0L
        every { otherTarget.getStringExtra("notification_local_base_path") } returns "Movies"

        assertEquals(notificationIdentityOf(syncPreview), notificationIdentityOf(sameContent))
        assertFalse(notificationIdentityOf(syncPreview) == notificationIdentityOf(otherRelation))
        assertFalse(notificationIdentityOf(syncPreview) == notificationIdentityOf(otherTarget))

        val notNotification = mockk<Intent>()
        every { notNotification.getStringExtra("navigateTo") } returns null
        assertEquals(null, notificationIdentityOf(notNotification))
        assertEquals(null, notificationIdentityOf(null))
    }

    @Test
    fun `shouldDeliverNotificationIntentOnCreate fresh launch delivers notification intent`() {
        assertTrue(
            shouldDeliverNotificationIntentOnCreate(
                isNotification = true,
                savedConsumed = false,
                savedConsumedIdentity = null,
                currentIdentity = 100
            )
        )
    }

    @Test
    fun `shouldDeliverNotificationIntentOnCreate rebuild replay of same consumed intent is suppressed`() {
        assertFalse(
            shouldDeliverNotificationIntentOnCreate(
                isNotification = true,
                savedConsumed = true,
                savedConsumedIdentity = 100,
                currentIdentity = 100
            )
        )
    }

    @Test
    fun `shouldDeliverNotificationIntentOnCreate stale consumed flag does not suppress new notification`() {
        assertTrue(
            shouldDeliverNotificationIntentOnCreate(
                isNotification = true,
                savedConsumed = true,
                savedConsumedIdentity = 100,
                currentIdentity = 200
            )
        )
    }

    @Test
    fun `shouldDeliverNotificationIntentOnCreate legacy saved state without identity suppresses`() {
        assertFalse(
            shouldDeliverNotificationIntentOnCreate(
                isNotification = true,
                savedConsumed = true,
                savedConsumedIdentity = null,
                currentIdentity = 200
            )
        )
    }

    @Test
    fun `shouldDeliverNotificationIntentOnCreate non notification intent never delivers`() {
        assertFalse(
            shouldDeliverNotificationIntentOnCreate(
                isNotification = false,
                savedConsumed = false,
                savedConsumedIdentity = null,
                currentIdentity = null
            )
        )
    }

    @Test
    fun `KEY_CONSUMED_IDENTITY constant is defined and matches expected prefix`() {
        assertEquals(
            "cn.vectory.syncplayer.INTENT_CONSUMED_IDENTITY",
            MainActivity.KEY_CONSUMED_IDENTITY
        )
        assertEquals(Int.MIN_VALUE, MainActivity.CONSUMED_IDENTITY_ABSENT)
    }
}
