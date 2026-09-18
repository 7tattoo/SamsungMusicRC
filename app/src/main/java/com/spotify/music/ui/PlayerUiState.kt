package com.spotify.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.spotify.music.core.model.Song
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.util.CrashLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放器 UI 状态：观察 MediaController（isPlaying / 进度 / 当前曲目 / 模式）。
 */
class PlayerUiState(
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
) {
    // 兜底：协程里未捕获的异常会直接杀进程（尤其是查库的 songByPath）
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        CrashLogger.log(t, "PlayerUiState coroutine")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashHandler)

    var controller by mutableStateOf<MediaController?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var currentSong by mutableStateOf<Song?>(null)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var queueCount by mutableIntStateOf(0)
        private set
    var queueIndex by mutableIntStateOf(-1)
        private set

    fun attach(c: MediaController) {
        controller = c
        syncFrom(c)
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@PlayerUiState.isPlaying = isPlaying
                if (!isPlaying) this@PlayerUiState.positionMs = c.currentPosition
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncFrom(c)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncFrom(c)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleEnabled = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                this@PlayerUiState.repeatMode = repeatMode
            }
        })
    }

    private fun syncFrom(c: MediaController) {
        isPlaying = c.isPlaying
        durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        positionMs = c.currentPosition
        queueCount = c.mediaItemCount
        queueIndex = c.currentMediaItemIndex
        shuffleEnabled = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        val path = c.currentMediaItem?.mediaId
        if (path != null && path != currentSong?.path) {
            scope.launch { currentSong = library.songByPath(path) }
        } else if (path == null) {
            currentSong = null
        }
    }

    /** 每 500ms 轮询进度（在 Composable 中以 LaunchedEffect 驱动） */
    suspend fun tick() {
        while (true) {
            controller?.let { c ->
                positionMs = c.currentPosition
                durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            }
            delay(500)
        }
    }
}
