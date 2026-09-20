package com.spotify.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.spotify.music.R
import com.spotify.music.ui.theme.SamsungBlueDark
import kotlinx.coroutines.launch

/**
 * 底部迷你播放条：展开条 ↔ 唱片 之间的连续形变。
 * progress p: 0=展开条, 1=唱片。容器宽/高/圆角/颜色随 p 插值；
 * 展开内容以固定全宽锚在右侧，容器变窄时裁剪从左侧把控件一点点
 * “吃”进圆环（收起），反向即“吐”出（展开）。
 * 按住往右拖直接驱动 p；松手过阈值收起，否则弹回。
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
    if (songPath == null) return
    val progress = remember { Animatable(if (expanded) 0f else 1f) }
    LaunchedEffect(expanded) {
        progress.animateTo(if (expanded) 0f else 1f, tween(380, easing = FastOutSlowInEasing))
    }
    val scope = rememberCoroutineScope()
    var dragPx by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { 180.dp.toPx() }

    fun settle() {
        if (dragPx > thresholdPx * 0.45f) onCollapse()
        else scope.launch { progress.animateTo(0f, tween(220)) }
        dragPx = 0f
    }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
        val fullW = maxWidth
        val p = progress.value
        val barW = fullW - 20.dp
        val w = lerp(barW, 52.dp, p)
        val h = lerp(64.dp, 52.dp, p)
        val corner = lerp(18.dp, 26.dp, p)
        val padEnd = lerp(10.dp, 14.dp, p)
        val padBottom = lerp(6.dp, 10.dp, p)
        Box(
            modifier = Modifier
                .padding(end = padEnd, bottom = padBottom)
                .width(w)
                .height(h)
                .clip(RoundedCornerShape(corner))
                .background(lerpColor(SamsungBlueDark, Color(0xFF2A2A32), p))
                // 展开态装右滑手势；收起态点击由外层覆盖层处理（见下）。
                // pointerInput(expanded) 保证回调拿到最新状态。
                .pointerInput(expanded) {
                    if (expanded) {
                        detectHorizontalDragGestures(
                            onDragEnd = { settle() },
                            onDragCancel = { dragPx = 0f; scope.launch { progress.animateTo(0f, tween(220)) } },
                        ) { change, amount ->
                            change.consume()
                            dragPx = (dragPx + amount).coerceAtLeast(0f)
                            scope.launch {
                                progress.stop()
                                progress.snapTo((dragPx / thresholdPx).coerceIn(0f, 1f))
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            // 展开内容：固定全宽、右缘对齐容器，容器收窄时左端（控制按钮）先被裁掉。
            // p≥1 时完全不组合：alpha(0f) 不阻断命中测试，幽灵控件会拦走唱片上的点击。
            if (p < 0.999f) Row(
                Modifier
                    .width(barW)
                    .fillMaxHeight()
                    .alpha(1f - p)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clickable { onOpenPlayer() }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.padding(end = 10.dp),
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
            // 唱片面：播放时封面慢速连续旋转，暂停时停在当前角度；外环和中心孔不动。
            if (p > 0.001f) {
                val rotationAnim = remember(songPath) { Animatable(0f) }
                LaunchedEffect(isPlaying, songPath) {
                    while (isPlaying) {
                        val start = rotationAnim.value
                        rotationAnim.animateTo(
                            targetValue = start + 360f,
                            animationSpec = tween(8000, easing = LinearEasing),
                        )
                    }
                }
                Box(Modifier.fillMaxSize().alpha(p), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .graphicsLayer { rotationZ = rotationAnim.value }
                            .clip(CircleShape),
                    ) {
                        AlbumArt(
                            path = songPath,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = { m ->
                                Box(
                                    m.clip(CircleShape).background(Color(0xFF5C64B8)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                                }
                            },
                        )
                    }
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A32)),
                    )
                }
            }
            // 收起态点击层：52dp 方形命中区，接管“点唱片展开”
            if (p > 0.999f) Box(Modifier.fillMaxSize().clickable { onExpand() })
        }
    }
}
