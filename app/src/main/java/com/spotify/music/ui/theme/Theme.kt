package com.spotify.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Samsung Music 品牌色（依据 UI 截图取色）──
val SamsungBlue = Color(0xFF7A7FD4)          // 标题 / 选中 Tab / 强调
val SamsungBlueDark = Color(0xFF4A52A8)      // 迷你播放条
val LibraryBg = Color(0xFFF3F4F6)            // 资料库页面背景
val CardWhite = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF1B1B1F)
val TextSecondary = Color(0xFF83838C)
val AccentToggle = Color(0xFF8A90DE)         // 开关/滑块
val LyricHighlight = Color(0xFF8A93E8)       // 歌词当前行
val LyricNormal = Color(0xFF3A3A42)
val OrangeDot = Color(0xFFFF6D3B)            // 菜单上的小红点
val CircleBtnBg = Color(0xFFEBEBF0)
val DarkCircleBtnBg = Color(0xFF23262F)

// 播放页渐变兜底色
val GradientTopFallback = Color(0xFFE9EBF7)
val GradientBottomFallback = Color(0xFFAEB9DD)

val DarkBg = Color(0xFF15151A)
val DarkCard = Color(0xFF22222A)
val DarkTextPrimary = Color(0xFFECECF1)
val DarkTextSecondary = Color(0xFF9A9AA5)

private val LightColors = lightColorScheme(
    primary = SamsungBlue,
    onPrimary = Color.White,
    background = LibraryBg,
    surface = CardWhite,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    secondary = SamsungBlueDark,
    surfaceVariant = Color(0xFFECECF2),
    onSurfaceVariant = TextSecondary,
)

private val DarkColors = darkColorScheme(
    primary = SamsungBlue,
    onPrimary = Color.White,
    background = DarkBg,
    surface = DarkCard,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    secondary = SamsungBlue,
    surfaceVariant = Color(0xFF2C2C36),
    onSurfaceVariant = DarkTextSecondary,
)

@Composable
fun SamsungMusicTheme(
    darkModeSetting: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (darkModeSetting) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
