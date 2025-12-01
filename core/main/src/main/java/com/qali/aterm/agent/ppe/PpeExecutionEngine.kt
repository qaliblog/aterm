package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.ppe.models.*
import com.qali.aterm.agent.tools.ToolRegistry
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
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
    
    // Track tool results for file diff extraction (queue-based to handle multiple calls)
    private val toolResultQueue = mutableListOf<Pair<String, com.qali.aterm.agent.tools.ToolResult>>()
    
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
                            // Check for new project startup (enhanced detection)
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
                            
                            // Check for upgrade/debug flow (existing project)
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
        val temperature = message.aiPlaceholderParams?.get("temperature")?.toString()?.toDoubleOrNull()
        val topP = message.aiPlaceholderParams?.get("top_p")?.toString()?.toDoubleOrNull()
        val topK = message.aiPlaceholderParams?.get("top_k")?.toString()?.toIntOrNull()
        
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
        val prunedMessages = ContextWindowManager.pruneChatHistory(messages, modelToUse)
        
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
        
        // Get tools from registry
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
                                temperature = null,
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
     * Data class for blueprint file structure
     */
    private data class BlueprintFile(
        val path: String,
        val type: String, // "code", "config", "test", etc.
        val dependencies: List<String> = emptyList(),
        val description: String = ""
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
        
        // Check for upgrade/debug keywords
        val upgradeDebugKeywords = listOf(
            "fix", "debug", "upgrade", "update", "improve", "refactor",
            "modify", "change", "edit", "add feature", "enhance",
            "error", "bug", "issue", "problem", "broken"
        )
        val hasUpgradeKeyword = upgradeDebugKeywords.any { message.contains(it) }
        
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
        // 1. Has upgrade/debug keywords AND
        // 2. Has existing code files (more than 2)
        val isUpgradeDebug = hasUpgradeKeyword && codeFileCount > 2
        
        Log.d("PpeExecutionEngine", "Upgrade/debug check - keywords: $hasUpgradeKeyword, codeFiles: $codeFileCount, isUpgrade: $isUpgradeDebug")
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
            
            // Parse blueprint JSON
            val blueprint = parseBlueprintJson(blueprintJson)
            if (blueprint == null) {
                Log.w("PpeExecutionEngine", "Failed to parse blueprint JSON")
                return PpeExecutionResult(
                    success = false,
                    finalResult = "Failed to parse project blueprint",
                    variables = emptyMap(),
                    chatHistory = chatHistory,
                    error = "Blueprint parsing failed"
                )
            }
            
            // Validate blueprint
            val validation = validateBlueprint(blueprint)
            if (!validation.isValid) {
                val errorMsg = "Blueprint validation failed:\n${validation.errors.joinToString("\n")}"
                Log.e("PpeExecutionEngine", errorMsg)
                return PpeExecutionResult(
                    success = false,
                    finalResult = errorMsg,
                    variables = emptyMap(),
                    chatHistory = chatHistory,
                    error = errorMsg
                )
            }
            
            if (validation.warnings.isNotEmpty()) {
                onChunk("Warnings: ${validation.warnings.joinToString("; ")}\n")
            }
            
            onChunk("Phase 2: Generating code for ${blueprint.files.size} files...\n\n")
            
            // Phase 2: Generate code for each file using topological sort
            val generatedFiles = mutableListOf<String>()
            val sortedFiles = topologicalSort(blueprint.files)
            
            for ((index, file) in sortedFiles.withIndex()) {
                Log.d("PpeExecutionEngine", "Generating code for file ${index + 1}/${sortedFiles.size}: ${file.path}")
                onChunk("Generating ${file.path}...\n")
                
                // Validate file path
                if (!validateFilePath(file.path, workspaceRoot)) {
                    onChunk("✗ Invalid file path: ${file.path}\n")
                    continue
                }
                
                val fileCodeResult = generateFileCode(
                    file,
                    blueprint,
                    userMessage,
                    updatedChatHistory,
                    script
                )
                
                val fileCode = fileCodeResult.getOrElse { error ->
                    onChunk("✗ Failed to generate code for ${file.path}: ${error.message}\n")
                    continue
                }
                
                // Use write_file tool to create the file
                val writeFileCall = FunctionCall(
                    name = "write_file",
                    args = mapOf(
                        "file_path" to file.path,
                        "file_contents" to fileCode
                    )
                )
                
                onToolCall(writeFileCall)
                val toolResult = executeTool(writeFileCall, onToolResult)
                
                if (toolResult.error == null) {
                    transaction.addCreatedFile(file.path)
                    generatedFiles.add(file.path)
                    onChunk("✓ Created ${file.path}\n")
                    
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
You are a project architect. Based on the user's request, generate a JSON blueprint for the project structure.

User Request: $userMessage

$projectTypeContext

Generate a JSON blueprint in this EXACT format (no markdown, no code blocks, just pure JSON):

{
  "projectType": "nodejs",
  "files": [
    {
      "path": "package.json",
      "type": "config",
      "dependencies": [],
      "description": "Package configuration file"
    },
    {
      "path": "server.js",
      "type": "code",
      "dependencies": ["package.json"],
      "description": "Main server file"
    },
    {
      "path": "public/index.html",
      "type": "code",
      "dependencies": ["package.json"],
      "description": "Main HTML file"
    }
  ]
}

IMPORTANT:
- Return ONLY valid JSON, no markdown, no code blocks, no explanations
- Include ALL files needed for the project
- Set dependencies array to show which files each file depends on
- Use appropriate file types: "config", "code", "test", "style", etc.
- Ensure the JSON is valid and can be parsed directly

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
        
        val result = apiClient.callApi(
            messages = messages,
            model = null,
            tools = null // Don't use tools for blueprint generation
        )
        
        val response = result.getOrElse {
            Log.e("PpeExecutionEngine", "Blueprint API call failed: ${it.message}")
            throw Exception("Blueprint API call failed: ${it.message}", it)
        }
        
        // Extract JSON from response (might be wrapped in markdown)
        var jsonText = response.text.trim()
        
        // Remove markdown code blocks if present
        jsonText = jsonText.removePrefix("```json").removePrefix("```")
        jsonText = jsonText.removePrefix("```")
        jsonText = jsonText.removeSuffix("```").trim()
        
        // Try to find JSON object in the response
        val jsonStart = jsonText.indexOf('{')
        val jsonEnd = jsonText.lastIndexOf('}') + 1
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            jsonText = jsonText.substring(jsonStart, jsonEnd)
        }
        
        Log.d("PpeExecutionEngine", "Extracted blueprint JSON (length: ${jsonText.length})")
        
        // Validate JSON is not empty
        if (jsonText.isEmpty() || !jsonText.trim().startsWith("{")) {
            throw Exception("Invalid blueprint JSON: empty or not a JSON object")
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
                
                if (path.isNotEmpty()) {
                    files.add(BlueprintFile(path, type, dependencies, description))
                }
            }
            
            Log.d("PpeExecutionEngine", "Parsed blueprint: projectType=$projectType, files=${files.size}")
            ProjectBlueprint(projectType, files)
        } catch (e: Exception) {
            Log.e("PpeExecutionEngine", "Failed to parse blueprint JSON: ${e.message}", e)
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
            maxRetries = 3,
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
        
        val filePrompt = buildString {
            appendLine("Generate the complete code for this file:")
            appendLine("")
            appendLine("File: ${file.path}")
            appendLine("Type: ${file.type}")
            appendLine("Description: ${file.description}")
            appendLine("")
            
            if (relatedFiles.isNotEmpty()) {
                appendLine("This file depends on:")
                relatedFiles.forEach { relatedFile ->
                    appendLine("  - ${relatedFile.path} (${relatedFile.type})")
                }
                appendLine("")
                
                if (existingFilesMetadata.isNotEmpty()) {
                    appendLine("Available imports/exports from related files:")
                    existingFilesMetadata.forEach { metadata ->
                        appendLine(metadata)
                    }
                    appendLine("")
                    appendLine("IMPORTANT: Use ONLY the imports/exports/functions/classes listed above from related files.")
                    appendLine("")
                }
            }
            
            appendLine("Project Context:")
            appendLine("  - Project Type: ${blueprint.projectType}")
            appendLine("  - Total Files: ${blueprint.files.size}")
            appendLine("")
            
            appendLine("Original User Request: $userMessage")
            appendLine("")
            appendLine("IMPORTANT:")
            appendLine("- Generate COMPLETE, working code for this file")
            appendLine("- Use appropriate imports/exports based on the project type")
            appendLine("- If importing from related files, use ONLY the names listed above")
            appendLine("- Ensure the code is production-ready and follows best practices")
            appendLine("- Include all necessary functionality")
            appendLine("- Return ONLY the code, no explanations, no markdown")
            appendLine("")
            appendLine("Code for ${file.path}:")
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
        
        val result = apiClient.callApi(
            messages = messages,
            model = null,
            tools = null // Don't use tools for code generation
        )
        
        val response = result.getOrElse {
            Log.e("PpeExecutionEngine", "File code generation failed for ${file.path}: ${it.message}")
            throw Exception("File code generation failed for ${file.path}: ${it.message}", it)
        }
        
        // Extract code from response (remove markdown if present)
        var code = response.text.trim()
        
        // Remove markdown code blocks if present
        val codeBlockPattern = Regex("```(?:\\w+)?\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL)
        codeBlockPattern.find(code)?.let { match ->
            code = match.groupValues[1].trim()
        }
        
        Log.d("PpeExecutionEngine", "Generated code for ${file.path} (length: ${code.length})")
        
        // Validate code is not empty
        if (code.trim().isEmpty()) {
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
     * Get file structure respecting .gitignore
     */
    private fun getFileStructureRespectingGitignore(workspaceRoot: String): String {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return "Workspace does not exist"
        }
        
        // Read .gitignore patterns
        val gitignoreFile = File(workspaceRoot, ".gitignore")
        val ignorePatterns = mutableListOf<String>()
        if (gitignoreFile.exists()) {
            gitignoreFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    ignorePatterns.add(trimmed)
                }
            }
        }
        
        // Default ignore patterns
        val defaultIgnores = listOf(
            ".git", "node_modules", ".gradle", "build", "dist", "out",
            ".idea", ".vscode", ".DS_Store", "*.class", "*.jar", "*.war"
        )
        ignorePatterns.addAll(defaultIgnores)
        
        // Check if path should be ignored
        fun shouldIgnore(path: String): Boolean {
            val relativePath = path.removePrefix(workspaceRoot).trimStart('/')
            return ignorePatterns.any { pattern ->
                when {
                    pattern.contains("*") -> {
                        val regex = pattern
                            .replace(".", "\\.")
                            .replace("*", ".*")
                            .toRegex()
                        regex.matches(relativePath) || relativePath.contains(pattern.removeSuffix("*"))
                    }
                    pattern.startsWith("/") -> relativePath.startsWith(pattern.removePrefix("/"))
                    else -> relativePath.contains(pattern) || relativePath.endsWith(pattern)
                }
            }
        }
        
        // Collect file structure
        val fileList = mutableListOf<Pair<String, Long>>()
        workspaceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(workspaceDir).path
                if (!shouldIgnore(relativePath)) {
                    fileList.add(relativePath to file.length())
                }
            }
        }
        
        // Format as structure summary
        return buildString {
            appendLine("=== Project File Structure ===")
            appendLine("Total files: ${fileList.size}")
            appendLine()
            appendLine("Files (excluding .gitignore patterns):")
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
     */
    private suspend fun determineFilesToRead(
        userMessage: String,
        fileStructure: String,
        chatHistory: List<Content>,
        script: PpeScript
    ): FileReadPlan? {
        val prompt = """
Based on the user's request and the project file structure, determine which files need to be read and which specific line ranges are relevant.

User Request: ${sanitizeForPrompt(userMessage)}

Project File Structure:
$fileStructure

Analyze the request and determine:
1. Which files are relevant to the task
2. Which specific line ranges should be read from each file (if not the entire file)

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
        
        val result = apiClient.callApi(
            messages = messages,
            model = null,
            tools = null
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
                    fileRequests.add(FileReadRequest(path, offset, limit, reason))
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
     */
    private suspend fun readFilesFromPlan(
        plan: FileReadPlan,
        workspaceRoot: String,
        onChunk: (String) -> Unit
    ): Map<String, String> {
        val fileContents = mutableMapOf<String, String>()
        
        for (request in plan.files) {
            // Validate path
            if (!validateFilePath(request.path, workspaceRoot)) {
                onChunk("⚠ Invalid file path: ${request.path}\n")
                continue
            }
            
            onChunk("Reading ${request.path}${if (request.offset != null && request.limit != null) " (lines ${request.offset + 1}-${request.offset + request.limit})" else ""}...\n")
            
            val file = File(workspaceRoot, request.path)
            if (!file.exists() || !file.isFile) {
                onChunk("⚠ File not found: ${request.path}\n")
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
                
                fileContents[request.path] = content
                onChunk("✓ Read ${request.path}\n")
            } catch (e: Exception) {
                onChunk("✗ Failed to read ${request.path}: ${e.message}\n")
                Log.e("PpeExecutionEngine", "Failed to read file ${request.path}", e)
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
            
            // Step 1: Get file structure (respecting .gitignore) with caching
            onChunk("Step 1: Analyzing project structure...\n")
            val fileStructure = IntelligentCache.getFileStructure(workspaceRoot) {
                getFileStructureRespectingGitignore(workspaceRoot)
            }
            onChunk("Project structure analyzed.\n\n")
            
            // Step 2: Determine which files to read (with rate limiting)
            onChunk("Step 2: Determining which files to read...\n")
            rateLimiter.acquire()
            val readPlan = determineFilesToRead(userMessage, fileStructure, chatHistory, script)
            
            if (readPlan == null || readPlan.files.isEmpty()) {
                Log.w("PpeExecutionEngine", "No files determined for reading")
                onChunk("No specific files identified. Proceeding with general analysis...\n\n")
            } else {
                onChunk("Identified ${readPlan.files.size} files to read.\n\n")
                
                // Step 3: Read the files
                onChunk("Step 3: Reading files...\n")
                val fileContents = readFilesFromPlan(readPlan, workspaceRoot, onChunk)
                onChunk("\n")
                
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
                onChunk("Step 4: Analyzing and creating plan...\n")
                val planPrompt = """
Based on the user's request and the files I've read, create a plan for what needs to be done.

User Request: $userMessage

Files Read:
${fileContents.keys.joinToString("\n") { "- $it" }}

Analyze the situation and provide a plan. The plan should specify:
1. Whether new files need to be created or existing files need to be modified
2. For new files: provide the file path, type, dependencies, and what imports/functions/classes it needs
3. For existing files: specify what changes are needed and where (line numbers if possible)

Be specific and actionable. Use tools to implement the plan.

Plan:
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
        
        suspend fun acquire() {
            val now = System.currentTimeMillis()
            synchronized(requests) {
                // Remove old requests outside the window
                requests.removeAll { it < now - windowMs }
                
                // Wait if we're at the limit
                while (requests.size >= maxRequests) {
                    val oldestRequest = requests.minOrNull() ?: break
                    val waitTime = (oldestRequest + windowMs) - now
                    if (waitTime > 0) {
                        delay(waitTime)
                    }
                    requests.removeAll { it < System.currentTimeMillis() - windowMs }
                }
                
                requests.add(System.currentTimeMillis())
            }
        }
    }
    
    private val rateLimiter = RateLimiter(
        maxRequests = PpeConfig.RATE_LIMIT_MAX_REQUESTS, 
        windowMs = PpeConfig.RATE_LIMIT_WINDOW_MS
    )
    
    // ==================== Validation Utilities ====================
    
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
        
        // Check for empty files
        if (blueprint.files.isEmpty()) {
            errors.add("Blueprint has no files")
        }
        
        // Check for duplicate paths
        val paths = blueprint.files.map { it.path }
        val duplicates = paths.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            errors.add("Duplicate file paths: ${duplicates.joinToString(", ")}")
        }
        
        // Check for invalid paths
        blueprint.files.forEach { file ->
            if (!validateFilePath(file.path, workspaceRoot)) {
                errors.add("Invalid file path: ${file.path}")
            }
        }
        
        // Check for circular dependencies
        val circularDeps = detectCircularDependencies(blueprint.files)
        if (circularDeps.isNotEmpty()) {
            errors.add("Circular dependencies detected: ${circularDeps.joinToString(", ")}")
        }
        
        // Check for missing dependencies
        val allPaths = paths.toSet()
        blueprint.files.forEach { file ->
            file.dependencies.forEach { dep ->
                if (!allPaths.contains(dep)) {
                    warnings.add("File ${file.path} depends on ${dep} which is not in blueprint")
                }
            }
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
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
     */
    private fun topologicalSort(files: List<BlueprintFile>): List<BlueprintFile> {
        val graph = files.associate { it.path to it.dependencies.toSet() }
        val inDegree = mutableMapOf<String, Int>()
        val fileMap = files.associateBy { it.path }
        
        // Initialize in-degree
        files.forEach { file ->
            inDegree[file.path] = file.dependencies.size
        }
        
        // Find files with no dependencies
        val queue = mutableListOf<String>()
        inDegree.forEach { (path, degree) ->
            if (degree == 0) {
                queue.add(path)
            }
        }
        
        val result = mutableListOf<BlueprintFile>()
        
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            fileMap[current]?.let { result.add(it) }
            
            // Reduce in-degree of dependents
            files.forEach { file ->
                if (file.dependencies.contains(current)) {
                    val newDegree = (inDegree[file.path] ?: 0) - 1
                    inDegree[file.path] = newDegree
                    if (newDegree == 0) {
                        queue.add(file.path)
                    }
                }
            }
        }
        
        // If we couldn't process all files, there's a cycle
        if (result.size != files.size) {
            Log.w("PpeExecutionEngine", "Topological sort incomplete - possible cycle. Returning original order.")
            return files.sortedBy { it.dependencies.size }
        }
        
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
