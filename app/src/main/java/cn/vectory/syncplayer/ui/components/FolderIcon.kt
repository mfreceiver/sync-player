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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.syncplayer.R
import cn.vectory.syncplayer.ui.theme.LocalExtendedColors

@Composable
internal fun FolderIcon(
    modifier: Modifier = Modifier,
    isSyncBaseDir: Boolean = false
) {
    val extendedColors = LocalExtendedColors.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = stringResource(R.string.folder),
            tint = extendedColors.folderAmber,
            modifier = Modifier.size(40.dp)
        )
        if (isSyncBaseDir) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = stringResource(R.string.sync_base_dir),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}
