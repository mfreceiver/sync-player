package cn.vectory.syncplayer.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer for debug log entries (max 1000, no disk persistence).
 *
 * Key subsystems (SyncEngine, SmbFileClient, MediaFileStore, etc.) call [d]/[i]/[w]/[e]
 * instead of (or in addition to) [android.util.Log]. The [DebugLogScreen] UI reads [snapshot]
 * to display a scrollable, level-filterable log view.
 *
 * Thread-safe via a synchronized ArrayDeque. Entries are never written to disk.
 */
object DebugLogger {

    private const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<LogEntry>()
    private val lock = Any()
    // Diagnostic format: pattern is ASCII digits/separators only, so Locale.ROOT keeps
    // the output stable across device locales (no stale captured default locale).
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    enum class Level(val priority: Int, val label: String) {
        DEBUG(3, "D"),
        INFO(4, "I"),
        WARN(5, "W"),
        ERROR(6, "E"),
    }

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String,
    )

    // --- Public logging API (also forwards to android.util.Log) ---

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        add(Level.DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add(Level.INFO, tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        add(Level.WARN, tag, if (t != null) "$msg\n${t.stackTraceToString()}" else msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        add(Level.ERROR, tag, if (t != null) "$msg\n${t.stackTraceToString()}" else msg)
    }

    private fun add(level: Level, tag: String, message: String) {
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(LogEntry(
                // SimpleDateFormat is NOT thread-safe — must format inside the lock.
                timestamp = timeFmt.format(Date()),
                level = level,
                tag = tag,
                message = message,
            ))
        }
    }

    /** Returns a snapshot of current entries (oldest first). Thread-safe. */
    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}
