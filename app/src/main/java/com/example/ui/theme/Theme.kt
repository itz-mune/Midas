package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = PrimaryD0BCFF,
    onPrimary = OnPrimary381E72,
    primaryContainer = PrimaryContainerEADDFF,
    onPrimaryContainer = OnPrimaryContainer21005D,
    background = Background1C1B1F,
    onBackground = TextE6E1E5,
    surface = Background1C1B1F,
    onSurface = TextE6E1E5,
    surfaceVariant = Surface2B2930,
    onSurfaceVariant = TextSecondaryCAC4D0,
    outline = Border49454F
  )

private val LightColorScheme = DarkColorScheme // Only providing dark theme to match design specs

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is intentionally bypassed to lock the specific Vibrant Palette
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
