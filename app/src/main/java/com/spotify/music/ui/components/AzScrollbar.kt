package com.spotify.music.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val AZ_CHARS = ('A'..'Z').toList() + listOf('#')

/**
 * A-Z 侧边快速定位条（歌曲/文件夹列表右侧）。
 * [letterOf] 把列表项映射到字母；[onLetter] 返回该字母在列表中的首行索引。
 */
@Composable
fun AzScrollbar(
    modifier: Modifier = Modifier,
    enabledLetters: Set<Char>,
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
                detectTapGestures { offset ->
                    val idx = indexFromY(offset.y)
                    if (idx >= 0) {
                        activeIndex = idx
                        onLetter(AZ_CHARS[idx])
                    }
                }
            }
            .pointerInput(enabledLetters) {
                detectDragGestures(
                    onDragStart = { offset -> activeIndex = indexFromY(offset.y) },
                ) { change, _ ->
                    val idx = indexFromY(change.position.y)
                    if (idx != activeIndex) {
                        activeIndex = idx
                        if (idx >= 0) onLetter(AZ_CHARS[idx])
                    }
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .clipToBounds()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AZ_CHARS.forEachIndexed { i, c ->
                val enabled = enabledLetters.contains(c)
                Text(
                    text = c.toString(),
                    fontSize = 8.sp,
                    fontWeight = if (i == activeIndex) FontWeight.Bold else FontWeight.Normal,
                    color = androidx.compose.ui.graphics.Color(
                        0xFF3A3A42
                    ).copy(alpha = if (enabled) 0.85f else 0.25f),
                    modifier = Modifier.padding(vertical = 0.4.dp),
                )
            }
        }
    }
}

fun letterOfText(text: String): Char {
    val first = text.trim().firstOrNull() ?: return '#'
    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper else '#'
}
