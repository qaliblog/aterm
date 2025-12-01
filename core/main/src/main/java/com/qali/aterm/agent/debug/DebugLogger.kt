package com.qali.aterm.agent.debug

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Comprehensive debug logging system for agent workflow
 * Provides structured logging with log levels, rotation, and persistence
 * All API calls remain non-streaming
 */
object DebugLogger {
    
    enum class LogLevel(val priority: Int) {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3)
    }
    
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val metadata: Map<String, Any>? = null,
        val exception: Throwable? = null
    )
    
    private var currentLogLevel: LogLevel = LogLevel.INFO
    private var logToFile: Boolean = false
    private var logDirectory: File? = null
    private var maxLogFileSize: Long = 10 * 1024 * 1024 // 10 MB
    private var maxLogFiles: Int = 5
    
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val isProcessing = AtomicBoolean(false)
    private var currentLogFile: File? = null
    private var currentLogFileWriter: PrintWriter? = null
    
    /**
     * Initialize logger with configuration
     */
    fun initialize(
        logLevel: LogLevel = LogLevel.INFO,
        enableFileLogging: Boolean = false,
        logDir: File? = null,
        maxFileSize: Long = 10 * 1024 * 1024,
        maxFiles: Int = 5
    ) {
        currentLogLevel = logLevel
        logToFile = enableFileLogging
        logDirectory = logDir
        maxLogFileSize = maxFileSize
        maxLogFiles = maxFiles
        
        if (logToFile && logDirectory != null) {
            ensureLogDirectory()
            rotateLogsIfNeeded()
            openLogFile()
        }
        
        d("DebugLogger", "Initialized with level=$logLevel, fileLogging=$enableFileLogging")
    }
    
    /**
     * Set log level dynamically
     */
    fun setLogLevel(level: LogLevel) {
        currentLogLevel = level
        i("DebugLogger", "Log level changed to $level")
    }
    
    /**
     * Get current log level
     */
    fun getLogLevel(): LogLevel = currentLogLevel
    
    /**
     * Enable/disable file logging
     */
    fun setFileLogging(enabled: Boolean, logDir: File? = null) {
        logToFile = enabled
        if (enabled) {
            logDirectory = logDir ?: logDirectory
            if (logDirectory != null) {
                ensureLogDirectory()
                openLogFile()
            }
        } else {
            closeLogFile()
        }
        i("DebugLogger", "File logging ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Log debug message
     */
    fun d(tag: String, message: String, metadata: Map<String, Any>? = null) {
        log(LogLevel.DEBUG, tag, message, metadata, null)
    }
    
    /**
     * Log info message
     */
    fun i(tag: String, message: String, metadata: Map<String, Any>? = null) {
        log(LogLevel.INFO, tag, message, metadata, null)
    }
    
    /**
     * Log warning message
     */
    fun w(tag: String, message: String, metadata: Map<String, Any>? = null, exception: Throwable? = null) {
        log(LogLevel.WARN, tag, message, metadata, exception)
    }
    
    /**
     * Log error message
     */
    fun e(tag: String, message: String, metadata: Map<String, Any>? = null, exception: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, metadata, exception)
    }
    
    /**
     * Log API call (with sanitized request/response)
     */
    fun logApiCall(
        tag: String,
        provider: String,
        model: String,
        requestSize: Int,
        responseSize: Int,
        duration: Long,
        success: Boolean,
        error: String? = null,
        sanitizedRequest: String? = null,
        sanitizedResponse: String? = null
    ) {
        val metadata = mapOf(
            "type" to "api_call",
            "provider" to provider,
            "model" to model,
            "request_size" to requestSize,
            "response_size" to responseSize,
            "duration_ms" to duration,
            "success" to success,
            "streaming" to false, // Always non-streaming
            "error" to (error ?: ""),
            "sanitized_request" to (sanitizedRequest ?: ""),
            "sanitized_response" to (sanitizedResponse ?: "")
        )
        
        val message = "API Call: $provider/$model (${duration}ms, ${if (success) "success" else "failed"})"
        
        if (success) {
            i(tag, message, metadata)
        } else {
            e(tag, message, metadata)
        }
    }
    
    /**
     * Log tool execution
     */
    fun logToolExecution(
        tag: String,
        toolName: String,
        params: Map<String, Any>?,
        duration: Long,
        success: Boolean,
        resultSize: Int? = null,
        error: String? = null
    ) {
        val metadata = mapOf(
            "type" to "tool_execution",
            "tool" to toolName,
            "params" to (params?.toString() ?: ""),
            "duration_ms" to duration,
            "success" to success,
            "result_size" to (resultSize ?: 0),
            "error" to (error ?: "")
        )
        
        val message = "Tool: $toolName (${duration}ms, ${if (success) "success" else "failed"})"
        
        if (success) {
            i(tag, message, metadata)
        } else {
            e(tag, message, metadata)
        }
    }
    
    /**
     * Log script execution
     */
    fun logScriptExecution(
        tag: String,
        scriptPath: String?,
        turnIndex: Int,
        totalTurns: Int,
        duration: Long,
        success: Boolean
    ) {
        val metadata = mapOf(
            "type" to "script_execution",
            "script_path" to (scriptPath ?: "inline"),
            "turn_index" to turnIndex,
            "total_turns" to totalTurns,
            "duration_ms" to duration,
            "success" to success
        )
        
        val message = "Script: turn $turnIndex/$totalTurns (${duration}ms, ${if (success) "success" else "failed"})"
        
        if (success) {
            i(tag, message, metadata)
        } else {
            e(tag, message, metadata)
        }
    }
    
    /**
     * Log context window usage
     */
    fun logContextWindow(
        tag: String,
        estimatedTokens: Int,
        maxTokens: Int,
        messagesCount: Int,
        compressionRatio: Double? = null
    ) {
        val metadata = mapOf(
            "type" to "context_window",
            "estimated_tokens" to estimatedTokens,
            "max_tokens" to maxTokens,
            "usage_percent" to ((estimatedTokens.toDouble() / maxTokens) * 100),
            "messages_count" to messagesCount,
            "compression_ratio" to (compressionRatio ?: 1.0)
        )
        
        val usagePercent = (estimatedTokens.toDouble() / maxTokens) * 100
        val message = "Context: ${estimatedTokens}/${maxTokens} tokens (${String.format("%.1f", usagePercent)}%)"
        
        if (usagePercent > 90) {
            w(tag, message, metadata)
        } else {
            d(tag, message, metadata)
        }
    }
    
    /**
     * Core logging function
     */
    private fun log(
        level: LogLevel,
        tag: String,
        message: String,
        metadata: Map<String, Any>?,
        exception: Throwable?
    ) {
        // Check if we should log this level
        if (level.priority < currentLogLevel.priority) {
            return
        }
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            metadata = metadata,
            exception = exception
        )
        
        // Log to Android Log
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, formatMessage(entry))
            LogLevel.INFO -> Log.i(tag, formatMessage(entry))
            LogLevel.WARN -> Log.w(tag, formatMessage(entry), exception)
            LogLevel.ERROR -> Log.e(tag, formatMessage(entry), exception)
        }
        
        // Add to queue for file logging
        if (logToFile) {
            logQueue.offer(entry)
            processLogQueue()
        }
    }
    
    /**
     * Format log message for display
     */
    private fun formatMessage(entry: LogEntry): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
        val metadataStr = if (entry.metadata != null && entry.metadata.isNotEmpty()) {
            " | ${entry.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } else {
            ""
        }
        return "[$time] ${entry.level.name} | ${entry.message}$metadataStr"
    }
    
    /**
     * Process log queue and write to file
     */
    private fun processLogQueue() {
        if (isProcessing.compareAndSet(false, true)) {
            try {
                val writer = currentLogFileWriter
                if (writer != null) {
                    var processed = 0
                    while (processed < 100 && logQueue.isNotEmpty()) { // Process in batches
                        val entry = logQueue.poll() ?: break
                        writer.println(formatLogEntry(entry))
                        processed++
                    }
                    writer.flush()
                    
                    // Check if we need to rotate
                    checkLogFileSize()
                }
            } catch (e: Exception) {
                Log.e("DebugLogger", "Error processing log queue", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }
    
    /**
     * Format log entry for file output
     */
    private fun formatLogEntry(entry: LogEntry): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
        val metadataStr = if (entry.metadata != null && entry.metadata.isNotEmpty()) {
            "\n  Metadata: ${entry.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
        } else {
            ""
        }
        val exceptionStr = if (entry.exception != null) {
            "\n  Exception: ${entry.exception.message}\n${entry.exception.stackTrace.joinToString("\n")}"
        } else {
            ""
        }
        return "[$time] [${entry.level.name}] [${entry.tag}] ${entry.message}$metadataStr$exceptionStr"
    }
    
    /**
     * Ensure log directory exists
     */
    private fun ensureLogDirectory() {
        logDirectory?.let { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }
    
    /**
     * Open log file
     */
    private fun openLogFile() {
        logDirectory?.let { dir ->
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateStr = dateFormat.format(Date())
            val logFile = File(dir, "aterm-agent-${dateStr}.log")
            
            try {
                currentLogFile = logFile
                currentLogFileWriter = PrintWriter(FileWriter(logFile, true), true)
                i("DebugLogger", "Opened log file: ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("DebugLogger", "Failed to open log file", e)
            }
        }
    }
    
    /**
     * Close log file
     */
    private fun closeLogFile() {
        currentLogFileWriter?.close()
        currentLogFileWriter = null
        currentLogFile = null
    }
    
    /**
     * Check log file size and rotate if needed
     */
    private fun checkLogFileSize() {
        currentLogFile?.let { file ->
            if (file.exists() && file.length() > maxLogFileSize) {
                rotateLogFile()
            }
        }
    }
    
    /**
     * Rotate log file
     */
    private fun rotateLogFile() {
        currentLogFile?.let { file ->
            closeLogFile()
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val rotatedFile = File(file.parent, "aterm-agent-${timestamp}.log")
            
            if (file.exists()) {
                file.renameTo(rotatedFile)
            }
            
            rotateLogsIfNeeded()
            openLogFile()
        }
    }
    
    /**
     * Rotate old log files if we exceed max files
     */
    private fun rotateLogsIfNeeded() {
        logDirectory?.let { dir ->
            val logFiles = dir.listFiles { file ->
                file.name.startsWith("aterm-agent-") && file.name.endsWith(".log")
            }?.sortedBy { it.lastModified() } ?: emptyList()
            
            if (logFiles.size >= maxLogFiles) {
                val filesToDelete = logFiles.take(logFiles.size - maxLogFiles + 1)
                filesToDelete.forEach { file ->
                    file.delete()
                    d("DebugLogger", "Deleted old log file: ${file.name}")
                }
            }
        }
    }
    
    /**
     * Flush all pending logs
     */
    fun flush() {
        while (logQueue.isNotEmpty()) {
            processLogQueue()
        }
        currentLogFileWriter?.flush()
    }
    
    /**
     * Get recent log entries (from memory queue, not file)
     */
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return logQueue.toList().takeLast(count)
    }
    
    /**
     * Clear log queue
     */
    fun clearQueue() {
        logQueue.clear()
    }
    
    /**
     * Shutdown logger
     */
    fun shutdown() {
        flush()
        closeLogFile()
        clearQueue()
        i("DebugLogger", "Logger shut down")
    }
}
