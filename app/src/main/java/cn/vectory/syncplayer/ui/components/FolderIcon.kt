package cn.vectory.syncplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors

@Composable
internal fun FolderIcon(
    isSyncBaseDir: Boolean = false,
    modifier: Modifier = Modifier
) {
    val extendedColors = LocalExtendedColors.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = "文件夹",
            tint = extendedColors.folderAmber,
            modifier = Modifier.size(40.dp)
        )
        if (isSyncBaseDir) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = "同步基目录",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}
