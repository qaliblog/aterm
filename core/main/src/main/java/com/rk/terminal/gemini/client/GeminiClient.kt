package com.rk.terminal.gemini.client

import com.rk.libcommons.alpineDir
import com.rk.terminal.api.ApiProviderManager
import com.rk.terminal.api.ApiProviderManager.KeysExhaustedException
import com.rk.terminal.gemini.tools.DeclarativeTool
import com.rk.terminal.gemini.core.*
import com.rk.terminal.gemini.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Main Gemini Client for making API calls and handling tool execution
 */
class GeminiClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String = alpineDir().absolutePath
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Content>()
    
    /**
     * Send a message and get streaming response
     * This implements automatic continuation after tool calls, matching the TypeScript implementation
     */
    suspend fun sendMessageStream(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
        android.util.Log.d("GeminiClient", "sendMessageStream: Starting request")
        android.util.Log.d("GeminiClient", "sendMessageStream: User message length: ${userMessage.length}")
        
        // Add user message to history (only if it's not already a function response)
        if (userMessage.isNotEmpty() && !userMessage.startsWith("__CONTINUE__")) {
            chatHistory.add(
                Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = userMessage))
                )
            )
        }
        
        val model = ApiProviderManager.getCurrentModel()
        val maxTurns = 100 // Maximum number of turns to prevent infinite loops
        var turnCount = 0
        
        // Loop to handle automatic continuation after tool calls
        while (turnCount < maxTurns) {
            turnCount++
            android.util.Log.d("GeminiClient", "sendMessageStream: Turn $turnCount")
            
            // Get API key
            val apiKey = ApiProviderManager.getNextApiKey()
                ?: run {
                    android.util.Log.e("GeminiClient", "sendMessageStream: No API keys configured")
                    emit(GeminiStreamEvent.Error("No API keys configured"))
                    return@flow
                }
            
            // Prepare request (use chat history which already includes function responses for continuation)
            val requestBody = buildRequest(if (turnCount == 1) userMessage else "", model)
            
            android.util.Log.d("GeminiClient", "sendMessageStream: Request body size: ${requestBody.toString().length} bytes")
            
            // Track if we have tool calls to execute and finish reason
            var hasToolCalls = false
            var finishReason: String? = null
            val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>() // FunctionCall, ToolResult, callId
            
            // Collect events to emit after API call (since callbacks aren't in coroutine context)
            val eventsToEmit = mutableListOf<GeminiStreamEvent>()
            
            // Make API call with retry
            val result = ApiProviderManager.makeApiCallWithRetry { key ->
                try {
                    android.util.Log.d("GeminiClient", "sendMessageStream: Attempting API call")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        finishReason = makeApiCall(
                            key, 
                            model, 
                            requestBody, 
                            { chunk ->
                                // Call the callback and collect event to emit later
                                onChunk(chunk)
                                eventsToEmit.add(GeminiStreamEvent.Chunk(chunk))
                            }, 
                            { functionCall ->
                                onToolCall(functionCall)
                                eventsToEmit.add(GeminiStreamEvent.ToolCall(functionCall))
                                hasToolCalls = true
                            },
                            { toolName, args ->
                                onToolResult(toolName, args)
                                // Note: ToolResult event will be emitted after tool execution completes
                            },
                            toolCallsToExecute
                        )
                    }
                    android.util.Log.d("GeminiClient", "sendMessageStream: API call completed successfully, finishReason: $finishReason")
                    Result.success(Unit)
                } catch (e: KeysExhaustedException) {
                    android.util.Log.e("GeminiClient", "sendMessageStream: Keys exhausted", e)
                    Result.failure(e)
                } catch (e: Exception) {
                    android.util.Log.e("GeminiClient", "sendMessageStream: Exception during API call", e)
                    android.util.Log.e("GeminiClient", "sendMessageStream: Exception type: ${e.javaClass.simpleName}")
                    android.util.Log.e("GeminiClient", "sendMessageStream: Exception message: ${e.message}")
                    if (ApiProviderManager.isRateLimitError(e)) {
                        android.util.Log.w("GeminiClient", "sendMessageStream: Rate limit error detected")
                        Result.failure(e)
                    } else {
                        Result.failure(e)
                    }
                }
            }
            
            // Emit collected events (chunks and tool calls) now that we're back in coroutine context
            if (eventsToEmit.isNotEmpty()) {
                android.util.Log.d("GeminiClient", "sendMessageStream: Emitting ${eventsToEmit.size} collected event(s)")
                for (event in eventsToEmit) {
                    android.util.Log.d("GeminiClient", "sendMessageStream: Emitting event: ${event.javaClass.simpleName}")
                    emit(event)
                }
                eventsToEmit.clear()
            } else {
                android.util.Log.d("GeminiClient", "sendMessageStream: No events collected to emit")
            }
            
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                android.util.Log.e("GeminiClient", "sendMessageStream: Result failed, error: ${error?.message}")
                if (error is KeysExhaustedException) {
                    emit(GeminiStreamEvent.KeysExhausted(error.message ?: "All keys exhausted"))
                } else {
                    emit(GeminiStreamEvent.Error(error?.message ?: "Unknown error"))
                }
                return@flow
            }
            
            // Validate response: if no tool calls and no finish reason, check if we have text content
            // If we have text content but no finish reason, assume STOP (model finished generating text)
            if (toolCallsToExecute.isEmpty() && finishReason == null) {
                // Check if we received any text chunks in this turn
                val hasTextContent = eventsToEmit.any { it is GeminiStreamEvent.Chunk }
                if (hasTextContent) {
                    android.util.Log.d("GeminiClient", "sendMessageStream: No finish reason but has text content, assuming STOP")
                    finishReason = "STOP"
                } else {
                    android.util.Log.w("GeminiClient", "sendMessageStream: No finish reason, no tool calls, and no text - invalid response")
                    emit(GeminiStreamEvent.Error("Model stream ended without a finish reason or tool calls"))
                    return@flow
                }
            }
            
            // Execute any pending tool calls
            if (toolCallsToExecute.isNotEmpty()) {
                android.util.Log.d("GeminiClient", "sendMessageStream: Processing ${toolCallsToExecute.size} tool call result(s)")
                
                // Format responses and add to history
                // Tools are already executed during response processing, we just format the results
                for (triple in toolCallsToExecute) {
                    val functionCall = triple.first
                    val toolResult = triple.second
                    val callId = triple.third
                    
                    // Emit ToolResult event for UI
                    emit(GeminiStreamEvent.ToolResult(functionCall.name, toolResult))
                    
                    // Format response based on tool result
                    val responseContent = when {
                        toolResult.error != null -> {
                            // Error response
                            mapOf("error" to (toolResult.error.message ?: "Unknown error"))
                        }
                        toolResult.llmContent is String -> {
                            // Simple string output
                            mapOf("output" to toolResult.llmContent)
                        }
                        else -> {
                            // Default success message
                            mapOf("output" to "Tool execution succeeded.")
                        }
                    }
                    chatHistory.add(
                        Content(
                            role = "user",
                            parts = listOf(
                                Part.FunctionResponsePart(
                                    functionResponse = FunctionResponse(
                                        name = functionCall.name,
                                        response = responseContent,
                                        id = callId
                                    )
                                )
                            )
                        )
                    )
                }
                
                // Continue the conversation with tool results
                android.util.Log.d("GeminiClient", "sendMessageStream: Continuing conversation after tool execution")
                continue // Loop to make another API call
            } else {
                // No more tool calls, check finish reason
                android.util.Log.d("GeminiClient", "sendMessageStream: No tool calls, finishReason: $finishReason")
                when (finishReason) {
                    "STOP" -> {
                        android.util.Log.d("GeminiClient", "sendMessageStream: Stream completed (STOP) - model indicates task is complete")
                        // Check if we should continue - if there are pending todos, the task might not be complete
                        // But for now, trust the model's STOP signal
                        emit(GeminiStreamEvent.Done)
                        break
                    }
                    "MAX_TOKENS" -> {
                        android.util.Log.w("GeminiClient", "sendMessageStream: Stream stopped due to MAX_TOKENS")
                        emit(GeminiStreamEvent.Error("Response was truncated due to token limit"))
                        break
                    }
                    "SAFETY" -> {
                        android.util.Log.w("GeminiClient", "sendMessageStream: Stream stopped due to SAFETY")
                        emit(GeminiStreamEvent.Error("Response was blocked due to safety filters"))
                        break
                    }
                    "MALFORMED_FUNCTION_CALL" -> {
                        android.util.Log.w("GeminiClient", "sendMessageStream: Stream stopped due to MALFORMED_FUNCTION_CALL")
                        emit(GeminiStreamEvent.Error("Model generated malformed function call"))
                        break
                    }
                    else -> {
                        android.util.Log.d("GeminiClient", "sendMessageStream: Stream completed (finishReason: $finishReason)")
                        emit(GeminiStreamEvent.Done)
                        break
                    }
                }
            }
        }
        
        if (turnCount >= maxTurns) {
            android.util.Log.w("GeminiClient", "sendMessageStream: Reached maximum turns ($maxTurns)")
            emit(GeminiStreamEvent.Error("Maximum number of turns reached"))
        }
    }
    
    private fun makeApiCall(
        apiKey: String,
        model: String,
        requestBody: JSONObject,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
        // Google Gemini API endpoint - using SSE (Server-Sent Events) for streaming
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey"
        android.util.Log.d("GeminiClient", "makeApiCall: URL: ${url.replace(apiKey, "***")}")
        android.util.Log.d("GeminiClient", "makeApiCall: Model: $model")
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        android.util.Log.d("GeminiClient", "makeApiCall: Executing request...")
        val startTime = System.currentTimeMillis()
        
        try {
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startTime
                android.util.Log.d("GeminiClient", "makeApiCall: Response received after ${elapsed}ms")
                android.util.Log.d("GeminiClient", "makeApiCall: Response code: ${response.code}")
                android.util.Log.d("GeminiClient", "makeApiCall: Response successful: ${response.isSuccessful}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    android.util.Log.e("GeminiClient", "makeApiCall: API call failed: ${response.code}")
                    android.util.Log.e("GeminiClient", "makeApiCall: Error body: $errorBody")
                    throw IOException("API call failed: ${response.code} - $errorBody")
                }
                
                android.util.Log.d("GeminiClient", "makeApiCall: Reading response body...")
                response.body?.let { body ->
                    val contentLength = body.contentLength()
                    android.util.Log.d("GeminiClient", "makeApiCall: Response body content length: $contentLength")
                    
                    // Read the entire body
                    val bodyString = body.string()
                    android.util.Log.d("GeminiClient", "makeApiCall: Response body string length: ${bodyString.length}")
                    
                    if (bodyString.isEmpty()) {
                        android.util.Log.w("GeminiClient", "makeApiCall: Response body is empty!")
                        return@let
                    }
                    
                    try {
                        // Try parsing as JSON array first (non-streaming response)
                        if (bodyString.trim().startsWith("[")) {
                            android.util.Log.d("GeminiClient", "makeApiCall: Detected JSON array format (non-streaming)")
                            val jsonArray = JSONArray(bodyString)
                            android.util.Log.d("GeminiClient", "makeApiCall: JSON array has ${jsonArray.length()} elements")
                            
                            var lastFinishReason: String? = null
                            var hasContent = false
                            for (i in 0 until jsonArray.length()) {
                                val json = jsonArray.getJSONObject(i)
                                android.util.Log.d("GeminiClient", "makeApiCall: Processing array element $i")
                                val finishReason = processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                                if (finishReason != null) {
                                    lastFinishReason = finishReason
                                }
                                // Check if this element has content (text or function calls)
                                val candidate = json.optJSONObject("candidates")?.optJSONObject(0)
                                if (candidate != null && candidate.has("content")) {
                                    hasContent = true
                                }
                            }
                            // If we have content but no finish reason, assume STOP
                            if (lastFinishReason == null && hasContent) {
                                android.util.Log.d("GeminiClient", "makeApiCall: No finish reason in array but has content, assuming STOP")
                                return "STOP"
                            }
                            return lastFinishReason
                        } else {
                            // Try parsing as SSE (Server-Sent Events) format
                            android.util.Log.d("GeminiClient", "makeApiCall: Attempting SSE format parsing")
                            val lines = bodyString.lines()
                            android.util.Log.d("GeminiClient", "makeApiCall: Total lines in response: ${lines.size}")
                            
                            var lineCount = 0
                            var dataLineCount = 0
                            
                            for (line in lines) {
                                lineCount++
                                val trimmedLine = line.trim()
                                
                                if (trimmedLine.isEmpty()) continue
                                
                                if (trimmedLine.startsWith("data: ")) {
                                    dataLineCount++
                                    val jsonStr = trimmedLine.substring(6)
                                    if (jsonStr == "[DONE]" || jsonStr.isEmpty()) {
                                        android.util.Log.d("GeminiClient", "makeApiCall: Received [DONE] marker")
                                        continue
                                    }
                                    
                                    try {
                                        android.util.Log.d("GeminiClient", "makeApiCall: Parsing SSE data line $dataLineCount")
                                        val json = JSONObject(jsonStr)
                                        android.util.Log.d("GeminiClient", "makeApiCall: Processing SSE data line $dataLineCount")
                                        val finishReason = processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                                        if (finishReason != null) {
                                            return finishReason
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("GeminiClient", "makeApiCall: Failed to parse SSE JSON on line $dataLineCount", e)
                                        android.util.Log.e("GeminiClient", "makeApiCall: JSON string: ${jsonStr.take(500)}")
                                    }
                                } else if (trimmedLine.startsWith(":")) {
                                    // SSE comment line, skip
                                    android.util.Log.d("GeminiClient", "makeApiCall: Skipping SSE comment line")
                                } else {
                                    // Try parsing the whole body as a single JSON object
                                    try {
                                        android.util.Log.d("GeminiClient", "makeApiCall: Attempting to parse as single JSON object")
                                        val json = JSONObject(bodyString)
                                        android.util.Log.d("GeminiClient", "makeApiCall: Processing single JSON object")
                                        return processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                                    } catch (e: Exception) {
                                        android.util.Log.w("GeminiClient", "makeApiCall: Unexpected line format: ${trimmedLine.take(100)}")
                                    }
                                }
                            }
                            android.util.Log.d("GeminiClient", "makeApiCall: Finished SSE parsing. Total lines: $lineCount, Data lines: $dataLineCount")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GeminiClient", "makeApiCall: Failed to parse response body", e)
                        android.util.Log.e("GeminiClient", "makeApiCall: Response preview: ${bodyString.take(500)}")
                        throw IOException("Failed to parse response: ${e.message}", e)
                    }
                } ?: run {
                    android.util.Log.w("GeminiClient", "makeApiCall: Response body is null")
                }
            }
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("GeminiClient", "makeApiCall: IOException after ${elapsed}ms", e)
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("GeminiClient", "makeApiCall: Unexpected exception after ${elapsed}ms", e)
            throw e
        }
        return null // No finish reason found
    }
    
    private fun processResponse(
        json: JSONObject,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
        var finishReason: String? = null
        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val candidate = candidates.getJSONObject(0)
            
            // Extract finish reason if present
            if (candidate.has("finishReason")) {
                finishReason = candidate.getString("finishReason")
            }
            
            // Extract usage metadata if present (for tracking)
            val usageMetadata = candidate.optJSONObject("usageMetadata")
            
            val content = candidate.optJSONObject("content")
            
            if (content != null) {
                val parts = content.optJSONArray("parts")
                if (parts != null) {
                    // First pass: collect all function calls and process text/thoughts
                    val functionCalls = mutableListOf<FunctionCall>()
                    
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        
                        // Check for thought (for thinking models like gemini-2.5)
                        if (part.has("text") && part.optBoolean("thought", false)) {
                            val thoughtText = part.getString("text")
                            // Thoughts are internal reasoning, we can log but don't need to emit to user
                            android.util.Log.d("GeminiClient", "processResponse: Received thought: ${thoughtText.take(100)}...")
                            // Continue to next part - thoughts don't go to user
                            continue
                        }
                        
                        // Check for text (non-thought)
                        if (part.has("text") && !part.optBoolean("thought", false)) {
                            val text = part.getString("text")
                            android.util.Log.d("GeminiClient", "processResponse: Found text chunk (length: ${text.length}): ${text.take(100)}...")
                            onChunk(text)
                            
                            // Add to history
                            if (chatHistory.isEmpty() || chatHistory.last().role != "model") {
                                chatHistory.add(
                                    Content(
                                        role = "model",
                                        parts = listOf(Part.TextPart(text = text))
                                    )
                                )
                            } else {
                                val lastContent = chatHistory.last()
                                val newParts = lastContent.parts + Part.TextPart(text = text)
                                chatHistory[chatHistory.size - 1] = lastContent.copy(parts = newParts)
                            }
                        }
                        
                        // Collect function calls (don't execute yet)
                        if (part.has("functionCall")) {
                            val functionCallJson = part.getJSONObject("functionCall")
                            val name = functionCallJson.getString("name")
                            val argsJson = functionCallJson.getJSONObject("args")
                            val args = jsonObjectToMap(argsJson)
                            // Generate callId if not provided by API (matching TypeScript implementation)
                            val callId = if (functionCallJson.has("id")) {
                                functionCallJson.getString("id")
                            } else {
                                "${name}-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
                            }
                            
                            val functionCall = FunctionCall(name = name, args = args, id = callId)
                            functionCalls.add(functionCall)
                            onToolCall(functionCall)
                        }
                    }
                    
                    // Extract citations if present (matching TypeScript implementation)
                    val citationMetadata = candidate.optJSONObject("citationMetadata")
                    if (citationMetadata != null) {
                        val citations = citationMetadata.optJSONArray("citations")
                        if (citations != null) {
                            for (j in 0 until citations.length()) {
                                val citation = citations.getJSONObject(j)
                                val uri = citation.optString("uri", "")
                                val title = citation.optString("title", "")
                                if (uri.isNotEmpty()) {
                                    val citationText = if (title.isNotEmpty()) "($title) $uri" else uri
                                    android.util.Log.d("GeminiClient", "processResponse: Citation: $citationText")
                                    // Citations are typically shown at the end, we can emit them if needed
                                }
                            }
                        }
                    }
                    
                    // Second pass: Execute all collected function calls AFTER processing all parts
                    // This matches the TypeScript behavior where function calls are collected first
                    for (functionCall in functionCalls) {
                        // Add function call to history
                        chatHistory.add(
                            Content(
                                role = "model",
                                parts = listOf(Part.FunctionCallPart(functionCall = functionCall))
                            )
                        )
                        
                        // Execute tool synchronously and collect for later continuation
                        val toolResult = try {
                            executeToolSync(functionCall.name, functionCall.args)
                        } catch (e: Exception) {
                            ToolResult(
                                llmContent = "Error executing tool: ${e.message}",
                                returnDisplay = "Error: ${e.message}",
                                error = ToolError(
                                    message = e.message ?: "Unknown error",
                                    type = ToolErrorType.EXECUTION_ERROR
                                )
                            )
                        }
                        
                        // Store tool call and result for execution after response processing
                        val callId = functionCall.id ?: "${functionCall.name}-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
                        toolCallsToExecute.add(Triple(functionCall, toolResult, callId))
                        onToolResult(functionCall.name, functionCall.args)
                    }
                }
            }
        }
        return finishReason
    }
    
    private fun executeToolSync(name: String, args: Map<String, Any>): ToolResult {
        val tool = toolRegistry.getTool(name)
            ?: return ToolResult(
                llmContent = "Tool not found: $name",
                returnDisplay = "Error: Tool not found",
                error = ToolError(
                    message = "Tool not found: $name",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        
        return try {
            val params = tool.validateParams(args)
                ?: return ToolResult(
                    llmContent = "Invalid parameters for tool: $name",
                    returnDisplay = "Error: Invalid parameters",
                    error = ToolError(
                        message = "Invalid parameters",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            
            @Suppress("UNCHECKED_CAST")
            val invocation = (tool as DeclarativeTool<Any, ToolResult>).createInvocation(params as Any)
            // Execute synchronously for now (in production, use coroutines properly)
            kotlinx.coroutines.runBlocking {
                invocation.execute(null, null)
            }
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error executing tool: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun buildRequest(userMessage: String, model: String): JSONObject {
        val request = JSONObject()
        request.put("contents", JSONArray().apply {
            // Add chat history (which already includes the user message if it's the first turn)
            chatHistory.forEach { content ->
                val contentObj = JSONObject()
                contentObj.put("role", content.role)
                contentObj.put("parts", JSONArray().apply {
                    content.parts.forEach { part ->
                        when (part) {
                            is Part.TextPart -> {
                                val partObj = JSONObject()
                                partObj.put("text", part.text)
                                put(partObj)
                            }
                            is Part.FunctionCallPart -> {
                                val partObj = JSONObject()
                                val functionCallObj = JSONObject()
                                functionCallObj.put("name", part.functionCall.name)
                                functionCallObj.put("args", JSONObject(part.functionCall.args))
                                part.functionCall.id?.let { functionCallObj.put("id", it) }
                                partObj.put("functionCall", functionCallObj)
                                put(partObj)
                            }
                            is Part.FunctionResponsePart -> {
                                val partObj = JSONObject()
                                val functionResponseObj = JSONObject()
                                functionResponseObj.put("name", part.functionResponse.name)
                                functionResponseObj.put("response", JSONObject(part.functionResponse.response))
                                part.functionResponse.id?.let { functionResponseObj.put("id", it) }
                                partObj.put("functionResponse", functionResponseObj)
                                put(partObj)
                            }
                        }
                    }
                })
                put(contentObj)
            }
        })
        
        // Add tools
        val tools = JSONArray()
        val toolObj = JSONObject()
        val functionDeclarations = JSONArray()
        toolRegistry.getFunctionDeclarations().forEach { decl ->
            val declObj = JSONObject()
            declObj.put("name", decl.name)
            declObj.put("description", decl.description)
            declObj.put("parameters", functionParametersToJson(decl.parameters))
            functionDeclarations.put(declObj)
        }
        toolObj.put("functionDeclarations", functionDeclarations)
        tools.put(toolObj)
        request.put("tools", tools)
        
        // Add system instruction to guide planning behavior
        // This matches the comprehensive system prompt from the original gemini-cli TypeScript implementation
        val hasWriteTodosTool = toolRegistry.getFunctionDeclarations().any { it.name == "write_todos" }
        
        val systemInstruction = buildString {
            append("You are an interactive CLI agent specializing in software engineering tasks. Your primary goal is to help users safely and efficiently, adhering strictly to the following instructions and utilizing your available tools.\n\n")
            
            append("# Core Mandates\n\n")
            append("- **Conventions:** Rigorously adhere to existing project conventions when reading or modifying code. Analyze surrounding code, tests, and configuration first.\n")
            append("- **Libraries/Frameworks:** NEVER assume a library/framework is available or appropriate. Verify its established usage within the project before employing it.\n")
            append("- **Style & Structure:** Mimic the style, structure, framework choices, typing, and architectural patterns of existing code in the project.\n")
            append("- **Proactiveness:** Fulfill the user's request thoroughly. When adding features or fixing bugs, this includes adding tests to ensure quality.\n")
            append("- **Explaining Changes:** After completing a code modification or file operation, do not provide summaries unless asked.\n\n")
            
            append("# Primary Workflows\n\n")
            append("## Software Engineering Tasks\n")
            append("When requested to perform tasks like fixing bugs, adding features, refactoring, or explaining code, follow this sequence:\n")
            append("1. **Understand:** Think about the user's request and the relevant codebase context. Use search tools extensively to understand file structures, existing code patterns, and conventions.\n")
            append("2. **Plan:** Build a coherent and grounded plan for how you intend to resolve the user's task.")
            
            if (hasWriteTodosTool) {
                append(" For complex tasks, break them down into smaller, manageable subtasks and use the `write_todos` tool to track your progress.")
            }
            
            append(" Share an extremely concise yet clear plan with the user if it would help the user understand your thought process. As part of the plan, you should use an iterative development process that includes writing unit tests to verify your changes.\n")
            append("3. **Implement:** Use the available tools to act on the plan, strictly adhering to the project's established conventions.\n")
            append("4. **Verify (Tests):** If applicable and feasible, verify the changes using the project's testing procedures.\n")
            append("5. **Verify (Standards):** VERY IMPORTANT: After making code changes, execute the project-specific build, linting and type-checking commands that you have identified for this project.\n")
            append("6. **Finalize:** After all verification passes, consider the task complete.\n\n")
            
            if (hasWriteTodosTool) {
                append("## Planning with write_todos Tool\n\n")
                append("For complex queries that require multiple steps, planning and generally is higher complexity than a simple Q&A, use the `write_todos` tool.\n\n")
                append("DO NOT use this tool for simple tasks that can be completed in less than 2 steps. If the user query is simple and straightforward, do not use the tool.\n\n")
                append("When using `write_todos`:\n")
                append("1. Use this todo list as soon as you receive a user request based on the complexity of the task.\n")
                append("2. Keep track of every subtask that you update the list with.\n")
                append("3. Mark a subtask as in_progress before you begin working on it. You should only have one subtask as in_progress at a time.\n")
                append("4. Update the subtask list as you proceed in executing the task. The subtask list is not static and should reflect your progress and current plans.\n")
                append("5. Mark a subtask as completed when you have completed it.\n")
                append("6. Mark a subtask as cancelled if the subtask is no longer needed.\n")
                append("7. **CRITICAL:** After creating a todo list, you MUST continue working on the todos. Creating the plan is NOT completing the task. You must execute each todo item until all are completed or cancelled. Do NOT stop after creating the todo list - continue implementing the tasks.\n\n")
            }
            
            append("# Operational Guidelines\n\n")
            append("## Tone and Style (CLI Interaction)\n")
            append("- **Concise & Direct:** Adopt a professional, direct, and concise tone suitable for a CLI environment.\n")
            append("- **Minimal Output:** Aim for fewer than 3 lines of text output (excluding tool use/code generation) per response whenever practical.\n")
            append("- **No Chitchat:** Avoid conversational filler, preambles, or postambles. Get straight to the action or answer.\n\n")
            
            append("## Tool Usage\n")
            append("- **Parallelism:** Execute multiple independent tool calls in parallel when feasible.\n")
            append("- **Command Execution:** Use shell tools for running shell commands.\n\n")
            
            append("# Final Reminder\n")
            append("Your core function is efficient and safe assistance. Balance extreme conciseness with the crucial need for clarity. Always prioritize user control and project conventions. Never make assumptions about the contents of files; instead use read tools to ensure you aren't making broad assumptions.\n\n")
            append("**CRITICAL: Task Completion Rules**\n")
            append("- You are an agent - you MUST keep going until the user's query is completely resolved.\n")
            append("- Creating a todo list with `write_todos` is PLANNING, not completion. You MUST continue executing the todos.\n")
            append("- Do NOT return STOP after creating todos. You must continue working until all todos are completed.\n")
            append("- Only return STOP when ALL tasks are actually finished and the user's request is fully implemented.\n")
            append("- If you create a todo list, immediately start working on the first todo item. Do not stop after planning.")
        }
        
        request.put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", systemInstruction)
                })
            })
        })
        
        return request
    }
    
    private fun functionParametersToJson(params: FunctionParameters): JSONObject {
        val json = JSONObject()
        json.put("type", params.type)
        json.put("properties", JSONObject().apply {
            params.properties.forEach { (key, schema) ->
                put(key, propertySchemaToJson(schema))
            }
        })
        json.put("required", JSONArray(params.required))
        return json
    }
    
    private fun propertySchemaToJson(schema: PropertySchema): JSONObject {
        val json = JSONObject()
        json.put("type", schema.type)
        json.put("description", schema.description)
        schema.enum?.let { json.put("enum", JSONArray(it)) }
        schema.items?.let { json.put("items", propertySchemaToJson(it)) }
        return json
    }
    
    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }
    
    private fun jsonArrayToList(json: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until json.length()) {
            val value = json.get(i)
            list.add(
                when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToList(value)
                    else -> value
                }
            )
        }
        return list
    }
    
    /**
     * Generate content with web search capability
     */
    suspend fun generateContentWithWebSearch(
        query: String,
        signal: CancellationSignal? = null
    ): GenerateContentResponseWithGrounding {
        val apiKey = ApiProviderManager.getNextApiKey()
            ?: throw Exception("No API keys configured")
        
        // Use 'web-search' model config (matching TypeScript implementation)
        // This should resolve to a web-search capable model like gemini-2.5-flash
        // For now, use a web-search capable model directly
        val model = "gemini-2.5-flash" // Web search capable model (matching TypeScript default)
        
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", query)
                        })
                    })
                })
            })
            // Enable Google Search tool (matching TypeScript: tools: [{ googleSearch: {} }])
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                })
            })
        }
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("API call failed: ${response.code} - $errorBody")
                }
                
                val responseBody = response.body?.string() ?: "{}"
                val json = JSONObject(responseBody)
                
                parseResponseWithGrounding(json)
            }
        }
    }
    
    private fun parseResponseWithGrounding(json: JSONObject): GenerateContentResponseWithGrounding {
        val candidates = json.optJSONArray("candidates")
        val candidateList = mutableListOf<CandidateWithGrounding>()
        
        if (candidates != null) {
            for (i in 0 until candidates.length()) {
                val candidateJson = candidates.getJSONObject(i)
                val content = candidateJson.optJSONObject("content")
                val groundingMetadataJson = candidateJson.optJSONObject("groundingMetadata")
                
                val contentObj = if (content != null) {
                    parseContent(content)
                } else {
                    null
                }
                
                val groundingMetadata = if (groundingMetadataJson != null) {
                    parseGroundingMetadata(groundingMetadataJson)
                } else {
                    null
                }
                
                candidateList.add(
                    CandidateWithGrounding(
                        content = contentObj,
                        finishReason = candidateJson.optString("finishReason"),
                        groundingMetadata = groundingMetadata
                    )
                )
            }
        }
        
        val usageMetadataJson = json.optJSONObject("usageMetadata")
        val usageMetadata = if (usageMetadataJson != null) {
            UsageMetadata(
                promptTokenCount = usageMetadataJson.optInt("promptTokenCount").takeIf { it > 0 },
                candidatesTokenCount = usageMetadataJson.optInt("candidatesTokenCount").takeIf { it > 0 },
                totalTokenCount = usageMetadataJson.optInt("totalTokenCount").takeIf { it > 0 }
            )
        } else {
            null
        }
        
        return GenerateContentResponseWithGrounding(
            candidates = candidateList,
            finishReason = json.optString("finishReason"),
            usageMetadata = usageMetadata
        )
    }
    
    private fun parseGroundingMetadata(json: JSONObject): GroundingMetadata {
        val chunksJson = json.optJSONArray("groundingChunks")
        val chunks = if (chunksJson != null) {
            (0 until chunksJson.length()).mapNotNull { i ->
                val chunkJson = chunksJson.getJSONObject(i)
                val webJson = chunkJson.optJSONObject("web")
                val web = if (webJson != null) {
                    GroundingChunkWeb(
                        uri = webJson.optString("uri").takeIf { it.isNotEmpty() },
                        title = webJson.optString("title").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
                if (web != null) {
                    GroundingChunkItem(web = web)
                } else {
                    null
                }
            }
        } else {
            null
        }
        
        val supportsJson = json.optJSONArray("groundingSupports")
        val supports = if (supportsJson != null) {
            (0 until supportsJson.length()).mapNotNull { i ->
                val supportJson = supportsJson.getJSONObject(i)
                val segmentJson = supportJson.optJSONObject("segment")
                val segment = if (segmentJson != null) {
                    GroundingSupportSegment(
                        startIndex = segmentJson.optInt("startIndex"),
                        endIndex = segmentJson.optInt("endIndex"),
                        text = segmentJson.optString("text").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
                
                val chunkIndicesJson = supportJson.optJSONArray("groundingChunkIndices")
                val chunkIndices = if (chunkIndicesJson != null) {
                    (0 until chunkIndicesJson.length()).map { chunkIndicesJson.getInt(it) }
                } else {
                    null
                }
                
                if (segment != null || chunkIndices != null) {
                    GroundingSupportItem(
                        segment = segment,
                        groundingChunkIndices = chunkIndices
                    )
                } else {
                    null
                }
            }
        } else {
            null
        }
        
        return GroundingMetadata(
            groundingChunks = chunks,
            groundingSupports = supports
        )
    }
    
    private fun parseContent(json: JSONObject): Content {
        val role = json.getString("role")
        val partsJson = json.getJSONArray("parts")
        val parts = (0 until partsJson.length()).mapNotNull { i ->
            val partJson = partsJson.getJSONObject(i)
            when {
                partJson.has("text") -> {
                    Part.TextPart(text = partJson.getString("text"))
                }
                partJson.has("functionCall") -> {
                    val fcJson = partJson.getJSONObject("functionCall")
                    Part.FunctionCallPart(
                        functionCall = FunctionCall(
                            name = fcJson.getString("name"),
                            args = jsonObjectToMap(fcJson.getJSONObject("args"))
                        )
                    )
                }
                partJson.has("functionResponse") -> {
                    val frJson = partJson.getJSONObject("functionResponse")
                    Part.FunctionResponsePart(
                        functionResponse = FunctionResponse(
                            name = frJson.getString("name"),
                            response = jsonObjectToMap(frJson.getJSONObject("response"))
                        )
                    )
                }
                else -> null
            }
        }
        
        return Content(role = role, parts = parts)
    }
    
    fun getHistory(): List<Content> = chatHistory.toList()
    
    fun resetChat() {
        chatHistory.clear()
    }
}

sealed class GeminiStreamEvent {
    data class Chunk(val text: String) : GeminiStreamEvent()
    data class ToolCall(val functionCall: FunctionCall) : GeminiStreamEvent()
    data class ToolResult(val toolName: String, val result: com.rk.terminal.gemini.tools.ToolResult) : GeminiStreamEvent()
    data class Error(val message: String) : GeminiStreamEvent()
    data class KeysExhausted(val message: String) : GeminiStreamEvent()
    object Done : GeminiStreamEvent()
}
