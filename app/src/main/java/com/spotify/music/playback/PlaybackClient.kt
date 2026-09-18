package com.spotify.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.spotify.music.util.CrashLogger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * UI → 服务控制客户端。
 * 通过 MediaController（Player 接口）控制播放，并提供自定义命令。
 */
class PlaybackClient private constructor(private val context: Context) {

    var controller: MediaController? = null
        private set

    val connected: Boolean
        get() = controller != null

    suspend fun connect(): MediaController = suspendCoroutine { cont ->
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    cont.resume(c)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun disconnect() {
        controller?.release()
        controller = null
    }

    val player: Player?
        get() = controller

    /**
     * 提交播放队列。
     *
     * 分批提交的原因：MediaController → MediaSession 是 Binder 调用，一次
     * setMediaItems 会把整个列表（每个 MediaItem 带完整 MediaMetadata）塞进
     * 一个 Binder 事务。Binder 单事务上限 1MB，上千首歌必然触发
     * TransactionTooLargeException。因此按 [CHUNK] 分批 addMediaItems。
     */
    fun playQueue(songs: List<com.spotify.music.core.model.Song>, startIndex: Int, play: Boolean = true) {
        try {
            val p = controller
            if (p == null) {
                CrashLogger.trace("playQueue SKIP: controller==null size=${songs.size}")
                return
            }
            val items = MediaItemFactory.fromPaths(songs)
            if (items.isEmpty()) {
                CrashLogger.trace("playQueue SKIP: items empty (songs=${songs.size})")
                return
            }
            val idx = startIndex.coerceIn(0, items.size - 1)
            CrashLogger.trace("playQueue ENTER size=${items.size} idx=$idx play=$play")
            if (items.size <= CHUNK) {
                p.setMediaItems(items, idx, 0L)
            } else {
                p.setMediaItems(items.subList(0, CHUNK), 0, 0L)
                var i = CHUNK
                while (i < items.size) {
                    val end = minOf(i + CHUNK, items.size)
                    p.addMediaItems(items.subList(i, end))
                    i = end
                }
                p.seekTo(idx, 0L)
            }
            CrashLogger.trace("playQueue items submitted")
            p.prepare()
            CrashLogger.trace("playQueue prepared")
            p.playWhenReady = play
            CrashLogger.trace("playQueue DONE playWhenReady=$play")
        } catch (t: Throwable) {
            CrashLogger.log(t, "playQueue size=${songs.size} startIndex=$startIndex")
            CrashLogger.trace("playQueue FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun togglePlayPause() {
        try {
            val p = controller ?: return
            if (p.isPlaying) p.pause() else p.play()
        } catch (t: Throwable) {
            CrashLogger.log(t, "togglePlayPause")
        }
    }

    fun seekTo(ms: Long) {
        try {
            controller?.seekTo(ms)
        } catch (t: Throwable) {
            CrashLogger.log(t, "seekTo")
        }
    }

    fun setSpeed(speed: Float) {
        try {
            controller?.setPlaybackSpeed(speed)
        } catch (t: Throwable) {
            CrashLogger.log(t, "setSpeed")
        }
    }

    fun setSkipSilence(enabled: Boolean) {
        try {
            val c = controller ?: return
            val args = Bundle().apply { putBoolean(PlaybackService.CMD_ARG_ENABLED, enabled) }
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_SKIP_SILENCE, Bundle.EMPTY), args)
        } catch (t: Throwable) {
            CrashLogger.log(t, "setSkipSilence")
        }
    }

    fun setSleepTimer(minutes: Int) {
        try {
            val c = controller ?: return
            val args = Bundle().apply { putInt(PlaybackService.CMD_ARG_MINUTES, minutes) }
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP_TIMER, Bundle.EMPTY), args)
        } catch (t: Throwable) {
            CrashLogger.log(t, "setSleepTimer")
        }
    }

    fun rebuildAudioOutput() {
        try {
            val c = controller ?: return
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_REBUILD_OUTPUT, Bundle.EMPTY), Bundle.EMPTY)
        } catch (t: Throwable) {
            CrashLogger.log(t, "rebuildAudioOutput")
        }
    }

    fun applySettings() {
        try {
            val c = controller ?: return
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_APPLY_SETTINGS, Bundle.EMPTY), Bundle.EMPTY)
        } catch (t: Throwable) {
            CrashLogger.log(t, "applySettings")
        }
    }

    /**
     * 服务刚从磁盘恢复过队列（进程重启）时自动续播一次。
     * 服务端校验 pendingAutoResume 标记：手动暂停/新队列不会误触发。
     */
    fun resumeIfNeeded() {
        try {
            val c = controller ?: return
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_RESUME, Bundle.EMPTY), Bundle.EMPTY)
        } catch (t: Throwable) {
            CrashLogger.log(t, "resumeIfNeeded")
        }
    }

    companion object {
        /** 单批提交的 MediaItem 数量上限（防 Binder TransactionTooLarge） */
        private const val CHUNK = 150

        @Volatile
        private var instance: PlaybackClient? = null

        fun get(context: Context): PlaybackClient =
            instance ?: synchronized(this) {
                instance ?: PlaybackClient(context.applicationContext).also { instance = it }
            }
    }
}
