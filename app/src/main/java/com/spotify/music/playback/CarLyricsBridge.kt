package com.spotify.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi

/**
 * vivo 车载歌词桥（适配「车联投屏 ucar」+「原子随身听 vivomusicmix」）。
 *
 * 协议要点（来自《vivo 车机滚动歌词适配开发文档》，逐条遵守）：
 *
 * 1. 车联投屏（ucar）：在 MediaMetadata 的 extras 里写
 *      ucar.media.metadata.LYRICS_WHOLE  = 整段 LRC（车机按 PlaybackState 自行滚动）
 *      ucar.media.metadata.LYRICS_STATUS = 0（有歌词）
 *    绝不能写 LYRICS_LINE、绝不能用 music.media.extras.* 单行通道、
 *    没有歌词时绝不写负状态（1/2 = "确认无歌词"，会让车机永久退回单行）。
 *
 * 2. 原子随身听（vivomusicmix）：
 *      - MediaMetadata extras 声明能力位 vivomusicmix.media.metadata.support_event = 31 (7|8|16)
 *      - 通过 MediaSession.setSessionExtras() 发 lrc_change 事件（整段 LRC）
 *      - 两个官方拼写错误必须照抄：meida / meidia
 *      - 切歌先发一次"空歌词事件"清除组件里上一首；无事件时绝不推空 Bundle
 *
 * 3. 工程要点：
 *      - metadata 注入走唯一出口（本应用只有一条路径：onMediaItemTransition → 加载 → decorate → replaceMediaItem）
 *      - 切歌时主动请求歌词（不依赖 UI 打开）
 *      - 歌词异步到达后强推一次
 *      - 每 25s 重发一次 lrc_change（兜底"车机在播放开始后才连上"）
 */
object CarLyricsBridge {

    // ── ucar 协议字段 ──
    const val KEY_UCAR_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE"
    const val KEY_UCAR_LYRICS_STATUS = "ucar.media.metadata.LYRICS_STATUS"

    // ── 原子随身听协议字段（meida / meidia 为 vivo 官方拼写错误，照抄！）──
    const val KEY_MIX_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
    const val KEY_MIX_ACTION = "vivomusicmix.meida.extra.key.action"
    const val KEY_MIX_MEDIA_ID = "vivomusicmix.extra.key.meidia_id"
    const val KEY_MIX_LYRIC = "vivomusicmix.extra.key.lyric"
    const val ACTION_LRC_CHANGE = "vivomusicmix.extra.lrc_change"

    /** 能力位：7(基础播控) | 8(歌词) | 16(进度条/seek) = 31 */
    const val SUPPORT_EVENT_ALL = 7L or 8L or 16L

    const val RESEND_INTERVAL_MS = 25_000L

    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var wholeLrc: String? = null

    @Volatile
    private var trackId: String? = null

    @Volatile
    private var lastLrcSentAt: Long = 0L

    /** 当前曲目是否已有整段歌词 */
    val hasLrc: Boolean
        get() = !wholeLrc.isNullOrEmpty()

    /** 切歌：更新曲目 ID，清空旧歌词，重置重发时钟 */
    fun onTrackChanged(newTrackId: String?) {
        if (trackId != newTrackId) {
            trackId = newTrackId
            wholeLrc = null
            lastLrcSentAt = 0L
        }
    }

    /** 歌词加载完成：只接受当前曲目的歌词 */
    fun seedLrc(trackId: String?, lrc: String?) {
        if (trackId == null) return
        if (this.trackId != trackId) return
        wholeLrc = lrc?.takeIf { it.isNotBlank() }
    }

    /**
     * 把当前整段 LRC 装饰进 MediaItem（ucar 通道 + support_event）。
     * 没有整段歌词时原样返回（铁律 1：绝不写负状态；铁律 3：零开销直接返回）。
     * 歌词已相同时跳过拷贝。
     */
    @OptIn(UnstableApi::class)
    fun decorate(item: MediaItem): MediaItem {
        if (!enabled) return item
        val lrc = wholeLrc ?: return item
        if (lrc.isEmpty()) return item
        if (trackId != item.mediaId) return item

        val oldExtras = item.mediaMetadata.extras
        if (oldExtras != null && lrc == oldExtras.getString(KEY_UCAR_LYRICS_WHOLE)) {
            return item // 已带相同歌词：跳过拷贝（铁律 3）
        }

        return try {
            val extras = oldExtras?.let { Bundle(it) } ?: Bundle()
            extras.putString(KEY_UCAR_LYRICS_WHOLE, lrc)
            extras.putLong(KEY_UCAR_LYRICS_STATUS, 0L)
            extras.putLong(KEY_MIX_SUPPORT_EVENT, SUPPORT_EVENT_ALL)
            item.buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon()
                        .setExtras(extras)
                        // 必须显式声明：Media3 转换给 framework MediaBrowser 客户端时会校验，
                        // 为 null 直接抛 IllegalArgumentException 导致闪退
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        } catch (t: Throwable) {
            item // 出错原样返回
        }
    }

    /** 队列构建时：只放 support_event 能力位，不放歌词（无歌词窗口零负担） */
    fun baseExtras(): Bundle = Bundle().apply {
        if (enabled) putLong(KEY_MIX_SUPPORT_EVENT, SUPPORT_EVENT_ALL)
    }

    /**
     * 原子随身听 lrc_change 事件 Bundle。
     * [lyric] 传空字符串 = 清除组件中上一首的歌词（切歌时）。
     */
    fun atomicExtras(mediaId: String?, lyric: String?): Bundle = Bundle().apply {
        putString(KEY_MIX_ACTION, ACTION_LRC_CHANGE)
        putString(KEY_MIX_MEDIA_ID, mediaId ?: "")
        putString(KEY_MIX_LYRIC, lyric ?: "")
    }

    /** 是否需要 25s 兜底重发 */
    fun shouldResendLrc(): Boolean {
        if (!enabled) return false
        if (wholeLrc.isNullOrEmpty()) return false
        return System.currentTimeMillis() - lastLrcSentAt >= RESEND_INTERVAL_MS
    }

    fun markLrcSent() {
        lastLrcSentAt = System.currentTimeMillis()
    }
}
