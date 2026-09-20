package com.spotify.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.ui.theme.SamsungBlueDark
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

/**
 * 底部迷你播放条。expanded=false 时收起为左下角唱片样式小圆封面
 * （显示当前歌曲封面），点击展开为完整条；展开状态由调用方持有。
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
    if (!expanded) {
        // 收起态：左下角唱片封面（黑胶外环 + 封面 + 中孔），点击展开。
        // ponytail: 只在歌曲页签 Box 内左下角，不遮挡列表末项太多；要拖拽/吸附再加。
        if (songPath == null) return
        Box(
            modifier = Modifier
                .padding(start = 14.dp, bottom = 10.dp)
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
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SamsungBlueDark)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenPlayer() },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, end = 8.dp),
                ) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 仅封面与标题区域可打开播放页；右侧控制按钮独立点击，避免队列图标被整条吞掉。
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
    }
}
