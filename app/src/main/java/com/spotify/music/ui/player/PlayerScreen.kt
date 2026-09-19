package com.spotify.music.ui.player

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.core.lyrics.LyricsLoader
import com.spotify.music.core.model.Song
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.playback.PlaybackClient
import com.spotify.music.ui.PlayerUiState
import com.spotify.music.ui.components.AlbumArt
import com.spotify.music.ui.components.EqualizerIcon
import com.spotify.music.ui.components.LyricsToggleIcon
import com.spotify.music.ui.components.LyricsView
import com.spotify.music.ui.components.PlaylistNoteIcon
import com.spotify.music.ui.components.VolumeIcon
import com.spotify.music.ui.theme.GradientBottomFallback
import com.spotify.music.ui.theme.GradientTopFallback
import com.spotify.music.ui.theme.LyricHighlight
import com.spotify.music.ui.theme.OrangeDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 播放页：竖屏（手机）/ 横屏（车机投屏）双布局，按长宽比自动切换。
 */
@Composable
fun PlayerScreen(
    uiState: PlayerUiState,
    client: PlaybackClient,
    settings: SettingsRepository,
    library: LibraryRepository,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onBrowseAlbum: (String) -> Unit,
    onBrowseArtist: (String) -> Unit,
    showQueue: Boolean = false,
) {
    val song = uiState.currentSong
    var lyrics by remember(song?.path) { mutableStateOf(com.spotify.music.core.model.Lyrics.EMPTY) }
    var gradientTop by remember(song?.path) { mutableStateOf(GradientTopFallback) }
    var gradientBottom by remember(song?.path) { mutableStateOf(GradientBottomFallback) }

    LaunchedEffect(song?.path) {
        val p = song?.path ?: return@LaunchedEffect
        var loaded = withContext(Dispatchers.IO) { LyricsLoader.load(p) }
        // Mounted storage and service metadata can become available a moment after
        // the player page. Retry empty reads instead of permanently showing no lyrics.
        repeat(3) { attempt ->
            if (loaded.lines.isNotEmpty()) return@repeat
            delay(180L * (attempt + 1))
            loaded = withContext(Dispatchers.IO) { LyricsLoader.load(p) }
        }
        lyrics = loaded
        val pair = withContext(Dispatchers.IO) { extractGradientColors(p) }
        gradientTop = pair.first
        gradientBottom = pair.second
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(gradientTop, gradientBottom))
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight
            if (isLandscape) {
                LandscapePlayer(uiState, client, settings, library, song, lyrics, onBack, onOpenSettings, onOpenEqualizer, onBrowseAlbum, onBrowseArtist, showQueue = uiState.showQueue)
            } else {
                PortraitPlayer(uiState, client, settings, library, song, lyrics, onBack, onOpenSettings, onOpenEqualizer, onBrowseAlbum, onBrowseArtist, showQueue = uiState.showQueue)
            }
        }
    }
}

// ─────────────────────────── 竖屏布局 ───────────────────────────

@Composable
private fun PortraitPlayer(
    uiState: PlayerUiState,
    client: PlaybackClient,
    settings: SettingsRepository,
    library: LibraryRepository,
    song: Song?,
    lyrics: com.spotify.music.core.model.Lyrics,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onBrowseAlbum: (String) -> Unit,
    onBrowseArtist: (String) -> Unit,
    showQueue: Boolean = false,
) {
    var showLyrics by remember { mutableStateOf(false) }

    // 歌词页的系统返回/侧滑：回播放页（封面），而不是退出播放器
    androidx.activity.compose.BackHandler(enabled = showLyrics) { showLyrics = false }

    com.spotify.music.ui.components.SwipeBackLayout(
        enabled = true,
        onBack = { if (showLyrics) showLyrics = false else onBack() },
        edgeWidth = 32.dp,
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        PlayerTopBar(
            onBack = { if (showLyrics) showLyrics = false else onBack() },
            onOpenSettings = onOpenSettings,
            onOpenEqualizer = onOpenEqualizer,
            onBrowseAlbum = onBrowseAlbum,
            onBrowseArtist = onBrowseArtist,
            uiState = uiState,
            song = song,
            library = library,
        )

        // fillMaxWidth 必须加：Column 里 weight 只撑高度，横向会收缩成内容宽度贴左
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (showLyrics) {
                LyricsView(
                    lyrics = lyrics,
                    positionMs = { uiState.positionMs },
                    modifier = Modifier.fillMaxSize(),
                    onSeek = { client.seekTo(it) },
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AlbumArt(
                        path = song?.path,
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .aspectRatio(1f)
                            .shadow(12.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showLyrics = true },
                        placeholder = { m ->
                            Box(
                                m.background(Color(0xFFDBDDEA)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote, contentDescription = null,
                                    tint = Color(0xFF9AA0BF),
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        },
                    )
                }
            }
        }

        if (!showLyrics) {
            Text(
                song?.title ?: "未在播放",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1B1B1F),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                song?.artist ?: "",
                fontSize = 13.sp,
                color = Color(0xFF5B5B66),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        // 图标行：播放列表 / 收藏 / 添加
        IconActionRow(
            uiState = uiState,
            settings = settings,
            client = client,
            library = library,
            song = song,
            tint = Color(0xFF33333C),
        )

        Spacer(Modifier.height(8.dp))

        SeekSlider(
            positionMs = uiState.positionMs,
            durationMs = uiState.durationMs,
            onSeek = { client.seekTo(it) },
            tint = Color(0xFF3A3F4B),
        )

        Spacer(Modifier.height(2.dp))

        TransportControls(
            uiState = uiState,
            client = client,
            tint = Color(0xFF202028),
            showLyrics = showLyrics,
            onToggleLyrics = { showLyrics = !showLyrics },
        )

        Spacer(Modifier.height(14.dp))
    }
    }
}

// ─────────────────────────── 横屏布局（车机） ───────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LandscapePlayer(
    uiState: PlayerUiState,
    client: PlaybackClient,
    settings: SettingsRepository,
    library: LibraryRepository,
    song: Song?,
    lyrics: com.spotify.music.core.model.Lyrics,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onBrowseAlbum: (String) -> Unit,
    onBrowseArtist: (String) -> Unit,
    showQueue: Boolean = false,
) {
    var showLyrics by remember { mutableStateOf(false) }
    var lyricScaleIdx by remember { mutableIntStateOf(0) }
    val lyricScales = floatArrayOf(1f, 1.25f, 1.5f)

    // 歌词页的系统返回/侧滑：回播放页（封面），而不是退出播放器
    androidx.activity.compose.BackHandler(enabled = showLyrics) { showLyrics = false }

    com.spotify.music.ui.components.SwipeBackLayout(
        enabled = true,
        onBack = { if (showLyrics) showLyrics = false else onBack() },
        edgeWidth = 32.dp,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 420.dp || maxWidth < 720.dp
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 2.dp),
        ) {
        PlayerTopBar(
            onBack = { if (showLyrics) showLyrics = false else onBack() },
            onOpenSettings = onOpenSettings,
            onOpenEqualizer = onOpenEqualizer,
            onBrowseAlbum = onBrowseAlbum,
            onBrowseArtist = onBrowseArtist,
            uiState = uiState,
            song = song,
            library = library,
        )

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 左侧：封面 / 歌词（封面尺寸按可用高度自适应，避免车机低分辨率溢出）
            Box(
                Modifier
                    .weight(if (compact) 0.54f else 0.42f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (showLyrics) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "x${lyricScales[lyricScaleIdx].toString().removeSuffix(".0")}",
                                fontSize = 12.sp,
                                color = Color(0xFF202028),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.55f))
                                    .clickable { lyricScaleIdx = (lyricScaleIdx + 1) % lyricScales.size }
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                        LyricsView(
                            lyrics = lyrics,
                            positionMs = { uiState.positionMs },
                            modifier = Modifier.fillMaxSize(),
                            fontScale = lyricScales[lyricScaleIdx],
                            onSeek = { client.seekTo(it) },
                        )
                    }
                } else {
                    AlbumArt(
                        path = song?.path,
                        modifier = Modifier
                            .fillMaxHeight(if (compact) 0.78f else 0.88f)
                            .aspectRatio(1f)
                            .shadow(14.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showLyrics = true },
                        placeholder = { m ->
                            Box(
                                m
                                    .background(Color(0xFFDBDDEA))
                                    .clickable { showLyrics = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote, contentDescription = null,
                                    tint = Color(0xFF9AA0BF),
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.width(if (compact) 8.dp else 20.dp))

            // 右侧：标题 / 图标行 / 进度 / 控制（全部可压缩，保证不被裁掉）
            Column(
                Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    song?.title ?: "未在播放",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B1B1F),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                )
                Text(
                    song?.artist ?: "",
                    fontSize = 12.sp,
                    color = Color(0xFF5B5B66),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.weight(1f))

                IconActionRow(
                    uiState = uiState,
                    settings = settings,
                    client = client,
                    library = library,
                    song = song,
                    tint = Color(0xFF33333C),
                )
                Spacer(Modifier.weight(0.5f))

                SeekSlider(
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    onSeek = { client.seekTo(it) },
                    tint = Color(0xFF3A3F4B),
                )
                Spacer(Modifier.height(6.dp))

                TransportControls(
                    uiState = uiState,
                    client = client,
                    tint = Color(0xFF202028),
                    compact = true,
                    showLyrics = showLyrics,
                    onToggleLyrics = {
                        showLyrics = !showLyrics
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
    }
    }
}

// ─────────────────────────── 公共部件 ───────────────────────────

@Composable
private fun PlayerTopBar(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onBrowseAlbum: (String) -> Unit,
    onBrowseArtist: (String) -> Unit,
    uiState: PlayerUiState,
    song: Song?,
    library: LibraryRepository,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "收起",
            tint = Color(0xFF2A2A32),
            modifier = Modifier
                .size(30.dp)
                .clickable { onBack() },
        )
        Spacer(Modifier.weight(1f))
        VolumeIcon(
            tint = Color(0xFF2A2A32),
            modifier = Modifier
                .clickable { showVolume = !showVolume }
                .padding(4.dp),
        )
        Spacer(Modifier.width(14.dp))
        EqualizerIcon(
            tint = Color(0xFF2A2A32),
            // 打开应用内置均衡器（不再调用系统音效面板）
            modifier = Modifier.clickable { onOpenEqualizer() },
        )
        Spacer(Modifier.width(14.dp))
        Box {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "菜单",
                tint = Color(0xFF2A2A32),
                modifier = Modifier.clickable { showMenu = true },
            )
            // 橙色圆点
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(OrangeDot),
            )
            PlayerDropdownMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                song = song,
                onDetails = { showDetails = true },
                onDelete = { showDeleteConfirm = true },
                onAlbum = { song?.let { onBrowseAlbum(it.album) } },
                onArtist = { song?.let { onBrowseArtist(it.artist) } },
                onSettings = onOpenSettings,
            )
        }
        if (showVolume) {
            VolumePopup(onDismiss = { showVolume = false })
        }
    }

    if (showDetails && song != null) {
        SongDetailsDialog(song, onDismiss = { showDetails = false })
    }
    if (showDeleteConfirm && song != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除歌曲") },
            text = { Text("确定从设备中删除「${song.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = java.io.File(song.path).delete()
                    Toast.makeText(
                        context,
                        if (ok) "已删除" else "删除失败（可能没有文件管理权限）",
                        Toast.LENGTH_SHORT,
                    ).show()
                    showDeleteConfirm = false
                    if (ok) {
                        LyricsLoader.invalidate(song.path)
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PlayerDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    song: Song?,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onAlbum: () -> Unit,
    onArtist: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
    ) {
        MenuItem("删除") { onDelete(); onDismiss() }
        MenuItem("分享") {
            onDismiss()
            shareSong(context, song)
        }
        MenuItem("歌曲详情") { onDetails(); onDismiss() }
        MenuItem("专辑") { onAlbum(); onDismiss() }
        MenuItem("歌手") { onArtist(); onDismiss() }
        MenuItem("设置为") {
            onDismiss()
            openRingtonePicker(context, song)
        }
        Box {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("设置", fontSize = 16.sp) },
                onClick = { onSettings(); onDismiss() },
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 18.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(OrangeDot),
            )
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text, fontSize = 16.sp, color = Color(0xFF1B1B1F)) },
        onClick = onClick,
    )
}

@Composable
private fun VolumePopup(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audioManager = context.getSystemService(Context_AUDIO_SERVICE) as AudioManager
    var volume by remember {
        mutableIntStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        )
    }
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .padding(top = 4.dp, end = 4.dp)
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp),
        ) {
            Text("音量  $volume/$max", fontSize = 14.sp, color = Color(0xFF1B1B1F))
            Slider(
                value = volume / max.toFloat(),
                onValueChange = {
                    volume = (it * max).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                },
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color(0xFF4A52A8),
                    activeTrackColor = Color(0xFF8A90DE),
                ),
            )
        }
    }
}

@Composable
private fun IconActionRow(
    uiState: PlayerUiState,
    settings: SettingsRepository,
    client: PlaybackClient,
    library: LibraryRepository,
    song: Song?,
    tint: Color,
) {
    var addSheet by remember { mutableStateOf(false) }
    val isFavorite = song != null && settings.favorites.contains(song.path)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistNoteIcon(
            tint = tint,
            modifier = Modifier.clickable { uiState.showQueue = true },
        )
        Icon(
            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = "收藏",
            tint = if (isFavorite) Color(0xFFE06070) else tint,
            modifier = Modifier.clickable {
                if (song != null) settings.toggleFavorite(song.path)
            },
        )
        Text(
            "+",
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            color = tint,
            modifier = Modifier.clickable { addSheet = true },
        )
    }

    if (uiState.showQueue) {
        QueueSheet(
            uiState = uiState,
            client = client,
            library = library,
            onDismiss = { uiState.showQueue = false },
        )
    }
    if (addSheet && song != null) {
        AddToPlaylistSheet(
            settings = settings,
            library = library,
            song = song,
            onDismiss = { addSheet = false },
        )
    }
}

@Composable
private fun SeekSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    tint: Color,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Column {
        Slider(
            value = if (dragging) dragValue
            else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            else 0f,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek((dragValue * durationMs).toLong())
                dragging = false
            },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFF2A2E38),
                activeTrackColor = Color(0xFF565D77),
                inactiveTrackColor = Color(0xFFB9BCCB),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(positionMs), fontSize = 11.sp, color = Color(0xFF3C3C46))
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), fontSize = 11.sp, color = Color(0xFF3C3C46))
        }
    }
}

@Composable
private fun TransportControls(
    uiState: PlayerUiState,
    client: PlaybackClient,
    tint: Color,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    compact: Boolean = false,
) {
    val playSize = if (compact) 38.dp else 46.dp
    val skipSize = if (compact) 25.dp else 30.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Shuffle,
            contentDescription = "随机播放",
            tint = if (uiState.shuffleEnabled) LyricHighlight else tint,
            modifier = Modifier
                .size(22.dp)
                .clickable {
                    client.player?.shuffleModeEnabled =
                        !uiState.shuffleEnabled
                },
        )
        Icon(
            Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            tint = tint,
            modifier = Modifier
                .size(skipSize)
                .clickable { client.player?.seekToPreviousMediaItem() },
        )
        Icon(
            if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = "播放/暂停",
            tint = tint,
            modifier = Modifier
                .size(playSize)
                .clickable { client.togglePlayPause() },
        )
        Icon(
            Icons.Filled.SkipNext,
            contentDescription = "下一首",
            tint = tint,
            modifier = Modifier
                .size(skipSize)
                .clickable { client.player?.seekToNextMediaItem() },
        )
        LyricsToggleIcon(
            tint = tint,
            size = 24,
            active = showLyrics,
            modifier = Modifier.clickable { onToggleLyrics() },
        )
    }
}

// ─────────────────────────── 队列 / 加入播放列表 ───────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    uiState: PlayerUiState,
    client: PlaybackClient,
    library: LibraryRepository,
    onDismiss: () -> Unit,
) {
    var paths by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val c = client.controller ?: return@LaunchedEffect
        paths = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "播放队列（${paths.size}首）",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.height(420.dp)) {
            itemsIndexed(paths) { i, path ->
                var s by remember(path) { mutableStateOf<Song?>(null) }
                LaunchedEffect(path) { s = library.songByPath(path) }
                val isCurrent = i == uiState.queueIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            client.controller?.seekTo(i, 0)
                            client.controller?.play()
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(
                        path = path,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            s?.title ?: path.substringAfterLast('/'),
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) LyricHighlight else Color(0xFF1B1B1F),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            s?.artist ?: "",
                            fontSize = 11.sp,
                            color = Color(0xFF83838C),
                            maxLines = 1,
                        )
                    }
                    Text(formatTime(s?.durationMs ?: 0), fontSize = 11.sp, color = Color(0xFF83838C))
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    settings: SettingsRepository,
    library: LibraryRepository,
    song: Song,
    onDismiss: () -> Unit,
) {
    var playlists by remember { mutableStateOf(settings.getPlaylists()) }
    var newName by remember { mutableStateOf("") }
    // 在 composable 作用域取出，clickable 的 lambda 不是 composable 上下文
    val context = LocalContext.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "添加到播放列表",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.height(300.dp)) {
            itemsIndexed(playlists) { i, (name, paths) ->
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text("${paths.size} 首") },
                    modifier = Modifier.clickable {
                        if (!paths.contains(song.path)) paths.add(song.path)
                        settings.savePlaylists(playlists.map { it.first to it.second })
                        Toast.makeText(context, "已添加", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("新建播放列表名称") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (newName.isNotBlank()) {
                            playlists.add(newName.trim() to mutableListOf(song.path))
                            settings.savePlaylists(playlists.map { it.first to it.second })
                            onDismiss()
                        }
                    }) { Text("创建") }
                }
            }
        }
    }
}

@Composable
private fun SongDetailsDialog(song: Song, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲详情") },
        text = {
            Column {
                DetailLine("标题", song.title)
                DetailLine("歌手", song.artist)
                DetailLine("专辑", song.album)
                DetailLine("格式", song.fileName.substringAfterLast('.', "未知").uppercase())
                DetailLine("时长", formatTime(song.durationMs))
                DetailLine("大小", "%.1f MB".format(song.sizeBytes / 1024f / 1024f))
                DetailLine("路径", song.path)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            fontSize = 13.sp,
            color = Color(0xFF83838C),
            modifier = Modifier.width(48.dp),
        )
        Text(value, fontSize = 13.sp, color = Color(0xFF1B1B1F))
    }
}

// ─────────────────────────── 工具 ───────────────────────────

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private const val Context_AUDIO_SERVICE = android.content.Context.AUDIO_SERVICE

private fun openRingtonePicker(context: android.content.Context, song: Song?) {
    if (song == null) return
    try {
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "设置为铃声")
        }
        context.startActivity(i)
    } catch (t: Throwable) {
        Toast.makeText(context, "无法打开铃声选择器", Toast.LENGTH_SHORT).show()
    }
}

private fun shareSong(context: android.content.Context, song: Song?) {
    if (song == null) return
    try {
        val file = java.io.File(song.path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file,
        )
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "分享歌曲"))
    } catch (t: Throwable) {
        Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
    }
}

/** 从封面提取渐变色（轻量实现：平均色 + 提亮） */
internal suspend fun extractGradientColors(path: String): Pair<Color, Color> =
    withContext(Dispatchers.IO) {
        try {
            val bmp = com.spotify.music.core.tags.CoverLoader.load(path)
            if (bmp == null || bmp.width == 0) {
                GradientTopFallback to GradientBottomFallback
            } else {
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, 8, 8, true)
                var r = 0; var g = 0; var b = 0
                for (y in 0 until 8) for (x in 0 until 8) {
                    val c = small.getPixel(x, y)
                    r += android.graphics.Color.red(c)
                    g += android.graphics.Color.green(c)
                    b += android.graphics.Color.blue(c)
                }
                val n = 64
                r /= n; g /= n; b /= n
                val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                val boost = if (lum < 0.45) 1.9 else 1.45
                fun light(v: Int): Int = (255 * 0.72 + v * boost * 0.28).toInt().coerceIn(0, 255)
                val top = Color(light(r), light(g), light(b))
                val bottom = Color(
                    light(r).coerceAtMost(225),
                    light(g).coerceAtMost(225),
                    (light(b) * 0.96).toInt().coerceIn(0, 255),
                )
                top to bottom
            }
        } catch (t: Throwable) {
            GradientTopFallback to GradientBottomFallback
        }
    }
