package br.com.bebidaspdv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8F768), onPrimary = Color(0xFF132300),
    primaryContainer = Color(0xFF2B401B), onPrimaryContainer = Color(0xFFD3FFAA),
    secondary = Color(0xFFB9CBB0), tertiary = Color(0xFFFFBC86),
    background = Color(0xFF10140F), surface = Color(0xFF10140F),
    surfaceVariant = Color(0xFF30382C), onSurface = Color(0xFFE8EDDF),
    onSurfaceVariant = Color(0xFFBEC8B5), outline = Color(0xFF899480),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF386800), onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F768), onPrimaryContainer = Color(0xFF102000),
    secondary = Color(0xFF53634A), tertiary = Color(0xFF855322),
    background = Color(0xFFF6F9EF), surface = Color(0xFFF6F9EF),
    surfaceVariant = Color(0xFFE0E8D5), onSurface = Color(0xFF192014),
    onSurfaceVariant = Color(0xFF434C3B),
)

@Composable
fun BebidasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(), content = content,
    )
}
