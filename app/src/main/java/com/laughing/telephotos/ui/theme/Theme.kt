package com.laughing.telephotos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.laughing.telephotos.core.ThemeMode

private val TeleLightColorScheme = lightColorScheme(
  primary = OceanPrimaryLight,
  onPrimary = OceanOnPrimaryLight,
  primaryContainer = OceanPrimaryContainerLight,
  onPrimaryContainer = OceanOnPrimaryContainerLight,
  secondary = TealSecondaryLight,
  onSecondary = TealOnSecondaryLight,
  secondaryContainer = TealSecondaryContainerLight,
  onSecondaryContainer = TealOnSecondaryContainerLight,
  tertiary = AmberTertiaryLight,
  tertiaryContainer = AmberContainerLight,
  onTertiaryContainer = OnAmberContainerLight,
  background = LightBackground,
  onBackground = LightTextPrimary,
  surface = LightSurface,
  onSurface = LightTextPrimary,
  surfaceVariant = LightSurfaceVariant,
  onSurfaceVariant = LightTextSecondary,
  outline = LightOutline,
  outlineVariant = LightOutline,
  error = StatusErrorRed,
)

private val TeleDarkColorScheme = darkColorScheme(
  primary = OceanPrimaryDark,
  onPrimary = OceanOnPrimaryDark,
  primaryContainer = OceanPrimaryContainerDark,
  onPrimaryContainer = OceanOnPrimaryContainerDark,
  secondary = TealSecondaryDark,
  onSecondary = TealOnSecondaryDark,
  secondaryContainer = TealSecondaryContainerDark,
  onSecondaryContainer = TealOnSecondaryContainerDark,
  tertiary = AmberTertiaryLight,
  tertiaryContainer = Color(0xFF3E2708),
  onTertiaryContainer = Color(0xFFFDE68A),
  background = DarkBackground,
  onBackground = DarkTextPrimary,
  surface = DarkSurface,
  onSurface = DarkTextPrimary,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = DarkTextSecondary,
  outline = DarkOutline,
  outlineVariant = DarkOutline,
  error = Color(0xFFF87171),
)

private val TeleLiquidGlassColorScheme = lightColorScheme(
  primary = OceanPrimaryLight,
  onPrimary = OceanOnPrimaryLight,
  primaryContainer = Color(0xCCDBEAFE),
  onPrimaryContainer = OceanOnPrimaryContainerLight,
  secondary = TealSecondaryLight,
  onSecondary = TealOnSecondaryLight,
  secondaryContainer = Color(0xB8CCFBF1),
  onSecondaryContainer = TealOnSecondaryContainerLight,
  tertiary = AmberTertiaryLight,
  tertiaryContainer = Color(0xCCFEF3C7),
  onTertiaryContainer = OnAmberContainerLight,
  background = GlassBackdropMid,
  onBackground = LightTextPrimary,
  surface = GlassSurfaceFill,
  onSurface = LightTextPrimary,
  surfaceVariant = Color(0xA6E0EDF8),
  onSurfaceVariant = LightTextSecondary,
  outline = GlassSubtleBorder,
  outlineVariant = GlassRimHighlight,
  error = StatusErrorRed,
)

@Immutable
data class TelePhotosVisualTokens(
  val themeMode: ThemeMode,
  val isGlass: Boolean,
  val isDark: Boolean,
  val advancedBlurEnabled: Boolean,
  val cardSurface: Color,
  val cardBorder: Color,
  val topEdgeSheen: Color,
)

val LocalTelePhotosTokens = staticCompositionLocalOf {
  TelePhotosVisualTokens(
    themeMode = ThemeMode.LIGHT,
    isGlass = false,
    isDark = false,
    advancedBlurEnabled = false,
    cardSurface = LightSurface,
    cardBorder = LightOutline,
    topEdgeSheen = Color.Transparent,
  )
}

@Composable
fun TelePhotosTheme(
  themeMode: ThemeMode = ThemeMode.LIGHT,
  advancedBlurEnabled: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = when (themeMode) {
    ThemeMode.LIGHT -> TeleLightColorScheme
    ThemeMode.DARK -> TeleDarkColorScheme
    ThemeMode.LIQUID_GLASS -> TeleLiquidGlassColorScheme
  }

  val visualTokens = when (themeMode) {
    ThemeMode.LIGHT -> TelePhotosVisualTokens(
      themeMode = ThemeMode.LIGHT,
      isGlass = false,
      isDark = false,
      advancedBlurEnabled = false,
      cardSurface = LightSurface,
      cardBorder = LightOutline,
      topEdgeSheen = Color.Transparent,
    )
    ThemeMode.DARK -> TelePhotosVisualTokens(
      themeMode = ThemeMode.DARK,
      isGlass = false,
      isDark = true,
      advancedBlurEnabled = false,
      cardSurface = DarkSurface,
      cardBorder = DarkOutline,
      topEdgeSheen = Color(0x14FFFFFF),
    )
    ThemeMode.LIQUID_GLASS -> TelePhotosVisualTokens(
      themeMode = ThemeMode.LIQUID_GLASS,
      isGlass = true,
      isDark = false,
      advancedBlurEnabled = advancedBlurEnabled,
      cardSurface = GlassSurfaceFill,
      cardBorder = GlassRimHighlight,
      topEdgeSheen = GlassTopSheen,
    )
  }

  CompositionLocalProvider(LocalTelePhotosTokens provides visualTokens) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = TelePhotosTypography,
      content = content,
    )
  }
}
