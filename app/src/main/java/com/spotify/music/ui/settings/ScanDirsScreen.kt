package com.spotify.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.ui.theme.CardWhite
import com.spotify.music.ui.theme.LibraryBg
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

@Composable
fun ScanDirsScreen(
    settings: SettingsRepository,
    library: LibraryRepository,
    scanDirsVersion: Int = 0,
    onBack: () -> Unit,
    onPickDirectory: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dirs by remember(scanDirsVersion) { mutableStateOf(settings.scanDirs.toList()) }

    fun rescan() {
        scope.launch {
            runCatching { library.rescan() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LibraryBg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = TextPrimary)
            }
            Text(
                stringResource(R.string.scan_dirs),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = SamsungBlue,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                onPickDirectory()
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_dir), tint = SamsungBlue)
            }
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(dirs) { dir ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardWhite)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(SamsungBlue)
                    )
                    Text(
                        dir,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                    IconButton(
                        onClick = {
                            if (dirs.size > 1) {
                                val cur = dirs.toMutableList().also { it.remove(dir) }
                                dirs = cur
                                settings.scanDirs = cur.toSet()
                                rescan()
                            }
                        },
                        enabled = dirs.size > 1,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.remove),
                            tint = if (dirs.size > 1) Color(0xFFE0533B) else Color(0xFFCCCCCC),
                        )
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.scan_dirs_hint),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }
    }
}
