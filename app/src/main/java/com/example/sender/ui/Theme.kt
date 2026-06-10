package com.example.sender.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Brand palette ─────────────────────────────────────────────────────────────

private val Teal400  = Color(0xFF26C6DA)
private val Teal600  = Color(0xFF00ACC1)
private val Teal800  = Color(0xFF006978)
private val Teal900  = Color(0xFF004D56)
private val Indigo50 = Color(0xFFE8EAF6)
private val Surface0 = Color(0xFF0E1117)
private val Surface1 = Color(0xFF161B22)
private val Surface2 = Color(0xFF1C2230)
private val Surface3 = Color(0xFF21283A)

private val DarkScheme = darkColorScheme(
    primary            = Teal400,
    onPrimary          = Color(0xFF003740),
    primaryContainer   = Teal800,
    onPrimaryContainer = Color(0xFFB2EBF2),
    secondary          = Color(0xFF80DEEA),
    onSecondary        = Color(0xFF003E47),
    secondaryContainer = Color(0xFF004F5A),
    onSecondaryContainer = Color(0xFFB2EBF2),
    tertiary           = Color(0xFF80CBC4),
    onTertiary         = Color(0xFF003735),
    background         = Surface0,
    onBackground       = Color(0xFFE2E8F0),
    surface            = Surface1,
    onSurface          = Color(0xFFE2E8F0),
    surfaceVariant     = Surface2,
    onSurfaceVariant   = Color(0xFF8B9BB4),
    outline            = Color(0xFF334155),
    outlineVariant     = Color(0xFF1E293B),
    error              = Color(0xFFFF6B6B),
    onError            = Color(0xFF690005),
    errorContainer     = Color(0xFF4B0000),
    onErrorContainer   = Color(0xFFFFDAD6),
    inverseSurface     = Color(0xFFE2E8F0),
    inverseOnSurface   = Surface1,
    inversePrimary     = Teal800,
    surfaceTint        = Teal400
)

private val LightScheme = lightColorScheme(
    primary            = Teal800,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFB2EBF2),
    onPrimaryContainer = Teal900,
    secondary          = Color(0xFF00838F),
    onSecondary        = Color.White,
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF002B30),
    background         = Color(0xFFF8FAFC),
    onBackground       = Color(0xFF0F172A),
    surface            = Color.White,
    onSurface          = Color(0xFF0F172A),
    surfaceVariant     = Color(0xFFEEF2FF),
    onSurfaceVariant   = Color(0xFF475569),
    outline            = Color(0xFFCBD5E1),
    outlineVariant     = Color(0xFFE2E8F0),
    error              = Color(0xFFDC2626),
    onError            = Color.White,
    inverseSurface     = Color(0xFF1E293B),
    inverseOnSurface   = Color(0xFFE2E8F0),
    inversePrimary     = Teal400
)

@Composable
fun SenderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography  = Typography(),
        content     = content
    )
}
