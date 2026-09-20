package com.spotify.music.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.reorderable.ReorderableItem
import androidx.compose.foundation.reorderable.detectReorderAfterLongPress
import androidx.compose.foundation.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotify.music.R
import com.spotify.music.data.SettingsRepository
import com.spotify.music.ui.library.DEFAULT_TABS
import com.spotify.music.ui.library.tabLabel
import com.spotify.music.ui.theme.SamsungBlue
import com.spotify.music.ui.theme.TextPrimary
import com.spotify.music.ui.theme.TextSecondary

/** 设置 → 管理标签：长按拖动排序 + 开关控制显示/隐藏 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManageTabsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    var order by remember {
        mutableStateOf(
            buildList {
                val saved = settings.tabOrder
                addAll(if (saved.isNotEmpty()) saved else DEFAULT_TABS)
                DEFAULT_TABS.filter { it !in this }.forEach { add(it) }
            }
        )
    }
    var hidden by remember { mutableStateOf(settings.hiddenTabs) }

    fun persist() {
        settings.tabOrder = order
        settings.hiddenTabs = hidden
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val item = order.removeAt(from)
        order = order.toMutableList().apply { add(to, item) }
        persist()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 标题栏
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
            Text(stringResource(R.string.manage_tabs), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SamsungBlue)
        }
        Text(
            stringResource(R.string.manage_tabs_hint),
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )

        LazyColumn(
            state = lazyListState,
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White),
        ) {
            itemsIndexed(order, key = { _, t -> t }) { _, tab ->
                ReorderableItem(reorderableState, key = tab) { isDragging ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isDragging) Color(0xFFEFEFF8) else Color.White)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.reorder),
                            tint = Color(0xFFB0B0B8),
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 4.dp)
                                .detectReorderAfterLongPress(reorderableState),
                        )
                        Text(
                            tabLabel(tab),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = tab !in hidden,
                            onCheckedChange = { checked ->
                                hidden = if (checked) hidden - tab else hidden + tab
                                persist()
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFF8A90DE),
                                checkedThumbColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}
