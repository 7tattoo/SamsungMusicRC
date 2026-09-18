package com.spotify.music.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.data.SettingsRepository
import com.spotify.music.playback.PlaybackClient
import com.spotify.music.ui.theme.OrangeDot
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary

/**
 * 三星音乐设置页（按 UI 截图 1-4 复刻）。
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    client: PlaybackClient,
    onBack: () -> Unit,
    onOpenScanDirs: () -> Unit,
    onOpenHiddenFolders: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenOutput: () -> Unit = {},
) {
    val context = LocalContext.current
    var sleepMinutes by remember { mutableIntStateOf(settings.sleepTimerMinutes) }
    var speed by remember { mutableFloatStateOf(settings.playbackSpeed) }
    var crossfade by remember { mutableIntStateOf(settings.crossfadeSeconds) }
    var skipSilence by remember { mutableStateOf(settings.skipSilence) }
    var lockscreen by remember { mutableStateOf(settings.lockscreenControl) }
    var ambient by remember { mutableStateOf(settings.ambientPlayback) }
    var noDup by remember { mutableStateOf(settings.queueNoDuplicates) }
    var carLyrics by remember { mutableStateOf(settings.carLyricsEnabled) }
    var concurrent by remember { mutableStateOf(settings.concurrentPlayback) }
    var darkMode by remember { mutableStateOf(settings.darkMode) }
    var externalStart by remember { mutableStateOf(settings.allowExternalStart) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showDarkDialog by remember { mutableStateOf(false) }
    var showQueueDialog by remember { mutableStateOf(false) }
    var manageDot by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // 标题
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                fontSize = 26.sp,
                color = SamsungBlue,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            Text("三星音乐设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }

        // ── 播放 ──
        SectionLabel("播放")
        SectionCard {
            RowItem("睡眠定时器", if (sleepMinutes > 0) "${sleepMinutes}分钟后" else "关", accent = true) {
                showSleepDialog = true
            }
            Divider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("播放速度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(
                    "%.2f 倍".format(speed),
                    fontSize = 15.sp,
                    color = SamsungBlue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        settings.playbackSpeed = it
                        client.setSpeed(it)
                    },
                    valueRange = 0.5f..2.0f,
                    colors = sliderColors(),
                )
            }
            Divider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("歌曲之间的淡入淡出", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(
                    if (crossfade <= 0) "关" else "$crossfade 秒",
                    fontSize = 15.sp,
                    color = SamsungBlue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Slider(
                    value = crossfade.toFloat(),
                    onValueChange = {
                        crossfade = it.toInt()
                        settings.crossfadeSeconds = it.toInt()
                    },
                    valueRange = 0f..12f,
                    colors = sliderColors(),
                )
            }
            Divider()
            SwitchItem("跳过歌曲之间的无声时间", skipSilence) {
                skipSilence = it
                settings.skipSilence = it
                client.setSkipSilence(it)
            }
            Divider()
            SwitchItem("通过锁定屏幕控制音乐", lockscreen) {
                lockscreen = it
                settings.lockscreenControl = it
            }
            Divider()
            SwitchItem(
                "息屏播放音乐",
                "已连接耳机且屏幕关闭时，在锁定屏幕上播放音乐。",
                ambient,
            ) {
                ambient = it
                settings.ambientPlayback = it
            }
        }

        // ── 音效 ──
        SectionLabel("音效")
        SectionCard {
            RowItem("均衡器", "10 段软件均衡器 · 低音增强 · 总增益", accent = true) {
                onOpenEqualizer()
            }
            Divider()
            RowItem("输出", "输出设备与实时音频参数", accent = true) {
                onOpenOutput()
            }
        }

        // ── 播放列表 ──
        SectionLabel("播放列表")
        SectionCard {
            RowItem("队列设置", "播放所有歌曲", accent = true) { showQueueDialog = true }
            Divider()
            SwitchItem("不允许重复歌曲", "不允许队列中有重复歌曲", noDup) {
                noDup = it
                settings.queueNoDuplicates = it
            }
            Divider()
            RowItem("管理播放列表", showDot = manageDot) {
                manageDot = false
            }
        }

        // ── 一般 ──
        SectionLabel("一般")
        SectionCard {
            SwitchItem(
                "车载投屏歌词",
                "开启后将当前歌曲歌词投送到vivo智能车载",
                carLyrics,
            ) {
                carLyrics = it
                settings.carLyricsEnabled = it
                com.spotify.music.playback.CarLyricsBridge.enabled = it
                client.applySettings()
            }
            Divider()
            SwitchItem(
                "与其他应用同时播放",
                "开启后导航播报、其他应用播放声音时本应用不暂停(可能被压低音量)",
                concurrent,
            ) {
                concurrent = it
                settings.concurrentPlayback = it
                client.applySettings()
            }
            Divider()
            RowItem("管理标签", "收藏，播放列表，歌曲，专辑，歌手，文件夹", accent = true) {}
            Divider()
            RowItem("黑暗模式", when (darkMode) { "dark" -> "深色"; "light" -> "浅色"; else -> "匹配手机设置" }, accent = true) {
                showDarkDialog = true
            }
            Divider()
            SwitchItem("允许外部设备开始播放", externalStart) {
                externalStart = it
                settings.allowExternalStart = it
            }
        }

        // ── 隐私 ──
        SectionLabel("隐私")
        SectionCard {
            RowItem("权限") {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                } catch (t: Throwable) {
                }
            }
            Divider()
            RowItem("扫描目录", "自定义扫描文件夹（含子文件夹）", accent = true) {
                onOpenScanDirs()
            }
            Divider()
            RowItem("隐藏文件夹", "选择扫描结果中需要隐藏的文件夹", accent = true) {
                onOpenHiddenFolders()
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showSleepDialog) {
        ChoiceDialog(
            title = "睡眠定时器",
            options = listOf("关" to 0, "5 分钟" to 5, "10 分钟" to 10, "15 分钟" to 15, "30 分钟" to 30, "60 分钟" to 60),
            current = sleepMinutes,
            onSelect = {
                sleepMinutes = it
                client.setSleepTimer(it)
            },
            onDismiss = { showSleepDialog = false },
        )
    }
    if (showDarkDialog) {
        ChoiceDialog(
            title = "黑暗模式",
            options = listOf("匹配手机设置" to "system", "浅色" to "light", "深色" to "dark"),
            current = darkMode,
            onSelect = { darkMode = it; settings.darkMode = it },
            onDismiss = { showDarkDialog = false },
        )
    }
    if (showQueueDialog) {
        ChoiceDialog(
            title = "队列设置",
            options = listOf("播放所有歌曲" to 0, "仅播放当前列表" to 1),
            current = 0,
            onSelect = {},
            onDismiss = { showQueueDialog = false },
        )
    }
}

// ─────────────────────────── 通用部件 ───────────────────────────

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = TextSecondary,
        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White),
    ) { content() }
}

@Composable
internal fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(Color(0xFFF0F0F3)),
    )
}

@Composable
private fun RowItem(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    accent: Boolean = false,
    showDot: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                if (showDot) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(OrangeDot),
                    )
                }
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = if (accent) SamsungBlue else TextSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (value != null) {
            Text(value, fontSize = 14.sp, color = if (accent) SamsungBlue else TextPrimary)
        }
    }
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchItem(title, null, checked, onChange)
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF8A90DE),
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
internal fun sliderColors() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = Color(0xFF8A90DE),
    activeTrackColor = Color(0xFF8A90DE),
    inactiveTrackColor = Color(0xFFE4E4EA),
)

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    current: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, v) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(v)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, fontSize = 15.sp, color = TextPrimary)
                        if (v == current) {
                            Text("✓", color = SamsungBlue, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
