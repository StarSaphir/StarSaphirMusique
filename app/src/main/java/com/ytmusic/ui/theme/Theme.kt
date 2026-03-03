package com.ytmusic.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary        = Color(0xFFBB86FC),
    onPrimary      = Color(0xFF000000),
    secondary      = Color(0xFF03DAC6),
    onSecondary    = Color(0xFF000000),
    background     = Color(0xFF121212),
    onBackground   = Color(0xFFE0E0E0),
    surface        = Color(0xFF1E1E1E),
    onSurface      = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2C2C2C),
    error          = Color(0xFFCF6679),
)

@Composable
fun YTMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}
