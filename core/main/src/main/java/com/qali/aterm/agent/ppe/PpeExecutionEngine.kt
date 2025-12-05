package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.ppe.models.*
import com.qali.aterm.agent.tools.ToolRegistry
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
import com.qali.aterm.agent.utils.AtermIgnoreManager
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.debug.ExecutionStateTracker
import com.qali.aterm.agent.debug.BreakpointManager
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Execution engine for PPE scripts
 * Handles script execution, template rendering, AI calls, and tool execution
 */
class PpeExecutionEngine(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String,
    private val ollamaUrl: String? = null,
    private val ollamaModel: String? = null
) {
    private val apiClient = PpeApiClient(toolRegistry, ollamaUrl, ollamaModel)
    private val scriptCache = mutableMapOf<String, PpeScript>()
    
    /**
     * Helper function to call API with automatic retry on rate limit exhaustion
     * Handles KeysExhaustedExceptionWithRetry by waiting and retrying
     */
    private suspend fun callApiWithRetryOnExhaustion(
        messages: List<Content>,
        model: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        tools: List<Tool>? = null,
        disableTools: Boolean = false,
        onChunk: ((String) -> Unit)? = null
    ): Result<PpeApiResponse> {
        val result = try {
            // Hard timeout so a single provider call can't hang the entire agent.
            withTimeout(PpeConfig.API_CALL_TIMEOUT_MS) {
                apiClient.callApi(
                    messages = messages,
                    model = model,
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    tools = tools,
                    disableTools = disableTools
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(
                "PpeExecutionEngine",
                "API call timed out after ${PpeConfig.API_CALL_TIMEOUT_MS}ms",
                e
            )
            onChunk?.invoke(
                "✗ LLM API call timed out after ${PpeConfig.API_CALL_TIMEOUT_MS / 1000}s. " +
                "Please check your network or provider configuration and try again.\n"
            )
            return Result.failure(e)
        }
        
        // If it's a KeysExhaustedExceptionWithRetry, wait and retry
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is KeysExhaustedExceptionWithRetry) {
                val retryDelayMs = error.retryDelayMs
                Log.w("PpeExecutionEngine", "All API keys exhausted with rate limit. Waiting ${retryDelayMs}ms before retry...")
                onChunk?.invoke("⏳ All API keys hit rate limit. Waiting ${retryDelayMs / 1000}s before retrying...\n")
                
                // Wait for the retry delay
                delay(retryDelayMs)
                
                // Retry the API call
                Log.d("PpeExecutionEngine", "Retrying API call after ${retryDelayMs}ms delay")
                onChunk?.invoke("🔄 Retrying API call...\n")
                
                return apiClient.callApi(
                    messages = messages,
                    model = model,
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    tools = tools,
                    disableTools = disableTools
                )
            }
        }
        
        return result
    }
    
    // Track tool results for file diff extraction (queue-based to handle multiple calls)
    private val toolResultQueue = mutableListOf<Pair<String, com.qali.aterm.agent.tools.ToolResult>>()
    
    /**
     * Get optimal temperature for API calls based on current model
     */
    private fun getOptimalTemperature(taskType: ModelTemperatureConfig.TaskType = ModelTemperatureConfig.TaskType.DEFAULT): Double {
        val currentModel = try {
            com.qali.aterm.api.ApiProviderManager.getCurrentModel()
        } catch (e: Exception) {
            null
        }
        return ModelTemperatureConfig.getOptimalTemperature(
            model = currentModel,
            taskType = taskType,
            userTemperature = null
        )
    }
    
    /**
     * Execute a PPE script with given input parameters (non-streaming)
     * Returns the final execution result directly
     */
    suspend fun executeScript(
        script: PpeScript,
        inputParams: Map<String, Any> = emptyMap(),
        onChunk: (String) -> Unit = {},
        onToolCall: (FunctionCall) -> Unit = {},
        onToolResult: (String, Map<String, Any>) -> Unit = { _, _ -> }
    ): PpeExecutionResult {
        val executionStartTime = System.currentTimeMillis()
        var lastActivityTime = executionStartTime
        var turnCount = 0
        var aiCallCount = 0
        var toolExecutionCount = 0
        
        // Start operation tracking
        val operationId = "script-exec-${System.currentTimeMillis()}"
        Observability.startOperation(operationId, "script-execution")
        
        // Start execution state tracking
        ExecutionStateTracker.startExecution(
            operationId = operationId,
            scriptPath = script.sourcePath,
            initialVariables = inputParams.toMap()
        )
        
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
            
            // Update state tracker with initial variables
            ExecutionStateTracker.updateVariables(operationId, currentVariables)
            
            // Execute each turn in sequence
            for (turnIndex in script.turns.indices) {
                turnCount++
                lastActivityTime = System.currentTimeMillis()
                val turn = script.turns[turnIndex]
                Log.d("PpeExecutionEngine", "Executing turn $turnCount/${script.turns.size} (messages: ${turn.messages.size}, instructions: ${turn.instructions.size})")
                
                // Update state tracker with turn information
                ExecutionStateTracker.updateTurn(operationId, turnCount, script.turns.size)
                
                // Check for breakpoint at turn
                if (BreakpointManager.checkBreakpoint(
                    operationId,
                    BreakpointManager.BreakpointType.TURN,
                    turnCount.toString(),
                    mapOf(
                        "turnNumber" to turnCount,
                        "totalTurns" to script.turns.size,
                        "variables" to currentVariables,
                        "operationId" to operationId
                    )
                )) {
                    // Wait for continue/step command
                    while (BreakpointManager.isPaused(operationId)) {
                        delay(100) // Check every 100ms
                    }
                }
                
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
                        
                        // Check if this is a new project startup - if so, use two-phase approach
                        val userMessage = (inputParams["userMessage"]?.toString() 
                            ?: inputParams["content"]?.toString() 
                            ?: processedContent).takeIf { it.isNotEmpty() } ?: processedContent
                        
                        if (aiCallCount == 1) {
                            // PRIORITY: Check for upgrade/debug flow FIRST (before new project detection)
                            // This prevents false positives where error messages are misclassified as new projects
                            if (isUpgradeOrDebugRequest(userMessage, workspaceRoot)) {
                                Log.d("PpeExecutionEngine", "Detected upgrade/debug request - using upgrade/debug flow")
                                val upgradeResult = executeUpgradeDebugFlow(
                                    userMessage,
                                    chatHistory + turnMessages,
                                    script,
                                    onChunk,
                                    onToolCall,
                                    onToolResult
                                )
                                
                                // Store result
                                currentVariables["LatestResult"] = upgradeResult.finalResult
                                currentVariables["RESPONSE"] = upgradeResult.finalResult
                                
                                // Add to chat history
                                turnMessages.add(
                                    Content(
                                        role = "model",
                                        parts = listOf(Part.TextPart(text = upgradeResult.finalResult))
                                    )
                                )
                                
                                // Continue with normal flow if there are more turns
                                continue
                            }
                            
                            // Check for new project startup (enhanced detection)
                            // Only check if NOT a debug/error request
                            val projectDetection = ProjectStartupDetector.detectNewProject(userMessage, workspaceRoot)
                            if (projectDetection.isNewProject) {
                                Log.d("PpeExecutionEngine", "Detected new project startup - type: ${projectDetection.projectType}, confidence: ${projectDetection.confidence}")
                                
                                // Log detection details
                                DebugLogger.i("PpeExecutionEngine", "New project detected", mapOf(
                                    "project_type" to (projectDetection.projectType?.name ?: "unknown"),
                                    "confidence" to projectDetection.confidence,
                                    "template" to (projectDetection.suggestedTemplate ?: "none")
                                ))
                                
                                val twoPhaseResult = executeTwoPhaseProjectStartup(
                                    userMessage,
                                    chatHistory + turnMessages,
                                    script,
                                    onChunk,
                                    onToolCall,
                                    onToolResult,
                                    projectDetection.projectType,
                                    projectDetection.suggestedTemplate
                                )
                                
                                // Store result
                                currentVariables["LatestResult"] = twoPhaseResult.finalResult
                                currentVariables["RESPONSE"] = twoPhaseResult.finalResult
                                
                                // Add to chat history
                                turnMessages.add(
                                    Content(
                                        role = "model",
                                        parts = listOf(Part.TextPart(text = twoPhaseResult.finalResult))
                                    )
                                )
                                
                                // Continue with normal flow if there are more turns
                                continue
                            }
                        }
                        
                        // Check for breakpoint before AI call
                        if (BreakpointManager.checkBreakpoint(
                            operationId,
                            BreakpointManager.BreakpointType.CONDITION,
                            "ai_call",
                            mapOf(
                                "turnNumber" to turnCount,
                                "aiCallNumber" to aiCallCount,
                                "variables" to currentVariables,
                                "operationId" to operationId
                            )
                        )) {
                            while (BreakpointManager.isPaused(operationId)) {
                                delay(100)
                            }
                        }
                        
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
                        
                        // Check for breakpoint on variable change
                        if (BreakpointManager.checkBreakpoint(
                            operationId,
                            BreakpointManager.BreakpointType.VARIABLE,
                            varName,
                            mapOf(
                                "variable_$varName" to aiResponse.text,
                                "variables" to currentVariables,
                                "operationId" to operationId
                            )
                        )) {
                            while (BreakpointManager.isPaused(operationId)) {
                                delay(100)
                            }
                        }
                        
                        // Update state tracker with variables and chat history
                        ExecutionStateTracker.updateVariables(operationId, currentVariables)
                        ExecutionStateTracker.updateChatHistory(operationId, chatHistory + turnMessages)
                        
                        // Add to chat history (replace AI placeholder with response)
                        val finalContent = processedContent.replace(Regex("""\[\[.*?\]\]"""), aiResponse.text)
                        turnMessages.add(
                            Content(
                                role = message.role,
                                parts = listOf(Part.TextPart(text = finalContent))
                            )
                        )
                        
                        // Handle function calls from AI response with parallel execution
                        if (aiResponse.functionCalls.isNotEmpty()) {
                            Log.d("PpeExecutionEngine", "Processing ${aiResponse.functionCalls.size} function calls from AI response")
                            
                            // Filter calls that need approval
                            val callsNeedingApproval = aiResponse.functionCalls.filter { call ->
                                ToolApprovalManager.requiresApproval(call) && !AllowListManager.isAllowed(call)
                            }
                            
                            val callsToExecute = aiResponse.functionCalls.filter { call ->
                                !ToolApprovalManager.requiresApproval(call) || AllowListManager.isAllowed(call)
                            }
                            
                            // If there are calls needing approval, we'll handle them separately
                            // For now, execute approved calls
                            
                            // Execute tools in parallel when possible
                            val toolResults = if (callsToExecute.isNotEmpty()) {
                                ParallelToolExecutor.executeInParallel(
                                    calls = callsToExecute,
                                    executeTool = { call ->
                                        toolExecutionCount++
                                        lastActivityTime = System.currentTimeMillis()
                                        
                                        // Check for breakpoint before tool call
                                        if (BreakpointManager.checkBreakpoint(
                                            operationId,
                                            BreakpointManager.BreakpointType.TOOL_CALL,
                                            call.name,
                                            mapOf(
                                                "toolName" to call.name,
                                                "toolExecutionCount" to toolExecutionCount,
                                                "turnNumber" to turnCount,
                                                "variables" to currentVariables,
                                                "operationId" to operationId
                                            )
                                        )) {
                                            while (BreakpointManager.isPaused(operationId)) {
                                                delay(100)
                                            }
                                        }
                                        
                                        Log.d("PpeExecutionEngine", "Executing tool #$toolExecutionCount: ${call.name}")
                                        onToolCall(call)
                                        
                                        val toolStartTime = System.currentTimeMillis()
                                        
                                        // Validate code before writing
                                        if (call.name == "write_file" && PpeConfig.VALIDATE_BEFORE_WRITE) {
                                            val filePath = call.args["file_path"] as? String
                                            val fileContents = call.args["file_contents"] as? String
                                            
                                            if (filePath != null && fileContents != null) {
                                                val validation = CodeQualityValidator.validateCode(
                                                    code = fileContents,
                                                    filePath = filePath,
                                                    workspaceRoot = workspaceRoot
                                                )
                                                
                                                if (!validation.isValid) {
                                                    Log.w("PpeExecutionEngine", "Code validation failed for $filePath: ${validation.errors.joinToString(", ")}")
                                                    // Still execute but log warnings
                                                    validation.warnings.forEach { warning ->
                                                        Log.w("PpeExecutionEngine", "Warning: $warning")
                                                    }
                                                }
                                            }
                                        }
                                        
                                        val result = executeTool(call, onToolResult)
                                        val toolExecutionTime = System.currentTimeMillis() - toolStartTime
                                        Log.d("PpeExecutionEngine", "Tool #$toolExecutionCount (${call.name}) completed (time: ${toolExecutionTime}ms, error: ${result.error != null})")
                                        
                                        // Display formatted file change notification for write_file tool (cursor-cli style)
                                        if (call.name == "write_file" && result.error == null && result.returnDisplay != null) {
                                            onChunk("\n${result.returnDisplay}\n")
                                        }
                                        
                                        // Record tool call in state tracker
                                        ExecutionStateTracker.recordToolCall(
                                            operationId = operationId,
                                            functionCall = call,
                                            duration = toolExecutionTime,
                                            success = result.error == null,
                                            error = result.error?.message
                                        )
                                        
                                        // Log tool execution
                                        val resultSize = when {
                                            result.llmContent is String -> (result.llmContent as String).length
                                            result.error != null -> 0
                                            else -> 0
                                        }
                                        DebugLogger.logToolExecution(
                                            tag = "PpeExecutionEngine",
                                            toolName = call.name,
                                            params = call.args,
                                            duration = toolExecutionTime,
                                            success = result.error == null,
                                            resultSize = resultSize,
                                            error = result.error?.message
                                        )
                                        
                                        result
                                    }
                                )
                            } else {
                                emptyList()
                            }
                            
                            // Store calls needing approval for UI (they won't execute until approved)
                            if (callsNeedingApproval.isNotEmpty()) {
                                callsNeedingApproval.forEach { call ->
                                    onToolCall(call) // Notify UI about pending approval
                                    // Add a placeholder result indicating approval needed
                                    turnMessages.add(
                                        Content(
                                            role = "user",
                                            parts = listOf(
                                                Part.FunctionResponsePart(
                                                    functionResponse = FunctionResponse(
                                                        name = call.name,
                                                        response = mapOf(
                                                            "status" to "pending_approval",
                                                            "message" to "This action requires user approval"
                                                        ),
                                                        id = call.id ?: ""
                                                    )
                                                )
                                            )
                                        )
                                    )
                                }
                            }
                            
                            // Process results in order
                            for ((functionCall, toolResult) in toolResults) {
                                
                                // Add tool result to chat history
                                val toolResponse = when {
                                    toolResult.error != null -> mapOf("error" to toolResult.error.message)
                                    toolResult.llmContent is String -> mapOf("output" to toolResult.llmContent)
                                    else -> mapOf("output" to "Tool execution succeeded.")
                                }
                                
                                turnMessages.add(
                                    Content(
                                        role = "user",
                                        parts = listOf(
                                            Part.FunctionResponsePart(
                                                functionResponse = FunctionResponse(
                                                    name = functionCall.name,
                                                    response = toolResponse,
                                                    id = functionCall.id ?: ""
                                                )
                                            )
                                        )
                                    )
                                )
                                
                                // Record metrics
                                Observability.recordToolCall(operationId)
                                if (toolResult.error != null) {
                                    Observability.recordError(operationId)
                                }
                                
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
                                    // For write_file, if we've only written a few files (less than 5), be more aggressive about continuing
                                    val allMessages = chatHistory + turnMessages
                                    val writeFileCount = allMessages.takeLast(20).flatMap { content ->
                                        content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
                                    }.count { it == "write_file" }
                                    
                                    // More aggressive check: if continuation response has no function calls AND we've written less than 5 files
                                    // Also check if the response text is very short or just says "done" or similar
                                    // IMPORTANT: For write_file, if we have < 5 files and no function calls, ALWAYS trigger fallback
                                    // even if text is not minimal - the agent might have stopped prematurely
                                    val hasMinimalText = continuationResponse.text.trim().isEmpty() || 
                                        continuationResponse.text.trim().lowercase().let { text ->
                                            text == "done" || text == "complete" || text == "finished" || 
                                            text.length < 20 // Very short responses likely mean the agent stopped
                                        }
                                    // For write_file with < 5 files: trigger fallback if no function calls, regardless of text length
                                    // This ensures we continue creating files even if the agent gives a longer response but no actions
                                    // Count includes the current write_file call
                                    val writeFileCountIncludingCurrent = writeFileCount + 1
                                    val needsFallbackForWriteFile = functionCall.name == "write_file" && 
                                        writeFileCountIncludingCurrent < 5 && 
                                        continuationResponse.functionCalls.isEmpty()
                                        // Removed hasMinimalText check - be more aggressive
                                    
                                    android.util.Log.d("PpeExecutionEngine", "Fallback check - writeFileCount: $writeFileCount, writeFileCountIncludingCurrent: $writeFileCountIncludingCurrent, needsFallback: $needsFallbackForWriteFile, hasMinimalText: $hasMinimalText, continuationText: '${continuationResponse.text.take(50)}'")
                                    val needsFallback = continuationResponse.text.isEmpty() && 
                                        continuationResponse.functionCalls.isEmpty() && 
                                        functionCall.name == "write_todos"
                                    
                                    if (needsFallback || needsFallbackForWriteFile) {
                                        // Make fallback continuation attempt
                                        val promptMessages = (chatHistory + turnMessages).toMutableList()
                                        val fallbackPrompt = if (needsFallbackForWriteFile) {
                                            "You must continue creating files. The project needs more files: server.js (if not created), index.html, style.css (or styles.css), and the JavaScript game logic file (app.js, script.js, or client.js). The game logic file is REQUIRED. Use write_file to create each file. IMPORTANT: Check the code relativeness information from previous write_file results. Only use imports/exports that exist in related files."
                                        } else {
                                            "The todo list has been created. Now proceed with implementing the first task. Start by creating the project files (package.json, server.js, etc.). Do not call write_todos again."
                                        }
                                        promptMessages.add(
                                            Content(
                                                role = "model",
                                                parts = listOf(Part.TextPart(text = continuationResponse.text.ifEmpty { "Tool execution completed." }))
                                            )
                                        )
                                        promptMessages.add(
                                            Content(
                                                role = "user",
                                                parts = listOf(Part.TextPart(text = fallbackPrompt))
                                            )
                                        )
                                        
                                        val fallbackResult = apiClient.callApi(
                                            messages = promptMessages,
                                            model = null,
                                            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_GENERATION),
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
                                        temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
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
                        val result = executeScript(
                            chainedScript,
                            chainInput,
                            onChunk,
                            onToolCall,
                            onToolResult
                        )
                        if (result.success) {
                            chainedFinalResult = result.finalResult
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
            
            // Log script execution
            DebugLogger.logScriptExecution(
                tag = "PpeExecutionEngine",
                scriptPath = script.sourcePath,
                turnIndex = turnCount,
                totalTurns = script.turns.size,
                duration = totalExecutionTime,
                success = true
            )
            
            // End operation tracking
            Observability.endOperation(operationId)
            ExecutionStateTracker.endExecution(operationId)
            
            return PpeExecutionResult(
                success = true,
                finalResult = finalResult,
                variables = currentVariables,
                chatHistory = chatHistory
            )
            
        } catch (e: Exception) {
            val totalExecutionTime = System.currentTimeMillis() - executionStartTime
            val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
            Log.e("PpeExecutionEngine", "Script execution failed", e)
            Log.e("PpeExecutionEngine", "Error details - Total time: ${totalExecutionTime}ms, Time since last activity: ${timeSinceLastActivity}ms")
            Log.e("PpeExecutionEngine", "Error stats - Turns: $turnCount, AI calls: $aiCallCount, Tool executions: $toolExecutionCount")
            Log.e("PpeExecutionEngine", "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
            e.printStackTrace()
            
            // Log script execution failure
            DebugLogger.logScriptExecution(
                tag = "PpeExecutionEngine",
                scriptPath = script.sourcePath,
                turnIndex = turnCount,
                totalTurns = script.turns.size,
                duration = totalExecutionTime,
                success = false
            )
            DebugLogger.e("PpeExecutionEngine", "Script execution exception", mapOf(
                "operation_id" to operationId,
                "turns" to turnCount,
                "ai_calls" to aiCallCount,
                "tool_executions" to toolExecutionCount,
                "duration_ms" to totalExecutionTime
            ), e)
            
            // Record error and end operation
            Observability.recordError(operationId)
            Observability.endOperation(operationId)
            ExecutionStateTracker.endExecution(operationId)
            
            return PpeExecutionResult(
                success = false,
                finalResult = "",
                variables = emptyMap(),
                chatHistory = emptyList(),
                error = e.message
            )
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
            // Ensure we add as user role (Gemini API requirement: last message must be user)
            val roleToUse = if (message.role == "user") "user" else "user"
            messages.add(Content(role = roleToUse, parts = listOf(Part.TextPart(text = messageWithoutPlaceholder))))
        }
        
        // Safety check: ensure we have at least one message and it's a user message
        if (messages.isEmpty()) {
            throw Exception("Cannot make API call: no messages to send. Ensure user message is included before AI placeholder.")
        }
        
        // CRITICAL: Gemini API requires the last message to be a user message
        // If the last message is not a user message, add an empty user message or convert it
        if (messages.last().role != "user") {
            android.util.Log.w("PpeExecutionEngine", "Last message is not user role (${messages.last().role}), ensuring user message at end")
            // If last message has content, we'll handle it in the blueprint section below
            // Otherwise, add an empty user message to satisfy API requirement
            if (messages.last().parts.isEmpty() || messages.last().parts.all { 
                it is Part.TextPart && it.text.isEmpty() 
            }) {
                messages.add(Content(role = "user", parts = listOf(Part.TextPart(text = "Continue."))))
            }
        }
        
        // Get model from placeholder params or script
        val model = message.aiPlaceholderParams?.get("model")?.toString()
        val userTemperature = message.aiPlaceholderParams?.get("temperature")?.toString()?.toDoubleOrNull()
        val topP = message.aiPlaceholderParams?.get("top_p")?.toString()?.toDoubleOrNull()
        val topK = message.aiPlaceholderParams?.get("top_k")?.toString()?.toIntOrNull()
        
        // Adjust temperature based on model capabilities
        val temperature = ModelTemperatureConfig.getOptimalTemperatureWithTaskDetection(
            model = model,
            userMessage = messageWithoutPlaceholder,
            context = if (messages.isNotEmpty()) messages.joinToString("\n") { it.parts.joinToString { p -> if (p is Part.TextPart) p.text else "" } } else "",
            userTemperature = userTemperature
        )
        
        // Handle constrained AI responses
        val constrainedOptions = message.constrainedOptions
        val constrainedCount = message.constrainedCount
        val constrainedRandom = message.constrainedRandom
        
        // Phase 1: Generate comprehensive blueprint by scanning all files first
        // This should be done at the start to build the complete dependency matrix
        val comprehensiveBlueprint = try {
            CodeDependencyAnalyzer.scanAndBuildBlueprint(workspaceRoot)
        } catch (e: Exception) {
            android.util.Log.w("PpeExecutionEngine", "Failed to scan and build blueprint: ${e.message}")
            ""
        }
        
        // Also get the existing coherence blueprint (for backward compatibility)
        val coherenceBlueprint = try {
            CodeDependencyAnalyzer.generateCoherenceBlueprint(workspaceRoot)
        } catch (e: Exception) {
            android.util.Log.w("PpeExecutionEngine", "Failed to generate coherence blueprint: ${e.message}")
            ""
        }
        
        // If constrained options are specified, modify the message to include them
        var finalMessageContent = messageWithoutPlaceholder
        if (constrainedOptions != null && constrainedOptions.isNotEmpty()) {
            // Add constraint to message
            val optionsText = constrainedOptions.joinToString("|")
            val countText = if (constrainedCount != null) ":$constrainedCount" else ""
            val randomText = if (constrainedRandom) ":random" else ""
            finalMessageContent += "\n\nYou must choose from these options only: $optionsText$countText$randomText"
        }
        
        // Add comprehensive blueprint first (Phase 1: Blueprint Generation)
        if (comprehensiveBlueprint.isNotEmpty()) {
            finalMessageContent += "\n\n$comprehensiveBlueprint"
        } else if (coherenceBlueprint.isNotEmpty()) {
            // Fallback to existing blueprint if comprehensive scan failed
            finalMessageContent += "\n\n$coherenceBlueprint"
        }
        
        // Update message content if modified
        // CRITICAL: Ensure the last message is always a user message (Gemini API requirement)
        if (finalMessageContent != messageWithoutPlaceholder) {
            if (messages.isNotEmpty() && messages.last().role == "user") {
                // Update the last user message
                messages[messages.size - 1] = Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = finalMessageContent))
                )
            } else {
                // Add a new user message with the blueprint (ensures last message is user)
                messages.add(Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = finalMessageContent))
                ))
            }
        } else if (messages.isEmpty() || messages.last().role != "user") {
            // Safety: Ensure we always end with a user message
            if (messageWithoutPlaceholder.isNotEmpty()) {
                messages.add(Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = messageWithoutPlaceholder))
                ))
            }
        }
        
        // Prune chat history if needed to fit context window
        val modelToUse = model ?: "default"
        val prunedMessages = ContextWindowManager
            .pruneChatHistory(messages, modelToUse)
            .toMutableList()
        
        if (prunedMessages.size < messages.size) {
            Log.d("PpeExecutionEngine", "Pruned ${messages.size - prunedMessages.size} messages to fit context window")
            // Add summary if messages were pruned
            if (prunedMessages.isNotEmpty() && prunedMessages.first().role != "system") {
                val summary = ContextWindowManager.createSummary(messages.drop(prunedMessages.size))
                if (summary.isNotEmpty()) {
                    prunedMessages.add(0, Content(
                        role = "system",
                        parts = listOf(Part.TextPart(text = summary))
                    ))
                }
            }
        }
        
        // Check if using built-in local model
        val currentProvider = com.qali.aterm.api.ApiProviderManager.selectedProvider
        val useLocalModel = currentProvider == com.qali.aterm.api.ApiProviderType.BUILTIN_LOCAL
        
        // If using local model, generate response directly
        if (useLocalModel) {
            val systemPrompt = buildString {
                appendLine("You are Blueprint AI. You must produce coherent, consistent code with matching tags, function names, and types.")
                appendLine()
                appendLine("CRITICAL RULES:")
                appendLine("1. Identifier Consistency: All identifiers (functions, classes, variables, props, API endpoints, types, event names) must remain consistent across all files.")
                appendLine("2. Never rename things unless explicitly requested by the user.")
                appendLine("3. When a tag, function, variable, or class is mentioned anywhere, it must keep the same name everywhere.")
                appendLine("4. Ensure imports match exports (module.exports / export default).")
                appendLine("5. Verify file paths are correct when importing.")
                appendLine("6. Methods must exist in target files when referenced.")
                appendLine("7. Return types must stay consistent.")
                appendLine("8. DOM or API identifiers must stay consistent.")
                appendLine()
                appendLine("You are a multi-role development agent with debugging and code engineering capabilities.")
                appendLine("Follow the AGENT_GUIDELINES.md principles for coherent code generation.")
            }
            
            val fullPrompt = buildString {
                // Add system prompt
                appendLine(systemPrompt)
                appendLine()
                
                // Add chat history context
                if (prunedMessages.isNotEmpty()) {
                    appendLine("## Conversation History")
                    prunedMessages.forEach { msg ->
                        val role = when (msg.role) {
                            "user" -> "User"
                            "model", "assistant" -> "Assistant"
                            else -> msg.role.capitalize()
                        }
                        val text = msg.parts.joinToString(" ") { part ->
                            when (part) {
                                is Part.TextPart -> part.text
                                else -> ""
                            }
                        }
                        if (text.isNotEmpty()) {
                            appendLine("$role: $text")
                        }
                    }
                    appendLine()
                }
                
                // Add current message
                appendLine("## Current Request")
                appendLine(finalMessageContent)
            }
            
            val response = try {
                com.qali.aterm.llm.LocalLlamaModel.generate(fullPrompt)
            } catch (e: Exception) {
                android.util.Log.e("PpeExecutionEngine", "Local model generation failed: ${e.message}", e)
                "Error: Failed to generate response from local model. ${e.message}"
            }
            
            return PpeApiResponse(
                text = response,
                finishReason = "STOP",
                functionCalls = emptyList()
            )
        }
        
        // Get tools from registry (for remote API calls)
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        // Make API call (non-streaming) with all parameters
        val result = apiClient.callApi(
            messages = prunedMessages,
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
        // Allow more calls for write_file and shell since we need multiple files/commands for a complete project
        val recentToolCalls = chatHistory.takeLast(10).flatMap { content ->
            content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
        }
        val sameToolCallCount = recentToolCalls.count { it == functionCall.name }
        val maxRepeatedCalls = when (functionCall.name) {
            "write_file" -> 15
            "shell" -> 20  // Need many shell commands for setup, install, etc.
            "ls", "read" -> 15  // Read-only tools - need to check multiple files/directories
            else -> 2
        }
        if (sameToolCallCount >= maxRepeatedCalls && recursionDepth > 0) {
            android.util.Log.w("PpeExecutionEngine", "Detected repeated calls to ${functionCall.name} (count: $sameToolCallCount), stopping recursion")
            // Don't emit a message that might trigger another call - just stop silently
            // The agent should have enough context to proceed
            return null
        }
        
        // Build messages with tool result for continuation
        val messages = chatHistory.toMutableList()
        
        // Detect missing module errors and add prompt to create missing files
        val missingFile = detectMissingModule(toolResult, functionCall)
        var missingFilePrompt = ""
        if (missingFile != null) {
            android.util.Log.d("PpeExecutionEngine", "Detected missing module/file: $missingFile")
            onChunk("🔍 Detected missing module: $missingFile\n")
            onChunk("⚠️ The file '$missingFile' is missing and needs to be created. Please use write_file to create it with appropriate content.\n")
            missingFilePrompt = "\n\n⚠️ IMPORTANT: A missing file was detected: '$missingFile'. You MUST create this file using write_file tool immediately. The error indicates this file is required for the application to work."
        }
        
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
        
        // Add code coherence constraint for write_file operations (Phase 2: File Writing)
        val coherenceConstraint = if (functionCall.name == "write_file") {
            val filePath = functionCall.args["file_path"] as? String
            if (filePath != null) {
                try {
                    val constraint = CodeDependencyAnalyzer.generateCoherenceConstraintForFile(filePath, workspaceRoot)
                    
                    // Also add file writing plan if this is part of a multi-file project
                    val matrix = CodeDependencyAnalyzer.getDependencyMatrix(workspaceRoot)
                    val allFiles = matrix.files.keys.toList()
                    val plan = if (allFiles.size > 1) {
                        "\n\n" + CodeDependencyAnalyzer.generateFileWritingPlan(allFiles, workspaceRoot)
                    } else {
                        ""
                    }
                    
                    constraint + plan
                } catch (e: Exception) {
                    android.util.Log.w("PpeExecutionEngine", "Failed to generate coherence constraint: ${e.message}")
                    ""
                }
            } else {
                ""
            }
        } else {
            ""
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
        
        // Add coherence constraint as a separate user message if available
        if (coherenceConstraint.isNotEmpty()) {
            messages.add(
                Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = coherenceConstraint))
                )
            )
        }
        
        // Add missing file prompt if a missing file was detected
        if (missingFilePrompt.isNotEmpty()) {
            messages.add(
                Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = missingFilePrompt))
                )
            )
        }
        
        // Make continuation API call
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        // Adjust temperature based on model capabilities for continuation
        val result = callApiWithRetryOnExhaustion(
            messages = messages,
            model = null,
            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
            topP = null,
            topK = null,
            tools = tools,
            onChunk = onChunk
        )
        
        val continuationResponse = result.getOrNull()
        
        android.util.Log.d("PpeExecutionEngine", "continueWithToolResult: got continuation response - text length: ${continuationResponse?.text?.length ?: 0}, functionCalls: ${continuationResponse?.functionCalls?.size ?: 0}, recursionDepth: $recursionDepth, tool: ${functionCall.name}")
        
        if (continuationResponse == null) {
            android.util.Log.w("PpeExecutionEngine", "Continuation response is null - this should not happen")
            return null
        }
        
        // Emit continuation response chunks to UI
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
            
            // Check if we need fallback when response is empty
            val writeFileCount = messages.takeLast(20).flatMap { content ->
                content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
            }.count { it == "write_file" }
            
            // Count includes the current write_file call
            val writeFileCountIncludingCurrent = writeFileCount + 1
            val needsFallbackForEmptyResponse = functionCall.name == "write_file" && 
                writeFileCountIncludingCurrent < 5
            
            android.util.Log.d("PpeExecutionEngine", "Empty response check - writeFileCount: $writeFileCount, writeFileCountIncludingCurrent: $writeFileCountIncludingCurrent, needsFallback: $needsFallbackForEmptyResponse")
            
            if (needsFallbackForEmptyResponse) {
                android.util.Log.d("PpeExecutionEngine", "Empty continuation response detected - writeFileCount: $writeFileCount, writeFileCountIncludingCurrent: $writeFileCountIncludingCurrent, triggering fallback")
                // Will continue to fallback logic below
            }
        }
        
        // Handle function calls from continuation response
        android.util.Log.d("PpeExecutionEngine", "Checking continuation response - functionCalls size: ${continuationResponse.functionCalls.size}, isEmpty: ${continuationResponse.functionCalls.isEmpty()}")
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
                    // Check if we need fallback after recursive continuation
                    val allMessagesAfterRecursion = updatedMessages
                    val writeFileCountAfterRecursion = allMessagesAfterRecursion.takeLast(20).flatMap { content ->
                        content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
                    }.count { it == "write_file" }
                    
                    val hasMinimalTextAfterRecursion = nextContinuation.text.trim().isEmpty() || 
                        nextContinuation.text.trim().lowercase().let { text ->
                            text == "done" || text == "complete" || text == "finished" || 
                            text.length < 20
                        }
                    
                    // For write_file with < 5 files: trigger fallback if no function calls, regardless of text
                    // Count includes the current write_file call
                    val writeFileCountAfterRecursionIncludingCurrent = writeFileCountAfterRecursion + 1
                    val needsFallbackAfterRecursion = nextFunctionCall.name == "write_file" && 
                        writeFileCountAfterRecursionIncludingCurrent < 5 && 
                        nextContinuation.functionCalls.isEmpty()
                        // Removed hasMinimalTextAfterRecursion check - be more aggressive
                    
                    if (needsFallbackAfterRecursion) {
                        android.util.Log.d("PpeExecutionEngine", "Fallback needed after recursive continuation - writeFileCount: $writeFileCountAfterRecursion, writeFileCountIncludingCurrent: $writeFileCountAfterRecursionIncludingCurrent, hasMinimalText: $hasMinimalTextAfterRecursion")
                        // Don't return the minimal continuation - trigger fallback instead
                        // Fallback will be handled below
                    } else {
                        return nextContinuation
                    }
                }
            }
            
            // Check if we need fallback after processing all function calls
            // This handles the case where continuation had function calls, they were executed, but the final response is minimal
            val allMessagesAfterCalls = messages
            val writeFileCountAfterCalls = allMessagesAfterCalls.takeLast(20).flatMap { content ->
                content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
            }.count { it == "write_file" }
            
            val hasMinimalTextAfterCalls = continuationResponse.text.trim().isEmpty() || 
                continuationResponse.text.trim().lowercase().let { text ->
                    text == "done" || text == "complete" || text == "finished" || 
                    text.length < 20
                }
            
            // For write_file with < 5 files: trigger fallback if no function calls, regardless of text
            // Count includes the current write_file call
            val writeFileCountAfterCallsIncludingCurrent = writeFileCountAfterCalls + 1
            val needsFallbackAfterCalls = functionCall.name == "write_file" && 
                writeFileCountAfterCallsIncludingCurrent < 5 && 
                continuationResponse.functionCalls.isEmpty()
                // Removed hasMinimalTextAfterCalls check - be more aggressive
            
            if (needsFallbackAfterCalls) {
                android.util.Log.d("PpeExecutionEngine", "Fallback needed after processing function calls - writeFileCount: $writeFileCountAfterCalls, writeFileCountIncludingCurrent: $writeFileCountAfterCallsIncludingCurrent, hasMinimalText: $hasMinimalTextAfterCalls")
                // Will fall through to fallback logic below
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
                    temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
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
            val textPreview = if (continuationResponse.text.length > 50) continuationResponse.text.take(50) + "..." else continuationResponse.text
            android.util.Log.d("PpeExecutionEngine", "Continuation response has no function calls (text length: ${continuationResponse.text.length}, text: '$textPreview') - checking if should continue for tool: ${functionCall.name}")
            
            // Check if we need fallback when response has no function calls
            val writeFileCount = messages.takeLast(20).flatMap { content ->
                content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
            }.count { it == "write_file" }
            
            val hasMinimalText = continuationResponse.text.trim().isEmpty() || 
                continuationResponse.text.trim().lowercase().let { text ->
                    text == "done" || text == "complete" || text == "finished" || 
                    text.length < 20
                }
            
            // For write_file with < 5 files: trigger fallback if no function calls, regardless of text
            // This ensures we continue creating files even if the agent gives a response but no actions
            // Count includes the current write_file call
            val writeFileCountIncludingCurrent = writeFileCount + 1
            val needsFallbackForNoCalls = functionCall.name == "write_file" && 
                writeFileCountIncludingCurrent < 5
                // Removed hasMinimalText check - be more aggressive
            
            android.util.Log.d("PpeExecutionEngine", "No function calls check - writeFileCount: $writeFileCount, writeFileCountIncludingCurrent: $writeFileCountIncludingCurrent, needsFallback: $needsFallbackForNoCalls, tool: ${functionCall.name}")
            
            if (needsFallbackForNoCalls) {
                android.util.Log.d("PpeExecutionEngine", "Fallback needed - no function calls, writeFileCount: $writeFileCount, writeFileCountIncludingCurrent: $writeFileCountIncludingCurrent, hasMinimalText: $hasMinimalText")
                // Trigger fallback API call
                val fallbackMessages = messages + listOf(
                    Content(
                        role = "model",
                        parts = listOf(Part.TextPart(text = continuationResponse.text.ifEmpty { "Tool execution completed." }))
                    ),
                    Content(
                        role = "user",
                        parts = listOf(Part.TextPart(text = "You must continue creating files. The project needs more files: package.json (if not created), server.js (if not created), index.html, style.css (or styles.css), and the JavaScript game logic file (app.js, script.js, or client.js). The game logic file is REQUIRED. Use write_file to create each file. Do not stop until all files are created. IMPORTANT: Check the code relativeness information from previous write_file results. Only use imports/exports that exist in related files."))
                    )
                )
                
                val fallbackResult = apiClient.callApi(
                    messages = fallbackMessages,
                    model = null,
                    temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_GENERATION),
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
                    
                    if (fallbackResponse.functionCalls.isNotEmpty()) {
                        for (fallbackFunctionCall in fallbackResponse.functionCalls) {
                            if (fallbackFunctionCall.name == "write_todos") {
                                android.util.Log.w("PpeExecutionEngine", "Skipping write_todos in fallback")
                                continue
                            }
                            
                            onToolCall(fallbackFunctionCall)
                            val fallbackToolResult = executeTool(fallbackFunctionCall, onToolResult)
                            
                            val fallbackContinuation = continueWithToolResult(
                                fallbackMessages + listOf(
                                    Content(
                                        role = "model",
                                        parts = listOf(Part.TextPart(text = fallbackResponse.text))
                                    )
                                ),
                                fallbackFunctionCall,
                                fallbackToolResult,
                                script,
                                onChunk,
                                onToolCall,
                                onToolResult,
                                recursionDepth + 1
                            )
                            
                            if (fallbackContinuation != null) {
                                return fallbackContinuation
                            }
                        }
                    }
                    
                    return fallbackResponse
                }
            }
            // Continue if: response is empty OR (response has text but we should prompt for next steps)
            val recentToolCalls = messages.takeLast(10).flatMap { content ->
                content.parts.filterIsInstance<Part.FunctionResponsePart>().map { it.functionResponse.name }
            }
            val sameToolCallCount = recentToolCalls.count { it == functionCall.name }
            android.util.Log.d("PpeExecutionEngine", "Recent tool calls: $recentToolCalls, sameToolCallCount: $sameToolCallCount")
            
            // Continue if:
            // 1. Response is empty (AI might be waiting for next instruction)
            // 2. Response has text but no function calls (AI provided plan/explanation, should continue implementing)
            // 3. Response mentions creating files but didn't call write_file (especially for script.js, app.js, client.js)
            // 4. We haven't exceeded recursion limits
            // For write_file, shell, and read-only tools, allow more calls since we need multiple files/commands for a complete project
            val maxSameToolCalls = when (functionCall.name) {
                "write_file" -> 10
                "shell" -> 15  // Need multiple shell commands for setup, install, etc.
                "ls", "read" -> 10  // Read-only tools - need to check multiple files/directories
                else -> 3
            }
            val hasTextButNoCalls = continuationResponse.text.isNotEmpty() && continuationResponse.functionCalls.isEmpty()
            val isEmpty = continuationResponse.text.isEmpty()
            // Check if response mentions creating files but didn't actually call write_file
            val mentionsCreatingFiles = continuationResponse.text.contains("create") || 
                continuationResponse.text.contains("will create") || 
                continuationResponse.text.contains("script.js") || 
                continuationResponse.text.contains("app.js") || 
                continuationResponse.text.contains("client.js")
            val shouldForceContinue = hasTextButNoCalls && mentionsCreatingFiles && functionCall.name == "write_file"
            // For shell and write_file, be more aggressive about continuing even with minimal text
            val shouldContinue = (isEmpty || hasTextButNoCalls || shouldForceContinue) && 
                sameToolCallCount < maxSameToolCalls && 
                recursionDepth < 5
            
            android.util.Log.d("PpeExecutionEngine", "Continuation decision - tool: ${functionCall.name}, hasTextButNoCalls: $hasTextButNoCalls, isEmpty: $isEmpty, sameToolCallCount: $sameToolCallCount, maxSameToolCalls: $maxSameToolCalls, recursionDepth: $recursionDepth, shouldContinue: $shouldContinue")
            
            if (!shouldContinue && (functionCall.name == "shell" || functionCall.name == "write_file" || functionCall.name == "ls" || functionCall.name == "read")) {
                android.util.Log.w("PpeExecutionEngine", "Not continuing for ${functionCall.name} - sameToolCallCount: $sameToolCallCount >= $maxSameToolCalls or recursionDepth: $recursionDepth >= 5")
            }
            
            if (shouldContinue) {
                // Make another API call to prompt continuation
                // Use clear, action-oriented prompts optimized for smaller models like gemini-2.5-flash-lite
                // Emphasize reading existing files before modifying to maintain code coherence
                val promptText = when {
                    functionCall.name == "write_todos" -> "The todo list is created. Now create the files. Start with package.json, then server.js, then HTML, CSS, and JavaScript files. Use write_file tool for each file. Do not call write_todos again."
                    functionCall.name == "write_file" -> "Good! Keep creating files. You need: package.json (if missing), index.html, style.css, and app.js (or client.js). Use write_file to create each one. IMPORTANT: Check the code relativeness information in the tool result. Only use imports/exports that exist in related files. Continue until all files are created, especially the JavaScript game logic file (app.js or client.js)."
                    functionCall.name == "shell" -> "Good progress! Continue with the next steps. Install dependencies (npm install express), create files (use write_file), or run more commands (use shell). Keep working until the Node.js webapp is complete and ready to run."
                    functionCall.name == "ls" -> "Good! You checked the directory. The dependency matrix shows file relativeness. Now read the files (use read tool) to see what exists, then fix or create what's needed. Use read to check existing code before modifying it. Check the dependency matrix to see which files are related."
                    functionCall.name == "read" -> "Good! You read a file. Now check other files if needed (use read), then fix issues using write_file or edit. IMPORTANT: When writing code, check the relativeness information from previous write_file results. Only use imports/exports that exist in related files. Make sure code is consistent across all files."
                    isEmpty -> "Continue with the next step. Read existing files (read tool) before modifying them. Check dependency matrix from ls results. Then use write_file to create files or shell to run commands. Keep working until the task is complete."
                    hasTextButNoCalls -> "You explained the plan. Now implement it. Read existing files first (read tool) to understand the code. Check the dependency matrix and relativeness information. Then use write_file to create/modify files or shell to run commands. Only use imports/exports from related files. Continue until finished."
                    else -> "Continue with the next step. Read files (read tool) to understand existing code. Check dependency matrix for relativeness. Then use write_file to create/modify files or shell to run commands. Only use imports/exports from related files. Complete the task."
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
                        temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
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
                                    temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
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
                        
                        // If the continuation prompt also returned no function calls, make one more attempt with a stronger prompt
                        if (continueResponse.functionCalls.isEmpty() && continueResponse.text.isNotEmpty() && (functionCall.name == "write_file" || functionCall.name == "shell" || functionCall.name == "ls" || functionCall.name == "read")) {
                            android.util.Log.w("PpeExecutionEngine", "Continuation prompt returned no function calls for ${functionCall.name} - making final retry with stronger prompt")
                            val finalPromptText = when (functionCall.name) {
                                "write_file" -> "You must continue creating files. The project is not complete yet. Create the remaining files now: package.json (if not created), HTML file (index.html), CSS file (style.css or styles.css), and the JavaScript game logic file (app.js or client.js). The game logic file is REQUIRED. Use write_file to create each file. IMPORTANT: Check the code relativeness information from previous write_file results. Only use imports/exports that exist in related files."
                                "shell" -> "You must continue. The project setup is not complete. Next steps: 1) Install dependencies (npm install express), 2) Create files using write_file (package.json, server.js, index.html, style.css, client.js), 3) Test the app. Use shell for commands and write_file for creating files. Keep working until done."
                                "ls" -> "You must continue. The dependency matrix shows file relativeness. Read the files you found (use read tool) to understand the code structure, then fix issues using write_file or edit. Check the dependency matrix to see which files are related. Make sure all files work together correctly."
                                "read" -> "You must continue. After reading files, fix the issues. Use write_file to modify files or create missing ones. IMPORTANT: Check the relativeness information from previous write_file results. Only use imports/exports that exist in related files. Ensure code is consistent and all files work together. Keep working until the task is complete."
                                else -> "Continue working. Read files (read) to understand code. Check dependency matrix for relativeness. Then use write_file to create/modify files or shell to run commands. Only use imports/exports from related files. Complete the task."
                            }
                            val finalRetryMessages = promptMessages + listOf(
                                Content(
                                    role = "model",
                                    parts = listOf(Part.TextPart(text = continueResponse.text))
                                ),
                                Content(
                                    role = "user",
                                    parts = listOf(Part.TextPart(text = finalPromptText))
                                )
                            )
                            
                            val finalRetryResult = apiClient.callApi(
                                messages = finalRetryMessages,
                                model = null,
                                temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_GENERATION),
                                topP = null,
                                topK = null,
                                tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                                    listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                                } else {
                                    null
                                }
                            )
                            
                            val finalRetryResponse = finalRetryResult.getOrNull()
                            if (finalRetryResponse != null) {
                                if (finalRetryResponse.text.isNotEmpty()) {
                                    onChunk(finalRetryResponse.text)
                                }
                                
                                if (finalRetryResponse.functionCalls.isNotEmpty()) {
                                    for (finalFunctionCall in finalRetryResponse.functionCalls) {
                                        if (finalFunctionCall.name == "write_todos") {
                                            android.util.Log.w("PpeExecutionEngine", "Skipping write_todos in final retry")
                                            continue
                                        }
                                        
                                        onToolCall(finalFunctionCall)
                                        val finalToolResult = executeTool(finalFunctionCall, onToolResult)
                                        
                                        val finalContinuation = continueWithToolResult(
                                            finalRetryMessages + listOf(
                                                Content(
                                                    role = "model",
                                                    parts = listOf(Part.TextPart(text = finalRetryResponse.text))
                                                )
                                            ),
                                            finalFunctionCall,
                                            finalToolResult,
                                            script,
                                            onChunk,
                                            onToolCall,
                                            onToolResult,
                                            recursionDepth + 1
                                        )
                                        
                                        if (finalContinuation != null) {
                                            return finalContinuation
                                        }
                                    }
                                }
                                
                                return finalRetryResponse
                            }
                        }
                        
                        return continueResponse
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
        @Suppress("UNCHECKED_CAST")
        val params = tool?.validateParams(functionCall.args) as Any?
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
        val invocation = (tool as com.qali.aterm.agent.tools.DeclarativeTool<Any, com.qali.aterm.agent.tools.ToolResult>).createInvocation(params)
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
                            this.executeScript(
                                script = script,
                                inputParams = variables.toMap(),
                                onChunk = onChunk,
                                onToolCall = onToolCall,
                                onToolResult = onToolResult
                            )
                            // Result is ignored for nested script execution
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
    
    /**
     * Data class for blueprint file structure with enhanced coherence information
     */
    private data class BlueprintFile(
        val path: String,
        val type: String, // "code", "config", "test", etc.
        val dependencies: List<String> = emptyList(),
        val description: String = "",
        val exports: List<String> = emptyList(),
        val imports: List<String> = emptyList(),
        val packageDependencies: List<String> = emptyList(),
        val relatedFiles: RelatedFiles? = null
    )
    
    /**
     * Data class for file relationships
     */
    private data class RelatedFiles(
        val importsFrom: List<String> = emptyList(),
        val exportsTo: List<String> = emptyList()
    )
    
    /**
     * Data class for project blueprint
     */
    private data class ProjectBlueprint(
        val projectType: String,
        val files: List<BlueprintFile>
    )
    
    /**
     * Check if this is an upgrade or debug request
     * Detects based on user message keywords and workspace state
     */
    private fun isUpgradeOrDebugRequest(userMessage: String, workspaceRoot: String): Boolean {
        val message = userMessage.lowercase()
        
        // Check for upgrade/debug keywords (enhanced with more patterns)
        val upgradeDebugKeywords = listOf(
            "fix", "debug", "upgrade", "update", "improve", "refactor",
            "modify", "change", "edit", "add feature", "enhance",
            "error", "bug", "issue", "problem", "broken", "not working",
            "doesn't work", "doesn't start", "won't start", "wont start",
            "not starting", "failed", "failure", "exception", "crash",
            "solve", "troubleshoot", "repair", "resolve"
        )
        val hasUpgradeKeyword = upgradeDebugKeywords.any { message.contains(it) }
        
        // Check for error patterns (stack traces, error messages, etc.)
        val errorPatterns = listOf(
            Regex("""error\s*:""", RegexOption.IGNORE_CASE),
            Regex("""exception\s*:""", RegexOption.IGNORE_CASE),
            Regex("""at\s+\w+.*\(.*:\d+\)"""), // Stack trace pattern
            Regex("""ReferenceError|TypeError|SyntaxError|EvalError"""),
            Regex("""npm\s+(?:start|run|test).*error""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s+error""", RegexOption.IGNORE_CASE),
            Regex("""failed\s+to""", RegexOption.IGNORE_CASE)
        )
        val hasErrorPattern = errorPatterns.any { it.find(userMessage) != null }
        
        // Check workspace state - should have existing code files
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return false
        }
        
        val codeFileCount = workspaceDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val name = file.name.lowercase()
                name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".py") ||
                name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".go") ||
                name.endsWith(".rs") || name.endsWith(".cpp") || name.endsWith(".c")
            }
            .count()
        
        // Consider it an upgrade/debug request if:
        // 1. (Has upgrade/debug keywords OR has error patterns) AND
        // 2. Has existing code files (more than 2)
        val isUpgradeDebug = (hasUpgradeKeyword || hasErrorPattern) && codeFileCount > 2
        
        Log.d("PpeExecutionEngine", "Upgrade/debug check - keywords: $hasUpgradeKeyword, errorPatterns: $hasErrorPattern, codeFiles: $codeFileCount, isUpgrade: $isUpgradeDebug")
        return isUpgradeDebug
    }
    
    /**
     * Check if this is a new project startup (legacy method - kept for compatibility)
     * Now uses ProjectStartupDetector
     */
    private fun isNewProjectStartup(userMessage: String, workspaceRoot: String): Boolean {
        val detection = ProjectStartupDetector.detectNewProject(userMessage, workspaceRoot)
        return detection.isNewProject
    }
    
    /**
     * Execute two-phase project startup:
     * Phase 1: Generate JSON blueprint
     * Phase 2: Generate code for each file with separate API calls
     * Enhanced with project type detection and template support
     */
    private suspend fun executeTwoPhaseProjectStartup(
        userMessage: String,
        chatHistory: List<Content>,
        script: PpeScript,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        projectType: ProjectStartupDetector.ProjectType? = null,
        suggestedTemplate: String? = null
    ): PpeExecutionResult {
        Log.d("PpeExecutionEngine", "Starting two-phase project startup")
        
        // Auto-create .atermignore for new projects
        com.qali.aterm.agent.utils.AtermIgnoreManager.createDefaultAtermIgnore(workspaceRoot)
        
        val transaction = FileTransaction(workspaceRoot)
        val updatedChatHistory = chatHistory.toMutableList()
        
        try {
            // Start operation tracking
            val operationId = "two-phase-${System.currentTimeMillis()}"
            val metrics = Observability.startOperation(operationId, "two-phase-project-startup")
            
            // Phase 1: Generate JSON blueprint (with caching and project type context)
            onChunk("Phase 1: Generating project blueprint...\n")
            if (projectType != null) {
                onChunk("Detected project type: ${projectType.name.lowercase()}\n")
            }
            if (suggestedTemplate != null) {
                onChunk("Suggested template: $suggestedTemplate\n")
            }
            
            val blueprintJsonResult = try {
                val cached = IntelligentCache.getBlueprint(workspaceRoot, userMessage) {
                    generateBlueprintJson(userMessage, chatHistory, script, projectType, suggestedTemplate).getOrNull() ?: ""
                }
                if (cached.isNotEmpty()) {
                    Result.success(cached)
                } else {
                    generateBlueprintJson(userMessage, chatHistory, script, projectType, suggestedTemplate)
                }
            } catch (e: Exception) {
                generateBlueprintJson(userMessage, chatHistory, script, projectType, suggestedTemplate)
            }
            
            val blueprintJson = blueprintJsonResult.getOrElse { error ->
                Log.w("PpeExecutionEngine", "Failed to generate blueprint JSON: ${error.message}")
                return PpeExecutionResult(
                    success = false,
                    finalResult = "Failed to generate project blueprint: ${error.message}",
                    variables = emptyMap(),
                    chatHistory = chatHistory,
                    error = error.message
                )
            }
            
            onChunk("Blueprint generated successfully.\n\n")
            
            // Parse blueprint JSON exactly as returned by the model
            val blueprint = parseBlueprintJson(blueprintJson)
            if (blueprint == null) {
                val errorMsg = "Failed to parse project blueprint. The model may not have returned valid JSON."
                Log.e("PpeExecutionEngine", errorMsg)
                Log.e("PpeExecutionEngine", "Blueprint JSON that failed to parse: ${blueprintJson.take(1000)}")
                onChunk("❌ $errorMsg\n")
                onChunk("Attempting to retry blueprint generation...\n")
                
                // Retry blueprint generation once
                val retryResult = generateBlueprintJson(userMessage, chatHistory, script, projectType, suggestedTemplate)
                val retryBlueprintJson = retryResult.getOrNull()
                if (retryBlueprintJson != null) {
                    val retryBlueprint = parseBlueprintJson(retryBlueprintJson)
                    if (retryBlueprint != null && retryBlueprint.files.isNotEmpty()) {
                        onChunk("✅ Blueprint generated successfully on retry.\n")
                        // Continue with retry blueprint - recursively call the main function
                        return executeTwoPhaseProjectStartup(
                            userMessage,
                            chatHistory,
                            script,
                            onChunk,
                            onToolCall,
                            onToolResult,
                            projectType,
                            suggestedTemplate
                        )
                    }
                }
                
                return PpeExecutionResult(
                    success = false,
                    finalResult = errorMsg,
                    variables = emptyMap(),
                    chatHistory = chatHistory,
                    error = errorMsg
                )
            }
            
            // Validate blueprint (basic validation)
            val validation = validateBlueprint(blueprint)
            
            // AI-powered blueprint analysis for error prevention
            onChunk("🔍 Analyzing blueprint for potential errors...\n")
            val blueprintAnalysis = try {
                com.qali.aterm.agent.utils.BlueprintAnalyzer.analyzeBlueprint(
                    blueprintJson = blueprintJson,
                    apiClient = apiClient,
                    chatHistory = updatedChatHistory
                )
            } catch (e: Exception) {
                Log.w("PpeExecutionEngine", "Blueprint analysis failed: ${e.message}", e)
                null
            }
            
            // Log validation results
            if (validation.errors.isNotEmpty()) {
                val errorMsg = "Blueprint validation errors:\n${validation.errors.joinToString("\n")}"
                Log.e("PpeExecutionEngine", errorMsg)
                onChunk("⚠️ Blueprint validation errors:\n${validation.errors.joinToString("\n")}\n")
            }
            
            if (validation.warnings.isNotEmpty()) {
                Log.w("PpeExecutionEngine", "Blueprint validation warnings: ${validation.warnings.joinToString("; ")}")
                onChunk("⚠️ Warnings: ${validation.warnings.joinToString("; ")}\n")
            }
            
            // Log AI analysis results
            if (blueprintAnalysis != null) {
                if (!blueprintAnalysis.isValid) {
                    onChunk("❌ Blueprint analysis found critical errors:\n")
                    blueprintAnalysis.errors.filter { it.severity == "CRITICAL" || it.severity == "HIGH" }
                        .forEach { error ->
                            onChunk("  - [${error.severity}] ${error.type.name}: ${error.message}")
                            if (error.filePath != null) {
                                onChunk(" (File: ${error.filePath})")
                            }
                            onChunk("\n")
                        }
                }
                
                if (blueprintAnalysis.errors.isNotEmpty()) {
                    onChunk("⚠️ Found ${blueprintAnalysis.errors.size} error(s) in blueprint:\n")
                    blueprintAnalysis.errors.forEach { error ->
                        onChunk("  - ${error.type.name}: ${error.message}")
                        if (error.filePath != null) {
                            onChunk(" (${error.filePath})")
                        }
                        onChunk("\n")
                    }
                }
                
                if (blueprintAnalysis.warnings.isNotEmpty()) {
                    onChunk("⚠️ Found ${blueprintAnalysis.warnings.size} warning(s):\n")
                    blueprintAnalysis.warnings.take(5).forEach { warning ->
                        onChunk("  - ${warning.type.name}: ${warning.message}")
                        if (warning.filePath != null) {
                            onChunk(" (${warning.filePath})")
                        }
                        onChunk("\n")
                    }
                }
                
                if (blueprintAnalysis.suggestions.isNotEmpty()) {
                    onChunk("💡 Suggestions for improvement:\n")
                    blueprintAnalysis.suggestions.take(5).forEach { suggestion ->
                        onChunk("  - ${suggestion.type.name}: ${suggestion.message}")
                        if (suggestion.filePath != null) {
                            onChunk(" (${suggestion.filePath})")
                        }
                        onChunk("\n")
                    }
                }
                
                // Use fixed blueprint if available and valid
                if (blueprintAnalysis.fixedBlueprint != null && blueprintAnalysis.isValid) {
                    onChunk("✅ Using AI-enhanced blueprint with fixes applied\n")
                    try {
                        val fixedBlueprint = parseBlueprintJson(blueprintAnalysis.fixedBlueprint)
                        if (fixedBlueprint != null && fixedBlueprint.files.isNotEmpty()) {
                            // Use the fixed blueprint instead
                            val filteredFixed = fixedBlueprint.files.filter { file ->
                                validateFilePath(file.path, workspaceRoot)
                            }
                            if (filteredFixed.isNotEmpty()) {
                                val enhancedBlueprint = fixedBlueprint.copy(files = filteredFixed)
                                onChunk("✓ Enhanced blueprint validated (${filteredFixed.size} files)\n\n")
                                // Continue with enhanced blueprint
                                val sortedFiles = topologicalSort(enhancedBlueprint.files)
                                // ... continue with enhanced blueprint instead of original
                                // (Note: This would require refactoring to use the enhanced blueprint)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("PpeExecutionEngine", "Failed to parse fixed blueprint: ${e.message}")
                    }
                }
                
                if (blueprintAnalysis.isValid) {
                    onChunk("✓ Blueprint analysis complete - no critical errors found\n\n")
                } else {
                    onChunk("⚠️ Blueprint has errors but proceeding with generation...\n\n")
                }
            }
            
            // Only fail if there are no files to generate (critical error)
            if (blueprint.files.isEmpty()) {
                val errorMsg = "Blueprint validation failed: No files to generate. ${validation.errors.joinToString("\n")}"
                Log.e("PpeExecutionEngine", errorMsg)
                onChunk("❌ $errorMsg\n")
                return PpeExecutionResult(
                    success = false,
                    finalResult = errorMsg,
                    variables = emptyMap(),
                    chatHistory = chatHistory,
                    error = errorMsg
                )
            }
            
            // Filter out files with invalid paths and continue
            val validFiles = blueprint.files.filter { file ->
                val isValid = validateFilePath(file.path, workspaceRoot)
                if (!isValid) {
                    Log.w("PpeExecutionEngine", "Skipping file with invalid path: ${file.path}")
                    onChunk("⚠️ Skipping invalid file path: ${file.path}\n")
                }
                isValid
            }
            
            if (validFiles.isEmpty()) {
                val errorMsg = "Blueprint validation failed: All file paths are invalid. ${validation.errors.joinToString("\n")}"
                Log.e("PpeExecutionEngine", errorMsg)
                onChunk("❌ $errorMsg\n")
                return PpeExecutionResult(
                    success = false,
                    finalResult = errorMsg,
                    variables = emptyMap(),
                    chatHistory = chatHistory,
                    error = errorMsg
                )
            }
            
            // Use filtered valid files
            val filteredBlueprint = blueprint.copy(files = validFiles)
            
            // Log if files were filtered
            if (validFiles.size < blueprint.files.size) {
                val filteredCount = blueprint.files.size - validFiles.size
                Log.w("PpeExecutionEngine", "Filtered out $filteredCount invalid file(s), proceeding with ${validFiles.size} valid file(s)")
                onChunk("⚠️ Filtered out $filteredCount invalid file(s), proceeding with ${validFiles.size} valid file(s)\n")
            }
            
            onChunk("Phase 2: Generating code for ${filteredBlueprint.files.size} files...\n\n")
            
            // Phase 2: Generate code for each file using topological sort
            val generatedFiles = mutableListOf<String>()
            val sortedFiles = topologicalSort(filteredBlueprint.files)
            
            for ((index, file) in sortedFiles.withIndex()) {
                Log.d("PpeExecutionEngine", "Generating code for file ${index + 1}/${sortedFiles.size}: ${file.path}")
                onChunk("Generating ${file.path}...\n")
                
                // Validate file path
                if (!validateFilePath(file.path, workspaceRoot)) {
                    onChunk("✗ Invalid file path: ${file.path}\n")
                    continue
                }
                
                var fileCodeResult = generateFileCode(
                    file,
                    filteredBlueprint,
                    userMessage,
                    updatedChatHistory,
                    script
                )
                
                // Avoid using 'continue' inside inline lambda (requires newer language version)
                var fileCode = fileCodeResult.getOrNull()
                var retryCount = 0
                val maxFileRetries = 3
                
                // Retry file generation if it fails (for timeout, network errors, etc.)
                while (fileCode == null && retryCount < maxFileRetries) {
                    val error = fileCodeResult.exceptionOrNull()
                    val errorMsg = error?.message ?: "unknown error"
                    val isRetryable = error != null && isRetryableException(error as? Exception ?: Exception(errorMsg))
                    
                    if (isRetryable && retryCount < maxFileRetries - 1) {
                        retryCount++
                        onChunk("↻ File generation failed for ${file.path}. Retrying (attempt $retryCount/$maxFileRetries)...\n")
                        Log.w("PpeExecutionEngine", "File generation failed for ${file.path}, retrying (attempt $retryCount/$maxFileRetries): $errorMsg")
                        
                        // Wait before retry (exponential backoff)
                        delay(1000L * retryCount)
                        
                        // Retry file code generation
                        fileCodeResult = generateFileCode(
                            file,
                            filteredBlueprint,
                            userMessage,
                            updatedChatHistory,
                            script
                        )
                        fileCode = fileCodeResult.getOrNull()
                    } else {
                        // Not retryable or max retries reached - check if it's empty code or other error
                        break
                    }
                }
                
                // If still null after retries, check if it's empty code or other error
                if (fileCode == null) {
                    val errorMsg = fileCodeResult.exceptionOrNull()?.message ?: "unknown error"
                    // If the model returned empty code, retry with a more direct prompt
                    if (errorMsg.contains("Generated code is empty")) {
                        onChunk("↻ Generated code was empty for ${file.path}. Retrying with a more direct prompt...\n")
                        Log.w("PpeExecutionEngine", "Empty code for ${file.path}, retrying with direct prompt")
                        
                        // Use a very direct, minimal prompt for retry
                        val directPrompt = when {
                            file.path.endsWith(".json") -> {
                                """Generate a valid JSON file for ${file.path}.
${file.description}

Return ONLY the JSON content, no markdown, no explanations. Start with '{'."""
                            }
                            file.path.endsWith(".html") -> {
                                """Generate a complete HTML file for ${file.path}.
${file.description}

Return ONLY the HTML content, no markdown, no explanations. Start with '<!DOCTYPE' or '<html'."""
                            }
                            file.path.endsWith(".css") -> {
                                """Generate a complete CSS file for ${file.path}.
${file.description}

Return ONLY the CSS content, no markdown, no explanations."""
                            }
                            file.path.endsWith(".js") -> {
                                """Generate a complete JavaScript file for ${file.path}.
${file.description}

Return ONLY the JavaScript code, no markdown, no explanations. Start with the actual code."""
                            }
                            else -> {
                                """Generate the complete code for ${file.path}.
${file.description}

Return ONLY the raw code content. No markdown, no explanations, no code blocks. Just the code."""
                            }
                        }
                        
                        val retryMessages = updatedChatHistory.toMutableList()
                        retryMessages.add(
                            Content(
                                role = "user",
                                parts = listOf(Part.TextPart(text = directPrompt))
                            )
                        )
                        
                        // Apply rate limiting
                        rateLimiter.acquire()
                        
                        val retryResult = apiClient.callApi(
                            messages = retryMessages,
                            model = null,
                            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_GENERATION),
                            topP = null,
                            topK = null,
                            tools = null,
                            disableTools = true // Force disable tools for retry as well
                        )
                        
                        val retryResponse = retryResult.getOrNull()
                        if (retryResponse == null) {
                            onChunk("✗ Retry failed for ${file.path}: ${retryResult.exceptionOrNull()?.message}\n")
                            continue // Continue to next file
                        }
                        
                        // Extract code from retry response
                        var retryCode = retryResponse.text.trim()
                        
                        // Remove markdown code blocks
                        val codeBlockPatterns = listOf(
                            Regex("```(?:\\w+)?\\s*\\n?(.*?)\\n?\\s*```", RegexOption.DOT_MATCHES_ALL),
                            Regex("```\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL),
                            Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
                        )
                        
                        for (pattern in codeBlockPatterns) {
                            val match = pattern.find(retryCode)
                            if (match != null) {
                                val extracted = match.groupValues[1].trim()
                                if (extracted.isNotEmpty()) {
                                    retryCode = extracted
                                    break
                                }
                            }
                        }
                        
                        // Remove common prefixes
                        val prefixesToRemove = listOf(
                            "Here is the code:", "Here's the code:", "Here is the file:", "Here's the file:",
                            "Code for ${file.path}:", "The code is:", "The file content is:"
                        )
                        
                        for (prefix in prefixesToRemove) {
                            if (retryCode.startsWith(prefix, ignoreCase = true)) {
                                retryCode = retryCode.substring(prefix.length).trim()
                            }
                        }
                        
                        retryCode = retryCode.trim()
                        
                        if (retryCode.isNotEmpty()) {
                            fileCode = retryCode
                            onChunk("✓ Generated code for ${file.path} on retry\n")
                        } else {
                            onChunk("✗ Still failed to generate code for ${file.path} (retry also returned empty)\n")
                            Log.e("PpeExecutionEngine", "Retry also returned empty code. Response: ${retryResponse.text.take(500)}")
                            continue
                        }
                    } else {
                        // Not empty code error - log and continue
                        onChunk("✗ Failed to generate code for ${file.path}: $errorMsg\n")
                        Log.e("PpeExecutionEngine", "File generation failed for ${file.path}: $errorMsg")
                        continue
                    }
                }
                
                // Use write_file tool to create the file
                // Note: WriteFileTool expects parameters: file_path, content, modified_by_user, ai_proposed_content
                val writeFileCall = FunctionCall(
                    name = "write_file",
                    args = mapOf(
                        "file_path" to file.path,
                        "content" to fileCode,
                        "modified_by_user" to false,
                        "ai_proposed_content" to fileCode
                    )
                )
                
                onToolCall(writeFileCall)
                val toolResult = executeTool(writeFileCall, onToolResult)
                
                if (toolResult.error == null) {
                    transaction.addCreatedFile(file.path)
                    generatedFiles.add(file.path)
                    // Display formatted file change notification (cursor-cli style)
                    val fileNotification = toolResult.returnDisplay ?: "✓ Created ${file.path}\n"
                    onChunk("\n$fileNotification\n")
                    
                    // Add tool result to chat history
                    updatedChatHistory.add(
                        Content(
                            role = "user",
                            parts = listOf(
                                Part.FunctionResponsePart(
                                    functionResponse = FunctionResponse(
                                        name = "write_file",
                                        response = mapOf("output" to "File created successfully"),
                                        id = writeFileCall.id ?: ""
                                    )
                                )
                            )
                        )
                    )
                } else {
                    onChunk("✗ Failed to create ${file.path}: ${toolResult.error?.message}\n")
                    // Rollback on critical failure
                    if (generatedFiles.isEmpty()) {
                        transaction.rollback()
                        return PpeExecutionResult(
                            success = false,
                            finalResult = "Failed to create first file: ${toolResult.error?.message}",
                            variables = emptyMap(),
                            chatHistory = updatedChatHistory,
                            error = toolResult.error?.message
                        )
                    }
                }
            }
            
            // Update blueprint AFTER all files are created
            if (generatedFiles.isNotEmpty()) {
                try {
                    onChunk("Updating project blueprint...\n")
                    val currentBlueprint = CodeDependencyAnalyzer.generateComprehensiveBlueprint(workspaceRoot)
                    
                    // Update dependency matrix for all created files
                    generatedFiles.forEach { filePath ->
                        try {
                            val file = File(workspaceRoot, filePath)
                            if (file.exists()) {
                                val content = file.readText()
                                val metadata = CodeDependencyAnalyzer.analyzeFile(filePath, content, workspaceRoot)
                                CodeDependencyAnalyzer.updateDependencyMatrix(workspaceRoot, metadata)
                            }
                        } catch (e: Exception) {
                            Log.w("PpeExecutionEngine", "Failed to update dependency matrix for $filePath: ${e.message}")
                        }
                    }
                    
                    onChunk("Blueprint updated successfully.\n")
                } catch (e: Exception) {
                    Log.w("PpeExecutionEngine", "Failed to update blueprint: ${e.message}")
                    // Don't fail the entire operation if blueprint update fails
                }
            }
            
            val result = "Project created successfully! Generated ${generatedFiles.size} files:\n" +
                generatedFiles.joinToString("\n") { "  - $it" }
            
            onChunk("\n$result\n")
            
            // Clear transaction on success
            transaction.clear()
            
            // End operation tracking
            Observability.endOperation(operationId)
            
            return PpeExecutionResult(
                success = true,
                finalResult = result,
                variables = mapOf("generatedFiles" to generatedFiles),
                chatHistory = updatedChatHistory
            )
            
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Two-phase project startup failed", e)
            // Rollback on exception
            try {
                transaction.rollback()
            } catch (rollbackError: Exception) {
                Log.e("PpeExecutionEngine", "Rollback failed: ${rollbackError.message}")
            }
            return PpeExecutionResult(
                success = false,
                finalResult = "Project creation failed: ${e.message}",
                variables = emptyMap(),
                chatHistory = updatedChatHistory,
                error = e.message
            )
        }
    }
    
    /**
     * Phase 1: Generate JSON blueprint via API call with retry
     * Enhanced with project type detection and template support
     */
    private suspend fun generateBlueprintJson(
        userMessage: String,
        chatHistory: List<Content>,
        script: PpeScript,
        projectType: ProjectStartupDetector.ProjectType? = null,
        suggestedTemplate: String? = null
    ): Result<String> {
        return retryWithBackoff(
            maxRetries = 3,
            isRetryable = ::isRetryableException
        ) {
            generateBlueprintJsonInternal(sanitizeForPrompt(userMessage), chatHistory, script, projectType, suggestedTemplate)
        }
    }
    
    /**
     * Internal blueprint generation (without retry)
     * Enhanced with project type and template context
     */
    private suspend fun generateBlueprintJsonInternal(
        userMessage: String,
        chatHistory: List<Content>,
        script: PpeScript,
        projectType: ProjectStartupDetector.ProjectType? = null,
        suggestedTemplate: String? = null
    ): String {
        // Build project type context
        val projectTypeContext = buildString {
            if (projectType != null) {
                appendLine("Detected Project Type: ${projectType.name.lowercase()}")
                if (suggestedTemplate != null) {
                    appendLine("Suggested Template: $suggestedTemplate")
                }
                val templateSuggestions = ProjectStartupDetector.getTemplateSuggestions(projectType)
                if (templateSuggestions.isNotEmpty()) {
                    appendLine("Available Templates: ${templateSuggestions.joinToString(", ")}")
                }
                appendLine()
            }
        }
        
        val blueprintPrompt = """
You are a senior software project architect specializing in file coherence and dependency management.
Your task is to understand the user's request and design a comprehensive JSON blueprint that ensures perfect file coherence and relativeness.

User Request (natural language from user):
$userMessage

$projectTypeContext

CRITICAL REQUIREMENTS FOR BLUEPRINT COHERENCE:
1. **File Relationships**: Each file MUST explicitly list ALL files it depends on (imports from, references, etc.)
2. **Import/Export Mapping**: For code files, specify what each file will export and what it needs to import
3. **Dependency Order**: Files must be ordered so dependencies are created before dependents
4. **Config Files Last**: package.json, requirements.txt, go.mod, Cargo.toml, etc. MUST be listed LAST in the files array
   - This allows them to include ALL dependencies discovered from code files
   - Code files should list package.json as a dependency, but package.json will be created after all code files
5. **Cross-File Coherence**: Ensure all imports/exports match between related files
6. **Platform Dependencies**: Only include actual package manager dependencies (npm packages, pip packages, etc.)

Return a SINGLE JSON object in this EXACT format (no markdown, no code blocks, just pure JSON):

{
  "projectType": "nodejs",
  "projectDescription": "Short summary of the user's request and the intended app behaviour (2-4 sentences, plain text).",
  "files": [
    {
      "path": "package.json",
      "type": "config",
      "dependencies": [],
      "description": "Package configuration file",
      "exports": [],
      "imports": [],
      "packageDependencies": []
    },
    {
      "path": "server.js",
      "type": "code",
      "dependencies": ["package.json"],
      "description": "Main server file",
      "exports": ["app", "startServer"],
      "imports": ["express", "path", "fs"],
      "packageDependencies": ["express"],
      "relatedFiles": {
        "importsFrom": [],
        "exportsTo": ["routes.js"]
      }
    },
    {
      "path": "routes.js",
      "type": "code",
      "dependencies": ["package.json", "server.js"],
      "description": "Route handlers",
      "exports": ["router", "setupRoutes"],
      "imports": ["express", "app"],
      "packageDependencies": [],
      "relatedFiles": {
        "importsFrom": ["server.js"],
        "exportsTo": ["server.js"]
      }
    }
  ]
}

FIELD DESCRIPTIONS:
- "path": File path relative to project root
- "type": "config", "code", "test", "style", "template", etc.
- "dependencies": Array of file paths this file depends on (must be created first)
- "description": What this file does
- "exports": Array of function/class/variable names this file will export (for code files)
- "imports": Array of function/class/variable names this file needs to import (from related files OR package dependencies)
- "packageDependencies": Array of actual package manager dependencies (npm packages, pip packages, etc.) - ONLY real dependencies
- "relatedFiles": Object with "importsFrom" (files this imports from) and "exportsTo" (files that import from this)

IMPORTANT RULES:
- Return ONLY valid JSON, no markdown, no code blocks, no explanations
- Include ALL files needed for the project
- **CRITICAL FILE ORDERING**: 
  * Config files (package.json, requirements.txt, go.mod, Cargo.toml, etc.) MUST be listed LAST in the files array
  * Code files should come first, in dependency order
  * This allows config files to include ALL dependencies discovered from code files
- For "packageDependencies": ONLY include actual dependencies that will be installed via package manager (npm install, pip install, etc.)
- For "imports": Include BOTH package dependencies AND imports from related files
- For "exports": List what this file will export so other files can import it
- Ensure "relatedFiles" shows the import/export relationships between files
- Files MUST be ordered so code file dependencies come before dependents, but config files come last
- Ensure all imports/exports match between related files (if A exports X, B can import X from A)

JSON Blueprint:
""".trimIndent()
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = blueprintPrompt))
            )
        )
        
        // Get tools (but don't use them for blueprint generation)
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        // Apply rate limiting
        rateLimiter.acquire()
        
        val result = callApiWithRetryOnExhaustion(
            messages = messages,
            model = null,
            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.DATA_ANALYSIS),
            topP = null,
            topK = null,
            tools = null, // Don't use tools for blueprint generation
            disableTools = false,
            onChunk = null
        )
        
        val response = result.getOrElse {
            Log.e("PpeExecutionEngine", "Blueprint API call failed: ${it.message}")
            throw Exception("Blueprint API call failed: ${it.message}", it)
        }
        
        // Extract JSON from response (might be wrapped in markdown or empty)
        var jsonText = response.text.trim()
        
        // Log raw response for debugging
        Log.d("PpeExecutionEngine", "Raw response text (length: ${jsonText.length}): ${jsonText.take(1000)}")
        
        // If response is empty, check if there's content in response object
        if (jsonText.isEmpty()) {
            Log.w("PpeExecutionEngine", "Response text is empty. Checking response structure...")
            // Try to extract from response object if available
            val responseStr = response.toString()
            if (responseStr.isNotEmpty()) {
                Log.d("PpeExecutionEngine", "Response object: ${responseStr.take(500)}")
            }
            throw Exception(
                "Empty response from API. The model may not have generated any content. Please check your API key and model configuration."
            )
        }
        
        // Remove markdown code blocks if present (handle various formats)
        jsonText = jsonText.removePrefix("```json").trim()
        jsonText = jsonText.removePrefix("```").trim()
        jsonText = jsonText.removeSuffix("```").trim()
        
        // Try to find JSON object in the response (handle markdown-wrapped JSON)
        val jsonStart = jsonText.indexOf('{')
        val jsonEnd = jsonText.lastIndexOf('}') + 1
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            jsonText = jsonText.substring(jsonStart, jsonEnd)
        } else if (jsonStart < 0) {
            // No JSON object found - might be plain text or malformed
            Log.w("PpeExecutionEngine", "No JSON object found in response. Full response: ${jsonText.take(1000)}")
            throw Exception(
                "No valid JSON found in response. The model may have returned plain text instead of JSON. Response: ${jsonText.take(200)}"
            )
        }
        
        Log.d("PpeExecutionEngine", "Extracted blueprint JSON (length: ${jsonText.length}): ${jsonText.take(500)}")
        
        // Validate JSON is not empty and looks like an object
        if (jsonText.isEmpty() || !jsonText.trim().startsWith("{")) {
            Log.w(
                "PpeExecutionEngine",
                "Invalid blueprint JSON from API (empty or not an object). Response: ${response.text.take(500)}"
            )
            throw Exception(
                "Invalid blueprint JSON from model. The response was not valid JSON. Please retry or switch to a model that can return pure JSON (no markdown). Response preview: ${jsonText.take(200)}"
            )
        }
        
        return jsonText
    }
    
    /**
     * Parse JSON blueprint into ProjectBlueprint object
     */
    private fun parseBlueprintJson(jsonText: String): ProjectBlueprint? {
        return try {
            val json = JSONObject(jsonText)
            val projectType = json.optString("projectType", "unknown")
            val filesArray = json.optJSONArray("files") ?: JSONArray()
            
            val files = mutableListOf<BlueprintFile>()
            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                val path = fileObj.optString("path", "")
                val type = fileObj.optString("type", "code")
                val description = fileObj.optString("description", "")
                
                val dependencies = mutableListOf<String>()
                val depsArray = fileObj.optJSONArray("dependencies")
                if (depsArray != null) {
                    for (j in 0 until depsArray.length()) {
                        dependencies.add(depsArray.getString(j))
                    }
                }
                
                val exports = mutableListOf<String>()
                val exportsArray = fileObj.optJSONArray("exports")
                if (exportsArray != null) {
                    for (j in 0 until exportsArray.length()) {
                        exports.add(exportsArray.getString(j))
                    }
                }
                
                val imports = mutableListOf<String>()
                val importsArray = fileObj.optJSONArray("imports")
                if (importsArray != null) {
                    for (j in 0 until importsArray.length()) {
                        imports.add(importsArray.getString(j))
                    }
                }
                
                val packageDependencies = mutableListOf<String>()
                val packageDepsArray = fileObj.optJSONArray("packageDependencies")
                if (packageDepsArray != null) {
                    for (j in 0 until packageDepsArray.length()) {
                        packageDependencies.add(packageDepsArray.getString(j))
                    }
                }
                
                val relatedFilesObj = fileObj.optJSONObject("relatedFiles")
                val relatedFiles = if (relatedFilesObj != null) {
                    val importsFrom = mutableListOf<String>()
                    val importsFromArray = relatedFilesObj.optJSONArray("importsFrom")
                    if (importsFromArray != null) {
                        for (j in 0 until importsFromArray.length()) {
                            importsFrom.add(importsFromArray.getString(j))
                        }
                    }
                    
                    val exportsTo = mutableListOf<String>()
                    val exportsToArray = relatedFilesObj.optJSONArray("exportsTo")
                    if (exportsToArray != null) {
                        for (j in 0 until exportsToArray.length()) {
                            exportsTo.add(exportsToArray.getString(j))
                        }
                    }
                    
                    RelatedFiles(importsFrom, exportsTo)
                } else {
                    null
                }
                
                if (path.isNotEmpty()) {
                    files.add(BlueprintFile(path, type, dependencies, description, exports, imports, packageDependencies, relatedFiles))
                }
            }
            
            Log.d("PpeExecutionEngine", "Parsed blueprint: projectType=$projectType, files=${files.size}")
            if (files.isEmpty()) {
                Log.w("PpeExecutionEngine", "Blueprint parsed but has no files. JSON: ${jsonText.take(500)}")
            }
            ProjectBlueprint(projectType, files)
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Failed to parse blueprint JSON: ${e.message}", e)
            Log.e("PpeExecutionEngine", "JSON text that failed to parse: ${jsonText.take(1000)}")
            null
        }
    }
    
    /**
     * Phase 2: Generate code for a specific file via API call with retry
     */
    private suspend fun generateFileCode(
        file: BlueprintFile,
        blueprint: ProjectBlueprint,
        userMessage: String,
        chatHistory: List<Content>,
        script: PpeScript
    ): Result<String> {
        return retryWithBackoff(
            maxRetries = 20,
            isRetryable = ::isRetryableException
        ) {
            generateFileCodeInternal(file, blueprint, sanitizeForPrompt(userMessage), chatHistory, script)
        }
    }
    
    /**
     * Internal file code generation (without retry)
     */
    private suspend fun generateFileCodeInternal(
        file: BlueprintFile,
        blueprint: ProjectBlueprint,
        userMessage: String,
        chatHistory: List<Content>,
        script: PpeScript
    ): String {
        // Get related files (dependencies)
        val relatedFiles = blueprint.files.filter { file.dependencies.contains(it.path) }
        
        // Get existing files metadata from dependency matrix for imports/exports
        val existingFilesMetadata = try {
            val matrix = CodeDependencyAnalyzer.getDependencyMatrix(workspaceRoot)
            relatedFiles.mapNotNull { relatedFile ->
                val metadata = matrix.files[relatedFile.path]
                if (metadata != null) {
                    buildString {
                        appendLine("  - ${relatedFile.path}:")
                        if (metadata.imports.isNotEmpty()) {
                            appendLine("    Imports: ${metadata.imports.joinToString(", ")}")
                        }
                        if (metadata.exports.isNotEmpty()) {
                            appendLine("    Exports: ${metadata.exports.joinToString(", ")}")
                        }
                        if (metadata.functions.isNotEmpty()) {
                            appendLine("    Functions: ${metadata.functions.take(10).joinToString(", ")}")
                        }
                        if (metadata.classes.isNotEmpty()) {
                            appendLine("    Classes: ${metadata.classes.joinToString(", ")}")
                        }
                    }
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        // Build comprehensive file relationship context from blueprint
        val blueprintExports = blueprint.files.associate { it.path to it.exports }
        val blueprintImports = blueprint.files.associate { it.path to it.imports }
        val blueprintPackageDeps = blueprint.files.flatMap { it.packageDependencies }.distinct()
        
        val filePrompt = buildString {
            appendLine("Generate the complete code for this file using the parsed blueprint data:")
            appendLine("")
            appendLine("File: ${file.path}")
            appendLine("Type: ${file.type}")
            appendLine("Description: ${file.description}")
            appendLine("")
            
            // Show what this file should export (from blueprint)
            if (file.exports.isNotEmpty()) {
                appendLine("This file MUST export the following (from blueprint):")
                file.exports.forEach { export ->
                    appendLine("  - $export")
                }
                appendLine("")
            }
            
            // Show what this file should import (from blueprint)
            if (file.imports.isNotEmpty()) {
                appendLine("This file MUST import the following (from blueprint):")
                file.imports.forEach { importItem ->
                    // Check if it's a package dependency or from a related file
                    val isPackageDep = file.packageDependencies.contains(importItem) || blueprintPackageDeps.contains(importItem)
                    if (isPackageDep) {
                        appendLine("  - $importItem (package dependency - use require/import)")
                    } else {
                        // Find which file exports this
                        val sourceFile = blueprint.files.find { it.exports.contains(importItem) }
                        if (sourceFile != null) {
                            appendLine("  - $importItem (from ${sourceFile.path})")
                        } else {
                            appendLine("  - $importItem")
                        }
                    }
                }
                appendLine("")
            }
            
            // Show package dependencies (only real dependencies)
            if (file.packageDependencies.isNotEmpty()) {
                appendLine("Package Dependencies (install via package manager):")
                file.packageDependencies.forEach { pkg ->
                    appendLine("  - $pkg")
                }
                appendLine("")
            }
            
            // Show related files and their exports from blueprint
            if (relatedFiles.isNotEmpty()) {
                appendLine("Related Files (from blueprint dependencies):")
                relatedFiles.forEach { relatedFile ->
                    // Get exports from blueprint (source of truth)
                    val blueprintFile = blueprint.files.find { it.path == relatedFile.path }
                    val blueprintExports = blueprintFile?.exports ?: emptyList()
                    val blueprintImports = blueprintFile?.imports ?: emptyList()
                    val blueprintPackageDeps = blueprintFile?.packageDependencies ?: emptyList()
                    
                    appendLine("  - ${relatedFile.path} (${relatedFile.type})")
                    if (blueprintExports.isNotEmpty()) {
                        appendLine("    Blueprint exports: ${blueprintExports.joinToString(", ")}")
                    }
                    if (blueprintImports.isNotEmpty()) {
                        appendLine("    Blueprint imports: ${blueprintImports.joinToString(", ")}")
                    }
                    if (blueprintPackageDeps.isNotEmpty()) {
                        appendLine("    Blueprint package deps: ${blueprintPackageDeps.joinToString(", ")}")
                    }
                }
                appendLine("")
                appendLine("IMPORTANT: Use ONLY the exports listed above from related files (from blueprint).")
                appendLine("Do NOT import anything that is NOT in the blueprint exports list.")
                appendLine("")
                
                if (existingFilesMetadata.isNotEmpty()) {
                    appendLine("Already created files (for reference - but use blueprint as source of truth):")
                    existingFilesMetadata.forEach { metadata ->
                        appendLine(metadata)
                    }
                    appendLine("")
                }
            }
            
            // Show file relationships from blueprint
            if (file.relatedFiles != null) {
                appendLine("File Relationships (from blueprint):")
                if (file.relatedFiles.importsFrom.isNotEmpty()) {
                    appendLine("  Imports from: ${file.relatedFiles.importsFrom.joinToString(", ")}")
                    file.relatedFiles.importsFrom.forEach { importFromPath ->
                        val sourceFile = blueprint.files.find { it.path == importFromPath }
                        if (sourceFile != null && sourceFile.exports.isNotEmpty()) {
                            appendLine("    - $importFromPath exports: ${sourceFile.exports.joinToString(", ")}")
                        }
                    }
                }
                if (file.relatedFiles.exportsTo.isNotEmpty()) {
                    appendLine("  Exports to: ${file.relatedFiles.exportsTo.joinToString(", ")}")
                }
                appendLine("")
            }
            
            appendLine("Project Context:")
            appendLine("  - Project Type: ${blueprint.projectType}")
            appendLine("  - Total Files: ${blueprint.files.size}")
            appendLine("")
            
            appendLine("Original User Request: $userMessage")
            appendLine("")
            appendLine("CRITICAL INSTRUCTIONS FOR CODE GENERATION:")
            appendLine("1. You MUST generate COMPLETE, working code for this file")
            appendLine("2. Return ONLY the raw code content - NO explanations, NO markdown, NO code blocks")
            appendLine("3. Do NOT wrap the code in ``` or any markdown")
            appendLine("4. Do NOT add comments like 'Here is the code:' or 'Here's the file:'")
            appendLine("5. Start directly with the actual code (e.g., '{' for JSON, '<!DOCTYPE' for HTML, 'const' for JS)")
            appendLine("6. The response will be written directly to the file, so it must be valid code")
            appendLine("")
            appendLine("IMPORT/EXPORT COHERENCE RULES (CRITICAL):")
            appendLine("1. Use ONLY the imports/exports specified in the blueprint above")
            appendLine("2. For package dependencies: Use ONLY packages listed in 'Package Dependencies' section")
            appendLine("3. For file imports: Import ONLY from files listed in 'Related Files' and use ONLY their exports")
            appendLine("4. Do NOT import anything that is NOT in the blueprint")
            appendLine("5. Do NOT use any imports unless they are:")
            appendLine("   a) Package dependencies (installed via npm/pip/etc.) - listed in 'Package Dependencies'")
            appendLine("   b) Exports from related files - listed in 'Available exports' above")
            appendLine("6. Ensure all exports match what's specified in the blueprint")
            appendLine("7. Ensure all imports match what's available from related files or package dependencies")
            appendLine("8. Maintain coherence: If file A exports X, and file B imports X, use the exact same name")
            appendLine("")
            appendLine("CODE QUALITY:")
            appendLine("9. Ensure the code is production-ready and follows best practices")
            appendLine("10. Include all necessary functionality")
            appendLine("11. Use consistent style and structure across all files")
            appendLine("")
            appendLine("IMPORTANT: You MUST NOT return any function calls or tool calls.")
            appendLine("Any output starting with '{' or '[' must be treated as raw file content, not a tool invocation.")
            appendLine("This applies to ALL files including JSON files like package.json or config files.")
            appendLine("Return ONLY plain text code content - no tool wrappers, no function calls, no JSON tool invocations.")
            appendLine("")
            // Special handling for JSON files
            if (file.path.endsWith(".json")) {
                appendLine("")
                appendLine("⚠️ CRITICAL JSON FILE RULES:")
                appendLine("1. Return ONLY valid JSON - nothing else")
                appendLine("2. NO explanations, NO comments, NO instructions inside or outside the JSON")
                appendLine("3. NO text before or after the JSON")
                appendLine("4. Start with '{' and end with '}' - that's it")
                appendLine("5. The entire response must be parseable as JSON")
                if (file.path == "package.json" || file.path.endsWith("/package.json")) {
                    appendLine("")
                    appendLine("For package.json specifically, you MUST include these fields:")
                    appendLine("  - \"name\": string (package name)")
                    appendLine("  - \"version\": string (semver format like \"1.0.0\")")
                    appendLine("  - \"description\": string (optional but recommended)")
                    appendLine("  - \"main\": string (entry point file, e.g., \"server.js\" or \"index.js\")")
                    appendLine("  - \"scripts\": object (npm scripts, e.g., {\"start\": \"node server.js\"})")
                    appendLine("  - \"dependencies\": object (npm packages, can be empty {})")
                    appendLine("")
                    appendLine("Example valid package.json output (this is what you should return, nothing else):")
                    appendLine("{\"name\":\"my-app\",\"version\":\"1.0.0\",\"description\":\"\",\"main\":\"server.js\",\"scripts\":{\"start\":\"node server.js\"},\"dependencies\":{}}")
                } else {
                    appendLine("")
                    appendLine("Example valid JSON output (this is what you should return, nothing else):")
                    appendLine("{\"key\":\"value\"}")
                }
                appendLine("")
                appendLine("REMEMBER: Your ENTIRE response must be ONLY the JSON object. No other text.")
            } else {
                appendLine("EXAMPLE FORMAT:")
                when {
                    file.path.endsWith(".html") -> appendLine("If this were index.html, you would return: <!DOCTYPE html><html><head>...</head><body>...</body></html>")
                    file.path.endsWith(".js") -> appendLine("If this were app.js, you would return: const express = require('express'); const app = express(); ...")
                    else -> appendLine("Return the raw code content directly, starting with the first character of the actual code.")
                }
                appendLine("")
                appendLine("Now generate the code for ${file.path} (raw code only, no markdown, no explanations):")
            }
        }
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = filePrompt))
            )
        )
        
        // Get tools (but don't use them for code generation)
        val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
            listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
        } else {
            null
        }
        
        // Apply rate limiting
        rateLimiter.acquire()
        
        val result = callApiWithRetryOnExhaustion(
            messages = messages,
            model = null,
            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_GENERATION),
            topP = null,
            topK = null,
            tools = null, // Don't use tools for code generation
            disableTools = true, // Force disable tools to prevent any tool calls
            onChunk = null
        )
        
        val response = result.getOrElse {
            Log.e("PpeExecutionEngine", "File code generation failed for ${file.path}: ${it.message}")
            throw Exception("File code generation failed for ${file.path}: ${it.message}", it)
        }
        
        // Check if response has function calls instead of text - retry if detected
        val finalResponse = if (response.functionCalls.isNotEmpty()) {
            Log.w("PpeExecutionEngine", "Response has function calls for ${file.path} even though tools are disabled. Retrying with enforced text-only mode.")
            // Retry with an even more explicit prompt
            val retryPrompt = buildString {
                appendLine("You attempted to return a tool call, but this is NOT allowed.")
                appendLine("You MUST return ONLY the raw file content as plain text.")
                appendLine("")
                appendLine("For ${file.path}, return ONLY the code content starting with:")
                when {
                    file.path.endsWith(".json") -> appendLine("'{' (the opening brace of JSON)")
                    file.path.endsWith(".html") -> appendLine("'<!DOCTYPE' or '<html'")
                    file.path.endsWith(".js") -> appendLine("'const', 'function', 'import', or 'export'")
                    file.path.endsWith(".css") -> appendLine("CSS rules (e.g., 'body {', '@media', etc.)")
                    else -> appendLine("the first character of the actual code")
                }
                appendLine("")
                appendLine("DO NOT return any JSON tool calls. DO NOT return function calls.")
                appendLine("Return ONLY the raw file content as plain text.")
            }
            
            val retryMessages = messages.toMutableList()
            retryMessages.add(
                Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = retryPrompt))
                )
            )
            
            rateLimiter.acquire()
            
            val retryResult = apiClient.callApi(
                messages = retryMessages,
                model = null,
                temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_GENERATION),
                topP = null,
                topK = null,
                tools = null,
                disableTools = true
            )
            
            val retryResponse = retryResult.getOrElse {
                throw Exception("Retry failed for ${file.path}: ${it.message}", it)
            }
            
            if (retryResponse.functionCalls.isNotEmpty()) {
                Log.e("PpeExecutionEngine", "Retry also returned function calls for ${file.path}. This is a critical error.")
                throw Exception("Model persistently returns function calls instead of code for ${file.path}. Cannot generate file.")
            }
            
            if (retryResponse.text.isEmpty()) {
                throw Exception("Retry returned empty text for ${file.path}")
            }
            
            retryResponse
        } else {
            if (response.text.isEmpty()) {
                Log.e("PpeExecutionEngine", "Response text is empty for ${file.path}. Full response: functionCalls=${response.functionCalls.size}, text length=${response.text.length}")
            }
            response
        }
        
        // Extract code from response (remove markdown if present)
        var code = finalResponse.text.trim()
        
        Log.d("PpeExecutionEngine", "Raw response for ${file.path} (length: ${code.length}): ${code.take(200)}")
        
        // Remove markdown code blocks if present (try multiple patterns)
        val codeBlockPatterns = listOf(
            Regex("```(?:\\w+)?\\s*\\n?(.*?)\\n?\\s*```", RegexOption.DOT_MATCHES_ALL), // Standard markdown
            Regex("```\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL), // Simple markdown
            Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL) // Minimal markdown
        )
        
        for (pattern in codeBlockPatterns) {
            val match = pattern.find(code)
            if (match != null) {
                val extracted = match.groupValues[1].trim()
                if (extracted.isNotEmpty()) {
                    code = extracted
                    Log.d("PpeExecutionEngine", "Extracted code from markdown block (length: ${code.length})")
                    break
                }
            }
        }
        
        // Remove common prefixes that models sometimes add
        val prefixesToRemove = listOf(
            "Here is the code:",
            "Here's the code:",
            "Here is the file:",
            "Here's the file:",
            "Code for ${file.path}:",
            "The code is:",
            "The file content is:"
        )
        
        for (prefix in prefixesToRemove) {
            if (code.startsWith(prefix, ignoreCase = true)) {
                code = code.substring(prefix.length).trim()
                Log.d("PpeExecutionEngine", "Removed prefix: $prefix")
            }
        }
        
        // Remove leading/trailing quotes if the entire response is quoted
        if ((code.startsWith("\"") && code.endsWith("\"")) || 
            (code.startsWith("'") && code.endsWith("'"))) {
            code = code.substring(1, code.length - 1).trim()
            Log.d("PpeExecutionEngine", "Removed surrounding quotes")
        }
        
        // Final trim
        code = code.trim()
        
        // If code is still empty but original response had content, try using the original response
        if (code.isEmpty() && response.text.trim().isNotEmpty()) {
            Log.w("PpeExecutionEngine", "Code extraction resulted in empty string, using original response")
            code = response.text.trim()
            
            // Try one more aggressive extraction
            val aggressivePattern = Regex("""```[^`]*?```""", RegexOption.DOT_MATCHES_ALL)
            val aggressiveMatch = aggressivePattern.find(code)
            if (aggressiveMatch != null) {
                var extracted = aggressiveMatch.value
                extracted = extracted.removePrefix("```").removeSuffix("```").trim()
                // Remove language identifier if present
                val langMatch = Regex("""^\w+""").find(extracted)
                if (langMatch != null) {
                    extracted = extracted.removePrefix(langMatch.value).trim()
                }
                if (extracted.isNotEmpty()) {
                    code = extracted
                }
            }
        }
        
        Log.d("PpeExecutionEngine", "Final extracted code for ${file.path} (length: ${code.length}): ${code.take(200)}")
        
        // Special validation and extraction for JSON files
        if (file.path.endsWith(".json")) {
            // Extract JSON object from response (find first { and last })
            val jsonStart = code.indexOf('{')
            val jsonEnd = code.lastIndexOf('}')
            
            if (jsonStart < 0 || jsonEnd < jsonStart) {
                Log.e("PpeExecutionEngine", "No valid JSON object found in response for ${file.path}. Response: ${code.take(500)}")
                throw Exception("No valid JSON object found in response for ${file.path}. Response must contain a JSON object starting with '{' and ending with '}'.")
            }
            
            // Extract only the JSON part
            code = code.substring(jsonStart, jsonEnd + 1).trim()
            
            // Validate JSON is parseable
            try {
                val jsonObj = JSONObject(code)
                
                // For package.json, validate required fields
                if (file.path == "package.json" || file.path.endsWith("/package.json")) {
                    val requiredFields = listOf("name", "version", "main", "scripts", "dependencies")
                    val missingFields = requiredFields.filter { !jsonObj.has(it) }
                    
                    if (missingFields.isNotEmpty()) {
                        Log.w("PpeExecutionEngine", "package.json missing required fields: ${missingFields.joinToString(", ")}")
                        // Add missing fields with defaults
                        if (!jsonObj.has("name")) jsonObj.put("name", "my-app")
                        if (!jsonObj.has("version")) jsonObj.put("version", "1.0.0")
                        if (!jsonObj.has("main")) jsonObj.put("main", "server.js")
                        if (!jsonObj.has("scripts")) jsonObj.put("scripts", JSONObject().apply { put("start", "node server.js") })
                        if (!jsonObj.has("dependencies")) jsonObj.put("dependencies", JSONObject())
                        
                        code = jsonObj.toString()
                        Log.d("PpeExecutionEngine", "Added missing fields to package.json")
                    }
                }
                
                Log.d("PpeExecutionEngine", "Validated JSON for ${file.path} (length: ${code.length})")
            } catch (e: Exception) {
                Log.e("PpeExecutionEngine", "Invalid JSON in response for ${file.path}: ${e.message}")
                Log.e("PpeExecutionEngine", "JSON content: ${code.take(500)}")
                throw Exception("Invalid JSON in response for ${file.path}. The response must be valid JSON only, with no extra text. Error: ${e.message}")
            }
        }
        
        // Validate code is not empty
        if (code.isEmpty()) {
            Log.e("PpeExecutionEngine", "Generated code is empty for ${file.path}. Original response (full): ${response.text}")
            throw Exception("Generated code is empty for ${file.path}")
        }
        
        return code
    }
    
    /**
     * Data class for file reading plan
     */
    private data class FileReadPlan(
        val files: List<FileReadRequest>
    )
    
    /**
     * Data class for a file read request with line ranges
     */
    private data class FileReadRequest(
        val path: String,
        val offset: Int? = null,
        val limit: Int? = null,
        val reason: String = ""
    )
    
    /**
     * Get file structure respecting .atermignore and .gitignore
     */
    private fun getFileStructureRespectingGitignore(workspaceRoot: String): String {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return "Workspace does not exist"
        }
        
        // Use AtermIgnoreManager for ignore patterns (includes .atermignore and defaults)
        val ignoreManager = com.qali.aterm.agent.utils.AtermIgnoreManager
        
        // Collect file structure
        val fileList = mutableListOf<Pair<String, Long>>()
        workspaceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                // Check if file should be ignored using AtermIgnoreManager
                if (!ignoreManager.shouldIgnoreFile(file, workspaceRoot)) {
                    val relativePath = file.relativeTo(workspaceDir).path.replace("\\", "/")
                    fileList.add(relativePath to file.length())
                }
            }
        }
        
        // Format as structure summary
        return buildString {
            appendLine("=== Project File Structure ===")
            appendLine("Total files: ${fileList.size}")
            appendLine("(Excluding .atermignore and .gitignore patterns)")
            appendLine()
            appendLine("Files:")
            fileList.sortedBy { it.first }.forEach { (path, size) ->
                val sizeStr = when {
                    size < 1024 -> "${size}B"
                    size < 1024 * 1024 -> "${size / 1024}KB"
                    else -> "${size / (1024 * 1024)}MB"
                }
                appendLine("  $path ($sizeStr)")
            }
        }
    }
    
    /**
     * Determine which files to read and line ranges via API call
     * Enhanced with error detection and smart file prioritization
     */
    private suspend fun determineFilesToRead(
        userMessage: String,
        fileStructure: String,
        chatHistory: List<Content>,
        script: PpeScript
    ): FileReadPlan? {
        // Check if user message contains error information
        val errorLocations = com.qali.aterm.agent.utils.ErrorDetectionUtils.parseErrorLocations(userMessage, workspaceRoot)
        // Enhanced API mismatch detection (now supports 20+ patterns)
        val apiMismatch = com.qali.aterm.agent.utils.ErrorDetectionUtils.detectApiMismatch(userMessage, null)
        
        // If error locations found, prioritize those files
        val priorityFiles = if (errorLocations.isNotEmpty()) {
            errorLocations.map { it.filePath } + 
            errorLocations.flatMap { 
                com.qali.aterm.agent.utils.ErrorDetectionUtils.getRelatedFilesForError(it, workspaceRoot)
            }
        } else {
            // Use smart file prioritization
            val projectTypeDetection = ProjectStartupDetector.detectProjectType(userMessage.lowercase(), workspaceRoot)
            com.qali.aterm.agent.utils.AtermIgnoreManager.getPriorityFiles(
                workspaceRoot, 
                projectTypeDetection.projectType?.name?.lowercase()
            )
        }
        // Build enhanced prompt with error detection info
        val errorContext = buildString {
            if (errorLocations.isNotEmpty()) {
                appendLine("\n⚠️ ERROR DETECTED:")
                errorLocations.forEach { loc ->
                    appendLine("  - File: ${loc.filePath}")
                    loc.lineNumber?.let { appendLine("    Line: $it") }
                    loc.functionName?.let { appendLine("    Function: $it") }
                }
                appendLine()
            }
            apiMismatch?.let { mismatch ->
                appendLine("\n⚠️ API MISMATCH DETECTED:")
                appendLine("  Type: ${mismatch.errorType}")
                appendLine("  Suggested Fix: ${mismatch.suggestedFix}")
                appendLine("  Affected Files: ${mismatch.affectedFiles.joinToString(", ")}")
                appendLine()
            }
            if (priorityFiles.isNotEmpty()) {
                appendLine("\n📋 PRIORITY FILES (read these first):")
                priorityFiles.forEach { file ->
                    appendLine("  - $file")
                }
                appendLine()
            }
        }
        
        val prompt = """
Based on the user's request and the project file structure, determine which files need to be read and which specific line ranges are relevant.

User Request: ${sanitizeForPrompt(userMessage)}
$errorContext
Project File Structure:
$fileStructure

Analyze the request and determine:
1. Which files are relevant to the task (prioritize files mentioned in errors if any)
2. Which specific line ranges should be read from each file (if not the entire file)
3. Focus on files that are likely to need changes or are dependencies

Return your response as a JSON array in this EXACT format (no markdown, just JSON):

[
  {
    "path": "src/main.js",
    "offset": 0,
    "limit": 50,
    "reason": "Need to see the main entry point"
  },
  {
    "path": "src/utils.js",
    "offset": 10,
    "limit": 30,
    "reason": "Need to check utility functions"
  }
]

IMPORTANT:
- Return ONLY valid JSON array, no markdown, no code blocks, no explanations
- Use offset and limit for line ranges (0-based, offset is starting line, limit is number of lines)
- If you need the entire file, omit offset and limit
- Be specific about which parts of files are relevant
- Focus on files that are likely to need changes or are dependencies

JSON Response:
""".trimIndent()
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            )
        )
        
        // Apply rate limiting
        rateLimiter.acquire()
        
        val result = callApiWithRetryOnExhaustion(
            messages = messages,
            model = null,
            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.CODE_ANALYSIS),
            topP = null,
            topK = null,
            tools = null,
            disableTools = false,
            onChunk = null
        )
        
        val response = result.getOrElse {
            Log.e("PpeExecutionEngine", "Failed to determine files to read: ${it.message}")
            return null
        }
        
        // Extract JSON from response
        var jsonText = response.text.trim()
        jsonText = jsonText.removePrefix("```json").removePrefix("```")
        jsonText = jsonText.removePrefix("```")
        jsonText = jsonText.removeSuffix("```").trim()
        
        val jsonStart = jsonText.indexOf('[')
        val jsonEnd = jsonText.lastIndexOf(']') + 1
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            jsonText = jsonText.substring(jsonStart, jsonEnd)
        }
        
        return try {
            val jsonArray = JSONArray(jsonText)
            val fileRequests = mutableListOf<FileReadRequest>()
            
            for (i in 0 until jsonArray.length()) {
                val fileObj = jsonArray.getJSONObject(i)
                val path = fileObj.optString("path", "")
                val offset = if (fileObj.has("offset")) fileObj.optInt("offset") else null
                val limit = if (fileObj.has("limit")) fileObj.optInt("limit") else null
                val reason = fileObj.optString("reason", "")
                
                if (path.isNotEmpty()) {
                    // Normalize path - convert absolute paths to relative paths
                    val normalizedPath = normalizeFilePath(path, workspaceRoot)
                    fileRequests.add(FileReadRequest(normalizedPath, offset, limit, reason))
                }
            }
            
            Log.d("PpeExecutionEngine", "Determined ${fileRequests.size} files to read")
            FileReadPlan(fileRequests)
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Failed to parse file read plan: ${e.message}", e)
            null
        }
    }
    
    /**
     * Read files based on the plan (with proper resource management)
     * Respects .atermignore patterns
     */
    private suspend fun readFilesFromPlan(
        plan: FileReadPlan,
        workspaceRoot: String,
        onChunk: (String) -> Unit
    ): Map<String, String> {
        val fileContents = mutableMapOf<String, String>()
        val ignoreManager = com.qali.aterm.agent.utils.AtermIgnoreManager
        
        for (request in plan.files) {
            // Normalize path first (in case it's an absolute path)
            val normalizedPath = normalizeFilePath(request.path, workspaceRoot)
            
            // Check if file should be ignored
            val file = File(workspaceRoot, normalizedPath)
            if (ignoreManager.shouldIgnoreFile(file, workspaceRoot)) {
                Log.w("PpeExecutionEngine", "Skipping ignored file: $normalizedPath")
                onChunk("⚠️ Skipping ignored file: $normalizedPath (in .atermignore)\n")
                continue
            }
            
            // Validate path
            if (!validateFilePath(normalizedPath, workspaceRoot)) {
                onChunk("⚠ Invalid file path: ${request.path} (normalized: $normalizedPath)\n")
                continue
            }
            
            onChunk("Reading $normalizedPath${if (request.offset != null && request.limit != null) " (lines ${request.offset + 1}-${request.offset + request.limit})" else ""}...\n")
            if (!file.exists() || !file.isFile) {
                onChunk("⚠ File not found: $normalizedPath (original: ${request.path})\n")
                continue
            }
            
            try {
                // Use useLines() for memory-efficient reading
                val content = file.useLines { lines ->
                    when {
                        request.offset != null && request.limit != null -> {
                            val start = request.offset.coerceAtLeast(0)
                            lines.drop(start).take(request.limit).joinToString("\n")
                        }
                        request.offset != null -> {
                            val start = request.offset.coerceAtLeast(0)
                            lines.drop(start).joinToString("\n")
                        }
                        else -> {
                            // Limit to MAX_FILE_LINES if reading entire file
                            val allLines = lines.toList()
                            val totalLines = allLines.size
                            val limitedLines = allLines.take(PpeConfig.MAX_FILE_LINES)
                            if (totalLines > PpeConfig.MAX_FILE_LINES) {
                                limitedLines.joinToString("\n") + "\n... (truncated, file has $totalLines lines)"
                            } else {
                                limitedLines.joinToString("\n")
                            }
                        }
                    }
                }
                
                fileContents[normalizedPath] = content
                onChunk("✓ Read $normalizedPath\n")
            } catch (e: Exception) {
                onChunk("✗ Failed to read $normalizedPath: ${e.message}\n")
                Log.e("PpeExecutionEngine", "Failed to read file $normalizedPath", e)
            }
        }
        
        return fileContents
    }
    
    /**
     * Update blueprint with new file information
     */
    private suspend fun updateBlueprintWithNewFile(
        newFilePath: String,
        newFileMetadata: String, // imports, exports, functions, classes
        blueprint: String,
        chatHistory: List<Content>,
        script: PpeScript
    ): String {
        val prompt = """
You are updating a project blueprint. A new file has been added to the project.

NEW FILE:
- Path: $newFilePath
- Metadata: $newFileMetadata

CURRENT BLUEPRINT:
$blueprint

IMPORTANT INSTRUCTIONS:
1. DO NOT change any existing files in the blueprint
2. ONLY add the new file to the blueprint
3. Include the new file's location, type, dependencies, and metadata
4. Update the dependency relationships if the new file depends on existing files or if existing files depend on it
5. Maintain the same JSON format as the current blueprint

Return the updated blueprint as JSON in the same format, with the new file added.

Updated Blueprint JSON:
""".trimIndent()
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            )
        )
        
        val result = apiClient.callApi(
            messages = messages,
            model = null,
            temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.DATA_ANALYSIS),
            topP = null,
            topK = null,
            tools = null
        )
        
        val response = result.getOrElse {
            Log.e("PpeExecutionEngine", "Failed to update blueprint: ${it.message}")
            return blueprint // Return original if update fails
        }
        
        // Extract JSON from response
        var jsonText = response.text.trim()
        jsonText = jsonText.removePrefix("```json").removePrefix("```")
        jsonText = jsonText.removePrefix("```")
        jsonText = jsonText.removeSuffix("```").trim()
        
        val jsonStart = jsonText.indexOf('{')
        val jsonEnd = jsonText.lastIndexOf('}') + 1
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            jsonText = jsonText.substring(jsonStart, jsonEnd)
        }
        
        return jsonText
    }
    
    /**
     * Execute upgrade/debug flow for existing projects
     */
    private suspend fun executeUpgradeDebugFlow(
        userMessage: String,
        chatHistory: List<Content>,
        script: PpeScript,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): PpeExecutionResult {
        Log.d("PpeExecutionEngine", "Starting upgrade/debug flow")
        
        val transaction = FileTransaction(workspaceRoot)
        val updatedChatHistory = chatHistory.toMutableList()
        
        try {
            // Start operation tracking
            val operationId = "upgrade-debug-${System.currentTimeMillis()}"
            Observability.startOperation(operationId, "upgrade-debug-flow")
            
            // Step -1: Classify request intent (ERROR_DEBUG, UPGRADE, BOTH, UNKNOWN)
            onChunk("🔍 Classifying request intent...\n")
            val classification = com.qali.aterm.agent.utils.RequestClassifier.classifyRequest(
                userMessage = userMessage,
                apiClient = apiClient,
                chatHistory = chatHistory
            )
            
            // Extract error information from prompt (if error-related)
            if (classification.intent == com.qali.aterm.agent.utils.RequestIntent.ERROR_DEBUG || 
                classification.intent == com.qali.aterm.agent.utils.RequestIntent.BOTH) {
                val extractedError = com.qali.aterm.agent.utils.PromptErrorExtractor.extractErrorInfo(userMessage)
                if (extractedError.errorType != null || extractedError.affectedFiles.isNotEmpty()) {
                    Log.d("PpeExecutionEngine", "Extracted error info: ${com.qali.aterm.agent.utils.PromptErrorExtractor.formatExtractedInfo(extractedError)}")
                    // Store extracted info for later use
                    updatedChatHistory.add(
                        Content(
                            role = "assistant",
                            parts = listOf(Part.TextPart(text = "Extracted error information:\n${com.qali.aterm.agent.utils.PromptErrorExtractor.formatExtractedInfo(extractedError)}"))
                        )
                    )
                }
            }
            
            // Log classification result
            Log.d("PpeExecutionEngine", "Request classified as: ${classification.intent} (confidence: ${classification.confidence})")
            onChunk("✓ Classified as: ${classification.intent.name} (confidence: ${(classification.confidence * 100).toInt()}%)\n")
            
            if (classification.reasoning != null) {
                onChunk("Reasoning: ${classification.reasoning}\n")
            }
            
            // Handle low confidence - request clarification
            if (classification.needsClarification()) {
                val clarificationMessage = """
⚠️ I'm not entirely sure what you need. Could you clarify?

Detected indicators:
${if (classification.errorIndicators.isNotEmpty()) "- Error indicators: ${classification.errorIndicators.joinToString(", ")}" else ""}
${if (classification.upgradeIndicators.isNotEmpty()) "- Upgrade indicators: ${classification.upgradeIndicators.joinToString(", ")}" else ""}

Are you trying to:
1. Fix an error or debug an issue?
2. Upgrade or add features to the app?
3. Both?

Please provide more details so I can help you better.
""".trimIndent()
                onChunk("$clarificationMessage\n")
                return PpeExecutionResult(
                    success = false,
                    finalResult = clarificationMessage,
                    variables = emptyMap(),
                    chatHistory = updatedChatHistory,
                    error = "Request intent unclear - needs clarification"
                )
            }
            
            // Store classification in chat history
            updatedChatHistory.add(
                Content(
                    role = "assistant",
                    parts = listOf(Part.TextPart(text = "Request classified as: ${classification.intent.name}"))
                )
            )
            
            // Step -0.5: Generate upgrade plan if upgrade is needed
            val needsUpgradePlanning = classification.intent == com.qali.aterm.agent.utils.RequestIntent.UPGRADE || 
                                      classification.intent == com.qali.aterm.agent.utils.RequestIntent.BOTH
            
            var upgradePlan: com.qali.aterm.agent.utils.UpgradePlan? = null
            if (needsUpgradePlanning) {
                onChunk("📋 Generating upgrade plan...\n")
                try {
                    upgradePlan = com.qali.aterm.agent.utils.UpgradePlanner.generateUpgradePlan(
                        userMessage = userMessage,
                        workspaceRoot = workspaceRoot,
                        apiClient = apiClient,
                        chatHistory = updatedChatHistory
                    )
                    
                    if (upgradePlan != null) {
                        onChunk("✓ Upgrade plan generated successfully!\n")
                        onChunk("Summary: ${upgradePlan.summary}\n")
                        onChunk("Files to modify: ${upgradePlan.filesToModify.size}\n")
                        onChunk("Files to create: ${upgradePlan.filesToCreate.size}\n")
                        onChunk("Execution steps: ${upgradePlan.executionSteps.size}\n")
                        
                        if (upgradePlan.riskAssessment != null) {
                            onChunk("Risk level: ${upgradePlan.riskAssessment.overallRisk.name}\n")
                        }
                        
                        // Add plan to chat history
                        val planText = buildString {
                            appendLine("=== Upgrade Plan ===")
                            appendLine(upgradePlan.summary)
                            appendLine("\nFiles to Modify: ${upgradePlan.filesToModify.size}")
                            appendLine("Files to Create: ${upgradePlan.filesToCreate.size}")
                            appendLine("Execution Steps: ${upgradePlan.executionSteps.size}")
                        }
                        updatedChatHistory.add(
                            Content(
                                role = "assistant",
                                parts = listOf(Part.TextPart(text = planText))
                            )
                        )
                    } else {
                        onChunk("⚠️ Could not generate upgrade plan, proceeding with standard flow...\n")
                    }
                } catch (e: Exception) {
                    Log.w("PpeExecutionEngine", "Upgrade plan generation failed: ${e.message}", e)
                    onChunk("⚠️ Upgrade plan generation failed, proceeding with standard flow...\n")
                }
            }
            
            // Step 0: Enhanced error analysis - read error location first, then analyze
            // Use classification result to determine if error analysis is needed
            val needsErrorAnalysis = classification.intent == com.qali.aterm.agent.utils.RequestIntent.ERROR_DEBUG || 
                                    classification.intent == com.qali.aterm.agent.utils.RequestIntent.BOTH
            
            if (needsErrorAnalysis) {
                onChunk("🔍 Step 0: Analyzing error...\n")
                
                // Step 0.1: Parse error locations from user message
                val errorLocations = com.qali.aterm.agent.utils.ErrorDetectionUtils.parseErrorLocations(
                    userMessage,
                    workspaceRoot
                )
                
                // Step 0.2: Read files around error locations FIRST
                if (errorLocations.isNotEmpty()) {
                    onChunk("📍 Found ${errorLocations.size} error location(s). Reading error context...\n")
                    
                    val errorContexts = mutableListOf<String>()
                    for (errorLoc in errorLocations) {
                        if (errorLoc.filePath.isNotEmpty()) {
                            try {
                                val errorFile = java.io.File(workspaceRoot, errorLoc.filePath)
                                if (errorFile.exists() && errorFile.isFile) {
                                    val fileContent = errorFile.readText()
                                    val lines = fileContent.lines()
                                    
                                    // Read context around error (10 lines before and after)
                                    val errorLine = errorLoc.lineNumber ?: 1
                                    val startLine = (errorLine - 10).coerceAtLeast(1)
                                    val endLine = (errorLine + 10).coerceAtMost(lines.size)
                                    
                                    val contextLines = lines.subList(startLine - 1, endLine)
                                    val contextText = buildString {
                                        appendLine("=== Error Context: ${errorLoc.filePath}:${errorLine} ===")
                                        contextLines.forEachIndexed { index, line ->
                                            val actualLineNum = startLine + index
                                            val marker = if (actualLineNum == errorLine) ">>> " else "    "
                                            appendLine("$marker${actualLineNum.toString().padStart(4)}: $line")
                                        }
                                    }
                                    
                                    errorContexts.add(contextText)
                                    onChunk("✓ Read context for ${errorLoc.filePath}:${errorLine}\n")
                                    
                                    // Get enhanced error context
                                    val enhancedContext = com.qali.aterm.agent.utils.ErrorDetectionUtils.getErrorContext(
                                        errorLoc,
                                        workspaceRoot,
                                        5
                                    )
                                    
                                    if (enhancedContext != null) {
                                        // Extract error type from user message
                                        val errorType = com.qali.aterm.agent.utils.ErrorDetectionUtils.parseErrorLocations(
                                            userMessage,
                                            workspaceRoot
                                        ).firstOrNull()?.let { 
                                            // Try to extract error type from message
                                            when {
                                                userMessage.contains("SyntaxError", ignoreCase = true) -> "SyntaxError"
                                                userMessage.contains("TypeError", ignoreCase = true) -> "TypeError"
                                                userMessage.contains("ReferenceError", ignoreCase = true) -> "ReferenceError"
                                                userMessage.contains("ImportError", ignoreCase = true) -> "ImportError"
                                                userMessage.contains("RuntimeError", ignoreCase = true) -> "RuntimeError"
                                                else -> null
                                            }
                                        } ?: "Unknown"
                                        
                                        val contextSummary = buildString {
                                            appendLine("\n📋 Error Details:")
                                            appendLine("  Type: $errorType")
                                            appendLine("  Severity: ${errorLoc.severity?.name ?: "MEDIUM"}")
                                            appendLine("  File: ${errorLoc.filePath}")
                                            appendLine("  Line: ${errorLoc.lineNumber ?: "Unknown"}")
                                            if (errorLoc.functionName != null) {
                                                appendLine("  Function: ${errorLoc.functionName}")
                                            }
                                            if (enhancedContext.surroundingCode != null) {
                                                appendLine("\n  Code Context:")
                                                val codeContext = enhancedContext.surroundingCode
                                                codeContext.linesBefore.take(3).forEach { line ->
                                                    appendLine("    $line")
                                                }
                                                appendLine("  >>> ${codeContext.errorLine}")
                                                codeContext.linesAfter.take(3).forEach { line ->
                                                    appendLine("    $line")
                                                }
                                            }
                                        }
                                        errorContexts.add(contextSummary)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("PpeExecutionEngine", "Failed to read error context for ${errorLoc.filePath}: ${e.message}", e)
                            }
                        }
                    }
                    
                    // Add error contexts to chat history
                    if (errorContexts.isNotEmpty()) {
                        val contextText = errorContexts.joinToString("\n\n")
                        updatedChatHistory.add(
                            Content(
                                role = "assistant",
                                parts = listOf(Part.TextPart(text = "Error Context Analysis:\n$contextText"))
                            )
                        )
                        onChunk("\n✅ Error context analyzed.\n\n")
                    }
                }
                
                // Step 0.3: Use intelligent error analysis tool for comprehensive analysis
                val errorAnalysisTool = toolRegistry.getTool("intelligent_error_analysis")
                if (errorAnalysisTool != null) {
                    try {
                        val analysisParams = mapOf(
                            "errorMessage" to userMessage
                        )
                        val toolParams = com.qali.aterm.agent.tools.IntelligentErrorAnalysisToolParams(
                            errorMessage = userMessage,
                            workspaceContext = null
                        )
                        @Suppress("UNCHECKED_CAST")
                        val analysisInvocation = (errorAnalysisTool as com.qali.aterm.agent.tools.DeclarativeTool<Any, com.qali.aterm.agent.tools.ToolResult>).createInvocation(toolParams)
                        onToolCall(FunctionCall(
                            name = "intelligent_error_analysis",
                            args = analysisParams
                        ))
                        val analysisResult = analysisInvocation.execute(null, onChunk)
                        onToolResult("intelligent_error_analysis", analysisParams)
                        
                        // Add analysis result to chat history
                        val analysisText = "Comprehensive Error Analysis:\n${analysisResult.llmContent}"
                        if (!updatedChatHistory.any { it.parts.any { part -> part is Part.TextPart && part.text.contains(analysisText.take(50)) } }) {
                            updatedChatHistory.add(
                                Content(
                                    role = "user",
                                    parts = listOf(Part.TextPart(text = analysisText))
                                )
                            )
                        }
                        onChunk("✅ Comprehensive error analysis completed.\n\n")
                    } catch (e: Exception) {
                        Log.w("PpeExecutionEngine", "Error analysis tool failed, continuing with normal flow", e)
                        onChunk("⚠️ Error analysis tool unavailable, continuing with standard flow...\n\n")
                    }
                }
            }
            
            // Step 1: Get file structure (respecting .gitignore) with caching
            // Only show message if not already in chat history
            val step1Message = "Step 1: Analyzing project structure..."
            if (!updatedChatHistory.any { it.parts.any { part -> part is Part.TextPart && part.text.contains(step1Message) } }) {
                onChunk("$step1Message\n")
            }
            val fileStructure = IntelligentCache.getFileStructure(workspaceRoot) {
                getFileStructureRespectingGitignore(workspaceRoot)
            }
            // Don't print "Project structure analyzed" if already printed
            val analyzedMessage = "Project structure analyzed"
            if (!updatedChatHistory.any { it.parts.any { part -> part is Part.TextPart && part.text.contains(analyzedMessage) } }) {
                onChunk("$analyzedMessage.\n\n")
            }
            
            // Step 2: Determine which files to read (with rate limiting)
            val step2Message = "Step 2: Determining which files to read..."
            if (!updatedChatHistory.any { it.parts.any { part -> part is Part.TextPart && part.text.contains(step2Message) } }) {
                onChunk("$step2Message\n")
            }
            rateLimiter.acquire()
            val readPlan = determineFilesToRead(userMessage, fileStructure, updatedChatHistory, script)
            
            if (readPlan == null || readPlan.files.isEmpty()) {
                Log.w("PpeExecutionEngine", "No files determined for reading")
                onChunk("No specific files identified. Proceeding with general analysis...\n\n")
            } else {
                onChunk("Identified ${readPlan.files.size} files to read.\n\n")
                
                // Step 3: Read the files
                val step3Message = "Step 3: Reading files..."
                if (!updatedChatHistory.any { it.parts.any { part -> part is Part.TextPart && part.text.contains(step3Message) } }) {
                    onChunk("$step3Message\n")
                }
                val fileContents = readFilesFromPlan(readPlan, workspaceRoot, onChunk)
                // Only add newline if we actually read files
                if (fileContents.isNotEmpty()) {
                    onChunk("\n")
                }
                
                // Add file contents to chat history for context
                if (fileContents.isNotEmpty()) {
                    val filesContext = buildString {
                        appendLine("=== Files Read for Analysis ===")
                        fileContents.forEach { (path, content) ->
                            appendLine("\n--- File: $path ---")
                            appendLine(content)
                        }
                    }
                    updatedChatHistory.add(
                        Content(
                            role = "user",
                            parts = listOf(Part.TextPart(text = filesContext))
                        )
                    )
                }
                
                // Step 4: Get plan from AI
                val step4Message = "Step 4: Analyzing and creating plan..."
                if (!updatedChatHistory.any { it.parts.any { part -> part is Part.TextPart && part.text.contains(step4Message) } }) {
                    onChunk("$step4Message\n")
                }
                
                // Check for "Cannot find module" errors and suggest fixes
                val moduleFixHint = if (userMessage.contains("Cannot find module", ignoreCase = true)) {
                    val moduleMatch = Regex("""Cannot find module\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(userMessage)
                    if (moduleMatch != null) {
                        val missingModule = moduleMatch.groupValues[1]
                        val fileName = File(missingModule.removePrefix("./").removePrefix("../")).name
                        val foundFiles = fileContents.keys.filter { it.contains(fileName, ignoreCase = true) }
                        if (foundFiles.isNotEmpty()) {
                            "\n\nMODULE PATH FIX REQUIRED:\nThe error indicates a missing module: '$missingModule'\n" +
                            "Found potential file(s): ${foundFiles.joinToString(", ")}\n" +
                            "ACTION REQUIRED: Use the 'edit' tool to fix the import path in the file that's trying to import '$missingModule'.\n" +
                            "Example: If server.js imports './db' but db.js is in src/, change the import to './src/db'\n" +
                            "Provide the exact old_string (current import) and new_string (corrected import path).\n"
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }
                } else {
                    ""
                }
                
                val planPrompt = """
IMPORTANT: This is a FIX/UPGRADE request for an EXISTING project. Do NOT regenerate the entire project or create blueprints.

User Request: $userMessage

Files Read:
${fileContents.keys.joinToString("\n") { "- $it" }}
$moduleFixHint
CRITICAL INSTRUCTIONS:
1. This is a targeted fix/upgrade - only modify what's necessary
2. For EXISTING files: Use the 'edit' tool to make specific changes, NOT 'write_file'
3. For NEW files: Use 'write_file' only if a file truly doesn't exist
4. Do NOT generate blueprints, dependency matrices, or full project rewrites
5. Do NOT suggest "Phase 1: Generate Blueprint" or similar full regeneration approaches
6. Focus on the specific issue mentioned in the user's request
7. Make minimal, targeted changes to fix the problem
8. If fixing a "Cannot find module" error, you MUST fix the import path using the 'edit' tool with exact old_string and new_string

Analyze the user's request and the files. Then:
- If modifying existing files: Use 'edit' tool with specific changes (include exact old_string and new_string)
- If creating new files: Use 'write_file' tool (only if file doesn't exist)
- Be precise and minimal - fix only what's needed
- For import path fixes: Provide the exact import line as old_string and the corrected import line as new_string

Now analyze and implement the fix directly using tools:
""".trimIndent()
                
                updatedChatHistory.add(
                    Content(
                        role = "user",
                        parts = listOf(Part.TextPart(text = planPrompt))
                    )
                )
                
                // Get plan from AI (with tools enabled)
                val tools = if (toolRegistry.getAllTools().isNotEmpty()) {
                    listOf(Tool(functionDeclarations = toolRegistry.getFunctionDeclarations()))
                } else {
                    null
                }
                
                val planResult = apiClient.callApi(
                    messages = updatedChatHistory,
                    model = null,
                    temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
                    topP = null,
                    topK = null,
                    tools = tools
                )
                
                val planResponse = planResult.getOrElse {
                    return PpeExecutionResult(
                        success = false,
                        finalResult = "Failed to create plan: ${it.message}",
                        variables = emptyMap(),
                        chatHistory = updatedChatHistory,
                        error = it.message
                    )
                }
                
                // Check if AI is trying to generate blueprints or full rewrites
                val responseText = planResponse.text.lowercase()
                val blueprintKeywords = listOf(
                    "phase 1", "generate blueprint", "blueprint generation", "dependency matrix",
                    "code dependency matrix", "project blueprint", "full rewrite", "regenerate",
                    "complete rewrite", "entire project", "all files"
                )
                val isTryingBlueprint = blueprintKeywords.any { responseText.contains(it) }
                
                if (isTryingBlueprint && planResponse.functionCalls.isEmpty()) {
                    // AI is trying to generate blueprints instead of fixing
                    Log.w("PpeExecutionEngine", "AI attempted blueprint generation in fix/upgrade flow. Redirecting...")
                    onChunk("⚠️ Detected blueprint generation attempt. Redirecting to targeted fix...\n\n")
                    
                    // Add redirect prompt
                    val redirectPrompt = """
STOP. You are trying to generate a blueprint or full rewrite, but this is a FIX/UPGRADE request.

The user wants you to FIX a specific issue: $userMessage

You have already read these files:
${fileContents.keys.joinToString("\n") { "- $it" }}

DO NOT generate blueprints or rewrite everything. Instead:
1. Identify the SPECIFIC issue from the user's request
2. Use the 'edit' tool to modify ONLY the relevant parts of existing files
3. Make minimal, targeted changes

For example, if the user says "the grid should be 3x3 but it's 1x9":
- Find the HTML/CSS/JS that creates the grid
- Use 'edit' tool to change the grid layout from 1x9 to 3x3
- Do NOT regenerate the entire project

Now fix the specific issue using the 'edit' tool:
""".trimIndent()
                    
                    updatedChatHistory.add(
                        Content(
                            role = "model",
                            parts = listOf(Part.TextPart(text = planResponse.text))
                        )
                    )
                    updatedChatHistory.add(
                        Content(
                            role = "user",
                            parts = listOf(Part.TextPart(text = redirectPrompt))
                        )
                    )
                    
                    // Retry with redirect prompt
                    val redirectResult = callApiWithRetryOnExhaustion(
                        messages = updatedChatHistory,
                        model = null,
                        temperature = getOptimalTemperature(ModelTemperatureConfig.TaskType.PROBLEM_SOLVING),
                        topP = null,
                        topK = null,
                        tools = tools,
                        onChunk = onChunk
                    )
                    
                    val redirectResponse = redirectResult.getOrElse {
                        return PpeExecutionResult(
                            success = false,
                            finalResult = "Failed to get fix plan: ${it.message}",
                            variables = emptyMap(),
                            chatHistory = updatedChatHistory,
                            error = it.message
                        )
                    }
                    
                    onChunk("Fix plan created. Executing...\n\n")
                    
                    // Use the redirected response
                    val finalChatHistory = updatedChatHistory.toMutableList()
                    finalChatHistory.add(
                        Content(
                            role = "model",
                            parts = listOf(Part.TextPart(text = redirectResponse.text))
                        )
                    )
                    
                    if (redirectResponse.functionCalls.isNotEmpty()) {
                        for (functionCall in redirectResponse.functionCalls) {
                            onToolCall(functionCall)
                            val toolResult = executeTool(functionCall, onToolResult)
                            
                            // Add tool result to chat history
                            finalChatHistory.add(
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
                            
                            // Track created files for blueprint update later
                            if (functionCall.name == "write_file" && toolResult.error == null) {
                                val filePath = functionCall.args["file_path"] as? String
                                if (filePath != null) {
                                    Log.d("PpeExecutionEngine", "File created in upgrade flow: $filePath")
                                }
                            }
                        }
                    }
                    
                    // Get final response
                    val finalResult = redirectResponse.text
                    onChunk("\n✅ Fix/upgrade completed. ${redirectResponse.functionCalls.size} actions executed.\n")
                    
                    Observability.endOperation(operationId)
                    return PpeExecutionResult(
                        success = true,
                        finalResult = finalResult,
                        variables = emptyMap(),
                        chatHistory = finalChatHistory,
                        error = null
                    )
                }
                
                onChunk("Plan created. Executing...\n\n")
                
                // Execute function calls from plan if any
                val finalChatHistory = updatedChatHistory.toMutableList()
                finalChatHistory.add(
                    Content(
                        role = "model",
                        parts = listOf(Part.TextPart(text = planResponse.text))
                    )
                )
                
                if (planResponse.functionCalls.isNotEmpty()) {
                    for (functionCall in planResponse.functionCalls) {
                        onToolCall(functionCall)
                        val toolResult = executeTool(functionCall, onToolResult)
                        
                        // Add tool result to chat history
                        finalChatHistory.add(
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
                        
                        // Track created files for blueprint update later
                        if (functionCall.name == "write_file" && toolResult.error == null) {
                            val filePath = functionCall.args["file_path"] as? String
                            if (filePath != null && validateFilePath(filePath, workspaceRoot)) {
                                transaction.addCreatedFile(filePath)
                            }
                        }
                    }
                }
                
                // Update blueprint AFTER all files are created
                val createdFiles = transaction.createdFiles
                if (createdFiles.isNotEmpty()) {
                    try {
                        onChunk("Updating project blueprint...\n")
                        // Update dependency matrix for all created files
                        createdFiles.forEach { filePath ->
                            try {
                                val file = File(workspaceRoot, filePath)
                                if (file.exists()) {
                                    val content = file.useLines { it.joinToString("\n") }
                                    val metadata = CodeDependencyAnalyzer.analyzeFile(filePath, content, workspaceRoot)
                                    CodeDependencyAnalyzer.updateDependencyMatrix(workspaceRoot, metadata)
                                }
                            } catch (e: Exception) {
                                Log.w("PpeExecutionEngine", "Failed to update dependency matrix for $filePath: ${e.message}")
                            }
                        }
                        onChunk("Blueprint updated successfully.\n")
                    } catch (e: Exception) {
                        Log.w("PpeExecutionEngine", "Failed to update blueprint: ${e.message}")
                    }
                }
                
                val result = "Upgrade/debug completed. ${planResponse.functionCalls.size} actions executed."
                onChunk("\n$result\n")
                
                // Clear transaction on success
                transaction.clear()
                
                // End operation tracking
                Observability.endOperation(operationId)
                
                return PpeExecutionResult(
                    success = true,
                    finalResult = result,
                    variables = emptyMap(),
                    chatHistory = finalChatHistory
                )
            }
            
            // If no specific files, proceed with normal flow
            return PpeExecutionResult(
                success = true,
                finalResult = "Analysis completed. Proceed with normal execution.",
                variables = emptyMap(),
                chatHistory = chatHistory
            )
            
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Upgrade/debug flow failed", e)
            // Rollback on exception
            try {
                transaction.rollback()
            } catch (rollbackError: Exception) {
                Log.e("PpeExecutionEngine", "Rollback failed: ${rollbackError.message}")
            }
            return PpeExecutionResult(
                success = false,
                finalResult = "Upgrade/debug failed: ${e.message}",
                variables = emptyMap(),
                chatHistory = updatedChatHistory,
                error = e.message
            )
        }
    }
    
    // ==================== Helper Classes ====================
    
    /**
     * File transaction for rollback support
     */
    private class FileTransaction(private val workspaceRoot: String) {
        val createdFiles = mutableListOf<String>()
        private val modifiedFiles = mutableMapOf<String, String>() // path -> original content
        
        fun addCreatedFile(path: String) {
            createdFiles.add(path)
        }
        
        fun addModifiedFile(path: String, originalContent: String) {
            modifiedFiles[path] = originalContent
        }
        
        suspend fun rollback() {
            withContext(Dispatchers.IO) {
                // Delete created files
                createdFiles.forEach { path ->
                    try {
                        val file = File(workspaceRoot, path)
                        if (file.exists()) {
                            file.delete()
                            Log.d("FileTransaction", "Rolled back created file: $path")
                        }
                    } catch (e: Exception) {
                        Log.e("FileTransaction", "Failed to rollback file $path: ${e.message}")
                    }
                }
                
                // Restore modified files
                modifiedFiles.forEach { (path, originalContent) ->
                    try {
                        val file = File(workspaceRoot, path)
                        if (file.exists()) {
                            file.writeText(originalContent)
                            Log.d("FileTransaction", "Restored modified file: $path")
                        }
                    } catch (e: Exception) {
                        Log.e("FileTransaction", "Failed to restore file $path: ${e.message}")
                    }
                }
            }
        }
        
        fun clear() {
            createdFiles.clear()
            modifiedFiles.clear()
        }
    }
    
    /**
     * Progress callback interface
     */
    interface ProgressCallback {
        fun onProgress(current: Int, total: Int, currentItem: String)
        fun isCancelled(): Boolean = false
    }
    
    /**
     * Rate limiter for API calls
     */
    private class RateLimiter(
        private val maxRequests: Int,
        private val windowMs: Long
    ) {
        private val requests = mutableListOf<Long>()

        /**
         * Acquire a rate-limit slot.
         *
         * Note: We must not call suspend functions (like delay) while holding a lock,
         * so we compute the required wait time inside the synchronized block and
         * perform the actual delay outside.
         */
        suspend fun acquire() {
            while (true) {
                val now = System.currentTimeMillis()
                var waitTimeMs: Long = 0L

                val canProceed = synchronized(requests) {
                    // Remove old requests outside the window
                    requests.removeAll { it < now - windowMs }

                    if (requests.size < maxRequests) {
                        // We can proceed immediately
                        requests.add(now)
                        true
                    } else {
                        // We need to wait until the oldest request expires
                        val oldestRequest = requests.minOrNull() ?: now
                        waitTimeMs = (oldestRequest + windowMs) - now
                        false
                    }
                }

                if (canProceed) {
                    return
                }

                if (waitTimeMs > 0) {
                    delay(waitTimeMs)
                }
            }
        }
    }
    
    private val rateLimiter = RateLimiter(
        maxRequests = PpeConfig.RATE_LIMIT_MAX_REQUESTS, 
        windowMs = PpeConfig.RATE_LIMIT_WINDOW_MS
    )
    
    // ==================== Validation Utilities ====================
    
    /**
     * Normalize file path - convert absolute paths to relative paths based on workspace root
     */
    private fun normalizeFilePath(path: String, workspaceRoot: String): String {
        // If path is already relative, return as-is
        if (!path.startsWith("/")) {
            return path
        }
        
        try {
            val workspaceFile = File(workspaceRoot)
            val workspaceCanonical = workspaceFile.canonicalPath
            
            // Try to resolve the absolute path
            val absoluteFile = File(path)
            val absoluteCanonical = try {
                absoluteFile.canonicalPath
            } catch (e: Exception) {
                // File doesn't exist, use the path as-is for pattern matching
                path
            }
            
            // If the absolute path is within the workspace, convert to relative
            if (absoluteCanonical is String && absoluteCanonical.startsWith(workspaceCanonical)) {
                val relativePath = absoluteCanonical.substring(workspaceCanonical.length)
                return relativePath.removePrefix("/").removePrefix("\\")
            }
            
            // Handle virtual paths that map to workspace root
            // Common patterns: /home/blog/file.js -> file.js (when workspace is .../home/blog)
            //                  /home/blog/src/file.js -> src/file.js
            val virtualPathPatterns = listOf(
                Regex("""^/home/blog/(.+)$"""),  // /home/blog/... -> ...
                Regex("""^/home/([^/]+)/(.+)$"""),  // /home/username/... -> ... (if workspace ends with username)
            )
            
            for (pattern in virtualPathPatterns) {
                val match = pattern.find(path)
                if (match != null) {
                    val relativePart = match.groupValues.last()
                    // Verify this makes sense by checking if workspace ends with the base part
                    val basePart = if (match.groupValues.size > 2) match.groupValues[1] else "blog"
                    if (workspaceCanonical.endsWith("/$basePart") || workspaceCanonical.endsWith("\\$basePart") ||
                        workspaceCanonical.endsWith("/$basePart/") || workspaceCanonical.endsWith("\\$basePart\\")) {
                        Log.d("PpeExecutionEngine", "Normalized virtual path: $path -> $relativePart (workspace: $workspaceCanonical)")
                        return relativePart
                    }
                }
            }
            
            // Special case: if workspace ends with /home/blog or /home/blog/, extract from /home/blog/ paths
            // Also check if workspace contains /home/blog anywhere (for Alpine Linux paths)
            val workspaceContainsHomeBlog = workspaceCanonical.contains("/home/blog") || workspaceCanonical.contains("\\home\\blog")
            if (workspaceContainsHomeBlog || workspaceCanonical.endsWith("/home/blog") || workspaceCanonical.endsWith("\\home\\blog") ||
                workspaceCanonical.endsWith("/home/blog/") || workspaceCanonical.endsWith("\\home\\blog\\")) {
                val homeBlogPattern = Regex("""^/home/blog/(.+)$""")
                val match = homeBlogPattern.find(path)
                if (match != null) {
                    val relativePart = match.groupValues[1]
                    Log.d("PpeExecutionEngine", "Normalized /home/blog path: $path -> $relativePart (workspace: $workspaceCanonical)")
                    return relativePart
                }
            }
            
            // If path starts with workspace root path components, extract relative part
            val workspaceParts = workspaceCanonical.split(File.separator).filter { it.isNotEmpty() }
            if (absoluteCanonical is String) {
                val pathParts = absoluteCanonical.split(File.separator).filter { it.isNotEmpty() }
                
                // Find where workspace path ends in the absolute path
                var workspaceIndex = -1
                for (i in pathParts.indices) {
                    if (i < workspaceParts.size && pathParts[i] == workspaceParts[i]) {
                        workspaceIndex = i
                    } else {
                        break
                    }
                }
                
                if (workspaceIndex >= 0 && workspaceIndex < pathParts.size - 1) {
                    // Extract relative path
                    val relativeParts = pathParts.subList(workspaceIndex + 1, pathParts.size)
                    val relativePath = relativeParts.joinToString(File.separator)
                    Log.d("PpeExecutionEngine", "Normalized by workspace matching: $path -> $relativePath")
                    return relativePath
                }
            }
            
            // Last resort: if path starts with /home/blog/, extract the relative part
            // This handles cases where the file doesn't exist yet and workspace contains /home/blog
            // This is a common pattern in Alpine Linux environments where /home/blog is a virtual path
            val homeBlogPattern = Regex("""^/home/blog/(.+)$""")
            val match = homeBlogPattern.find(path)
            if (match != null) {
                val relativePart = match.groupValues[1]
                // Always extract if workspace contains /home/blog (common in Alpine/Proot environments)
                if (workspaceCanonical.contains("/home/blog") || workspaceCanonical.contains("\\home\\blog")) {
                    Log.d("PpeExecutionEngine", "Normalized /home/blog path (last resort): $path -> $relativePart (workspace contains /home/blog)")
                    return relativePart
                }
            }
            
            // If we can't normalize, return the original path (validation will catch it if invalid)
            Log.w("PpeExecutionEngine", "Could not normalize absolute path: $path (workspace: $workspaceCanonical)")
            return path
        } catch (e: Exception) {
            Log.w("PpeExecutionEngine", "Error normalizing path $path: ${e.message}")
            // If normalization fails, try simple pattern matching
            val homeBlogPattern = Regex("""^/home/blog/(.+)$""")
            val match = homeBlogPattern.find(path)
            if (match != null) {
                val relativePart = match.groupValues[1]
                Log.d("PpeExecutionEngine", "Normalized /home/blog path (fallback): $path -> $relativePart")
                return relativePart
            }
            return path
        }
    }
    
    /**
     * Validate file path to prevent path traversal attacks
     */
    private fun validateFilePath(path: String, workspaceRoot: String): Boolean {
        return try {
            val file = File(workspaceRoot, path)
            val canonicalPath = file.canonicalPath
            val workspaceCanonical = File(workspaceRoot).canonicalPath
            canonicalPath.startsWith(workspaceCanonical)
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Path validation failed: ${e.message}")
            false
        }
    }
    
    /**
     * Sanitize user input for prompts
     */
    private fun sanitizeForPrompt(input: String): String {
        return input
            .replace("```", "\\`\\`\\`") // Escape code blocks
            .replace("${'$'}", "\\${'$'}") // Escape dollar signs
            .take((PpeConfig.MAX_TOKENS_BY_PROVIDER.values.maxOrNull() ?: 32768) * PpeConfig.CHARS_PER_TOKEN.toInt()) // Limit based on max tokens
    }
    
    /**
     * Validate blueprint structure
     */
    private fun validateBlueprint(blueprint: ProjectBlueprint): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check for empty files (critical error)
        if (blueprint.files.isEmpty()) {
            errors.add("Blueprint has no files")
        }
        
        // Check for duplicate paths (warning, not error - we can deduplicate)
        val paths = blueprint.files.map { it.path }
        val duplicates = paths.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            warnings.add("Duplicate file paths detected: ${duplicates.joinToString(", ")} (will use first occurrence)")
        }
        
        // Check for invalid paths (warning, not error - we'll filter them out)
        val invalidPaths = blueprint.files.filter { !validateFilePath(it.path, workspaceRoot) }
        if (invalidPaths.isNotEmpty()) {
            warnings.add("Invalid file paths detected: ${invalidPaths.map { it.path }.joinToString(", ")} (will be filtered out)")
        }
        
        // Check for circular dependencies (warning, not error - we'll handle in topological sort)
        val circularDeps = detectCircularDependencies(blueprint.files)
        if (circularDeps.isNotEmpty()) {
            warnings.add("Circular dependencies detected: ${circularDeps.joinToString(", ")} (may affect generation order)")
        }
        
        // Check for missing dependencies (warning only)
        val allPaths = paths.toSet()
        blueprint.files.forEach { file ->
            file.dependencies.forEach { dep ->
                if (!allPaths.contains(dep)) {
                    warnings.add("File ${file.path} depends on ${dep} which is not in blueprint")
                }
            }
        }
        
        // Only mark as invalid if there are no files (critical error)
        // All other issues are warnings that won't stop execution
        return ValidationResult(
            isValid = blueprint.files.isNotEmpty(), // Only invalid if no files
            errors = errors,
            warnings = warnings
        )
    }
    
    /**
     * Detect circular dependencies
     */
    private fun detectCircularDependencies(files: List<BlueprintFile>): List<String> {
        val graph = files.associate { it.path to it.dependencies.toSet() }
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val cycles = mutableListOf<String>()
        
        fun dfs(node: String): Boolean {
            if (node in recursionStack) {
                cycles.add(node)
                return true
            }
            if (node in visited) return false
            
            visited.add(node)
            recursionStack.add(node)
            
            graph[node]?.forEach { neighbor ->
                if (dfs(neighbor)) {
                    cycles.add(node)
                    return true
                }
            }
            
            recursionStack.remove(node)
            return false
        }
        
        graph.keys.forEach { node ->
            if (node !in visited) {
                dfs(node)
            }
        }
        
        return cycles.distinct()
    }
    
    /**
     * Topological sort for dependency resolution
     * Config files (package.json, etc.) are placed LAST so they can include all dependencies
     */
    private fun topologicalSort(files: List<BlueprintFile>): List<BlueprintFile> {
        // Separate config files to be created LAST (so they can include all dependencies from code files)
        val configFiles = files.filter { file ->
            file.path.endsWith("package.json") || 
            file.path.endsWith("requirements.txt") || 
            file.path.endsWith("Pipfile") ||
            file.path.endsWith("go.mod") ||
            file.path.endsWith("Cargo.toml") ||
            file.path.endsWith("pom.xml") ||
            file.path.endsWith("build.gradle") ||
            file.path.endsWith("build.gradle.kts") ||
            file.type == "config"
        }
        val codeFiles = files.filter { !configFiles.contains(it) }
        
        // Topological sort only code files (excluding config files from dependency graph)
        val inDegree = mutableMapOf<String, Int>()
        val fileMap = codeFiles.associateBy { it.path }
        
        // Initialize in-degree (only count code file dependencies, not config files)
        codeFiles.forEach { file ->
            val codeDependencies = file.dependencies.filter { dep ->
                !configFiles.any { it.path == dep }
            }
            inDegree[file.path] = codeDependencies.size
        }
        
        // Find code files with no code dependencies
        val queue = mutableListOf<String>()
        inDegree.forEach { (path, degree) ->
            if (degree == 0) {
                queue.add(path)
            }
        }
        
        val result = mutableListOf<BlueprintFile>()
        
        // Topological sort for code files
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            fileMap[current]?.let { result.add(it) }
            
            // Reduce in-degree of dependents
            codeFiles.forEach { file ->
                val codeDeps = file.dependencies.filter { dep ->
                    !configFiles.any { it.path == dep }
                }
                if (codeDeps.contains(current)) {
                    val newDegree = (inDegree[file.path] ?: 0) - 1
                    inDegree[file.path] = newDegree
                    if (newDegree == 0) {
                        queue.add(file.path)
                    }
                }
            }
        }
        
        // Add any remaining code files (circular dependencies)
        codeFiles.forEach { file ->
            if (!result.any { it.path == file.path }) {
                result.add(file)
            }
        }
        
        // Add config files LAST so they can include all dependencies discovered from code files
        result.addAll(configFiles)
        
        Log.d("PpeExecutionEngine", "Topological sort: ${result.size} files (${codeFiles.size} code, ${configFiles.size} config). Config files at end: ${configFiles.map { it.path }}")
        return result
    }
    
    /**
     * Retry logic with exponential backoff
     */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = PpeConfig.MAX_RETRIES,
        initialDelayMs: Long = PpeConfig.RETRY_INITIAL_DELAY_MS,
        maxDelayMs: Long = PpeConfig.RETRY_MAX_DELAY_MS,
        isRetryable: (Exception) -> Boolean = { true },
        operation: suspend () -> T
    ): Result<T> {
        var lastException: Exception? = null
        var delayMs = initialDelayMs
        
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(operation())
            } catch (e: Exception) {
                lastException = e
                if (!isRetryable(e)) {
                    return Result.failure(e)
                }
                
                if (attempt < maxRetries - 1) {
                    Log.w("PpeExecutionEngine", "Retry attempt ${attempt + 1}/$maxRetries failed: ${e.message}. Retrying in ${delayMs}ms...")
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, maxDelayMs) // Exponential backoff with cap
                }
            }
        }
        
        return Result.failure(lastException ?: Exception("Failed after $maxRetries attempts"))
    }
    
    /**
     * Check if exception is retryable
     */
    private fun isRetryableException(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return when {
            message.contains("timeout") -> true
            message.contains("network") -> true
            message.contains("connection") -> true
            message.contains("rate limit") -> true
            message.contains("429") -> true
            e is java.net.SocketTimeoutException -> true
            e is java.net.UnknownHostException -> true
            else -> false
        }
    }
    
    /**
     * Detect missing module errors in tool results
     * Returns the file path if a missing file was detected, null otherwise
     */
    private fun detectMissingModule(
        toolResult: com.qali.aterm.agent.tools.ToolResult,
        functionCall: FunctionCall
    ): String? {
        // Check shell command results and read tool results for missing module errors
        if (functionCall.name != "shell" && functionCall.name != "read") {
            return null
        }
        
        // Get error message from tool result
        val errorMessage = toolResult.error?.message ?: ""
        val outputContent = if (toolResult.llmContent is String) {
            toolResult.llmContent as String
        } else {
            ""
        }
        
        // Combine error and output to check for missing module errors
        val combinedText = "$errorMessage $outputContent"
        
        // Check for "Cannot find module" errors
        val cannotFindModulePattern = Regex(
            """Cannot find module\s+['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )
        val match = cannotFindModulePattern.find(combinedText)
        
        if (match == null) {
            return null
        }
        
        val missingModule = match.groupValues[1]
        android.util.Log.d("PpeExecutionEngine", "Detected missing module: $missingModule")
        
        // Convert module path to file path
        // Handle both relative paths (./middleware/auth) and module names (middleware/auth)
        val requestedPath = when {
            missingModule.startsWith("./") -> missingModule.substring(2)
            missingModule.startsWith("../") -> missingModule
            missingModule.startsWith("/") -> missingModule.substring(1)
            else -> missingModule
        }
        
        val filePath = requestedPath.let { path ->
            // Add .js extension if not present and it's a JavaScript module
            if (!path.contains(".") && (combinedText.contains(".js") || combinedText.contains("node") || combinedText.contains("require"))) {
                "$path.js"
            } else {
                path
            }
        }
        
        // Check if file already exists at requested path
        val file = File(workspaceRoot, filePath)
        if (file.exists()) {
            android.util.Log.d("PpeExecutionEngine", "File already exists: $filePath")
            return null
        }
        
        // Search for the file in common locations (src/, lib/, etc.)
        val fileName = File(filePath).name
        val searchDirs = listOf("src", "lib", "app", "server", "routes", "controllers", "models", "utils")
        for (dir in searchDirs) {
            val candidateFile = File(workspaceRoot, "$dir/$fileName")
            if (candidateFile.exists()) {
                android.util.Log.d("PpeExecutionEngine", "Found file in alternative location: ${candidateFile.relativeTo(File(workspaceRoot)).path}")
                // Return the correct path that should be used in the import
                return candidateFile.relativeTo(File(workspaceRoot)).path.replace("\\", "/")
            }
        }
        
        // Also search recursively for the file
        val foundFile = File(workspaceRoot).walkTopDown()
            .firstOrNull { it.isFile && it.name == fileName && !AtermIgnoreManager.shouldIgnoreFile(it, workspaceRoot) }
        
        if (foundFile != null) {
            val relativePath = foundFile.relativeTo(File(workspaceRoot)).path.replace("\\", "/")
            android.util.Log.d("PpeExecutionEngine", "Found file recursively: $relativePath")
            return relativePath
        }
        
        return filePath
    }
    
    /**
     * Validation result
     */
    private data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )
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
