package com.spotify.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.core.lyrics.LrcParser
import com.spotify.music.core.model.Lyrics
import com.spotify.music.ui.theme.LyricHighlight
import com.spotify.music.ui.theme.LyricNormal
import kotlinx.coroutines.delay

/**
 * 滚动歌词：带时间戳时按进度自动滚动，当前行高亮；点击行可跳转进度。
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    darkText: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()

    // 当前驱动行：用 snapshot polling（LyricsView 由调用方定期重组驱动）
    val pos = positionMs()
    val lineIndex = if (lyrics.hasTimestamps) LrcParser.findLineIndex(lyrics, pos) else -1

    LaunchedEffect(lineIndex) {
        if (lineIndex >= 0 && lyrics.lines.isNotEmpty()) {
            // 滚动使当前行居中
            val target = (lineIndex - 3).coerceAtLeast(0)
            runCatching {
                listState.animateScrollToItem(target.coerceAtMost(lyrics.lines.size - 1))
            }
        }
    }

    val normalColor = if (darkText) LyricNormal else LyricNormal
    val unitSp: (Float) -> TextUnit = { TextUnit(it * fontScale, TextUnitType.Sp) }

    if (!lyrics.hasTimestamps && lyrics.lines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词",
                color = normalColor.copy(alpha = 0.5f),
                fontSize = unitSp(15f),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lyrics.hasTimestamps) {
            item { Box(Modifier.fillMaxWidth().padding(vertical = 60.dp)) }
        }
        itemsIndexed(lyrics.lines) { i, line ->
            val isCurrent = lyrics.hasTimestamps && i == lineIndex
            Text(
                text = line.text,
                fontSize = unitSp(if (isCurrent) 17f else 15f),
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) LyricHighlight else normalColor,
                textAlign = TextAlign.Center,
                lineHeight = unitSp(28f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = lyrics.hasTimestamps && line.timeMs >= 0) {
                        onSeek(line.timeMs)
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (lyrics.hasTimestamps) {
            item { Box(Modifier.fillMaxWidth().padding(vertical = 120.dp)) }
        }
    }
}
