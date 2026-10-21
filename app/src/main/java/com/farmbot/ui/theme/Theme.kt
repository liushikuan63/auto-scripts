/**
 * 主题与配色
 *
 * 提供深浅两套配色（农场绿主色、橙色强调、红色错误等）、卡片与文字色阶，
 * 以及 Material3 的亮 / 暗色方案与排版。
 */
package com.farmbot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val GreenPrimary = Color(0xFF4CAF50)
val GreenDark = Color(0xFF2E7D32)
val GreenLight = Color(0xFF81C784)
val OrangeAccent = Color(0xFFFF9800)
val RedError = Color(0xFFF44336)
val BlueInfo = Color(0xFF2196F3)
val BackgroundLight = Color(0xFFF5F5F5)
val BackgroundDark = Color(0xFF1A1A1A)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF2C2C2C)
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)
val TextOnDark = Color(0xFFE0E0E0)
val CardDark = Color(0xCC2C2C2C)
val CardLight = Color(0xCCFFFFFF)

private val LightColorScheme = lightColorScheme(
    primary = GreenPrimary,
    secondary = OrangeAccent,
    error = RedError,
    background = BackgroundLight,
    surface = SurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = GreenPrimary,
    secondary = OrangeAccent,
    error = RedError,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextOnDark,
    onSurface = TextOnDark
)

@Composable
fun FarmTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FarmTypography,
        content = content
    )
}

val FarmTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)
