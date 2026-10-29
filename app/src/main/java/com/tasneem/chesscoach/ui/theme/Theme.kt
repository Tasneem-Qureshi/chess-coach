package com.tasneem.chesscoach.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.analysis.MoveClass

/** Pure-black AMOLED palette: black surfaces, hairline borders, one cyan accent. */
object Amoled {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0A)
    val Raised = Color(0xFF141414)
    val Border = Color(0xFF242424)
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFF8A8A8A)
    val Accent = Color(0xFF00E5FF)
    val Alt = Color(0xFFFF2BD6)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFFF5C5C)
    val BoardLight = Color(0xFF3A3F47)
    val BoardDark = Color(0xFF22262C)

    fun of(c: MoveClass): Color = when (c) {
        MoveClass.BEST -> Accent
        MoveClass.EXCELLENT -> Good
        MoveClass.GOOD -> Color(0xFF9AE6B4)
        MoveClass.INACCURACY -> Warn
        MoveClass.MISTAKE -> Color(0xFFFF9F43)
        MoveClass.BLUNDER -> Bad
    }
}

private val Scheme = darkColorScheme(
    primary = Amoled.Accent,
    onPrimary = Amoled.Black,
    secondary = Amoled.Alt,
    onSecondary = Amoled.Black,
    background = Amoled.Black,
    onBackground = Amoled.Text,
    surface = Amoled.Black,
    onSurface = Amoled.Text,
    surfaceVariant = Amoled.Raised,
    onSurfaceVariant = Amoled.Muted,
    surfaceContainer = Amoled.Surface,
    surfaceContainerHigh = Amoled.Raised,
    surfaceContainerHighest = Amoled.Raised,
    surfaceContainerLow = Amoled.Surface,
    surfaceContainerLowest = Amoled.Black,
    outline = Amoled.Border,
    outlineVariant = Amoled.Border,
    error = Amoled.Bad,
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun ChessCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
