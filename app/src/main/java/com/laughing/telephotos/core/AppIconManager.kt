package com.laughing.telephotos.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

enum class AppIconPreset(
  val labelVi: String,
  val descriptionVi: String,
  val aliasClassName: String,
) {
  DEFAULT(
    labelVi = "Cổ điển",
    descriptionVi = "Chong chóng 4 màu nền sáng",
    aliasClassName = "com.laughing.telephotos.LauncherIconDefault",
  ),
  DARK(
    labelVi = "Xanh Đêm",
    descriptionVi = "Chong chóng nổi bật nền tối",
    aliasClassName = "com.laughing.telephotos.LauncherIconDark",
  ),
  MONO(
    labelVi = "Telegram",
    descriptionVi = "Biểu tượng trắng trên nền xanh",
    aliasClassName = "com.laughing.telephotos.LauncherIconMono",
  ),
}

object AppIconManager {

  fun applyIconPreset(context: Context, preset: AppIconPreset) {
    val pm = context.packageManager
    val packageName = context.packageName

    val targetComponent = ComponentName(packageName, preset.aliasClassName)
    pm.setComponentEnabledSetting(
      targetComponent,
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
      PackageManager.DONT_KILL_APP,
    )

    for (candidate in AppIconPreset.entries) {
      if (candidate == preset) continue
      pm.setComponentEnabledSetting(
        ComponentName(packageName, candidate.aliasClassName),
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
      )
    }
  }
}
