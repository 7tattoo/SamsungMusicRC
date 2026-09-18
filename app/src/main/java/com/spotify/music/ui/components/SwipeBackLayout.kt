package com.spotify.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 从左边缘右滑返回（iOS 风格）。
 *
 * 只在屏幕最左侧 [edgeWidth] 区域响应，避免和列表竖向滚动、进度条拖动冲突；
 * 拖动时内容跟随手指右移，背后露出阴影；松手超过 [threshold] 触发返回，否则回弹。
 */
@Composable
fun SwipeBackLayout(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    edgeWidth: Dp = 24.dp,
    threshold: Dp = 88.dp,
    content: @Composable () -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val thresholdPx = with(density) { threshold.toPx() }

    Box(modifier.fillMaxSize()) {
        // 拖动时露出的背景阴影（在内容之下）
        if (dragX > 0f) {
            val alpha = (0.38f * (1f - (dragX / (thresholdPx * 4f)).coerceAtMost(1f))).coerceIn(0f, 0.38f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)))
        }

        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(dragX.roundToInt(), 0) },
        ) {
            content()
        }

        if (enabled) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(edgeWidth)
                    .pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragX = 0f },
                            onDragEnd = {
                                if (dragX >= thresholdPx) onBack()
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f },
                            onHorizontalDrag = { _, dx ->
                                dragX = (dragX + dx).coerceAtLeast(0f)
                            },
                        )
                    },
            )
        }
    }
}
