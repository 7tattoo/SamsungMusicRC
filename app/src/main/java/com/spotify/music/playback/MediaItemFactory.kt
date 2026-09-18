package com.spotify.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.spotify.music.core.model.Song

/**
 * 队列 MediaItem 构建器（唯一出口；extras 由 CarLyricsBridge.baseExtras 提供）
 *
 * 关键：MediaMetadata 必须显式 setIsPlayable(true)。
 * Media3 在把 MediaItem 转换给 framework MediaBrowser 客户端（vivo 原子随身听 /
 * 车联投屏就是走这条路）时会校验，isPlayable 为 null 时抛
 * "IllegalArgumentException: mediaMetadata must specify isPlayable"，
 * 而这个异常发生在 MediaSession 内部回调链上，try/catch 完全兜不住 → 直接闪退。
 */
@OptIn(UnstableApi::class)
object MediaItemFactory {

    fun from(song: Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsPlayable(true) // 不写会在 vivo 上必崩，见类注释
            .setExtras(CarLyricsBridge.baseExtras())
            .build()
        return MediaItem.Builder()
            .setMediaId(song.path)
            .setUri(Uri.fromFile(java.io.File(song.path)))
            .setMediaMetadata(metadata)
            .build()
    }

    fun fromPaths(songs: List<Song>): List<MediaItem> = songs.map { from(it) }

    /** 兜底：任何来源的 MediaItem 在送回播放器前都过一遍，确保 isPlayable 已声明 */
    fun ensurePlayable(item: MediaItem): MediaItem =
        if (item.mediaMetadata.isPlayable == null) {
            item.buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon().setIsPlayable(true).build()
                )
                .build()
        } else item
}
