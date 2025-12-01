package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.debug.ExecutionStateTracker
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Variable inspector tool
 * Inspects and modifies execution variables for debugging
 */
data class VariableInspectorToolParams(
    val operationId: String? = null, // Specific operation to inspect, or null for current
    val action: String, // "list", "inspect", "modify", "export"
    val variableName: String? = null, // Variable name for inspect/modify
    val newValue: String? = null, // New value for modify (JSON string)
    val outputPath: String? = null // Path to export variables (for export action)
)

data class VariableInfo(
    val name: String,
    val value: Any?,
    val type: String,
    val size: Int? = null, // For strings/collections
    val preview: String // Truncated preview of value
)

class VariableInspectorToolInvocation(
    toolParams: VariableInspectorToolParams,
    private val workspaceRoot: String
) : ToolInvocation<VariableInspectorToolParams, ToolResult> {
    
    override val params: VariableInspectorToolParams = toolParams
    
    override fun getDescription(): String {
        return when (params.action) {
            "list" -> "Listing all variables"
            "inspect" -> "Inspecting variable: ${params.variableName}"
            "modify" -> "Modifying variable: ${params.variableName}"
            "export" -> "Exporting variable state"
            else -> "Inspecting variables"
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
                llmContent = "Variable inspection cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            val state = ExecutionStateTracker.getExecutionState(params.operationId)
            
            if (state == null) {
                return@withContext ToolResult(
                    llmContent = "No execution state found${if (params.operationId != null) " for operation: ${params.operationId}" else ""}",
                    returnDisplay = "Error: No execution state",
                    error = ToolError(
                        message = "No execution state found",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            val result = when (params.action) {
                "list" -> listVariables(state)
                "inspect" -> inspectVariable(state, params.variableName)
                "modify" -> modifyVariable(state, params.variableName, params.newValue)
                "export" -> exportVariables(state, params.outputPath)
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
            
            DebugLogger.i("VariableInspectorTool", "Variable inspection completed", mapOf(
                "action" to params.action,
                "operation_id" to state.operationId,
                "variable_count" to state.variables.size
            ))
            
            result
        } catch (e: Exception) {
            DebugLogger.e("VariableInspectorTool", "Error inspecting variables", exception = e)
            ToolResult(
                llmContent = "Error inspecting variables: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun listVariables(state: ExecutionStateTracker.ExecutionState): ToolResult {
        val variables = state.variables.map { (name, value) ->
            VariableInfo(
                name = name,
                value = value,
                type = getTypeName(value),
                size = getSize(value),
                preview = getPreview(value)
            )
        }.sortedBy { it.name }
        
        val output = buildString {
            appendLine("# Variables List")
            appendLine()
            appendLine("**Operation ID:** ${state.operationId}")
            appendLine("**Total Variables:** ${variables.size}")
            appendLine()
            
            if (variables.isEmpty()) {
                appendLine("No variables found.")
            } else {
                appendLine("## Variables")
                appendLine()
                appendLine("| Name | Type | Size | Preview |")
                appendLine("|------|------|------|---------|")
                variables.forEach { variable ->
                    appendLine("| `${variable.name}` | ${variable.type} | ${variable.size ?: "N/A"} | ${variable.preview} |")
                }
                appendLine()
                
                appendLine("## Variable Details")
                appendLine()
                variables.forEach { variable ->
                    appendLine("### `${variable.name}`")
                    appendLine("- **Type:** ${variable.type}")
                    if (variable.size != null) {
                        appendLine("- **Size:** ${variable.size}")
                    }
                    appendLine("- **Value:**")
                    appendLine("```")
                    appendLine(formatValue(variable.value))
                    appendLine("```")
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Found ${variables.size} variables"
        )
    }
    
    private fun inspectVariable(
        state: ExecutionStateTracker.ExecutionState,
        variableName: String?
    ): ToolResult {
        if (variableName == null) {
            return ToolResult(
                llmContent = "Variable name is required for inspect action",
                returnDisplay = "Error: Variable name required",
                error = ToolError(
                    message = "Variable name is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        val value = state.variables[variableName]
        
        if (value == null) {
            return ToolResult(
                llmContent = "Variable `$variableName` not found. Available variables: ${state.variables.keys.joinToString(", ")}",
                returnDisplay = "Variable not found",
                error = ToolError(
                    message = "Variable not found: $variableName",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        val variableInfo = VariableInfo(
            name = variableName,
            value = value,
            type = getTypeName(value),
            size = getSize(value),
            preview = getPreview(value)
        )
        
        val output = buildString {
            appendLine("# Variable Inspection: `$variableName`")
            appendLine()
            appendLine("**Operation ID:** ${state.operationId}")
            appendLine()
            appendLine("## Variable Information")
            appendLine()
            appendLine("- **Name:** `$variableName`")
            appendLine("- **Type:** ${variableInfo.type}")
            if (variableInfo.size != null) {
                appendLine("- **Size:** ${variableInfo.size}")
            }
            appendLine()
            appendLine("## Value")
            appendLine()
            appendLine("```")
            appendLine(formatValue(value))
            appendLine("```")
            appendLine()
            
            // Additional analysis
            when {
                value is String -> {
                    appendLine("## String Analysis")
                    appendLine()
                    appendLine("- **Length:** ${value.length} characters")
                    appendLine("- **Lines:** ${value.lines().size}")
                    appendLine("- **Words:** ${value.split(Regex("\\s+")).filter { it.isNotEmpty() }.size}")
                    if (value.length > 1000) {
                        appendLine("- **Note:** Large string value (truncated in preview)")
                    }
                    appendLine()
                }
                value is Map<*, *> -> {
                    appendLine("## Map Analysis")
                    appendLine()
                    appendLine("- **Keys:** ${value.keys.joinToString(", ")}")
                    appendLine("- **Size:** ${value.size} entries")
                    appendLine()
                }
                value is List<*> -> {
                    appendLine("## List Analysis")
                    appendLine()
                    appendLine("- **Size:** ${value.size} items")
                    if (value.isNotEmpty()) {
                        appendLine("- **First Item Type:** ${getTypeName(value.first())}")
                    }
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Variable: $variableName (${variableInfo.type})"
        )
    }
    
    private fun modifyVariable(
        state: ExecutionStateTracker.ExecutionState,
        variableName: String?,
        newValue: String?
    ): ToolResult {
        if (variableName == null) {
            return ToolResult(
                llmContent = "Variable name is required for modify action",
                returnDisplay = "Error: Variable name required",
                error = ToolError(
                    message = "Variable name is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        if (newValue == null) {
            return ToolResult(
                llmContent = "New value is required for modify action",
                returnDisplay = "Error: New value required",
                error = ToolError(
                    message = "New value is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            }
        }
        
        val oldValue = state.variables[variableName]
        
        if (oldValue == null) {
            return ToolResult(
                llmContent = "Variable `$variableName` not found. Cannot modify non-existent variable.",
                returnDisplay = "Variable not found",
                error = ToolError(
                    message = "Variable not found: $variableName",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Parse and validate new value
        val parsedValue = try {
            parseValue(newValue, oldValue)
        } catch (e: Exception) {
            return ToolResult(
                llmContent = "Failed to parse new value: ${e.message}. Old value type: ${getTypeName(oldValue)}",
                returnDisplay = "Error: Invalid value format",
                error = ToolError(
                    message = "Failed to parse value: ${e.message}",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Validate type compatibility
        val oldType = getTypeName(oldValue)
        val newType = getTypeName(parsedValue)
        
        if (oldType != newType && !isCompatibleType(oldType, newType)) {
            return ToolResult(
                llmContent = "Type mismatch: Cannot change variable type from $oldType to $newType. Old value: ${getPreview(oldValue)}",
                returnDisplay = "Error: Type mismatch",
                error = ToolError(
                    message = "Type mismatch: $oldType -> $newType",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Update variable in ExecutionStateTracker
        val updatedVariables = state.variables.toMutableMap()
        updatedVariables[variableName] = parsedValue
        ExecutionStateTracker.updateVariables(state.operationId, updatedVariables)
        
        val output = buildString {
            appendLine("# Variable Modified: `$variableName`")
            appendLine()
            appendLine("**Operation ID:** ${state.operationId}")
            appendLine()
            appendLine("## Change Summary")
            appendLine()
            appendLine("- **Variable:** `$variableName`")
            appendLine("- **Type:** $oldType (unchanged)")
            appendLine()
            appendLine("### Old Value")
            appendLine("```")
            appendLine(formatValue(oldValue))
            appendLine("```")
            appendLine()
            appendLine("### New Value")
            appendLine("```")
            appendLine(formatValue(parsedValue))
            appendLine("```")
            appendLine()
            appendLine("✅ Variable updated successfully")
        }
        
        DebugLogger.i("VariableInspectorTool", "Variable modified", mapOf(
            "operation_id" to state.operationId,
            "variable_name" to variableName,
            "old_type" to oldType,
            "new_type" to newType
        ))
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Variable $variableName updated"
        )
    }
    
    private fun exportVariables(
        state: ExecutionStateTracker.ExecutionState,
        outputPath: String?
    ): ToolResult {
        val json = JSONObject()
        json.put("operationId", state.operationId)
        json.put("scriptPath", state.scriptPath)
        json.put("currentTurn", state.currentTurn)
        json.put("totalTurns", state.totalTurns)
        json.put("startTime", state.startTime)
        
        val variablesObj = JSONObject()
        state.variables.forEach { (name, value) ->
            variablesObj.put(name, value.toString())
        }
        json.put("variables", variablesObj)
        
        val jsonString = json.toString(2)
        
        val savedPath = outputPath?.let { path ->
            val file = if (File(path).isAbsolute) {
                File(path)
            } else {
                File(workspaceRoot, path)
            }
            
            // Create parent directories if needed
            file.parentFile?.mkdirs()
            
            // Ensure .json extension
            val finalFile = if (file.extension.isEmpty()) {
                File(file.parent, "${file.name}.json")
            } else {
                file
            }
            
            FileWriter(finalFile).use { writer ->
                writer.write(jsonString)
            }
            
            finalFile.absolutePath
        }
        
        val output = buildString {
            appendLine("# Variable State Export")
            appendLine()
            appendLine("**Operation ID:** ${state.operationId}")
            appendLine("**Variables Count:** ${state.variables.size}")
            if (savedPath != null) {
                appendLine("**Saved To:** `$savedPath`")
            }
            appendLine()
            appendLine("## Variables")
            appendLine()
            appendLine("```json")
            appendLine(jsonString)
            appendLine("```")
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = if (savedPath != null) "Exported to $savedPath" else "Exported (in-memory)"
        )
    }
    
    private fun getTypeName(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "String"
            is Int -> "Int"
            is Long -> "Long"
            is Double -> "Double"
            is Float -> "Float"
            is Boolean -> "Boolean"
            is Map<*, *> -> "Map"
            is List<*> -> "List"
            is Set<*> -> "Set"
            is Array<*> -> "Array"
            else -> value.javaClass.simpleName
        }
    }
    
    private fun getSize(value: Any?): Int? {
        return when (value) {
            is String -> value.length
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            is Array<*> -> value.size
            else -> null
        }
    }
    
    private fun getPreview(value: Any?): String {
        val preview = formatValue(value)
        return if (preview.length > 50) {
            preview.take(47) + "..."
        } else {
            preview
        }
    }
    
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value
            is Map<*, *> -> {
                val entries = value.entries.take(10).joinToString(", ") { (k, v) ->
                    "$k: ${getPreview(v)}"
                }
                "{ $entries${if (value.size > 10) ", ..." else ""} }"
            }
            is List<*> -> {
                val items = value.take(10).joinToString(", ") { getPreview(it) }
                "[ $items${if (value.size > 10) ", ..." else ""} ]"
            }
            is Set<*> -> {
                val items = value.take(10).joinToString(", ") { getPreview(it) }
                "{ $items${if (value.size > 10) ", ..." else ""} }"
            }
            else -> value.toString()
        }
    }
    
    private fun parseValue(newValue: String, oldValue: Any?): Any {
        // Try to parse as JSON first
        return try {
            val json = JSONObject(newValue)
            // Convert JSON to appropriate type based on old value
            when (oldValue) {
                is String -> json.optString("value", newValue)
                is Int -> json.optInt("value", newValue.toIntOrNull() ?: throw IllegalArgumentException("Invalid integer"))
                is Long -> json.optLong("value", newValue.toLongOrNull() ?: throw IllegalArgumentException("Invalid long"))
                is Double -> json.optDouble("value", newValue.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid double"))
                is Boolean -> json.optBoolean("value", newValue.toBoolean())
                else -> newValue // Default to string
            }
        } catch (e: Exception) {
            // Not JSON, try to parse based on old value type
            when (oldValue) {
                is String -> newValue
                is Int -> newValue.toIntOrNull() ?: throw IllegalArgumentException("Invalid integer: $newValue")
                is Long -> newValue.toLongOrNull() ?: throw IllegalArgumentException("Invalid long: $newValue")
                is Double -> newValue.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid double: $newValue")
                is Boolean -> newValue.toBoolean()
                else -> newValue // Default to string
            }
        }
    }
    
    private fun isCompatibleType(oldType: String, newType: String): Boolean {
        // Allow some type conversions
        return when {
            oldType == "String" && newType != "String" -> false // String is strict
            oldType == "Int" && newType == "Long" -> true // Int -> Long is OK
            oldType == "Long" && newType == "Int" -> true // Long -> Int is OK (with potential loss)
            oldType == "Double" && newType == "Int" -> true // Double -> Int is OK
            oldType == "Double" && newType == "Long" -> true // Double -> Long is OK
            oldType == "Float" && newType == "Double" -> true // Float -> Double is OK
            else -> oldType == newType
        }
    }
}

/**
 * Variable inspector tool
 */
class VariableInspectorTool(
    private val workspaceRoot: String
) : DeclarativeTool<VariableInspectorToolParams, ToolResult>() {
    
    override val name: String = "inspect_variables"
    override val displayName: String = "Variable Inspector"
    override val description: String = """
        Inspect and modify execution variables for debugging. Allows viewing, inspecting, modifying, and exporting variable state.
        
        Actions:
        - list: List all current variables with types and previews
        - inspect: Inspect a specific variable in detail
        - modify: Modify a variable value (with type validation)
        - export: Export all variables to JSON file
        
        Features:
        - View all variables with types and sizes
        - Inspect variable values with detailed analysis
        - Modify variables with type validation
        - Export variable state to JSON
        - Type compatibility checking
        - Value parsing and validation
        
        Examples:
        - inspect_variables(action="list") - List all variables
        - inspect_variables(action="inspect", variableName="RESPONSE") - Inspect specific variable
        - inspect_variables(action="modify", variableName="counter", newValue="42") - Modify variable
        - inspect_variables(action="export", outputPath="variables.json") - Export to file
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "operationId" to PropertySchema(
                type = "string",
                description = "Specific operation ID to inspect, or omit for current operation"
            ),
            "action" to PropertySchema(
                type = "string",
                description = "Action to perform: 'list', 'inspect', 'modify', or 'export'",
                enum = listOf("list", "inspect", "modify", "export")
            ),
            "variableName" to PropertySchema(
                type = "string",
                description = "Variable name (required for inspect/modify actions)"
            ),
            "newValue" to PropertySchema(
                type = "string",
                description = "New value for variable (required for modify action, can be JSON or plain value)"
            ),
            "outputPath" to PropertySchema(
                type = "string",
                description = "Path to save exported variables (for export action, relative to workspace or absolute)"
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
        params: VariableInspectorToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<VariableInspectorToolParams, ToolResult> {
        return VariableInspectorToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): VariableInspectorToolParams {
        return VariableInspectorToolParams(
            operationId = params["operationId"] as? String,
            action = params["action"] as? String ?: "list",
            variableName = params["variableName"] as? String,
            newValue = params["newValue"] as? String,
            outputPath = params["outputPath"] as? String
        )
    }
}
