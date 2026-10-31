package cn.vectory.syncplayer.data.local.dao

import androidx.room.*
import cn.vectory.syncplayer.data.local.entity.VideoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_item WHERE parentPath = :folderPath ORDER BY fileName COLLATE NOCASE ASC")
    fun getItemsInFolder(folderPath: String): Flow<List<VideoItemEntity>>

    @Query("""
        SELECT DISTINCT folderName FROM (
            SELECT SUBSTR(
                CASE WHEN :folderPath = '' THEN parentPath
                ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                END,
                1,
                INSTR(
                    CASE WHEN :folderPath = '' THEN parentPath
                    ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                    END || '/',
                    '/'
                ) - 1
            ) AS folderName
            FROM video_item
            WHERE CASE
                WHEN :folderPath = '' THEN parentPath != ''
                ELSE substr(parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
            END
        )
        WHERE folderName != ''
    """)
    fun getSubFolders(folderPath: String): Flow<List<String>>

    @Query("""
        SELECT DISTINCT folderName FROM (
            SELECT SUBSTR(
                CASE WHEN :folderPath = '' THEN parentPath
                ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                END,
                1,
                INSTR(
                    CASE WHEN :folderPath = '' THEN parentPath
                    ELSE SUBSTR(parentPath, LENGTH(:folderPath) + 2)
                    END || '/',
                    '/'
                ) - 1
            ) AS folderName
            FROM video_item
            WHERE CASE
                WHEN :folderPath = '' THEN parentPath != ''
                ELSE substr(parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
            END
        )
        WHERE folderName != ''
    """)
    suspend fun getSubFoldersOnce(folderPath: String): List<String>

    @Query("SELECT * FROM video_item WHERE id = :id")
    suspend fun getById(id: Long): VideoItemEntity?

    /** Synchronous variant for DocumentsProvider binder-thread use (no coroutine overhead). */
    @Query("SELECT * FROM video_item WHERE id = :id")
    fun getByIdSync(id: Long): VideoItemEntity?

    @Query("SELECT * FROM video_item WHERE relativePath = :relativePath")
    suspend fun getByRelativePath(relativePath: String): VideoItemEntity?

    @Query("SELECT * FROM video_item")
    suspend fun getAll(): List<VideoItemEntity>

    @Query("SELECT * FROM video_item WHERE syncRelationId = :relationId")
    suspend fun getBySyncRelationId(relationId: Long): List<VideoItemEntity>

    @Query("SELECT * FROM video_item WHERE relativePath = :relativePath AND syncRelationId = :syncRelationId")
    suspend fun getByRelativePathAndRelationId(relativePath: String, syncRelationId: Long): VideoItemEntity?

    @Upsert
    suspend fun upsert(item: VideoItemEntity): Long

    @Upsert
    suspend fun upsertAll(items: List<VideoItemEntity>)

    @Query("DELETE FROM video_item WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM video_item WHERE relativePath = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM video_item WHERE relativePath = :relativePath AND syncRelationId = :syncRelationId")
    suspend fun deleteByRelativePathAndRelationId(relativePath: String, syncRelationId: Long)

    @Query("DELETE FROM video_item")
    suspend fun deleteAll()

    /** Items under [folderPath] (exact or descendants). Binary-exact prefix (no LIKE wildcards/case folding). */
    @Query("""
        SELECT * FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN 1
            ELSE (relativePath = :folderPath
                  OR substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/')
        END
    """)
    suspend fun getByPathPrefix(folderPath: String): List<VideoItemEntity>

    /** Relation-scoped path prefix query for deleteFolder isolation. Binary-exact prefix. */
    @Query("""
        SELECT * FROM video_item
        WHERE syncRelationId = :syncRelationId
          AND CASE
              WHEN :folderPath = '' THEN 1
              ELSE (relativePath = :folderPath
                    OR substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/')
          END
    """)
    suspend fun getByPathPrefixAndRelation(folderPath: String, syncRelationId: Long): List<VideoItemEntity>

    /**
     * ParentPaths of all descendants under [folderPath] that have at least one more path segment
     * after the direct child. Used to compute folderHasSubDirs in one query.
     */
    @Query("""
        SELECT DISTINCT parentPath FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN parentPath LIKE '%/%'
            ELSE (substr(parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
                  AND instr(substr(parentPath, length(:folderPath) + 2), '/') > 0)
        END
    """)
    suspend fun getDeepParentPaths(folderPath: String): List<String>

    @Query("SELECT id FROM video_item ORDER BY fileName ASC")
    suspend fun getAllIdsSorted(): List<Long>

    data class VideoItemWithPlayback(
        val id: Long,
        val relativePath: String,
        val fileName: String,
        val fileSize: Long,
        val duration: Long?,
        val width: Int?,
        val height: Int?,
        val bitrate: Long?,
        val frameRate: Float?,
        val audioGainDb: Float?,
        val star: Int,
        val mediaType: String,
        val dateTaken: Long?,
        val playbackPosition: Long,
        val playbackDuration: Long,
        val isCompleted: Boolean,
        val isTrashed: Boolean
    )

    @Query("""
        SELECT v.id, v.fileName, v.relativePath, v.fileSize, v.duration, v.width, v.height,
               v.bitrate, v.frameRate, v.audioGainDb, v.star, v.mediaType, v.dateTaken,
               COALESCE(pr.position, 0) as playbackPosition,
               COALESCE(pr.duration, 0) as playbackDuration,
               COALESCE(pr.isCompleted, 0) as isCompleted,
               COALESCE(pr.isTrashed, 0) as isTrashed
        FROM video_item v
        LEFT JOIN playback_record pr ON pr.videoItemId = v.id
        WHERE v.fileName LIKE '%' || REPLACE(REPLACE(REPLACE(:query, '\', '\\'), '%', '\%'), '_', '\_') || '%' COLLATE NOCASE ESCAPE '\'
    """)
    fun searchByFileName(query: String): Flow<List<VideoItemWithPlayback>>

    @Query("""
        SELECT v.id, v.fileName, v.relativePath, v.fileSize, v.duration, v.width, v.height,
               v.bitrate, v.frameRate, v.audioGainDb, v.star, v.mediaType, v.dateTaken,
               COALESCE(pr.position, 0) as playbackPosition,
               COALESCE(pr.duration, 0) as playbackDuration,
               COALESCE(pr.isCompleted, 0) as isCompleted,
               COALESCE(pr.isTrashed, 0) as isTrashed
        FROM video_item v
        LEFT JOIN playback_record pr ON pr.videoItemId = v.id
        WHERE v.fileName LIKE '%' || REPLACE(REPLACE(REPLACE(:query, '\', '\\'), '%', '\%'), '_', '\_') || '%' COLLATE NOCASE ESCAPE '\'
        AND CASE
            WHEN :folder = '' THEN 1
            ELSE (v.parentPath = :folder
                  OR substr(v.parentPath, 1, length(:folder) + 1) = :folder || '/')
        END
    """)
    fun searchByFileNameInFolder(query: String, folder: String): Flow<List<VideoItemWithPlayback>>

    @Query("SELECT DISTINCT relativePath FROM video_item")
    fun getAllRelativePaths(): Flow<List<String>>

    @Query("SELECT DISTINCT parentPath FROM video_item")
    fun getAllParentPaths(): Flow<List<String>>

    @Query("""
        SELECT DISTINCT parentPath FROM video_item
        WHERE CASE
            WHEN :folder = '' THEN 1
            ELSE (parentPath = :folder
                  OR substr(parentPath, 1, length(:folder) + 1) = :folder || '/')
        END
    """)
    fun getParentPathsInFolder(folder: String): Flow<List<String>>

    // Recursive folder statistics for requirement 1 (all files under a folder path)
    data class RecursiveFolderStat(
        val totalFileCount: Int,
        val totalVideoCount: Int,
        val totalImageCount: Int,
        val totalSize: Long
    )

    @Query("""
        SELECT 
            COUNT(*) as totalFileCount,
            SUM(CASE WHEN mediaType = 'VIDEO' THEN 1 ELSE 0 END) as totalVideoCount,
            SUM(CASE WHEN mediaType = 'IMAGE' THEN 1 ELSE 0 END) as totalImageCount,
            COALESCE(SUM(fileSize), 0) as totalSize
        FROM video_item
        WHERE CASE
            WHEN :folderPath = '' THEN 1
            ELSE substr(relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
        END
    """
    )
    suspend fun getRecursiveFolderStats(folderPath: String): RecursiveFolderStat

    // Flat mode: get all items for file browsing mode
    @Query("SELECT * FROM video_item")
    fun getAllItemsFlow(): Flow<List<VideoItemEntity>>

    /**
     * Efficient boolean flow indicating if any video items exist in the library.
     * Avoids loading and mapping all entities when only emptiness is needed.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM video_item)")
    fun hasAnyItemsFlow(): Flow<Boolean>

    // Flat mode: get total count for scrollbar estimation
    @Query("SELECT COUNT(*) FROM video_item")
    suspend fun getTotalItemCount(): Int

    // Get all distinct directory paths for directory tree building
    @Query("SELECT DISTINCT relativePath FROM video_item")
    suspend fun getAllRelativePathsList(): List<String>

    // Update star rating for a video item
    @Query("UPDATE video_item SET star = :star WHERE id = :id")
    suspend fun updateStar(id: Long, star: Int)

    // Update loudness gain for a video item (single-column write: never resurrects a
    // deleted row and never clobbers concurrent column updates).
    @Query("UPDATE video_item SET audioGainDb = :audioGainDb WHERE id = :id")
    suspend fun updateAudioGain(id: Long, audioGainDb: Float?)

    // ------------------------------------------------------------------ A1 按列更新（列所有权）

    /**
     * A1: 同步引擎列提交——只动 fileSize/remoteModifiedTime/dateSynced（下载管线
     * finalize 成功后的最终元数据）。返回受影响行数；行不存在（含并发删除）⇒ 0，
     * 绝不插入、绝不复活。整行 upsert 会用下载前快照覆盖用户列（star/audioGainDb）
     * 且 @Upsert 带已知 id 会复活被删行——禁止用于此场景。
     */
    @Query(
        "UPDATE video_item SET fileSize = :fileSize, remoteModifiedTime = :remoteModifiedTime, dateSynced = :dateSynced WHERE id = :id"
    )
    suspend fun updateSyncMetadata(id: Long, fileSize: Long, remoteModifiedTime: Long?, dateSynced: Long): Int

    /**
     * v5 双卷存储列提交（冻结契约「接口先行」）：一次 UPDATE 同步提交
     * volumeId/storageFormat/iv/generation/storageState + fileSize/remoteModifiedTime/dateSynced。
     *
     * CAS：仅当行存在且 generation 仍等于 [expectedOldGeneration]（含双方均为 null 的
     * 新建 PENDING 行）时更新。返回 0 = 行已被并发提交/删除 —— 调用方必须失败本 attempt
     * （回滚文件），绝不 upsert 复活、绝不覆盖 star/audioGainDb。
     */
    @Query(
        """
        UPDATE video_item SET
            volumeId = :volumeId,
            storageFormat = :storageFormat,
            iv = :iv,
            generation = :generation,
            storageState = :storageState,
            fileSize = :fileSize,
            remoteModifiedTime = :remoteModifiedTime,
            dateSynced = :dateSynced
        WHERE id = :id
          AND ((:expectedOldGeneration IS NULL AND generation IS NULL) OR generation = :expectedOldGeneration)
        """
    )
    suspend fun updateSyncStorageMetadata(
        id: Long,
        expectedOldGeneration: String?,
        volumeId: String,
        storageFormat: String,
        iv: ByteArray?,
        generation: String?,
        storageState: String,
        fileSize: Long,
        remoteModifiedTime: Long?,
        dateSynced: Long
    ): Int

    /**
     * A1: 视频提取器列提交——完整覆盖五个所有列（含显式 null 清除陈旧值：
     * 「成功但字段缺失」语义；提取失败则调用方不得触 DB）。行不存在 ⇒ 0，绝不复活。
     */
    @Query(
        "UPDATE video_item SET duration = :duration, width = :width, height = :height, bitrate = :bitrate, frameRate = :frameRate WHERE id = :id"
    )
    suspend fun updateVideoMetadata(
        id: Long,
        duration: Long?,
        width: Int?,
        height: Int?,
        bitrate: Long?,
        frameRate: Float?
    ): Int

    /**
     * A1: 图片提取器列提交——width/height/dateTaken 三列（null 语义同上）。
     * 行不存在 ⇒ 0，绝不复活；绝不动 video 专属列/用户列/同步列。
     */
    @Query(
        "UPDATE video_item SET width = :width, height = :height, dateTaken = :dateTaken WHERE id = :id"
    )
    suspend fun updateImageMetadata(id: Long, width: Int?, height: Int?, dateTaken: Long?): Int

    @Query("SELECT * FROM video_item ORDER BY fileName ASC")
    fun getFlatItemsFlow(): Flow<List<VideoItemEntity>>

    @Query("""
        SELECT vi.id, vi.relativePath, vi.fileName, vi.audioGainDb,
               pr.position, pr.isCompleted, pr.isTrashed
        FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE vi.mediaType IN ('VIDEO', 'IMAGE')
        AND CASE
            WHEN :folderPath = '' THEN
                vi.parentPath != '' AND vi.parentPath NOT LIKE '%/%'
            ELSE
                (substr(vi.parentPath, 1, length(:folderPath) + 1) = :folderPath || '/'
                AND LENGTH(SUBSTR(vi.parentPath, LENGTH(:folderPath) + 2))
                    - LENGTH(REPLACE(SUBSTR(vi.parentPath, LENGTH(:folderPath) + 2), '/', '')) = 0)
        END
    """)
    suspend fun getChildVideoStats(folderPath: String): List<ChildVideoStat>

    data class ChildVideoStat(
        val id: Long,
        val relativePath: String,
        val fileName: String,
        val audioGainDb: Float?,
        val position: Long,
        val isCompleted: Boolean,
        val isTrashed: Boolean
    )

    /**
     * U2-4a: [folderPath] 的**任意深度**后代媒体行（relativePath 前缀 folder+'/'，含递归层级，
     * 直接位于 [folderPath] 内的文件同样计入）。与 [getChildVideoStats] 的区别是去掉了
     * 「恰好直接子目录一层」的深度限制；单次扫描服务当前文件夹全部直接子文件夹的缩略图
     * 合成，绝不按文件夹逐个查询（无 N+1）。注意前缀匹配用 relativePath（parentPath 前缀
     * 会漏掉 folder 本身的直属文件）；根目录（''）= parentPath 非空（排除根散落文件）。
     */
    @Query("""
        SELECT vi.id, vi.relativePath, vi.fileName, vi.audioGainDb,
               pr.position, pr.isCompleted, pr.isTrashed
        FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE vi.mediaType IN ('VIDEO', 'IMAGE')
        AND CASE
            WHEN :folderPath = '' THEN vi.parentPath != ''
            ELSE substr(vi.relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
        END
        ORDER BY vi.fileName COLLATE NOCASE ASC, vi.relativePath COLLATE BINARY ASC, vi.id ASC
    """)
    suspend fun getDescendantVideoStats(folderPath: String): List<ChildVideoStat>

    /**
     * U2c: [folderPath] 路径范围内的后代候选行（单次扫描，**无任何语义过滤**）。
     * 路径口径与 [getDescendantVideoStats] 相同：任意深度后代（前缀匹配 relativePath）；
     * 根目录（''）= parentPath 非空。
     *
     * 为什么不做 mediaType 的 media/star/unwatched SQL 门：mediaType 不可靠 —— 同步占位
     * 实体不设 mediaType（SyncDownloadPipeline 占位行），落在 [VideoItemEntity] 默认值
     * "VIDEO" 上，后续按列 UPDATE 也不修正；非媒体文件（.txt/.nfo 等）入库后 mediaType
     * 同为 VIDEO，按它建门必然错杀/漏放。筛选口径（扩展名分类 + 未看判定 + star 仅作用
     * 媒体扩展名行）统一在 BrowseItemBuilder 内与目录列表同源执行，保证「列表显示 ⇔
     * 父文件夹计数」同进同出。
     */
    data class DescendantFilterStat(
        val id: Long,
        val relativePath: String,
        val fileName: String,
        val mediaType: String,
        val star: Int,
        val playbackPosition: Long,
        val playbackDuration: Long,
        val isCompleted: Boolean
    )

    @Query("""
        SELECT vi.id, vi.relativePath, vi.fileName, vi.mediaType, vi.star,
               COALESCE(pr.position, 0) AS playbackPosition,
               COALESCE(pr.duration, 0) AS playbackDuration,
               COALESCE(pr.isCompleted, 0) AS isCompleted
        FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE CASE
            WHEN :folderPath = '' THEN vi.parentPath != ''
            ELSE substr(vi.relativePath, 1, length(:folderPath) + 1) = :folderPath || '/'
        END
        ORDER BY vi.relativePath COLLATE NOCASE ASC
    """)
    suspend fun getDescendantFilterStats(folderPath: String): List<DescendantFilterStat>

    data class FlatItemWithPlayback(
        val id: Long,
        val fileName: String,
        val relativePath: String,
        val fileSize: Long,
        val duration: Long?,
        val width: Int?,
        val height: Int?,
        val bitrate: Long?,
        val frameRate: Float?,
        val audioGainDb: Float?,
        val star: Int,
        val mediaType: String,
        val dateTaken: Long?,
        val playbackPosition: Long,
        val playbackDuration: Long,
        val isCompleted: Boolean,
        val isTrashed: Boolean
    )

    @Query("""
        SELECT vi.id, vi.fileName, vi.relativePath, vi.fileSize, vi.duration, vi.width, vi.height,
               vi.bitrate, vi.frameRate, vi.audioGainDb, vi.star, vi.mediaType, vi.dateTaken,
               COALESCE(pr.position, 0) AS playbackPosition,
               COALESCE(pr.duration, 0) AS playbackDuration,
               COALESCE(pr.isCompleted, 0) AS isCompleted,
               COALESCE(pr.isTrashed, 0) AS isTrashed
        FROM video_item vi
        LEFT JOIN playback_record pr ON pr.videoItemId = vi.id
        WHERE (:mediaOnly = 0 OR vi.mediaType IN ('VIDEO', 'IMAGE'))
          AND (:unwatchedOnly = 0 OR (pr.position IS NULL OR (pr.position = 0 AND COALESCE(pr.duration, 0) = 0 AND COALESCE(pr.isCompleted, 0) = 0)))
          AND (:starMode = 'OFF' OR
               (:starMode = 'EQ' AND vi.star = :starValue) OR
               (:starMode = 'GT' AND vi.star > :starValue) OR
               (:starMode = 'LT' AND vi.star < :starValue))
        ORDER BY
          CASE WHEN :sortCol = 'duration_asc' THEN CASE WHEN vi.mediaType = 'IMAGE' THEN vi.dateTaken ELSE vi.duration END END ASC,
          CASE WHEN :sortCol = 'duration_desc' THEN CASE WHEN vi.mediaType = 'IMAGE' THEN vi.dateTaken ELSE vi.duration END END DESC,
          CASE WHEN :sortCol = 'fileSize_asc' THEN vi.fileSize END ASC,
          CASE WHEN :sortCol = 'fileSize_desc' THEN vi.fileSize END DESC
    """)
    suspend fun getFlatItemsFiltered(
        mediaOnly: Int,
        unwatchedOnly: Int,
        starMode: String,
        starValue: Int,
        sortCol: String
    ): List<FlatItemWithPlayback>
}
