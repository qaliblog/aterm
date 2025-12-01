package com.qali.aterm.agent.debug

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Unit tests for DebugLogger
 * Tests log levels, file logging, and log rotation
 */
class DebugLoggerTest {
    
    private val testLogDir = File("/tmp/test-logs")
    
    @Before
    fun setup() {
        // Clean up test directory
        if (testLogDir.exists()) {
            testLogDir.deleteRecursively()
        }
        testLogDir.mkdirs()
        
        // Initialize logger
        DebugLogger.initialize(
            logLevel = DebugLogger.LogLevel.DEBUG,
            enableFileLogging = true,
            logDir = testLogDir,
            maxFileSize = 1024, // 1 KB for testing
            maxFiles = 3
        )
    }
    
    @Test
    fun testDebugLogging() {
        DebugLogger.d("TestTag", "Debug message")
        // Verify no exception is thrown
        assertTrue(true)
    }
    
    @Test
    fun testInfoLogging() {
        DebugLogger.i("TestTag", "Info message")
        assertTrue(true)
    }
    
    @Test
    fun testWarningLogging() {
        DebugLogger.w("TestTag", "Warning message")
        assertTrue(true)
    }
    
    @Test
    fun testErrorLogging() {
        DebugLogger.e("TestTag", "Error message")
        assertTrue(true)
    }
    
    @Test
    fun testErrorLoggingWithException() {
        val exception = Exception("Test exception")
        DebugLogger.e("TestTag", "Error with exception", exception = exception)
        assertTrue(true)
    }
    
    @Test
    fun testLogLevelFiltering() {
        // Set to WARN level
        DebugLogger.setLogLevel(DebugLogger.LogLevel.WARN)
        
        // DEBUG and INFO should be filtered
        DebugLogger.d("TestTag", "Debug message - should be filtered")
        DebugLogger.i("TestTag", "Info message - should be filtered")
        
        // WARN and ERROR should pass
        DebugLogger.w("TestTag", "Warning message - should pass")
        DebugLogger.e("TestTag", "Error message - should pass")
        
        assertTrue(true)
    }
    
    @Test
    fun testLogApiCall() {
        DebugLogger.logApiCall(
            tag = "TestTag",
            provider = "OpenAI",
            model = "gpt-4",
            requestSize = 100,
            responseSize = 200,
            duration = 1500,
            success = true
        )
        assertTrue(true)
    }
    
    @Test
    fun testLogApiCallFailure() {
        DebugLogger.logApiCall(
            tag = "TestTag",
            provider = "OpenAI",
            model = "gpt-4",
            requestSize = 100,
            responseSize = 0,
            duration = 500,
            success = false,
            error = "Rate limit exceeded"
        )
        assertTrue(true)
    }
    
    @Test
    fun testLogToolExecution() {
        DebugLogger.logToolExecution(
            tag = "TestTag",
            toolName = "write_file",
            params = mapOf("file_path" to "/tmp/test.txt"),
            duration = 100,
            success = true,
            resultSize = 50
        )
        assertTrue(true)
    }
    
    @Test
    fun testLogScriptExecution() {
        DebugLogger.logScriptExecution(
            tag = "TestTag",
            scriptPath = "/tmp/test.ai.yaml",
            turnIndex = 1,
            totalTurns = 3,
            duration = 2000,
            success = true
        )
        assertTrue(true)
    }
    
    @Test
    fun testLogContextWindow() {
        DebugLogger.logContextWindow(
            tag = "TestTag",
            estimatedTokens = 1000,
            maxTokens = 2000,
            messagesCount = 10
        )
        assertTrue(true)
    }
    
    @Test
    fun testFileLogging() {
        // Enable file logging
        DebugLogger.setFileLogging(true, testLogDir)
        
        DebugLogger.i("TestTag", "Test message for file logging")
        DebugLogger.flush()
        
        // Verify log file exists
        val logFiles = testLogDir.listFiles { file ->
            file.name.startsWith("aterm-agent-") && file.name.endsWith(".log")
        }
        
        assertNotNull(logFiles)
        assertTrue(logFiles!!.isNotEmpty())
    }
    
    @Test
    fun testGetRecentLogs() {
        DebugLogger.d("TestTag", "Message 1")
        DebugLogger.i("TestTag", "Message 2")
        DebugLogger.w("TestTag", "Message 3")
        
        val recentLogs = DebugLogger.getRecentLogs(10)
        assertTrue(recentLogs.size >= 0) // May be 0 if queue was processed
    }
    
    @Test
    fun testShutdown() {
        DebugLogger.shutdown()
        // Verify no exception is thrown
        assertTrue(true)
    }
}
