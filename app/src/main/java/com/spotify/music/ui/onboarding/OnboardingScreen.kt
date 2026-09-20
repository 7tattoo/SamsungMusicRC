package com.spotify.music.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.data.SettingsRepository
import com.spotify.music.needsAllFilesPermission
import com.spotify.music.ui.theme.CardWhite
import com.spotify.music.ui.theme.LibraryBg
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

/**
 * 首次启动引导页（参考 Samsung Music 权限页样式）：
 *  - 需要的权限：音乐与音频；可选权限：通知
 *  - 扫描目录选择（可添加 / 移除）
 *  - 底部「继续」按钮：进入应用并触发运行时权限申请与首次扫描
 */
@Composable
fun OnboardingScreen(
    settings: SettingsRepository,
    scanDirsVersion: Int = 0,
    onPickDirectory: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onContinue: () -> Unit,
) {
    var dirs by remember(scanDirsVersion) { mutableStateOf(settings.scanDirs.toList()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(LibraryBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SamsungBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                stringResource(R.string.onboarding_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                stringResource(R.string.onboarding_subtitle),
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
            )

            PermissionCard(
                icon = {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = SamsungBlue,
                        modifier = Modifier.size(22.dp),
                    )
                },
                tag = stringResource(R.string.required_permission),
                title = stringResource(R.string.music_audio),
                desc = stringResource(R.string.music_audio_desc),
            )
            PermissionCard(
                icon = {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = SamsungBlue,
                        modifier = Modifier.size(22.dp),
                    )
                },
                tag = stringResource(R.string.optional_permission),
                title = stringResource(R.string.notifications),
                desc = stringResource(R.string.notifications_desc),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.scan_dirs),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickDirectory) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = SamsungBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.add), color = SamsungBlue)
                }
            }
            dirs.forEach { dir ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardWhite)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dir,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (dirs.size > 1) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.remove),
                            tint = Color(0xFFE0533B),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    dirs = dirs - dir
                                    settings.scanDirs = dirs.toSet()
                                },
                        )
                    }
                }
            }
            if (needsAllFilesPermission()) {
                Text(
                    stringResource(R.string.all_files_hint),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                TextButton(onClick = onGrantAllFiles) {
                    Text(stringResource(R.string.grant_all_files), color = SamsungBlue, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = onContinue,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.continue_label), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: @Composable () -> Unit,
    tag: String,
    title: String,
    desc: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .padding(14.dp),
    ) {
        Text(tag, fontSize = 11.sp, color = SamsungBlue, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFECECF2)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(desc, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
            }
        }
    }
}
