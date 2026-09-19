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

    /** 单曲歌词文本很小（约 1~4KB），但整库 300+ 首无上限累积也有几 MB，这里按 LRU 限容 */
    private const val MAX_CACHE = 64

    private val cache = object : LinkedHashMap<String, Lyrics>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>): Boolean = size > MAX_CACHE
    }
    private val wholeTextCache = object : LinkedHashMap<String, String>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_CACHE
    }

    @Synchronized
    fun load(audioPath: String): Lyrics {
        // Do not permanently cache EMPTY: the player page can race the service's
        // first metadata read while a mounted file is becoming available. The car
        // bridge may then have lyrics while the player page is stuck with the old
        // empty result.
        cache[audioPath]?.let { cached ->
            if (cached.lines.isNotEmpty()) return cached
            cache.remove(audioPath)
        }
        val lyrics = doLoad(audioPath)
        if (lyrics.lines.isNotEmpty()) cache[audioPath] = lyrics
        return lyrics
    }

    @Synchronized
    fun loadWholeLrc(audioPath: String): String? {
        wholeTextCache[audioPath]?.let { return it.ifEmpty { null } }
        val text = readRaw(audioPath)?.trim()?.takeIf { it.isNotEmpty() }
        // As with parsed lyrics, don't freeze a transient read failure into the cache.
        if (text != null) wholeTextCache[audioPath] = text
        return text
    }

    @Synchronized
    fun invalidate(audioPath: String) {
        cache.remove(audioPath)
        wholeTextCache.remove(audioPath)
    }

    /**
     * 空歌词定位诊断（一次性探测，只进 trace 不进缓存）：
     * 旁路文件（.lrc/.txt）存在性与大小 + 内嵌歌词探测结论。
     */
    @Synchronized
    fun diagnose(audioPath: String): String {
        val f = File(audioPath)
        if (!f.exists()) return "file-missing"
        val base = f.nameWithoutExtension
        val sidecars = f.parentFile?.takeIf { it.isDirectory }
            ?.listFiles { _, name ->
                name.equals("$base.lrc", true) || name.equals("$base.txt", true)
            }
            ?.map { "${it.name}:${it.length()}" }
            ?.toString()
            ?: "[]"
        val embedded = runCatching { EmbeddedLyricsReader.describe(audioPath) }
            .getOrDefault("embed-probe-error")
        return "sidecar=$sidecars embedded=$embedded"
    }

    /**
     * 歌词旁路文件的解码：优先识别 BOM（UTF-8 / UTF-16LE / UTF-16BE，
     * Windows 记事本存 .lrc 常见 UTF-16），无 BOM 时按 UTF-8 读、出现替换符
     * 再退回 ISO-8859-1（旧 GBK/latin 文件至少不产生乱码块）。
     */
    private fun readTextSmart(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val n = bytes.size
        if (n >= 2) {
            when {
                bytes[0].toInt() == 0xFF && bytes[1].toInt() == 0xFE ->
                    return String(bytes, 2, n - 2, Charsets.UTF_16LE)
                bytes[0].toInt() == 0xFE && bytes[1].toInt() == 0xFF ->
                    return String(bytes, 2, n - 2, Charsets.UTF_16BE)
            }
        }
        var text = String(bytes, Charsets.UTF_8)
        if ('\uFFFD' in text) text = String(bytes, Charsets.ISO_8859_1)
        return text
    }

    private fun readRaw(audioPath: String): String? {
        val f = File(audioPath)
        if (!f.exists()) return null
        // 1. 同目录同名 .lrc（优先）/ .txt（纯歌词文本兜底，解析为无时间戳静态歌词）
        val base = f.nameWithoutExtension
        val dir = f.parentFile
        if (dir != null && dir.isDirectory) {
            val candidates = dir.listFiles { _, name ->
                name.equals("$base.lrc", true) || name.equals("$base.txt", true)
            }
            if (candidates != null) {
                val sidecar = candidates.firstOrNull { it.name.equals("$base.lrc", true) }
                    ?: candidates.firstOrNull { it.name.equals("$base.txt", true) }
                if (sidecar != null) {
                    val text = readTextSmart(sidecar)
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
