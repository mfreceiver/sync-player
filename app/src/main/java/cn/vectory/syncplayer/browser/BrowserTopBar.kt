package cn.vectory.syncplayer.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R

/**
 * The browser top bar: navigation icon (settings at root / home in folder),
 * current folder title (opens the directory tree), and the action icons
 * (sync, search, sort/filter). The advanced-filter active state tints the
 * sort/filter icon and shows a background pill behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserTopBar(
    folder: String,
    currentRelationId: Long?,
    starFilterActive: Boolean,
    onSettings: () -> Unit,
    onHome: () -> Unit,
    onTitleClick: () -> Unit,
    onSync: () -> Unit,
    onSearch: () -> Unit,
    onSortFilterClick: () -> Unit,
    sortFilterDropdown: @Composable () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            if (folder.isEmpty()) {
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.browser_settings)
                    )
                }
            } else {
                IconButton(onClick = onHome) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = stringResource(R.string.browser_root_dir)
                    )
                }
            }
        },
        title = {
            val displayPath = folder.substringAfterLast('/')
            val title = if (displayPath.isNotEmpty()) displayPath
                else stringResource(R.string.browser_app_title)
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onTitleClick() }
            )
        },
        actions = {
            if (currentRelationId != null) {
                IconButton(onClick = onSync) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = stringResource(R.string.browser_sync)
                    )
                }
            }
            IconButton(onClick = onSearch) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.browser_search)
                )
            }
            Box {
                if (starFilterActive) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(8.dp)
                            )
                    )
                }
                IconButton(onClick = onSortFilterClick) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.browser_sort_filter),
                        tint = if (starFilterActive) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                sortFilterDropdown()
            }
        }
    )
}
