package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.ppe.models.*
import com.qali.aterm.agent.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import android.util.Log

/**
 * Execution engine for PPE scripts
 * Handles script execution, template rendering, AI calls, and tool execution
 */
class PpeExecutionEngine(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String
) {
    private val apiClient = PpeApiClient(toolRegistry)
    private val scriptCache = mutableMapOf<String, PpeScript>()
    
    // Track tool results for file diff extraction (queue-based to handle multiple calls)
    private val toolResultQueue = mutableListOf<Pair<String, com.qali.aterm.agent.tools.ToolResult>>()
    
    /**
     * Execute a PPE script with given input parameters
     */
    suspend fun executeScript(
        script: PpeScript,
        inputParams: Map<String, Any> = emptyMap(),
        onChunk: (String) -> Unit = {},
        onToolCall: (FunctionCall) -> Unit = {},
        onToolResult: (String, Map<String, Any>) -> Unit = {}
    ): Flow<PpeExecutionResult> = flow {
        try {
            // Merge script parameters with input params (input takes precedence)
            val variables = mutableMapOf<String, Any>().apply {
                putAll(script.parameters)
                putAll(inputParams)
            }
            
            // Track execution state
            val chatHistory = mutableListOf<Content>()
            var currentVariables = variables.toMutableMap()
            
            // Execute each turn in sequence
            for (turnIndex in script.turns.indices) {
                val turn = script.turns[turnIndex]
                
                // Process messages in this turn
                val turnMessages = mutableListOf<Content>()
                var hasAiPlaceholderInTurn = false
                
                for (message in turn.messages) {
                    // Process message with all replacements (scripts, instructions, regex, etc.)
                    val processedContent = PpeMessageProcessor.processMessage(
                        message,
                        currentVariables,
                        this@PpeExecutionEngine,
                        script.sourcePath
                    )
                    
                    // Check if message has AI placeholder
                    if (message.hasAiPlaceholder) {
                        hasAiPlaceholderInTurn = true
                        // Execute AI call
                        val aiResponse = executeAiPlaceholder(
                            message,
                            processedContent,
                            chatHistory,
                            script,
                            onChunk,
                            onToolCall,
                            onToolResult
                        )
                        
                        // Store AI response in variable
                        val varName = message.aiPlaceholderVar ?: "LatestResult"
                        currentVariables[varName] = aiResponse.text
                        // Also store as RESPONSE (default variable)
                        currentVariables["RESPONSE"] = aiResponse.text
                        
                        // Add to chat history (replace AI placeholder with response)
                        val finalContent = processedContent.replace(Regex("""\[\[.*?\]\]"""), aiResponse.text)
                        turnMessages.add(
                            Content(
                                role = message.role,
                                parts = listOf(Part.TextPart(text = finalContent))
                            )
                        )
                        
                        // Handle function calls from AI response
                        if (aiResponse.functionCalls.isNotEmpty()) {
                            for (functionCall in aiResponse.functionCalls) {
                                onToolCall(functionCall)
                                
                                // Execute tool
                                val toolResult = executeTool(functionCall, onToolResult)
                                
                                // Add tool result to chat history
                                turnMessages.add(
                                    Content(
                                        role = "user",
                                        parts = listOf(
                                            Part.FunctionResponsePart(
                                                functionResponse = FunctionResponse(
                                                    name = functionCall.name,
                                                    response = when {
                                                        toolResult.error != null -> mapOf("error" to toolResult.error.message)
                                                        toolResult.llmContent is String -> mapOf("output" to toolResult.llmContent)
                                                        else -> mapOf("output" to "Tool execution succeeded.")
                                                    },
                                                    id = functionCall.id ?: ""
                                                )
                                            )
                                        )
                                    )
                                )
                                
                                // Continue conversation with tool result
                                val continuationResponse = continueWithToolResult(
                                    chatHistory + turnMessages,
                                    functionCall,
                                    toolResult,
                                    script,
                                    onChunk,
                                    onToolCall,
                                    onToolResult
                                )
                                
                                if (continuationResponse != null) {
                                    currentVariables["LatestResult"] = continuationResponse.text
                                    turnMessages.add(
                                        Content(
                                            role = "model",
                                            parts = listOf(Part.TextPart(text = continuationResponse.text))
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Regular message - use processed content
                        turnMessages.add(
                            Content(
                                role = message.role,
                                parts = listOf(Part.TextPart(text = processedContent))
                            )
                        )
                    }
                }
                
                // Add turn messages to chat history
                chatHistory.addAll(turnMessages)
                
                // Execute control flow blocks first
                for (block in turn.controlFlowBlocks) {
                    executeControlFlowBlock(block, currentVariables, onChunk, onToolCall, onToolResult)
                }
                
                // Execute instructions in this turn
                for (instruction in turn.instructions) {
                    executeInstruction(instruction, currentVariables, onChunk)
                }
                
                // Handle agent chaining
                if (turn.chainTo != null) {
                    val chainedScript = loadChainedScript(turn.chainTo, script.sourcePath)
                    if (chainedScript != null) {
                        // Prepare chain parameters
                        val chainParams = turn.chainParams ?: emptyMap()
                        val chainInput = mutableMapOf<String, Any>().apply {
                            // Pass current result as content by default
                            val latestResult = currentVariables["LatestResult"] ?: currentVariables["RESPONSE"] ?: ""
                            put("content", latestResult.toString())
                            putAll(chainParams)
                        }
                        
                        // Execute chained script recursively
                        var chainedFinalResult = ""
                        executeScript(
                            chainedScript,
                            chainInput,
                            onChunk,
                            onToolCall,
                            onToolResult
                        ).collect { result ->
                            if (result.success) {
                                chainedFinalResult = result.finalResult
                            }
                        }
                        
                        // Update variables with chained result
                        currentVariables["LatestResult"] = chainedFinalResult
                        currentVariables["RESPONSE"] = chainedFinalResult
                    }
                }
                
                // Auto-run LLM if prompt available and no AI placeholder was called
                if (!hasAiPlaceholderInTurn && script.autoRunLLMIfPromptAvailable && turn.messages.isNotEmpty()) {
                    // Check if we have user messages but no AI response
                    val hasUserMessage = turn.messages.any { it.role == "user" }
                    if (hasUserMessage && chatHistory.isNotEmpty()) {
                        // Auto-execute AI on the last user message
                        val lastUserMessage = turn.messages.lastOrNull { it.role == "user" }
                        if (lastUserMessage != null) {
                        val processedContent = PpeMessageProcessor.processMessage(
                            lastUserMessage,
                            currentVariables,
                            this@PpeExecutionEngine,
                            script.sourcePath
                        )
                            
                            // Create a message with AI placeholder
                            val autoMessage = lastUserMessage.copy(
                                hasAiPlaceholder = true,
                                aiPlaceholderVar = "RESPONSE"
                            )
                            
                            val aiResponse = executeAiPlaceholder(
                                autoMessage,
                                processedContent,
                                chatHistory,
                                script,
                                onChunk,
                                onToolCall,
                                onToolResult
                            )
                            
                            currentVariables["RESPONSE"] = aiResponse.text
                            currentVariables["LatestResult"] = aiResponse.text
                            
                            turnMessages.add(
                                Content(
                                    role = "model",
                                    parts = listOf(Part.TextPart(text = aiResponse.text))
                                )
                            )
                            chatHistory.addAll(turnMessages)
                        }
                    }
                }
            }
            
            // Final result - use RESPONSE if available, else LatestResult
            val finalResult = currentVariables["RESPONSE"]?.toString() 
                ?: currentVariables["LatestResult"]?.toString() 
                ?: ""
            
            emit(PpeExecutionResult(
                success = true,
                finalResult = finalResult,
                variables = currentVariables,
                chatHistory = chatHistory
            ))
            
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Script execution failed", e)
            emit(PpeExecutionResult(
                success = false,
                finalResult = "",
                variables = emptyMap(),
                chatHistory = emptyList(),
                error = e.message
            ))
        }
    }
    
    /**
     * Execute AI placeholder ([[AI]] or [[AI:model='...']])
     */
    private suspend fun executeAiPlaceholder(
        message: PpeMessage,
        processedContent: String,
        chatHistory: List<Content>,
        script: PpeScript,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): PpeApiResponse {
        // Build messages for API call
        val messages = mutableListOf<Content>()
        
        // Add system message if present in script
        script.turns.firstOrNull()?.messages?.firstOrNull { it.role == "system" }?.let { systemMsg ->
            val renderedSystem = PpeTemplateEngine.render(systemMsg.content, emptyMap())
            messages.add(Content(role = "user", parts = listOf(Part.TextPart(text = renderedSystem))))
        }
        
        // Add chat history
        messages.addAll(chatHistory)
        
        // Add current message (without AI placeholder)
        val messageWithoutPlaceholder = processedContent.replace(Regex("""\[\[.*?\]\]"""), "")
        if (messageWithoutPlaceholder.isNotEmpty()) {
            messages.add(Content(role = message.role, parts = listOf(Part.TextPart(text = messageWithoutPlaceholder))))
        }
        
        // Get model from placeholder params or script
        val model = message.aiPlaceholderParams?.get("model")?.toString()
        val temperature = message.aiPlaceholderParams?.get("temperature")?.toString()?.toDoubleOrNull()
        val topP = message.aiPlaceholderParams?.get("top_p")?.toString()?.toDoubleOrNull()
        val topK = message.aiPlaceholderParams?.get("top_k")?.toString()?.toIntOrNull()
        
        // Handle constrained AI responses
        val constrainedOptions = message.constrainedOptions
        val constrainedCount = message.constrainedCount
        val constrainedRandom = message.constrainedRandom
        
        // If constrained options are specified, modify the message to include them
        var finalMessageContent = messageWithoutPlaceholder
        if (constrainedOptions != null && constrainedOptions.isNotEmpty()) {
            // Add constraint to message
            val optionsText = constrainedOptions.joinToString("|")
            val countText = if (constrainedCount != null) ":$constrainedCount" else ""
            val randomText = if (constrainedRandom) ":random" else ""
            finalMessageContent += "\n\nYou must choose from these options only: $optionsText$countText$randomText"
        }
        
        // Update message content if modified
        if (finalMessageContent != messageWithoutPlaceholder && messages.isNotEmpty()) {
            messages[messages.size - 1] = Content(
                role = message.role,
                parts = listOf(Part.TextPart(text = finalMessageContent))
            )
        }
        
        // Get tools from registry
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        // Make API call (non-streaming) with all parameters
        val result = apiClient.callApi(
            messages = messages,
            model = model,
            temperature = temperature,
            topP = topP,
            topK = topK,
            tools = tools
        )
        
        var apiResponse = result.getOrElse {
            throw Exception("API call failed: ${it.message}")
        }
        
        // Handle constrained responses - validate and filter
        if (constrainedOptions != null && constrainedOptions.isNotEmpty() && !constrainedRandom) {
            // Validate response matches one of the options
            val responseText = apiResponse.text.trim()
            val matchedOption = constrainedOptions.find { option ->
                responseText.equals(option, ignoreCase = true) || 
                responseText.contains(option, ignoreCase = true)
            }
            
            if (matchedOption != null) {
                // Use matched option
                apiResponse = apiResponse.copy(text = matchedOption)
            } else if (constrainedOptions.size == 1) {
                // Single option, use it
                apiResponse = apiResponse.copy(text = constrainedOptions[0])
            }
        } else if (constrainedRandom && constrainedOptions != null && constrainedOptions.isNotEmpty()) {
            // Random selection (local, not AI)
            val selected = constrainedOptions.random()
            apiResponse = apiResponse.copy(text = selected)
        }
        
        return apiResponse
    }
    
    /**
     * Continue conversation after tool execution
     */
    private suspend fun continueWithToolResult(
        chatHistory: List<Content>,
        functionCall: FunctionCall,
        toolResult: com.qali.aterm.agent.tools.ToolResult,
        script: PpeScript,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): PpeApiResponse? {
        // Add tool result to history and continue
        val messages = chatHistory.toMutableList()
        
        // Make continuation API call
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        val result = apiClient.callApi(messages, null, null, tools)
        
        return result.getOrNull()
    }
    
    /**
     * Execute a tool
     */
    private suspend fun executeTool(
        functionCall: FunctionCall,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): com.qali.aterm.agent.tools.ToolResult {
        val tool = toolRegistry.getTool(functionCall.name)
        if (tool == null) {
            return com.qali.aterm.agent.tools.ToolResult(
                llmContent = "Tool not found: ${functionCall.name}",
                error = com.qali.aterm.agent.tools.ToolError(
                    message = "Tool not found: ${functionCall.name}",
                    type = com.qali.aterm.agent.tools.ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Validate and convert parameters
        val params = tool?.validateParams(functionCall.args)
        if (params == null) {
            return com.qali.aterm.agent.tools.ToolResult(
                llmContent = "Invalid parameters for tool: ${functionCall.name}",
                error = com.qali.aterm.agent.tools.ToolError(
                    message = "Invalid parameters",
                    type = com.qali.aterm.agent.tools.ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Create invocation and execute
        val invocation = tool?.createInvocation(params)
        val result = invocation?.execute() ?: com.qali.aterm.agent.tools.ToolResult(
            llmContent = "Failed to execute tool: ${functionCall.name}",
            error = com.qali.aterm.agent.tools.ToolError(
                message = "Execution failed",
                type = com.qali.aterm.agent.tools.ToolErrorType.EXECUTION_ERROR
            )
        )
        
        // Store result in queue for file diff extraction (FIFO)
        toolResultQueue.add(Pair(functionCall.name, result))
        
        // Notify callback
        onToolResult(functionCall.name, functionCall.args)
        
        return result
    }
    
    /**
     * Execute an instruction (e.g., $echo, $set, $print)
     */
    fun executeInstruction(
        instruction: PpeInstruction,
        variables: MutableMap<String, Any>,
        onChunk: (String) -> Unit
    ) {
        when (instruction.name) {
            "echo" -> {
                val value = instruction.args["value"]?.toString() ?: instruction.rawContent ?: ""
                val isTemplateRef = instruction.args["isTemplateRef"] as? Boolean ?: false
                val rendered = if (isTemplateRef) {
                    // ?= prefix means template variable reference
                    val varName = value.trim()
                    getNestedValue(varName, variables)?.toString() ?: ""
                } else {
                    PpeTemplateEngine.render(value, variables)
                }
                onChunk(rendered)
            }
            "set" -> {
                // $set: key=value or $set: key: value or $set(key=value)
                val content = instruction.rawContent ?: instruction.args["value"]?.toString() ?: ""
                if (content.isNotEmpty()) {
                    val parts = content.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().removeSurrounding("'").removeSurrounding("\"")
                        val value = PpeTemplateEngine.render(parts[1].trim(), variables)
                        variables[key] = value
                    } else {
                        // Try key: value format
                        val colonParts = content.split(":", limit = 2)
                        if (colonParts.size == 2) {
                            val key = colonParts[0].trim().removeSurrounding("'").removeSurrounding("\"")
                            val value = PpeTemplateEngine.render(colonParts[1].trim(), variables)
                            variables[key] = value
                        }
                    }
                } else {
                    // Check args for key-value pairs
                    instruction.args.forEach { (key, value) ->
                        if (key != "value" && key != "isTemplateRef") {
                            variables[key] = value
                        }
                    }
                }
            }
            "print" -> {
                val value = variables["LatestResult"]?.toString() ?: ""
                onChunk(value)
            }
            // Add more instructions as needed
        }
    }
    
    /**
     * Execute instruction for replacement (returns result as string)
     */
    suspend fun executeInstructionForReplacement(
        instruction: PpeInstruction,
        variables: Map<String, Any>,
        onResult: (String) -> Unit
    ) {
        val vars = variables.toMutableMap()
        executeInstruction(instruction, vars) { output ->
            onResult(output)
        }
    }
    
    /**
     * Execute control flow block ($if, $while, etc.)
     */
    private suspend fun executeControlFlowBlock(
        block: PpeControlFlowBlock,
        variables: MutableMap<String, Any>,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ) {
        when (block.type) {
            "if" -> {
                val conditionResult = evaluateCondition(block.condition ?: "", variables)
                if (conditionResult) {
                    block.thenBlock?.forEach { instruction ->
                        executeInstruction(instruction, variables, onChunk)
                    }
                } else {
                    block.elseBlock?.forEach { instruction ->
                        executeInstruction(instruction, variables, onChunk)
                    }
                }
            }
            "while" -> {
                var conditionResult = evaluateCondition(block.condition ?: "", variables)
                var iterations = 0
                val maxIterations = 1000 // Prevent infinite loops
                
                while (conditionResult && iterations < maxIterations) {
                    block.doBlock?.forEach { instruction ->
                        executeInstruction(instruction, variables, onChunk)
                    }
                    conditionResult = evaluateCondition(block.condition ?: "", variables)
                    iterations++
                }
            }
            "for" -> {
                // Similar to while but with iteration variable
                var conditionResult = evaluateCondition(block.condition ?: "", variables)
                var iterations = 0
                val maxIterations = 1000
                
                while (conditionResult && iterations < maxIterations) {
                    block.doBlock?.forEach { instruction ->
                        executeInstruction(instruction, variables, onChunk)
                    }
                    conditionResult = evaluateCondition(block.condition ?: "", variables)
                    iterations++
                }
            }
            "match" -> {
                val expression = block.condition ?: ""
                val expressionValue = evaluateExpression(expression, variables)
                val caseKey = expressionValue.toString()
                
                block.cases?.get(caseKey)?.forEach { instruction ->
                    executeInstruction(instruction, variables, onChunk)
                }
            }
            "pipe" -> {
                // Execute pipe chain
                block.pipeChain?.forEach { chainItem ->
                    if (chainItem.startsWith("$")) {
                        val instruction = PpeScriptParserEnhanced.parseInstruction("$chainItem")
                        if (instruction != null) {
                            executeInstruction(instruction, variables, onChunk)
                        }
                    } else {
                        // Script chaining
                        val script = loadChainedScript(chainItem, null)
                        if (script != null) {
                            executeScript(
                                script = script,
                                inputParams = variables.toMap(),
                                onChunk = onChunk,
                                onToolCall = onToolCall,
                                onToolResult = onToolResult
                            ).collect { }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Evaluate a condition expression
     */
    private fun evaluateCondition(condition: String, variables: Map<String, Any>): Boolean {
        // Simple condition evaluation
        // Support: variable, !variable, variable === value, variable !== value, etc.
        val trimmed = condition.trim()
        
        // Check for !==
        if (trimmed.contains("!==")) {
            val parts = trimmed.split("!==", limit = 2)
            val left = evaluateExpression(parts[0].trim(), variables)
            val right = evaluateExpression(parts[1].trim(), variables)
            return left != right
        }
        
        // Check for ===
        if (trimmed.contains("===")) {
            val parts = trimmed.split("===", limit = 2)
            val left = evaluateExpression(parts[0].trim(), variables)
            val right = evaluateExpression(parts[1].trim(), variables)
            return left == right
        }
        
        // Check for !=
        if (trimmed.contains("!=")) {
            val parts = trimmed.split("!=", limit = 2)
            val left = evaluateExpression(parts[0].trim(), variables)
            val right = evaluateExpression(parts[1].trim(), variables)
            return left != right
        }
        
        // Check for ==
        if (trimmed.contains("==")) {
            val parts = trimmed.split("==", limit = 2)
            val left = evaluateExpression(parts[0].trim(), variables)
            val right = evaluateExpression(parts[1].trim(), variables)
            return left == right
        }
        
        // Check for negation
        if (trimmed.startsWith("!")) {
            val expr = trimmed.substring(1).trim()
            val value = evaluateExpression(expr, variables)
            return !value.toBoolean()
        }
        
        // Simple variable check
        val value = evaluateExpression(trimmed, variables)
        return value.toBoolean()
    }
    
    /**
     * Evaluate an expression
     */
    private fun evaluateExpression(expression: String, variables: Map<String, Any>): Any {
        val trimmed = expression.trim().removeSurrounding("\"").removeSurrounding("'")
        
        // Check if it's a variable
        if (trimmed.matches(Regex("""^[a-zA-Z_][a-zA-Z0-9_.]*$"""))) {
            return getNestedValue(trimmed, variables) ?: ""
        }
        
        // Check if it's a boolean
        if (trimmed == "true") return true
        if (trimmed == "false") return false
        
        // Check if it's a number
        trimmed.toDoubleOrNull()?.let { return it }
        trimmed.toIntOrNull()?.let { return it }
        
        // Return as string
        return trimmed
    }
    
    /**
     * Get nested value using dot notation
     */
    private fun getNestedValue(path: String, variables: Map<String, Any>): Any? {
        val parts = path.split(".")
        var current: Any? = variables
        
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                else -> null
            }
            if (current == null) break
        }
        
        return current
    }
    
    /**
     * Convert value to boolean
     */
    private fun Any.toBoolean(): Boolean {
        return when (this) {
            is Boolean -> this
            is String -> this.isNotEmpty() && this.lowercase() != "false" && this != "0"
            is Number -> this.toDouble() != 0.0
            null -> false
            else -> true
        }
    }
    
    /**
     * Load a chained script
     */
    private fun loadChainedScript(scriptName: String, currentScriptPath: String?): PpeScript? {
        return try {
            // Try to find script in same directory or search paths
            val scriptPath = when {
                currentScriptPath != null -> {
                    val currentDir = java.io.File(currentScriptPath).parentFile
                    java.io.File(currentDir, "$scriptName.ai.yaml")
                }
                else -> java.io.File("$scriptName.ai.yaml")
            }
            
            if (scriptPath.exists()) {
                PpeScriptLoader.loadScript(scriptPath)
            } else {
                Log.w("PpeExecutionEngine", "Chained script not found: $scriptName")
                null
            }
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Failed to load chained script: $scriptName", e)
            null
        }
    }
    
    /**
     * Get and remove next tool result for a tool (for file diff extraction)
     * Uses FIFO queue to handle multiple calls of the same tool
     */
    fun getNextToolResult(toolName: String): com.qali.aterm.agent.tools.ToolResult? {
        val index = toolResultQueue.indexOfFirst { it.first == toolName }
        return if (index >= 0) {
            val result = toolResultQueue[index].second
            toolResultQueue.removeAt(index)
            result
        } else {
            null
        }
    }
}

/**
 * Result of PPE script execution
 */
data class PpeExecutionResult(
    val success: Boolean,
    val finalResult: String,
    val variables: Map<String, Any>,
    val chatHistory: List<Content>,
    val error: String? = null
)
