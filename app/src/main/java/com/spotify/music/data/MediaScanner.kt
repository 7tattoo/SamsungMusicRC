package com.spotify.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import com.spotify.music.core.tags.EmbeddedLyricsReader
import com.spotify.music.data.db.AppDatabase
import com.spotify.music.data.db.SongEntity
import com.spotify.music.util.CrashLogger
import java.io.File

/**
 * 自定义目录媒体扫描器（含子文件夹，递归）。
 * 需要已授予 READ_MEDIA_AUDIO / MANAGE_EXTERNAL_STORAGE。
 */
class MediaScanner(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "amr", "aiff", "ape")
        /** 递归深度上限：从存储根目录扫描时防止异常深的目录树把栈打爆 */
        private const val MAX_DEPTH = 24
        /** 单次扫描的文件数上限：防止异常目录树把扫描拖成几分钟的 CPU 风暴 */
        private const val MAX_FILES = 6000
        /** SQLite 单语句绑定变量上限 999，留一点余量 */
        private const val SQL_CHUNK = 900
        /**
         * 默认跳过的目录名：
         *  - Android/ 下是各应用私有数据（缓存的语音、铃声素材极多，且对用户无意义）
         *  - 以 . 开头的隐藏目录同理
         * 从存储根目录扫描时这一条能把扫描量砍掉一大半。
         */
        private val SKIP_DIR_NAMES = setOf("Android")
    }

    data class Progress(val found: Int, val processed: Int, val total: Int, val currentFile: String)

    suspend fun scan(onProgress: suspend (Progress) -> Unit) {
        val dirs = settings.scanDirs
        val hidden = settings.hiddenFolders

        // 1. 收集文件
        val files = mutableListOf<File>()
        for (dir in dirs) {
            val root = File(dir)
            if (root.exists() && root.isDirectory) {
                collectAudioFiles(root, hidden, files)
            }
        }

        // 2. 解析元数据
        val entities = ArrayList<SongEntity>(files.size)
        var processed = 0
        for (f in files) {
            entities.add(readEntity(f))
            processed++
            if (processed % 20 == 0 || processed == files.size) {
                onProgress(Progress(files.size, processed, files.size, f.name))
            }
        }

        // 3. 入库：先清掉已消失的，再 upsert
        // 注意：entities 为空时 IN () 是非法 SQL，Room 会抛异常，故需判空
        if (entities.isNotEmpty()) {
            val paths = entities.map { it.path }
            // SQLite 单条语句的绑定变量上限是 999（SQLITE_MAX_VARIABLE_NUMBER）。
            // 曲库上千首时一次性 deleteNotIn 会抛 "too many SQL variables"，
            // 整个扫描结果全部丢弃 —— 必须分块。
            paths.chunked(SQL_CHUNK).forEach { db.songDao().deleteNotIn(it) }
            entities.chunked(SQL_CHUNK).forEach { db.songDao().upsertAll(it) }
        }
        CrashLogger.trace("scan DONE files=${files.size} entities=${entities.size}")
    }

    suspend fun removeFolder(folderPath: String) {
        db.songDao().deleteByFolder(folderPath, "$folderPath/")
    }

    private fun collectAudioFiles(dir: File, hidden: Set<String>, out: MutableList<File>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        val canonical = try {
            dir.canonicalPath
        } catch (t: Throwable) {
            dir.absolutePath
        }
        if (hidden.any { canonical == it || canonical.startsWith("$it/") }) return
        if (out.size >= MAX_FILES) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= MAX_FILES) return
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name in SKIP_DIR_NAMES) continue
                collectAudioFiles(child, hidden, out, depth + 1)
            } else if (child.isFile && child.length() > 0) {
                val ext = child.extension.lowercase()
                if (ext in AUDIO_EXTENSIONS) out.add(child)
            }
        }
    }

    /**
     * 读取单曲元数据。
     *
     * 关键：即使 MediaMetadataRetriever 解析失败（部分 FLAC / APE / 高解析度音轨会失败），
     * 也必须返回基于文件名的基础实体，**绝不能返回 null** —— 否则整首歌会被静默丢弃，
     * 用户看到的现象就是「明明有文件却扫不到」。
     */
    private fun readEntity(f: File): SongEntity {
        val fallback = SongEntity(
            path = f.absolutePath,
            fileName = f.name,
            folderPath = f.parent ?: "",
            folderName = f.parentFile?.name ?: "根目录",
            title = f.nameWithoutExtension,
            artist = "未知歌手",
            album = "未知专辑",
            durationMs = 0L,
            sizeBytes = f.length(),
            lastModified = f.lastModified(),
            hasEmbeddedLyrics = false,
            trackNumber = 0,
            year = 0,
        )
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val rawTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val track = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.toIntOrNull() ?: 0
                val year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull() ?: 0
                val hasLyrics = runCatching { EmbeddedLyricsReader.read(f.absolutePath)?.isNotBlank() == true }
                    .getOrDefault(false)

                fallback.copy(
                    title = rawTitle?.takeIf { it.isNotBlank() } ?: fallback.title,
                    artist = artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback.artist,
                    album = album?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback.album,
                    durationMs = duration,
                    hasEmbeddedLyrics = hasLyrics,
                    trackNumber = track,
                    year = year,
                )
            } finally {
                runCatching { mmr.release() }
            }
        } catch (t: Throwable) {
            // 元数据解析失败：保留文件本身（用户仍能播放），只记录日志
            CrashLogger.log(t, "readEntity failed: ${f.absolutePath}")
            fallback
        }
    }
}
