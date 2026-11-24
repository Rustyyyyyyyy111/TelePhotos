package com.laughing.telephotos.core

/**
 * Supported visual theme modes for Tele Photos.
 */
enum class ThemeMode(val labelVi: String, val descriptionVi: String) {
  LIGHT(
    labelVi = "Sáng",
    descriptionVi = "Nền sáng tinh gọn, viền mảnh 1dp, phản hồi nhanh",
  ),
  DARK(
    labelVi = "Tối",
    descriptionVi = "Nền tối dịu mắt, tiết kiệm pin màn hình OLED",
  ),
  LIQUID_GLASS(
    labelVi = "Liquid Glass",
    descriptionVi = "Bề mặt kính mờ nhẹ nhàng với viền sáng 1dp tối ưu cho máy cấu hình thấp",
  ),
}
