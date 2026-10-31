package com.hiwtata.melique.ui.theme

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF070708)
val Paper = Color(0xFFF3F0E8)
val Muted = Color(0xFFA39E93)
val Faint = Color(0xFF6F6A62)
val Hairline = Color(0x14FFFFFF)
val Card = Color(0xFF141416)
val DeleteTone = Color(0xFFE08B7C)
val PendingTone = Color(0xFFE2B15C)
val KeepTone = Color(0xFF8FCBB2)

data class LibraryColors(
    val background: Color,
    val onBackground: Color,
    val muted: Color,
    val faint: Color,
    val card: Color,
    val accent: Color,
    val onAccent: Color,
    val scheme: ColorScheme,
)

private val Scheme = darkColorScheme(
    primary = Paper,
    onPrimary = Color(0xFF161616),
    secondary = Muted,
    background = Ink,
    onBackground = Paper,
    surface = Card,
    onSurface = Paper,
    outline = Hairline,
    error = DeleteTone,
)

private val LightInk = Color(0xFF1A1916)
private val LightPaper = Color(0xFFF4F1EA)
private val LightMuted = Color(0xFF5E5A53)
private val LightFaint = Color(0xFF8A847A)
private val LightCard = Color(0xFFFFFFFF)

private val LightScheme = lightColorScheme(
    primary = LightInk,
    onPrimary = LightPaper,
    secondary = LightMuted,
    background = LightPaper,
    onBackground = LightInk,
    surface = LightCard,
    onSurface = LightInk,
    outline = Color(0x1A000000),
    error = DeleteTone,
)

val DarkLibrary = LibraryColors(
    background = Ink,
    onBackground = Paper,
    muted = Muted,
    faint = Faint,
    card = Card,
    accent = Paper,
    onAccent = Color(0xFF161616),
    scheme = Scheme,
)

val LightLibrary = LibraryColors(
    background = LightPaper,
    onBackground = LightInk,
    muted = LightMuted,
    faint = LightFaint,
    card = LightCard,
    accent = LightInk,
    onAccent = LightPaper,
    scheme = LightScheme,
)

val LocalLibraryColors = staticCompositionLocalOf { DarkLibrary }

private val Type = Typography(
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.6).sp,
        color = Paper,
    ),
    titleLarge = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Medium,
        color = Paper,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        color = Paper,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Muted,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Medium,
        color = Muted,
    ),
)

@Composable
fun SonaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val base = if (dark) DarkLibrary else LightLibrary
    val accent: Color
    val onAccent: Color
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        accent = dynamic.primary
        onAccent = dynamic.onPrimary
    } else {
        accent = systemAccent(context)
        onAccent = contentOn(accent)
    }
    val library = base.copy(
        accent = accent,
        onAccent = onAccent,
        scheme = base.scheme.copy(primary = accent, onPrimary = onAccent),
    )
    CompositionLocalProvider(LocalLibraryColors provides library) {
        MaterialTheme(
            colorScheme = Scheme,
            typography = Type,
            content = content,
        )
    }
}

private fun systemAccent(context: android.content.Context): Color {
    val typed = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
    val color = typed.getColor(0, 0xFF6750A4.toInt())
    typed.recycle()
    return Color(color)
}

private fun contentOn(color: Color): Color {
    val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
    return if (luminance > 0.6f) Color(0xFF161616) else Color.White
}
