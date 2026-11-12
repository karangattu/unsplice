package com.echomute

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val UnspliceMidnight = Color(0xFF0B101B)
internal val UnspliceSurface = Color(0xFF131B2B)
internal val UnspliceSurfaceElevated = Color(0xFF1A2438)
internal val UnspliceSurfaceBright = Color(0xFF222F47)
internal val UnspliceMint = Color(0xFF3EE6A0)
internal val UnspliceOnMint = Color(0xFF062016)
internal val UnspliceMintContainer = Color(0xFF11382B)
internal val UnspliceOnMintContainer = Color(0xFFB5F7D8)
internal val UnspliceAmber = Color(0xFFF5B041)
internal val UnspliceAmberContainer = Color(0xFF332611)
internal val UnspliceIvory = Color(0xFFF4F6FA)
internal val UnspliceSlate = Color(0xFF94A3B8)
internal val UnspliceOutline = Color(0xFF27344D)
internal val UnspliceTrack = Color(0xFF2D3B56)
internal val UnspliceError = Color(0xFFFF6B6B)

private val UnspliceColorScheme = darkColorScheme(
    primary = UnspliceMint,
    onPrimary = UnspliceOnMint,
    primaryContainer = UnspliceMintContainer,
    onPrimaryContainer = UnspliceOnMintContainer,
    secondary = Color(0xFFCBD5E1),
    onSecondary = UnspliceMidnight,
    secondaryContainer = UnspliceSurfaceElevated,
    onSecondaryContainer = UnspliceIvory,
    tertiary = UnspliceAmber,
    onTertiary = Color(0xFF261904),
    background = UnspliceMidnight,
    onBackground = UnspliceIvory,
    surface = UnspliceSurface,
    onSurface = UnspliceIvory,
    surfaceVariant = UnspliceSurfaceElevated,
    onSurfaceVariant = UnspliceSlate,
    outline = UnspliceOutline,
    outlineVariant = Color(0xFF1E293E),
    error = UnspliceError,
    onError = Color(0xFF2B0707)
)

private val UnspliceTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
internal fun UnspliceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UnspliceColorScheme,
        typography = UnspliceTypography,
        content = content
    )
}
