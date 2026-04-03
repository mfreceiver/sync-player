package cn.vectory.syncplayer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_record",
    foreignKeys = [
        ForeignKey(
            entity = VideoItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["videoItemId"], unique = true)]
)
data class PlaybackRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoItemId: Long,
    val position: Long = 0L,
    val duration: Long = 0L,
    val lastPlayedTime: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val isFavorite: Boolean = false
)
