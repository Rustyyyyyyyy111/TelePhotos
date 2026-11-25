package com.laughing.telephotos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laughing.telephotos.ui.components.TelePhotosBackdrop
import com.laughing.telephotos.ui.navigation.TelePhotosApp
import com.laughing.telephotos.ui.theme.TelePhotosTheme
import com.laughing.telephotos.ui.viewmodel.TelePhotosViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    val app = application as? TelePhotosApplication
    val isColdProcessLaunch = app != null && !app.hasInitializedUiInCurrentProcess
    // Discard stale saved navigation/UI state when reopened after process kill from Recents
    val effectiveSavedState = if (isColdProcessLaunch) null else savedInstanceState
    super.onCreate(effectiveSavedState)
    app?.hasInitializedUiInCurrentProcess = true

    enableEdgeToEdge()
    setContent {
      val telePhotosViewModel: TelePhotosViewModel = viewModel()
      val settings by telePhotosViewModel.settings.collectAsStateWithLifecycle()

      LaunchedEffect(isColdProcessLaunch) {
        if (isColdProcessLaunch || effectiveSavedState == null) {
          telePhotosViewModel.clearSelection()
        }
      }

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

  override fun onDestroy() {
    if (isFinishing && !isChangingConfigurations) {
      (application as? TelePhotosApplication)?.hasInitializedUiInCurrentProcess = false
    }
    super.onDestroy()
  }
}
