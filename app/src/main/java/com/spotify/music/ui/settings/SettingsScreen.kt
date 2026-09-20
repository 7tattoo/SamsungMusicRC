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
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

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
            Text(stringResource(R.string.settings_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }

        // ── 播放 ──
        SectionLabel(stringResource(R.string.section_playback))
        SectionCard {
            RowItem(stringResource(R.string.sleep_timer), if (sleepMinutes > 0) stringResource(R.string.sleep_in_minutes, sleepMinutes) else stringResource(R.string.off), accent = true) {
                showSleepDialog = true
            }
            Divider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.playback_speed), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(
                    stringResource(R.string.speed_x, speed),
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
                Text(stringResource(R.string.crossfade), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(
                    if (crossfade <= 0) stringResource(R.string.off) else stringResource(R.string.seconds, crossfade),
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
            SwitchItem(stringResource(R.string.skip_silence), skipSilence) {
                skipSilence = it
                settings.skipSilence = it
                client.setSkipSilence(it)
            }
            Divider()
            SwitchItem(stringResource(R.string.lockscreen_control), lockscreen) {
                lockscreen = it
                settings.lockscreenControl = it
            }
            Divider()
            SwitchItem(
                stringResource(R.string.ambient_playback),
                stringResource(R.string.ambient_desc),
                ambient,
            ) {
                ambient = it
                settings.ambientPlayback = it
            }
        }

        // 音效与输出分别作为一级大类，和 Halcyon 的设置结构保持一致。
        SectionLabel(stringResource(R.string.section_effects))
        SectionCard {
            RowItem(stringResource(R.string.equalizer), stringResource(R.string.eq_row_desc), accent = true) {
                onOpenEqualizer()
            }
        }

        SectionLabel(stringResource(R.string.section_output))
        SectionCard {
            RowItem(stringResource(R.string.output_channel), stringResource(R.string.output_row_desc), accent = true) {
                onOpenOutput()
            }
        }

        // ── 播放列表 ──
        SectionLabel(stringResource(R.string.section_playlists))
        SectionCard {
            RowItem(stringResource(R.string.queue_settings), stringResource(R.string.play_all_songs), accent = true) { showQueueDialog = true }
            Divider()
            SwitchItem(stringResource(R.string.no_duplicates), stringResource(R.string.no_duplicates_desc), noDup) {
                noDup = it
                settings.queueNoDuplicates = it
            }
            Divider()
            RowItem(stringResource(R.string.manage_playlists), showDot = manageDot) {
                manageDot = false
            }
        }

        // ── 一般 ──
        SectionLabel(stringResource(R.string.section_general))
        SectionCard {
            SwitchItem(
                stringResource(R.string.car_lyrics),
                stringResource(R.string.car_lyrics_desc),
                carLyrics,
            ) {
                carLyrics = it
                settings.carLyricsEnabled = it
                com.spotify.music.playback.CarLyricsBridge.enabled = it
                client.applySettings()
            }
            Divider()
            SwitchItem(
                stringResource(R.string.concurrent_playback),
                stringResource(R.string.concurrent_desc),
                concurrent,
            ) {
                concurrent = it
                settings.concurrentPlayback = it
                client.applySettings()
            }
            Divider()
            RowItem(stringResource(R.string.manage_tabs), stringResource(R.string.manage_tabs_desc), accent = true) {}
            Divider()
            RowItem(stringResource(R.string.dark_mode), when (darkMode) { "dark" -> stringResource(R.string.dark); "light" -> stringResource(R.string.light); else -> stringResource(R.string.match_system) }, accent = true) {
                showDarkDialog = true
            }
            Divider()
            SwitchItem(stringResource(R.string.allow_external_start), externalStart) {
                externalStart = it
                settings.allowExternalStart = it
            }
        }

        // ── 隐私 ──
        SectionLabel(stringResource(R.string.section_privacy))
        SectionCard {
            RowItem(stringResource(R.string.permissions)) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                } catch (t: Throwable) {
                }
            }
            Divider()
            RowItem(stringResource(R.string.scan_dirs), stringResource(R.string.scan_dirs_desc), accent = true) {
                onOpenScanDirs()
            }
            Divider()
            RowItem(stringResource(R.string.hidden_folders), stringResource(R.string.hidden_folders_desc), accent = true) {
                onOpenHiddenFolders()
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showSleepDialog) {
        ChoiceDialog(
            title = stringResource(R.string.sleep_timer),
            options = listOf(stringResource(R.string.off) to 0, stringResource(R.string.minutes, 5) to 5, stringResource(R.string.minutes, 10) to 10, stringResource(R.string.minutes, 15) to 15, stringResource(R.string.minutes, 30) to 30, stringResource(R.string.minutes, 60) to 60),
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
            title = stringResource(R.string.dark_mode),
            options = listOf(stringResource(R.string.match_system) to "system", stringResource(R.string.light) to "light", stringResource(R.string.dark) to "dark"),
            current = darkMode,
            onSelect = { darkMode = it; settings.darkMode = it },
            onDismiss = { showDarkDialog = false },
        )
    }
    if (showQueueDialog) {
        ChoiceDialog(
            title = stringResource(R.string.queue_settings),
            options = listOf(stringResource(R.string.play_all_songs) to 0, stringResource(R.string.only_current_list) to 1),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
