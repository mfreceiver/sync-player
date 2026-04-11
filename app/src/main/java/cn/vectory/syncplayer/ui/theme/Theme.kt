package cn.vectory.syncplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class ExtendedColors(
    val folderAmber: Color,
    val successGreen: Color,
    val placeholderGrey: Color,
    val trashRed: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        folderAmber = FolderAmber,
        successGreen = SuccessGreen,
        placeholderGrey = PlaceholderGrey,
        trashRed = TrashRed,
    )
}

@Composable
fun SyncPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    val bluePrimary = Color(0xFF1565C0)
    val blueOnPrimary = Color.White
    val bluePrimaryContainer = Color(0xFF90CAF9)
    val blueOnPrimaryContainer = Color(0xFF001D36)
    val blueSecondaryContainer = Color(0xFFD4E4FF)
    val blueOnSecondaryContainer = Color(0xFF001C3A)

    val colorScheme = baseScheme.copy(
        primary = bluePrimary,
        onPrimary = blueOnPrimary,
        primaryContainer = bluePrimaryContainer,
        onPrimaryContainer = blueOnPrimaryContainer,
        secondaryContainer = blueSecondaryContainer,
        onSecondaryContainer = blueOnSecondaryContainer,
    )

    val typography = Typography()

    val extendedColors = ExtendedColors(
        folderAmber = FolderAmber,
        successGreen = SuccessGreen,
        placeholderGrey = PlaceholderGrey,
        trashRed = TrashRed,
    )

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
