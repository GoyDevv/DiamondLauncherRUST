package com.diamondlauncher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Horizon Launcher's 8 WCAG-AA verified accents (`docs/design-system.md`) + Diamond Cyan.
 */
enum class HorizonAccent(
    val id: String,
    val label: String,
    val darkColor: Color,
    val lightColor: Color
) {
    DIAMOND("diamond", "Diamond Cyan", Color(0xFF38BDF8), Color(0xFF0284C7)),
    EMERALD("emerald", "Horizon Green", Color(0xFF3FB950), Color(0xFF1A7F37)),
    AMBER("amber", "Blaze Amber", Color(0xFFD29922), Color(0xFF9A6700)),
    SAPPHIRE("sapphire", "Lapis Blue", Color(0xFF58A6FF), Color(0xFF0969DA)),
    AMETHYST("amethyst", "End Violet", Color(0xFFBC8CFF), Color(0xFF8250DF)),
    REDSTONE("redstone", "Redstone Crimson", Color(0xFFF85149), Color(0xFFCF222E)),
    COPPER("copper", "Oxidized Teal", Color(0xFF39C5BB), Color(0xFF0E7490)),
    NETHERITE("netherite", "Monochrome Slate", Color(0xFFF0F4F8), Color(0xFF1C2026));

    companion object {
        fun fromId(id: String): HorizonAccent =
            entries.firstOrNull { it.id == id } ?: DIAMOND
    }
}

enum class ThemePreference(val id: String, val label: String) {
    SYSTEM("system", "System Default"),
    DARK("dark", "Obsidian Dark"),
    LIGHT("light", "Quartz Light");
}

private fun fallbackDarkScheme(accent: HorizonAccent): ColorScheme = darkColorScheme(
    primary = accent.darkColor,
    onPrimary = Color(0xFF061018),
    primaryContainer = accent.darkColor.copy(alpha = 0.18f),
    onPrimaryContainer = accent.darkColor,
    secondary = Color(0xFF3FB950),
    background = Color(0xFF0D0F12),
    surface = Color(0xFF14171C),
    surfaceVariant = Color(0xFF1C2026),
    onBackground = Color(0xFFF0F4F8),
    onSurface = Color(0xFFF0F4F8),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF282D35),
    outlineVariant = Color(0xFF1E2229),
    error = Color(0xFFF85149)
)

private fun fallbackLightScheme(accent: HorizonAccent): ColorScheme = lightColorScheme(
    primary = accent.lightColor,
    onPrimary = Color.White,
    primaryContainer = accent.lightColor.copy(alpha = 0.14f),
    onPrimaryContainer = accent.lightColor,
    secondary = Color(0xFF1A7F37),
    background = Color(0xFFFAF9F6),
    surface = Color(0xFFF2F0EC),
    surfaceVariant = Color(0xFFE8E5DF),
    onBackground = Color(0xFF14171C),
    onSurface = Color(0xFF14171C),
    onSurfaceVariant = Color(0xFF57606A),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFE2E6EA),
    error = Color(0xFFCF222E)
)

val DiamondTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun DiamondLauncherTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    useMaterialYouDynamicColor: Boolean = true,
    accent: HorizonAccent = HorizonAccent.DIAMOND,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    val context = LocalContext.current
    val colorScheme = when {
        useMaterialYouDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> fallbackDarkScheme(accent)
        else -> fallbackLightScheme(accent)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DiamondTypography,
        content = content
    )
}