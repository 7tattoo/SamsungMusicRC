package com.spotify.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

/** 数据层缺失标记（旧库写入的中文占位）在显示层本地化 */
@Composable
fun artistDisplay(artist: String): String =
    if (artist.isBlank() || artist == "未知歌手") stringResource(R.string.unknown_artist) else artist

@Composable
fun albumDisplay(album: String): String =
    if (album.isBlank() || album == "未知专辑") stringResource(R.string.unknown_album) else album

@Composable
fun folderDisplay(name: String): String =
    if (name.isBlank() || name == "根目录") stringResource(R.string.root_dir) else name
