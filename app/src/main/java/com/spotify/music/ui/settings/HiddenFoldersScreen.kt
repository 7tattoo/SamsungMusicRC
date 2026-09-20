package com.spotify.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.data.FolderGroup
import com.spotify.music.data.LibraryRepository
import com.spotify.music.data.SettingsRepository
import com.spotify.music.ui.theme.AccentToggle
import com.spotify.music.ui.theme.CardWhite
import com.spotify.music.ui.theme.LibraryBg
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.spotify.music.R

@Composable
fun HiddenFoldersScreen(
    library: LibraryRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val folders by library.folders.collectAsState(initial = emptyList())
    val hidden = settings.hiddenFolders
    val scope = rememberCoroutineScope()

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
                stringResource(R.string.hidden_folders_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = SamsungBlue,
                modifier = Modifier.weight(1f),
            )
        }

        if (folders.isEmpty()) {
            Text(
                stringResource(R.string.hidden_empty_hint),
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(folders) { group ->
                    HiddenFolderRow(
                        group = group,
                        isHidden = hidden.contains(group.path),
                        onToggle = { wantHidden ->
                            scope.launch {
                                if (wantHidden) {
                                    library.hideFolder(group.path)
                                } else {
                                    library.unhideFolder(group.path)
                                    runCatching { library.rescan() }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HiddenFolderRow(
    group: FolderGroup,
    isHidden: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardWhite)
            .clickable { onToggle(!isHidden) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.folder_line, group.songs.size, group.path),
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Checkbox(
            checked = isHidden,
            onCheckedChange = { onToggle(it) },
            colors = CheckboxDefaults.colors(
                checkedColor = AccentToggle,
                uncheckedColor = Color(0xFFC4C4CC),
            ),
        )
    }
}
