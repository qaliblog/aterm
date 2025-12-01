package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Command history tool
 * Manages and replays command history for debugging and automation
 */
data class CommandHistoryToolParams(
    val action: String, // "view", "search", "replay", "save", "clear"
    val query: String? = null, // Search query or command index/range for replay
    val outputPath: String? = null, // Path to save script (for save action)
    val limit: Int? = null // Limit number of results (for view/search)
)

data class CommandHistoryEntry(
    val id: String,
    val command: String,
    val timestamp: Long,
    val exitCode: Int?,
    val output: String?,
    val workingDir: String?,
    val success: Boolean?
)

object CommandHistoryManager {
    private val history = ConcurrentLinkedQueue<CommandHistoryEntry>()
    private val maxHistorySize = 1000
    
    /**
     * Add command to history
     */
    fun addCommand(
        command: String,
        exitCode: Int? = null,
        output: String? = null,
        workingDir: String? = null
    ): CommandHistoryEntry {
        val entry = CommandHistoryEntry(
            id = "cmd-${System.currentTimeMillis()}-${history.size}",
            command = command,
            timestamp = System.currentTimeMillis(),
            exitCode = exitCode,
            output = output,
            workingDir = workingDir,
            success = exitCode == 0
        )
        
        history.add(entry)
        
        // Limit history size
        while (history.size > maxHistorySize) {
            history.poll()
        }
        
        return entry
    }
    
    /**
     * Get all history entries
     */
    fun getAllHistory(): List<CommandHistoryEntry> {
        return history.toList()
    }
    
    /**
     * Get recent history
     */
    fun getRecentHistory(limit: Int = 50): List<CommandHistoryEntry> {
        return history.takeLast(limit)
    }
    
    /**
     * Search history
     */
    fun searchHistory(query: String, limit: Int = 50): List<CommandHistoryEntry> {
        val lowerQuery = query.lowercase()
        return history.filter { entry ->
            entry.command.lowercase().contains(lowerQuery) ||
            entry.output?.lowercase()?.contains(lowerQuery) == true
        }.takeLast(limit)
    }
    
    /**
     * Get command by ID or index
     */
    fun getCommand(idOrIndex: String): CommandHistoryEntry? {
        // Try as index first
        val index = idOrIndex.toIntOrNull()
        if (index != null && index >= 0 && index < history.size) {
            return history.elementAt(index)
        }
        
        // Try as ID
        return history.find { it.id == idOrIndex }
    }
    
    /**
     * Clear history
     */
    fun clearHistory() {
        history.clear()
    }
    
    /**
     * Get history statistics
     */
    fun getStatistics(): HistoryStatistics {
        val entries = history.toList()
        val successful = entries.count { it.success == true }
        val failed = entries.count { it.success == false }
        val totalCommands = entries.size
        val uniqueCommands = entries.map { it.command }.distinct().size
        
        return HistoryStatistics(
            totalCommands = totalCommands,
            successfulCommands = successful,
            failedCommands = failed,
            uniqueCommands = uniqueCommands,
            successRate = if (totalCommands > 0) (successful.toDouble() / totalCommands) * 100.0 else 0.0
        )
    }
    
    data class HistoryStatistics(
        val totalCommands: Int,
        val successfulCommands: Int,
        val failedCommands: Int,
        val uniqueCommands: Int,
        val successRate: Double
    )
}

class CommandHistoryToolInvocation(
    toolParams: CommandHistoryToolParams,
    private val workspaceRoot: String
) : ToolInvocation<CommandHistoryToolParams, ToolResult> {
    
    override val params: CommandHistoryToolParams = toolParams
    
    override fun getDescription(): String {
        return when (params.action) {
            "view" -> "Viewing command history"
            "search" -> "Searching command history: ${params.query}"
            "replay" -> "Replaying command: ${params.query}"
            "save" -> "Saving command sequence to script"
            "clear" -> "Clearing command history"
            else -> "Managing command history"
        }
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult = withContext(Dispatchers.IO) {
        if (signal?.isAborted() == true) {
            return@withContext ToolResult(
                llmContent = "Command history operation cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            val result = when (params.action) {
                "view" -> viewHistory(params.limit)
                "search" -> searchHistory(params.query, params.limit)
                "replay" -> replayCommand(params.query)
                "save" -> saveScript(params.query, params.outputPath)
                "clear" -> clearHistory()
                else -> {
                    return@withContext ToolResult(
                        llmContent = "Unknown action: ${params.action}",
                        returnDisplay = "Error: Unknown action",
                        error = ToolError(
                            message = "Unknown action: ${params.action}",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
            }
            
            DebugLogger.i("CommandHistoryTool", "History operation completed", mapOf(
                "action" to params.action,
                "query" to (params.query ?: "none")
            ))
            
            result
        } catch (e: Exception) {
            DebugLogger.e("CommandHistoryTool", "Error managing command history", exception = e)
            ToolResult(
                llmContent = "Error managing command history: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun viewHistory(limit: Int?): ToolResult {
        val history = if (limit != null) {
            CommandHistoryManager.getRecentHistory(limit)
        } else {
            CommandHistoryManager.getAllHistory()
        }
        
        val stats = CommandHistoryManager.getStatistics()
        
        val output = buildString {
            appendLine("# Command History")
            appendLine()
            appendLine("## Statistics")
            appendLine()
            appendLine("- **Total Commands:** ${stats.totalCommands}")
            appendLine("- **Successful:** ${stats.successfulCommands}")
            appendLine("- **Failed:** ${stats.failedCommands}")
            appendLine("- **Unique Commands:** ${stats.uniqueCommands}")
            appendLine("- **Success Rate:** ${String.format("%.1f", stats.successRate)}%")
            appendLine()
            appendLine("## Recent Commands")
            appendLine()
            if (history.isEmpty()) {
                appendLine("No command history found.")
            } else {
                appendLine("| # | Command | Time | Exit Code | Success |")
                appendLine("|---|---------|------|-----------|---------|")
                history.reversed().forEachIndexed { index, entry ->
                    val time = formatTimestamp(entry.timestamp)
                    val exitCodeStr = entry.exitCode?.toString() ?: "N/A"
                    val successStr = when (entry.success) {
                        true -> "✅"
                        false -> "❌"
                        null -> "?"
                    }
                    appendLine("| ${history.size - index} | `${entry.command.take(50)}` | $time | $exitCodeStr | $successStr |")
                }
                appendLine()
                
                appendLine("## Command Details")
                appendLine()
                history.reversed().take(20).forEachIndexed { index, entry ->
                    appendLine("### Command ${history.size - index}: `${entry.command}`")
                    appendLine("- **ID:** ${entry.id}")
                    appendLine("- **Time:** ${formatTimestamp(entry.timestamp)}")
                    if (entry.exitCode != null) {
                        appendLine("- **Exit Code:** ${entry.exitCode}")
                    }
                    if (entry.workingDir != null) {
                        appendLine("- **Working Directory:** ${entry.workingDir}")
                    }
                    if (entry.output != null && entry.output.isNotEmpty()) {
                        val preview = if (entry.output.length > 200) {
                            entry.output.take(200) + "..."
                        } else {
                            entry.output
                        }
                        appendLine("- **Output Preview:** $preview")
                    }
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "History: ${history.size} commands (${stats.successRate.toInt()}% success)"
        )
    }
    
    private fun searchHistory(query: String?, limit: Int?): ToolResult {
        if (query == null || query.isBlank()) {
            return ToolResult(
                llmContent = "Search query is required",
                returnDisplay = "Error: Query required",
                error = ToolError(
                    message = "Search query is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        val results = CommandHistoryManager.searchHistory(query, limit ?: 50)
        
        val output = buildString {
            appendLine("# Command History Search")
            appendLine()
            appendLine("**Query:** `$query`")
            appendLine("**Results:** ${results.size}")
            appendLine()
            
            if (results.isEmpty()) {
                appendLine("No commands found matching the query.")
            } else {
                appendLine("## Matching Commands")
                appendLine()
                appendLine("| # | Command | Time | Exit Code |")
                appendLine("|---|---------|------|-----------|")
                results.reversed().forEachIndexed { index, entry ->
                    val time = formatTimestamp(entry.timestamp)
                    val exitCodeStr = entry.exitCode?.toString() ?: "N/A"
                    appendLine("| ${index + 1} | `${entry.command.take(60)}` | $time | $exitCodeStr |")
                }
                appendLine()
                
                appendLine("## Details")
                appendLine()
                results.reversed().take(10).forEachIndexed { index, entry ->
                    appendLine("### ${index + 1}. `${entry.command}`")
                    appendLine("- **Time:** ${formatTimestamp(entry.timestamp)}")
                    appendLine("- **ID:** ${entry.id}")
                    if (entry.exitCode != null) {
                        appendLine("- **Exit Code:** ${entry.exitCode}")
                    }
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Found ${results.size} matching commands"
        )
    }
    
    private suspend fun replayCommand(query: String?): ToolResult {
        if (query == null || query.isBlank()) {
            return ToolResult(
                llmContent = "Command ID or index is required for replay",
                returnDisplay = "Error: ID/index required",
                error = ToolError(
                    message = "Command ID or index is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        val entry = CommandHistoryManager.getCommand(query)
        
        if (entry == null) {
            return ToolResult(
                llmContent = "Command not found: $query. Use 'view' action to see available commands.",
                returnDisplay = "Command not found",
                error = ToolError(
                    message = "Command not found: $query",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Return command details for replay (actual execution would be done via shell tool)
        val output = buildString {
            appendLine("# Command Replay")
            appendLine()
            appendLine("**Command ID:** ${entry.id}")
            appendLine("**Original Time:** ${formatTimestamp(entry.timestamp)}")
            appendLine()
            appendLine("## Command to Execute")
            appendLine()
            appendLine("```bash")
            appendLine(entry.command)
            appendLine("```")
            appendLine()
            
            if (entry.workingDir != null) {
                appendLine("**Working Directory:** ${entry.workingDir}")
                appendLine()
            }
            
            appendLine("## Original Execution")
            appendLine()
            if (entry.exitCode != null) {
                appendLine("- **Exit Code:** ${entry.exitCode}")
            }
            if (entry.output != null) {
                appendLine("- **Output:**")
                appendLine("```")
                appendLine(if (entry.output.length > 500) entry.output.take(500) + "..." else entry.output)
                appendLine("```")
            }
            appendLine()
            appendLine("**Note:** To execute this command, use the `shell` tool with the command above.")
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Command ready for replay: ${entry.command.take(50)}"
        )
    }
    
    private suspend fun saveScript(query: String?, outputPath: String?): ToolResult {
        val commands = if (query != null) {
            // Parse query as range or list of indices
            val indices = parseIndices(query)
            indices.mapNotNull { index ->
                val history = CommandHistoryManager.getAllHistory()
                if (index >= 0 && index < history.size) {
                    history[index]
                } else {
                    null
                }
            }
        } else {
            // Save all recent commands
            CommandHistoryManager.getRecentHistory(50)
        }
        
        if (commands.isEmpty()) {
            return ToolResult(
                llmContent = "No commands to save",
                returnDisplay = "No commands found"
            )
        }
        
        val scriptContent = buildString {
            appendLine("#!/bin/sh")
            appendLine("# Command history script")
            appendLine("# Generated: ${formatTimestamp(System.currentTimeMillis())}")
            appendLine("# Total commands: ${commands.size}")
            appendLine()
            commands.forEach { entry ->
                appendLine("# Command: ${entry.command}")
                appendLine("# Time: ${formatTimestamp(entry.timestamp)}")
                if (entry.workingDir != null) {
                    appendLine("cd '${entry.workingDir}'")
                }
                appendLine(entry.command)
                appendLine()
            }
        }
        
        val savedPath = outputPath?.let { path ->
            val file = if (File(path).isAbsolute) {
                File(path)
            } else {
                File(workspaceRoot, path)
            }
            
            // Create parent directories if needed
            file.parentFile?.mkdirs()
            
            // Ensure .sh extension
            val finalFile = if (file.extension.isEmpty()) {
                File(file.parent, "${file.name}.sh")
            } else {
                file
            }
            
            FileWriter(finalFile).use { writer ->
                writer.write(scriptContent)
            }
            
            // Make executable
            finalFile.setExecutable(true)
            
            finalFile.absolutePath
        }
        
        val output = buildString {
            appendLine("# Command Script Saved")
            appendLine()
            appendLine("**Commands:** ${commands.size}")
            if (savedPath != null) {
                appendLine("**Saved To:** `$savedPath`")
            }
            appendLine()
            appendLine("## Script Content")
            appendLine()
            appendLine("```bash")
            appendLine(scriptContent)
            appendLine("```")
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = if (savedPath != null) "Saved to $savedPath" else "Script generated (${commands.size} commands)"
        )
    }
    
    private fun clearHistory(): ToolResult {
        val count = CommandHistoryManager.getAllHistory().size
        CommandHistoryManager.clearHistory()
        
        return ToolResult(
            llmContent = "Command history cleared. Removed $count commands.",
            returnDisplay = "History cleared ($count commands)"
        )
    }
    
    private fun parseIndices(query: String): List<Int> {
        return when {
            query.contains("-") -> {
                // Range: "1-5" or "1..5"
                val parts = query.split("-", "..")
                if (parts.size == 2) {
                    val start = parts[0].trim().toIntOrNull() ?: return emptyList()
                    val end = parts[1].trim().toIntOrNull() ?: return emptyList()
                    (start..end).toList()
                } else {
                    emptyList()
                }
            }
            query.contains(",") -> {
                // Comma-separated: "1,2,3"
                query.split(",").mapNotNull { it.trim().toIntOrNull() }
            }
            else -> {
                // Single index
                listOfNotNull(query.trim().toIntOrNull())
            }
        }
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }
}

/**
 * Command history tool
 */
class CommandHistoryTool(
    private val workspaceRoot: String
) : DeclarativeTool<CommandHistoryToolParams, ToolResult>() {
    
    override val name: String = "command_history"
    override val displayName: String = "Command History"
    override val description: String = """
        Manage and replay command history. View, search, replay, and save command sequences for debugging and automation.
        
        Actions:
        - view: View command history (recent or all)
        - search: Search commands by query
        - replay: Get command details for replay
        - save: Save command sequence as executable script
        - clear: Clear command history
        
        Features:
        - View command history with statistics
        - Search commands by content or output
        - Replay commands (get command details)
        - Save command sequences as shell scripts
        - Clear history
        - Command statistics (success rate, unique commands)
        
        Examples:
        - command_history(action="view") - View recent commands
        - command_history(action="view", limit=100) - View last 100 commands
        - command_history(action="search", query="npm") - Search for npm commands
        - command_history(action="replay", query="0") - Replay most recent command
        - command_history(action="save", query="1-10", outputPath="setup.sh") - Save commands 1-10 as script
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "action" to PropertySchema(
                type = "string",
                description = "Action to perform: 'view', 'search', 'replay', 'save', or 'clear'",
                enum = listOf("view", "search", "replay", "save", "clear")
            ),
            "query" to PropertySchema(
                type = "string",
                description = "Search query (for search), command ID/index (for replay), or index range (for save, e.g., '1-10' or '1,2,3')"
            ),
            "outputPath" to PropertySchema(
                type = "string",
                description = "Path to save script file (for save action, relative to workspace or absolute)"
            ),
            "limit" to PropertySchema(
                type = "integer",
                description = "Limit number of results (for view/search actions)"
            )
        ),
        required = listOf("action")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: CommandHistoryToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<CommandHistoryToolParams, ToolResult> {
        return CommandHistoryToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): CommandHistoryToolParams {
        return CommandHistoryToolParams(
            action = params["action"] as? String ?: "view",
            query = params["query"] as? String,
            outputPath = params["outputPath"] as? String,
            limit = (params["limit"] as? Number)?.toInt()
        )
    }
}
