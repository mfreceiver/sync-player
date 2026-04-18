package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch

data class DirectoryNode(
    val name: String,
    val fullPath: String,
    val children: MutableList<DirectoryNode> = mutableListOf(),
    val isSyncBaseDir: Boolean = false,
    var isExpanded: Boolean = false,
    var isLoaded: Boolean = false
) {
    /**
     * Create an independent deep copy of this node tree.
     * Used to trigger Compose recomposition when internal mutable state changes.
     */
    fun deepCopy(): DirectoryNode = DirectoryNode(
        name = name,
        fullPath = fullPath,
        children = children.map { it.deepCopy() }.toMutableList(),
        isSyncBaseDir = isSyncBaseDir,
        isExpanded = isExpanded,
        isLoaded = isLoaded
    )
}

@Composable
internal fun DirectoryTreeDialog(
    directoryTree: List<DirectoryNode>,
    currentPath: String,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    onLoadChildren: suspend (String) -> List<DirectoryNode>,
    modifier: Modifier = Modifier
) {
    val treeState = remember(directoryTree) {
        mutableStateOf(directoryTree.map { it.deepCopy() }.also { tree ->
            expandAncestors(tree, currentPath)
        })
    }

    val selectedPath = remember { mutableStateOf(currentPath) }
    val scope = rememberCoroutineScope()
    val loadingPath = remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier.width(360.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "选择目录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    item {
                        DirectoryTreeNode(
                            node = DirectoryNode(
                                name = "根目录",
                                fullPath = "",
                                isExpanded = true
                            ),
                            level = 0,
                            currentPath = selectedPath.value,
                            isLoading = false,
                            onSelect = { selectedPath.value = it },
                            onToggleExpand = {}
                        )
                    }

                    items(
                        items = flattenedNodes(treeState.value),
                        key = { it.node.fullPath }
                    ) { item ->
                        DirectoryTreeNode(
                            node = item.node,
                            level = item.level,
                            currentPath = selectedPath.value,
                            isLoading = loadingPath.value == item.node.fullPath,
                            onSelect = { selectedPath.value = it },
                            onToggleExpand = { path ->
                                val node = findNode(treeState.value, path)
                                if (node != null && !node.isLoaded) {
                                    loadingPath.value = path
                                    scope.launch {
                                        val children = onLoadChildren(path)
                                        node.children.clear()
                                        node.children.addAll(children)
                                        node.isLoaded = true
                                        node.isExpanded = true
                                        loadingPath.value = null
                                        treeState.value = treeState.value.map { it.deepCopy() }
                                    }
                                } else if (node != null) {
                                    node.isExpanded = !node.isExpanded
                                    treeState.value = treeState.value.map { it.deepCopy() }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        onNavigate(selectedPath.value)
                        onDismiss()
                    }) {
                        Text("确认")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirectoryTreeNode(
    node: DirectoryNode,
    level: Int,
    currentPath: String,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    onToggleExpand: (String) -> Unit
) {
    val isSelected = node.fullPath == currentPath
    val extendedColors = LocalExtendedColors.current
    val isRoot = node.fullPath.isEmpty()

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .combinedClickable {
                onSelect(node.fullPath)
                if (!isRoot) {
                    onToggleExpand(node.fullPath)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Spacer(Modifier.width((level * 24).dp))

        if (isRoot) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = "根目录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        } else if (node.isSyncBaseDir) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "文件夹",
                    tint = extendedColors.folderAmber,
                    modifier = Modifier.size(20.dp)
                )
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = "同步基目录",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.BottomEnd)
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "文件夹",
                tint = extendedColors.folderAmber,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (!isRoot && (node.children.isNotEmpty() || !node.isLoaded)) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = if (node.isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (node.isExpanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private data class FlatNode(
    val node: DirectoryNode,
    val level: Int
)

private fun flattenedNodes(roots: List<DirectoryNode>): List<FlatNode> {
    val result = mutableListOf<FlatNode>()
    fun walk(nodes: List<DirectoryNode>, level: Int) {
        for (node in nodes) {
            result.add(FlatNode(node, level))
            if (node.isExpanded) {
                walk(node.children, level + 1)
            }
        }
    }
    walk(roots, 1)
    return result
}

private fun findNode(roots: List<DirectoryNode>, path: String): DirectoryNode? {
    for (node in roots) {
        if (node.fullPath == path) return node
        findNode(node.children, path)?.let { return it }
    }
    return null
}

private fun expandAncestors(nodes: List<DirectoryNode>, targetPath: String) {
    if (targetPath.isEmpty()) return
    fun walk(node: DirectoryNode): Boolean {
        if (node.fullPath == targetPath) {
            return true
        }
        for (child in node.children) {
            if (walk(child)) {
                node.isExpanded = true
                return true
            }
        }
        return false
    }
    for (node in nodes) {
        walk(node)
    }
}
