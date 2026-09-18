package com.spotify.music.ui.settings

import android.media.AudioDeviceInfo
import android.media.AudioManager
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

/**
 * 输出信息页：显示实时音频输出链路（设备 / 采样率 / 位深 / 声道 / 软件音效链）。
 * 数据来自 AudioProcessor onConfigure 的实际 PCM 参数 + AudioManager 当前输出设备。
 *
 * 输出参数变更后会保留队列并重建播放内核；两种模式都使用 Media3 DSP/输出格式处理链。
 */
@Composable
fun OutputScreen(onBack: () -> Unit, onOutputChanged: () -> Unit = {}) {
    val context = LocalContext.current
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

    val deviceName = remember(tick, outputMode, devices) {
        if (outputMode != "system") {
            val activeId = EqState.outDeviceId
            if (activeId > 0) devices.firstOrNull { it.id == activeId }?.let { deviceLabel(it) } ?: "Oboe 设备 ID $activeId"
            else "Oboe 自动路由"
        } else "AudioTrack · 系统路由"
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
            Text("输出", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }

        SectionCard {
            InfoLine("输出设备", deviceName)
            Divider()
            InfoLine("实际输出采样率", if (sampleRate > 0) "$sampleRate Hz" else "未在播放")
            Divider()
            InfoLine("输出位深 (PCM)", encoding)
            Divider()
            InfoLine("声道数", if (channelCount > 0) "$channelCount" else "未在播放")
        }

        SectionLabel("输出设置")
        SectionCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showModeDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("输出模式", fontSize = 15.sp, color = TextPrimary)
                    Text(
                        "Halcyon Oboe 后端：可选 AAudio / OpenSL ES；USB DAC 独占需单独开启。",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    when (outputMode) { "aaudio" -> "AAudio（Oboe）"; "opensles" -> "OpenSL ES（Oboe）"; else -> "AudioTrack（系统）" },
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
                    Text("USB DAC 独占模式", fontSize = 15.sp, color = TextPrimary)
                    Text("仅 AAudio 后端有效；不支持时 Oboe 自动使用共享模式", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 3.dp))
                }
                Switch(
                    checked = usbExclusive,
                    onCheckedChange = {
                        usbExclusive = it
                        settings.usbExclusive = it
                        onOutputChanged()
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
                    Text("输出通道", fontSize = 15.sp, color = TextPrimary)
                    Text(
                        "指定输出到扬声器/耳机/USB DAC/蓝牙（AAudio 原生模式生效）",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    if (outputDeviceId <= 0) "自动" else
                        devices.firstOrNull { it.id == outputDeviceId }?.let { deviceLabel(it) } ?: "已指定设备",
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
                    Text("输出采样率", fontSize = 15.sp, color = TextPrimary)
                    Text("自动跟随音源，或指定目标采样率（两种输出模式均生效）", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 3.dp))
                }
                Text(if (targetSampleRate > 0) "${targetSampleRate / 1000f} kHz" else "自动", fontSize = 15.sp, color = SamsungBlue, fontWeight = FontWeight.SemiBold)
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showDepthDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("输出位深", fontSize = 15.sp, color = TextPrimary)
                    Text(
                        "位深设置作用于系统 AudioTrack；AAudio 原生端固定使用 Float32",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    when (bitDepth) { "auto" -> "自动"; "24" -> "24bit"; "32" -> "32bit"; "float" -> "32bit float"; else -> "16bit" },
                    fontSize = 15.sp,
                    color = SamsungBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        SectionLabel("软件音效链")
        SectionCard {
            InfoLine("均衡器", if (EqState.enabled) "开启" else "关闭")
            Divider()
            InfoLine("低音增强", if (EqState.bassBoostDb > 0f) "+%.0f dB".format(EqState.bassBoostDb) else "关")
            Divider()
            InfoLine("高音增强", if (EqState.trebleDb > 0f) "+%.0f dB".format(EqState.trebleDb) else "关")
            Divider()
            InfoLine("环绕增强", if (EqState.width > 0f) "%.0f%%".format(EqState.width * 100) else "关")
            Divider()
            InfoLine("总增益", "%+.1f dB".format(EqState.preampDb))
        }

        SectionLabel("关于")
        SectionCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "以上为解码后经 DSP/格式处理后的 PCM 参数。\n\n" +
                        "「AAudio / OpenSL ES（Oboe）」模式：由 Oboe 打开原生输出流，Media3 DSP/格式处理链在写入前运行；" +
                        "独占模式只对兼容的 AAudio USB DAC 请求，失败由 Oboe 回退共享。原生模式端点按 Oboe 协商 PCM 格式。\n" +
                        "「系统」模式：走 Android AudioTrack 系统混音，兼容性更好，支持播放速度和跳过静音。",
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
            title = { Text("输出采样率") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    val rates = listOf(0, 44100, 48000, 88200, 96000, 176400, 192000)
                    rates.forEach { rate ->
                        val label = if (rate == 0) "自动（跟随音源）" else "${rate / 1000f} kHz"
                        DeviceRow(label, targetSampleRate == rate) {
                            targetSampleRate = rate
                            settings.audioSampleRate = rate
                            showRateDialog = false
                            onOutputChanged()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRateDialog = false }) { Text("取消") } },
        )
    }
    if (showDepthDialog) {
        AlertDialog(
            onDismissRequest = { showDepthDialog = false },
            title = { Text("输出位深") },
            text = {
                Column {
                    listOf("自动（播放器协商）" to "auto", "16bit" to "16", "24bit" to "24", "32bit" to "32", "32bit float" to "float").forEach { (label, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    bitDepth = v
                                    settings.audioBitDepth = v
                                    showDepthDialog = false
                                    onOutputChanged()
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
                TextButton(onClick = { showDepthDialog = false }) { Text("取消") }
            },
        )
    }
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("输出通道") },
            text = {
                Column(
                    Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DeviceRow("自动（跟随系统）", outputDeviceId <= 0) {
                        outputDeviceId = -1
                        settings.audioOutputDeviceId = -1
                        showDeviceDialog = false
                        onOutputChanged()
                    }
                    devices.forEach { d ->
                        DeviceRow(deviceLabel(d), outputDeviceId == d.id) {
                            outputDeviceId = d.id
                            settings.audioOutputDeviceId = d.id
                            showDeviceDialog = false
                            onOutputChanged()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) { Text("取消") }
            },
        )
    }
    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("输出模式") },
            text = {
                Column {
                    listOf(
                        "系统（AudioTrack，兼容优先）" to "system",
                        "AAudio（Oboe）" to "aaudio",
                        "OpenSL ES（Oboe 兼容后端）" to "opensles",
                    ).forEach { (label, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    outputMode = v
                                    settings.audioOutputMode = v
                                    showModeDialog = false
                                    onOutputChanged()
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
                TextButton(onClick = { showModeDialog = false }) { Text("取消") }
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
private fun deviceLabel(d: android.media.AudioDeviceInfo): String {
    val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
    val type = when (d.type) {
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        else -> "设备"
    }
    return if (name.isNotBlank()) "$type · $name" else type
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

private fun currentOutputDevice(am: AudioManager): String {
    return try {
        val sinks = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        val preferred = sinks.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        } ?: sinks.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } ?: sinks.firstOrNull()
        when (preferred?.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频设备（DAC）"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_TELEPHONY -> "听筒"
            null -> "未知"
            else -> "其他设备 (type=${preferred.type})"
        }
    } catch (t: Throwable) {
        "未知"
    }
}
