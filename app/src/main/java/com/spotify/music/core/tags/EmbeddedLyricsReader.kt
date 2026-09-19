package com.spotify.music.core.tags

import java.io.RandomAccessFile

/**
 * 从音频文件内嵌标签中读取歌词（纯 Kotlin，无 Android 依赖，便于迁移）：
 *  - MP3: ID3v2 USLT（内容通常带 LRC 时间戳）
 *  - FLAC: Vorbis Comment 的 LYRICS / UNSYNCEDLYRICS 字段
 *  - M4A: ilst 中的 (c)lyr 原子
 */
object EmbeddedLyricsReader {

    /** 返回原始歌词文本（可能含时间戳），无则 null */
    fun read(path: String): String? = try {
        RandomAccessFile(path, "r").use { raf ->
            when (raf.length()) {
                0L -> null
                else -> {
                    val head = ByteArray(4)
                    if (raf.read(head) < 4) null
                    else when {
                        head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                            head[2] == '3'.code.toByte() -> readId3(raf)
                        head.contentEquals("fLaC".toByteArray()) -> readFlac(raf)
                        else -> readM4a(raf)
                    }
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 只读探测：容器类型 + 歌词字段是否/多少存在。仅用于 trace 诊断，
     * 不返回歌词内容，也不做整盘扫描（MP4 走 ftyp 预检 + moov 单次定位）。
     */
    fun describe(path: String): String = try {
        RandomAccessFile(path, "r").use { raf ->
            val head = ByteArray(12)
            if (raf.read(head) < 4) return "unreadable"
            when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte() -> {
                    val text = readId3(raf)
                    "id3v2:" + if (text != null) "uslt-${text.length}chars" else "no-uslt"
                }
                head.contentEquals("fLaC".toByteArray()) -> flacDescribe(raf)
                head[4].toInt() == 'f'.code && head[5].toInt() == 't'.code &&
                    head[6].toInt() == 'y'.code && head[7].toInt() == 'p'.code -> {
                    val text = readM4a(raf)
                    "m4a:" + if (text != null) "lyr-${text.length}chars" else "no-lyr"
                }
                else -> "unknown-container"
            }
        }
    } catch (t: Throwable) {
        "probe-error:${t.javaClass.simpleName}"
    }

    /** FLAC：列出 Vorbis comment 里所有键（+歌词键的长度），用于判断歌词存在但键名不匹配 */
    private fun flacDescribe(raf: RandomAccessFile): String {
        raf.seek(4)
        while (true) {
            val h = ByteArray(4)
            if (raf.read(h) < 4) return "flac:short"
            val isLast = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (len < 0 || len > 32 * 1024 * 1024) return "flac:bad-len"
            if (type == 4) {
                val b = ByteArray(len)
                runCatching { raf.readFully(b) }.getOrElse { return "flac:read-fail" }
                val keys = vorbisCommentKeys(b)
                return "flac-vorbis:${keys.keys}"
            }
            if (isLast) return "flac:no-vorbis"
            raf.seek(raf.filePointer + len)
        }
    }

    /** 解析 Vorbis comment，只提取键名（歌词键附长度） */
    private fun vorbisCommentKeys(b: ByteArray): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        var pos = 0
        if (pos + 4 > b.size) return result
        val vendorLen = u32le(b, pos); pos += 4
        pos += vendorLen
        if (pos + 4 > b.size) return result
        val count = u32le(b, pos); pos += 4
        repeat(count) {
            if (pos + 4 > b.size) return@repeat
            val l = u32le(b, pos); pos += 4
            if (pos + l > b.size) return@repeat
            val comment = String(b, pos, l, Charsets.UTF_8)
            pos += l
            val eq = comment.indexOf('=')
            val key = (if (eq > 0) comment.substring(0, eq) else comment).uppercase()
            result[key] = if (eq > 0) l else 0
        }
        return result
    }

    private fun u32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    // ─────────────────────────── ID3v2 (MP3) ───────────────────────────

    private fun readId3(raf: RandomAccessFile): String? {
        raf.seek(0)
        val header = ByteArray(10)
        raf.readFully(header)
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val size = syncsafe(header, 6)
        if (size <= 0 || size > 16 * 1024 * 1024) return null
        val body = ByteArray(size)
        if (raf.read(body) != size) return null
        var pos = 0
        // 跳过扩展头
        if (flags and 0x40 != 0 && pos + 4 <= size) {
            val extSize = if (major >= 4) syncsafe(body, pos) else {
                ((body[pos].toInt() and 0xFF) shl 24) or ((body[pos + 1].toInt() and 0xFF) shl 16) or
                    ((body[pos + 2].toInt() and 0xFF) shl 8) or (body[pos + 3].toInt() and 0xFF)
            }
            pos += if (extSize in 1..size) extSize else 4
        }
        while (pos + 10 <= size) {
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            if (id == "\u0000\u0000\u0000\u0000") break
            val fSize: Int = if (major >= 4) syncsafe(body, pos + 4) else {
                ((body[pos + 4].toInt() and 0xFF) shl 24) or ((body[pos + 5].toInt() and 0xFF) shl 16) or
                    ((body[pos + 6].toInt() and 0xFF) shl 8) or (body[pos + 7].toInt() and 0xFF)
            }
            if (fSize <= 0 || pos + 10 + fSize > size) break
            val frameBody = body.copyOfRange(pos + 10, pos + 10 + fSize)
            if (id == "USLT" || id == "ULT") {
                val text = parseUslt(frameBody)
                if (!text.isNullOrBlank()) return text
            }
            pos += 10 + fSize
        }
        return null
    }

    /** USLT 帧：编码(1) 语言(3) 内容描述符(终止字符串) 歌词文本 */
    private fun parseUslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = b[0].toInt() and 0xFF
        var pos = 4 // 编码 + 语言
        val desc = readTerminatedString(b, pos, enc) ?: return null
        val textStart = desc.second
        return decodeText(b, textStart, b.size - textStart, enc)
    }

    private fun readTerminatedString(b: ByteArray, start: Int, enc: Int): Pair<String, Int>? {
        var i = start
        when (enc) {
            1, 2 -> { // UTF-16：双字节终止符
                while (i + 1 < b.size) {
                    if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                        return String(b, start, i - start, if (enc == 1) Charsets.UTF_16LE else Charsets.UTF_16BE) to (i + 2)
                    }
                    i += 2
                }
            }
            else -> {
                while (i < b.size) {
                    if (b[i].toInt() == 0) {
                        return String(b, start, i - start, Charsets.ISO_8859_1) to (i + 1)
                    }
                    i++
                }
            }
        }
        return null
    }

    private fun decodeText(b: ByteArray, start: Int, len: Int, enc: Int): String? = try {
        when (enc) {
            0 -> String(b, start, len, Charsets.ISO_8859_1)
            1 -> decodeUtf16WithBom(b, start, len)
            2 -> String(b, start, len, Charsets.UTF_16BE)
            3 -> String(b, start, len, Charsets.UTF_8)
            else -> String(b, start, len, Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }

    private fun decodeUtf16WithBom(b: ByteArray, start: Int, len: Int): String {
        if (len < 2) return ""
        val charset = if (b[start].toInt() == 0xFF && b[start + 1].toInt() == 0xFE) Charsets.UTF_16LE
        else if (b[start].toInt() == 0xFE && b[start + 1].toInt() == 0xFF) Charsets.UTF_16BE
        else Charsets.UTF_16LE
        val s = start + if (b[start].toInt() in intArrayOf(0xFF, 0xFE) && b[start + 1].toInt() in intArrayOf(0xFF, 0xFE)) 2 else 0
        return String(b, s, len - (s - start), charset)
    }

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    // ─────────────────────────── FLAC ───────────────────────────

    private fun readFlac(raf: RandomAccessFile): String? {
        raf.seek(4) // 跳过 fLaC
        while (true) {
            val h = ByteArray(4)
            if (raf.read(h) < 4) return null
            val isLast = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (len < 0 || len > 32 * 1024 * 1024) return null
            if (type == 4) { // VORBIS_COMMENT
                val b = ByteArray(len)
                // RandomAccessFile.read(byte[]) may legally return a short read.
                // Large FLAC comment blocks then looked like "no lyrics" intermittently.
                runCatching { raf.readFully(b) }.getOrElse { return null }
                return parseVorbisComment(b)
            }
            if (isLast) return null
            raf.seek(raf.filePointer + len)
        }
    }

    private fun parseVorbisComment(b: ByteArray): String? {
        var pos = 0
        fun u32le(): Int {
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        if (pos + 4 > b.size) return null
        val vendorLen = u32le()
        pos += vendorLen
        if (pos + 4 > b.size) return null
        val count = u32le()
        repeat(count) {
            if (pos + 4 > b.size) return null
            val l = u32le()
            if (pos + l > b.size) return null
            val comment = String(b, pos, l, Charsets.UTF_8)
            pos += l
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                // 不同工具写 FLAC 歌词用的键名不一：FLAC 标准约定 LYRICS（无时间戳），
                // LRC/SYNCEDLYRICS/SYNC_LYRICS 是各写标签工具（foobar、XLD、Kugou 等）的自定义键
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "LYRIC" ||
                    key == "SYNCEDLYRICS" || key == "LRC" || key == "SYNC_LYRICS"
                ) {
                    return comment.substring(eq + 1)
                }
            }
        }
        return null
    }

    // ─────────────────────────── MP4/M4A ───────────────────────────

    private val LYR_TYPE = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())

    private fun readM4a(raf: RandomAccessFile): String? {
        // 签名预检：MP4/M4A 的第 5~8 字节必然是 ftyp。
        // 没有这道检查的话，任何一个非 MP4 文件（wav / ogg / 任意二进制）都会被
        // 逐 8 字节从头扫到尾去找 moov atom —— 一个几十 MB 的文件就是几百万次
        // seek+read，扫描整盘时 CPU 直接被打满，轻则卡死重则被系统按"资源超限"杀掉。
        raf.seek(0)
        val head = ByteArray(12)
        if (raf.read(head) < 12) return null
        if (!(head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte())
        ) return null

        val moov = findAtom(raf, 0, raf.length(), "moov") ?: return null
        val udta = findAtom(raf, moov.first + 8, moov.first + moov.second, "udta") ?: return null
        // meta 原子前有 4 字节 version/flags
        val meta = findAtom(raf, udta.first + 8, udta.first + udta.second, "meta")
        if (meta != null) {
            val ilst = findAtom(raf, meta.first + 12, meta.first + meta.second, "ilst")
            if (ilst != null) {
                readLyrAtom(raf, ilst.first, ilst.first + ilst.second)?.let { return it }
            }
        }
        // 有些文件没有 meta，直接在 udta 下找
        return readLyrAtom(raf, udta.first + 8, udta.first + udta.second)
    }

    /** 返回 (内容起始偏移, 内容长度) */
    private fun findAtom(raf: RandomAccessFile, from: Long, to: Long, type: String): Pair<Long, Long>? {
        val target = type.toByteArray(Charsets.ISO_8859_1)
        var pos = from
        val header = ByteArray(8)
        while (pos + 8 <= to) {
            raf.seek(pos)
            if (raf.read(header) < 8) return null
            var size = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val typeOk = header.contentRangeEquals(4, target, 0, 4)
            if (size == 1) {
                val ext = ByteArray(8)
                if (raf.read(ext) < 8) return null
                size = 0
                for (i in 0 until 8) size = ((size.toLong() shl 8) or (ext[i].toLong() and 0xFF)).toInt()
                if (size <= 0) return null
            } else if (size == 0) {
                size = (to - pos).toInt()
            }
            if (size < 8) return null
            if (typeOk) return pos to size.toLong()
            pos += size
        }
        return null
    }

    private fun readLyrAtom(raf: RandomAccessFile, from: Long, to: Long): String? {
        var pos = from
        val header = ByteArray(8)
        while (pos + 8 <= to) {
            raf.seek(pos)
            if (raf.read(header) < 8) return null
            val size = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (size < 8 || pos + size > to) return null
            if (header.contentRangeEquals(4, LYR_TYPE, 0, 4)) {
                // ©lyr 内容是 data 原子
                val data = findAtom(raf, pos + 8, pos + size, "data") ?: return null
                if (data.second < 8) return null
                val payload = ByteArray((data.second - 8).toInt())
                raf.seek(data.first + 8)
                if (raf.read(payload) != payload.size) return null
                // data: 4字节类型 + 4字节locale + UTF-8文本
                return String(payload, 8, payload.size - 8, Charsets.UTF_8).takeIf { it.isNotBlank() }
            }
            pos += size
        }
        return null
    }

    private fun ByteArray.contentRangeEquals(from: Int, other: ByteArray, otherFrom: Int, len: Int): Boolean {
        for (i in 0 until len) {
            if (this[from + i] != other[otherFrom + i]) return false
        }
        return true
    }
}
