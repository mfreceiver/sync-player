package cn.vectory.syncplayer.sync

/**
 * 复审阻断（敏感日志红线）：sync 写域的安全日志消息 helper。
 *
 * 只承载安全 token：操作类型、结构化错误码（[SyncException.code]）、异常类别
 * （`javaClass.simpleName`）、非敏感数值（行 id/计数/字节数）。绝不拼接 relative/remote
 * path、fileName，绝不传入原始 Throwable/cause/message——调用侧不得把 Throwable 交给
 * `Log.w/e(tag, msg, throwable)` 或 `DebugLogger.w/e(tag, msg, throwable)`（后者的
 * throwable 会以 stackTraceToString 落入调试环形缓冲，泄漏 message/stack）。
 *
 * 可测性边界（成文）：单元测试 `unitTests.isReturnDefaultValues = true` 下 android.util.Log
 * 为 no-op、不可捕获——本类全部为纯格式化函数，由 `SyncSafeLogTest` 锁定「输入异常携带
 * path/fileName/cause message 也只产出安全 token」；`DebugLogger` 环形缓冲路径由管线行为
 * 测试（`failing download logs no path or filename tokens to debug ring`）锁定。
 */
internal object SyncSafeLog {

    /** 失败消息：操作 + 错误码 + 异常类别。SyncException.message 恒 null；非 SyncException 只取类别。 */
    fun failure(operation: String, e: Throwable): String =
        "$operation failed: code=${(e as? SyncException)?.code}, error=${e.javaClass.simpleName}"

    /** 本地删除未完全成功消息：行 id + 两个删除结果布尔（与既有安全格式一致）。 */
    fun localDeleteIncomplete(operation: String, id: Long, mediaGone: Boolean, thumbGone: Boolean): String =
        "$operation local failed id=$id: mediaGone=$mediaGone thumbGone=$thumbGone"

    /** 元数据提取失败消息（非致命路径）：只留异常类别。 */
    fun metadataExtractionFailed(e: Throwable): String =
        "metadata extraction failed: ${e.javaClass.simpleName}"

    /** 清理辅助失败消息（best-effort 路径）：行 id + 异常类别。 */
    fun cleanupFailed(what: String, id: Long, e: Throwable): String =
        "$what failed id=$id: ${e.javaClass.simpleName}"
}
