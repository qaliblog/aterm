package com.qali.aterm.gemini.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.core.FunctionDeclaration
import com.qali.aterm.gemini.core.FunctionParameters
import com.qali.aterm.gemini.core.PropertySchema
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
    private val workspaceRoot: String = alpineDir().absolutePath
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
            val workingDir = if (params.dir_path != null) {
                File(workspaceRoot, params.dir_path)
            } else {
                File(workspaceRoot)
            }
            
            // Ensure working directory exists
            if (!workingDir.exists()) {
                return ToolResult(
                    llmContent = "Working directory does not exist: ${workingDir.absolutePath}",
                    returnDisplay = "Error: Directory not found",
                    error = ToolError(
                        message = "Working directory does not exist: ${workingDir.absolutePath}",
                        type = ToolErrorType.FILE_NOT_FOUND
                    )
                )
            }
            
            // Use withContext to ensure we're on the right thread for process operations
            // Add timeout to prevent hanging (60 seconds max)
            val result = kotlinx.coroutines.withTimeoutOrNull(60000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        android.util.Log.d("ShellTool", "Executing command: ${params.command}")
                        android.util.Log.d("ShellTool", "Working directory: ${workingDir.absolutePath}")
                        
                        // Build process with environment
                        val processBuilder = ProcessBuilder()
                            .command("sh", "-c", params.command)
                            .directory(workingDir)
                            .redirectErrorStream(true)
                        
                        // Set up environment variables for better compatibility
                        val env = processBuilder.environment()
                        env["PATH"] = "/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/system/bin:/system/xbin:${env["PATH"] ?: ""}"
                        env["HOME"] = env["HOME"] ?: "/root"
                        env["SHELL"] = "/bin/sh"
                        
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
    private val workspaceRoot: String = alpineDir().absolutePath
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
        return ShellToolInvocation(params, workspaceRoot)
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
