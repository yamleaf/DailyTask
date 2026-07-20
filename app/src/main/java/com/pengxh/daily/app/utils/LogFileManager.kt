package com.pengxh.daily.app.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors

/**
 * 日志级别。文件行以 [D]/[I]/[W]/[E] 前缀标记，便于诊断导出时按级别过滤。
 */
enum class LogLevel(val char: Char) {
    D('D'), I('I'), W('W'), E('E')
}

object LogFileManager {
    private val kTag = "LogFileManager"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_LOG_FILES = 5 // 最多保留5个日志文件
    private lateinit var currentLogFile: Path
    private val fileLock = ReentrantLock() // 防止并发写入冲突

    @Synchronized
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
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun rotateLogFiles(directory: Path) {
        fileLock.lock()
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory)
            }

            // 获取并按时间戳排序日志文件
            val logFiles = Files.list(directory).use { stream ->
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("app_runtime_log_") && name.endsWith(".txt")
                }.map { path ->
                    val name = path.fileName.toString()
                    val timestampStr = name.removePrefix("app_runtime_log_").removeSuffix(".txt")
                    timestampStr.toLongOrNull()?.let { timestamp -> path to timestamp }
                }.filter { it != null }.map { it }.collect(Collectors.toList())
            }.sortedBy { it.second }.map { it.first }

            // 如果日志数量达到上限，删除最早的
            if (logFiles.size >= MAX_LOG_FILES) {
                Files.deleteIfExists(logFiles.first())
            }

            // 生成新日志文件名
            val newTimestamp = System.currentTimeMillis()
            val newLogFile = directory.resolve("app_runtime_log_$newTimestamp.txt")

            // 重命名当前日志文件
            Files.move(currentLogFile, newLogFile)

            // 创建新的空日志文件
            Files.createFile(currentLogFile)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            fileLock.unlock()
        }
    }

    /** 默认 INFO 级别写入 */
    @Synchronized
    fun writeLog(log: String) = writeLog(LogLevel.I, log)

    /** 按级别写入：映射 Log.d/i/w/e，并在文件行加 [级别] 前缀 */
    @Synchronized
    fun writeLog(level: LogLevel, log: String) {
        if (::currentLogFile.isInitialized) {
            fileLock.lock()
            try {
                when (level) {
                    LogLevel.D -> Log.d(kTag, log)
                    LogLevel.I -> Log.i(kTag, log)
                    LogLevel.W -> Log.w(kTag, log)
                    LogLevel.E -> Log.e(kTag, log)
                }
                val time = System.currentTimeMillis().timestampToCompleteDate()
                val str = "$time [${level.char}] ${log}${System.lineSeparator()}"
                Files.write(currentLogFile, str.toByteArray(), StandardOpenOption.APPEND)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                fileLock.unlock()
            }
        } else {
            throw IllegalStateException("Log file not initialized. Call initLogFile first.")
        }
    }

    /**
     * 读取当前日志文件内容（最多 [maxLines] 行），用于一键诊断导出。
     * [minLevel] 指定最低级别过滤（默认 [LogLevel.D] 表示不过滤）。
     */
    fun readLogContent(maxLines: Int = 500): String = readLogContent(maxLines, LogLevel.D)

    fun readLogContent(maxLines: Int = 500, minLevel: LogLevel): String {
        if (!::currentLogFile.isInitialized) return ""
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
                        else -> LogLevel.I
                    }
                    lvl.ordinal >= minLevel.ordinal
                }
            }
            val start = (filtered.size - maxLines).coerceAtLeast(0)
            filtered.subList(start, filtered.size).joinToString(System.lineSeparator())
        } catch (e: IOException) {
            e.printStackTrace()
            ""
        }
    }
}
