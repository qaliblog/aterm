package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.debug.ExecutionStateTracker
import com.qali.aterm.agent.ppe.Observability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Debug tool that allows AI to inspect execution state, variables, and history
 * Provides comprehensive debugging information without modifying execution
 */
data class DebugToolParams(
    val inspect: String? = null, // "variables", "history", "tools", "metrics", "all"
    val operationId: String? = null, // Specific operation to inspect, or null for current
    val variableName: String? = null // Specific variable to inspect
)

class DebugToolInvocation(
    toolParams: DebugToolParams,
    private val workspaceRoot: String
) : ToolInvocation<DebugToolParams, ToolResult> {
    
    override val params: DebugToolParams = toolParams
    
    override fun getDescription(): String {
        val inspect = params.inspect ?: "all"
        return "Inspecting execution state: $inspect"
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
                llmContent = "Debug inspection cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            updateOutput?.invoke("🔍 Gathering debug information...")
            
            val inspect = params.inspect?.lowercase() ?: "all"
            val operationId = params.operationId
            val state = ExecutionStateTracker.getExecutionState(operationId)
            
            val debugInfo = buildString {
                appendLine("# Debug Information")
                appendLine()
                
                when (inspect) {
                    "variables" -> {
                        appendLine(inspectVariables(state, params.variableName))
                    }
                    "history" -> {
                        appendLine(inspectHistory(state))
                    }
                    "tools" -> {
                        appendLine(inspectTools(state))
                    }
                    "metrics" -> {
                        appendLine(inspectMetrics(state, operationId))
                    }
                    "all" -> {
                        appendLine(inspectAll(state, operationId))
                    }
                    else -> {
                        appendLine("Unknown inspect type: $inspect")
                        appendLine("Available types: variables, history, tools, metrics, all")
                    }
                }
            }
            
            updateOutput?.invoke("✅ Debug information gathered")
            
            DebugLogger.d("DebugTool", "Debug inspection completed", mapOf(
                "inspect" to inspect,
                "operation_id" to (operationId ?: "current")
            ))
            
            ToolResult(
                llmContent = debugInfo,
                returnDisplay = "Debug information retrieved"
            )
        } catch (e: Exception) {
            DebugLogger.e("DebugTool", "Error during debug inspection", exception = e)
            ToolResult(
                llmContent = "Error gathering debug information: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private suspend fun inspectVariables(
        state: ExecutionStateTracker.ExecutionState?,
        variableName: String?
    ): String {
        return buildString {
            appendLine("## Execution Variables")
            appendLine()
            
            if (state == null) {
                appendLine("No active execution found.")
                return@buildString
            }
            
            if (variableName != null) {
                // Inspect specific variable
                val value = state.variables[variableName]
                if (value != null) {
                    appendLine("**Variable: `$variableName`**")
                    appendLine()
                    appendLine("```")
                    appendLine(formatValue(value))
                    appendLine("```")
                } else {
                    appendLine("Variable `$variableName` not found.")
                    appendLine()
                    appendLine("Available variables:")
                    state.variables.keys.forEach { key ->
                        appendLine("- `$key`")
                    }
                }
            } else {
                // List all variables
                if (state.variables.isEmpty()) {
                    appendLine("No variables set.")
                } else {
                    appendLine("**All Variables:**")
                    appendLine()
                    state.variables.forEach { (key, value) ->
                        appendLine("### `$key`")
                        appendLine("```")
                        appendLine(formatValue(value))
                        appendLine("```")
                        appendLine()
                    }
                }
            }
        }
    }
    
    private fun inspectHistory(state: ExecutionStateTracker.ExecutionState?): String {
        return buildString {
            appendLine("## Chat History")
            appendLine()
            
            if (state == null) {
                appendLine("No active execution found.")
                return@buildString
            }
            
            val history = state.chatHistory
            if (history.isEmpty()) {
                appendLine("No chat history available.")
            } else {
                appendLine("**Total Messages: ${history.size}**")
                appendLine()
                
                history.forEachIndexed { index, content ->
                    appendLine("### Message ${index + 1} (${content.role})")
                    content.parts.forEach { part ->
                        when (part) {
                            is com.qali.aterm.agent.core.Part.TextPart -> {
                                val text = part.text
                                val preview = if (text.length > 200) {
                                    text.take(200) + "..."
                                } else {
                                    text
                                }
                                appendLine("```")
                                appendLine(preview)
                                appendLine("```")
                            }
                            is com.qali.aterm.agent.core.Part.FunctionResponsePart -> {
                                appendLine("**Function Response:** ${part.functionResponse.name}")
                            }
                            else -> {
                                appendLine("**Part:** ${part.javaClass.simpleName}")
                            }
                        }
                    }
                    appendLine()
                }
            }
        }
    }
    
    private fun inspectTools(state: ExecutionStateTracker.ExecutionState?): String {
        return buildString {
            appendLine("## Tool Execution History")
            appendLine()
            
            if (state == null) {
                appendLine("No active execution found.")
                return@buildString
            }
            
            val toolCalls = state.toolCalls
            if (toolCalls.isEmpty()) {
                appendLine("No tools executed yet.")
            } else {
                appendLine("**Total Tool Calls: ${toolCalls.size}**")
                appendLine()
                
                toolCalls.forEachIndexed { index, toolCall ->
                    appendLine("### Tool Call ${index + 1}: `${toolCall.name}`")
                    appendLine()
                    appendLine("- **Timestamp:** ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(toolCall.timestamp))}")
                    toolCall.duration?.let {
                        appendLine("- **Duration:** ${it}ms")
                    }
                    toolCall.success?.let {
                        appendLine("- **Success:** $it")
                    }
                    toolCall.error?.let {
                        appendLine("- **Error:** $it")
                    }
                    appendLine("- **Arguments:**")
                    appendLine("```json")
                    appendLine(JSONObject(toolCall.args).toString(2))
                    appendLine("```")
                    appendLine()
                }
            }
        }
    }
    
    private suspend fun inspectMetrics(
        state: ExecutionStateTracker.ExecutionState?,
        operationId: String?
    ): String {
        return buildString {
            appendLine("## Execution Metrics")
            appendLine()
            
            val actualOperationId = operationId ?: state?.operationId
            if (actualOperationId != null) {
                val metrics = ExecutionStateTracker.getOperationMetrics(actualOperationId)
                if (metrics != null) {
                    appendLine("### Operation: $actualOperationId")
                    appendLine()
                    appendLine("- **Type:** ${metrics.operationType}")
                    appendLine("- **Start Time:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(metrics.startTime))}")
                    metrics.endTime?.let {
                        appendLine("- **End Time:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it))}")
                        val duration = it - metrics.startTime
                        appendLine("- **Duration:** ${duration}ms (${duration / 1000.0}s)")
                    }
                    appendLine("- **API Calls:** ${metrics.apiCalls}")
                    appendLine("- **Tool Calls:** ${metrics.toolCalls}")
                    appendLine("- **Tokens Used:** ${metrics.tokensUsed}")
                    appendLine("- **Cost:** $${String.format("%.4f", metrics.cost)}")
                    appendLine("- **Errors:** ${metrics.errors}")
                } else {
                    appendLine("No metrics available for operation: $actualOperationId")
                }
            } else {
                appendLine("No operation ID specified and no active execution found.")
            }
            
            appendLine()
            appendLine("### Global Statistics")
            appendLine()
            val globalStats = ExecutionStateTracker.getGlobalStats()
            globalStats.forEach { (key, value) ->
                appendLine("- **$key:** $value")
            }
        }
    }
    
    private suspend fun inspectAll(
        state: ExecutionStateTracker.ExecutionState?,
        operationId: String?
    ): String {
        return buildString {
            appendLine(inspectVariables(state, null))
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(inspectHistory(state))
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(inspectTools(state))
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(inspectMetrics(state, operationId))
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Script Information")
            appendLine()
            if (state != null) {
                appendLine("- **Script Path:** ${state.scriptPath ?: "Inline script"}")
                appendLine("- **Current Turn:** ${state.currentTurn}/${state.totalTurns}")
                appendLine("- **Execution Time:** ${(System.currentTimeMillis() - state.startTime) / 1000.0}s")
            } else {
                appendLine("No active execution found.")
            }
        }
    }
    
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> JSONObject(value as Map<*, *>).toString(2)
            is List<*> -> value.joinToString(", ")
            else -> value.toString()
        }
    }
}

/**
 * Debug tool for inspecting execution state
 */
class DebugTool : DeclarativeTool<DebugToolParams, ToolResult>() {
    
    override val name: String = "debug"
    override val displayName: String = "Debug Inspector"
    override val description: String = """
        Inspect execution state, variables, chat history, tool calls, and metrics.
        Useful for debugging agent behavior and understanding current execution context.
        
        Parameters:
        - inspect: What to inspect - "variables", "history", "tools", "metrics", or "all" (default: "all")
        - operationId: Specific operation ID to inspect, or null for current execution
        - variableName: Specific variable name to inspect (only used when inspect="variables")
        
        Examples:
        - debug(inspect="variables") - Show all variables
        - debug(inspect="variables", variableName="RESPONSE") - Show specific variable
        - debug(inspect="history") - Show chat history
        - debug(inspect="tools") - Show tool execution history
        - debug(inspect="metrics") - Show execution metrics
        - debug(inspect="all") - Show everything
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "inspect" to PropertySchema(
                type = "string",
                description = "What to inspect: 'variables', 'history', 'tools', 'metrics', or 'all'",
                enum = listOf("variables", "history", "tools", "metrics", "all")
            ),
            "operationId" to PropertySchema(
                type = "string",
                description = "Specific operation ID to inspect, or omit for current execution"
            ),
            "variableName" to PropertySchema(
                type = "string",
                description = "Specific variable name to inspect (only used when inspect='variables')"
            )
        ),
        required = emptyList()
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: DebugToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<DebugToolParams, ToolResult> {
        // Workspace root is not needed for debug tool, but we need to pass it
        // Use a default value since debug tool doesn't access files
        return DebugToolInvocation(params, "/tmp")
    }
    
    /**
     * Create invocation with workspace root
     */
    fun createInvocationWithWorkspace(
        params: DebugToolParams,
        workspaceRoot: String
    ): ToolInvocation<DebugToolParams, ToolResult> {
        return DebugToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): DebugToolParams {
        return DebugToolParams(
            inspect = params["inspect"] as? String,
            operationId = params["operationId"] as? String,
            variableName = params["variableName"] as? String
        )
    }
}
