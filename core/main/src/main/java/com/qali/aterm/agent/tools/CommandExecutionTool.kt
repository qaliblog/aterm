package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.utils.CommandAllowlist
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Command execution tool with allowlist support
 * Prompts for permission unless command is in allowlist
 */
data class CommandExecutionToolParams(
    val command: String,
    val description: String? = null,
    val dir_path: String? = null,
    val timeout: Long? = null,
    val analyze_output: Boolean = false, // If true, will reprompt AI to analyze output
    val requires_approval: Boolean = true // If false, skip allowlist check (use with caution)
)

class CommandExecutionToolInvocation(
    toolParams: CommandExecutionToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val context: Context? = null,
    private val onPermissionRequest: ((String) -> CommandAllowlist.PermissionResult)? = null
) : ToolInvocation<CommandExecutionToolParams, ToolResult> {
    
    override val params: CommandExecutionToolParams = toolParams
    
    override fun getDescription(): String {
        return params.description ?: "Execute command: ${params.command}"
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
                llmContent = "Command execution cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        // Check allowlist
        if (params.requires_approval && !CommandAllowlist.isAllowed(params.command, context)) {
            // Request permission
            if (onPermissionRequest != null) {
                val result = onPermissionRequest(params.command)
                when (result) {
                    CommandAllowlist.PermissionResult.ALLOWED -> {
                        // Add to allowlist for future
                        CommandAllowlist.addToAllowlist(params.command, context)
                        updateOutput?.invoke("✓ Command approved and added to allowlist\n")
                    }
                    CommandAllowlist.PermissionResult.DENIED -> {
                        return ToolResult(
                            llmContent = "Command execution denied by user: ${params.command}",
                            returnDisplay = "Command denied",
                            error = ToolError(
                                message = "Command denied by user",
                                type = ToolErrorType.PERMISSION_DENIED
                            )
                        )
                    }
                    CommandAllowlist.PermissionResult.SKIPPED -> {
                        return ToolResult(
                            llmContent = "Command execution skipped: ${params.command}",
                            returnDisplay = "Command skipped",
                            error = ToolError(
                                message = "Command skipped by user",
                                type = ToolErrorType.CANCELLED
                            )
                        )
                    }
                    CommandAllowlist.PermissionResult.NEEDS_APPROVAL -> {
                        return ToolResult(
                            llmContent = "Command requires approval: ${params.command}",
                            returnDisplay = "Command requires approval",
                            error = ToolError(
                                message = "Command requires user approval",
                                type = ToolErrorType.PERMISSION_DENIED
                            )
                        )
                    }
                }
            } else {
                return ToolResult(
                    llmContent = "Command requires approval but no permission handler available: ${params.command}",
                    returnDisplay = "Command requires approval",
                    error = ToolError(
                        message = "Command requires approval",
                        type = ToolErrorType.PERMISSION_DENIED
                    )
                )
            }
        }
        
        // Execute command using ShellTool
        val shellTool = ShellToolInvocation(
            toolParams = ShellToolParams(
                command = params.command,
                description = params.description,
                dir_path = params.dir_path,
                timeout = params.timeout,
                background = false,
                parseOutput = false
            ),
            workspaceRoot = workspaceRoot
        )
        
        val result = shellTool.execute(signal, updateOutput)
        
        // If analyze_output is true, return result with flag for AI analysis
        if (params.analyze_output && result.llmContent.isNotEmpty()) {
            return ToolResult(
                llmContent = result.llmContent + "\n\n[ANALYZE_OUTPUT:true]",
                returnDisplay = result.returnDisplay,
                metadata = (result.metadata ?: emptyMap()) + mapOf(
                    "analyze_output" to true,
                    "command" to params.command
                )
            )
        }
        
        return result
    }
}

/**
 * Command execution tool declaration
 */
class CommandExecutionTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val context: Context? = null,
    private val onPermissionRequest: ((String) -> CommandAllowlist.PermissionResult)? = null
) : DeclarativeTool<CommandExecutionToolParams, ToolResult>() {
    
    override val name = "execute_command"
    override val displayName = "ExecuteCommand"
    override val description = "Executes a shell command with allowlist checking. Commands not in allowlist require user approval."
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = FunctionParameters(
                type = "object",
                properties = mapOf(
                    "command" to PropertySchema(
                        type = "string",
                        description = "The command to execute"
                    ),
                    "description" to PropertySchema(
                        type = "string",
                        description = "Optional description of what this command does"
                    ),
                    "dir_path" to PropertySchema(
                        type = "string",
                        description = "Optional directory to execute command in"
                    ),
                    "timeout" to PropertySchema(
                        type = "number",
                        description = "Optional timeout in seconds (default: 60)"
                    ),
                    "analyze_output" to PropertySchema(
                        type = "boolean",
                        description = "If true, output will be analyzed by AI (default: false)"
                    ),
                    "requires_approval" to PropertySchema(
                        type = "boolean",
                        description = "If false, skip allowlist check (use with caution, default: true)"
                    )
                ),
                required = listOf("command")
            )
        )
    }
    
    override fun createInvocation(
        params: CommandExecutionToolParams,
        workspaceRoot: String
    ): ToolInvocation<CommandExecutionToolParams, ToolResult> {
        return CommandExecutionToolInvocation(
            toolParams = params,
            workspaceRoot = workspaceRoot,
            context = context,
            onPermissionRequest = onPermissionRequest
        )
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): CommandExecutionToolParams {
        return CommandExecutionToolParams(
            command = params["command"] as? String ?: throw IllegalArgumentException("command is required"),
            description = params["description"] as? String,
            dir_path = params["dir_path"] as? String,
            timeout = (params["timeout"] as? Number)?.toLong(),
            analyze_output = params["analyze_output"] as? Boolean ?: false,
            requires_approval = params["requires_approval"] as? Boolean ?: true
        )
    }
}
