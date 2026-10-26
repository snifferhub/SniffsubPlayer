package com.sniffsub.sniff_sub_player.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BrowserTabBar(
    state: BrowserTabsState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.tabs, key = { it.id }) { tab ->
                val isSelected = state.selectedId == tab.id
                Row(
                    Modifier.widthIn(min = 96.dp, max = 210.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(16.dp),
                        )
                        .semantics { selected = isSelected }
                        .clickable { onSelect(tab.id) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.title.ifBlank { addressDisplayText(tab.url).ifBlank { "新标签页" } },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "关闭标签页", Modifier.size(16.dp))
                    }
                }
            }
        }
        IconButton(onClick = onAdd, enabled = state.tabs.size < BrowserWorkspace.MAX_TABS) {
            Icon(Icons.Default.Add, "新建标签页")
        }
    }
}
