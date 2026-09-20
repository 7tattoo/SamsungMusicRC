package com.spotify.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.spotify.music.R
import com.spotify.music.ui.theme.SamsungBlueDark

/**
 * 底部迷你播放条。expanded=false 收起为右下角唱片封面（点击展开）；
 * 展开条控制按钮靠左、封面靠右，按住往右滑超过阈值收起为唱片。
 * 收起/展开切换带 fade+scale 过渡动画。
 */
@Composable
fun MiniPlayer(
    songPath: String?,
    title: String,
    artist: String,
    isPlaying: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            (fadeIn(tween(200)) + scaleIn(initialScale = 0.85f, animationSpec = tween(200))) togetherWith
                (fadeOut(tween(150)) + scaleOut(targetScale = 0.85f, animationSpec = tween(150)))
        },
        label = "miniPlayer",
    ) { exp ->
        if (!exp) {
            CollapsedDisc(songPath, onExpand)
        } else {
            ExpandedBar(
                songPath = songPath,
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                onCollapse = onCollapse,
                onToggle = onToggle,
                onNext = onNext,
                onPrev = onPrev,
                onOpenQueue = onOpenQueue,
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

/** 收起态：右下角唱片（黑胶外环 + 封面 + 中孔），点击展开。 */
@Composable
private fun CollapsedDisc(songPath: String?, onExpand: () -> Unit) {
    if (songPath == null) return
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 14.dp, bottom = 10.dp)
                .size(52.dp)
                .clickable { onExpand() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A32)),
            )
            AlbumArt(
                path = songPath,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape),
                placeholder = { m ->
                    Box(
                        m.clip(CircleShape).background(Color(0xFF5C64B8)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                    }
                },
            )
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A32)),
            )
        }
    }
}

/** 展开态：控制按钮在左、封面+标题靠右；按住往右滑收起（拖动跟手平移）。 */
@Composable
private fun ExpandedBar(
    songPath: String?,
    title: String,
    artist: String,
    isPlaying: Boolean,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .graphicsLayer { translationX = dragX }
            .clip(RoundedCornerShape(18.dp))
            .background(SamsungBlueDark)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX > threshold) onCollapse()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    dragX = (dragX + dragAmount).coerceAtLeast(0f)
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 控制按钮靠左
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.prev),
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { onPrev() },
            )
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.play_pause),
                tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onToggle() },
            )
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.next),
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { onNext() },
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.collapse),
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onCollapse() },
            )
            PlaylistNoteIcon(
                tint = Color.White,
                size = 24,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable { onOpenQueue() },
            )
        }
        // 封面+标题靠右（仅此区域点击打开播放页）
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenPlayer() },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 10.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
                AlbumArt(
                    path = songPath,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    placeholder = { m ->
                        Box(
                            m.clip(RoundedCornerShape(8.dp)).background(Color(0xFF5C64B8)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        }
                    },
                )
            }
        }
    }
}
