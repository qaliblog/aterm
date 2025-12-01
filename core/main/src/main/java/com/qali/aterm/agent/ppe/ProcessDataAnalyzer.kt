package com.qali.aterm.agent.ppe

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.qali.aterm.agent.HistoryPersistenceService
import com.qali.aterm.ui.screens.agent.AgentMessage
import com.rk.libcommons.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * AI Process Data Analyzer
 * Analyzes agent execution logs every N tasks and generates debug suggestions
 */
class ProcessDataAnalyzer(
    private val workspaceRoot: String,
    private val apiClient: PpeApiClient,
    private val tasksPerAnalysis: Int = 1
) {
    private val prefs: SharedPreferences
        get() = application!!.getSharedPreferences("process_analyzer", Context.MODE_PRIVATE)
    
    private val taskCountKey = "task_count"
    private val lastAnalysisTaskKey = "last_analysis_task"
    
    /**
     * Track a task execution and trigger analysis if needed
     * If messages are not provided, will load from history persistence
     */
    suspend fun trackTask(
        sessionId: String,
        messages: List<AgentMessage>? = null,
        onChunk: (String) -> Unit = {}
    ) {
        val currentTaskCount = getTaskCount()
        val newTaskCount = currentTaskCount + 1
        
        saveTaskCount(newTaskCount)
        
        Log.d("ProcessDataAnalyzer", "Task tracked: $newTaskCount/$tasksPerAnalysis")
        
        // Check if we need to perform analysis
        if (newTaskCount >= tasksPerAnalysis && shouldPerformAnalysis()) {
            onChunk("\n🔍 Process Data Analysis triggered (after $newTaskCount tasks)...\n")
            // Use provided messages or load from history
            val messagesToAnalyze = messages ?: HistoryPersistenceService.loadHistory(sessionId)
            performAnalysis(sessionId, messagesToAnalyze, onChunk)
            saveLastAnalysisTask(newTaskCount)
        }
    }
    
    /**
     * Perform analysis on agent logs
     */
    private suspend fun performAnalysis(
        sessionId: String,
        messages: List<AgentMessage>,
        onChunk: (String) -> Unit
    ) {
        try {
            withContext(Dispatchers.IO) {
                onChunk("📊 Collecting agent execution logs...\n")
                
                // Collect recent messages (last N tasks worth)
                val recentMessages = messages.takeLast(100) // Get last 100 messages
                
                // Build log summary
                val logSummary = buildLogSummary(recentMessages, sessionId)
                
                onChunk("🤖 Sending logs to LLM for analysis...\n")
                
                // Send to LLM for analysis
                val analysisResult = analyzeWithLLM(logSummary)
                
                if (analysisResult != null) {
                    // Save to numbered file
                    val fileNumber = getNextFileNumber()
                    val fileName = "askForDebug${String.format("%02d", fileNumber)}.txt"
                    val filePath = File(workspaceRoot, fileName)
                    
                    filePath.parentFile?.mkdirs()
                    filePath.writeText(analysisResult)
                    
                    onChunk("✅ Analysis saved to: $fileName\n")
                    Log.d("ProcessDataAnalyzer", "Analysis saved to: ${filePath.absolutePath}")
                } else {
                    onChunk("⚠️ Analysis failed: Could not get response from LLM\n")
                    Log.w("ProcessDataAnalyzer", "Analysis failed: No response from LLM")
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessDataAnalyzer", "Error during analysis", e)
            onChunk("❌ Analysis error: ${e.message}\n")
        }
    }
    
    /**
     * Build a summary of agent execution logs
     */
    private fun buildLogSummary(messages: List<AgentMessage>, sessionId: String): String {
        val summary = StringBuilder()
        
        summary.appendLine("=== Agent Execution Log Analysis ===")
        summary.appendLine("Session ID: $sessionId")
        summary.appendLine("Analysis Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        summary.appendLine("Total Messages: ${messages.size}")
        summary.appendLine()
        
        // Categorize messages
        val userMessages = messages.filter { it.isUser }
        val aiMessages = messages.filter { !it.isUser }
        val toolCalls = messages.filter { it.text.contains("🔧 Calling tool") || it.text.contains("Tool '") }
        val errors = messages.filter { it.text.contains("❌") || it.text.contains("Error") || it.text.contains("error") }
        val fileChanges = messages.filter { it.fileDiff != null }
        
        summary.appendLine("=== Message Statistics ===")
        summary.appendLine("User Messages: ${userMessages.size}")
        summary.appendLine("AI Messages: ${aiMessages.size}")
        summary.appendLine("Tool Calls: ${toolCalls.size}")
        summary.appendLine("Errors: ${errors.size}")
        summary.appendLine("File Changes: ${fileChanges.size}")
        summary.appendLine()
        
        // Recent activity summary
        summary.appendLine("=== Recent Activity (Last 20 Messages) ===")
        messages.takeLast(20).forEachIndexed { index, msg ->
            val prefix = if (msg.isUser) "[User]" else "[AI]"
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))
            val preview = msg.text.take(100).replace("\n", " ")
            summary.appendLine("$index. $prefix [$timestamp] $preview${if (msg.text.length > 100) "..." else ""}")
            if (msg.fileDiff != null) {
                summary.appendLine("   └─ File: ${msg.fileDiff.filePath} (${if (msg.fileDiff.isNewFile) "NEW" else "MODIFIED"})")
            }
        }
        summary.appendLine()
        
        // Error analysis
        if (errors.isNotEmpty()) {
            summary.appendLine("=== Error Analysis ===")
            errors.takeLast(10).forEach { error ->
                summary.appendLine("- ${error.text.take(200)}")
            }
            summary.appendLine()
        }
        
        // Tool usage patterns
        if (toolCalls.isNotEmpty()) {
            summary.appendLine("=== Tool Usage Patterns ===")
            val toolCounts = toolCalls.mapNotNull { msg ->
                val toolMatch = Regex("""Tool ['"]([^'"]+)['"]""").find(msg.text)
                toolMatch?.groupValues?.get(1)
            }.groupingBy { it }.eachCount()
            
            toolCounts.toList().sortedByDescending { it.second }.forEach { (tool, count) ->
                summary.appendLine("$tool: $count")
            }
            summary.appendLine()
        }
        
        // File change patterns
        if (fileChanges.isNotEmpty()) {
            summary.appendLine("=== File Change Patterns ===")
            fileChanges.takeLast(10).forEach { msg ->
                msg.fileDiff?.let { diff ->
                    summary.appendLine("${if (diff.isNewFile) "NEW" else "MODIFIED"}: ${diff.filePath}")
                }
            }
            summary.appendLine()
        }
        
        // Full message log (last 50 messages for context)
        summary.appendLine("=== Full Message Log (Last 50 Messages) ===")
        messages.takeLast(50).forEach { msg ->
            val prefix = if (msg.isUser) "[User]" else "[AI]"
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))
            summary.appendLine("$prefix [$timestamp]")
            summary.appendLine(msg.text)
            if (msg.fileDiff != null) {
                summary.appendLine("File Diff: ${msg.fileDiff.filePath}")
            }
            summary.appendLine("---")
        }
        
        return summary.toString()
    }
    
    /**
     * Analyze logs with LLM
     */
    private suspend fun analyzeWithLLM(logSummary: String): String? {
        return try {
            val prompt = """
Analyze the following agent execution logs and identify issues, patterns, and areas for improvement.

Your task is to:
1. Identify recurring problems or errors
2. Detect inefficient patterns in tool usage
3. Find issues with file operations or code generation
4. Suggest specific improvements to the agent flow to prevent these issues

Provide your analysis in a clear, actionable format that can be used to improve the agent's behavior.

=== Agent Execution Logs ===

$logSummary

=== Analysis Request ===

Please provide:
1. **Issues Found**: List any recurring problems, errors, or inefficiencies
2. **Root Causes**: Explain why these issues are occurring
3. **Recommended Changes**: Specific, actionable changes to the agent flow to prevent these issues
4. **Priority**: Mark each recommendation as HIGH, MEDIUM, or LOW priority

Format your response as a clear, structured analysis that can be saved and referenced later.

Analysis:
""".trimIndent()
            
            val messages = listOf(
                com.qali.aterm.agent.core.Content(
                    role = "user",
                    parts = listOf(com.qali.aterm.agent.core.Part.TextPart(text = prompt))
                )
            )
            
            val result = apiClient.callApi(
                messages = messages,
                model = null,
                temperature = 0.7, // Slightly creative for analysis
                topP = null,
                topK = null,
                tools = null
            )
            
            result.getOrNull()?.text
        } catch (e: Exception) {
            Log.e("ProcessDataAnalyzer", "Error calling LLM for analysis", e)
            null
        }
    }
    
    /**
     * Get next file number for askForDebug files
     */
    private fun getNextFileNumber(): Int {
        val debugDir = File(workspaceRoot)
        if (!debugDir.exists()) {
            return 1
        }
        
        val existingFiles = debugDir.listFiles { _, name ->
            name.matches(Regex("""askForDebug\d{2}\.txt"""))
        } ?: return 1
        
        if (existingFiles.isEmpty()) {
            return 1
        }
        
        val numbers = existingFiles.mapNotNull { file ->
            val match = Regex("""askForDebug(\d{2})\.txt""").find(file.name)
            match?.groupValues?.get(1)?.toIntOrNull()
        }
        
        return (numbers.maxOrNull() ?: 0) + 1
    }
    
    /**
     * Get current task count
     */
    private fun getTaskCount(): Int {
        return prefs.getInt(taskCountKey, 0)
    }
    
    /**
     * Save task count
     */
    private fun saveTaskCount(count: Int) {
        prefs.edit().putInt(taskCountKey, count).apply()
    }
    
    /**
     * Check if analysis should be performed
     * Only analyze if we haven't analyzed for this task count yet
     */
    private fun shouldPerformAnalysis(): Boolean {
        val lastAnalysisTask = prefs.getInt(lastAnalysisTaskKey, 0)
        val currentTaskCount = getTaskCount()
        
        // Only analyze if we've completed a full cycle since last analysis
        return currentTaskCount >= tasksPerAnalysis && 
               (currentTaskCount - lastAnalysisTask) >= tasksPerAnalysis
    }
    
    /**
     * Save last analysis task number
     */
    private fun saveLastAnalysisTask(taskNumber: Int) {
        prefs.edit().putInt(lastAnalysisTaskKey, taskNumber).apply()
    }
    
    /**
     * Reset task counter (useful for testing or manual reset)
     */
    fun resetTaskCount() {
        prefs.edit().remove(taskCountKey).remove(lastAnalysisTaskKey).apply()
        Log.d("ProcessDataAnalyzer", "Task count reset")
    }
    
    /**
     * Get current task count (for display/debugging)
     */
    fun getCurrentTaskCount(): Int {
        return getTaskCount()
    }
}
