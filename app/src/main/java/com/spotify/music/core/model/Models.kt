package com.spotify.music.core.model

/** 一首歌曲的领域模型（纯 Kotlin，便于跨平台迁移） */
data class Song(
    val id: Long,
    val path: String,
    val fileName: String,
    val folderPath: String,
    val folderName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val hasEmbeddedLyrics: Boolean,
    val trackNumber: Int = 0,
    val year: Int = 0,
)

/** 单行歌词 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/** 解析后的歌词：带时间戳时可按进度滚动，无时间戳则静态显示 */
data class Lyrics(
    val lines: List<LyricLine>,
    val hasTimestamps: Boolean,
) {
    companion object {
        val EMPTY = Lyrics(emptyList(), false)
    }
}
