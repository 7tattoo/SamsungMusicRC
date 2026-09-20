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

    private val LINE_TIME_REGEX = Regex("""\[\s*(\d{1,3})\s*:\s*(\d{1,2})(?:\s*[.:]\s*(\d{1,3}))?\s*]""")
    // 时:分:秒 变体（部分工具写 [0:01:23.45]）
    private val HMS_TIME_REGEX = Regex("""\[\s*(\d{1,3})\s*:\s*(\d{1,2})\s*:\s*(\d{1,2})(?:\s*[.:]\s*(\d{1,3}))?\s*]""")
    private val META_REGEX = Regex("""^\[(ti|ar|al|by|offset|au|length):(.*)]$""", RegexOption.IGNORE_CASE)
    private val WORD_TIME_REGEX = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String): Lyrics {
        val strict = parseStrict(raw)
        if (strict.lines.isNotEmpty()) return strict
        // 严格模式一行未中 → 用宽松规则再试一次（全角标点容错）
        val loose = parseLoose(raw)
        if (loose.lines.isNotEmpty()) return loose
        return strict
    }

    private fun parseLoose(raw: String): Lyrics {
        val normalized = raw.removePrefix("\uFEFF")
            .replace("：", ":").replace("．", ".")
            .replace("［", "[").replace("］", "]")
            .replace("【", "[").replace("】", "]")
            .replace("\r\n", "\n").replace('\r', '\n')
        return parseInternal(normalized)
    }

    private fun parseStrict(raw: String): Lyrics {
        val normalizedRaw = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return parseInternal(normalizedRaw)
    }

    private fun parseInternal(normalizedRaw: String): Lyrics {
        if (normalizedRaw.isBlank()) return Lyrics.EMPTY
        var offsetMs = 0L
        val out = ArrayList<LyricLine>(64)
        var hasTimestamps = false

        normalizedRaw.lineSequence().forEach { line0 ->
            val line = line0.trim()
            if (line.isEmpty()) return@forEach

            META_REGEX.find(line)?.let { m ->
                if (m.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = m.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }

            val times = LINE_TIME_REGEX.findAll(line).toList()
                .ifEmpty {
                    // [hh:mm:ss(.xx)] 变体：归一化成 [mm:ss.xx] 后再跑主正则
                    HMS_TIME_REGEX.replace(line) { m ->
                        val h = m.groupValues[1].toLong()
                        val mm = m.groupValues[2]
                        val ss = m.groupValues[3]
                        val frac = m.groupValues[4].ifEmpty { "0" }
                        "[${h * 60 + mm.toLong()}:$ss.${frac}]"
                    }.let { LINE_TIME_REGEX.findAll(it).toList() }
                }
            if (times.isEmpty()) {
                val text = WORD_TIME_REGEX.replace(line, "").trim()
                if (text.isNotEmpty() && !text.startsWith("[")) {
                    out.add(LyricLine(-1L, text))
                }
                return@forEach
            }
            hasTimestamps = true
            // 歌词文本提取：剥掉行内所有时间戳取剩余文本。不能用「最后一个时间戳
            // 之后的内容」——网易云等工具导出的行是 [时间]歌词[下一句时间] 排布，
            // 歌词夹在两个时间戳中间，按旧取法得到空串，整首被丢成「暂无歌词」。
            val text = WORD_TIME_REGEX.replace(stampFree(line), "").trim()
            if (text.isEmpty()) return@forEach
            times.forEach { m ->
                val min = m.groupValues[1].toLong().coerceAtMost(5999)
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

    /** 剥掉行内所有 [mm:ss(.xx)] 时间戳（歌词夹在时间戳中间的导出格式靠这个取文本） */
    private fun stampFree(line: String): String =
        line.replace(Regex("""\[\s*\d{1,3}\s*:\s*\d{1,2}(?:\s*[.:]\s*\d{1,3})?\s*]"""), " ")

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
