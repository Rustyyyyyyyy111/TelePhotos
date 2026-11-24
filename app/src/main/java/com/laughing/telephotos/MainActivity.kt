package com.laughing.telephotos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laughing.telephotos.ui.components.TelePhotosBackdrop
import com.laughing.telephotos.ui.navigation.TelePhotosApp
import com.laughing.telephotos.ui.theme.TelePhotosTheme
import com.laughing.telephotos.ui.viewmodel.TelePhotosViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val telePhotosViewModel: TelePhotosViewModel = viewModel()
      val settings by telePhotosViewModel.settings.collectAsStateWithLifecycle()

      TelePhotosTheme(
        themeMode = settings.themeMode,
        advancedBlurEnabled = settings.advancedBlurEnabled,
      ) {
        TelePhotosBackdrop {
          TelePhotosApp(viewModel = telePhotosViewModel)
        }
      }
    }
  }
}
