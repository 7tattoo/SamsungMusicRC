package com.spotify.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.core.model.Song
import com.spotify.music.data.AlbumGroup
import com.spotify.music.data.ArtistGroup
import com.spotify.music.data.FolderGroup
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.data.groupedAlbums
import com.spotify.music.data.groupedArtists
import com.spotify.music.data.sortedByMixedKey
import com.spotify.music.data.sortedByMode
import com.spotify.music.playback.PlaybackClient
import com.spotify.music.ui.albumDisplay
import com.spotify.music.ui.artistDisplay
import com.spotify.music.ui.components.AlbumArt
import com.spotify.music.ui.components.AzBubble
import com.spotify.music.ui.components.AzScrollbar
import com.spotify.music.ui.components.SortIcon
import com.spotify.music.ui.components.letterOfText
import com.spotify.music.ui.folderDisplay
import com.spotify.music.ui.player.formatTime
import com.spotify.music.ui.theme.LibraryBg
import com.spotify.music.ui.theme.OrangeDot
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

internal val DEFAULT_TABS = listOf("收藏", "播放列表", "歌曲", "专辑", "歌手", "文件夹")

@Composable
internal fun tabLabel(t: String): String = when (t) {
    "收藏" -> stringResource(R.string.tab_favorites)
    "播放列表" -> stringResource(R.string.tab_playlists)
    "歌曲" -> stringResource(R.string.tab_songs)
    "专辑" -> stringResource(R.string.tab_albums)
    "歌手" -> stringResource(R.string.tab_artists)
    "文件夹" -> stringResource(R.string.tab_folders)
    else -> t
}

/**
 * 资料库主页（竖屏）：Samsung Music 标题 + Tabs + 白色列表卡片 + 迷你播放条。
 */
@Composable
fun LibraryScreen(
    library: LibraryRepository,
    settings: SettingsRepository,
    client: PlaybackClient,
    uiState: com.spotify.music.ui.PlayerUiState,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    browse: Pair<String, String>? = null, // ("album"|"artist", name)
    onClearBrowse: () -> Unit,
    onSetBrowse: (Pair<String, String>?) -> Unit = {},
) {
    var tab by remember { mutableStateOf("歌曲") }
    var showBrowseBar by remember { mutableStateOf(browse != null) }
    // 用户可隐藏/排序底部标签；被隐藏或重排后回到资料库时按最新配置渲染
    val visibleTabs = remember {
        buildList {
            val saved = settings.tabOrder
            addAll(if (saved.isNotEmpty()) saved else DEFAULT_TABS)
            DEFAULT_TABS.filter { it !in this }.forEach { add(it) }
        }.filter { it !in settings.hiddenTabs }
    }
    LaunchedEffect(visibleTabs) {
        if (tab !in visibleTabs) {
            tab = visibleTabs.firstOrNull() ?: "歌曲"
        }
    }
    LaunchedEffect(browse) {
        if (browse != null) {
            tab = "歌曲"
            showBrowseBar = true
        }
    }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderGroup>>(emptyList()) }
    var sortMode by remember { mutableStateOf(settings.sortMode) }
    var favoritesVersion by remember { mutableIntStateOf(0) }
    // 记录从哪个页签进入的浏览详情，返回时切回去
    var browseFrom by remember { mutableStateOf("专辑") }

    val openBrowse: (String, String) -> Unit = { type, name ->
        browseFrom = if (type == "album") "专辑" else "歌手"
        onSetBrowse(type to name)
        tab = "歌曲"
        showBrowseBar = true
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 收集 flow（简化：一次性拉取 + 后续由 rescan 触发）
        }
        library.allSongs.collect { songs = it }
    }
    LaunchedEffect(Unit) {
        library.folders.collect { folders = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LibraryBg)
            .statusBarsPadding()
            // 车机底部 dock 会盖住内容，避开导航栏区域
            .navigationBarsPadding(),
    ) {
        // 顶部标题栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showBrowseBar && browse != null) browse.second else "Samsung Music",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = SamsungBlue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.search),
                tint = Color(0xFF3A3A42),
                modifier = Modifier
                    .padding(6.dp)
                    .clickable(enabled = false) {},
            )
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.menu),
                    tint = Color(0xFF3A3A42),
                    modifier = Modifier.clickable { onOpenSettings() },
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(OrangeDot),
                )
            }
        }

        // Tabs
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            visibleTabs.forEach { t ->
                Text(
                    tabLabel(t),
                    fontSize = 15.sp,
                    fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                    color = if (tab == t) SamsungBlue else TextPrimary,
                    modifier = Modifier
                        .clickable {
                            tab = t
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
        }

        // 白色圆角内容卡片
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color.White),
        ) {
            if (showBrowseBar && browse != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "‹ " + stringResource(R.string.back),
                        color = SamsungBlue,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable {
                                showBrowseBar = false
                                tab = browseFrom
                                onClearBrowse()
                            }
                            .padding(12.dp),
                    )
                    Text(
                        stringResource(R.string.songs_count, filterBrowse(songs, browse).size),
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    "歌曲" -> SongsTab(
                        songs = filterBrowse(songs, browse),
                        sortMode = sortMode,
                        onSortChange = {
                            sortMode = it
                            settings.sortMode = it
                        },
                        client = client,
                        settings = settings,
                        library = library,
                        favoritesVersion = favoritesVersion,
                        onFavoriteToggle = { favoritesVersion++ },
                    )
                    "收藏" -> SongsTab(
                        songs = songs.filter { settings.favorites.contains(it.path) }
                            .let { applySort(it, sortMode) },
                        sortMode = sortMode,
                        onSortChange = {
                            sortMode = it
                            settings.sortMode = it
                        },
                        client = client,
                        settings = settings,
                        library = library,
                        favoritesVersion = favoritesVersion,
                        onFavoriteToggle = { favoritesVersion++ },
                        hideAz = false,
                    )
                    "播放列表" -> PlaylistsTab(settings, library, client)
                    "专辑" -> AlbumsTab(songs, client, onBrowse = { name -> openBrowse("album", name) })
                    "歌手" -> ArtistsTab(songs, client, onBrowse = { name -> openBrowse("artist", name) })
                    "文件夹" -> FoldersTab(folders, client, library, settings)
                }

                // 迷你播放条悬浮于列表之上
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    MiniPlayerBarInline(uiState, client, onOpenPlayer)
                }
            }
        }
    }

    if (uiState.showQueue) {
        com.spotify.music.ui.player.QueueSheet(
            uiState = uiState,
            client = client,
            library = library,
            onDismiss = { uiState.showQueue = false },
        )
    }
}

private fun filterBrowse(songs: List<Song>, browse: Pair<String, String>?): List<Song> {
    if (browse == null) return songs
    return when (browse.first) {
        "album" -> songs.filter { it.album == browse.second }
        "artist" -> songs.filter { it.artist == browse.second }
        else -> songs
    }
}

private fun applySort(songs: List<Song>, mode: String) = songs.sortedByMode(mode)

@Composable
private fun MiniPlayerBarInline(
    uiState: com.spotify.music.ui.PlayerUiState,
    client: PlaybackClient,
    onOpenPlayer: () -> Unit,
) {
    val song = uiState.currentSong
    // 展开状态在 Library 组合内存活（tab 切换不影响）；默认收起为唱片
    var miniExpanded by remember { mutableStateOf(false) }
    com.spotify.music.ui.components.MiniPlayer(
        songPath = song?.path,
        title = song?.title ?: stringResource(R.string.not_playing),
        artist = song?.let { artistDisplay(it.artist) } ?: stringResource(R.string.click_to_play),
        isPlaying = uiState.isPlaying,
        expanded = miniExpanded,
        onExpand = { miniExpanded = true },
        onCollapse = { miniExpanded = false },
        onToggle = { client.togglePlayPause() },
        onNext = { client.player?.seekToNextMediaItem() },
        onPrev = { client.player?.seekToPreviousMediaItem() },
        onOpenQueue = { uiState.showQueue = true },
        onOpenPlayer = onOpenPlayer,
    )
}

// ─────────────────────────── 歌曲页签 ───────────────────────────

@Composable
fun SongsTab(
    songs: List<Song>,
    sortMode: String,
    onSortChange: (String) -> Unit,
    client: PlaybackClient,
    settings: SettingsRepository,
    library: LibraryRepository,
    favoritesVersion: Int,
    onFavoriteToggle: () -> Unit,
    hideAz: Boolean = false,
) {
    val sorted = remember(songs, sortMode, favoritesVersion) { applySort(songs, sortMode) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }
    var hoverLetter by remember { mutableStateOf<Char?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 排序条
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showSortMenu = true },
                ) {
                    SortIcon(tint = Color(0xFF3A3A42))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (sortMode) {
                            "artist" -> stringResource(R.string.sort_artist)
                            "album" -> stringResource(R.string.sort_album)
                            "added" -> stringResource(R.string.sort_added)
                            "duration" -> stringResource(R.string.sort_duration)
                            else -> stringResource(R.string.sort_title)
                        },
                        fontSize = 13.sp,
                        color = TextSecondary,
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    listOf(R.string.sort_title to "title", R.string.sort_artist to "artist", R.string.sort_album to "album", R.string.sort_added to "added").forEach { (res, key) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(res)) },
                            onClick = {
                                onSortChange(key)
                                showSortMenu = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // 随机播放圆形按钮
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEBEBF0))
                    .clickable {
                        if (sorted.isNotEmpty()) {
                            client.playQueue(sorted, (0 until sorted.size).random())
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    tint = Color(0xFF2A2A32),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            // 播放圆形按钮
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF23262F))
                    .clickable {
                        if (sorted.isNotEmpty()) client.playQueue(sorted, 0)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play_all),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(sorted, key = { _, s -> s.path }) { _, song ->
                    SongRow(
                        song = song,
                        client = client,
                        settings = settings,
                        library = library,
                        queue = sorted,
                        onFavoriteToggle = onFavoriteToggle,
                    )
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
            if (!hideAz) {
                AzScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enabledLetters = sorted.groupBy { letterOfText(it.title) }.keys,
                    onHoverLetter = { hoverLetter = it },
                ) { letter ->
                    val idx = sorted.indexOfFirst { letterOfText(it.title) == letter }
                    if (idx >= 0) scope.launch { listState.scrollToItem(idx.coerceAtLeast(0)) }
                }
            }
            val hover = hoverLetter
            if (hover != null) {
                AzBubble(hover, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    client: PlaybackClient,
    settings: SettingsRepository,
    library: LibraryRepository,
    queue: List<Song>,
    onFavoriteToggle: () -> Unit,
    onOpenPlayer: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                com.spotify.music.util.CrashLogger.trace("CLICK song='${song.title}' queue=${queue.size}")
                val idx = queue.indexOfFirst { it.path == song.path }
                client.playQueue(queue, if (idx >= 0) idx else 0)
                com.spotify.music.util.CrashLogger.trace("CLICK song playQueue returned")
                onOpenPlayer?.invoke()
            }
            // 间距对照 Samsung Music 参考截图：... 右缘距屏幕右缘约 40dp（视觉空隙约 14dp）。
            .padding(start = 14.dp, end = 40.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            path = song.path,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp)),
            placeholder = { m ->
                Box(
                    m.background(Color(0xFFE7E7EE)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFA0A5C0),
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                song.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artistDisplay(song.artist),
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.more),
                tint = Color(0xFF6B6B75),
                modifier = Modifier.clickable { showMenu = true },
            )
            androidx.compose.material3.DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.next_play)) },
                    onClick = {
                        showMenu = false
                        val p = client.player ?: return@DropdownMenuItem
                        val cur = p.currentMediaItemIndex
                        val item = com.spotify.music.playback.MediaItemFactory.from(song)
                        p.addMediaItem(cur + 1, item)
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (settings.favorites.contains(song.path)) stringResource(R.string.unfavorite) else stringResource(R.string.favorite)) },
                    onClick = {
                        showMenu = false
                        settings.toggleFavorite(song.path)
                        onFavoriteToggle()
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.song_details)) },
                    onClick = {
                        showMenu = false
                        showDetails = true
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        showMenu = false
                        showDelete = true
                    },
                )
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_song)) },
            text = { Text(stringResource(R.string.delete_confirm, song.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    val ok = File(song.path).delete()
                    if (ok) {
                        com.spotify.music.core.lyrics.LyricsLoader.invalidate(song.path)
                        com.spotify.music.core.tags.CoverLoader.invalidate(song.path)
                        scope { library.rescan() }
                    } else {
                        android.widget.Toast.makeText(context, context.getString(R.string.delete_failed), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.song_details)) },
            text = {
                Column {
                    Text(stringResource(R.string.title_label) + "：${song.title}", fontSize = 13.sp)
                    Text(stringResource(R.string.artist_label) + "：${artistDisplay(song.artist)}", fontSize = 13.sp)
                    Text(stringResource(R.string.album_label) + "：${albumDisplay(song.album)}", fontSize = 13.sp)
                    Text(stringResource(R.string.duration_label) + "：${formatTime(song.durationMs)}", fontSize = 13.sp)
                    Text(stringResource(R.string.path_label) + "：${song.path}", fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

private fun scope(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
    kotlinx.coroutines.MainScope().launch { block() }

// ─────────────────────────── 播放列表页签 ───────────────────────────

@Composable
private fun PlaylistsTab(
    settings: SettingsRepository,
    library: LibraryRepository,
    client: PlaybackClient,
) {
    var playlists by remember { mutableStateOf(settings.getPlaylists()) }
    var newName by remember { mutableStateOf("") }
    var expandedPath by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) { playlists = settings.getPlaylists() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text(stringResource(R.string.new_playlist)) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Button(onClick = {
                if (newName.isNotBlank()) {
                    val list = settings.getPlaylists()
                    list.add(newName.trim() to mutableListOf())
                    settings.savePlaylists(list)
                    newName = ""
                    version++
                }
            }) { Text(stringResource(R.string.create)) }
        }
        LazyColumn {
            itemsIndexed(playlists) { i, (name, paths) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope {
                                val songs = library.songsByPaths(paths)
                                if (songs.isNotEmpty()) client.playQueue(songs, 0)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = SamsungBlue,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.songs_count, paths.size), fontSize = 12.sp, color = TextSecondary)
                    }
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_playlist),
                        tint = Color(0xFFB0B0B8),
                        modifier = Modifier.clickable {
                            val list = settings.getPlaylists().toMutableList()
                            list.removeAt(i)
                            settings.savePlaylists(list)
                            version++
                        },
                    )
                }
            }
        }
    }
}

// ─────────────────────────── 专辑 / 歌手 ───────────────────────────

@Composable
private fun AlbumsTab(songs: List<Song>, client: PlaybackClient, onBrowse: (String) -> Unit) {
    val albums = remember(songs) { songs.groupedAlbums() }
    // 大图两列网格（参考 Samsung Music）：近方形大封面 + 圆角，下方歌名/歌手
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        gridItemsIndexed(albums, key = { _, a -> a.name + a.artist }) { _, album ->
            AlbumCard(album, client, onBrowse)
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumGroup, client: PlaybackClient, onBrowse: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                com.spotify.music.util.CrashLogger.trace("CLICK album='${album.name}' songs=${album.songs.size}")
                onBrowse(album.name)
            },
    ) {
        AlbumArt(
            path = album.coverPath,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            albumDisplay(album.name),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            artistDisplay(album.artist),
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistsTab(songs: List<Song>, client: PlaybackClient, onBrowse: (String) -> Unit) {
    // 中英混拼（英文在前、中文按拼音跟随）后按首字母挂 A-Z 条
    val artists = remember(songs) {
        songs.groupedArtists().sortedByMixedKey { it.name }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hoverLetter by remember { mutableStateOf<Char?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(artists, key = { _, a -> a.name }) { _, artist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            com.spotify.music.util.CrashLogger.trace("CLICK artist='${artist.name}' songs=${artist.songs.size}")
                            onBrowse(artist.name)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(
                        path = artist.coverPath,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(artistDisplay(artist.name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(stringResource(R.string.artist_info, artist.albumCount, artist.songCount), fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
        AzScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd),
            enabledLetters = artists.groupBy { letterOfText(it.name) }.keys,
            onHoverLetter = { hoverLetter = it },
        ) { letter ->
            val idx = artists.indexOfFirst { letterOfText(it.name) == letter }
            if (idx >= 0) scope.launch { listState.scrollToItem(idx) }
        }
        val hover = hoverLetter
        if (hover != null) {
            AzBubble(hover, Modifier.align(Alignment.Center))
        }
    }
}

// ─────────────────────────── 文件夹页签 ───────────────────────────

@Composable
private fun FoldersTab(
    folders: List<FolderGroup>,
    client: PlaybackClient,
    library: LibraryRepository,
    settings: SettingsRepository,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hoverLetter by remember { mutableStateOf<Char?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(folders, key = { _, f -> f.path }) { _, folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (expanded == folder.path) expanded = null else expanded = folder.path
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        AlbumArt(
                            path = folder.songs.firstOrNull()?.path,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = Color(0xFF7A7FD4),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(16.dp)
                                .background(Color.White, CircleShape),
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(folderDisplay(folder.name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(folder.path, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.songs_count, folder.songs.size), fontSize = 12.sp, color = TextSecondary)
                }
                if (expanded == folder.path) {
                    folder.songs.forEach { song ->
                        SongRow(
                            song = song,
                            client = client,
                            settings = settings,
                            library = library,
                            queue = folder.songs,
                            onFavoriteToggle = {},
                            onOpenPlayer = null,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
        AzScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd),
            enabledLetters = folders.groupBy { letterOfText(it.name) }.keys,
            onHoverLetter = { hoverLetter = it },
        ) { letter ->
            val idx = folders.indexOfFirst { letterOfText(it.name) == letter }
            if (idx >= 0) scope.launch { listState.scrollToItem(idx) }
        }
        val hover = hoverLetter
        if (hover != null) {
            AzBubble(hover, Modifier.align(Alignment.Center))
        }
    }
}
