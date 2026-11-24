package com.laughing.telephotos.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.laughing.telephotos.ui.theme.GlassBackdropBottom
import com.laughing.telephotos.ui.theme.GlassBackdropMid
import com.laughing.telephotos.ui.theme.GlassBackdropTop
import com.laughing.telephotos.ui.theme.LocalTelePhotosTokens

@Composable
fun TelePhotosBackdrop(
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val tokens = LocalTelePhotosTokens.current
  val bgModifier = if (tokens.isGlass) {
    Modifier.background(
      Brush.verticalGradient(
        colors = listOf(GlassBackdropTop, GlassBackdropMid, GlassBackdropBottom),
      )
    )
  } else {
    Modifier.background(MaterialTheme.colorScheme.background)
  }

  Box(modifier = modifier.fillMaxSize().then(bgModifier)) {
    if (tokens.isGlass && tokens.advancedBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(220.dp)
          .blur(28.dp)
          .background(
            Brush.radialGradient(
              colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                Color.Transparent,
              )
            )
          )
      )
    }
    content()
  }
}

@Composable
fun TelePhotosGlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(18.dp),
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val tokens = LocalTelePhotosTokens.current
  val border = BorderStroke(1.dp, tokens.cardBorder)

  if (onClick != null) {
    Surface(
      onClick = onClick,
      modifier = modifier,
      shape = shape,
      color = tokens.cardSurface,
      border = border,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Column {
        if (tokens.isGlass) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .background(tokens.topEdgeSheen)
          )
        }
        content()
      }
    }
  } else {
    Surface(
      modifier = modifier,
      shape = shape,
      color = tokens.cardSurface,
      border = border,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Column {
        if (tokens.isGlass) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .background(tokens.topEdgeSheen)
          )
        }
        content()
      }
    }
  }
}
