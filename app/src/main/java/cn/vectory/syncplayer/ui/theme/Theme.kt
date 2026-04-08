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
    val favoritePink: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        folderAmber = FolderAmber,
        successGreen = SuccessGreen,
        placeholderGrey = PlaceholderGrey,
        favoritePink = FavoritePink,
    )
}

@Composable
fun SyncPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    val typography = Typography()

    val extendedColors = ExtendedColors(
        folderAmber = FolderAmber,
        successGreen = SuccessGreen,
        placeholderGrey = PlaceholderGrey,
        favoritePink = FavoritePink,
    )

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
