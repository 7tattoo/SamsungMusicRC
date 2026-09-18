package com.spotify.music.core.lyrics

import com.spotify.music.core.model.Lyrics
import com.spotify.music.core.tags.EmbeddedLyricsReader
import java.io.File

/**
 * 歌词加载器：
 *  优先级：同名 .lrc 旁路文件 > 内嵌歌词标签。
 *  带内存缓存；也负责给车载通道提供整段 LRC 文本。
 */
object LyricsLoader {

    private val cache = HashMap<String, Lyrics>()
    private val wholeTextCache = HashMap<String, String>()

    @Synchronized
    fun load(audioPath: String): Lyrics {
        cache[audioPath]?.let { return it }
        val lyrics = doLoad(audioPath)
        cache[audioPath] = lyrics
        return lyrics
    }

    @Synchronized
    fun loadWholeLrc(audioPath: String): String? {
        wholeTextCache[audioPath]?.let { return it.ifEmpty { null } }
        val text = readRaw(audioPath)?.trim()
        wholeTextCache[audioPath] = text ?: ""
        return text
    }

    @Synchronized
    fun invalidate(audioPath: String) {
        cache.remove(audioPath)
        wholeTextCache.remove(audioPath)
    }

    private fun readRaw(audioPath: String): String? {
        val f = File(audioPath)
        if (!f.exists()) return null
        // 1. 同目录同名 .lrc
        val base = f.nameWithoutExtension
        val dir = f.parentFile
        if (dir != null && dir.isDirectory) {
            val candidates = dir.listFiles { _, name ->
                name.equals("$base.lrc", true)
            }
            if (candidates != null) {
                val lrcFile = candidates.firstOrNull()
                if (lrcFile != null) {
                    val text = runCatching { lrcFile.readText(Charsets.UTF_8) }.getOrNull()
                        ?: runCatching { lrcFile.readText(Charsets.ISO_8859_1) }.getOrNull()
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        // 2. 内嵌标签
        return EmbeddedLyricsReader.read(audioPath)
    }

    private fun doLoad(audioPath: String): Lyrics {
        val text = readRaw(audioPath) ?: return Lyrics.EMPTY
        return LrcParser.parse(text)
    }
}
