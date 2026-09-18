package com.spotify.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp

/**
 * 自绘图标（对齐 UI 截图风格：细线、圆头）。全部使用 Canvas，避免 ImageVector API 兼容问题。
 */

/** 播放列表（三横线 + 音符）图标 */
@Composable
fun PlaylistNoteIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 26) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.26f), Offset(w * 0.44f, h * 0.26f), w * 0.08f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.48f), Offset(w * 0.44f, h * 0.48f), w * 0.08f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.70f), Offset(w * 0.44f, h * 0.70f), w * 0.08f, StrokeCap.Round)
        // 音符
        drawLine(tint, Offset(w * 0.62f, h * 0.70f), Offset(w * 0.62f, h * 0.24f), w * 0.08f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.62f, h * 0.24f), Offset(w * 0.90f, h * 0.17f), w * 0.08f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.90f, h * 0.17f), Offset(w * 0.90f, h * 0.60f), w * 0.08f, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.115f, center = Offset(w * 0.50f, h * 0.735f))
        drawCircle(tint, radius = w * 0.115f, center = Offset(w * 0.785f, h * 0.635f))
    }
}

/** 音量图标 */
@Composable
fun VolumeIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 24) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.40f)
            lineTo(w * 0.28f, h * 0.40f)
            lineTo(w * 0.48f, h * 0.22f)
            lineTo(w * 0.48f, h * 0.78f)
            lineTo(w * 0.28f, h * 0.60f)
            lineTo(w * 0.10f, h * 0.60f)
            close()
        }
        drawPath(path, color = tint, style = stroke)
        drawArc(
            color = tint, startAngle = -55f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(w * 0.42f, h * 0.32f), size = Size(w * 0.30f, h * 0.36f), style = stroke,
        )
        drawArc(
            color = tint, startAngle = -55f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(w * 0.40f, h * 0.20f), size = Size(w * 0.48f, h * 0.60f), style = stroke,
        )
    }
}

/** 均衡器（柱状）图标 */
@Composable
fun EqualizerIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 24) {
    Canvas(modifier.size(size.dp)) {
        val h = this.size.height
        val w = this.size.width
        val barW = w * 0.12f
        val heights = listOf(0.52f, 0.80f, 0.40f, 0.64f)
        heights.forEachIndexed { i, ratio ->
            val x = w * (0.14f + i * 0.22f)
            val bh = h * ratio
            drawRoundRect(
                color = tint,
                topLeft = Offset(x, (h - bh) / 2f),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** "A" 歌词开关图标（截图右下角的 A+虚线箭头样式） */
@Composable
fun LyricsToggleIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 24, active: Boolean = false) {
    Box(modifier.size(size.dp)) {
        androidx.compose.material3.Text(
            text = "A",
            color = tint,
            fontSize = TextUnit(size * 0.62f, TextUnitType.Sp),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
        Canvas(Modifier.size(size.dp)) {
            val w = this.size.width
            val h = this.size.height
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, h * 0.82f),
                end = Offset(w * 0.74f, h * 0.82f),
                strokeWidth = w * 0.055f,
                cap = StrokeCap.Round,
                pathEffect = if (active) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
            drawLine(tint, Offset(w * 0.74f, h * 0.82f), Offset(w * 0.64f, h * 0.72f), w * 0.055f, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.74f, h * 0.82f), Offset(w * 0.64f, h * 0.92f), w * 0.055f, StrokeCap.Round)
        }
    }
}

/** 排序图标（三横线 + 小音符，截图排序条左侧） */
@Composable
fun SortIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 22) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        drawLine(tint, Offset(w * 0.08f, h * 0.22f), Offset(w * 0.58f, h * 0.22f), w * 0.08f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.08f, h * 0.48f), Offset(w * 0.44f, h * 0.48f), w * 0.08f, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.095f, center = Offset(w * 0.24f, h * 0.78f))
        drawLine(tint, Offset(w * 0.335f, h * 0.76f), Offset(w * 0.335f, h * 0.36f), w * 0.07f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.335f, h * 0.36f), Offset(w * 0.52f, h * 0.28f), w * 0.07f, StrokeCap.Round)
    }
}

/** 折叠箭头（V 形） */
@Composable
fun ChevronDownIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 26) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        drawLine(tint, Offset(w * 0.15f, h * 0.35f), Offset(w * 0.5f, h * 0.68f), w * 0.09f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.85f, h * 0.35f), Offset(w * 0.5f, h * 0.68f), w * 0.09f, StrokeCap.Round)
    }
}

/** 左返回箭头 */
@Composable
fun ChevronLeftIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 26) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        drawLine(tint, Offset(w * 0.62f, h * 0.15f), Offset(w * 0.32f, h * 0.5f), w * 0.09f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.32f, h * 0.5f), Offset(w * 0.62f, h * 0.85f), w * 0.09f, StrokeCap.Round)
    }
}

/** 文件夹小图标（列表项封面右上角的小角标） */
@Composable
fun FolderBadgeIcon(modifier: Modifier = Modifier, tint: Color, size: Int = 16) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(
            Path().apply {
                moveTo(w * 0.08f, h * 0.24f)
                lineTo(w * 0.38f, h * 0.24f)
                lineTo(w * 0.46f, h * 0.36f)
                lineTo(w * 0.92f, h * 0.36f)
                lineTo(w * 0.92f, h * 0.80f)
                lineTo(w * 0.08f, h * 0.80f)
                close()
            },
            color = tint, style = stroke,
        )
    }
}

private fun rectOf(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)
