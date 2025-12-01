package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.debug.ExecutionStateTracker
import com.qali.aterm.agent.ppe.Observability
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * Execution trace tool
 * Generates detailed execution traces with API calls, tool executions, and variable changes
 */
data class ExecutionTraceToolParams(
    val operationId: String? = null, // Specific operation to trace, or null for all
    val format: String? = null, // "json", "text", "html", "all"
    val outputPath: String? = null, // Path to save trace file, or null for in-memory
    val includeVariables: Boolean = true, // Include variable changes in trace
    val includeApiCalls: Boolean = true, // Include API call details
    val includeToolCalls: Boolean = true // Include tool execution details
)

data class ExecutionTrace(
    val operationId: String,
    val startTime: Long,
    val endTime: Long?,
    val duration: Long,
    val scriptPath: String?,
    val turns: List<TraceTurn>,
    val apiCalls: List<TraceApiCall>,
    val toolCalls: List<TraceToolCall>,
    val variableChanges: List<TraceVariableChange>,
    val summary: TraceSummary
)

data class TraceTurn(
    val turnNumber: Int,
    val timestamp: Long,
    val messages: Int,
    val instructions: Int
)

data class TraceApiCall(
    val timestamp: Long,
    val provider: String?,
    val model: String?,
    val tokens: Int,
    val cost: Double,
    val duration: Long?,
    val success: Boolean,
    val error: String?
)

data class TraceToolCall(
    val timestamp: Long,
    val toolName: String,
    val args: Map<String, Any>,
    val duration: Long?,
    val success: Boolean?,
    val error: String?,
    val resultSize: Int
)

data class TraceVariableChange(
    val timestamp: Long,
    val variableName: String,
    val oldValue: Any?,
    val newValue: Any?,
    val changeType: String // "set", "update", "delete"
)

data class TraceSummary(
    val totalTurns: Int,
    val totalApiCalls: Int,
    val totalToolCalls: Int,
    val totalVariableChanges: Int,
    val totalTokens: Long,
    val totalCost: Double,
    val totalErrors: Int,
    val totalDuration: Long
)

class ExecutionTraceToolInvocation(
    toolParams: ExecutionTraceToolParams,
    private val workspaceRoot: String
) : ToolInvocation<ExecutionTraceToolParams, ToolResult> {
    
    override val params: ExecutionTraceToolParams = toolParams
    
    override fun getDescription(): String {
        val operationId = params.operationId ?: "all operations"
        return "Generating execution trace for: $operationId"
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
                llmContent = "Trace generation cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            updateOutput?.invoke("📊 Collecting trace data...")
            
            val traces = if (params.operationId != null) {
                listOf(generateTrace(params.operationId))
            } else {
                // Generate traces for all active operations
                ExecutionStateTracker.getAllActiveExecutions().keys.map { opId ->
                    generateTrace(opId)
                }
            }
            
            if (traces.isEmpty()) {
                return@withContext ToolResult(
                    llmContent = "No execution traces found",
                    returnDisplay = "No traces available"
                )
            }
            
            updateOutput?.invoke("📝 Formatting trace...")
            val format = params.format?.lowercase() ?: "text"
            
            val formattedTraces = when (format) {
                "json" -> formatAsJson(traces)
                "html" -> formatAsHtml(traces)
                "text" -> formatAsText(traces)
                "all" -> formatAsAll(traces)
                else -> formatAsText(traces)
            }
            
            // Save to file if output path is provided
            val savedPath = params.outputPath?.let { path ->
                updateOutput?.invoke("💾 Saving trace to file...")
                saveTraceToFile(formattedTraces, format, path)
            }
            
            val result = buildString {
                appendLine("# Execution Trace")
                appendLine()
                if (savedPath != null) {
                    appendLine("**Trace saved to:** `$savedPath`")
                    appendLine()
                }
                appendLine("## Summary")
                traces.forEach { trace ->
                    appendLine("### Operation: ${trace.operationId}")
                    appendLine("- **Duration:** ${trace.duration}ms")
                    appendLine("- **Turns:** ${trace.summary.totalTurns}")
                    appendLine("- **API Calls:** ${trace.summary.totalApiCalls}")
                    appendLine("- **Tool Calls:** ${trace.summary.totalToolCalls}")
                    appendLine("- **Variable Changes:** ${trace.summary.totalVariableChanges}")
                    appendLine("- **Total Tokens:** ${trace.summary.totalTokens}")
                    appendLine("- **Total Cost:** $${String.format("%.4f", trace.summary.totalCost)}")
                    appendLine("- **Errors:** ${trace.summary.totalErrors}")
                    appendLine()
                }
                if (format == "text" || format == "all") {
                    appendLine("## Detailed Trace")
                    appendLine()
                    appendLine(formattedTraces)
                }
            }
            
            DebugLogger.i("ExecutionTraceTool", "Trace generated", mapOf(
                "operation_id" to (params.operationId ?: "all"),
                "format" to format,
                "traces" to traces.size
            ))
            
            ToolResult(
                llmContent = result,
                returnDisplay = "Trace: ${traces.size} operation(s), ${traces.sumOf { it.summary.totalApiCalls }} API calls, ${traces.sumOf { it.summary.totalToolCalls }} tool calls"
            )
        } catch (e: Exception) {
            DebugLogger.e("ExecutionTraceTool", "Error generating trace", exception = e)
            ToolResult(
                llmContent = "Error generating trace: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private suspend fun generateTrace(operationId: String): ExecutionTrace {
        val state = ExecutionStateTracker.getExecutionState(operationId)
        val metrics = Observability.getOperationMetrics(operationId)
        
        val startTime = state?.startTime ?: System.currentTimeMillis()
        val endTime = metrics?.endTime
        val duration = if (endTime != null) endTime - startTime else System.currentTimeMillis() - startTime
        
        // Collect turns
        val turns = mutableListOf<TraceTurn>()
        if (state != null) {
            for (i in 1..state.totalTurns) {
                turns.add(TraceTurn(
                    turnNumber = i,
                    timestamp = startTime + (i * 1000L), // Estimate
                    messages = 0, // Would need to track this
                    instructions = 0 // Would need to track this
                ))
            }
        }
        
        // Collect API calls (from Observability)
        val apiCalls = mutableListOf<TraceApiCall>()
        if (params.includeApiCalls && metrics != null) {
            // Note: Observability doesn't expose individual API call details
            // This is a simplified version
            if (metrics.apiCalls > 0) {
                apiCalls.add(TraceApiCall(
                    timestamp = startTime,
                    provider = null,
                    model = null,
                    tokens = metrics.tokensUsed,
                    cost = metrics.cost,
                    duration = if (endTime != null) duration else null,
                    success = metrics.errors == 0,
                    error = if (metrics.errors > 0) "Error occurred" else null
                ))
            }
        }
        
        // Collect tool calls
        val toolCalls = mutableListOf<TraceToolCall>()
        if (params.includeToolCalls && state != null) {
            state.toolCalls.forEach { toolCall ->
                toolCalls.add(TraceToolCall(
                    timestamp = toolCall.timestamp,
                    toolName = toolCall.name,
                    args = toolCall.args,
                    duration = toolCall.duration,
                    success = toolCall.success,
                    error = toolCall.error,
                    resultSize = 0 // Would need to track this
                ))
            }
        }
        
        // Collect variable changes
        val variableChanges = mutableListOf<TraceVariableChange>()
        if (params.includeVariables && state != null) {
            // Track variable changes (simplified - would need history tracking)
            state.variables.forEach { (name, value) ->
                variableChanges.add(TraceVariableChange(
                    timestamp = System.currentTimeMillis(),
                    variableName = name,
                    oldValue = null, // Would need to track previous values
                    newValue = value,
                    changeType = "set"
                ))
            }
        }
        
        val summary = TraceSummary(
            totalTurns = state?.totalTurns ?: 0,
            totalApiCalls = metrics?.apiCalls ?: 0,
            totalToolCalls = state?.toolCalls?.size ?: 0,
            totalVariableChanges = variableChanges.size,
            totalTokens = metrics?.tokensUsed?.toLong() ?: 0L,
            totalCost = metrics?.cost ?: 0.0,
            totalErrors = metrics?.errors ?: 0,
            totalDuration = duration
        )
        
        return ExecutionTrace(
            operationId = operationId,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            scriptPath = state?.scriptPath,
            turns = turns,
            apiCalls = apiCalls,
            toolCalls = toolCalls,
            variableChanges = variableChanges,
            summary = summary
        )
    }
    
    private fun formatAsJson(traces: List<ExecutionTrace>): String {
        val jsonArray = JSONArray()
        traces.forEach { trace ->
            val traceObj = JSONObject()
            traceObj.put("operationId", trace.operationId)
            traceObj.put("startTime", trace.startTime)
            traceObj.put("endTime", trace.endTime)
            traceObj.put("duration", trace.duration)
            traceObj.put("scriptPath", trace.scriptPath)
            
            val turnsArray = JSONArray()
            trace.turns.forEach { turn ->
                val turnObj = JSONObject()
                turnObj.put("turnNumber", turn.turnNumber)
                turnObj.put("timestamp", turn.timestamp)
                turnObj.put("messages", turn.messages)
                turnObj.put("instructions", turn.instructions)
                turnsArray.put(turnObj)
            }
            traceObj.put("turns", turnsArray)
            
            val apiCallsArray = JSONArray()
            trace.apiCalls.forEach { apiCall ->
                val apiObj = JSONObject()
                apiObj.put("timestamp", apiCall.timestamp)
                apiObj.put("provider", apiCall.provider)
                apiObj.put("model", apiCall.model)
                apiObj.put("tokens", apiCall.tokens)
                apiObj.put("cost", apiCall.cost)
                apiObj.put("duration", apiCall.duration)
                apiObj.put("success", apiCall.success)
                apiObj.put("error", apiCall.error)
                apiCallsArray.put(apiObj)
            }
            traceObj.put("apiCalls", apiCallsArray)
            
            val toolCallsArray = JSONArray()
            trace.toolCalls.forEach { toolCall ->
                val toolObj = JSONObject()
                toolObj.put("timestamp", toolCall.timestamp)
                toolObj.put("toolName", toolCall.toolName)
                toolObj.put("args", JSONObject(toolCall.args))
                toolObj.put("duration", toolCall.duration)
                toolObj.put("success", toolCall.success)
                toolObj.put("error", toolCall.error)
                toolObj.put("resultSize", toolCall.resultSize)
                toolCallsArray.put(toolObj)
            }
            traceObj.put("toolCalls", toolCallsArray)
            
            val summaryObj = JSONObject()
            summaryObj.put("totalTurns", trace.summary.totalTurns)
            summaryObj.put("totalApiCalls", trace.summary.totalApiCalls)
            summaryObj.put("totalToolCalls", trace.summary.totalToolCalls)
            summaryObj.put("totalVariableChanges", trace.summary.totalVariableChanges)
            summaryObj.put("totalTokens", trace.summary.totalTokens)
            summaryObj.put("totalCost", trace.summary.totalCost)
            summaryObj.put("totalErrors", trace.summary.totalErrors)
            summaryObj.put("totalDuration", trace.summary.totalDuration)
            traceObj.put("summary", summaryObj)
            
            jsonArray.put(traceObj)
        }
        
        return jsonArray.toString(2)
    }
    
    private fun formatAsText(traces: List<ExecutionTrace>): String {
        val sb = StringBuilder()
        traces.forEach { trace ->
            sb.appendLine("=".repeat(80))
            sb.appendLine("Execution Trace: ${trace.operationId}")
            sb.appendLine("=".repeat(80))
            sb.appendLine()
            
            sb.appendLine("Summary:")
            sb.appendLine("  Start Time: ${formatTimestamp(trace.startTime)}")
            if (trace.endTime != null) {
                sb.appendLine("  End Time: ${formatTimestamp(trace.endTime)}")
            }
            sb.appendLine("  Duration: ${trace.duration}ms")
            sb.appendLine("  Script: ${trace.scriptPath ?: "N/A"}")
            sb.appendLine()
            
            sb.appendLine("Statistics:")
            sb.appendLine("  Turns: ${trace.summary.totalTurns}")
            sb.appendLine("  API Calls: ${trace.summary.totalApiCalls}")
            sb.appendLine("  Tool Calls: ${trace.summary.totalToolCalls}")
            sb.appendLine("  Variable Changes: ${trace.summary.totalVariableChanges}")
            sb.appendLine("  Total Tokens: ${trace.summary.totalTokens}")
            sb.appendLine("  Total Cost: $${String.format("%.4f", trace.summary.totalCost)}")
            sb.appendLine("  Errors: ${trace.summary.totalErrors}")
            sb.appendLine()
            
            if (trace.toolCalls.isNotEmpty()) {
                sb.appendLine("Tool Calls:")
                trace.toolCalls.forEach { toolCall ->
                    sb.appendLine("  [${formatTimestamp(toolCall.timestamp)}] ${toolCall.toolName}")
                    sb.appendLine("    Args: ${toolCall.args}")
                    if (toolCall.duration != null) {
                        sb.appendLine("    Duration: ${toolCall.duration}ms")
                    }
                    sb.appendLine("    Success: ${toolCall.success ?: "unknown"}")
                    if (toolCall.error != null) {
                        sb.appendLine("    Error: ${toolCall.error}")
                    }
                    sb.appendLine()
                }
            }
            
            if (trace.apiCalls.isNotEmpty()) {
                sb.appendLine("API Calls:")
                trace.apiCalls.forEach { apiCall ->
                    sb.appendLine("  [${formatTimestamp(apiCall.timestamp)}] ${apiCall.provider ?: "unknown"}")
                    sb.appendLine("    Model: ${apiCall.model ?: "unknown"}")
                    sb.appendLine("    Tokens: ${apiCall.tokens}")
                    sb.appendLine("    Cost: $${String.format("%.4f", apiCall.cost)}")
                    if (apiCall.duration != null) {
                        sb.appendLine("    Duration: ${apiCall.duration}ms")
                    }
                    sb.appendLine("    Success: ${apiCall.success}")
                    if (apiCall.error != null) {
                        sb.appendLine("    Error: ${apiCall.error}")
                    }
                    sb.appendLine()
                }
            }
            
            if (trace.variableChanges.isNotEmpty()) {
                sb.appendLine("Variable Changes:")
                trace.variableChanges.take(50).forEach { change ->
                    sb.appendLine("  [${formatTimestamp(change.timestamp)}] ${change.variableName}")
                    sb.appendLine("    Type: ${change.changeType}")
                    if (change.oldValue != null) {
                        sb.appendLine("    Old: ${truncate(change.oldValue.toString(), 100)}")
                    }
                    sb.appendLine("    New: ${truncate(change.newValue?.toString() ?: "null", 100)}")
                    sb.appendLine()
                }
                if (trace.variableChanges.size > 50) {
                    sb.appendLine("  ... and ${trace.variableChanges.size - 50} more")
                }
            }
            
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    private fun formatAsHtml(traces: List<ExecutionTrace>): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html>")
        sb.appendLine("<head>")
        sb.appendLine("  <title>Execution Trace</title>")
        sb.appendLine("  <style>")
        sb.appendLine("    body { font-family: monospace; margin: 20px; }")
        sb.appendLine("    h1 { color: #333; }")
        sb.appendLine("    h2 { color: #666; margin-top: 30px; }")
        sb.appendLine("    table { border-collapse: collapse; width: 100%; margin: 20px 0; }")
        sb.appendLine("    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
        sb.appendLine("    th { background-color: #f2f2f2; }")
        sb.appendLine("    .success { color: green; }")
        sb.appendLine("    .error { color: red; }")
        sb.appendLine("  </style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("  <h1>Execution Trace</h1>")
        
        traces.forEach { trace ->
            sb.appendLine("  <h2>Operation: ${trace.operationId}</h2>")
            sb.appendLine("  <p><strong>Duration:</strong> ${trace.duration}ms</p>")
            sb.appendLine("  <p><strong>Script:</strong> ${trace.scriptPath ?: "N/A"}</p>")
            
            sb.appendLine("  <h3>Summary</h3>")
            sb.appendLine("  <table>")
            sb.appendLine("    <tr><th>Metric</th><th>Value</th></tr>")
            sb.appendLine("    <tr><td>Turns</td><td>${trace.summary.totalTurns}</td></tr>")
            sb.appendLine("    <tr><td>API Calls</td><td>${trace.summary.totalApiCalls}</td></tr>")
            sb.appendLine("    <tr><td>Tool Calls</td><td>${trace.summary.totalToolCalls}</td></tr>")
            sb.appendLine("    <tr><td>Total Tokens</td><td>${trace.summary.totalTokens}</td></tr>")
            sb.appendLine("    <tr><td>Total Cost</td><td>$${String.format("%.4f", trace.summary.totalCost)}</td></tr>")
            sb.appendLine("    <tr><td>Errors</td><td>${trace.summary.totalErrors}</td></tr>")
            sb.appendLine("  </table>")
            
            if (trace.toolCalls.isNotEmpty()) {
                sb.appendLine("  <h3>Tool Calls</h3>")
                sb.appendLine("  <table>")
                sb.appendLine("    <tr><th>Time</th><th>Tool</th><th>Duration</th><th>Status</th></tr>")
                trace.toolCalls.forEach { toolCall ->
                    val statusClass = if (toolCall.success == true) "success" else "error"
                    sb.appendLine("    <tr>")
                    sb.appendLine("      <td>${formatTimestamp(toolCall.timestamp)}</td>")
                    sb.appendLine("      <td>${toolCall.toolName}</td>")
                    sb.appendLine("      <td>${toolCall.duration ?: "N/A"}ms</td>")
                    sb.appendLine("      <td class=\"$statusClass\">${toolCall.success ?: "unknown"}</td>")
                    sb.appendLine("    </tr>")
                }
                sb.appendLine("  </table>")
            }
        }
        
        sb.appendLine("</body>")
        sb.appendLine("</html>")
        return sb.toString()
    }
    
    private fun formatAsAll(traces: List<ExecutionTrace>): String {
        return buildString {
            appendLine("# Execution Trace (All Formats)")
            appendLine()
            appendLine("## Text Format")
            appendLine()
            appendLine("```")
            appendLine(formatAsText(traces))
            appendLine("```")
            appendLine()
            appendLine("## JSON Format")
            appendLine()
            appendLine("```json")
            appendLine(formatAsJson(traces))
            appendLine("```")
        }
    }
    
    private fun saveTraceToFile(content: String, format: String, path: String): String {
        val file = if (File(path).isAbsolute) {
            File(path)
        } else {
            File(workspaceRoot, path)
        }
        
        // Create parent directories if needed
        file.parentFile?.mkdirs()
        
        // Determine file extension
        val extension = when (format) {
            "json" -> "json"
            "html" -> "html"
            "text", "all" -> "txt"
            else -> "txt"
        }
        
        val finalFile = if (file.extension.isEmpty()) {
            File(file.parent, "${file.name}.$extension")
        } else {
            file
        }
        
        FileWriter(finalFile).use { writer ->
            writer.write(content)
        }
        
        return finalFile.absolutePath
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timestamp))
    }
    
    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength) + "..."
        } else {
            text
        }
    }
}

/**
 * Execution trace tool
 */
class ExecutionTraceTool(
    private val workspaceRoot: String
) : DeclarativeTool<ExecutionTraceToolParams, ToolResult>() {
    
    override val name: String = "generate_execution_trace"
    override val displayName: String = "Execution Trace"
    override val description: String = """
        Generate detailed execution traces for debugging. Records all API calls, tool executions, and variable changes with timestamps.
        
        Features:
        - Record all API calls with timestamps, tokens, and costs
        - Record all tool executions with arguments and results
        - Record variable changes over time
        - Export traces in multiple formats (JSON, text, HTML)
        - Save traces to files for later analysis
        
        Formats:
        - json: Structured JSON format for programmatic analysis
        - text: Human-readable text format
        - html: HTML format for viewing in browsers
        - all: All formats combined
        
        Examples:
        - generate_execution_trace() - Generate trace for all operations
        - generate_execution_trace(operationId="op-123") - Generate trace for specific operation
        - generate_execution_trace(format="json", outputPath="trace.json") - Save as JSON
        - generate_execution_trace(format="html", outputPath="trace.html") - Save as HTML
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "operationId" to PropertySchema(
                type = "string",
                description = "Specific operation ID to trace, or omit for all operations"
            ),
            "format" to PropertySchema(
                type = "string",
                description = "Output format: 'json', 'text', 'html', or 'all'",
                enum = listOf("json", "text", "html", "all")
            ),
            "outputPath" to PropertySchema(
                type = "string",
                description = "Path to save trace file (relative to workspace or absolute)"
            ),
            "includeVariables" to PropertySchema(
                type = "boolean",
                description = "Include variable changes in trace (default: true)"
            ),
            "includeApiCalls" to PropertySchema(
                type = "boolean",
                description = "Include API call details (default: true)"
            ),
            "includeToolCalls" to PropertySchema(
                type = "boolean",
                description = "Include tool execution details (default: true)"
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
        params: ExecutionTraceToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ExecutionTraceToolParams, ToolResult> {
        return ExecutionTraceToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ExecutionTraceToolParams {
        return ExecutionTraceToolParams(
            operationId = params["operationId"] as? String,
            format = params["format"] as? String,
            outputPath = params["outputPath"] as? String,
            includeVariables = params["includeVariables"] as? Boolean ?: true,
            includeApiCalls = params["includeApiCalls"] as? Boolean ?: true,
            includeToolCalls = params["includeToolCalls"] as? Boolean ?: true
        )
    }
}
