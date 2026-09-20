package com.spotify.music.ui.settings

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.audio.EqState
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

/**
 * 输出信息页：显示实时音频输出链路（设备 / 采样率 / 位深 / 声道 / 软件音效链）。
 * 数据来自 AudioProcessor onConfigure 的实际 PCM 参数 + AudioManager 当前输出设备。
 *
 * 输出参数变更后保存到设置；为避免车机原生音频流重建导致进程退出，需完整重启应用后生效。
 */
@Composable
fun OutputScreen(onBack: () -> Unit, onOutputChanged: () -> Unit = {}) {
    val context = LocalContext.current
    fun changed() {
        onOutputChanged()
        Toast.makeText(context, context.getString(R.string.output_saved), Toast.LENGTH_SHORT).show()
    }
    var tick by remember { mutableIntStateOf(0) }
    var showDepthDialog by remember { mutableStateOf(false) }
    val settings = remember {
        com.spotify.music.data.SettingsRepository.get(context)
    }
    var bitDepth by remember { mutableStateOf(settings.audioBitDepth) }
    var targetSampleRate by remember { mutableIntStateOf(settings.audioSampleRate) }
    var showRateDialog by remember { mutableStateOf(false) }
    var outputMode by remember { mutableStateOf(settings.audioOutputMode) }
    var usbExclusive by remember { mutableStateOf(settings.usbExclusive) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var outputDeviceId by remember { mutableIntStateOf(settings.audioOutputDeviceId) }
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    val devices = remember(showDeviceDialog) {
        audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .filter {
                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
    }
    // 1 秒刷新一次（读系统设备列表 + 处理器实际参数）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val activeDevice = remember(tick, outputMode, devices) {
        if (outputMode != "system") {
            val activeId = EqState.outDeviceId
            if (activeId > 0) devices.firstOrNull { it.id == activeId } else null
        } else null
    }
    val deviceName = if (outputMode == "system") {
        stringResource(R.string.mode_system_route)
    } else {
        val activeId = EqState.outDeviceId
        if (activeId > 0) {
            activeDevice?.let { localizedDeviceLabel(it) }
                ?: stringResource(R.string.device) + " ID $activeId"
        } else stringResource(R.string.mode_aaudio_auto)
    }
    val sampleRate = EqState.outSampleRate
    val channelCount = EqState.outChannelCount
    val encoding = EqState.encodingLabel()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "‹",
                fontSize = 26.sp,
                color = SamsungBlue,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp),
            )
            Text(stringResource(R.string.output_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }

        SectionCard {
            InfoLine(stringResource(R.string.output_device), deviceName)
            Divider()
            InfoLine(stringResource(R.string.actual_sample_rate), if (sampleRate > 0) "$sampleRate Hz" else stringResource(R.string.not_playing))
            Divider()
            InfoLine(stringResource(R.string.output_bit_depth), localizedEncoding(encoding))
            Divider()
            InfoLine(stringResource(R.string.channels), if (channelCount > 0) "$channelCount" else stringResource(R.string.not_playing))
        }

        SectionLabel(stringResource(R.string.output_settings))
        SectionCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showModeDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.output_mode), fontSize = 15.sp, color = TextPrimary)
                    Text(
                        stringResource(R.string.output_mode_desc),
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    when (outputMode) { "aaudio" -> stringResource(R.string.mode_aaudio); "opensles" -> stringResource(R.string.mode_opensles); else -> stringResource(R.string.mode_system) },
                    fontSize = 15.sp,
                    color = SamsungBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(stringResource(R.string.usb_exclusive), fontSize = 15.sp, color = TextPrimary)
                    Text(stringResource(R.string.usb_exclusive_desc), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(
                    checked = usbExclusive,
                    onCheckedChange = {
                        usbExclusive = it
                        settings.usbExclusive = it
                        changed()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF8A90DE)),
                )
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showDeviceDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.output_channel), fontSize = 15.sp, color = TextPrimary)
                    Text(
                        stringResource(R.string.output_channel_desc),
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    if (outputDeviceId <= 0) stringResource(R.string.auto) else
                        devices.firstOrNull { it.id == outputDeviceId }?.let { localizedDeviceLabel(it) } ?: stringResource(R.string.device_specified),
                    fontSize = 15.sp,
                    color = SamsungBlue,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showRateDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.output_sample_rate), fontSize = 15.sp, color = TextPrimary)
                    Text(stringResource(R.string.output_sample_rate_desc), fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 3.dp))
                }
                Text(if (targetSampleRate > 0) "${targetSampleRate / 1000f} kHz" else stringResource(R.string.auto), fontSize = 15.sp, color = SamsungBlue, fontWeight = FontWeight.SemiBold)
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showDepthDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.output_bit_depth_title), fontSize = 15.sp, color = TextPrimary)
                    Text(
                        stringResource(R.string.output_bit_depth_desc),
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    when (bitDepth) { "auto" -> stringResource(R.string.auto); "24" -> "24bit"; "32" -> "32bit"; "float" -> "32bit float"; else -> "16bit" },
                    fontSize = 15.sp,
                    color = SamsungBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        SectionLabel(stringResource(R.string.effect_chain))
        SectionCard {
            InfoLine(stringResource(R.string.equalizer), if (EqState.enabled) stringResource(R.string.on_state) else stringResource(R.string.off))
            Divider()
            InfoLine(stringResource(R.string.bass_boost), if (EqState.bassBoostDb != 0f) "%+.0f dB".format(EqState.bassBoostDb) else stringResource(R.string.off))
            Divider()
            InfoLine(stringResource(R.string.treble_boost), if (EqState.trebleDb != 0f) "%+.0f dB".format(EqState.trebleDb) else stringResource(R.string.off))
            Divider()
            InfoLine(stringResource(R.string.surround), if (EqState.width > 0f) "%.0f%%".format(EqState.width * 100) else stringResource(R.string.off))
            Divider()
            InfoLine(stringResource(R.string.total_gain), "%+.1f dB".format(EqState.preampDb))
        }

        SectionLabel(stringResource(R.string.about))
        SectionCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.about_output),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 19.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showRateDialog) {
        AlertDialog(
            onDismissRequest = { showRateDialog = false },
            title = { Text(stringResource(R.string.output_sample_rate)) },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    val rates = listOf(0, 44100, 48000, 88200, 96000, 176400, 192000)
                    rates.forEach { rate ->
                        val label = if (rate == 0) stringResource(R.string.auto_follow_source) else "${rate / 1000f} kHz"
                        DeviceRow(label, targetSampleRate == rate) {
                            targetSampleRate = rate
                            settings.audioSampleRate = rate
                            showRateDialog = false
                            changed()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRateDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showDepthDialog) {
        AlertDialog(
            onDismissRequest = { showDepthDialog = false },
            title = { Text(stringResource(R.string.output_bit_depth_title)) },
            text = {
                Column {
                    listOf(stringResource(R.string.auto_player_negotiated) to "auto", "16bit" to "16", "24bit" to "24", "32bit" to "32", "32bit float" to "float").forEach { (label, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    bitDepth = v
                                    settings.audioBitDepth = v
                                    showDepthDialog = false
                                    changed()
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            if (v == bitDepth) Text("✓", color = SamsungBlue, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDepthDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text(stringResource(R.string.output_channel)) },
            text = {
                Column(
                    Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DeviceRow(stringResource(R.string.auto_follow_system), outputDeviceId <= 0) {
                        outputDeviceId = -1
                        settings.audioOutputDeviceId = -1
                        showDeviceDialog = false
                        changed()
                    }
                    devices.forEach { d ->
                        DeviceRow(localizedDeviceLabel(d), outputDeviceId == d.id) {
                            outputDeviceId = d.id
                            settings.audioOutputDeviceId = d.id
                            showDeviceDialog = false
                            changed()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(stringResource(R.string.output_mode)) },
            text = {
                Column {
                    listOf(
                        stringResource(R.string.mode_system_alt) to "system",
                        stringResource(R.string.mode_aaudio) to "aaudio",
                        stringResource(R.string.mode_opensles_alt) to "opensles",
                    ).forEach { (label, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    outputMode = v
                                    settings.audioOutputMode = v
                                    showModeDialog = false
                                    changed()
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            if (v == outputMode) Text("✓", color = SamsungBlue, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = SamsungBlue, fontWeight = FontWeight.SemiBold)
    }
}

/** 当前音频实际走哪个输出设备（取第一个激活的 sink，优先非内置设备） */
@Composable
private fun localizedDeviceLabel(d: android.media.AudioDeviceInfo): String {
    val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
    val type = when (d.type) {
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> stringResource(R.string.usb_dac)
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> stringResource(R.string.wired_headphones)
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> stringResource(R.string.wired_headset)
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> stringResource(R.string.bluetooth_audio)
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> stringResource(R.string.speaker)
        else -> stringResource(R.string.device)
    }
    return if (name.isNotBlank()) "$type · $name" else type
}

@Composable
private fun localizedEncoding(encoding: String): String = when (encoding) {
    "未初始化" -> stringResource(R.string.not_initialized)
    "其他" -> stringResource(R.string.other)
    else -> encoding
}

@Composable
private fun DeviceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (checked) Text("✓", color = SamsungBlue, fontSize = 15.sp)
    }
}
