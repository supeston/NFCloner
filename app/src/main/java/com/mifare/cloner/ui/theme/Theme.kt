package com.mifare.cloner.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mifare.cloner.data.AppThemeMode

@Composable
fun MifareClonerTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    accentTheme: AccentTheme = AccentTheme.CYAN,
    useDynamicColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val targetColorScheme = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> {
            darkColorScheme(
                primary = accentTheme.primaryColor,
                onPrimary = Color(0xFF001E2B),
                primaryContainer = accentTheme.containerColor,
                onPrimaryContainer = Color(0xFFC3E8FF),
                secondary = accentTheme.primaryColor,
                onSecondary = Color(0xFF001E2B),
                secondaryContainer = accentTheme.containerColor,
                onSecondaryContainer = Color(0xFFE0E2E8),
                tertiary = accentTheme.primaryColor,
                onTertiary = Color(0xFF001E2B),
                tertiaryContainer = accentTheme.containerColor,
                onTertiaryContainer = Color(0xFFE0E2E8),
                background = BackgroundDark,
                onBackground = OnBackgroundDark,
                surface = SurfaceDark,
                onSurface = OnSurfaceDark,
                surfaceVariant = SurfaceVariantDark,
                onSurfaceVariant = OnSurfaceVariantDark,
                error = ErrorDark,
                onError = OnErrorDark,
                errorContainer = ErrorDark.copy(alpha = 0.2f),
                onErrorContainer = Color(0xFFFFB4AB),
                outline = OutlineDark,
                outlineVariant = OutlineDark.copy(alpha = 0.5f)
            )
        }
        else -> {
            lightColorScheme(
                primary = accentTheme.primaryColor,
                onPrimary = Color(0xFF001E2B),
                primaryContainer = accentTheme.containerColor.copy(alpha = 0.2f),
                onPrimaryContainer = Color(0xFF001E2B),
                secondary = accentTheme.primaryColor,
                onSecondary = Color(0xFF001E2B),
                secondaryContainer = accentTheme.containerColor.copy(alpha = 0.2f),
                onSecondaryContainer = Color(0xFF1F2328),
                tertiary = accentTheme.primaryColor,
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = accentTheme.containerColor.copy(alpha = 0.2f),
                onTertiaryContainer = Color(0xFF1F2328),
                background = BackgroundLight,
                onBackground = OnBackgroundLight,
                surface = SurfaceLight,
                onSurface = OnSurfaceLight,
                surfaceVariant = SurfaceVariantLight,
                onSurfaceVariant = OnSurfaceVariantLight,
                error = ErrorLight,
                onError = OnErrorLight,
                errorContainer = ErrorLight.copy(alpha = 0.15f),
                onErrorContainer = Color(0xFFBA1A1A),
                outline = OutlineLight,
                outlineVariant = OutlineLight.copy(alpha = 0.5f)
            )
        }
    }

    val animatedColorScheme = targetColorScheme.animateColors(durationMillis = 1000)

    MaterialTheme(
        colorScheme = animatedColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
private fun ColorScheme.animateColors(durationMillis: Int = 1000): ColorScheme {
    val animSpec = tween<Color>(durationMillis = durationMillis, easing = FastOutSlowInEasing)

    return copy(
        primary = animateColorAsState(primary, animSpec, label = "primary").value,
        onPrimary = animateColorAsState(onPrimary, animSpec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(primaryContainer, animSpec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(onPrimaryContainer, animSpec, label = "onPrimaryContainer").value,
        inversePrimary = animateColorAsState(inversePrimary, animSpec, label = "inversePrimary").value,
        secondary = animateColorAsState(secondary, animSpec, label = "secondary").value,
        onSecondary = animateColorAsState(onSecondary, animSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(secondaryContainer, animSpec, label = "secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(onSecondaryContainer, animSpec, label = "onSecondaryContainer").value,
        tertiary = animateColorAsState(tertiary, animSpec, label = "tertiary").value,
        onTertiary = animateColorAsState(onTertiary, animSpec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(tertiaryContainer, animSpec, label = "tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(onTertiaryContainer, animSpec, label = "onTertiaryContainer").value,
        background = animateColorAsState(background, animSpec, label = "background").value,
        onBackground = animateColorAsState(onBackground, animSpec, label = "onBackground").value,
        surface = animateColorAsState(surface, animSpec, label = "surface").value,
        onSurface = animateColorAsState(onSurface, animSpec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(surfaceVariant, animSpec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(onSurfaceVariant, animSpec, label = "onSurfaceVariant").value,
        surfaceTint = animateColorAsState(surfaceTint, animSpec, label = "surfaceTint").value,
        inverseSurface = animateColorAsState(inverseSurface, animSpec, label = "inverseSurface").value,
        inverseOnSurface = animateColorAsState(inverseOnSurface, animSpec, label = "inverseOnSurface").value,
        error = animateColorAsState(error, animSpec, label = "error").value,
        onError = animateColorAsState(onError, animSpec, label = "onError").value,
        errorContainer = animateColorAsState(errorContainer, animSpec, label = "errorContainer").value,
        onErrorContainer = animateColorAsState(onErrorContainer, animSpec, label = "onErrorContainer").value,
        outline = animateColorAsState(outline, animSpec, label = "outline").value,
        outlineVariant = animateColorAsState(outlineVariant, animSpec, label = "outlineVariant").value,
        scrim = animateColorAsState(scrim, animSpec, label = "scrim").value
    )
}
