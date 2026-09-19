package com.spotify.music.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.spotify.music.MainActivity
import com.spotify.music.core.lyrics.LyricsLoader
import com.spotify.music.core.model.Lyrics
import com.spotify.music.core.lyrics.LrcParser
import com.spotify.music.core.tags.CoverLoader
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.util.CrashLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放服务：Media3 ExoPlayer 内核 + MediaSession。
 * 车载歌词注入唯一出口：onMediaItemTransition → 主动加载歌词 → decorate → replaceMediaItem 强推。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        const val CMD_SLEEP_TIMER = "com.spotify.music.SLEEP_TIMER"
        const val CMD_APPLY_SETTINGS = "com.spotify.music.APPLY_SETTINGS"
        const val CMD_REBUILD_OUTPUT = "com.spotify.music.REBUILD_OUTPUT"
        const val CMD_SKIP_SILENCE = "com.spotify.music.SKIP_SILENCE"
        const val CMD_TOGGLE_PLAY_PAUSE = "com.spotify.music.TOGGLE_PLAY_PAUSE"
        const val CMD_ARG_MINUTES = "minutes"
        const val CMD_ARG_ENABLED = "enabled"

        /** 队列落盘节流间隔 */
        private const val SAVE_QUEUE_THROTTLE_MS = 3_000L
        /** 队列落盘最多保留条数 */
        private const val MAX_SAVE_QUEUE = 500

        const val CMD_RESUME = "com.spotify.music.RESUME"

        /** 手动暂停后拦截外部恢复播放的窗口时长 */
        private const val EXTERNAL_PLAY_GUARD_MS = 15_000L
    }

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    // 兜底：本服务里任何协程抛出未捕获异常都会直接杀进程，必须接住并落盘
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        CrashLogger.log(t, "PlaybackService coroutine")
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashHandler)
    private lateinit var settings: SettingsRepository
    private lateinit var library: LibraryRepository

    private var sleepTimerJob: Job? = null
    private var crossfadeJob: Job? = null
    private var lyricsJob: Job? = null
    private var prefetchJob: Job? = null
    private var lastQueueSaveAt: Long = 0L
    /** 服务刚从磁盘恢复过队列（进程重启后）：等 App 前台连上时自动续播一次 */
    @Volatile private var pendingAutoResume = false
    /**
     * 用户在 App 内手动暂停后的保护窗口：期间拦截外部控制器（vivo 音乐组件 /
     * 原子随身听 / 车机 / 蓝牙重连）发来的恢复播放，避免"刚暂停就被悄悄续上"。
     * 只拦外部包名，App 自己的控制器不受影响。
     */
    @Volatile private var externalPlayGuardUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        CrashLogger.trace("PlaybackService.onCreate")
        settings = SettingsRepository.get(this)
        library = LibraryRepository.get(this)

        player = createPlayer()
        CrashLogger.trace("PlaybackService player created")

        session = MediaLibrarySession.Builder(this, player, libraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        CrashLogger.trace("PlaybackService session built")

        player.addListener(playerListener)
        applyPlaybackSettings()
        restoreEq()
        restoreLastQueue()

        // 25s 兜底重发 lrc_change（覆盖"车机在播放开始后才连上"的情况）+ 定期保存进度
        serviceScope.launch {
            while (isActive) {
                delay(CarLyricsBridge.RESEND_INTERVAL_MS)
                runCatching {
                    if (player.isPlaying) saveLastQueue()
                    if (player.isPlaying && CarLyricsBridge.shouldResendLrc()) {
                        val id = player.currentMediaItem?.mediaId
                        val lrc = id?.let { withContext(Dispatchers.IO) { LyricsLoader.loadWholeLrc(it) } }
                        if (!lrc.isNullOrEmpty()) {
                            session?.setSessionExtras(CarLyricsBridge.atomicExtras(id, lrc))
                            CarLyricsBridge.markLrcSent()
                        }
                    }
                }.onFailure { CrashLogger.log(it, "lrcResendLoop") }
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val useNativeSink = settings.audioOutputMode == "aaudio" || settings.audioOutputMode == "opensles"
        val nativeApi = if (settings.audioOutputMode == "opensles") {
            com.spotify.music.audio.OboeAudioSink.AUDIO_API_OPENSLES
        } else {
            com.spotify.music.audio.OboeAudioSink.AUDIO_API_AAUDIO
        }
        val eqProcessor = com.spotify.music.audio.EqualizerProcessor()
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                if (useNativeSink) {
                    // Oboe must keep one stable stream across track transitions. Convert every
                    // source to float, stereo and a fixed rate before handing it to native output.
                    // This avoids close/open when the next FLAC has a different rate/channel layout.
                    val nativeRate = settings.audioSampleRate.takeIf { it > 0 } ?: 48000
                    val channelMixer = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
                        // Media3 only implements a subset of default constant-power
                        // matrices (7->2 is intentionally unsupported). Use an explicit
                        // safe stereo matrix for every decoder channel count instead.
                        for (channels in 1..8) {
                            val coefficients = FloatArray(channels * 2)
                            if (channels == 1) {
                                coefficients[0] = 0.7071f
                                coefficients[1] = 0.7071f
                            } else {
                                coefficients[0] = 1f
                                coefficients[1] = 0f
                                coefficients[2] = 0f
                                coefficients[3] = 1f
                                for (input in 2 until channels) {
                                    coefficients[input * 2] = 0.5f
                                    coefficients[input * 2 + 1] = 0.5f
                                }
                            }
                            putChannelMixingMatrix(
                                androidx.media3.common.audio.ChannelMixingMatrix(
                                    channels,
                                    2,
                                    coefficients,
                                ),
                            )
                        }
                    }
                    val nativeSonic = androidx.media3.common.audio.SonicAudioProcessor().apply {
                        setOutputSampleRateHz(nativeRate)
                    }
                    val nativeProcessors = arrayOf(
                        com.spotify.music.audio.OutputFormatProcessor(
                            requestedBitDepth = "float",
                            preferFloatWhenAutomatic = true,
                        ),
                        channelMixer,
                        eqProcessor,
                        nativeSonic,
                        // EQ stays float internally, but the vivo AAudio/OpenSL endpoint
                        // is kept at I16. Its OpenSL path frequently opens float silently.
                        com.spotify.music.audio.OutputFormatProcessor(
                            requestedBitDepth = "16",
                            preferFloatWhenAutomatic = false,
                        ),
                    )
                    return com.spotify.music.audio.OboeAudioSink(
                        audioApi = nativeApi,
                        exclusive = settings.usbExclusive,
                        deviceId = settings.audioOutputDeviceId,
                        processors = nativeProcessors.toList(),
                    )
                }

                val sonic = androidx.media3.common.audio.SonicAudioProcessor().apply {
                    setOutputSampleRateHz(
                        settings.audioSampleRate.takeIf { it > 0 }
                            ?: androidx.media3.common.audio.SonicAudioProcessor.SAMPLE_RATE_NO_CHANGE,
                    )
                }
                // Normalize every path to float + stereo before EQ. EQ then always sees
                // a stable 2-channel float stream, regardless of source FLAC layout/depth.
                val channelMixer = androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
                    for (channels in 1..8) {
                        val coefficients = FloatArray(channels * 2)
                        if (channels == 1) {
                            coefficients[0] = 0.7071f
                            coefficients[1] = 0.7071f
                        } else {
                            coefficients[0] = 1f
                            coefficients[3] = 1f
                            for (input in 2 until channels) {
                                coefficients[input * 2] = 0.5f
                                coefficients[input * 2 + 1] = 0.5f
                            }
                        }
                        putChannelMixingMatrix(
                            androidx.media3.common.audio.ChannelMixingMatrix(channels, 2, coefficients),
                        )
                    }
                }
                val processors = arrayOf(
                    com.spotify.music.audio.OutputFormatProcessor(
                        requestedBitDepth = "float",
                        preferFloatWhenAutomatic = true,
                    ),
                    channelMixer,
                    sonic,
                    eqProcessor,
                    com.spotify.music.audio.OutputFormatProcessor(
                        requestedBitDepth = settings.audioBitDepth,
                        preferFloatWhenAutomatic = enableFloatOutput,
                    ),
                )
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(processors)
                    .setEnableFloatOutput(enableFloatOutput || settings.audioBitDepth == "float")
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ !settings.concurrentPlayback,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 关闭向平台诊断服务上报播放异常：部分 ROM（vivo/OriginOS）的实现
            // 会在异常上报路径上出岔子，我们用自己的 CrashLogger 取证即可
            .setUsePlatformDiagnostics(false)
            .build()
    }

    /** Rebuild the renderer/sink while retaining the current queue, position, and play intent. */
    private fun rebuildAudioOutput() {
        val oldPlayer = player
        val items = (0 until oldPlayer.mediaItemCount).map { oldPlayer.getMediaItemAt(it) }
        val index = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
        val position = oldPlayer.currentPosition.coerceAtLeast(0L)
        val shouldPlay = oldPlayer.playWhenReady
        runCatching { oldPlayer.removeListener(playerListener) }
        crossfadeJob?.cancel()
        val replacement = createPlayer()
        player = replacement
        replacement.addListener(playerListener)
        session?.setPlayer(replacement)
        oldPlayer.release()
        applyPlaybackSettings()
        restoreEq()
        if (items.isNotEmpty()) {
            replacement.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            replacement.prepare()
            replacement.playWhenReady = shouldPlay
        }
        CrashLogger.trace("audio output rebuilt mode=${settings.audioOutputMode} rate=${settings.audioSampleRate} depth=${settings.audioBitDepth}")
    }

    private fun applyPlaybackSettings() {
        runCatching {
            // 倍速必须 > 0，否则 ExoPlayer 会抛异常直接崩掉服务
            val speed = settings.playbackSpeed
            if (speed > 0f) player.setPlaybackSpeed(speed)
            player.skipSilenceEnabled = settings.skipSilence
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ !settings.concurrentPlayback,
            )
            if (settings.sleepTimerMinutes > 0) {
                startSleepTimer(settings.sleepTimerMinutes)
            }
        }.onFailure { CrashLogger.log(it, "applyPlaybackSettings") }
    }

    // ────────────────────────── 恢复 / 预取 / EQ ──────────────────────────

    /** 从磁盘恢复上次队列与进度（暂停态）。进程被杀后重启也能接着播。 */
    private fun restoreLastQueue() {
        runCatching {
            val paths = settings.lastQueuePaths
            if (paths.isEmpty()) return
            if (player.mediaItemCount > 0) return
            val idx = settings.lastQueueIndex
            val pos = settings.lastQueuePositionMs
            serviceScope.launch {
                runCatching {
                    val songs = library.songsByPaths(paths)
                    val items = MediaItemFactory.fromPaths(songs)
                    if (items.isEmpty()) return@launch
                    player.setMediaItems(items, idx.coerceIn(0, items.size - 1), pos.coerceAtLeast(0L))
                    player.prepare()
                    player.playWhenReady = false
                    pendingAutoResume = true
                    CrashLogger.trace("restoreQueue done items=${items.size} idx=$idx pos=$pos")
                }.onFailure { CrashLogger.log(it, "restoreLastQueue") }
            }
        }.onFailure { CrashLogger.log(it, "restoreLastQueue") }
    }

    /** 启动时把已保存的 EQ 参数装进音频链（系统模式 = AudioProcessor；原生模式 = 推给原生 DSP） */
    private fun restoreEq() {
        runCatching {
            com.spotify.music.audio.EqState.setAll(
                settings.eqEnabled,
                settings.eqPreampDb,
                settings.eqBassBoostDb,
                settings.eqTrebleDb,
                settings.eqWidth,
                settings.getEqBands(),
            )
        }.onFailure { CrashLogger.log(it, "restoreEq") }
    }

    /**
     * 预取队列后续 2-3 首的封面与歌词到缓存：
     * 切歌时车机立刻能拿到封面（高解析内嵌封面解码慢，现取会来不及推送）。
     */
    private fun prefetchUpcoming() {
        prefetchJob?.cancel()
        prefetchJob = serviceScope.launch {
            runCatching {
                val cur = player.currentMediaItemIndex
                val n = player.mediaItemCount
                val upcoming = ArrayList<String>(3)
                for (off in 1..3) {
                    val i = cur + off
                    if (i < n) {
                        val id = player.getMediaItemAt(i).mediaId
                        if (!id.isNullOrEmpty()) upcoming.add(id)
                    }
                }
                if (upcoming.isEmpty()) return@launch
                withContext(Dispatchers.IO) {
                    for (path in upcoming) {
                        runCatching {
                            CoverLoader.load(path)
                            CoverLoader.loadThumbBytes(path)
                            LyricsLoader.loadWholeLrc(path)
                        }
                    }
                }
                CrashLogger.trace("prefetch done n=${upcoming.size}")
            }.onFailure { CrashLogger.log(it, "prefetchUpcoming") }
        }
    }

    // ────────────────────────── 播放器监听 ──────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            CrashLogger.trace("onMediaItemTransition id=${mediaItem?.mediaId} reason=$reason")
            handleTrackChanged()
            saveLastQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 回调跑在主线程 Looper 上，这里抛出任何异常都会变成未捕获异常直接杀进程
            runCatching {
                CrashLogger.trace("service isPlaying=$isPlaying playWhenReady=${player.playWhenReady}")
                if (isPlaying) {
                    pendingAutoResume = false
                    startCrossfadeWatch()
                } else {
                    crossfadeJob?.cancel()
                    player.volume = 1f
                    // 暂停即落盘：进程被杀也能从暂停点恢复
                    saveLastQueue(force = true)
                }
            }.onFailure { CrashLogger.log(it, "onIsPlayingChanged") }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            CrashLogger.trace("onPlaybackStateChanged state=$playbackState")
            saveLastQueue()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            CrashLogger.log(error, "onPlayerError")
            CrashLogger.trace("onPlayerError ${error.errorCodeName}: ${error.message}")
        }
    }

    /**
     * 车载歌词唯一出口：
     * 切歌 → 清旧词 + 发空歌词事件（清原子随身听）→ 主动加载歌词（不依赖 UI）
     * → 歌词就绪后 decorate（ucar LYRICS_WHOLE + support_event）→ replaceMediaItem 强推
     * → 原子随身听 lrc_change。
     */
    private fun handleTrackChanged() {
        try {
            val id = player.currentMediaItem?.mediaId
            CarLyricsBridge.onTrackChanged(id)
            if (!CarLyricsBridge.enabled) return

        // 切歌先发一次空歌词事件，清除原子随身听里上一首的歌词
        session?.setSessionExtras(CarLyricsBridge.atomicExtras(id, ""))

        // 去重：一次起播会连着来多次 onMediaItemTransition（PLAYLIST_CHANGED →
        // MEDIA_ITEM_CHANGED），不加这句就会并发跑多个注入协程，重复 decode 封面、
        // 重复 replaceMediaItem（日志里能看到同样的 ENTER/replace 各出现两次）
        lyricsJob?.cancel()
        lyricsJob = serviceScope.launch {
            try {
                loadAndInjectLyrics()
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    CrashLogger.log(t, "loadAndInjectLyrics")
                }
            }
        }
        prefetchUpcoming()
        } catch (t: Throwable) {
            CrashLogger.log(t, "handleTrackChanged")
        }
    }

    private suspend fun loadAndInjectLyrics() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val path = item.mediaId ?: return
        CrashLogger.trace("loadAndInjectLyrics ENTER idx=$index path=$path")

        val lrc = withContext(Dispatchers.IO) { LyricsLoader.loadWholeLrc(path) }
        CrashLogger.trace("loadAndInjectLyrics lrc=${lrc?.length ?: 0} chars")
        // 曲目可能已再次切换
        if (player.currentMediaItemIndex != index) return
        val current = player.currentMediaItem ?: return
        if (current.mediaId != item.mediaId) return

        CarLyricsBridge.seedLrc(item.mediaId, lrc)

        val coverBytes = if (current.mediaMetadata.artworkData == null) {
            withContext(Dispatchers.IO) { CoverLoader.loadThumbBytes(path) }
        } else null
        CrashLogger.trace("loadAndInjectLyrics cover=${coverBytes?.size ?: 0} bytes")

        var newItem = current
        if (coverBytes != null) {
            newItem = newItem.buildUpon()
                .setMediaMetadata(
                    newItem.mediaMetadata.buildUpon()
                        .setArtworkData(coverBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        // 同理必须显式声明，否则 Media3 转换时抛异常闪退
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        val decorated = MediaItemFactory.ensurePlayable(CarLyricsBridge.decorate(newItem))
        if (decorated !== current) {
            CrashLogger.trace("loadAndInjectLyrics replaceMediaItem idx=$index")
            runCatching { player.replaceMediaItem(index, decorated) }
                .onFailure { CrashLogger.log(it, "replaceMediaItem") }
        }

        if (!lrc.isNullOrEmpty()) {
            session?.setSessionExtras(CarLyricsBridge.atomicExtras(item.mediaId, lrc))
            CarLyricsBridge.markLrcSent()
        }
    }

    // ────────────────────────── 睡眠定时器 ──────────────────────────

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            settings.sleepTimerMinutes = 0
            return
        }
        settings.sleepTimerMinutes = minutes
        sleepTimerJob = serviceScope.launch {
            delay(minutes * 60_000L)
            player.pause()
            settings.sleepTimerMinutes = 0
        }
    }

    // ────────────────────────── 淡入淡出 ──────────────────────────

    private fun startCrossfadeWatch() {
        runCatching {
            crossfadeJob?.cancel()
            val seconds = settings.crossfadeSeconds
            if (seconds <= 0) return
            val fadeMs = seconds * 1000L
            player.volume = 1f
            crossfadeJob = serviceScope.launch {
                while (isActive && player.isPlaying) {
                    val duration = player.duration
                    val pos = player.currentPosition
                    if (duration != C.TIME_UNSET && duration > fadeMs && duration - pos <= fadeMs) {
                        val remain = duration - pos
                        player.volume = (remain.toFloat() / fadeMs).coerceIn(0f, 1f)
                    }
                    delay(200)
                }
            }
        }.onFailure { CrashLogger.log(it, "startCrossfadeWatch") }
    }

    // ────────────────────────── 会话回调 ──────────────────────────

    private fun libraryCallback() = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ConnectionResult {
            val sessionCommands = ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CMD_APPLY_SETTINGS, Bundle.EMPTY))
                .add(SessionCommand(CMD_REBUILD_OUTPUT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_SILENCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_TOGGLE_PLAY_PAUSE, Bundle.EMPTY))
                .add(SessionCommand(CMD_RESUME, Bundle.EMPTY))
                .build()
            return ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            commandCode: Int,
        ): Int {
            runCatching {
                val external = controller.packageName != packageName
                CrashLogger.trace(
                    "player command req pkg=${controller.packageName} external=$external " +
                        "code=$commandCode isPlaying=${player.isPlaying} playWhenReady=${player.playWhenReady}"
                )
                if (external && commandCode == Player.COMMAND_PLAY_PAUSE) {
                    val now = SystemClock.elapsedRealtime()
                    if (now < externalPlayGuardUntilMs && !player.isPlaying) {
                        CrashLogger.trace("external play suppressed (recent in-app pause)")
                        return SessionResult.RESULT_INFO_SKIPPED
                    }
                }
                // "允许外部设备开始播放"：关闭时，外部控制器不能在无队列时启动播放
                if (!settings.allowExternalStart && external &&
                    player.mediaItemCount == 0 &&
                    commandCode == Player.COMMAND_PLAY_PAUSE
                ) {
                    return SessionResult.RESULT_INFO_SKIPPED
                }
            }.onFailure { CrashLogger.log(it, "onPlayerCommandRequest") }
            return super.onPlayerCommandRequest(session, controller, commandCode)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            runCatching {
                when (customCommand.customAction) {
                    CMD_SLEEP_TIMER -> startSleepTimer(
                        args.getInt(CMD_ARG_MINUTES, 0)
                    )
                    CMD_APPLY_SETTINGS -> {
                        applyPlaybackSettings()
                        restoreEq()
                    }
                    CMD_REBUILD_OUTPUT -> rebuildAudioOutput()
                    CMD_SKIP_SILENCE -> player.skipSilenceEnabled = args.getBoolean(CMD_ARG_ENABLED)
                    CMD_TOGGLE_PLAY_PAUSE -> {
                        val intended = player.isPlaying || player.playWhenReady
                        CrashLogger.trace("service mini toggle intended=$intended isPlaying=${player.isPlaying} playWhenReady=${player.playWhenReady}")
                        if (intended) {
                            // A real user pause cancels the cold-start resume intent, so a
                            // late UI resume command cannot start playback again.
                            pendingAutoResume = false
                            externalPlayGuardUntilMs =
                                SystemClock.elapsedRealtime() + EXTERNAL_PLAY_GUARD_MS
                            player.playWhenReady = false
                            player.pause()
                        } else {
                            player.playWhenReady = true
                            player.play()
                        }
                    }
                    CMD_RESUME -> if (pendingAutoResume) {
                        pendingAutoResume = false
                        player.play()
                        CrashLogger.trace("auto-resume after restore")
                        // On cold start the car/Atomic controller often connects after
                        // playback has already resumed. Re-send the current track lyrics
                        // after the controller has had time to subscribe to the session.
                        serviceScope.launch {
                            delay(1200L)
                            runCatching {
                                val id = player.currentMediaItem?.mediaId
                                val lrc = id?.let { withContext(Dispatchers.IO) { LyricsLoader.loadWholeLrc(it) } }
                                if (!lrc.isNullOrEmpty()) {
                                    CarLyricsBridge.seedLrc(id, lrc)
                                    session?.setSessionExtras(CarLyricsBridge.atomicExtras(id, lrc))
                                    CarLyricsBridge.markLrcSent()
                                    CrashLogger.trace("startup lyrics resend id=$id chars=${lrc.length}")
                                }
                            }.onFailure { CrashLogger.log(it, "startup lyrics resend") }
                        }
                    }
                }
            }.onFailure { CrashLogger.log(it, "onCustomCommand ${customCommand.customAction}") }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val paths = settings.lastQueuePaths
                    val idx = settings.lastQueueIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
                    val items = library.songsByPaths(paths).map { MediaItemFactory.from(it) }
                    if (items.isEmpty()) {
                        future.setException(IllegalStateException("no saved queue"))
                    } else {
                        future.set(MediaSession.MediaItemsWithStartPosition(items, idx, settings.lastQueuePositionMs))
                    }
                } catch (t: Throwable) {
                    future.setException(t)
                }
            }
            return future
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false) // 文件夹节点不可播放；不显式声明同样会被 Media3 校验拒绝
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }
    }

    /**
     * 保存上次队列（供外部设备恢复播放）。
     *
     * 原先每次 onPlaybackStateChanged / onMediaItemTransition 都会把整个队列
     * （可能是上千条路径）序列化成 JSON 写进 SharedPreferences，而这个回调在
     * 起播时会连着来好几发，主线程被反复拖住。现在：
     *   - 3 秒节流
     *   - 只保留前 [MAX_SAVE_QUEUE] 条（恢复播放用不到全量）
     *   - 读播放器只准在播放器所在线程（这里是主线程），JSON 序列化挪到 IO
     */
    private fun saveLastQueue(force: Boolean = false) {
        runCatching {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && now - lastQueueSaveAt < SAVE_QUEUE_THROTTLE_MS) return
            lastQueueSaveAt = now
            val count = player.mediaItemCount
            if (count == 0) return
            val n = minOf(count, MAX_SAVE_QUEUE)
            val paths = ArrayList<String>(n)
            for (i in 0 until n) {
                paths.add(player.getMediaItemAt(i).mediaId ?: "")
            }
            val idx = player.currentMediaItemIndex
            val pos = player.currentPosition.coerceAtLeast(0L) // 原生 sink 初始化前可能返回异常值
            serviceScope.launch(Dispatchers.IO) {
                runCatching {
                    settings.lastQueuePaths = paths
                    settings.lastQueueIndex = idx
                    settings.lastQueuePositionMs = pos
                }.onFailure { CrashLogger.log(it, "saveLastQueue/write") }
            }
        }.onFailure { CrashLogger.log(it, "saveLastQueue") }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        saveLastQueue(force = true)
        sleepTimerJob?.cancel()
        crossfadeJob?.cancel()
        lyricsJob?.cancel()
        prefetchJob?.cancel()
        player.removeListener(playerListener)
        session?.release()
        player.release()
        session = null
        serviceScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉后台：先把当前进度存下来再决定要不要停服务
        saveLastQueue(force = true)
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
}
