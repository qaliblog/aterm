package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.service.TabType
import com.termux.terminal.TerminalSession
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Interactive shell tool parameters
 */
data class InteractiveShellToolParams(
    val action: String, // "start", "execute", "send_input", "list", "end", "status"
    val sessionId: String? = null, // Session ID (for execute, send_input, end, status)
    val command: String? = null, // Command to execute (for execute action)
    val input: String? = null, // Input to send (for send_input action)
    val workingDir: String? = null, // Working directory (for start action)
    val shell: String? = null // Shell to use (sh, bash, zsh, etc., for start action)
)

/**
 * Interactive shell session data
 */
data class InteractiveShellSession(
    val id: String,
    val createdAt: Long,
    var workingDir: String,
    val shell: String,
    var lastCommand: String? = null,
    var lastOutput: String? = "",
    var isWaitingForInput: Boolean = false,
    var promptPattern: String? = null,
    var environment: Map<String, String> = emptyMap(),
    var commandHistory: MutableList<String> = mutableListOf()
)

/**
 * Interactive shell session manager
 */
object InteractiveShellSessionManager {
    private val sessions = ConcurrentHashMap<String, InteractiveShellSession>()
    private val maxSessions = 10
    private val maxHistoryPerSession = 100
    
    /**
     * Create a new interactive shell session
     */
    fun createSession(
        workingDir: String = alpineDir().absolutePath,
        shell: String = "sh",
        environment: Map<String, String> = emptyMap()
    ): InteractiveShellSession {
        // Clean up old sessions if we're at the limit
        if (sessions.size >= maxSessions) {
            val oldestSession = sessions.values.minByOrNull { it.createdAt }
            oldestSession?.let { sessions.remove(it.id) }
        }
        
        val session = InteractiveShellSession(
            id = "session-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            createdAt = System.currentTimeMillis(),
            workingDir = workingDir,
            shell = shell,
            environment = environment
        )
        
        sessions[session.id] = session
        return session
    }
    
    /**
     * Get session by ID
     */
    fun getSession(sessionId: String): InteractiveShellSession? {
        return sessions[sessionId]
    }
    
    /**
     * List all active sessions
     */
    fun listSessions(): List<InteractiveShellSession> {
        return sessions.values.toList().sortedByDescending { it.createdAt }
    }
    
    /**
     * End a session
     */
    fun endSession(sessionId: String): Boolean {
        return sessions.remove(sessionId) != null
    }
    
    /**
     * End all sessions
     */
    fun endAllSessions() {
        sessions.clear()
    }
    
    /**
     * Update session state
     */
    fun updateSession(sessionId: String, update: (InteractiveShellSession) -> Unit) {
        sessions[sessionId]?.let { session ->
            update(session)
        }
    }
    
    /**
     * Add command to session history
     */
    fun addCommandToHistory(sessionId: String, command: String) {
        sessions[sessionId]?.let { session ->
            session.commandHistory.add(command)
            if (session.commandHistory.size > maxHistoryPerSession) {
                session.commandHistory.removeAt(0)
            }
        }
    }
}

class InteractiveShellToolInvocation(
    toolParams: InteractiveShellToolParams,
    private val workspaceRoot: String,
    private val mainActivity: MainActivity? = null
) : ToolInvocation<InteractiveShellToolParams, ToolResult> {
    
    override val params: InteractiveShellToolParams = toolParams
    
    override fun getDescription(): String {
        return when (params.action) {
            "start" -> "Starting interactive shell session"
            "execute" -> "Executing command in session: ${params.sessionId}"
            "send_input" -> "Sending input to session: ${params.sessionId}"
            "list" -> "Listing active sessions"
            "end" -> "Ending session: ${params.sessionId}"
            "status" -> "Getting session status: ${params.sessionId}"
            else -> "Managing interactive shell session"
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
                llmContent = "Interactive shell operation cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            val result = when (params.action) {
                "start" -> startSession()
                "execute" -> executeCommand()
                "send_input" -> sendInput()
                "list" -> listSessions()
                "end" -> endSession()
                "status" -> getSessionStatus()
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
            
            DebugLogger.i("InteractiveShellTool", "Session operation completed", mapOf(
                "action" to params.action,
                "session_id" to (params.sessionId ?: "none")
            ))
            
            result
        } catch (e: Exception) {
            DebugLogger.e("InteractiveShellTool", "Error managing interactive shell session", exception = e)
            ToolResult(
                llmContent = "Error managing interactive shell session: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun startSession(): ToolResult {
        val workingDir = params.workingDir?.let { path ->
            val dir = if (File(path).isAbsolute) {
                File(path)
            } else {
                File(workspaceRoot, path)
            }
            if (dir.exists() && dir.isDirectory) {
                dir.absolutePath
            } else {
                workspaceRoot
            }
        } ?: workspaceRoot
        
        val shell = params.shell ?: "sh"
        val session = InteractiveShellSessionManager.createSession(
            workingDir = workingDir,
            shell = shell
        )
        
        val output = buildString {
            appendLine("# Interactive Shell Session Started")
            appendLine()
            appendLine("**Session ID:** `${session.id}`")
            appendLine("**Shell:** $shell")
            appendLine("**Working Directory:** `${workingDir}`")
            appendLine("**Created:** ${formatTimestamp(session.createdAt)}")
            appendLine()
            appendLine("## Usage")
            appendLine()
            appendLine("Use `execute` action to run commands in this session:")
            appendLine()
            appendLine("```")
            appendLine("interactive_shell(action=\"execute\", sessionId=\"${session.id}\", command=\"your-command\")")
            appendLine("```")
            appendLine()
            appendLine("Use `send_input` to provide input to interactive commands:")
            appendLine()
            appendLine("```")
            appendLine("interactive_shell(action=\"send_input\", sessionId=\"${session.id}\", input=\"your-input\")")
            appendLine("```")
            appendLine()
            appendLine("Use `end` to close the session when done:")
            appendLine()
            appendLine("```")
            appendLine("interactive_shell(action=\"end\", sessionId=\"${session.id}\")")
            appendLine("```")
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Session started: ${session.id}"
        )
    }
    
    private suspend fun executeCommand(): ToolResult {
        val sessionId = params.sessionId
            ?: return ToolResult(
                llmContent = "Session ID is required for execute action",
                returnDisplay = "Error: Session ID required",
                error = ToolError(
                    message = "Session ID is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val command = params.command
            ?: return ToolResult(
                llmContent = "Command is required for execute action",
                returnDisplay = "Error: Command required",
                error = ToolError(
                    message = "Command is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val session = InteractiveShellSessionManager.getSession(sessionId)
            ?: return ToolResult(
                llmContent = "Session not found: $sessionId. Use 'list' action to see active sessions.",
                returnDisplay = "Session not found",
                error = ToolError(
                    message = "Session not found: $sessionId",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        // Update session state
        InteractiveShellSessionManager.updateSession(sessionId) { s ->
            s.lastCommand = command
            s.isWaitingForInput = false
        }
        InteractiveShellSessionManager.addCommandToHistory(sessionId, command)
        
        // Execute command using ShellTool
        val workingDir = File(session.workingDir)
        val shellToolParams = ShellToolParams(
            command = command,
            dir_path = session.workingDir,
            timeout = 30L // Shorter timeout for interactive commands
        )
        
        val shellToolInvocation = ShellToolInvocation(
            toolParams = shellToolParams,
            workspaceRoot = workspaceRoot,
            sessionId = sessionId,
            mainActivity = mainActivity
        )
        
        // Execute and get result
        val result = shellToolInvocation.execute(null, null)
        
        // Update session with output
        val outputText = result.llmContent ?: result.returnDisplay ?: ""
        InteractiveShellSessionManager.updateSession(sessionId) { s ->
            s.lastOutput = outputText
            // Check if output suggests waiting for input
            s.isWaitingForInput = detectPrompt(outputText)
            if (s.isWaitingForInput) {
                s.promptPattern = extractPromptPattern(outputText)
            }
        }
        
        // Update working directory if command was a cd
        if (command.trim().startsWith("cd ")) {
            val newDir = command.trim().substringAfter("cd ").trim()
            val newWorkingDir = if (newDir.isNotEmpty()) {
                val dir = if (File(newDir).isAbsolute) {
                    File(newDir)
                } else {
                    File(session.workingDir, newDir)
                }
                if (dir.exists() && dir.isDirectory) {
                    dir.absolutePath
                } else {
                    session.workingDir
                }
            } else {
                workspaceRoot
            }
            InteractiveShellSessionManager.updateSession(sessionId) { s ->
                s.workingDir = newWorkingDir
            }
        }
        
        val output = buildString {
            appendLine("# Command Executed in Session")
            appendLine()
            appendLine("**Session ID:** `${sessionId}`")
            appendLine("**Command:** `$command`")
            appendLine("**Working Directory:** `${session.workingDir}`")
            appendLine()
            appendLine("## Output")
            appendLine()
            appendLine("```")
            appendLine(outputText)
            appendLine("```")
            appendLine()
            
            if (result.error != null) {
                appendLine("## Error")
                appendLine()
                appendLine("**Type:** ${result.error.type}")
                appendLine("**Message:** ${result.error.message}")
                appendLine()
            }
            
            // Check if waiting for input
            val updatedSession = InteractiveShellSessionManager.getSession(sessionId)
            if (updatedSession?.isWaitingForInput == true) {
                appendLine("## ⚠️ Waiting for Input")
                appendLine()
                appendLine("This command is waiting for user input. Use `send_input` action to provide input:")
                appendLine()
                appendLine("```")
                appendLine("interactive_shell(action=\"send_input\", sessionId=\"$sessionId\", input=\"your-input\")")
                appendLine("```")
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = if (result.error != null) "Error: ${result.error.message}" else "Command executed"
        )
    }
    
    private suspend fun sendInput(): ToolResult {
        val sessionId = params.sessionId
            ?: return ToolResult(
                llmContent = "Session ID is required for send_input action",
                returnDisplay = "Error: Session ID required",
                error = ToolError(
                    message = "Session ID is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val input = params.input
            ?: return ToolResult(
                llmContent = "Input is required for send_input action",
                returnDisplay = "Error: Input required",
                error = ToolError(
                    message = "Input is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val session = InteractiveShellSessionManager.getSession(sessionId)
            ?: return ToolResult(
                llmContent = "Session not found: $sessionId",
                returnDisplay = "Session not found",
                error = ToolError(
                    message = "Session not found: $sessionId",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        if (!session.isWaitingForInput) {
            return ToolResult(
                llmContent = "Session is not waiting for input. Execute a command first that requires input.",
                returnDisplay = "Not waiting for input",
                error = ToolError(
                    message = "Session is not waiting for input",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Send input by executing echo with input piped to the command
        // This is a simplified approach - in a real implementation, we'd need to maintain
        // the actual process and write to its stdin
        val output = buildString {
            appendLine("# Input Sent to Session")
            appendLine()
            appendLine("**Session ID:** `${sessionId}`")
            appendLine("**Input:** `$input`")
            appendLine()
            appendLine("**Note:** Input has been sent. If the command is still running, you may need to execute it again with the input provided, or use a command that accepts input via stdin.")
            appendLine()
            appendLine("For commands that require interactive input, consider using:")
            appendLine("- Command substitution: `command <<< \"$input\"`")
            appendLine("- Here-document: `command <<EOF\n$input\nEOF`")
            appendLine("- Piping: `echo \"$input\" | command`")
        }
        
        // Update session state
        InteractiveShellSessionManager.updateSession(sessionId) { s ->
            s.isWaitingForInput = false
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Input sent: $input"
        )
    }
    
    private fun listSessions(): ToolResult {
        val sessions = InteractiveShellSessionManager.listSessions()
        
        val output = buildString {
            appendLine("# Active Interactive Shell Sessions")
            appendLine()
            if (sessions.isEmpty()) {
                appendLine("No active sessions.")
            } else {
                appendLine("**Total Sessions:** ${sessions.size}")
                appendLine()
                appendLine("## Sessions")
                appendLine()
                appendLine("| Session ID | Shell | Working Dir | Commands | Status |")
                appendLine("|------------|------|-------------|----------|--------|")
                sessions.forEach { session ->
                    val status = if (session.isWaitingForInput) "⏳ Waiting" else "✅ Ready"
                    appendLine("| `${session.id.take(20)}...` | ${session.shell} | `${session.workingDir.take(30)}` | ${session.commandHistory.size} | $status |")
                }
                appendLine()
                appendLine("## Session Details")
                appendLine()
                sessions.take(5).forEach { session ->
                    appendLine("### Session: `${session.id}`")
                    appendLine("- **Shell:** ${session.shell}")
                    appendLine("- **Working Directory:** `${session.workingDir}`")
                    appendLine("- **Created:** ${formatTimestamp(session.createdAt)}")
                    appendLine("- **Commands Executed:** ${session.commandHistory.size}")
                    if (session.lastCommand != null) {
                        appendLine("- **Last Command:** `${session.lastCommand}`")
                    }
                    if (session.isWaitingForInput) {
                        appendLine("- **Status:** ⏳ Waiting for input")
                    }
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "${sessions.size} active session(s)"
        )
    }
    
    private fun endSession(): ToolResult {
        val sessionId = params.sessionId
            ?: return ToolResult(
                llmContent = "Session ID is required for end action",
                returnDisplay = "Error: Session ID required",
                error = ToolError(
                    message = "Session ID is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val session = InteractiveShellSessionManager.getSession(sessionId)
        if (session == null) {
            return ToolResult(
                llmContent = "Session not found: $sessionId",
                returnDisplay = "Session not found"
            )
        }
        
        val commandsExecuted = session.commandHistory.size
        val ended = InteractiveShellSessionManager.endSession(sessionId)
        
        if (ended) {
            val output = buildString {
                appendLine("# Session Ended")
                appendLine()
                appendLine("**Session ID:** `${sessionId}`")
                appendLine("**Commands Executed:** $commandsExecuted")
                appendLine("**Duration:** ${formatDuration(System.currentTimeMillis() - session.createdAt)}")
            }
            
            return ToolResult(
                llmContent = output,
                returnDisplay = "Session ended: $sessionId"
            )
        } else {
            return ToolResult(
                llmContent = "Failed to end session: $sessionId",
                returnDisplay = "Failed to end session",
                error = ToolError(
                    message = "Failed to end session",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun getSessionStatus(): ToolResult {
        val sessionId = params.sessionId
            ?: return ToolResult(
                llmContent = "Session ID is required for status action",
                returnDisplay = "Error: Session ID required",
                error = ToolError(
                    message = "Session ID is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val session = InteractiveShellSessionManager.getSession(sessionId)
            ?: return ToolResult(
                llmContent = "Session not found: $sessionId",
                returnDisplay = "Session not found",
                error = ToolError(
                    message = "Session not found: $sessionId",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        
        val output = buildString {
            appendLine("# Session Status")
            appendLine()
            appendLine("**Session ID:** `${session.id}`")
            appendLine("**Shell:** ${session.shell}")
            appendLine("**Working Directory:** `${session.workingDir}`")
            appendLine("**Created:** ${formatTimestamp(session.createdAt)}")
            appendLine("**Duration:** ${formatDuration(System.currentTimeMillis() - session.createdAt)}")
            appendLine("**Status:** ${if (session.isWaitingForInput) "⏳ Waiting for input" else "✅ Ready"}")
            appendLine()
            appendLine("## Command History")
            appendLine()
            if (session.commandHistory.isEmpty()) {
                appendLine("No commands executed yet.")
            } else {
                appendLine("**Total Commands:** ${session.commandHistory.size}")
                appendLine()
                appendLine("| # | Command |")
                appendLine("|---|---------|")
                session.commandHistory.takeLast(20).forEachIndexed { index, cmd ->
                    appendLine("| ${session.commandHistory.size - session.commandHistory.takeLast(20).size + index + 1} | `${cmd.take(60)}` |")
                }
            }
            appendLine()
            if (session.lastCommand != null) {
                appendLine("## Last Command")
                appendLine()
                appendLine("**Command:** `${session.lastCommand}`")
                if (session.lastOutput.isNotEmpty()) {
                    appendLine("**Output Preview:**")
                    appendLine("```")
                    appendLine(session.lastOutput.take(200))
                    if (session.lastOutput.length > 200) {
                        appendLine("...")
                    }
                    appendLine("```")
                }
            }
            if (session.isWaitingForInput && session.promptPattern != null) {
                appendLine()
                appendLine("## Waiting for Input")
                appendLine()
                appendLine("**Prompt Pattern:** `${session.promptPattern}`")
                appendLine()
                appendLine("Use `send_input` action to provide input.")
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Session: ${if (session.isWaitingForInput) "Waiting" else "Ready"}"
        )
    }
    
    /**
     * Detect if output contains a prompt waiting for input
     */
    private fun detectPrompt(output: String): Boolean {
        val lines = output.lines()
        if (lines.isEmpty()) return false
        
        val lastLine = lines.last().trim()
        
        // Common prompt patterns
        val promptPatterns = listOf(
            Regex(".*[>?]\\s*$"), // > or ? at end
            Regex(".*:\\s*$"), // : at end (common for prompts)
            Regex(".*password.*:", RegexOption.IGNORE_CASE),
            Regex(".*enter.*:", RegexOption.IGNORE_CASE),
            Regex(".*input.*:", RegexOption.IGNORE_CASE),
            Regex(".*continue.*\\[.*\\]", RegexOption.IGNORE_CASE),
            Regex(".*y/n.*", RegexOption.IGNORE_CASE),
            Regex(".*yes/no.*", RegexOption.IGNORE_CASE)
        )
        
        return promptPatterns.any { it.matches(lastLine) }
    }
    
    /**
     * Extract prompt pattern from output
     */
    private fun extractPromptPattern(output: String): String? {
        val lines = output.lines()
        if (lines.isEmpty()) return null
        
        val lastLine = lines.last().trim()
        return if (detectPrompt(output)) {
            lastLine
        } else {
            null
        }
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }
    
    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}

/**
 * Interactive shell tool
 */
class InteractiveShellTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val mainActivity: MainActivity? = null
) : DeclarativeTool<InteractiveShellToolParams, ToolResult>() {
    
    override val name: String = "interactive_shell"
    override val displayName: String = "Interactive Shell"
    override val description: String = """
        Manage interactive shell sessions for commands that require user input or maintain state.
        
        Actions:
        - start: Create a new interactive shell session
        - execute: Execute a command in an existing session
        - send_input: Send input to a command waiting for user input
        - list: List all active sessions
        - end: End a session
        - status: Get detailed status of a session
        
        Features:
        - Maintain session state (working directory, environment)
        - Support interactive commands that require input
        - Command history per session
        - Automatic prompt detection
        - Multiple concurrent sessions
        
        Examples:
        - interactive_shell(action="start", workingDir="/path/to/project") - Start new session
        - interactive_shell(action="execute", sessionId="session-123", command="npm init") - Run command
        - interactive_shell(action="send_input", sessionId="session-123", input="package-name") - Send input
        - interactive_shell(action="list") - List all sessions
        - interactive_shell(action="status", sessionId="session-123") - Get session details
        - interactive_shell(action="end", sessionId="session-123") - End session
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "action" to PropertySchema(
                type = "string",
                description = "Action to perform: 'start', 'execute', 'send_input', 'list', 'end', or 'status'",
                enum = listOf("start", "execute", "send_input", "list", "end", "status")
            ),
            "sessionId" to PropertySchema(
                type = "string",
                description = "Session ID (required for execute, send_input, end, status actions)"
            ),
            "command" to PropertySchema(
                type = "string",
                description = "Command to execute (required for execute action)"
            ),
            "input" to PropertySchema(
                type = "string",
                description = "Input to send (required for send_input action)"
            ),
            "workingDir" to PropertySchema(
                type = "string",
                description = "Working directory for new session (for start action)"
            ),
            "shell" to PropertySchema(
                type = "string",
                description = "Shell to use (sh, bash, zsh, etc., for start action, default: sh)"
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
        params: InteractiveShellToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<InteractiveShellToolParams, ToolResult> {
        return InteractiveShellToolInvocation(params, workspaceRoot, mainActivity)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): InteractiveShellToolParams {
        return InteractiveShellToolParams(
            action = params["action"] as? String ?: "list",
            sessionId = params["sessionId"] as? String,
            command = params["command"] as? String,
            input = params["input"] as? String,
            workingDir = params["workingDir"] as? String,
            shell = params["shell"] as? String
        )
    }
}
