package cn.vectory.syncplayer.debug

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class DebugLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    private val buffer = ConcurrentLinkedQueue<String>()
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()
    private val maxLogLines = 1000

    private var currentLogFile: File? = null
    private var previousLogFile: File? = null

    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    fun init() {
        val wasEnabled = prefs.getBoolean("debug_mode", false)
        if (wasEnabled) {
            previousLogFile = findLatestLogFile()
        }
        prefs.edit().putBoolean("debug_mode", false).apply()
        _enabled.value = false
    }

    fun enable() {
        prefs.edit().putBoolean("debug_mode", true).apply()
        _enabled.value = true
        val dir = File(context.filesDir, "debug-logs")
        if (!dir.exists()) dir.mkdirs()
        currentLogFile = File(dir, "session-${System.currentTimeMillis()}.log")
        try {
            currentLogFile?.createNewFile()
        } catch (_: Exception) {
        }
        startFlushLoop()
        log("DebugLogger", "Debug mode enabled, session started")
    }

    fun disable() {
        log("DebugLogger", "Debug mode disabled")
        prefs.edit().putBoolean("debug_mode", false).apply()
        _enabled.value = false
        flush()
        flushJob?.cancel()
        flushJob = null
        _logLines.value = emptyList()
    }

    fun log(tag: String, message: String) {
        if (!_enabled.value) return
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val time = sdf.format(Date())
            val line = "$time | ${Thread.currentThread().name} | $tag | $message"
            buffer.add(line)
            val current = _logLines.value
            _logLines.value = if (current.size >= maxLogLines) {
                current.takeLast(maxLogLines - 1) + line
            } else {
                current + line
            }
        } catch (_: Exception) {
        }
    }

    suspend fun getPreviousLogContent(): List<String> {
        val file = previousLogFile ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try { file.readLines() } catch (_: Exception) { emptyList() }
        }
    }

    fun getPreviousSessionLogPath(): String? = previousLogFile?.absolutePath

    fun getPreviousSessionLogSize(): Long = previousLogFile?.length() ?: 0L

    fun exportPreviousLog(): File? {
        val file = previousLogFile ?: return null
        if (!file.exists() || !file.canRead()) return null
        val dest = File(context.cacheDir, "debug-export.log")
        return try {
            dest.parentFile?.mkdirs()
            file.copyTo(dest, true)
            dest
        } catch (_: Exception) {
            null
        }
    }

    fun clearPreviousLog() {
        previousLogFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        previousLogFile = null
    }

    fun getPreviousSessionSummary(): String {
        val f = previousLogFile ?: return "无上次调试记录"
        val sizeKB = f.length() / 1024
        return "上次调试日志: ${f.name} (${sizeKB}KB)"
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = flushScope.launch {
            try {
                while (isActive) {
                    delay(3000)
                    flush()
                }
            } catch (_: CancellationException) {
            }
        }
    }

    private fun flush() {
        val file = currentLogFile ?: return
        if (buffer.isEmpty()) return
        try {
            val writer = BufferedWriter(FileWriter(file, true))
            while (true) {
                val line = buffer.poll() ?: break
                writer.write(line)
                writer.newLine()
            }
            writer.flush()
            writer.close()
        } catch (_: IOException) {
        }
    }

    private fun findLatestLogFile(): File? {
        val dir = File(context.filesDir, "debug-logs")
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return null
        return files.maxByOrNull { it.lastModified() }
    }
}
