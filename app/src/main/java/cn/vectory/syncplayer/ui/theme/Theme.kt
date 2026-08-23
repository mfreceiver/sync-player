package cn.vectory.syncplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class ExtendedColors(
    val folderAmber: Color,
    val starGold: Color,
    val successGreen: Color,
    val placeholderGrey: Color,
    val trashRed: Color,
    val syncCompleted: Color,
    val scrollbarColor: Color,
    val playerTopBar: Color,
    val playerProgressTrack: Color,
    val playerProgressFill: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        folderAmber = FolderAmber,
        starGold = Color(0xFFFFC107),
        successGreen = SuccessGreen,
        placeholderGrey = PlaceholderGrey,
        trashRed = TrashRed,
        syncCompleted = SyncCompleted,
        scrollbarColor = BluePrimary,
        playerTopBar = PlayerTopBar,
        playerProgressTrack = PlayerProgressTrack,
        playerProgressFill = PlayerProgressFill,
    )
}

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = BlueSecondary,
    onSecondary = BlueOnSecondary,
    secondaryContainer = BlueSecondaryContainer,
    onSecondaryContainer = BlueOnSecondaryContainer,
    tertiary = BlueTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    background = BlueSurface,
    onBackground = Color(0xFF1B1B1F),
    surface = BlueSurface,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = BlueSurfaceVariant,
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = BluePrimaryDark,
    surfaceTint = BluePrimary,
)

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = BlueOnPrimaryDark,
    primaryContainer = BluePrimaryContainerDark,
    onPrimaryContainer = BlueOnPrimaryContainerDark,
    secondary = BlueSecondaryDark,
    onSecondary = BlueOnSecondaryDark,
    secondaryContainer = BlueSecondaryContainerDark,
    onSecondaryContainer = BlueOnSecondaryContainerDark,
    tertiary = BlueTertiaryDark,
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF4F3D5C),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = BlueSurfaceDark,
    onBackground = Color(0xFFE3E2E6),
    surface = BlueSurfaceDark,
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = BlueSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF1B1B1F),
    inversePrimary = BluePrimary,
    surfaceTint = BluePrimaryDark,
)

@Composable
fun SyncPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // Deterministic base scheme based on theme
    val context = LocalContext.current
    val blueScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    // Cache dynamic color computation to avoid recomputing on every recomposition
    val colorScheme = remember(darkTheme, dynamicColor) {
        if (dynamicColor) {
            val dynamicScheme = if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
            dynamicScheme.copy(
                primary = blueScheme.primary,
                onPrimary = blueScheme.onPrimary,
                primaryContainer = blueScheme.primaryContainer,
                onPrimaryContainer = blueScheme.onPrimaryContainer,
                secondary = blueScheme.secondary,
                onSecondary = blueScheme.onSecondary,
                secondaryContainer = blueScheme.secondaryContainer,
                onSecondaryContainer = blueScheme.onSecondaryContainer,
            )
        } else {
            blueScheme
        }
    }

    val extendedColors = if (darkTheme) {
        ExtendedColors(
            folderAmber = FolderAmber,
            starGold = Color(0xFFFFC107),
            successGreen = SuccessGreen,
            placeholderGrey = PlaceholderGrey,
            trashRed = TrashRed,
            syncCompleted = SyncCompleted,
            scrollbarColor = BluePrimaryDark,
            playerTopBar = PlayerTopBarDark,
            playerProgressTrack = PlayerProgressTrackDark,
            playerProgressFill = BluePrimaryDark,
        )
    } else {
        ExtendedColors(
            folderAmber = FolderAmber,
            starGold = Color(0xFFFFC107),
            successGreen = SuccessGreen,
            placeholderGrey = PlaceholderGrey,
            trashRed = TrashRed,
            syncCompleted = SyncCompleted,
            scrollbarColor = BluePrimary,
            playerTopBar = PlayerTopBar,
            playerProgressTrack = PlayerProgressTrack,
            playerProgressFill = PlayerProgressFill,
        )
    }

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}
