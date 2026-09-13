package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch

data class DirectoryNode(
    val name: String,
    val fullPath: String,
    val isSyncBaseDir: Boolean = false
)

@Composable
internal fun DirectoryTreeDialog(
    directoryTree: List<DirectoryNode>,
    currentPath: String,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    onLoadChildren: suspend (String) -> List<DirectoryNode>,
    modifier: Modifier = Modifier
) {
    val selectedPath = remember { mutableStateOf(currentPath) }
    val scope = rememberCoroutineScope()
    val loadingPath = remember { mutableStateOf<String?>(null) }

    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
    val childrenMap = remember { mutableStateMapOf<String, List<DirectoryNode>>() }

    fun loadChildrenInto(node: DirectoryNode) {
        if (childrenMap.containsKey(node.fullPath)) {
            val isExpanded = expandedPaths[node.fullPath] == true
            expandedPaths[node.fullPath] = !isExpanded
            return
        }
        loadingPath.value = node.fullPath
        scope.launch {
            val children = onLoadChildren(node.fullPath)
            childrenMap[node.fullPath] = children
            expandedPaths[node.fullPath] = true
            loadingPath.value = null
        }
    }

    fun buildFlatList(): List<FlatEntry> {
        val result = mutableListOf<FlatEntry>()
        fun walk(nodes: List<DirectoryNode>, level: Int) {
            for (node in nodes) {
                val children = childrenMap[node.fullPath]
                val isExpanded = expandedPaths[node.fullPath] == true
                val isLeaf = children != null && children.isEmpty()
                result.add(FlatEntry(node, level, isExpanded, isLeaf))
                if (isExpanded && children != null) {
                    walk(children, level + 1)
                }
            }
        }
        walk(directoryTree, 1)
        return result
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier.width(360.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.browser_select_directory),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(8.dp))

                val flatList by remember { derivedStateOf { buildFlatList() } }

                LazyColumn(
                    modifier = Modifier.heightIn(min = 120.dp, max = 400.dp)
                ) {
                    item {
                        DirectoryTreeRow(
                            name = stringResource(R.string.browser_root_label),
                            fullPath = "",
                            level = 0,
                            isExpanded = true,
                            isLeaf = false,
                            isLoading = false,
                            isSelected = selectedPath.value.isEmpty(),
                            isSyncBaseDir = false,
                            onSelect = {
                                onNavigate("")
                                onDismiss()
                            },
                            onToggleExpand = {}
                        )
                    }

                    items(
                        items = flatList,
                        key = { it.node.fullPath }
                    ) { entry ->
                        DirectoryTreeRow(
                            name = entry.node.name,
                            fullPath = entry.node.fullPath,
                            level = entry.level,
                            isExpanded = entry.isExpanded,
                            isLeaf = entry.isLeaf,
                            isLoading = loadingPath.value == entry.node.fullPath,
                            isSelected = selectedPath.value == entry.node.fullPath,
                            isSyncBaseDir = entry.node.isSyncBaseDir,
                            onSelect = {
                                onNavigate(entry.node.fullPath)
                                onDismiss()
                            },
                            onToggleExpand = { loadChildrenInto(entry.node) }
                        )
                    }
                }
            }
        }
    }
}

private data class FlatEntry(
    val node: DirectoryNode,
    val level: Int,
    val isExpanded: Boolean,
    val isLeaf: Boolean
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirectoryTreeRow(
    name: String,
    fullPath: String,
    level: Int,
    isExpanded: Boolean,
    isLeaf: Boolean,
    isLoading: Boolean,
    isSelected: Boolean,
    isSyncBaseDir: Boolean,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit
) {
    val isRoot = fullPath.isEmpty()
    val extendedColors = LocalExtendedColors.current

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .combinedClickable {
                onSelect()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Spacer(Modifier.width((level * 24).dp))

        if (isRoot) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = stringResource(R.string.browser_root_dir),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        } else if (isSyncBaseDir) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = extendedColors.folderAmber,
                    modifier = Modifier.size(20.dp)
                )
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.BottomEnd)
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = extendedColors.folderAmber,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (!isRoot && !isLeaf) {
            if (isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClick = onToggleExpand
                        )
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = if (isExpanded) stringResource(R.string.browser_collapse) else stringResource(R.string.browser_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
