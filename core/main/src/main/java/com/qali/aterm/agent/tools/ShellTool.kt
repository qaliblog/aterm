package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.service.TabType
import com.termux.terminal.TerminalSession
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

data class ShellToolParams(
    val command: String,
    val description: String? = null,
    val dir_path: String? = null
)

class ShellToolInvocation(
    toolParams: ShellToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val sessionId: String? = null,
    private val mainActivity: MainActivity? = null
) : ToolInvocation<ShellToolParams, ToolResult> {
    
    override val params: ShellToolParams = toolParams
    
    override fun getDescription(): String {
        var desc = params.command
        if (params.dir_path != null) {
            desc += " [in ${params.dir_path}]"
        }
        if (params.description != null) {
            desc += " (${params.description})"
        }
        return desc
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Command cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return try {
            val workingDir: File = if (params.dir_path != null && params.dir_path.isNotEmpty()) {
                val dirPath = params.dir_path.trim()
                
                // If dir_path is the same as workspace root, just use workspace root
                val finalDir = if (dirPath == workspaceRoot) {
                    File(workspaceRoot)
                } else {
                    try {
                        // Check if paths are equivalent (canonical comparison)
                        val dirPathFile = File(dirPath)
                        val workspaceRootFile = File(workspaceRoot)
                        if (dirPathFile.canonicalPath == workspaceRootFile.canonicalPath) {
                            File(workspaceRoot)
                        } else {
                            // Check if it's an absolute path
                            val dir = if (dirPathFile.isAbsolute) {
                                // Already absolute, use as-is
                                dirPathFile
                            } else {
                                // Relative path, resolve from workspace root
                                File(workspaceRoot, dirPath)
                            }
                            
                            // Normalize the path to avoid duplication
                            try {
                                dir.canonicalFile
                            } catch (e: Exception) {
                                // If canonicalization fails, use the dir as-is
                                dir
                            }
                        }
                    } catch (e: Exception) {
                        // If path comparison fails, fall back to workspace root
                        android.util.Log.w("ShellTool", "Path comparison failed: ${e.message}")
                        File(workspaceRoot)
                    }
                }
                
                // Try to create directory if it doesn't exist
                if (!finalDir.exists()) {
                    try {
                        finalDir.mkdirs()
                        android.util.Log.d("ShellTool", "Created working directory: ${finalDir.absolutePath}")
                        finalDir
                    } catch (e: Exception) {
                        android.util.Log.w("ShellTool", "Failed to create directory, using workspace root: ${e.message}")
                        // Fallback to workspace root if directory creation fails
                        File(workspaceRoot)
                    }
                } else {
                    finalDir
                }
            } else {
                File(workspaceRoot)
            }
            
            // Ensure working directory exists (should exist now after mkdirs, but double-check)
            val finalWorkingDir: File
            if (!workingDir.exists()) {
                // Last resort: use workspace root
                val fallbackDir = File(workspaceRoot)
                if (fallbackDir.exists()) {
                    android.util.Log.w("ShellTool", "Working directory ${workingDir.absolutePath} not found, using workspace root")
                    finalWorkingDir = fallbackDir
                } else {
                    return ToolResult(
                        llmContent = "Working directory does not exist: ${workingDir.absolutePath}. Workspace root also not found: ${workspaceRoot}",
                        returnDisplay = "Error: Directory not found",
                        error = ToolError(
                            message = "Working directory does not exist: ${workingDir.absolutePath}",
                            type = ToolErrorType.FILE_NOT_FOUND
                        )
                    )
                }
            } else {
                finalWorkingDir = workingDir
            }
            
            // Use withContext to ensure we're on the right thread for process operations
            // Add timeout to prevent hanging (60 seconds max)
            val result = kotlinx.coroutines.withTimeoutOrNull(60000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        android.util.Log.d("ShellTool", "Executing command: ${params.command}")
                        android.util.Log.d("ShellTool", "Working directory: ${finalWorkingDir.absolutePath}")
                        
                        // Try to use terminal session if available
                        val agentSessionId = sessionId?.let { "${it}_agent" }
                        val terminalSession: TerminalSession? = if (agentSessionId != null && mainActivity != null && mainActivity.sessionBinder != null) {
                            mainActivity.sessionBinder!!.getSession(agentSessionId)
                        } else {
                            null
                        }
                        
                        if (terminalSession != null) {
                            // Use terminal session for command execution
                            android.util.Log.d("ShellTool", "Using terminal session: $agentSessionId")
                            
                            // Change to working directory if needed
                            val cdCommand = if (finalWorkingDir.absolutePath != workspaceRoot) {
                                "cd '${finalWorkingDir.absolutePath}' && "
                            } else {
                                ""
                            }
                            
                            // Get initial transcript length
                            val initialLength = terminalSession.emulator?.screen?.getTranscriptText()?.length ?: 0
                            
                            // Write command to terminal (add newline to execute)
                            terminalSession.write("$cdCommand${params.command}\n")
                            
                            // Wait for command to complete and read output
                            // Use a more efficient approach: wait for prompt to appear
                            var output = ""
                            var attempts = 0
                            val maxAttempts = 200 // 20 seconds max (200 * 100ms)
                            
                            // Wait a bit for command to start
                            delay(200)
                            
                            while (attempts < maxAttempts) {
                                // Check for cancellation first
                                if (signal?.isAborted() == true) {
                                    android.util.Log.d("ShellTool", "Command cancelled")
                                    throw InterruptedException("Command cancelled")
                                }
                                
                                val currentTranscript = terminalSession.emulator?.screen?.getTranscriptText() ?: ""
                                if (currentTranscript.length > initialLength) {
                                    // Get new output (everything after initial transcript)
                                    val newOutput = currentTranscript.substring(initialLength)
                                    
                                    // Check if we see a prompt (command completed)
                                    // Look for common prompt patterns or empty line followed by prompt
                                    val lines = newOutput.lines()
                                    if (lines.size >= 2) {
                                        val lastLine = lines.lastOrNull()?.trim() ?: ""
                                        val secondLastLine = lines.getOrNull(lines.size - 2)?.trim() ?: ""
                                        
                                        // Check if last line looks like a prompt (contains $ or # and path-like structure)
                                        val isPrompt = lastLine.matches(Regex(".*[\\$#]\\s*$")) && 
                                                      (lastLine.contains("@") || lastLine.contains(":") || lastLine.contains("/"))
                                        
                                        // Or if we have output and waited enough
                                        val hasOutput = newOutput.trim().isNotEmpty() && attempts >= 10
                                        
                                        if (isPrompt || (hasOutput && attempts >= 30)) {
                                            output = newOutput
                                            // Remove the command echo and prompt from output
                                            val filteredLines = lines.filterIndexed { index, line ->
                                                val trimmed = line.trim()
                                                // Skip first line if it's the command itself
                                                if (index == 0 && (trimmed.contains(params.command.split(" ").firstOrNull() ?: "") || trimmed.isEmpty())) {
                                                    false
                                                } else {
                                                    // Skip prompt lines
                                                    !trimmed.matches(Regex(".*[\\$#]\\s*$")) || !trimmed.contains("@")
                                                }
                                            }
                                            output = filteredLines.joinToString("\n").trim()
                                            break
                                        }
                                    } else if (newOutput.trim().isNotEmpty() && attempts >= 30) {
                                        // If we have some output and waited 3 seconds, use it
                                        output = newOutput.trim()
                                        break
                                    }
                                }
                                
                                attempts++
                                delay(100) // Wait 100ms between checks
                            }
                            
                            if (output.isEmpty() && attempts >= maxAttempts) {
                                android.util.Log.w("ShellTool", "Command output timeout, using transcript")
                                val fullTranscript = terminalSession.emulator?.screen?.getTranscriptText() ?: ""
                                if (fullTranscript.length > initialLength) {
                                    val rawOutput = fullTranscript.substring(initialLength)
                                    val lines = rawOutput.lines()
                                    val filteredLines = lines.filterIndexed { index, line ->
                                        val trimmed = line.trim()
                                        !trimmed.matches(Regex(".*[\\$#]\\s*$")) || !trimmed.contains("@")
                                    }
                                    output = filteredLines.joinToString("\n").trim()
                                }
                            }
                            
                            android.util.Log.d("ShellTool", "Command completed via terminal session")
                            android.util.Log.d("ShellTool", "Output length: ${output.length} characters")
                            
                            Pair(0, output) // Terminal session commands typically return 0, actual exit code would need parsing
                        } else {
                            // Fallback to ProcessBuilder if terminal session not available
                            android.util.Log.d("ShellTool", "Terminal session not available, using ProcessBuilder")
                            
                            // Build process with environment
                            val processBuilder = ProcessBuilder()
                                .command("sh", "-c", params.command)
                                .directory(finalWorkingDir)
                                .redirectErrorStream(true)
                        
                            // Set up environment variables matching rootfs/terminal session environment
                            val env = processBuilder.environment()
                            // Use comprehensive PATH that includes rootfs paths and common Node.js/npm locations
                            val rootfsPath = "/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin"
                            // Add common Node.js/npm installation paths
                            val nodePaths = "/usr/local/lib/node_modules/npm/bin:/usr/local/bin/node:/usr/bin/node:/opt/node/bin"
                            val systemPath = "/system/bin:/system/xbin"
                            // Prioritize rootfs paths, then node paths, then system paths
                            env["PATH"] = "$rootfsPath:$nodePaths:$systemPath:${env["PATH"] ?: ""}"
                            env["HOME"] = env["HOME"] ?: "/root"
                            env["SHELL"] = "/bin/sh"
                            env["TERM"] = "xterm-256color"
                            env["COLORTERM"] = "truecolor"
                            env["LANG"] = "C.UTF-8"
                            // Add workspace root to environment for scripts that need it
                            env["WORKSPACE_ROOT"] = workspaceRoot
                            env["PWD"] = finalWorkingDir.absolutePath
                            
                            val process = processBuilder.start()
                            android.util.Log.d("ShellTool", "Process started successfully")
                            
                            // Read output - since redirectErrorStream(true), both stdout and stderr go to inputStream
                            val output = StringBuilder()
                            val reader = process.inputStream.bufferedReader()
                            
                            // Read all lines from the combined stream
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                output.appendLine(line)
                                // Check for cancellation
                                if (signal?.isAborted() == true) {
                                    process.destroyForcibly()
                                    throw InterruptedException("Command cancelled")
                                }
                            }
                            
                            // Wait for process to complete (with timeout check)
                            val exitCode = process.waitFor()
                            val finalOutput = output.toString().trim()
                            
                            // Close streams
                            reader.close()
                            process.inputStream.close()
                            process.outputStream.close()
                            process.errorStream.close()
                            
                            // Clean up process
                            if (process.isAlive) {
                                process.destroy()
                            }
                            
                            android.util.Log.d("ShellTool", "Command completed with exit code: $exitCode")
                            android.util.Log.d("ShellTool", "Output length: ${finalOutput.length} characters")
                            
                            Pair(exitCode, finalOutput)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ShellTool", "Error executing shell command: ${params.command}", e)
                        throw e
                    }
                }
            } ?: run {
                // Timeout occurred
                android.util.Log.e("ShellTool", "Command timed out after 60 seconds: ${params.command}")
                Pair(-1, "Command timed out after 60 seconds")
            }
            
            val (exitCode, output) = result
            
            if (exitCode == 0) {
                updateOutput?.invoke(output)
                ToolResult(
                    llmContent = output,
                    returnDisplay = "Command executed successfully"
                )
            } else {
                ToolResult(
                    llmContent = "Command failed with exit code $exitCode:\n$output",
                    returnDisplay = "Error: Exit code $exitCode",
                    error = ToolError(
                        message = "Command failed with exit code $exitCode",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
        } catch (e: java.io.IOException) {
            android.util.Log.e("ShellTool", "IOException executing command: ${params.command}", e)
            ToolResult(
                llmContent = "IO Error executing command: ${e.message}\nCommand: ${params.command}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "IO Error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        } catch (e: InterruptedException) {
            android.util.Log.e("ShellTool", "InterruptedException executing command: ${params.command}", e)
            ToolResult(
                llmContent = "Command execution was interrupted: ${e.message}",
                returnDisplay = "Error: Interrupted",
                error = ToolError(
                    message = e.message ?: "Command interrupted",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("ShellTool", "Exception executing command: ${params.command}", e)
            android.util.Log.e("ShellTool", "Exception type: ${e.javaClass.simpleName}")
            android.util.Log.e("ShellTool", "Exception message: ${e.message}")
            e.printStackTrace()
            ToolResult(
                llmContent = "Error executing command: ${e.message ?: e.javaClass.simpleName}\nCommand: ${params.command}",
                returnDisplay = "Error: ${e.message ?: "Unknown error"}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
}

class ShellTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val sessionId: String? = null,
    private val mainActivity: MainActivity? = null
) : DeclarativeTool<ShellToolParams, ToolResult>() {
    
    override val name = "shell"
    override val displayName = "Shell"
    override val description = "Executes a shell command and returns the output. Use this to run terminal commands, scripts, and interact with the file system."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "command" to PropertySchema(
                type = "string",
                description = "The shell command to execute."
            ),
            "description" to PropertySchema(
                type = "string",
                description = "Optional description of what the command does."
            ),
            "dir_path" to PropertySchema(
                type = "string",
                description = "Optional directory path to execute the command in."
            )
        ),
        required = listOf("command")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: ShellToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ShellToolParams, ToolResult> {
        return ShellToolInvocation(params, workspaceRoot, sessionId, mainActivity)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ShellToolParams {
        val command = params["command"] as? String
            ?: throw IllegalArgumentException("command is required")
        
        if (command.trim().isEmpty()) {
            throw IllegalArgumentException("command must be non-empty")
        }
        
        return ShellToolParams(
            command = command,
            description = params["description"] as? String,
            dir_path = params["dir_path"] as? String
        )
    }
}
