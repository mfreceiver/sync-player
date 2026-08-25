package cn.vectory.syncplayer.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import cn.vectory.syncplayer.R
import kotlinx.coroutines.delay

@Composable
fun LockScreenOverlay(
    appLockManager: AppLockManager,
    biometricAuthManager: BiometricAuthManager
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    var authFailed by remember { mutableStateOf(false) }
    var failCount by rememberSaveable { mutableIntStateOf(0) }
    var isCooldown by rememberSaveable { mutableStateOf(false) }
    var cooldownRemaining by rememberSaveable { mutableIntStateOf(0) }

    // Callback shared between initial auth and retry button
    val onAuthError: () -> Unit = {
        authFailed = true
        failCount++
        if (failCount >= 5 && !isCooldown) {
            isCooldown = true
            cooldownRemaining = 30
        }
    }

    // Cooldown countdown timer — runs only when isCooldown is true
    LaunchedEffect(isCooldown) {
        if (isCooldown) {
            var remaining = 30
            while (remaining > 0) {
                cooldownRemaining = remaining
                delay(1000L)
                remaining--
            }
            cooldownRemaining = 0
            failCount = 0
            isCooldown = false
        }
    }

    // Auto-trigger authentication on first composition (skip during cooldown)
    LaunchedEffect(Unit) {
        if (!isCooldown) {
            biometricAuthManager.authenticate(
                activity = activity,
                onSuccess = { appLockManager.unlock() },
                onError = onAuthError
            )
        }
    }

    BackHandler(enabled = true) { /* Block back gesture */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 120.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.lock_app_locked),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.lock_verify_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (authFailed) {
                Spacer(Modifier.height(24.dp))
                Button(
                    enabled = !isCooldown,
                    onClick = {
                        if (isCooldown) return@Button
                        authFailed = false
                        biometricAuthManager.authenticate(
                            activity = activity,
                            onSuccess = { appLockManager.unlock() },
                            onError = onAuthError
                        )
                    }
                ) {
                    Text(stringResource(R.string.lock_verify))
                }
                if (isCooldown) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.lock_cooldown, cooldownRemaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
