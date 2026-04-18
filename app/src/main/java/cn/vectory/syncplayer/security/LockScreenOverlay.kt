package cn.vectory.syncplayer.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

@Composable
fun LockScreenOverlay(
    appLockManager: AppLockManager,
    biometricAuthManager: BiometricAuthManager
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return

    var authFailed by remember { mutableStateOf(false) }

    // Auto-trigger authentication on first composition
    LaunchedEffect(Unit) {
        biometricAuthManager.authenticate(
            activity = activity,
            onSuccess = { appLockManager.unlock() },
            onError = { authFailed = true }
        )
    }

    BackHandler(enabled = true) { /* Block back gesture */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "应用已锁定",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请验证身份以继续使用",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (authFailed) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        authFailed = false
                        biometricAuthManager.authenticate(
                            activity = activity,
                            onSuccess = { appLockManager.unlock() },
                            onError = { authFailed = true }
                        )
                    }
                ) {
                    Text("重试验证")
                }
            }
        }
    }
}
