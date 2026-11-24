package com.laughing.telephotos.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.laughing.telephotos.R

val BeVietnamProFamily = FontFamily(
  Font(R.font.be_vietnam_pro, FontWeight.Normal),
  Font(R.font.be_vietnam_pro, FontWeight.Medium),
  Font(R.font.be_vietnam_pro, FontWeight.SemiBold),
  Font(R.font.be_vietnam_pro, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
  Font(R.font.jetbrains_mono, FontWeight.Normal),
  Font(R.font.jetbrains_mono, FontWeight.Medium),
)

val TelePhotosTypography = Typography(
  displaySmall = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
  ),
  headlineMedium = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
  ),
  titleLarge = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp,
    lineHeight = 25.sp,
  ),
  titleMedium = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
  ),
  titleSmall = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.5.sp,
    lineHeight = 19.sp,
  ),
  bodySmall = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
  ),
  labelMedium = TextStyle(
    fontFamily = BeVietnamProFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
  ),
  labelSmall = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
  ),
)
