package com.spotify.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.audio.EqState
import com.spotify.music.data.SettingsRepository
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

/**
 * 10 段软件均衡器（版式对齐 Halcyon）：
 * 前置增益 / 10 段竖向滑杆 / 低音 / 高音 / 环绕增强 / 总增益 / 重置。
 * 系统 AudioTrack 模式由 EqualizerProcessor 处理；AAudio 原生模式由原生 DSP 处理，
 * 参数都从 EqState 读取，即调即听。
 */
@Composable
fun EqualizerScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    var enabled by remember { mutableStateOf(settings.eqEnabled) }
    var preamp by remember { mutableFloatStateOf(settings.eqPreampDb) }
    var bass by remember { mutableFloatStateOf(settings.eqBassBoostDb) }
    var treble by remember { mutableFloatStateOf(settings.eqTrebleDb) }
    var width by remember { mutableFloatStateOf(settings.eqWidth) }
    var bands by remember { mutableStateOf(settings.getEqBands()) }
    // bands 是 FloatArray，元素写入不会触发 Compose 重组；用版本号驱动 UI 刷新
    var tick by remember { mutableIntStateOf(0) }

    fun apply() {
        EqState.setAll(enabled, preamp, bass, treble, width, bands)
        settings.eqEnabled = enabled
        settings.eqPreampDb = preamp
        settings.eqBassBoostDb = bass
        settings.eqTrebleDb = treble
        settings.eqWidth = width
        settings.setEqBands(bands)
        tick++
    }

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
            Text(stringResource(R.string.equalizer), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }

        // ── 主卡片 ──
        SectionCard {
            // 启用开关
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.eq_enabled), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(stringResource(R.string.eq_subtitle), fontSize = 12.sp, color = TextSecondary)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; apply() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF8A90DE),
                        checkedThumbColor = Color.White,
                    ),
                )
            }
            Divider()

            // 前置增益
            LabeledSlider(stringResource(R.string.preamp), preamp, -6f, 6f, "%+.0f dB", "%+.1f dB") {
                preamp = (it * 2).toInt() / 2f; apply()
            }
            Divider()

            // 10 段竖向滑杆（tick 参与读取以驱动重组）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val t = tick
                for (b in 0 until EqState.BAND_COUNT) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            bandDbLabel(bands[b]),
                            fontSize = 10.sp,
                            color = SamsungBlue,
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            Modifier
                                .width(31.dp)
                                .height(170.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Slider(
                                value = bands[b],
                                onValueChange = {
                                    bands[b] = ((it * 2).roundToInt()) / 2f
                                    apply()
                                },
                                valueRange = -12f..12f,
                                colors = sliderColors(),
                                modifier = Modifier
                                    .graphicsLayer { rotationZ = -90f }
                                    .requiredSize(170.dp, 40.dp),
                            )
                        }
                        Text(
                            bandLabel(EqState.BAND_FREQS[b]),
                            fontSize = 10.sp,
                            color = TextSecondary,
                        )
                    }
                }
            }
            Divider()

            // 低音 / 高音 / 环绕 / 总增益
            LabeledSlider(stringResource(R.string.bass), bass, -12f, 12f, "%+.0f dB @100Hz", "%+.1f dB @100Hz") {
                bass = (it * 2).roundToInt() / 2f; apply()
            }
            Divider()
            LabeledSlider(stringResource(R.string.treble), treble, -12f, 12f, "%+.0f dB @6kHz", "%+.1f dB @6kHz") {
                treble = (it * 2).roundToInt() / 2f; apply()
            }
            Divider()
            LabeledSlider(stringResource(R.string.surround), width * 100f, 0f, 100f, "%.0f%%", "%.0f%%") {
                width = it.roundToInt() / 100f; apply()
            }

            Divider()
            // 重置
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        bands = FloatArray(EqState.BAND_COUNT)
                        bass = 0f
                        treble = 0f
                        apply()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.reset), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(stringResource(R.string.reset_all), fontSize = 12.sp, color = TextSecondary)
                }
                Text("›", fontSize = 20.sp, color = TextSecondary)
            }
        }

        // ── 预设 ──
        SectionCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EqState.PRESETS.forEach { (name, values) ->
                    val active = bands.contentEquals(values)
                    Text(
                        presetLabel(name),
                        fontSize = 14.sp,
                        color = if (active) Color.White else TextPrimary,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) SamsungBlue else Color(0xFFEBEBF0))
                            .clickable {
                                bands = values.copyOf()
                                apply()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Text(
            stringResource(R.string.eq_note),
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** 横向滑杆行（标题 + 当前值 + Slider） */
@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    valueFormat: String,
    valueFormatOneDecimal: String,
    label: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            label ?: title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Text(
            if ((value * 2).roundToInt() % 2 == 0) String.format(valueFormat, value)
            else String.format(valueFormatOneDecimal, value),
            fontSize = 15.sp,
            color = SamsungBlue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            textAlign = TextAlign.Center,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            colors = sliderColors(),
        )
    }
}

private fun bandLabel(f: Float): String =
    if (f >= 1000f) "%.0fk".format(f / 1000f) else "%.0f".format(f)

private fun bandDbLabel(v: Float): String {
    val r = (v * 2).roundToInt() / 2f
    return if (r == 0f) "0" else "%+.0f".format(r)
}

@Composable
private fun presetLabel(name: String): String = when (name) {
    "平直" -> stringResource(R.string.eq_flat)
    "流行" -> stringResource(R.string.eq_pop)
    "摇滚" -> stringResource(R.string.eq_rock)
    "爵士" -> stringResource(R.string.eq_jazz)
    "古典" -> stringResource(R.string.eq_classical)
    "电子" -> stringResource(R.string.eq_electronic)
    "人声" -> stringResource(R.string.eq_vocal)
    else -> name
}
