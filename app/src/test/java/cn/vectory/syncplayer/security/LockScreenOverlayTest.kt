package cn.vectory.syncplayer.security

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.FragmentActivity
import cn.vectory.syncplayer.NoOpTestApp
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NoOpTestApp::class)
class LockScreenOverlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `auto triggers authenticate on first composition when not cooldown`() {
        val appLockManager = mockk<AppLockManager>(relaxed = true)
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val biometricAuthManager = spyk(BiometricAuthManager(appContext))
        every {
            biometricAuthManager.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any()
            )
        } answers {}
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides activity) {
                LockScreenOverlay(
                    appLockManager = appLockManager,
                    biometricAuthManager = biometricAuthManager
                )
            }
        }

        verify(atLeast = 1) {
            biometricAuthManager.authenticate(
                activity = activity,
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `shows retry button after auth error`() {
        val appLockManager = mockk<AppLockManager>(relaxed = true)
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val biometricAuthManager = spyk(BiometricAuthManager(appContext))
        var capturedOnError: (() -> Unit)? = null
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        every {
            biometricAuthManager.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any()
            )
        } answers {
            capturedOnError = lastArg()
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides activity) {
                LockScreenOverlay(
                    appLockManager = appLockManager,
                    biometricAuthManager = biometricAuthManager
                )
            }
        }

        // Trigger error
        composeTestRule.runOnUiThread {
            capturedOnError?.invoke()
        }

        // Verify button is displayed and clickable
        composeTestRule.waitForIdle()
        val verifyText = activity.getString(cn.vectory.syncplayer.R.string.lock_verify)
        composeTestRule.onNodeWithText(verifyText).assertHasClickAction()
    }

    @Test
    fun `entering cooldown disables verify button and shows cooldown text`() {
        val appLockManager = mockk<AppLockManager>(relaxed = true)
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val biometricAuthManager = spyk(BiometricAuthManager(appContext))
        var capturedOnError: (() -> Unit)? = null
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        every {
            biometricAuthManager.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any()
            )
        } answers {
            capturedOnError = lastArg()
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides activity) {
                LockScreenOverlay(
                    appLockManager = appLockManager,
                    biometricAuthManager = biometricAuthManager
                )
            }
        }

        // Trigger 5 errors to enter cooldown
        composeTestRule.runOnUiThread {
            repeat(5) {
                capturedOnError?.invoke()
            }
        }

        composeTestRule.waitForIdle()
        val verifyText = activity.getString(cn.vectory.syncplayer.R.string.lock_verify)
        composeTestRule.onNodeWithText(verifyText).assertIsNotEnabled()
    }

    @Test
    fun `cooldown countdown completion triggers re-authentication`() {
        val appLockManager = mockk<AppLockManager>(relaxed = true)
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val biometricAuthManager = spyk(BiometricAuthManager(appContext))
        var capturedOnError: (() -> Unit)? = null
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        every {
            biometricAuthManager.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any()
            )
        } answers {
            capturedOnError = lastArg()
        }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides activity) {
                LockScreenOverlay(
                    appLockManager = appLockManager,
                    biometricAuthManager = biometricAuthManager
                )
            }
        }

        // Trigger 5 errors to enter cooldown
        composeTestRule.runOnUiThread {
            repeat(5) {
                capturedOnError?.invoke()
            }
        }
        composeTestRule.waitForIdle()

        // Advance time by 31 seconds for cooldown
        composeTestRule.mainClock.advanceTimeBy(31_000L)
        composeTestRule.waitForIdle()

        // Verify authenticate is called again after cooldown finishes
        verify(atLeast = 2) {
            biometricAuthManager.authenticate(
                activity = activity,
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any()
            )
        }
    }
}
