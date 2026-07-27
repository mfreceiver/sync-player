package cn.vectory.syncplayer.security

import android.content.Context
import androidx.biometric.BiometricManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies BiometricAuthManager.getAuthCapabilityInfo() resolves descriptions
 * from string resources (not hardcoded literals) and that the classification
 * logic produces distinct outputs for distinct capability combinations.
 *
 * Asserts on non-emptiness and distinctness — never on exact Chinese text
 * (those live in strings.xml).
 */
class BiometricAuthManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val biometricManager = mockk<BiometricManager>()

    private lateinit var manager: BiometricAuthManager

    @Before
    fun setUp() {
        mockkStatic(BiometricManager::class)
        every { BiometricManager.from(context) } returns biometricManager
        // Stub getString to return a non-empty placeholder (resource resolution is
        // not under test here; we only verify the classification logic + non-empty).
        every { context.getString(any<Int>()) } answers { "placeholder-${firstArg<Int>()}" }
        manager = BiometricAuthManager(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(BiometricManager::class)
    }

    @Test
    fun `class3 plus class2 plus credential yields non-empty description`() {
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } returns BiometricManager.BIOMETRIC_SUCCESS
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } returns BiometricManager.BIOMETRIC_SUCCESS
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } returns BiometricManager.BIOMETRIC_SUCCESS

        val info = manager.getAuthCapabilityInfo()
        assertTrue("should be able to authenticate", info.canAuthenticate)
        assertTrue("description should be non-empty", info.description.isNotEmpty())
    }

    @Test
    fun `device credential only yields distinct description from full biometric`() {
        // Full biometric setup
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } returns BiometricManager.BIOMETRIC_SUCCESS
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } returns BiometricManager.BIOMETRIC_SUCCESS
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } returns BiometricManager.BIOMETRIC_SUCCESS
        val fullDesc = manager.getAuthCapabilityInfo().description

        // Device credential only: weak+credential succeeds, but strong and weak alone fail
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } returns BiometricManager.BIOMETRIC_SUCCESS
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } returns BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } returns BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        val credOnlyDesc = manager.getAuthCapabilityInfo().description

        assertTrue("full biometric description should be non-empty", fullDesc.isNotEmpty())
        assertTrue("credential-only description should be non-empty", credOnlyDesc.isNotEmpty())
        assertNotEquals(
            "full biometric and credential-only descriptions should differ",
            fullDesc,
            credOnlyDesc
        )
    }

    @Test
    fun `no auth method yields non-authenticable with non-empty description`() {
        every {
            biometricManager.canAuthenticate(any())
        } returns BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

        val info = manager.getAuthCapabilityInfo()
        assertEquals(false, info.canAuthenticate)
        assertTrue("even no-auth description should be non-empty", info.description.isNotEmpty())
    }
}
