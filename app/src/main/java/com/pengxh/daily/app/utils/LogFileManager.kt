package com.pengxh.daily.app.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pengxh.daily.app.BuildConfig
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors

/**
 * 日志级别。文件行以 [D]/[I]/[W]/[E]/[A] 前缀标记，便于诊断导出时按级别过滤。
 *   D/I —— 调试/普通（Release 不落文件，仅 logcat）
 *   W/E —— 警告/异常（始终落盘）
 *   A   —— 关键业务动作（始终落盘，出问题时快速定位核心状态）
 */
enum class LogLevel(val char: Char) {
    D('D'), I('I'), W('W'), E('E'), A('A')
}

object LogFileManager {
    private val kTag = "LogFileManager"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_LOG_FILES = 5 // 最多保留5个日志文件
    private const val BATCH_SIZE = 256
    private const val DRAIN_INTERVAL_MS = 50L

    /** Release 构建：普通调试日志（D/I）不落文件，仅保留关键动作(A)与异常(W/E) */
    private val inRelease = !BuildConfig.DEBUG

    @Volatile
    private lateinit var currentLogFile: Path
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val writerLock = ReentrantLock()
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var running = true

    // 独立 IO 守护线程：批量把队列中的日志落盘，绝不占用调用线程（尤其是主线程）。
    // 此前 writeLog 在主线程同步 Files.write + 全局锁，无障碍服务/设置页等主线程调用方
    // 会被磁盘 I/O 抢占，造成界面间歇性卡顿。
    private val ioThread = Thread({ drainLoop() }, "LogFileManager-IO").apply {
        isDaemon = true
        start()
    }

    fun initLogFile(context: Context) {
        val documentDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IllegalStateException("External storage directory not available")
        val logDir = documentDir.toPath()
        currentLogFile = logDir.resolve("app_runtime_log.txt")
        try {
            if (!Files.exists(currentLogFile)) {
                Files.createFile(currentLogFile)
            } else if (Files.size(currentLogFile) > MAX_LOG_SIZE) {
                rotateLogFiles(logDir)
            }
        } catch (e: IOException) {
            Log.e(kTag, "日志文件操作异常", e)
        }
    }

    /** 关键业务动作：始终写文件（Release 也写），用于出问题时快速定位核心状态 */
    fun action(log: String) {
        if (!loggingEnabled()) return
        Log.i(kTag, "★ $log")
        enqueue(LogLevel.A, log)
    }

    /** 异常/失败：始终写文件（Release 也写） */
    fun error(log: String) {
        if (!loggingEnabled()) return
        Log.e(kTag, log)
        enqueue(LogLevel.E, log)
    }

    /** 默认 INFO 级别写入（Debug 写文件，Release 仅 logcat） */
    fun writeLog(log: String) = writeLog(LogLevel.I, log)

    /**
     * 异步写入：仅格式化入队后立即返回，不阻塞调用线程。
     * 落盘由 [ioThread] 批量完成（BufferedWriter + 周期 flush）。
     *
     * Release 策略：普通调试日志（D/I）不落文件，只保留关键动作 [action] 与
     * 异常 [error]，避免正常流程污染诊断文件、徒增 IO；Debug 包保留完整日志便于排查。
     */
    fun writeLog(level: LogLevel, log: String) {
        if (!loggingEnabled()) return
        when (level) {
            LogLevel.D -> Log.d(kTag, log)
            LogLevel.I -> Log.i(kTag, log)
            LogLevel.W -> Log.w(kTag, log)
            LogLevel.E -> Log.e(kTag, log)
            LogLevel.A -> Log.i(kTag, "★ $log")
        }
        // Release：仅 D/I 不落文件；W/E/A 始终落盘
        if (inRelease && level <= LogLevel.I) return
        enqueue(level, log)
    }

    /** 日志总开关：默认开启。关闭后 action/error/writeLog 一律不记录（含文件与 logcat 镜像） */
    private fun loggingEnabled(): Boolean = SaveKeyValues.loadBoolean(Constant.LOG_ENABLED_KEY, true)

    private fun enqueue(level: LogLevel, log: String) {
        if (!::currentLogFile.isInitialized) return
        val time = System.currentTimeMillis().timestampToCompleteDate()
        val str = "$time [${level.char}] ${log}${System.lineSeparator()}"
        logQueue.add(str)
    }

    private fun drainLoop() {
        while (running || logQueue.isNotEmpty()) {
            if (logQueue.isEmpty()) {
                try {
                    Thread.sleep(DRAIN_INTERVAL_MS)
                } catch (_: InterruptedException) {
                }
                continue
            }
            val batch = ArrayList<String>(BATCH_SIZE)
            while (batch.size < BATCH_SIZE) {
                val s = logQueue.poll() ?: break
                batch.add(s)
            }
            if (batch.isNotEmpty()) writeBatch(batch)
        }
        flushWriter()
    }

    private fun writeBatch(batch: List<String>) {
        writerLock.lock()
        try {
            ensureWriter()
            val w = writer ?: return
            for (s in batch) w.write(s)
            w.flush()
            if (Files.size(currentLogFile) > MAX_LOG_SIZE) {
                rotateLogFiles(currentLogFile.parent)
            }
        } catch (e: IOException) {
            Log.e(kTag, "日志写入异常", e)
        } finally {
            writerLock.unlock()
        }
    }

    private fun ensureWriter() {
        if (writer == null) {
            writer = Files.newBufferedWriter(
                currentLogFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    private fun rotateLogFiles(directory: Path) {
        // 仅在持有 writerLock 时调用
        runCatching { writer?.close() }
        writer = null
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory)
            }

            val logFiles = Files.list(directory).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("app_runtime_log_") && name.endsWith(".txt")
                }.map { path ->
                    val name = path.fileName.toString()
                    val timestampStr = name.removePrefix("app_runtime_log_").removeSuffix(".txt")
                    timestampStr.toLongOrNull()?.let { timestamp -> path to timestamp }
                }.filter { it != null }.map { it!! }.collect(Collectors.toList())
            }.sortedBy { it.second }.map { it.first }

            // 如果日志数量达到上限，删除最早的
            if (logFiles.size >= MAX_LOG_FILES) {
                Files.deleteIfExists(logFiles.first())
            }

            val newTimestamp = System.currentTimeMillis()
            val newLogFile = directory.resolve("app_runtime_log_$newTimestamp.txt")

            // 重命名当前日志文件
            Files.move(currentLogFile, newLogFile)

            Files.createFile(currentLogFile)
        } catch (e: IOException) {
            Log.e(kTag, "日志文件操作异常", e)
        }
    }

    /**
     * 读取前先把内存中未落盘的行刷到磁盘，保证诊断内容最新。
     * 调用方应位于后台线程（诊断导出已在 IO 调度器执行）。
     */
    fun readLogContent(maxLines: Int = 500): String = readLogContent(maxLines, LogLevel.D)

    fun readLogContent(maxLines: Int = 500, minLevel: LogLevel): String {
        if (!::currentLogFile.isInitialized) return ""
        flushPendingToDisk()
        return try {
            val lines = Files.readAllLines(currentLogFile)
            val filtered = if (minLevel == LogLevel.D) {
                lines
            } else {
                lines.filter { line ->
                    val open = line.indexOf('[')
                    val close = line.indexOf(']', open + 1)
                    val c = if (open >= 0 && close > open + 1) line[open + 1] else null
                    val lvl = when (c) {
                        'D' -> LogLevel.D
                        'I' -> LogLevel.I
                        'W' -> LogLevel.W
                        'E' -> LogLevel.E
                        'A' -> LogLevel.A
                        else -> LogLevel.I
                    }
                    lvl.ordinal >= minLevel.ordinal
                }
            }
            val start = (filtered.size - maxLines).coerceAtLeast(0)
            filtered.subList(start, filtered.size).joinToString(System.lineSeparator())
        } catch (e: IOException) {
            Log.e(kTag, "日志文件操作异常", e)
            ""
        }
    }

    /** 把队列中尚未落盘的行立即写出（供读取前调用） */
    private fun flushPendingToDisk() {
        val batch = ArrayList<String>(logQueue.size.coerceAtLeast(1))
        while (true) {
            val s = logQueue.poll() ?: break
            batch.add(s)
        }
        if (batch.isNotEmpty()) writeBatch(batch)
    }

    private fun flushWriter() {
        writerLock.lock()
        try {
            runCatching { writer?.flush() }
        } finally {
            writerLock.unlock()
        }
    }

    /** 进程退出前调用：停止 IO 线程并尽量把剩余日志落盘 */
    fun shutdown() {
        running = false
        try {
            ioThread.join(2000)
        } catch (_: InterruptedException) {
        }
    }
}
