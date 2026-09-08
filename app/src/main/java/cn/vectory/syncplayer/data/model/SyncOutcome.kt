package cn.vectory.syncplayer.data.model

/**
 * 结构化同步终态快照（F02/T6 落地）。
 *
 * 一次同步/删除/检查完成时，「时间 + 终态 + 错误码 + 失败数」作为一个快照原子写入
 * （单条 UPDATE，见 [cn.vectory.syncplayer.data.local.dao.SyncRelationDao]）；
 * 时间戳由 repository 写入侧生成，本类型只承载终态本身。
 *
 * 持久化约定（强约束）：
 *  - [status] / [errorCode] 以稳定字符串标识（enum name）入库，**绝不存 ordinal、
 *    绝不存本地化文案**；
 *  - 无记录 = 所有列为 NULL，不使用任何哨兵值；
 *  - [failedCount] 只保存展示确实需要的最小计数；拿不到准确值用 null 缺省，
 *    不用 0 伪造；
 *  - CANCELLED 只能由取消路径的「有界收尾点」（NonCancellable 内单条 UPDATE）写入；
 *    CancellationException 本身必须先传播——绝不吞取消、绝不把取消当成功。
 */
data class SyncOutcome(
    val status: Status,
    /** [cn.vectory.syncplayer.sync.SyncErrorCode].name；仅 FAILED（及未来需要时）携带。 */
    val errorCode: String? = null,
    /** 失败动作数；未知为 null，不用 0 伪造。 */
    val failedCount: Int? = null
) {
    enum class Status {
        /** 全部动作成功（含幂等成功语义）。 */
        SUCCESS,

        /** 部分动作失败；[failedCount] 应携带（未知时为 null）。 */
        PARTIAL_FAILED,

        /** 整轮失败（连接/扫描/空间不足/未捕获异常等）；[errorCode] 应携带。 */
        FAILED,

        /** 用户/系统中止；仅由取消路径的有界收尾点写入。 */
        CANCELLED,

        /** 历史记录无法可靠反推状态（如旧文案迁移），保守展示，不默认成功。 */
        UNKNOWN
    }

    companion object {
        /**
         * 从持久化列还原终态。
         * status 列为 null → 返回 null（= 无记录）；
         * status 字符串无法解析（外来/未来值）→ 归一化为 [Status.UNKNOWN]（保守，
         * 绝不默认成功、绝不默认失败）。
         */
        fun fromStored(statusName: String?, errorCode: String?, failedCount: Int?): SyncOutcome? {
            if (statusName == null) return null
            val status = runCatching { Status.valueOf(statusName) }.getOrDefault(Status.UNKNOWN)
            return SyncOutcome(status = status, errorCode = errorCode, failedCount = failedCount)
        }
    }
}
