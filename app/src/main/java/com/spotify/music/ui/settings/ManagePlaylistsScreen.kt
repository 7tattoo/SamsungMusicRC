package com.spotify.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.R
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.playback.PlaybackClient
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** 设置 → 管理播放列表：新建 / 重命名 / 删除 / 点击播放 */
@Composable
fun ManagePlaylistsScreen(
    settings: SettingsRepository,
    library: LibraryRepository,
    client: PlaybackClient,
    onBack: () -> Unit,
) {
    var playlists by remember { mutableStateOf(settings.getPlaylists()) }
    var newName by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) { playlists = settings.getPlaylists() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 标题栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                fontSize = 26.sp,
                color = SamsungBlue,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            Text(stringResource(R.string.manage_playlists), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }

        // 新建播放列表
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text(stringResource(R.string.new_playlist)) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (newName.isNotBlank()) {
                    val list = settings.getPlaylists()
                    list.add(newName.trim() to mutableListOf())
                    settings.savePlaylists(list)
                    newName = ""
                    version++
                }
            }) { Text(stringResource(R.string.create)) }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(playlists) { i, (name, paths) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            scope.launch {
                                val songs = library.songsByPaths(paths)
                                if (songs.isNotEmpty()) client.playQueue(songs, 0)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = SamsungBlue,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(stringResource(R.string.songs_count, paths.size), fontSize = 12.sp, color = TextSecondary)
                    }
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.rename),
                        tint = Color(0xFFB0B0B8),
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                renameIndex = i
                                renameText = name
                            },
                    )
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_playlist),
                        tint = Color(0xFFB0B0B8),
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { deleteIndex = i },
                    )
                }
            }
        }
    }

    // 重命名对话框
    val renaming = renameIndex
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        val list = settings.getPlaylists()
                        list[renaming] = renameText.trim() to list[renaming].second
                        settings.savePlaylists(list)
                        version++
                    }
                    renameIndex = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameIndex = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 删除确认对话框
    val deleting = deleteIndex
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text(stringResource(R.string.delete_playlist)) },
            text = { Text(stringResource(R.string.confirm_delete_playlist, playlists[deleting].first)) },
            confirmButton = {
                TextButton(onClick = {
                    val list = settings.getPlaylists().toMutableList()
                    list.removeAt(deleting)
                    settings.savePlaylists(list)
                    version++
                    deleteIndex = null
                }) { Text(stringResource(R.string.delete), color = Color(0xFFE0533B)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteIndex = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
