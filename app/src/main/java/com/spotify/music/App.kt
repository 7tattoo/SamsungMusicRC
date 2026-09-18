package com.spotify.music

import android.app.Application
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.playback.PlaybackClient
import com.spotify.music.util.CrashLogger

/**
 * 应用入口。单例（SettingsRepository / LibraryRepository / PlaybackClient）均为惰性初始化，
 * 首次访问时通过各自 `get(context)` 创建，便于跨平台迁移时把 Android 依赖收敛在边界。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 崩溃日志必须最先装好，否则后续任何初始化崩溃都抓不到
        CrashLogger.install(this)
        CrashLogger.trace("App.onCreate pid=${android.os.Process.myPid()}")
        // 预热读取（触发 SharedPreferences 与 DB 的早期初始化，避免首扫时发生冷启动抖动）
        // 任何一项失败都不能让 App 起不来
        runCatching { SettingsRepository.get(this) }
            .onFailure { CrashLogger.log(it, "init SettingsRepository") }
        runCatching { LibraryRepository.get(this) }
            .onFailure { CrashLogger.log(it, "init LibraryRepository") }
        runCatching { PlaybackClient.get(this) }
            .onFailure { CrashLogger.log(it, "init PlaybackClient") }
        installMainLooperGuard()
    }

    /**
     * 主线程异常护栏。
     *
     * 像 "mediaMetadata must specify isPlayable" 这类异常是 Media3 在**它自己的内部
     * 回调链**上抛出来的，业务代码的 try/catch 完全够不着，抛出来就直接杀进程。
     *
     * 这里在主 Looper 上再套一层 loop：外层 loop 抛出后由我们接住并落盘，
     * 然后重新进入 loop 继续处理后续消息 —— 异常不再等于闪退。
     */
    private fun installMainLooperGuard() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            var guarded = 0
            while (true) {
                try {
                    android.os.Looper.loop()
                    // loop() 正常返回 = Looper 已 quit，退出护栏，避免空转烧 CPU
                    break
                } catch (t: Throwable) {
                    guarded++
                    CrashLogger.log(t, "MAIN LOOPER GUARD #$guarded")
                    CrashLogger.trace("GUARD caught ${t.javaClass.simpleName}: ${t.message}")
                    if (guarded >= 200) throw t // 真停不下来就别硬撑，让它按原样崩掉
                }
            }
        }
    }
}
