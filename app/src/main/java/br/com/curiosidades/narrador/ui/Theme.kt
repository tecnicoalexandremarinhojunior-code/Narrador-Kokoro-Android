package br.com.curiosidades.narrador.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF9FB4FF), onPrimary = Color(0xFF0F1B4D),
    secondaryContainer = Color(0xFF2A3157), onSecondaryContainer = Color(0xFFDDE3FF),
    background = Color(0xFF0F1220), surface = Color(0xFF171B2E),
    surfaceVariant = Color(0xFF232842), onSurfaceVariant = Color(0xFFB7BCD6)
)
private val Light = lightColorScheme(
    primary = Color(0xFF3F51B5), onPrimary = Color.White,
    secondaryContainer = Color(0xFFDDE3FF), onSecondaryContainer = Color(0xFF1B2140),
    background = Color(0xFFF6F7FB), surface = Color.White,
    surfaceVariant = Color(0xFFE9EBF5), onSurfaceVariant = Color(0xFF4A4E66)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
