package com.mifare.cloner.ui.theme

import androidx.compose.ui.graphics.Color

enum class AccentTheme(val title: String, val primaryColor: Color, val containerColor: Color) {
    CYAN("электро-циан", Color(0xFF80D4FF), Color(0xFF004D67)),
    GREEN("изумруд", Color(0xFF69F0AE), Color(0xFF00512C)),
    PURPLE("неон-фиолет", Color(0xFFD0BCFF), Color(0xFF4F378B)),
    AMBER("янтарный", Color(0xFFFFCA28), Color(0xFF664D00)),
    CRIMSON("кибер-красный", Color(0xFFFF8A80), Color(0xFF680005)),
    MONOCHROME("монохром", Color(0xFFE0E0E0), Color(0xFF373737))
}

// AMOLED Dark Base
val BackgroundDark = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val SurfaceVariantDark = Color(0xFF21262D)
val OnBackgroundDark = Color(0xFFF0F6FC)
val OnSurfaceDark = Color(0xFFF0F6FC)
val OnSurfaceVariantDark = Color(0xFF8B949E)
val ErrorDark = Color(0xFFFF7B72)
val OnErrorDark = Color(0xFF490202)
val OutlineDark = Color(0xFF30363D)

// Clean Light Base
val BackgroundLight = Color(0xFFF6F8FA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFEAEFF5)
val OnBackgroundLight = Color(0xFF1F2328)
val OnSurfaceLight = Color(0xFF1F2328)
val OnSurfaceVariantLight = Color(0xFF656D76)
val ErrorLight = Color(0xFFCF222E)
val OnErrorLight = Color(0xFFFFFFFF)
val OutlineLight = Color(0xFFD0D7DE)
