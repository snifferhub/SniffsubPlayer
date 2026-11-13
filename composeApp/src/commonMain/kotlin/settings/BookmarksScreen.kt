package com.sniffsub.sniff_sub_player.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sniffsub.sniff_sub_player.data.Bookmark

data class BookmarksActions(
    val onBack: () -> Unit,
    val onOpenBookmark: (Bookmark) -> Unit,
    val onDeleteBookmark: (Bookmark) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<Bookmark>,
    actions: BookmarksActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(title = "收藏夹", onBack = actions.onBack) },
    ) { innerPadding ->
        if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(bookmarks, key = Bookmark::id) { bookmark ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.onOpenBookmark(bookmark) }
                            .testTag("bookmark_item_${bookmark.id}")
                            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                bookmark.url,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { actions.onDeleteBookmark(bookmark) },
                            modifier = Modifier.testTag("delete_bookmark_${bookmark.id}"),
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除收藏")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp))
                }
            }
        }
    }
}
