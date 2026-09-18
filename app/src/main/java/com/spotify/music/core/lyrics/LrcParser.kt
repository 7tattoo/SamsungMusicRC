package com.spotify.music.core.lyrics

import com.spotify.music.core.model.LyricLine
import com.spotify.music.core.model.Lyrics

/**
 * LRC 歌词解析器（纯 Kotlin）。
 * 支持：
 *  - 行级时间戳 [mm:ss]、[mm:ss.xx]、[mm:ss.xxx]，一行多时间戳
 *  - 元信息标签 [ti:] [ar:] [al:] [by:] [offset:]
 *  - 增强型逐字时间戳 <mm:ss.xx>（自动剥离）
 */
object LrcParser {

    private val LINE_TIME_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_REGEX = Regex("""^\[(ti|ar|al|by|offset|au|length):(.*)]$""", RegexOption.IGNORE_CASE)
    private val WORD_TIME_REGEX = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String): Lyrics {
        if (raw.isBlank()) return Lyrics.EMPTY
        var offsetMs = 0L
        val out = ArrayList<LyricLine>(64)
        var hasTimestamps = false

        raw.lineSequence().forEach { line0 ->
            val line = line0.trim()
            if (line.isEmpty()) return@forEach

            META_REGEX.find(line)?.let { m ->
                if (m.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = m.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }

            val times = LINE_TIME_REGEX.findAll(line).toList()
            if (times.isEmpty()) {
                val text = WORD_TIME_REGEX.replace(line, "").trim()
                if (text.isNotEmpty() && !text.startsWith("[")) {
                    out.add(LyricLine(-1L, text))
                }
                return@forEach
            }
            hasTimestamps = true
            val text = WORD_TIME_REGEX
                .replace(line.substring(times.last().range.last + 1), "")
                .trim()
            if (text.isEmpty()) return@forEach
            times.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                out.add(LyricLine(min * 60_000 + sec * 1_000 + frac, text))
            }
        }

        val offsetAdj = -offsetMs
        val timed = out.filter { it.timeMs >= 0 }
            .sortedBy { it.timeMs }
            .map { it.copy(timeMs = (it.timeMs + offsetAdj).coerceAtLeast(0)) }
        val untimed = out.filter { it.timeMs < 0 }
        val lines = if (timed.isNotEmpty()) timed else untimed
        if (lines.isEmpty()) return Lyrics.EMPTY
        return Lyrics(lines, timed.isNotEmpty())
    }

    /** 二分查找当前进度应显示的行号（无时间戳返回 -1） */
    fun findLineIndex(lyrics: Lyrics, positionMs: Long): Int {
        if (!lyrics.hasTimestamps || lyrics.lines.isEmpty()) return -1
        var lo = 0
        var hi = lyrics.lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lyrics.lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
