package com.deepaksigroha.imageclean.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.deepaksigroha.imageclean.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF2F5BEA), onPrimary = Color.White, primaryContainer = Color(0xFFDCE3FF), onPrimaryContainer = Color(0xFF0A1A5C),
    secondary = Color(0xFF0EA5E9), secondaryContainer = Color(0xFFD5F0FF), tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF8FAFF), surface = Color(0xFFF8FAFF), surfaceVariant = Color(0xFFE3E8F4),
    error = Color(0xFFBA1A1A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF0A2A8C), primaryContainer = Color(0xFF1E3A8A), onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF7DD3FC), secondaryContainer = Color(0xFF0C4A6E), tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF0B1120), surface = Color(0xFF0B1120), surfaceVariant = Color(0xFF1E293B),
)

val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
)

/** Semantic status colours used for badges. */
object StatusColors {
    val good = Color(0xFF16A34A)
    val warn = Color(0xFFF59E0B)
    val bad = Color(0xFFDC2626)
    val info = Color(0xFF2563EB)
    val neutral = Color(0xFF64748B)
}

@Composable
fun ImageCleanTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
