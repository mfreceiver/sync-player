package cn.vectory.syncplayer.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AuthCapabilityInfo(
    val canAuthenticate: Boolean,
    val description: String
)

@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun getAuthCapabilityInfo(): AuthCapabilityInfo {
        val biometricManager = BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

        // Check individual biometric strengths
        val canClass3 = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

        val canClass2 = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

        val hasDeviceCredential = canAuth && !canClass3 && !canClass2

        val description = when {
            canClass3 && canClass2 -> "Class 3 (强生物识别) + Class 2 (弱生物识别) + 锁屏密码"
            canClass3 -> "Class 3 (强生物识别) + 锁屏密码"
            canClass2 -> "Class 2 (弱生物识别) + 锁屏密码"
            hasDeviceCredential -> "仅锁屏密码"
            else -> "未设置任何认证方式"
        }

        return AuthCapabilityInfo(
            canAuthenticate = canAuth,
            description = description
        )
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "应用已锁定",
        subtitle: String = "请验证身份以继续使用",
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
