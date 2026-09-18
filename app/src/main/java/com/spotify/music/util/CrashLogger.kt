package com.spotify.music.util

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import com.spotify.music.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃 / 异常取证。
 *
 * 为什么重写：
 *  上一版把「业务里 catch 住的异常」和「真正的崩溃」混在了一个 key 里，
 *  于是弹窗显示的其实是无关紧要的 MediaMetadataRetriever 失败（红鲱鱼），
 *  而真正让进程死掉的原因（Java 未捕获异常落盘失败 / native 崩溃 / 被系统杀）
 *  一条都没记录到，导致无法定位。
 *
 * 这一版三条腿：
 *  1. 【致命】Thread.UncaughtExceptionHandler —— 走 KEY_CRASH，弹窗显示
 *  2. 【系统级】ActivityManager.getHistoricalProcessExitReasons()（API 30+，无需权限）
 *     —— 能抓到 Java 崩溃之外的所有死法：REASON_CRASH_NATIVE（含 tombstone）、
 *        REASON_LOW_MEMORY（被 OOM killer 杀）、REASON_ANR、REASON_EXCESSIVE_RESOURCE_USAGE
 *        （vivo/OriginOS 常见的"过度消耗资源"强杀）、REASON_SIGNALED 等
 *  3. 【面包屑】关键动作同步落盘到 trace 日志 —— 进程被瞬间杀掉也能看到"死前最后一步"
 *
 * 非致命异常（log()）只写文件 + KEY_ERROR，不再冒充崩溃弹窗。
 */
object CrashLogger {

    private const val PREFS = "crash_prefs"
    private const val KEY_CRASH = "last_crash"      // 致命：Java 未捕获异常
    private const val KEY_ERROR = "last_error"      // 非致命：仅记录，不弹窗
    private const val KEY_RESTART_AT = "last_restart_at"
    private const val KEY_EXIT_SEEN = "last_exit_ts"

    private const val FILE_NAME = "samsung_music_crash.log"
    private const val TRACE_NAME = "samsung_music_trace.log"
    private const val MAX_BYTES = 512 * 1024
    private const val TRACE_MAX_BYTES = 192 * 1024
    private const val TRACE_KEEP_BYTES = 96 * 1024
    private const val MAX_STORED = 8000
    /** 10 秒内不重复自动重启，避免崩溃循环把机器卡死 */
    private const val RESTART_GUARD_MS = 10_000L

    private val lock = Any()
    private val traceLock = Any()

    @Volatile
    private var app: Application? = null

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /** 安装全局未捕获异常处理器 */
    fun install(application: Application) {
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 先写一份极简记录（不分配大对象）：万一紧接着的落盘因为 OOM 失败，至少留个线索
            runCatching {
                traceInternal(
                    "FATAL ${throwable.javaClass.simpleName}: ${throwable.message} @${thread.name}",
                    application,
                )
            }
            runCatching { saveFatal(application, thread, throwable) }
            runCatching { maybeRestart(application) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 业务侧主动记录的异常（非致命，不会弹窗、不会重启） */
    fun log(t: Throwable, tag: String = "EXCEPTION") {
        val ctx = app ?: return
        runCatching { save(ctx, Thread.currentThread(), t, tag, fatal = false) }
    }

    /**
     * 面包屑：同步落盘，进程下一微秒被杀也能留住最后一行。
     * 只在关键路径调用（播放、切歌、扫描、页面切换、内存采样），不要放进高频循环。
     */
    fun trace(msg: String) {
        val ctx = app ?: return
        runCatching { traceInternal(msg, ctx) }
    }

    /** 内存水位（MB） */
    fun mem(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val total = rt.totalMemory() / 1048576
        val max = rt.maxMemory() / 1048576
        return "mem ${used}MB/${total}MB(max ${max}MB)"
    }

    /** 崩溃日志文件路径（告诉用户去哪拿） */
    fun logPath(context: Context): String =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME).absolutePath

    /** 面包屑日志路径 */
    fun tracePath(context: Context): String =
        File(context.getExternalFilesDir(null) ?: context.filesDir, TRACE_NAME).absolutePath

    /** 上次致命 Java 崩溃（未确认则一直保留） */
    fun pendingCrash(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRASH, null)

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CRASH).apply()
    }

    /**
     * 读取系统记录的「进程上次是怎么死的」。这是唯一能在无 adb 的前提下
     * 抓到 native 崩溃 / OOM 强杀 / ANR / 资源超限强杀 的手段。
     *
     * @return 组装好的诊断文本；没有新记录则返回 null
     */
    fun captureExitReasons(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 10) }
            .getOrNull() ?: return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
        var newest = seen

        val sb = StringBuilder(8192)
        var found = false
        for (info in infos.sortedBy { it.timestamp }) {
            if (info.timestamp <= seen) continue
            newest = maxOf(newest, info.timestamp)
            found = true
            sb.append("==== 进程终止记录 ====\n")
            sb.append("time=").append(dayFmt.format(Date(info.timestamp)))
                .append("  pid=").append(info.pid)
                .append("  importance=").append(info.importance).append('\n')
            sb.append("reason=").append(reasonName(info.reason))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.reason == ApplicationExitInfo.REASON_SIGNALED) {
                sb.append(" (status=").append(info.status).append(')')
            }
            sb.append('\n')
            info.description?.takeIf { it.isNotBlank() }?.let {
                sb.append("description=").append(it).append('\n')
            }
            // REASON_CRASH_NATIVE 会给 tombstone；REASON_ANR 会给 ANR trace
            val traceText = runCatching {
                info.traceInputStream?.use { input ->
                    val all = input.readBytes().toString(Charsets.UTF_8)
                    all.lineSequence().take(80).joinToString("\n")
                }
            }.getOrNull()
            if (!traceText.isNullOrBlank()) {
                sb.append("---- trace ----\n").append(traceText).append('\n')
            }
            sb.append('\n')
        }
        prefs.edit().putLong(KEY_EXIT_SEEN, newest).apply()
        if (!found) return null

        // 附上死前的面包屑（来自 trace 文件，进程已死，内存里的没用了）
        val tail = runCatching { readTraceTail(context) }.getOrNull()
        if (!tail.isNullOrBlank()) {
            sb.append("==== 死前最后动作（面包屑）====\n").append(tail).append('\n')
        }
        val text = sb.toString()
        runCatching { writeFiles(context, text) }
        return text
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    private fun saveFatal(ctx: Context, thread: Thread, t: Throwable) {
        save(ctx, thread, t, "FATAL/Uncaught", fatal = true)
    }

    private fun save(ctx: Context, thread: Thread, t: Throwable, tag: String, fatal: Boolean) {
        val ts = dayFmt.format(Date())
        val sb = StringBuilder(4096)
        sb.append("==== ").append(tag).append(" @ ").append(ts).append(" ====\n")
        sb.append("thread=").append(thread.name).append(" (id=").append(thread.id).append(")\n")
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("  android=").append(Build.VERSION.RELEASE)
            .append("  sdk=").append(Build.VERSION.SDK_INT).append('\n')
        sb.append(mem()).append('\n')
        appendThrowable(sb, t, null)
        sb.append('\n')
        val text = sb.toString()

        runCatching { writeFiles(ctx, text) }
        runCatching {
            val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(if (fatal) KEY_CRASH else KEY_ERROR, text.take(MAX_STORED))
            if (fatal) e.putLong("last_crash_at", System.currentTimeMillis())
            e.apply()
        }
    }

    private fun traceInternal(msg: String, ctx: Context) {
        val line = "[${tsFmt.format(Date())}] [${Thread.currentThread().name}] $msg | ${mem()}\n"
        synchronized(traceLock) {
            val files = traceTargets(ctx)
            for (f in files) {
                runCatching {
                    f.parentFile?.mkdirs()
                    if (f.exists() && f.length() > TRACE_MAX_BYTES) {
                        val bytes = f.readBytes()
                        val keep = bytes.copyOfRange(
                            (bytes.size - TRACE_KEEP_BYTES).coerceAtLeast(0),
                            bytes.size,
                        )
                        f.writeBytes(keep)
                    }
                    f.appendText(line)
                }
            }
        }
    }

    private fun readTraceTail(context: Context): String? {
        val f = traceTargets(context).firstOrNull { it.exists() } ?: return null
        val bytes = f.readBytes()
        val keep = bytes.copyOfRange((bytes.size - 4096).coerceAtLeast(0), bytes.size)
        val text = keep.toString(Charsets.UTF_8)
        val idx = text.indexOf('\n')
        return if (idx >= 0) text.substring(idx + 1) else text
    }

    private fun traceTargets(ctx: Context): List<File> {
        val out = mutableListOf<File>()
        ctx.getExternalFilesDir(null)?.let { out.add(File(it, TRACE_NAME)) }
        out.add(File(ctx.filesDir, TRACE_NAME))
        return out
    }

    private fun writeFiles(ctx: Context, text: String) {
        val targets = linkedSetOf<File>()
        // 应用私有外部目录：无需任何权限，Android 11+ 也一定可写
        ctx.getExternalFilesDir(null)?.let { targets.add(File(it, FILE_NAME)) }
        targets.add(File(ctx.filesDir, FILE_NAME))
        // 已授予「所有文件管理权限」时，再写一份到公共 Documents 方便用户查找
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            runCatching {
                val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                targets.add(File(docs, FILE_NAME))
            }
        }
        synchronized(lock) {
            for (f in targets) {
                runCatching {
                    f.parentFile?.mkdirs()
                    if (f.exists() && f.length() > MAX_BYTES) f.delete()
                    f.appendText(text)
                }
            }
        }
    }

    private fun maybeRestart(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_RESTART_AT, 0L) < RESTART_GUARD_MS) return
        prefs.edit().putLong(KEY_RESTART_AT, now).apply()
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(0)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF(自杀/System.exit)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(被信号杀)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(内存不足被杀)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(Java 未捕获异常)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(native 崩溃)"
        ApplicationExitInfo.REASON_ANR -> "ANR(无响应)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE(资源超限被杀)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED(用户主动移除)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED(用户强行停止)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED(依赖进程死亡)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "CODE_$reason"
    }

    private fun appendThrowable(sb: StringBuilder, t: Throwable, prefix: String?) {
        if (prefix != null) sb.append(prefix)
        sb.append(t.toString()).append('\n')
        for (el in t.stackTrace.take(80)) {
            sb.append("    at ").append(el.toString()).append('\n')
        }
        t.cause?.let { appendThrowable(sb, it, "Caused by: ") }
        for (s in t.suppressed) {
            appendThrowable(sb, s, "Suppressed: ")
        }
    }

    /** 进程已运行毫秒数，用于面包屑里判断"多久之后死的" */
    fun uptime(): Long = SystemClock.elapsedRealtime()
}
