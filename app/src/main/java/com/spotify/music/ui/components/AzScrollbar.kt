package com.spotify.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AZ_CHARS = ('A'..'Z').toList() + listOf('#')

/**
 * A-Z 侧边快速定位条（歌曲/文件夹列表右侧）。
 *
 * 交互（参考 Samsung Music）：
 *  - 按下即显示浮动大字气泡并跳到该字母（[onHoverLetter] 上报当前字母）
 *  - 按住上下滑动实时切换字母，列表随之滚动
 *  - 松手气泡消失，列表停留在最后定位的字母
 *
 * [letterOf] 把列表项映射到字母；[onLetter] 返回该字母在列表中的首行索引。
 */
@Composable
fun AzScrollbar(
    modifier: Modifier = Modifier,
    enabledLetters: Set<Char>,
    onHoverLetter: (Char?) -> Unit = {},
    onLetter: (Char) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var activeIndex by remember { mutableStateOf(-1) }

    fun indexFromY(y: Float): Int {
        if (size.height <= 0) return -1
        val idx = (y / size.height * AZ_CHARS.size).toInt().coerceIn(0, AZ_CHARS.size - 1)
        return idx
    }

    Box(
        modifier
            .width(28.dp)
            .fillMaxHeight()
            .onSizeChanged { size = it }
            .pointerInput(enabledLetters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var idx = indexFromY(down.position.y)
                    if (idx >= 0) {
                        activeIndex = idx
                        onLetter(AZ_CHARS[idx])
                        onHoverLetter(AZ_CHARS[idx])
                    }
                    drag(down.id) { change ->
                        change.consume()
                        val i = indexFromY(change.position.y)
                        if (i != idx) {
                            idx = i
                            if (i >= 0) {
                                activeIndex = i
                                onLetter(AZ_CHARS[i])
                                onHoverLetter(AZ_CHARS[i])
                            }
                        }
                    }
                    activeIndex = -1
                    onHoverLetter(null)
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        // 蒙版直接长在字母列上（等宽 26dp + 圆角 + 半透明底）：
        // 不能用独立的蒙版 Box —— CenterEnd 会把窄字母列贴到屏幕最右缘，
        // 表现为字母错位/被裁（上一版踩坑）。
        Column(
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF8A8A94).copy(alpha = 0.12f))
                .clipToBounds()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 字母均布整条高度：手指位置 → 字母的映射与视觉对齐
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            AZ_CHARS.forEachIndexed { i, c ->
                val enabled = enabledLetters.contains(c)
                Text(
                    text = c.lowercaseChar().toString(),
                    fontSize = if (i == activeIndex) 11.sp else 9.sp,
                    fontWeight = if (i == activeIndex) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == activeIndex) {
                        Color(0xFF7A7FD4)
                    } else {
                        Color(0xFF9A9AA5).copy(alpha = if (enabled) 0.9f else 0.3f)
                    },
                    modifier = Modifier.padding(vertical = 0.5.dp),
                )
            }
        }
    }
}

/**
 * 浮动大字气泡：按下/滑动 A-Z 条时显示当前字母。
 * 样式参考 Samsung Music：淡紫圆底 + 白色大写字母，居中浮于列表之上。
 */
@Composable
fun AzBubble(
    letter: Char,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color(0xFFA5A6F6)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

fun letterOfText(text: String): Char = com.spotify.music.data.pinyinInitial(text)
