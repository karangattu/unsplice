package com.echomute

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val UnspliceMidnight = Color(0xFF070B14)
internal val UnspliceSurface = Color(0xFF0E1422)
internal val UnspliceSurfaceElevated = Color(0xFF151D30)
internal val UnspliceSurfaceBright = Color(0xFF1E2942)
internal val UnspliceHairline = Color(0x14FFFFFF)
internal val UnspliceMint = Color(0xFF42EB9F)
internal val UnspliceOnMint = Color(0xFF052114)
internal val UnspliceMintContainer = Color(0xFF103428)
internal val UnspliceOnMintContainer = Color(0xFFBDF7DA)
internal val UnspliceAmber = Color(0xFFF6B247)
internal val UnspliceAmberContainer = Color(0xFF2F230F)
internal val UnspliceIvory = Color(0xFFF6F5F0)
internal val UnspliceSlate = Color(0xFF98A6BE)
internal val UnspliceMuted = Color(0xFF66758F)
internal val UnspliceOutline = Color(0xFF222E47)
internal val UnspliceTrack = Color(0xFF25324B)
internal val UnspliceError = Color(0xFFFF6E76)

private val UnspliceColorScheme = darkColorScheme(
    primary = UnspliceMint,
    onPrimary = UnspliceOnMint,
    primaryContainer = UnspliceMintContainer,
    onPrimaryContainer = UnspliceOnMintContainer,
    secondary = Color(0xFFD6DEEC),
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
    outlineVariant = Color(0xFF1A243A),
    error = UnspliceError,
    onError = Color(0xFF2B0707)
)

private val UnspliceTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
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
        fontSize = 12.5.sp,
        lineHeight = 18.sp
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

private val UnspliceShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

internal fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
internal fun UnspliceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UnspliceColorScheme,
        typography = UnspliceTypography,
        shapes = UnspliceShapes,
        content = content
    )
}
