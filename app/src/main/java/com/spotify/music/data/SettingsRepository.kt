package com.spotify.music.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 全部应用设置（SharedPreferences） */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("samsung_music_settings", Context.MODE_PRIVATE)

    // ── 扫描目录 ──
    var scanDirs: Set<String>
        get() {
            val stored = prefs.getStringSet(KEY_SCAN_DIRS, null)?.toSet()
                ?: return setOf(DEFAULT_SCAN_DIR)
            // 旧版本默认只扫 Music：升级后迁移到存储根，否则用户会以为扫描坏了
            return if (stored == setOf(LEGACY_SCAN_DIR)) setOf(DEFAULT_SCAN_DIR) else stored
        }
        set(value) = prefs.edit().putStringSet(KEY_SCAN_DIRS, value).apply()

    // ── 隐藏的文件夹 ──
    var hiddenFolders: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_FOLDERS, value).apply()

    // ── 一般 ──
    var carLyricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAR_LYRICS, true)
        set(value) = prefs.edit().putBoolean(KEY_CAR_LYRICS, value).apply()

    var concurrentPlayback: Boolean
        get() = prefs.getBoolean(KEY_CONCURRENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONCURRENT, value).apply()

    var darkMode: String
        get() = prefs.getString(KEY_DARK_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_DARK_MODE, value).apply()

    var allowExternalStart: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_START, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTERNAL_START, value).apply()

    // ── 播放 ──
    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var crossfadeSeconds: Int
        get() = prefs.getInt(KEY_CROSSFADE, 0)
        set(value) = prefs.edit().putInt(KEY_CROSSFADE, value).apply()

    var skipSilence: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()

    var lockscreenControl: Boolean
        get() = prefs.getBoolean(KEY_LOCKSCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCKSCREEN, value).apply()

    var ambientPlayback: Boolean
        get() = prefs.getBoolean(KEY_AMBIENT, true)
        set(value) = prefs.edit().putBoolean(KEY_AMBIENT, value).apply()

    var sleepTimerMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_TIMER, 0)
        set(value) = prefs.edit().putInt(KEY_SLEEP_TIMER, value).apply()

    // ── 播放列表 ──
    var queueNoDuplicates: Boolean
        get() = prefs.getBoolean(KEY_NO_DUP, true)
        set(value) = prefs.edit().putBoolean(KEY_NO_DUP, value).apply()

    // ── 收藏 ──
    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    fun toggleFavorite(path: String): Set<String> {
        val cur = favorites.toMutableSet()
        if (!cur.add(path)) cur.remove(path)
        favorites = cur
        return cur
    }

    // ── 自建播放列表 ──
    fun getPlaylists(): MutableList<Pair<String, MutableList<String>>> {
        val json = prefs.getString(KEY_PLAYLISTS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Pair<String, MutableList<String>>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val paths = mutableListOf<String>()
                val parr = o.getJSONArray("paths")
                for (j in 0 until parr.length()) paths.add(parr.getString(j))
                list.add(o.getString("name") to paths)
            }
            list
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun savePlaylists(list: List<Pair<String, List<String>>>) {
        val arr = JSONArray()
        list.forEach { (name, paths) ->
            val o = JSONObject()
            o.put("name", name)
            o.put("paths", JSONArray(paths))
            arr.put(o)
        }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    // ── 底部标签页管理（隐藏/排序） ──
    var hiddenTabs: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_TABS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_TABS, value).apply()

    var tabOrder: List<String>
        get() {
            val json = prefs.getString(KEY_TAB_ORDER, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (t: Throwable) {
                emptyList()
            }
        }
        set(value) {
            prefs.edit().putString(KEY_TAB_ORDER, JSONArray(value).toString()).apply()
        }

    // ── 上次播放队列（用于外部设备恢复播放） ──
    var lastQueuePaths: List<String>
        get() {
            val json = prefs.getString(KEY_LAST_QUEUE, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (t: Throwable) {
                emptyList()
            }
        }
        set(value) {
            prefs.edit().putString(KEY_LAST_QUEUE, JSONArray(value).toString()).apply()
        }

    var lastQueueIndex: Int
        get() = prefs.getInt(KEY_LAST_QUEUE_IDX, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_QUEUE_IDX, value).apply()

    var lastQueuePositionMs: Long
        get() = prefs.getLong(KEY_LAST_QUEUE_POS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_QUEUE_POS, value).apply()

    /**
     * 上次退出的播放意图（播放中 / 已暂停）。
     *
     * 队列与进度之外必须单独记一个「是否在播」：进程被杀后重启若不看这个标记，
     * 恢复播放就无法区分「用户暂停过」和「用户正在播放」，于是暂停也会被
     * auto-resume 拉回播放，表现成「点暂停无效，音乐继续播放」。
     * 旧版本没有这个键 → 默认 true，保留原来「接着播」的行为。
     */
    var lastQueuePlaying: Boolean
        get() = prefs.getBoolean(KEY_LAST_QUEUE_PLAYING, true)
        set(value) = prefs.edit().putBoolean(KEY_LAST_QUEUE_PLAYING, value).apply()

    // ── 均衡器（软件 EQ） ──
    var eqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

    var eqPreampDb: Float
        get() = prefs.getFloat(KEY_EQ_PREAMP, 0f)
        set(value) = prefs.edit().putFloat(KEY_EQ_PREAMP, value).apply()

    var eqBassBoostDb: Float
        get() = prefs.getFloat(KEY_EQ_BASS, 0f)
        set(value) = prefs.edit().putFloat(KEY_EQ_BASS, value).apply()

    /** 高音增强（dB），6kHz 高架 */
    var eqTrebleDb: Float
        get() = prefs.getFloat(KEY_EQ_TREBLE, 0f)
        set(value) = prefs.edit().putFloat(KEY_EQ_TREBLE, value).apply()

    /** 环绕增强 0~1 */
    var eqWidth: Float
        get() = prefs.getFloat(KEY_EQ_WIDTH, 0f)
        set(value) = prefs.edit().putFloat(KEY_EQ_WIDTH, value).apply()

    /** 系统 AudioTrack 输出格式：16-bit 或 float32；AAudio 原生后端固定接收 float PCM。 */
    var audioBitDepth: String
        get() = prefs.getString(KEY_AUDIO_BIT_DEPTH, "16") ?: "16"
        set(value) = prefs.edit().putString(KEY_AUDIO_BIT_DEPTH, value).apply()

    /** 系统输出目标采样率（Hz），0 表示跟随音源。 */
    var audioSampleRate: Int
        get() = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, 0)
        set(value) = prefs.edit().putInt(KEY_AUDIO_SAMPLE_RATE, value.coerceAtLeast(0)).apply()

    /** 输出后端：system / aaudio / opensles（均通过 Halcyon Oboe sink）。 */
    var audioOutputMode: String
        get() {
            val value = prefs.getString(KEY_AUDIO_OUTPUT_MODE, "system") ?: "system"
            return if (value == "native") "aaudio" else value
        }
        set(value) = prefs.edit().putString(KEY_AUDIO_OUTPUT_MODE, value).apply()

    /** Oboe 独占模式；通常只建议 USB DAC 使用。 */
    var usbExclusive: Boolean
        get() = prefs.getBoolean(KEY_USB_EXCLUSIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_USB_EXCLUSIVE, value).apply()

    /** 输出通道：Oboe 设备 id（AudioDeviceInfo.getId()），-1 = 自动跟随系统 */
    var audioOutputDeviceId: Int
        get() = prefs.getInt(KEY_AUDIO_OUTPUT_DEVICE, -1)
        set(value) = prefs.edit().putInt(KEY_AUDIO_OUTPUT_DEVICE, value).apply()

    fun getEqBands(): FloatArray {
        val json = prefs.getString(KEY_EQ_BANDS, null) ?: return FloatArray(10)
        return try {
            val arr = JSONArray(json)
            FloatArray(10) { i ->
                if (i < arr.length()) arr.getDouble(i).toFloat() else 0f
            }
        } catch (t: Throwable) {
            FloatArray(10)
        }
    }

    fun setEqBands(bands: FloatArray) {
        val arr = JSONArray()
        for (b in bands) arr.put(b.toDouble())
        prefs.edit().putString(KEY_EQ_BANDS, arr.toString()).apply()
    }

    // ── 排序 ──
    var sortMode: String
        get() = prefs.getString(KEY_SORT, "title") ?: "title"
        set(value) = prefs.edit().putString(KEY_SORT, value).apply()

    // ── 首次扫描标记 ──
    var firstScanDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SCAN, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_SCAN, value).apply()

    /**
     * 首次启动引导页是否已完成。
     * 老用户升级（已有首次扫描记录）直接视为完成，不再打扰；仅全新安装显示引导页。
     */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, prefs.contains(KEY_FIRST_SCAN))
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    companion object {
        // 默认扫描整个存储根目录（递归含子文件夹），这样 Music / HIRES / Download 等
        // 任意位置的音频都能被发现；用户可在「隐藏文件夹」里剔除不需要的目录。
        const val DEFAULT_SCAN_DIR = "/storage/emulated/0"
        const val LEGACY_SCAN_DIR = "/storage/emulated/0/Music"
        private const val KEY_SCAN_DIRS = "scan_dirs"
        private const val KEY_HIDDEN_FOLDERS = "hidden_folders"
        private const val KEY_CAR_LYRICS = "car_lyrics_enabled"
        private const val KEY_CONCURRENT = "concurrent_playback"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_EXTERNAL_START = "allow_external_start"
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_CROSSFADE = "crossfade_seconds"
        private const val KEY_SKIP_SILENCE = "skip_silence"
        private const val KEY_LOCKSCREEN = "lockscreen_control"
        private const val KEY_AMBIENT = "ambient_playback"
        private const val KEY_SLEEP_TIMER = "sleep_timer_minutes"
        private const val KEY_NO_DUP = "queue_no_duplicates"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_HIDDEN_TABS = "hidden_tabs"
        private const val KEY_TAB_ORDER = "tab_order"
        private const val KEY_LAST_QUEUE = "last_queue_paths"
        private const val KEY_LAST_QUEUE_IDX = "last_queue_index"
        private const val KEY_LAST_QUEUE_POS = "last_queue_position"
        private const val KEY_LAST_QUEUE_PLAYING = "last_queue_playing"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_FIRST_SCAN = "first_scan_done"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_PREAMP = "eq_preamp_db"
        private const val KEY_EQ_BASS = "eq_bass_db"
        private const val KEY_EQ_TREBLE = "eq_treble_db"
        private const val KEY_EQ_WIDTH = "eq_width"
        private const val KEY_EQ_BANDS = "eq_bands_json"
        private const val KEY_AUDIO_BIT_DEPTH = "audio_bit_depth"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        private const val KEY_AUDIO_OUTPUT_MODE = "audio_output_mode"
        private const val KEY_USB_EXCLUSIVE = "usb_exclusive"
        private const val KEY_AUDIO_OUTPUT_DEVICE = "audio_output_device"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
