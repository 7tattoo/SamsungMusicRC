package com.spotify.music.core.tags

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache

/**
 * 封面加载器：读取音频文件内嵌封面。
 *
 * 关键点（曾经的内存杀手）：
 *  - 内嵌封面可能是 3000×3000 的原图（HIRES/FLAC 尤其常见），全尺寸解码一张就是几十 MB。
 *    列表里每行都加载 + 缓存 128 张 → 必然 OOM 闪退。
 *  - 因此这里统一按 [MAX_PX] 降采样后再入缓存，且缓存按“实际字节数”限容（而非条目数）。
 */
object CoverLoader {

    /** 解码目标最大边长：UI 最大用到全屏封面，640px 足够（配合 GPU 缩放） */
    private const val MAX_PX = 640
    private const val THUMB_MAX_PX = 512
    /** 缓存上限：24MB（约 40~60 张 640px 封面） */
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        val bmp = decode(path)
        if (bmp != null) cache.put(path, bmp)
        return bmp
    }

    fun invalidate(path: String) {
        cache.remove(path)
    }

    fun clearCache() {
        cache.evictAll()
    }

    /** 解码并按 [MAX_PX] 降采样；失败返回 null */
    private fun decode(path: String): Bitmap? = try {
        val raw = MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(path)
            mmr.embeddedPicture
        } ?: return null

        // 1. 只解析边界，算出采样率（不分配像素内存）
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, MAX_PX)

        // 2. 真正解码（已降采样）
        val finalOpts = BitmapFactory.Options().apply {
            inSampleSize = opts.inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565 // 封面不需要透明通道，省一半内存
        }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, finalOpts)
    } catch (t: Throwable) {
        null
    }

    private fun computeSampleSize(w: Int, h: Int, maxPx: Int): Int {
        if (w <= 0 || h <= 0) return 1
        val longer = maxOf(w, h)
        var sample = 1
        while (longer / (sample * 2) >= maxPx) sample *= 2
        return sample.coerceAtLeast(1)
    }

    /** 供通知/车机使用的缩略图字节（≤512px JPEG），无封面返回 null */
    fun loadThumbBytes(path: String): ByteArray? {
        return try {
            val bmp = load(path) ?: return null
            val scaled = if (bmp.width > THUMB_MAX_PX || bmp.height > THUMB_MAX_PX) {
                val scale = THUMB_MAX_PX.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else bmp
            java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
        } catch (t: Throwable) {
            null
        }
    }
}

private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        try {
            release()
        } catch (t: Throwable) {
        }
    }
}
