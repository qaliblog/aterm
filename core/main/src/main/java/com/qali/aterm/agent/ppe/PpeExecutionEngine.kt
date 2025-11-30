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
        onToolResult: (String, Map<String, Any>) -> Unit = { _, _ -> }
    ): Flow<PpeExecutionResult> = flow {
        val executionStartTime = System.currentTimeMillis()
        var lastActivityTime = executionStartTime
        var turnCount = 0
        var aiCallCount = 0
        var toolExecutionCount = 0
        
        try {
            Log.d("PpeExecutionEngine", "Starting script execution (turns: ${script.turns.size})")
            
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
                turnCount++
                lastActivityTime = System.currentTimeMillis()
                val turn = script.turns[turnIndex]
                Log.d("PpeExecutionEngine", "Executing turn $turnCount/${script.turns.size} (messages: ${turn.messages.size}, instructions: ${turn.instructions.size})")
                
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
                        aiCallCount++
                        lastActivityTime = System.currentTimeMillis()
                        val timeSinceStart = lastActivityTime - executionStartTime
                        Log.d("PpeExecutionEngine", "Executing AI placeholder call #$aiCallCount (turn $turnCount, time: ${timeSinceStart}ms)")
                        // Execute AI call - include current turn's messages processed so far
                        val aiResponse = executeAiPlaceholder(
                            message,
                            processedContent,
                            chatHistory + turnMessages, // Include current turn's messages
                            script,
                            onChunk,
                            onToolCall,
                            onToolResult
                        )
                        val aiResponseTime = System.currentTimeMillis()
                        Log.d("PpeExecutionEngine", "AI call #$aiCallCount completed (response length: ${aiResponse.text.length}, function calls: ${aiResponse.functionCalls.size}, time: ${aiResponseTime - lastActivityTime}ms)")
                        
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
                            Log.d("PpeExecutionEngine", "Processing ${aiResponse.functionCalls.size} function calls from AI response")
                            for (functionCallIndex in aiResponse.functionCalls.indices) {
                                val functionCall = aiResponse.functionCalls[functionCallIndex]
                                toolExecutionCount++
                                lastActivityTime = System.currentTimeMillis()
                                Log.d("PpeExecutionEngine", "Executing tool #$toolExecutionCount: ${functionCall.name} (call ${functionCallIndex + 1}/${aiResponse.functionCalls.size})")
                                onToolCall(functionCall)
                                
                                // Execute tool
                                val toolStartTime = System.currentTimeMillis()
                                val toolResult = executeTool(functionCall, onToolResult)
                                val toolExecutionTime = System.currentTimeMillis() - toolStartTime
                                Log.d("PpeExecutionEngine", "Tool #$toolExecutionCount (${functionCall.name}) completed (time: ${toolExecutionTime}ms, error: ${toolResult.error != null})")
                                
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
                                    Log.d("PpeExecutionEngine", "Continuation response received - text length: ${continuationResponse.text.length}, function calls: ${continuationResponse.functionCalls.size}")
                                    currentVariables["LatestResult"] = continuationResponse.text
                                    currentVariables["RESPONSE"] = continuationResponse.text
                                    
                                    // Emit continuation response text to UI (if not already emitted by continueWithToolResult)
                                    // Note: continueWithToolResult already emits chunks, but we ensure it's in turnMessages for history
                                    turnMessages.add(
                                        Content(
                                            role = "model",
                                            parts = listOf(Part.TextPart(text = continuationResponse.text))
                                        )
                                    )
                                    
                                    // Check if we need to continue further (response has text but no function calls)
                                    val hasTextNoCalls = continuationResponse.text.isNotEmpty() && continuationResponse.functionCalls.isEmpty()
                                    if (hasTextNoCalls) {
                                        Log.d("PpeExecutionEngine", "Continuation response has text but no function calls - will check if further continuation needed")
                                    }
                                    
                                    // Check if response is empty or has no function calls - might need fallback continuation
                                    val needsFallback = continuationResponse.text.isEmpty() && 
                                        continuationResponse.functionCalls.isEmpty() && 
                                        functionCall.name == "write_todos"
                                    
                                    if (needsFallback) {
                                        // Make fallback continuation attempt
                                        val promptMessages = (chatHistory + turnMessages).toMutableList()
                                        promptMessages.add(
                                            Content(
                                                role = "model",
                                                parts = listOf(Part.TextPart(text = "The todo list has been created. Now proceed with implementing the first task. Start by creating the project files (package.json, server.js, etc.). Do not call write_todos again."))
                                            )
                                        )
                                        
                                        val fallbackResult = apiClient.callApi(
                                            messages = promptMessages,
                                            model = null,
                                            temperature = null,
                                            topP = null,
                                            topK = null,
                                            tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                                                listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                                            } else {
                                                null
                                            }
                                        )
                                        
                                        val fallbackResponse = fallbackResult.getOrNull()
                                        if (fallbackResponse != null) {
                                            if (fallbackResponse.text.isNotEmpty()) {
                                                onChunk(fallbackResponse.text)
                                            }
                                            
                                            // Handle function calls from fallback
                                            if (fallbackResponse.functionCalls.isNotEmpty()) {
                                                for (nextFunctionCall in fallbackResponse.functionCalls) {
                                                    // Skip write_todos
                                                    if (nextFunctionCall.name == "write_todos") {
                                                        android.util.Log.w("PpeExecutionEngine", "Skipping write_todos in fallback")
                                                        continue
                                                    }
                                                    
                                                    onToolCall(nextFunctionCall)
                                                    val nextToolResult = executeTool(nextFunctionCall, onToolResult)
                                                    
                                                    val nextContinuation = continueWithToolResult(
                                                        promptMessages + listOf(
                                                            Content(
                                                                role = "model",
                                                                parts = listOf(Part.TextPart(text = fallbackResponse.text))
                                                            )
                                                        ),
                                                        nextFunctionCall,
                                                        nextToolResult,
                                                        script,
                                                        onChunk,
                                                        onToolCall,
                                                        onToolResult,
                                                        recursionDepth = 0
                                                    )
                                                    
                                                    if (nextContinuation != null) {
                                                        currentVariables["LatestResult"] = nextContinuation.text
                                                        currentVariables["RESPONSE"] = nextContinuation.text
                                                        turnMessages.add(
                                                            Content(
                                                                role = "model",
                                                                parts = listOf(Part.TextPart(text = nextContinuation.text))
                                                            )
                                                        )
                                                    }
                                                }
                                            } else {
                                                // Update variables with fallback response
                                                currentVariables["LatestResult"] = fallbackResponse.text
                                                currentVariables["RESPONSE"] = fallbackResponse.text
                                                turnMessages.add(
                                                    Content(
                                                        role = "model",
                                                        parts = listOf(Part.TextPart(text = fallbackResponse.text))
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } else if (functionCall.name == "write_todos") {
                                    // If continuation returned null (possibly due to repeated calls), 
                                    // make one more attempt to prompt continuation
                                    val promptMessages = (chatHistory + turnMessages).toMutableList()
                                    promptMessages.add(
                                        Content(
                                            role = "model",
                                            parts = listOf(Part.TextPart(text = "The todo list has been created. Now proceed with implementing the first task. Start by creating the project files (package.json, server.js, etc.). Do not call write_todos again."))
                                        )
                                    )
                                    
                                    val finalContinueResult = apiClient.callApi(
                                        messages = promptMessages,
                                        model = null,
                                        temperature = null,
                                        topP = null,
                                        topK = null,
                                        tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                                            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                                        } else {
                                            null
                                        }
                                    )
                                    
                                    val finalContinueResponse = finalContinueResult.getOrNull()
                                    if (finalContinueResponse != null) {
                                        if (finalContinueResponse.text.isNotEmpty()) {
                                            onChunk(finalContinueResponse.text)
                                        }
                                        
                                        // Handle function calls
                                        if (finalContinueResponse.functionCalls.isNotEmpty()) {
                                            for (nextFunctionCall in finalContinueResponse.functionCalls) {
                                                // Skip write_todos if it was just called
                                                if (nextFunctionCall.name == "write_todos") {
                                                    android.util.Log.w("PpeExecutionEngine", "Skipping write_todos - already called")
                                                    continue
                                                }
                                                
                                                onToolCall(nextFunctionCall)
                                                val nextToolResult = executeTool(nextFunctionCall, onToolResult)
                                                
                                                // Continue recursively but with limited depth
                                                val nextContinuation = continueWithToolResult(
                                                    promptMessages + listOf(
                                                        Content(
                                                            role = "model",
                                                            parts = listOf(Part.TextPart(text = finalContinueResponse.text))
                                                        )
                                                    ),
                                                    nextFunctionCall,
                                                    nextToolResult,
                                                    script,
                                                    onChunk,
                                                    onToolCall,
                                                    onToolResult,
                                                    recursionDepth = 0 // Start fresh recursion depth
                                                )
                                                
                                                if (nextContinuation != null) {
                                                    currentVariables["LatestResult"] = nextContinuation.text
                                                    currentVariables["RESPONSE"] = nextContinuation.text
                                                    turnMessages.add(
                                                        Content(
                                                            role = "model",
                                                            parts = listOf(Part.TextPart(text = nextContinuation.text))
                                                        )
                                                    )
                                                }
                                            }
                                        } else {
                                            // No function calls but we have text - add it to history
                                            currentVariables["LatestResult"] = finalContinueResponse.text
                                            currentVariables["RESPONSE"] = finalContinueResponse.text
                                            turnMessages.add(
                                                Content(
                                                    role = "model",
                                                    parts = listOf(Part.TextPart(text = finalContinueResponse.text))
                                                )
                                            )
                                        }
                                    }
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
            
            val totalExecutionTime = System.currentTimeMillis() - executionStartTime
            val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
            Log.d("PpeExecutionEngine", "Script execution completed successfully")
            Log.d("PpeExecutionEngine", "Execution summary - Total time: ${totalExecutionTime}ms, Time since last activity: ${timeSinceLastActivity}ms")
            Log.d("PpeExecutionEngine", "Execution stats - Turns: $turnCount, AI calls: $aiCallCount, Tool executions: $toolExecutionCount")
            Log.d("PpeExecutionEngine", "Final result length: ${finalResult.length}")
            
            emit(PpeExecutionResult(
                success = true,
                finalResult = finalResult,
                variables = currentVariables,
                chatHistory = chatHistory
            ))
            
        } catch (e: Exception) {
            val totalExecutionTime = System.currentTimeMillis() - executionStartTime
            val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
            Log.e("PpeExecutionEngine", "Script execution failed", e)
            Log.e("PpeExecutionEngine", "Error details - Total time: ${totalExecutionTime}ms, Time since last activity: ${timeSinceLastActivity}ms")
            Log.e("PpeExecutionEngine", "Error stats - Turns: $turnCount, AI calls: $aiCallCount, Tool executions: $toolExecutionCount")
            Log.e("PpeExecutionEngine", "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
            e.printStackTrace()
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
        
        // Note: System messages are handled by ApiRequestBuilder via systemInstruction field
        // We don't add them to the messages list to avoid confusion
        
        // Add chat history (filter out system messages and empty messages)
        // Note: Assistant messages are kept - they'll be mapped to "model" by ApiRequestBuilder
        messages.addAll(chatHistory.filter { 
            it.role != "system" && 
            it.parts.isNotEmpty() && 
            it.parts.any { part -> 
                when (part) {
                    is Part.TextPart -> part.text.isNotEmpty()
                    else -> true // Keep non-text parts (function calls, etc.)
                }
            }
        })
        
        // Add current message (without AI placeholder)
        // Note: We don't add assistant messages with placeholders - they're just markers for AI response
        val messageWithoutPlaceholder = processedContent.replace(Regex("""\[\[.*?\]\]"""), "")
        if (messageWithoutPlaceholder.isNotEmpty() && message.role != "assistant") {
            messages.add(Content(role = message.role, parts = listOf(Part.TextPart(text = messageWithoutPlaceholder))))
        }
        
        // Safety check: ensure we have at least one message
        if (messages.isEmpty()) {
            throw Exception("Cannot make API call: no messages to send. Ensure user message is included before AI placeholder.")
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
        onToolResult: (String, Map<String, Any>) -> Unit,
        recursionDepth: Int = 0
    ): PpeApiResponse? {
        // Prevent infinite recursion (max 10 levels)
        if (recursionDepth >= 10) {
            android.util.Log.w("PpeExecutionEngine", "Max recursion depth reached in continueWithToolResult")
            return null
        }
        
        // Check for repeated tool calls (prevent loops)
        val recentToolCalls = chatHistory.takeLast(10).flatMap { content ->
            content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
        }
        val sameToolCallCount = recentToolCalls.count { it == functionCall.name }
        if (sameToolCallCount >= 2 && recursionDepth > 0) {
            android.util.Log.w("PpeExecutionEngine", "Detected repeated calls to ${functionCall.name}, stopping recursion")
            // Don't emit a message that might trigger another call - just stop silently
            // The agent should have enough context to proceed
            return null
        }
        
        // Build messages with tool result for continuation
        val messages = chatHistory.toMutableList()
        
        // Add tool result to messages (as function response)
        // Format the response to be clear and actionable
        val responseContent = when {
            toolResult.error != null -> mapOf("error" to toolResult.error.message)
            toolResult.llmContent is String -> {
                // For write_todos, ensure the response is clear that todos were created/updated
                if (functionCall.name == "write_todos") {
                    mapOf(
                        "output" to toolResult.llmContent,
                        "status" to "success",
                        "message" to "Todos have been created/updated. Proceed with implementing the tasks."
                    )
                } else {
                    mapOf("output" to toolResult.llmContent)
                }
            }
            else -> mapOf("output" to "Tool execution succeeded.")
        }
        
        messages.add(
            Content(
                role = "user",
                parts = listOf(
                    Part.FunctionResponsePart(
                        functionResponse = FunctionResponse(
                            name = functionCall.name,
                            response = responseContent,
                            id = functionCall.id ?: ""
                        )
                    )
                )
            )
        )
        
        // Make continuation API call
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        val result = apiClient.callApi(
            messages = messages,
            model = null,
            temperature = null,
            topP = null,
            topK = null,
            tools = tools
        )
        
        val continuationResponse = result.getOrNull()
        
        android.util.Log.d("PpeExecutionEngine", "continueWithToolResult: got continuation response - text length: ${continuationResponse?.text?.length ?: 0}, functionCalls: ${continuationResponse?.functionCalls?.size ?: 0}, recursionDepth: $recursionDepth")
        
        // Emit continuation response chunks to UI
        if (continuationResponse != null) {
            // Always emit text first (even if there are function calls)
            if (continuationResponse.text.isNotEmpty()) {
                onChunk(continuationResponse.text)
            } else if (continuationResponse.functionCalls.isNotEmpty()) {
                // If no text but there are function calls, emit a brief message
                onChunk("Continuing with next steps...\n")
            } else {
                // If response is empty and no function calls, the AI might have stopped
                // This shouldn't happen often, but log it for debugging
                android.util.Log.d("PpeExecutionEngine", "Continuation response is empty with no function calls")
            }
            
            // Handle function calls from continuation response
            if (continuationResponse.functionCalls.isNotEmpty()) {
                android.util.Log.d("PpeExecutionEngine", "Continuation has ${continuationResponse.functionCalls.size} function calls - processing them")
                var executedAnyCall = false
                for (nextFunctionCall in continuationResponse.functionCalls) {
                    // Skip write_todos if it was already called in recent messages
                    val recentToolCalls = messages.takeLast(10).flatMap { content ->
                        content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
                    }
                    val writeTodosCount = recentToolCalls.count { it == "write_todos" }
                    
                    if (nextFunctionCall.name == "write_todos" && writeTodosCount >= 1) {
                        android.util.Log.w("PpeExecutionEngine", "Skipping write_todos in continuation - already called")
                        continue
                    }
                    
                    executedAnyCall = true
                    onToolCall(nextFunctionCall)
                    
                    // Execute tool
                    val nextToolResult = executeTool(nextFunctionCall, onToolResult)
                    
                    // Add the continuation response text to messages before recursive call
                    val updatedMessages = messages + listOf(
                        Content(
                            role = "model",
                            parts = listOf(Part.TextPart(text = continuationResponse.text))
                        )
                    )
                    
                    // Recursively continue with next tool result (increment recursion depth)
                    val nextContinuation = continueWithToolResult(
                        updatedMessages,
                        nextFunctionCall,
                        nextToolResult,
                        script,
                        onChunk,
                        onToolCall,
                        onToolResult,
                        recursionDepth + 1
                    )
                    
                    // If there's another continuation, use it instead
                    if (nextContinuation != null) {
                        return nextContinuation
                    }
                }
                
                // If all function calls were skipped, make another API call to get a different response
                if (!executedAnyCall) {
                    android.util.Log.w("PpeExecutionEngine", "All function calls from continuation were skipped - making retry API call")
                    val updatedMessages = messages + listOf(
                        Content(
                            role = "model",
                            parts = listOf(Part.TextPart(text = continuationResponse.text))
                        )
                    )
                    val retryMessages = updatedMessages + listOf(
                        Content(
                            role = "user",
                            parts = listOf(Part.TextPart(text = "Do not call write_todos again. Start implementing the tasks by creating files. Use write_file, edit, or shell tools."))
                        )
                    )
                    
                    val retryResult = apiClient.callApi(
                        messages = retryMessages,
                        model = null,
                        temperature = null,
                        topP = null,
                        topK = null,
                        tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                        } else {
                            null
                        }
                    )
                    
                    val retryResponse = retryResult.getOrNull()
                    if (retryResponse != null) {
                        if (retryResponse.text.isNotEmpty()) {
                            onChunk(retryResponse.text)
                        }
                        
                        if (retryResponse.functionCalls.isNotEmpty()) {
                            for (retryFunctionCall in retryResponse.functionCalls) {
                                if (retryFunctionCall.name == "write_todos") {
                                    android.util.Log.w("PpeExecutionEngine", "Skipping write_todos in retry")
                                    continue
                                }
                                
                                onToolCall(retryFunctionCall)
                                val retryToolResult = executeTool(retryFunctionCall, onToolResult)
                                
                                val retryContinuation = continueWithToolResult(
                                    retryMessages + listOf(
                                        Content(
                                            role = "model",
                                            parts = listOf(Part.TextPart(text = retryResponse.text))
                                        )
                                    ),
                                    retryFunctionCall,
                                    retryToolResult,
                                    script,
                                    onChunk,
                                    onToolCall,
                                    onToolResult,
                                    recursionDepth + 1
                                )
                                
                                if (retryContinuation != null) {
                                    return retryContinuation
                                }
                            }
                        }
                        
                        return retryResponse
                    }
                }
            } else {
                // No function calls in continuation response - check if we should continue
                android.util.Log.d("PpeExecutionEngine", "Continuation response has no function calls - checking if should continue")
                // Continue if: response is empty OR (response has text but we should prompt for next steps)
                val recentToolCalls = messages.takeLast(10).flatMap { content ->
                    content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
                }
                val sameToolCallCount = recentToolCalls.count { it == functionCall.name }
                android.util.Log.d("PpeExecutionEngine", "Recent tool calls: $recentToolCalls, sameToolCallCount: $sameToolCallCount")
                
                // Continue if:
                // 1. Response is empty (AI might be waiting for next instruction)
                // 2. Response has text but no function calls (AI provided plan/explanation, should continue implementing)
                // 3. We haven't exceeded recursion limits
                val hasTextButNoCalls = continuationResponse.text.isNotEmpty() && continuationResponse.functionCalls.isEmpty()
                val isEmpty = continuationResponse.text.isEmpty()
                val shouldContinue = (isEmpty || hasTextButNoCalls) && 
                    sameToolCallCount < 3 && 
                    recursionDepth < 5
                
                Log.d("PpeExecutionEngine", "Continuation decision - hasTextButNoCalls: $hasTextButNoCalls, isEmpty: $isEmpty, sameToolCallCount: $sameToolCallCount, recursionDepth: $recursionDepth, shouldContinue: $shouldContinue")
                
                if (shouldContinue) {
                    // Make another API call to prompt continuation
                    val promptText = when {
                        functionCall.name == "write_todos" -> "The todo list has been created. Now proceed with implementing the tasks. Start by creating the project files (package.json, server files, etc.) and continue until the task is complete. Do not call write_todos again."
                        hasTextButNoCalls -> "You've provided a plan or explanation. Now proceed with implementing it. Use the available tools to create files, run commands, and complete the task. Continue until finished."
                        else -> "Please continue with the next steps to complete the task. Use the available tools to make progress."
                    }
                    
                    Log.d("PpeExecutionEngine", "Prompting continuation with: ${promptText.take(100)}...")
                    
                    // First add the continuation response text (e.g., the todo list) as model message
                    // Then add the prompt as user message to guide next steps
                    val promptMessages = messages + listOf(
                        Content(
                            role = "model",
                            parts = listOf(Part.TextPart(text = continuationResponse.text))
                        ),
                        Content(
                            role = "user",
                            parts = listOf(Part.TextPart(text = promptText))
                        )
                    )
                    
                    val continueResult = apiClient.callApi(
                        messages = promptMessages,
                        model = null,
                        temperature = null,
                        topP = null,
                        topK = null,
                        tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                        } else {
                            null
                        }
                    )
                    
                    val continueResponse = continueResult.getOrNull()
                    if (continueResponse != null) {
                        // Emit the continuation response
                        if (continueResponse.text.isNotEmpty()) {
                            onChunk(continueResponse.text)
                        }
                        
                        // Handle function calls from the continuation
                        if (continueResponse.functionCalls.isNotEmpty()) {
                            var executedAnyCall = false
                            for (nextFunctionCall in continueResponse.functionCalls) {
                                // Skip write_todos if it was just called
                                if (nextFunctionCall.name == "write_todos" && sameToolCallCount >= 1) {
                                    android.util.Log.w("PpeExecutionEngine", "Skipping write_todos call - already called recently")
                                    continue
                                }
                                
                                executedAnyCall = true
                                onToolCall(nextFunctionCall)
                                val nextToolResult = executeTool(nextFunctionCall, onToolResult)
                                
                                val nextContinuation = continueWithToolResult(
                                    promptMessages + listOf(
                                        Content(
                                            role = "model",
                                            parts = listOf(Part.TextPart(text = continueResponse.text))
                                        )
                                    ),
                                    nextFunctionCall,
                                    nextToolResult,
                                    script,
                                    onChunk,
                                    onToolCall,
                                    onToolResult,
                                    recursionDepth + 1
                                )
                                
                                if (nextContinuation != null) {
                                    return nextContinuation
                                }
                            }
                            
                            // If all function calls were skipped, prompt again to get a different response
                            if (!executedAnyCall) {
                                android.util.Log.w("PpeExecutionEngine", "All function calls were skipped - prompting again for different response")
                                val retryPromptMessages = promptMessages + listOf(
                                    Content(
                                        role = "model",
                                        parts = listOf(Part.TextPart(text = continueResponse.text))
                                    ),
                                    Content(
                                        role = "user",
                                        parts = listOf(Part.TextPart(text = "Do not call write_todos again. Instead, start implementing the tasks by creating files and writing code. Use write_file, edit, or shell tools."))
                                    )
                                )
                                
                                val retryResult = apiClient.callApi(
                                    messages = retryPromptMessages,
                                    model = null,
                                    temperature = null,
                                    topP = null,
                                    topK = null,
                                    tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                                        listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                                    } else {
                                        null
                                    }
                                )
                                
                                val retryResponse = retryResult.getOrNull()
                                if (retryResponse != null) {
                                    if (retryResponse.text.isNotEmpty()) {
                                        onChunk(retryResponse.text)
                                    }
                                    
                                    if (retryResponse.functionCalls.isNotEmpty()) {
                                        for (retryFunctionCall in retryResponse.functionCalls) {
                                            // Still skip write_todos
                                            if (retryFunctionCall.name == "write_todos") {
                                                android.util.Log.w("PpeExecutionEngine", "Skipping write_todos in retry response")
                                                continue
                                            }
                                            
                                            onToolCall(retryFunctionCall)
                                            val retryToolResult = executeTool(retryFunctionCall, onToolResult)
                                            
                                            val retryContinuation = continueWithToolResult(
                                                retryPromptMessages + listOf(
                                                    Content(
                                                        role = "model",
                                                        parts = listOf(Part.TextPart(text = retryResponse.text))
                                                    )
                                                ),
                                                retryFunctionCall,
                                                retryToolResult,
                                                script,
                                                onChunk,
                                                onToolCall,
                                                onToolResult,
                                                recursionDepth + 1
                                            )
                                            
                                            if (retryContinuation != null) {
                                                return retryContinuation
                                            }
                                        }
                                    }
                                    
                                    return retryResponse
                                }
                            }
                        }
                        
                        return continueResponse
                    }
                }
            }
        }
        
        return continuationResponse
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
        
        // Create invocation and execute - use unchecked cast like AgentClient does
        @Suppress("UNCHECKED_CAST")
        val invocation = (tool as com.qali.aterm.agent.tools.DeclarativeTool<Any, com.qali.aterm.agent.tools.ToolResult>).createInvocation(params as Any)
        val result = invocation.execute()
        
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
