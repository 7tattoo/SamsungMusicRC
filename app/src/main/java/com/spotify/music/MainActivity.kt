package com.spotify.music

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.playback.PlaybackClient
import com.spotify.music.ui.PlayerUiState
import com.spotify.music.ui.components.SwipeBackLayout
import com.spotify.music.ui.library.LibraryScreen
import com.spotify.music.ui.player.PlayerScreen
import com.spotify.music.ui.settings.HiddenFoldersScreen
import com.spotify.music.ui.settings.OutputScreen
import com.spotify.music.ui.settings.ScanDirsScreen
import com.spotify.music.ui.settings.SettingsScreen
import com.spotify.music.ui.settings.EqualizerScreen
import com.spotify.music.ui.theme.LibraryBg
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.SamsungMusicTheme
import com.spotify.music.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 把 SAF 目录树 URI 转成绝对文件路径（仅支持主存储 primary，SD 卡不在此路径转换内） */
fun treeUriToPath(uri: Uri): String? {
    if (!DocumentsContract.isTreeUri(uri)) return null
    val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
    val split = docId.split(":")
    val type = split.firstOrNull() ?: return null
    val sub = if (split.size > 1) split[1] else ""
    return when (type) {
        "primary" -> {
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (sub.isEmpty()) base else "$base/$sub"
        }
        else -> null
    }
}

/** 是否需要申请「所有文件管理权限」(Android 11+ 且仅在未授权时) */
fun needsAllFilesPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

class MainActivity : androidx.activity.ComponentActivity() {

    private val client by lazy { PlaybackClient.get(this) }
    private val library by lazy { LibraryRepository.get(this) }
    private val settings by lazy { SettingsRepository.get(this) }
    private val uiState by lazy { PlayerUiState(settings, library) }

    enum class Route { LIBRARY, PLAYER, SETTINGS, SCAN_DIRS, HIDDEN_FOLDERS, EQUALIZER, OUTPUT }

    /**
     * Android 13+ 必须运行时申请 READ_MEDIA_AUDIO 与 POST_NOTIFICATIONS，
     * 只在 manifest 声明是不够的：不申请会导致读不到音频文件、媒体通知不显示。
     */
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果仅作参考：即使被拒，仍可依赖 MANAGE_EXTERNAL_STORAGE 扫描 */ }

    private fun requestRuntimePermissions() {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }
                .onFailure { CrashLogger.log(it, "requestRuntimePermissions") }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            var darkMode by remember { mutableStateOf(settings.darkMode) }
            var route by remember { mutableStateOf(Route.LIBRARY) }
            var browse by remember { mutableStateOf<Pair<String, String>?>(null) }
            // 均衡器页返回时该回哪（播放页 or 设置页）
            var equalizerReturn by remember { mutableStateOf(Route.SETTINGS) }
            var permissionGranted by remember { mutableStateOf(!needsAllFilesPermission()) }
            // 崩溃/终止原因只静默写入文件（应用私有目录），不再弹窗打扰使用
            // 每 5 秒采一次内存水位：崩溃后能看出内存曲线是不是一路飙到顶
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(5_000)
                    CrashLogger.trace("heartbeat ${CrashLogger.mem()}")
                }
            }

            // 统一返回逻辑：子页面回上一级；资料库首页（根页面）退回桌面
            val goBack: () -> Unit = {
                when (route) {
                    Route.LIBRARY -> {
                        // 专辑/歌手详情页状态下，先退出详情回列表，再按一次才退桌面
                        if (browse != null) browse = null else moveTaskToBack(true)
                    }
                    Route.PLAYER -> route = Route.LIBRARY
                    Route.SETTINGS -> {
                        darkMode = settings.darkMode
                        route = Route.LIBRARY
                    }
                    Route.SCAN_DIRS -> route = Route.SETTINGS
                    Route.HIDDEN_FOLDERS -> route = Route.SETTINGS
                    Route.EQUALIZER -> route = equalizerReturn
                    Route.OUTPUT -> route = Route.SETTINGS
                }
            }
            // 系统返回键 / 系统侧滑返回手势
            BackHandler { goBack() }

            // 连接播放服务 + 启动进度轮询 + 首次扫描
            LaunchedEffect(Unit) {
                CrashLogger.trace("connect playback service")
                runCatching {
                    val c = client.connect()
                    uiState.attach(c)
                    launch { uiState.tick() }
                    // 服务刚恢复过队列（进程重启）→ 自动接着上次进度播放
                    client.resumeIfNeeded()
                    CrashLogger.trace("connect playback service OK")
                }.onFailure { CrashLogger.log(it, "connect playback service") }
                maybeFirstScan()
            }

            // 添加自定义扫描目录：SAF 目录选择器
            val treeLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    val path = treeUriToPath(uri)
                    if (path != null) {
                        val cur = settings.scanDirs.toMutableSet()
                        cur.add(path)
                        settings.scanDirs = cur
                        Toast.makeText(this, "已添加扫描目录：$path", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) { library.rescan() }
                    } else {
                        Toast.makeText(this, "暂仅支持主存储目录", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            SamsungMusicTheme(darkModeSetting = darkMode) {
                Box(Modifier.fillMaxSize().background(LibraryBg)) {
                    if (!permissionGranted) {
                        PermissionPrompt(
                            onGrant = { requestAllFilesAccess() },
                            onContinue = { permissionGranted = true },
                        )
                    } else {
                        // 左边缘侧滑返回（所有子页面生效）
                        SwipeBackLayout(enabled = true, onBack = goBack) {
                            when (route) {
                            Route.LIBRARY -> LibraryScreen(
                                library = library,
                                settings = settings,
                                client = client,
                                uiState = uiState,
                                onOpenPlayer = { route = Route.PLAYER },
                                onOpenSettings = { route = Route.SETTINGS },
                                browse = browse,
                                onClearBrowse = { browse = null },
                                onSetBrowse = { browse = it },
                            )
                            Route.PLAYER -> PlayerScreen(
                                uiState = uiState,
                                client = client,
                                settings = settings,
                                library = library,
                                onBack = { route = Route.LIBRARY },
                                onOpenSettings = { route = Route.SETTINGS },
                                onOpenEqualizer = {
                                    equalizerReturn = Route.PLAYER
                                    route = Route.EQUALIZER
                                },
                                onBrowseAlbum = { name -> browse = "album" to name; route = Route.LIBRARY },
                                onBrowseArtist = { name -> browse = "artist" to name; route = Route.LIBRARY },
                            )
                            Route.SETTINGS -> SettingsScreen(
                                settings = settings,
                                client = client,
                                onBack = {
                                    darkMode = settings.darkMode
                                    route = Route.LIBRARY
                                },
                                onOpenScanDirs = { route = Route.SCAN_DIRS },
                                onOpenHiddenFolders = { route = Route.HIDDEN_FOLDERS },
                                onOpenEqualizer = {
                                    equalizerReturn = Route.SETTINGS
                                    route = Route.EQUALIZER
                                },
                                onOpenOutput = { route = Route.OUTPUT },
                            )
                            Route.EQUALIZER -> EqualizerScreen(
                                settings = settings,
                                onBack = { route = Route.SETTINGS },
                            )
                            Route.OUTPUT -> OutputScreen(
                                onBack = { route = Route.SETTINGS },
                            )
                            Route.SCAN_DIRS -> ScanDirsScreen(
                                settings = settings,
                                library = library,
                                onBack = { route = Route.SETTINGS },
                                onPickDirectory = { treeLauncher.launch(null) },
                            )
                            Route.HIDDEN_FOLDERS -> HiddenFoldersScreen(
                                library = library,
                                settings = settings,
                                onBack = { route = Route.SETTINGS },
                            )
                            }
                        }
                    }
                }
            }

            // 从权限设置页返回后重新评估
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(800)
                    if (needsAllFilesPermission() && !Environment.isExternalStorageManager()) {
                        // 仍被拒绝：保持提示，但用户可点「继续」绕过
                    } else if (permissionGranted != !needsAllFilesPermission()) {
                        permissionGranted = !needsAllFilesPermission()
                    }
                }
            }
        }
    }

    /**
     * 申请「所有文件访问权限」。
     *
     * 注意：vivo / OriginOS 等 ROM 上 Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
     * 这个专属设置页可能不存在，直接 startActivity 会抛 ActivityNotFoundException 导致闪退。
     * 因此做多级降级：专属页 → 应用详情页 → 应用管理页 → 系统设置，最后兜底 Toast，绝不让它崩。
     */
    private fun requestAllFilesAccess() {
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (t: Throwable) {
                CrashLogger.log(t, "requestAllFilesAccess failed: $intent")
            }
        }
        Toast.makeText(this, "无法打开权限设置页，请在系统设置中手动授予", Toast.LENGTH_LONG).show()
    }

    private fun maybeFirstScan() {
        if (settings.firstScanDone) return
        if (needsAllFilesPermission() && !Environment.isExternalStorageManager()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { library.rescan() }
            settings.firstScanDone = true
        }
    }
}

/**
 * 首次启动的「所有文件管理权限」引导页。
 * 用户可授予（推荐的自定义扫描目录体验）或点击「继续」在受限权限下使用默认 Music 目录。
 */
@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(LibraryBg)
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = SamsungBlue,
            modifier = Modifier.size(72.dp),
        )
        Text(
            "允许访问音乐文件",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = SamsungBlue,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "为了扫描任意文件夹下的本地音乐与歌词，建议授予「所有文件管理权限」。\n你也可以稍后在设置中更改。",
            fontSize = 14.sp,
            color = Color(0xFF83838C),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
        )
        Button(
            onClick = onGrant,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("授予权限", fontSize = 16.sp)
        }
        TextButton(onClick = onContinue, modifier = Modifier.padding(top = 8.dp)) {
            Text("继续（仅默认目录）", color = SamsungBlue)
        }
    }
}
