package dev.kovka.app

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val Ember = Color(0xFFFFB86C)
internal val Ink = Color(0xFF171410)
internal val Paper = Color(0xFFF7EBE0)
internal val Muted = Color(0xFFD3BFB0)
internal val Panel = Color(0xFF221D18)
internal val Raised = Color(0xFF2C251F)
internal val Copper = Color(0xFF4C301A)
internal val Mint = Color(0xFFC3D89C)

private val colors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF492B12),
    primaryContainer = Copper,
    onPrimaryContainer = Color(0xFFFFDBBB),
    secondary = Color(0xFFE1C0A7),
    secondaryContainer = Color(0xFF4B382C),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Mint,
    tertiaryContainer = Color(0xFF323B25),
    onTertiaryContainer = Color(0xFFDDEBC5),
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    onSurfaceVariant = Muted,
    surfaceVariant = Raised,
    surfaceContainer = Panel,
    surfaceContainerHigh = Raised,
    outline = Color(0xFF9D8779),
    outlineVariant = Color(0xFF4B3C31)
)

private val expressiveTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-1.7).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.35).sp
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.3.sp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun KovkaTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = colors, typography = expressiveTypography, content = content)
}
