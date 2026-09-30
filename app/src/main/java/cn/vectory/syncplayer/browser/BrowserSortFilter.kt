package cn.vectory.syncplayer.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R

/**
 * The sort/filter/view-mode dropdown shown from the browser top bar.
 *
 * Visual sections are extracted into dedicated [Sort*Section] / [Filter*Section]
 * composables so the dropdown body stays a flat list of section calls + dividers.
 */
@Composable
internal fun SortFilterDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sortMode: BrowserViewModel.SortMode,
    onSortChange: (BrowserViewModel.SortMode) -> Unit,
    filter: BrowserViewModel.BrowseFilter,
    onFilterChange: (BrowserViewModel.BrowseFilter) -> Unit,
    browseMode: BrowserViewModel.BrowseMode,
    onBrowseModeChange: (BrowserViewModel.BrowseMode) -> Unit,
    viewMode: BrowserViewModel.ViewMode,
    onViewModeChange: (BrowserViewModel.ViewMode) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    starFilterMode: BrowserViewModel.StarFilterMode,
    onClearStarFilter: () -> Unit,
    onAdvancedFilter: () -> Unit,
    folder: String
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 260.dp)
    ) {
        SortSection(sortMode = sortMode, onSortChange = onSortChange)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        FilterSection(filter = filter, onFilterChange = onFilterChange, onDismiss = onDismiss)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        if (browseMode == BrowserViewModel.BrowseMode.FILE || folder.isEmpty()) {
            BrowseModeSection(browseMode = browseMode, onBrowseModeChange = onBrowseModeChange, onDismiss = onDismiss)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
        ViewModeSection(viewMode = viewMode, onViewModeChange = onViewModeChange, onDismiss = onDismiss)
        if (viewMode == BrowserViewModel.ViewMode.GRID || viewMode == BrowserViewModel.ViewMode.STAGGERED_GRID) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            GridColumnsSection(gridColumns = gridColumns, onGridColumnsChange = onGridColumnsChange)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        DropdownFooter(
            starFilterMode = starFilterMode,
            onClearStarFilter = onClearStarFilter,
            onAdvancedFilter = {
                onDismiss()
                onAdvancedFilter()
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun SortSection(
    sortMode: BrowserViewModel.SortMode,
    onSortChange: (BrowserViewModel.SortMode) -> Unit
) {
    Text(
        stringResource(R.string.browser_sort),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SortFieldChip(
            selected = sortMode == BrowserViewModel.SortMode.FILENAME_ASC || sortMode == BrowserViewModel.SortMode.FILENAME_DESC,
            ascending = sortMode == BrowserViewModel.SortMode.FILENAME_ASC,
            mainIcon = Icons.Default.SortByAlpha,
            onClick = {
                val next = when (sortMode) {
                    BrowserViewModel.SortMode.FILENAME_ASC -> BrowserViewModel.SortMode.FILENAME_DESC
                    else -> BrowserViewModel.SortMode.FILENAME_ASC
                }
                onSortChange(next)
            },
            contentDescription = stringResource(R.string.browser_sort_filename)
        )
        SortFieldChip(
            selected = sortMode == BrowserViewModel.SortMode.DURATION_ASC || sortMode == BrowserViewModel.SortMode.DURATION_DESC,
            ascending = sortMode == BrowserViewModel.SortMode.DURATION_ASC,
            mainIcon = Icons.Default.Schedule,
            onClick = {
                val next = when (sortMode) {
                    BrowserViewModel.SortMode.DURATION_ASC -> BrowserViewModel.SortMode.DURATION_DESC
                    else -> BrowserViewModel.SortMode.DURATION_ASC
                }
                onSortChange(next)
            },
            contentDescription = stringResource(R.string.browser_sort_duration)
        )
        SortFieldChip(
            selected = sortMode == BrowserViewModel.SortMode.FILE_SIZE_ASC || sortMode == BrowserViewModel.SortMode.FILE_SIZE_DESC,
            ascending = sortMode == BrowserViewModel.SortMode.FILE_SIZE_ASC,
            mainIcon = Icons.Default.SdCard,
            onClick = {
                val next = when (sortMode) {
                    BrowserViewModel.SortMode.FILE_SIZE_ASC -> BrowserViewModel.SortMode.FILE_SIZE_DESC
                    else -> BrowserViewModel.SortMode.FILE_SIZE_ASC
                }
                onSortChange(next)
            },
            contentDescription = stringResource(R.string.browser_sort_size)
        )
    }
}

@Composable
private fun FilterSection(
    filter: BrowserViewModel.BrowseFilter,
    onFilterChange: (BrowserViewModel.BrowseFilter) -> Unit,
    onDismiss: () -> Unit
) {
    SectionLabel(stringResource(R.string.browser_filter))
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconOptionChip(
            selected = filter == BrowserViewModel.BrowseFilter.ALL,
            onClick = { onFilterChange(BrowserViewModel.BrowseFilter.ALL); onDismiss() },
            contentDescription = stringResource(R.string.browser_filter_all)
        ) { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(20.dp)) }
        IconOptionChip(
            selected = filter == BrowserViewModel.BrowseFilter.MEDIA_ONLY,
            onClick = { onFilterChange(BrowserViewModel.BrowseFilter.MEDIA_ONLY); onDismiss() },
            contentDescription = stringResource(R.string.browser_filter_media_only)
        ) { Icon(Icons.Default.Collections, null, modifier = Modifier.size(20.dp)) }
        IconOptionChip(
            selected = filter == BrowserViewModel.BrowseFilter.UNWATCHED,
            onClick = { onFilterChange(BrowserViewModel.BrowseFilter.UNWATCHED); onDismiss() },
            contentDescription = stringResource(R.string.browser_filter_unwatched)
        ) { Icon(Icons.Default.PlayCircleOutline, null, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun BrowseModeSection(
    browseMode: BrowserViewModel.BrowseMode,
    onBrowseModeChange: (BrowserViewModel.BrowseMode) -> Unit,
    onDismiss: () -> Unit
) {
    SectionLabel(stringResource(R.string.browser_browse))
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconOptionChip(
            selected = browseMode == BrowserViewModel.BrowseMode.DIRECTORY,
            onClick = { onBrowseModeChange(BrowserViewModel.BrowseMode.DIRECTORY); onDismiss() },
            contentDescription = stringResource(R.string.browser_browse_directory)
        ) { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp)) }
        IconOptionChip(
            selected = browseMode == BrowserViewModel.BrowseMode.FILE,
            onClick = { onBrowseModeChange(BrowserViewModel.BrowseMode.FILE); onDismiss() },
            contentDescription = stringResource(R.string.browser_browse_file)
        ) { Icon(Icons.Default.VideoFile, null, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun ViewModeSection(
    viewMode: BrowserViewModel.ViewMode,
    onViewModeChange: (BrowserViewModel.ViewMode) -> Unit,
    onDismiss: () -> Unit
) {
    SectionLabel(stringResource(R.string.browser_view))
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconOptionChip(
            selected = viewMode == BrowserViewModel.ViewMode.LIST,
            onClick = { onViewModeChange(BrowserViewModel.ViewMode.LIST); onDismiss() },
            contentDescription = stringResource(R.string.browser_view_list)
        ) { Icon(Icons.AutoMirrored.Filled.ViewList, null, modifier = Modifier.size(20.dp)) }
        IconOptionChip(
            selected = viewMode == BrowserViewModel.ViewMode.GRID,
            onClick = { onViewModeChange(BrowserViewModel.ViewMode.GRID); onDismiss() },
            contentDescription = stringResource(R.string.browser_view_grid)
        ) { Icon(Icons.Default.Apps, null, modifier = Modifier.size(20.dp)) }
        IconOptionChip(
            selected = viewMode == BrowserViewModel.ViewMode.STAGGERED_GRID,
            onClick = { onViewModeChange(BrowserViewModel.ViewMode.STAGGERED_GRID); onDismiss() },
            contentDescription = stringResource(R.string.browser_view_staggered)
        ) {
            // AND-074: hardcoded white was invisible on the light theme — track the
            // chip's content color like the Icon siblings do.
            val glyphColor = LocalContentColor.current
            Canvas(modifier = Modifier.size(20.dp)) {
                val barWidth = size.width / 5f
                val gap = barWidth
                drawRect(glyphColor, topLeft = Offset(0f, size.height * 0.15f), size = Size(barWidth, size.height * 0.85f))
                drawRect(glyphColor, topLeft = Offset(barWidth + gap, 0f), size = Size(barWidth, size.height * 0.6f))
                drawRect(glyphColor, topLeft = Offset(2 * (barWidth + gap), size.height * 0.3f), size = Size(barWidth, size.height * 0.7f))
            }
        }
    }
}

@Composable
private fun GridColumnsSection(
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(1, 2, 3, 4, 5).forEach { col ->
            GridColumnChip(
                selected = gridColumns == col,
                onClick = { onGridColumnsChange(col) },
                label = "$col"
            )
        }
    }
}

@Composable
private fun DropdownFooter(
    starFilterMode: BrowserViewModel.StarFilterMode,
    onClearStarFilter: () -> Unit,
    onAdvancedFilter: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onAdvancedFilter) {
            Text(stringResource(R.string.browser_advanced_filter), style = MaterialTheme.typography.bodySmall)
        }
        if (starFilterMode != BrowserViewModel.StarFilterMode.OFF) {
            TextButton(onClick = onClearStarFilter) {
                Text(
                    stringResource(R.string.browser_clear_filter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
internal fun IconOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
    icon: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .size(40.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            icon()
        }
    }
}

@Composable
internal fun SortFieldChip(
    selected: Boolean,
    ascending: Boolean,
    mainIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String
) {
    IconOptionChip(selected = selected, onClick = onClick, modifier = modifier, contentDescription = contentDescription) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(mainIcon, null, modifier = Modifier.size(18.dp))
            if (selected) {
                Icon(
                    if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    null,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
internal fun GridColumnChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
