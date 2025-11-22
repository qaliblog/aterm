package com.qali.aterm.gemini.client

import com.rk.libcommons.alpineDir
import com.rk.settings.Settings
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderManager.KeysExhaustedException
import com.qali.aterm.gemini.tools.DeclarativeTool
import com.qali.aterm.gemini.core.*
import com.qali.aterm.gemini.tools.*
import com.qali.aterm.gemini.SystemInfoService
import com.qali.aterm.gemini.MemoryService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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
    
    // Client with longer timeout for non-streaming mode (metadata generation can take longer)
    private val longTimeoutClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // 3 minutes for complex metadata generation
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
        
        // Check if streaming is enabled
        if (!Settings.enable_streaming) {
            android.util.Log.d("GeminiClient", "sendMessageStream: Streaming disabled, using non-streaming mode")
            
            // Detect intent: create new project vs debug/upgrade existing
            val intent = detectIntent(userMessage)
            android.util.Log.d("GeminiClient", "sendMessageStream: Detected intent: $intent")
            
            if (intent == IntentType.DEBUG_UPGRADE) {
                emitAll(sendMessageNonStreamingReverse(userMessage, onChunk, onToolCall, onToolResult))
            } else {
                emitAll(sendMessageNonStreaming(userMessage, onChunk, onToolCall, onToolResult))
            }
            return@flow
        }
        
        // Use internal streaming function
        emitAll(sendMessageStreamInternal(userMessage, onChunk, onToolCall, onToolResult))
    }
    
    /**
     * Internal streaming function (bypasses settings check)
     */
    private suspend fun sendMessageStreamInternal(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
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
                                val candidates = json.optJSONArray("candidates")
                                if (candidates != null && candidates.length() > 0) {
                                    val candidate = candidates.optJSONObject(0)
                                    if (candidate != null && candidate.has("content")) {
                                        hasContent = true
                                    }
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
            
            // Execute tool with proper coroutine context
            // Use a new coroutine scope with Default dispatcher to avoid blocking IO dispatcher
            // This prevents deadlocks when called from within withContext(Dispatchers.IO)
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    invocation.execute(null, null)
                } catch (e: Exception) {
                    android.util.Log.e("GeminiClient", "Error executing tool $name in runBlocking", e)
                    throw e
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Handle cancellation gracefully
            ToolResult(
                llmContent = "Tool execution was cancelled: $name",
                returnDisplay = "Cancelled",
                error = ToolError(
                    message = "Tool execution cancelled",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Error executing tool: $name", e)
            android.util.Log.e("GeminiClient", "Exception type: ${e.javaClass.simpleName}")
            android.util.Log.e("GeminiClient", "Exception message: ${e.message}")
            e.printStackTrace()
            ToolResult(
                llmContent = "Error executing tool '$name': ${e.message ?: e.javaClass.simpleName}",
                returnDisplay = "Error: ${e.message ?: "Unknown error"}",
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
            
            // Add system information
            append(SystemInfoService.generateSystemContext())
            append("\n")
            
            // Add memory context if available
            val memoryContext = MemoryService.getSummarizedMemory()
            if (memoryContext.isNotEmpty()) {
                append(memoryContext)
                append("\n")
            }
            
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
                append("**IMPORTANT - Documentation Search Planning:**\n")
                append("If the task involves unfamiliar libraries, frameworks, APIs, or requires up-to-date documentation/examples, you MUST include a todo item for web search/documentation lookup in your todo list. Examples:\n")
                append("- \"Search for [library/framework] documentation and best practices\"\n")
                append("- \"Find examples and tutorials for [technology]\"\n")
                append("- \"Look up current API documentation for [service/API]\"\n")
                append("This ensures you have the latest information before implementing.\n\n")
                append("When using `write_todos`:\n")
                append("1. Use this todo list as soon as you receive a user request based on the complexity of the task.\n")
                append("2. **If task needs documentation search, add it as the FIRST or early todo item** before implementation.\n")
                append("3. Keep track of every subtask that you update the list with.\n")
                append("4. Mark a subtask as in_progress before you begin working on it. You should only have one subtask as in_progress at a time.\n")
                append("5. Update the subtask list as you proceed in executing the task. The subtask list is not static and should reflect your progress and current plans.\n")
                append("6. Mark a subtask as completed when you have completed it.\n")
                append("7. Mark a subtask as cancelled if the subtask is no longer needed.\n")
                append("8. **CRITICAL:** After creating a todo list, you MUST continue working on the todos. Creating the plan is NOT completing the task. You must execute each todo item until all are completed or cancelled. Do NOT stop after creating the todo list - continue implementing the tasks.\n\n")
            }
            
            append("# Operational Guidelines\n\n")
            append("## Tone and Style (CLI Interaction)\n")
            append("- **Concise & Direct:** Adopt a professional, direct, and concise tone suitable for a CLI environment.\n")
            append("- **Minimal Output:** Aim for fewer than 3 lines of text output (excluding tool use/code generation) per response whenever practical.\n")
            append("- **No Chitchat:** Avoid conversational filler, preambles, or postambles. Get straight to the action or answer.\n\n")
            
            append("## Tool Usage\n")
            append("- **Parallelism:** Execute multiple independent tool calls in parallel when feasible.\n")
            append("- **Command Execution:** Use shell tools for running shell commands.\n")
            append("- **Web Search:** ALWAYS use web search tools (google_web_search or custom_web_search) when:\n")
            append("  - The user asks about current information, recent events, or real-world data\n")
            append("  - You need to find documentation, tutorials, or examples for libraries/frameworks\n")
            append("  - The user asks questions that require up-to-date information from the internet\n")
            append("  - You need to verify facts, find solutions to problems, or gather information\n")
            append("  - The task involves external APIs, services, or online resources\n")
            append("  **IMPORTANT:** If you're unsure whether information is current or need to verify something, use web search. Don't rely on potentially outdated training data.\n\n")
            
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
    
    /**
     * Restore chat history from AgentMessages
     * This is used to restore conversation context when switching tabs/sessions
     */
    fun restoreHistoryFromMessages(agentMessages: List<com.qali.aterm.ui.screens.agent.AgentMessage>) {
        chatHistory.clear()
        agentMessages.forEach { msg ->
            // Skip loading messages and tool messages, only restore actual conversation
            if (msg.text != "Thinking..." && 
                !msg.text.startsWith("🔧") && 
                !msg.text.startsWith("✅") &&
                !msg.text.startsWith("❌") &&
                !msg.text.startsWith("⚠️")) {
                chatHistory.add(
                    Content(
                        role = if (msg.isUser) "user" else "model",
                        parts = listOf(Part.TextPart(text = msg.text))
                    )
                )
            }
        }
        android.util.Log.d("GeminiClient", "Restored ${chatHistory.size} messages to chat history")
    }
    
    /**
     * Intent types for non-streaming mode
     */
    private enum class IntentType {
        CREATE_NEW,
        DEBUG_UPGRADE
    }
    
    /**
     * Detect user intent: create new project or debug/upgrade existing
     * Uses memory context and keyword analysis
     * Also detects if task needs documentation search or planning
     */
    private suspend fun detectIntent(userMessage: String): IntentType {
        // Load memory context for better intent detection
        val memoryContext = MemoryService.getSummarizedMemory()
        
        val debugKeywords = listOf(
            "debug", "fix", "repair", "error", "bug", "issue", "problem",
            "upgrade", "update", "improve", "refactor", "modify", "change",
            "enhance", "optimize", "correct", "resolve", "solve"
        )
        
        val createKeywords = listOf(
            "create", "new", "build", "generate", "make", "start", "init",
            "setup", "scaffold", "bootstrap"
        )
        
        val messageLower = userMessage.lowercase()
        val contextLower = (userMessage + " " + memoryContext).lowercase()
        
        val debugScore = debugKeywords.count { contextLower.contains(it) }
        val createScore = createKeywords.count { contextLower.contains(it) }
        
        // Check if workspace has existing files
        val workspaceDir = File(workspaceRoot)
        val hasExistingFiles = workspaceDir.exists() && 
            workspaceDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        
        // Check memory for project context
        val hasProjectContext = memoryContext.contains("project", ignoreCase = true) ||
                                memoryContext.contains("codebase", ignoreCase = true) ||
                                memoryContext.contains("repository", ignoreCase = true)
        
        // If workspace has files and debug keywords are present, likely debug/upgrade
        if (hasExistingFiles && (debugScore > createScore || debugScore > 0)) {
            return IntentType.DEBUG_UPGRADE
        }
        
        // If memory indicates existing project and debug keywords, likely debug/upgrade
        if (hasProjectContext && hasExistingFiles && debugScore >= createScore) {
            return IntentType.DEBUG_UPGRADE
        }
        
        // If create keywords dominate, likely create new
        if (createScore > debugScore && !hasExistingFiles) {
            return IntentType.CREATE_NEW
        }
        
        // Default: if workspace has files, assume debug/upgrade
        return if (hasExistingFiles) IntentType.DEBUG_UPGRADE else IntentType.CREATE_NEW
    }
    
    /**
     * Detect if task needs documentation search or planning phase
     * Returns true if task likely needs web search for documentation, tutorials, or examples
     */
    private fun needsDocumentationSearch(userMessage: String): Boolean {
        val messageLower = userMessage.lowercase()
        val memoryContext = MemoryService.getSummarizedMemory().lowercase()
        val contextLower = (userMessage + " " + memoryContext).lowercase()
        
        // Keywords indicating need for documentation/search
        val docSearchKeywords = listOf(
            "documentation", "docs", "tutorial", "example", "guide", "how to",
            "api", "library", "framework", "package", "npm", "pip", "crate",
            "learn", "understand", "reference", "specification", "spec",
            "unknown", "unfamiliar", "new", "first time", "don't know",
            "latest", "current", "up to date", "recent", "modern"
        )
        
        // Framework/library names that might need documentation
        val frameworkKeywords = listOf(
            "react", "vue", "angular", "svelte", "next", "nuxt",
            "express", "fastapi", "django", "flask", "spring",
            "tensorflow", "pytorch", "keras", "pandas", "numpy"
        )
        
        // Check for documentation search indicators
        val hasDocKeywords = docSearchKeywords.any { contextLower.contains(it) }
        val hasFrameworkKeywords = frameworkKeywords.any { contextLower.contains(it) }
        val mentionsLibrary = contextLower.contains("library") || contextLower.contains("package") || 
                             contextLower.contains("framework") || contextLower.contains("tool")
        
        // If task mentions unfamiliar libraries/frameworks or asks for documentation
        return hasDocKeywords || (hasFrameworkKeywords && mentionsLibrary) ||
               messageLower.contains("how do i") || messageLower.contains("what is") ||
               messageLower.contains("show me") || messageLower.contains("find")
    }
    
    /**
     * Non-streaming mode: Enhanced 3-phase approach
     * Phase 1: Get list of all files needed
     * Phase 2: Get comprehensive metadata for all files (relationships, imports, classes, functions, etc.)
     * Phase 3: Generate each file separately with full code using only the metadata provided
     */
    private suspend fun sendMessageNonStreaming(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
        val signal = CancellationSignal() // Create local signal for non-streaming mode
        android.util.Log.d("GeminiClient", "sendMessageNonStreaming: Starting non-streaming mode")
        
        // Add user message to history
        chatHistory.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = userMessage))
            )
        )
        
        val model = ApiProviderManager.getCurrentModel()
        val systemInfo = SystemInfoService.detectSystemInfo()
        val systemContext = SystemInfoService.generateSystemContext()
        
        // Check if task needs documentation search
        val needsDocSearch = needsDocumentationSearch(userMessage)
        
        // Initialize todos for tracking - allow custom todos including documentation search
        var currentTodos = mutableListOf<Todo>()
        val updateTodos: suspend (List<Todo>) -> Unit = { todos ->
            currentTodos = todos.toMutableList()
            val todoCall = FunctionCall(
                name = "write_todos",
                args = mapOf("todos" to todos.map { mapOf("description" to it.description, "status" to it.status.name) })
            )
            emit(GeminiStreamEvent.ToolCall(todoCall))
            onToolCall(todoCall)
            try {
                val todoResult = executeToolSync("write_todos", todoCall.args)
                emit(GeminiStreamEvent.ToolResult("write_todos", todoResult))
                onToolResult("write_todos", todoCall.args)
            } catch (e: Exception) {
                android.util.Log.e("GeminiClient", "Failed to update todos", e)
            }
        }
        
        // Pre-planning phase: Check if documentation search is needed and add to todos
        val initialTodos = mutableListOf<Todo>()
        if (needsDocSearch) {
            emit(GeminiStreamEvent.Chunk("🔍 Task may need documentation search - adding to plan...\n"))
            initialTodos.add(Todo("Search for relevant documentation and examples", TodoStatus.PENDING))
        }
        initialTodos.addAll(listOf(
            Todo("Phase 1: Get file list", TodoStatus.PENDING),
            Todo("Phase 2: Get metadata for all files", TodoStatus.PENDING),
            Todo("Phase 3: Generate code for each file", TodoStatus.PENDING)
        ))
        
        // Phase 1: Get list of all files needed
        emit(GeminiStreamEvent.Chunk("📋 Phase 1: Identifying files needed...\n"))
        
        // Mark Phase 1 as in progress (no withContext - emit must be in same context as Flow)
        val todosWithProgress = initialTodos.map { todo ->
            if (todo.description == "Phase 1: Get file list") {
                todo.copy(status = TodoStatus.IN_PROGRESS)
            } else {
                todo
            }
        }
        updateTodos(todosWithProgress)
        
        // Helper function to wrap emit as suspend function
        suspend fun emitEvent(event: GeminiStreamEvent) {
            emit(event)
        }
        
        val fileListPrompt = """
            $systemContext
            
            Analyze the user's request and provide a complete list of ALL files that need to be created.
            
            For each file, provide ONLY:
            - file_path: The relative path from the project root
            
            Format your response as a JSON array of file objects with only the file_path field.
            Example format:
            [
              {"file_path": "src/main.js"},
              {"file_path": "src/config.js"},
              {"file_path": "package.json"}
            ]
            
            Be comprehensive - include all files needed: source files, config files, documentation, tests, etc.
            
            User request: $userMessage
        """.trimIndent()
        
        // Get file list with retry mechanism
        val fileListRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", fileListPrompt)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", SystemInfoService.generateSystemContext())
                    })
                })
            })
        }
        
        var fileListResult = makeApiCallWithRetryAndCorrection(
            model, fileListRequest, "file list", signal, null, ::emitEvent, onChunk
        )
        
        if (fileListResult == null) {
            emit(GeminiStreamEvent.Error("Failed to get file list after retries"))
            return@flow
        }
        
        // Parse file list
        val fileListJson = try {
            val jsonStart = fileListResult.indexOf('[')
            val jsonEnd = fileListResult.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONArray(fileListResult.substring(jsonStart, jsonEnd))
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to parse file list", e)
            null
        }
        
        if (fileListJson == null || fileListJson.length() == 0) {
            emit(GeminiStreamEvent.Error("Failed to parse file list or no files found"))
            return@flow
        }
        
        val filePaths = (0 until fileListJson.length()).mapNotNull { i ->
            try {
                fileListJson.getJSONObject(i).getString("file_path")
            } catch (e: Exception) {
                null
            }
        }
        
        emit(GeminiStreamEvent.Chunk("✅ Found ${filePaths.size} files to create\n"))
        
        // Update todos - preserve documentation search todo if it exists (no withContext - emit must be in same context)
        var updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 1: Get file list" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 2: Get metadata for all files" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Phase 2: Get comprehensive metadata for all files
        emit(GeminiStreamEvent.Chunk("📊 Phase 2: Generating metadata for all files...\n"))
        
        val metadataPrompt = """
            $systemContext
            
            Now that we know all the files that need to be created, generate comprehensive metadata for ALL files.
            
            Files to create:
            ${filePaths.joinToString("\n") { "- $it" }}
            
            For each file, provide COMPREHENSIVE metadata:
            - file_path: The relative path from project root
            - classes: List of all class names in this file (empty array if none)
            - functions: List of all function/method names in this file (empty array if none)
            - imports: List of all imports/dependencies (use relative paths or file names)
            - exports: List of what this file exports (classes, functions, constants, etc.)
            - metadata_tags: Unique tags for categorization (e.g., "db", "auth", "api", "ui", "config", "test")
            - relationships: List of other files this file depends on (use file_path values)
            - dependencies: List of external dependencies/packages needed
            - description: Detailed description of the file's purpose and role
            - expectations: What this file should accomplish and how it fits in the project
            - file_type: Type of file (e.g., "javascript", "typescript", "python", "html", "css", "json", "config")
            
            For HTML files, also include:
            - links: CSS files, JS files, images, etc. referenced
            - ids: HTML element IDs used
            - classes: CSS classes used
            
            For CSS files, also include:
            - selectors: CSS selectors defined
            - imports: @import statements
            - variables: CSS variables defined
            
            Format your response as a JSON array of file metadata objects.
            
            User's original request: $userMessage
        """.trimIndent()
        
        val metadataRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", metadataPrompt)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", SystemInfoService.generateSystemContext())
                    })
                })
            })
        }
        
        var metadataText = makeApiCallWithRetryAndCorrection(
            model, metadataRequest, "metadata", signal, null, ::emitEvent, onChunk
        )
        
        if (metadataText == null) {
            emit(GeminiStreamEvent.Error("Failed to generate metadata after retries"))
            return@flow
        }
        
        // Parse metadata
        val metadataJson = try {
            val jsonStart = metadataText.indexOf('[')
            val jsonEnd = metadataText.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONArray(metadataText.substring(jsonStart, jsonEnd))
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to parse metadata", e)
            null
        }
        
        if (metadataJson == null || metadataJson.length() != filePaths.size) {
            emit(GeminiStreamEvent.Error("Failed to parse metadata or metadata count mismatch"))
            return@flow
        }
        
        emit(GeminiStreamEvent.Chunk("✅ Metadata generated for ${metadataJson.length()} files\n"))
        
        // Update todos - preserve all existing todos (no withContext - emit must be in same context)
        updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 2: Get metadata for all files" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 3: Generate code for each file" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Phase 3: Generate each file separately with full code
        emit(GeminiStreamEvent.Chunk("💻 Phase 3: Generating code for each file...\n"))
        
        val files = mutableListOf<Pair<String, String>>() // file_path to content
        val metadataMap = mutableMapOf<String, JSONObject>()
        
        // Build metadata map for easy lookup
        for (i in 0 until metadataJson.length()) {
            val fileMeta = metadataJson.getJSONObject(i)
            val filePath = fileMeta.getString("file_path")
            metadataMap[filePath] = fileMeta
        }
        
        // Generate code for each file
        for ((fileIndex, filePath) in filePaths.withIndex()) {
            val fileMeta = metadataMap[filePath] ?: continue
            
            emit(GeminiStreamEvent.Chunk("📝 Generating: $filePath (${fileIndex + 1}/${filePaths.size})\n"))
            
            // Build comprehensive code generation prompt
            val classes = if (fileMeta.has("classes")) {
                fileMeta.getJSONArray("classes").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } else emptyList()
            
            val functions = if (fileMeta.has("functions")) {
                fileMeta.getJSONArray("functions").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } else emptyList()
            
            val imports = if (fileMeta.has("imports")) {
                fileMeta.getJSONArray("imports").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } else emptyList()
            
            val exports = if (fileMeta.has("exports")) {
                fileMeta.getJSONArray("exports").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } else emptyList()
            
            val relationships = if (fileMeta.has("relationships")) {
                fileMeta.getJSONArray("relationships").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } else emptyList()
            
            val description = fileMeta.optString("description", "")
            val expectations = fileMeta.optString("expectations", "")
            val fileType = fileMeta.optString("file_type", "")
            
            val codePrompt = """
                $systemContext
                
                Generate the COMPLETE, FULL code for file: $filePath
                
                **CRITICAL INSTRUCTIONS:**
                - You MUST use ONLY the metadata provided below
                - You MUST include ALL classes, functions, imports, and exports specified
                - You MUST respect the relationships and dependencies
                - Generate complete, working, production-ready code
                - Do NOT use placeholders or TODOs unless explicitly needed
                - Ensure all imports are correct and match the metadata
                - Follow the file type conventions: $fileType
                
                **File Metadata:**
                - Description: $description
                - Expectations: $expectations
                - File Type: $fileType
                - Classes to include: ${classes.joinToString(", ")}
                - Functions to include: ${functions.joinToString(", ")}
                - Imports to use: ${imports.joinToString(", ")}
                - Exports: ${exports.joinToString(", ")}
                - Related files: ${relationships.joinToString(", ")}
                
                **Project Context:**
                - User's original request: $userMessage
                - All files in project: ${filePaths.joinToString(", ")}
                
                Generate the complete code now. Return ONLY the code, no explanations or markdown formatting.
            """.trimIndent()
            
            // Make code generation request
            val codeRequest = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", codePrompt)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", SystemInfoService.generateSystemContext())
                        })
                    })
                })
            }
            
            val codeContent = makeApiCallWithRetryAndCorrection(
                model, codeRequest, "code for $filePath", signal, null, ::emitEvent, onChunk
            )
            
            if (codeContent == null) {
                emit(GeminiStreamEvent.Chunk("⚠️ Failed to generate: $filePath\n"))
                continue
            }
            
            // Extract code (remove markdown code blocks if present)
            val cleanCode = codeContent
                .replace(Regex("```[\\w]*\\n"), "")
                .replace(Regex("```\\n?"), "")
                .trim()
            
            files.add(Pair(filePath, cleanCode))
            emit(GeminiStreamEvent.Chunk("✅ Generated: $filePath\n"))
            onChunk("✅ Generated: $filePath\n")
        }
        
        // Step 3: Create files using write_file tool
        emit(GeminiStreamEvent.Chunk("📝 Creating files...\n"))
        onChunk("📝 Creating files...\n")
        
        for ((filePath, content) in files) {
            val functionCall = FunctionCall(
                name = "write_file",
                args = mapOf(
                    "file_path" to filePath,
                    "content" to content
                )
            )
            
            emit(GeminiStreamEvent.ToolCall(functionCall))
            onToolCall(functionCall)
            
            val toolResult = try {
                executeToolSync(functionCall.name, functionCall.args)
            } catch (e: Exception) {
                ToolResult(
                    llmContent = "Error: ${e.message}",
                    returnDisplay = "Error",
                    error = ToolError(
                        message = e.message ?: "Unknown error",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
            
            emit(GeminiStreamEvent.ToolResult(functionCall.name, toolResult))
            onToolResult(functionCall.name, functionCall.args)
        }
        
        // Mark Phase 3 as completed (no withContext - emit must be in same context)
        updatedTodos = currentTodos.map { todo ->
            if (todo.description == "Phase 3: Generate code for each file") {
                todo.copy(status = TodoStatus.COMPLETED)
            } else {
                todo
            }
        }
        updateTodos(updatedTodos)
        
        emit(GeminiStreamEvent.Chunk("\n✨ Project generation complete!\n"))
        onChunk("\n✨ Project generation complete!\n")
        emit(GeminiStreamEvent.Done)
    }
    
    /**
     * Helper function for API calls with retry and AI-powered error correction
     * Handles bash command failures by asking AI to correct them
     */
    private suspend fun makeApiCallWithRetryAndCorrection(
        model: String,
        requestBody: JSONObject,
        operationName: String,
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): String? {
        var retryCount = 0
        val maxRetries = 3
        var lastError: Throwable? = null
        var lastResponse: String? = null
        
        while (retryCount < maxRetries && signal?.isAborted() != true) {
            if (retryCount > 0) {
                val backoffMs = minOf(1000L * (1 shl retryCount), 20000L)
                emit(GeminiStreamEvent.Chunk("⏳ Retrying $operationName (attempt ${retryCount + 1}/$maxRetries)...\n"))
                onChunk("⏳ Retrying $operationName (attempt ${retryCount + 1}/$maxRetries)...\n")
                kotlinx.coroutines.delay(backoffMs)
            }
            
            val result = ApiProviderManager.makeApiCallWithRetry { key ->
                try {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val response = makeApiCallSimple(key, model, requestBody, useLongTimeout = true)
                        Result.success(response)
                    }
                } catch (e: Exception) {
                    lastError = e
                    Result.failure(e)
                }
            }
            
            if (result.isSuccess) {
                lastResponse = result.getOrNull()
                return lastResponse
            }
            
            val error = result.exceptionOrNull() ?: lastError
            retryCount++
            
            // Check if it's a bash command error that can be corrected
            val errorMessage = error?.message ?: ""
            if (errorMessage.contains("command not found", ignoreCase = true) ||
                errorMessage.contains("No such file or directory", ignoreCase = true) ||
                errorMessage.contains("Permission denied", ignoreCase = true) ||
                (errorMessage.contains("exit code") && errorMessage.contains("non-zero", ignoreCase = true))) {
                
                // Ask AI to correct the command
                emit(GeminiStreamEvent.Chunk("🔧 Command failed, asking AI to correct...\n"))
                onChunk("🔧 Command failed, asking AI to correct...\n")
                
                val correctionPrompt = """
                    A bash command failed with this error: $errorMessage
                    
                    The operation was: $operationName
                    
                    Please provide a corrected command or approach that will work on this system:
                    ${SystemInfoService.generateSystemContext()}
                    
                    If this is not a command issue, provide guidance on how to proceed.
                """.trimIndent()
                
                val correctionRequest = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", correctionPrompt)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", SystemInfoService.generateSystemContext())
                            })
                        })
                    })
                }
                
                val correctionResult = ApiProviderManager.makeApiCallWithRetry { key ->
                    try {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            val response = makeApiCallSimple(key, model, correctionRequest, useLongTimeout = false)
                            Result.success(response)
                        }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                
                if (correctionResult.isSuccess) {
                    val correction = correctionResult.getOrNull() ?: ""
                    emit(GeminiStreamEvent.Chunk("💡 AI suggestion: ${correction.take(200)}\n"))
                    onChunk("💡 AI suggestion: ${correction.take(200)}\n")
                }
            }
            
            // If it's a 503/overloaded error, retry
            if (error is IOException && (errorMessage.contains("503", ignoreCase = true) ||
                                        errorMessage.contains("overloaded", ignoreCase = true) ||
                                        errorMessage.contains("unavailable", ignoreCase = true))) {
                continue // Retry
            }
            
            // For other errors, break after max retries
            if (retryCount >= maxRetries) {
                break
            }
        }
        
        return null
    }
    
    /**
     * Non-streaming reverse flow: Debug/Upgrade existing project
     * 1. Extract project structure (classes, functions, imports, tree)
     * 2. Analyze what needs fixing
     * 3. Read specific lines/functions
     * 4. Get fixes with assurance
     * 5. Apply fixes using edit tools
     */
    private suspend fun sendMessageNonStreamingReverse(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
        val signal = CancellationSignal()
        android.util.Log.d("GeminiClient", "sendMessageNonStreamingReverse: Starting reverse flow for debug/upgrade")
        
        // Add user message to history
        chatHistory.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = userMessage))
            )
        )
        
        val model = ApiProviderManager.getCurrentModel()
        val systemContext = SystemInfoService.generateSystemContext()
        
        // Check if task needs documentation search
        val needsDocSearch = needsDocumentationSearch(userMessage)
        
        // Initialize todos - allow custom todos including documentation search
        var currentTodos = mutableListOf<Todo>()
        val updateTodos: suspend (List<Todo>) -> Unit = { todos ->
            currentTodos = todos.toMutableList()
            val todoCall = FunctionCall(
                name = "write_todos",
                args = mapOf("todos" to todos.map { mapOf("description" to it.description, "status" to it.status.name) })
            )
            emit(GeminiStreamEvent.ToolCall(todoCall))
            onToolCall(todoCall)
            try {
                val todoResult = executeToolSync("write_todos", todoCall.args)
                emit(GeminiStreamEvent.ToolResult("write_todos", todoResult))
                onToolResult("write_todos", todoCall.args)
            } catch (e: Exception) {
                android.util.Log.e("GeminiClient", "Failed to update todos", e)
            }
        }
        
        // Pre-planning phase: Check if documentation search is needed and add to todos
        val initialTodos = mutableListOf<Todo>()
        if (needsDocSearch) {
            emit(GeminiStreamEvent.Chunk("🔍 Task may need documentation search - adding to plan...\n"))
            onChunk("🔍 Task may need documentation search - adding to plan...\n")
            initialTodos.add(Todo("Search for relevant documentation and examples", TodoStatus.PENDING))
        }
        initialTodos.addAll(listOf(
            Todo("Phase 1: Extract project structure", TodoStatus.PENDING),
            Todo("Phase 2: Analyze what needs fixing", TodoStatus.PENDING),
            Todo("Phase 3: Read specific lines/functions", TodoStatus.PENDING),
            Todo("Phase 4: Get fixes with assurance", TodoStatus.PENDING),
            Todo("Phase 5: Apply fixes", TodoStatus.PENDING)
        ))
        
        // Mark Phase 1 as in progress (no withContext - emit must be in same context)
        val todosWithProgress = initialTodos.map { todo ->
            if (todo.description == "Phase 1: Extract project structure") {
                todo.copy(status = TodoStatus.IN_PROGRESS)
            } else {
                todo
            }
        }
        updateTodos(todosWithProgress)
        
        // Phase 1: Extract project structure
        emit(GeminiStreamEvent.Chunk("📊 Phase 1: Extracting project structure...\n"))
        onChunk("📊 Phase 1: Extracting project structure...\n")
        
        // Helper function to wrap emit as suspend function
        suspend fun emitEvent(event: GeminiStreamEvent) {
            emit(event)
        }
        
        val projectStructure = extractProjectStructure(workspaceRoot, signal, ::emitEvent, onChunk)
        
        if (projectStructure.isEmpty()) {
            emit(GeminiStreamEvent.Error("No source files found in project"))
            return@flow
        }
        
        val fileCount = projectStructure.split("===").size - 1
        emit(GeminiStreamEvent.Chunk("✅ Extracted structure from $fileCount files\n"))
        onChunk("✅ Extracted structure from $fileCount files\n")
        
        // Update todos after Phase 1 (no withContext - emit must be in same context)
        // Preserve documentation search todo if it exists
        val phase1CompletedTodos = if (currentTodos.any { it.description == "Search for relevant documentation and examples" }) {
            currentTodos.map { todo ->
                when {
                    todo.description == "Phase 1: Extract project structure" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 2: Analyze what needs fixing" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    else -> todo
                }
            }
        } else {
            currentTodos.map { todo ->
                when {
                    todo.description == "Phase 1: Extract project structure" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 2: Analyze what needs fixing" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    else -> todo
                }
            }
        }
        updateTodos(phase1CompletedTodos)
        
        // Phase 2: Analyze what needs fixing
        emit(GeminiStreamEvent.Chunk("🔍 Phase 2: Analyzing what needs fixing...\n"))
        onChunk("🔍 Phase 2: Analyzing what needs fixing...\n")
        
        val analysisPrompt = """
            $systemContext
            
            **Project Goal:** $userMessage
            
            **Project Structure:**
            $projectStructure
            
            Analyze this project and identify:
            1. Which functions/classes need fixing or updating
            2. Which specific lines need to be read for context
            3. What issues exist that need to be resolved
            
            Format your response as JSON:
            {
              "files_to_read": [
                {
                  "file_path": "path/to/file.ext",
                  "functions": ["functionName1", "functionName2"],
                  "line_ranges": [[start1, end1], [start2, end2]],
                  "reason": "Why this needs to be read"
                }
              ],
              "issues": [
                {
                  "file_path": "path/to/file.ext",
                  "function": "functionName",
                  "line_range": [start, end],
                  "issue": "Description of the issue",
                  "priority": "high|medium|low"
                }
              ]
            }
        """.trimIndent()
        
        val analysisRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", analysisPrompt)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemContext)
                    })
                })
            })
        }
        
        var analysisText = makeApiCallWithRetryAndCorrection(
            model, analysisRequest, "analysis", signal, null, ::emitEvent, onChunk
        )
        
        if (analysisText == null) {
            emit(GeminiStreamEvent.Error("Failed to analyze project"))
            return@flow
        }
        
        // Parse analysis
        val analysisJson = try {
            val jsonStart = analysisText.indexOf('{')
            val jsonEnd = analysisText.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONObject(analysisText.substring(jsonStart, jsonEnd))
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to parse analysis", e)
            null
        }
        
        if (analysisJson == null) {
            emit(GeminiStreamEvent.Error("Failed to parse analysis results"))
            return@flow
        }
        
        emit(GeminiStreamEvent.Chunk("✅ Analysis complete\n"))
        onChunk("✅ Analysis complete\n")
        
        // Update todos - preserve documentation search todo if it exists (no withContext - emit must be in same context)
        var updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 1: Extract project structure" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 2: Analyze what needs fixing" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 3: Read specific lines/functions" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Phase 3: Read specific lines/functions
        emit(GeminiStreamEvent.Chunk("📖 Phase 3: Reading specific code sections...\n"))
        onChunk("📖 Phase 3: Reading specific code sections...\n")
        
        val filesToRead = analysisJson.optJSONArray("files_to_read")
        val codeSections = mutableMapOf<String, String>() // file_path -> code content
        
        if (filesToRead != null) {
            for (i in 0 until filesToRead.length()) {
                val fileInfo = filesToRead.getJSONObject(i)
                val filePath = fileInfo.getString("file_path")
                
                try {
                    val readResult = executeToolSync("read_file", mapOf("file_path" to filePath))
                    val fullContent = readResult.llmContent
                    
                    // Extract specific functions or line ranges
                    val functions = if (fileInfo.has("functions")) {
                        fileInfo.getJSONArray("functions").let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        }
                    } else emptyList()
                    
                    val lineRanges = if (fileInfo.has("line_ranges")) {
                        fileInfo.getJSONArray("line_ranges").let { arr ->
                            (0 until arr.length()).mapNotNull { idx ->
                                val range = arr.getJSONArray(idx)
                                if (range.length() >= 2) {
                                    Pair(range.getInt(0), range.getInt(1))
                                } else null
                            }
                        }
                    } else emptyList()
                    
                    val extractedCode = if (functions.isEmpty() && lineRanges.isEmpty()) {
                        // If no specific functions/ranges, use full content
                        fullContent
                    } else {
                        extractCodeSections(fullContent, filePath, functions, lineRanges)
                    }
                    codeSections[filePath] = extractedCode
                    
                    emit(GeminiStreamEvent.Chunk("📄 Read: $filePath\n"))
                    onChunk("📄 Read: $filePath\n")
                } catch (e: Exception) {
                    android.util.Log.e("GeminiClient", "Failed to read $filePath", e)
                }
            }
        }
        
        // Update todos - preserve documentation search todo if it exists (no withContext - emit must be in same context)
        updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 3: Read specific lines/functions" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 4: Get fixes with assurance" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Phase 4: Get fixes with assurance
        emit(GeminiStreamEvent.Chunk("🔧 Phase 4: Getting fixes with assurance...\n"))
        onChunk("🔧 Phase 4: Getting fixes with assurance...\n")
        
        val codeContext = codeSections.entries.joinToString("\n\n") { (path, code) ->
            "=== $path ===\n$code"
        }
        
        val issues = analysisJson.optJSONArray("issues")
        val issuesText = if (issues != null) {
            (0 until issues.length()).joinToString("\n") { i ->
                val issue = issues.getJSONObject(i)
                "- ${issue.getString("file_path")}: ${issue.getString("issue")} (${issue.optString("priority", "medium")})"
            }
        } else "No specific issues identified"
        
        val fixPrompt = """
            $systemContext
            
            **Project Goal:** $userMessage
            
            **Code Sections to Fix:**
            $codeContext
            
            **Identified Issues:**
            $issuesText
            
            **Project Structure:**
            $projectStructure
            
            Provide fixes for all identified issues. For each fix, provide:
            1. The file path
            2. The exact old_string to replace (include enough context)
            3. The exact new_string (complete fixed code)
            4. Confidence level (high/medium/low)
            
            Format as JSON array:
            [
              {
                "file_path": "path/to/file.ext",
                "old_string": "exact code to replace with context",
                "new_string": "complete fixed code",
                "confidence": "high|medium|low",
                "description": "What this fix does"
              }
            ]
            
            Be thorough and ensure all fixes are complete and correct.
        """.trimIndent()
        
        val fixRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", fixPrompt)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemContext)
                    })
                })
            })
        }
        
        var fixText = makeApiCallWithRetryAndCorrection(
            model, fixRequest, "fixes", signal, null, ::emitEvent, onChunk
        )
        
        if (fixText == null) {
            emit(GeminiStreamEvent.Error("Failed to generate fixes"))
            return@flow
        }
        
        // Parse fixes
        val fixesJson = try {
            val jsonStart = fixText.indexOf('[')
            val jsonEnd = fixText.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONArray(fixText.substring(jsonStart, jsonEnd))
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to parse fixes", e)
            null
        }
        
        if (fixesJson == null || fixesJson.length() == 0) {
            emit(GeminiStreamEvent.Error("No fixes generated"))
            return@flow
        }
        
        emit(GeminiStreamEvent.Chunk("✅ Generated ${fixesJson.length()} fixes\n"))
        onChunk("✅ Generated ${fixesJson.length()} fixes\n")
        
        // Update todos - preserve documentation search todo if it exists (no withContext - emit must be in same context)
        updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 4: Get fixes with assurance" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 5: Apply fixes" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Phase 5: Apply fixes (group by file for efficiency)
        emit(GeminiStreamEvent.Chunk("✏️ Phase 5: Applying fixes...\n"))
        onChunk("✏️ Phase 5: Applying fixes...\n")
        
        // Group fixes by file
        val fixesByFile = mutableMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until fixesJson.length()) {
            val fix = fixesJson.getJSONObject(i)
            val filePath = fix.getString("file_path")
            fixesByFile.getOrPut(filePath) { mutableListOf() }.add(fix)
        }
        
        // Apply fixes file by file
        for ((filePath, fixes) in fixesByFile) {
            emit(GeminiStreamEvent.Chunk("🔨 Applying ${fixes.size} fix(es) to $filePath...\n"))
            onChunk("🔨 Applying ${fixes.size} fix(es) to $filePath...\n")
            
            var successCount = 0
            var failCount = 0
            
            for (fix in fixes) {
                val oldString = fix.getString("old_string")
                val newString = fix.getString("new_string")
                val confidence = fix.optString("confidence", "medium")
                val description = fix.optString("description", "")
                
                val editCall = FunctionCall(
                    name = "edit",
                    args = mapOf(
                        "file_path" to filePath,
                        "old_string" to oldString,
                        "new_string" to newString
                    )
                )
                
                emit(GeminiStreamEvent.ToolCall(editCall))
                onToolCall(editCall)
                
                val editResult = try {
                    executeToolSync("edit", editCall.args)
                } catch (e: Exception) {
                    ToolResult(
                        llmContent = "Error: ${e.message}",
                        returnDisplay = "Error",
                        error = ToolError(
                            message = e.message ?: "Unknown error",
                            type = ToolErrorType.EXECUTION_ERROR
                        )
                    )
                }
                
                emit(GeminiStreamEvent.ToolResult("edit", editResult))
                onToolResult("edit", editCall.args)
                
                if (editResult.error == null) {
                    successCount++
                } else {
                    failCount++
                    emit(GeminiStreamEvent.Chunk("⚠️ Fix failed: ${editResult.error.message}\n"))
                    onChunk("⚠️ Fix failed: ${editResult.error.message}\n")
                }
            }
            
            if (successCount > 0) {
                emit(GeminiStreamEvent.Chunk("✅ $filePath: $successCount fix(es) applied${if (failCount > 0) ", $failCount failed" else ""}\n"))
                onChunk("✅ $filePath: $successCount fix(es) applied${if (failCount > 0) ", $failCount failed" else ""}\n")
            } else {
                emit(GeminiStreamEvent.Chunk("❌ $filePath: All fixes failed\n"))
                onChunk("❌ $filePath: All fixes failed\n")
            }
        }
        
        // Mark Phase 5 as completed - preserve documentation search todo if it exists (no withContext - emit must be in same context)
        updatedTodos = currentTodos.map { todo ->
            if (todo.description == "Phase 5: Apply fixes") {
                todo.copy(status = TodoStatus.COMPLETED)
            } else {
                todo
            }
        }
        updateTodos(updatedTodos)
        
        emit(GeminiStreamEvent.Chunk("\n✨ Debug/upgrade complete!\n"))
        onChunk("\n✨ Debug/upgrade complete!\n")
        emit(GeminiStreamEvent.Done)
    }
    
    /**
     * Extract project structure: classes, functions, imports, tree
     */
    private suspend fun extractProjectStructure(
        workspaceRoot: String,
        signal: CancellationSignal?,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): String {
        val structure = StringBuilder()
        val workspaceDir = File(workspaceRoot)
        
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return ""
        }
        
        // Get project tree
        val projectTree = buildProjectTree(workspaceDir, maxDepth = 3)
        structure.append("**Project Tree:**\n$projectTree\n\n")
        
        // Extract from source files
        val sourceFiles = findSourceFiles(workspaceDir)
        structure.append("**Files with Code Structure:**\n\n")
        
        for (file in sourceFiles.take(50)) { // Limit to 50 files
            if (signal?.isAborted() == true) break
            
            try {
                val relativePath = file.relativeTo(workspaceDir).path
                val content = file.readText()
                
                structure.append("=== $relativePath ===\n")
                
                // Extract imports
                val imports = extractImports(content, file.extension)
                if (imports.isNotEmpty()) {
                    structure.append("Imports: ${imports.joinToString(", ")}\n")
                }
                
                // Extract classes
                val classes = extractClasses(content, file.extension)
                if (classes.isNotEmpty()) {
                    classes.forEach { (name, line) ->
                        structure.append("Class: $name (line $line)\n")
                    }
                }
                
                // Extract functions
                val functions = extractFunctions(content, file.extension)
                if (functions.isNotEmpty()) {
                    functions.forEach { (name, line) ->
                        structure.append("Function: $name (line $line)\n")
                    }
                }
                
                structure.append("\n")
            } catch (e: Exception) {
                android.util.Log.e("GeminiClient", "Failed to extract from ${file.name}", e)
            }
        }
        
        return structure.toString()
    }
    
    /**
     * Build project tree structure
     */
    private fun buildProjectTree(dir: File, prefix: String = "", maxDepth: Int = 3, currentDepth: Int = 0): String {
        if (currentDepth >= maxDepth) return ""
        
        val builder = StringBuilder()
        val files = dir.listFiles()?.sortedBy { !it.isDirectory } ?: return ""
        
        for ((index, file) in files.withIndex()) {
            if (file.name.startsWith(".")) continue
            
            val isLast = index == files.size - 1
            val currentPrefix = if (isLast) "└── " else "├── "
            builder.append("$prefix$currentPrefix${file.name}\n")
            
            if (file.isDirectory) {
                val nextPrefix = prefix + if (isLast) "    " else "│   "
                builder.append(buildProjectTree(file, nextPrefix, maxDepth, currentDepth + 1))
            }
        }
        
        return builder.toString()
    }
    
    /**
     * Find source files in project
     */
    private fun findSourceFiles(dir: File): List<File> {
        val sourceExtensions = setOf(
            "kt", "java", "js", "ts", "jsx", "tsx", "py", "go", "rs", "cpp", "c", "h",
            "html", "css", "xml", "json", "yaml", "yml", "md"
        )
        
        val files = mutableListOf<File>()
        
        fun traverse(currentDir: File) {
            if (!currentDir.exists() || !currentDir.isDirectory) return
            
            currentDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(".")) return
                if (file.isDirectory && file.name != "node_modules" && file.name != ".git") {
                    traverse(file)
                } else if (file.isFile) {
                    val ext = file.extension.lowercase()
                    if (ext in sourceExtensions) {
                        files.add(file)
                    }
                }
            }
        }
        
        traverse(dir)
        return files
    }
    
    /**
     * Extract imports from file content
     */
    private fun extractImports(content: String, extension: String): List<String> {
        return when (extension.lowercase()) {
            "kt", "java" -> {
                Regex("^import\\s+([^;]+);", RegexOption.MULTILINE)
                    .findAll(content)
                    .map { it.groupValues[1].trim() }
                    .toList()
            }
            "js", "ts", "jsx", "tsx" -> {
                Regex("^import\\s+.*?from\\s+['\"]([^'\"]+)['\"]", RegexOption.MULTILINE)
                    .findAll(content)
                    .map { it.groupValues[1].trim() }
                    .toList()
            }
            "py" -> {
                Regex("^import\\s+([^\\n]+)|^from\\s+([^\\s]+)\\s+import", RegexOption.MULTILINE)
                    .findAll(content)
                    .mapNotNull { it.groupValues[1].takeIf { it.isNotEmpty() } ?: it.groupValues[2] }
                    .toList()
            }
            else -> emptyList()
        }
    }
    
    /**
     * Extract classes from file content
     */
    private fun extractClasses(content: String, extension: String): List<Pair<String, Int>> {
        val classes = mutableListOf<Pair<String, Int>>()
        val lines = content.lines()
        
        when (extension.lowercase()) {
            "kt", "java" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:class|interface|enum)\\s+(\\w+)").find(line)?.let {
                        classes.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
            "js", "ts", "jsx", "tsx" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:class|interface|enum|type|const)\\s+(\\w+)").find(line)?.let {
                        classes.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
            "py" -> {
                lines.forEachIndexed { index, line ->
                    Regex("class\\s+(\\w+)").find(line)?.let {
                        classes.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
        }
        
        return classes
    }
    
    /**
     * Extract functions from file content
     */
    private fun extractFunctions(content: String, extension: String): List<Pair<String, Int>> {
        val functions = mutableListOf<Pair<String, Int>>()
        val lines = content.lines()
        
        when (extension.lowercase()) {
            "kt", "java" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:fun|private|public|protected)?\\s*(?:fun)?\\s*(\\w+)\\s*\\(").find(line)?.let {
                        functions.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
            "js", "ts", "jsx", "tsx" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:function|const|let|var)\\s+(\\w+)\\s*[=(]|(\\w+)\\s*:\\s*function").find(line)?.let {
                        val name = it.groupValues[1].takeIf { it.isNotEmpty() } ?: it.groupValues[2]
                        if (name.isNotEmpty()) {
                            functions.add(Pair(name, index + 1))
                        }
                    }
                }
            }
            "py" -> {
                lines.forEachIndexed { index, line ->
                    Regex("def\\s+(\\w+)\\s*\\(").find(line)?.let {
                        functions.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
        }
        
        return functions
    }
    
    /**
     * Extract specific code sections (functions or line ranges)
     */
    private fun extractCodeSections(
        content: String,
        filePath: String,
        functionNames: List<String>,
        lineRanges: List<Pair<Int, Int>>
    ): String {
        val lines = content.lines()
        val sections = mutableListOf<String>()
        
        // Extract functions
        for (funcName in functionNames) {
            val funcPattern = when {
                filePath.endsWith(".kt") || filePath.endsWith(".java") -> 
                    Regex("fun\\s+$funcName\\s*\\(")
                filePath.endsWith(".js") || filePath.endsWith(".ts") -> 
                    Regex("(?:function|const|let|var)\\s+$funcName\\s*[=(]")
                filePath.endsWith(".py") -> 
                    Regex("def\\s+$funcName\\s*\\(")
                else -> Regex("$funcName\\s*\\(")
            }
            
            lines.forEachIndexed { index, line ->
                if (funcPattern.find(line) != null) {
                    // Extract function with context (next 50 lines or until next function)
                    val endLine = minOf(index + 50, lines.size)
                    val funcCode = lines.subList(index, endLine).joinToString("\n")
                    sections.add("// Function: $funcName (line ${index + 1})\n$funcCode")
                }
            }
        }
        
        // Extract line ranges
        for ((start, end) in lineRanges) {
            val startIdx = (start - 1).coerceAtLeast(0)
            val endIdx = end.coerceAtMost(lines.size)
            if (startIdx < endIdx) {
                val rangeCode = lines.subList(startIdx, endIdx).joinToString("\n")
                sections.add("// Lines $start-$end\n$rangeCode")
            }
        }
        
        return sections.joinToString("\n\n---\n\n")
    }
    
    /**
     * Simple API call that returns the full response text (non-streaming)
     * Note: This is a blocking function, should be called from within withContext(Dispatchers.IO)
     */
    private fun makeApiCallSimple(
        apiKey: String,
        model: String,
        requestBody: JSONObject,
        useLongTimeout: Boolean = false
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val httpClient = if (useLongTimeout) longTimeoutClient else client
        android.util.Log.d("GeminiClient", "makeApiCallSimple: Using ${if (useLongTimeout) "long" else "normal"} timeout client")
        
        httpClient.newCall(request).execute().use { response ->
            android.util.Log.d("GeminiClient", "makeApiCallSimple: Response code: ${response.code}")
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("API call failed: ${response.code} - $errorBody")
            }
            
            val bodyString = response.body?.string() ?: ""
            val json = JSONObject(bodyString)
            val candidates = json.optJSONArray("candidates")
            
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                if (content != null) {
                    val parts = content.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val textParts = (0 until parts.length())
                            .mapNotNull { i ->
                                val part = parts.getJSONObject(i)
                                if (part.has("text")) part.getString("text") else null
                            }
                        return textParts.joinToString("")
                    }
                }
            }
            
            return ""
        }
    }
}

sealed class GeminiStreamEvent {
    data class Chunk(val text: String) : GeminiStreamEvent()
    data class ToolCall(val functionCall: FunctionCall) : GeminiStreamEvent()
    data class ToolResult(val toolName: String, val result: com.qali.aterm.gemini.tools.ToolResult) : GeminiStreamEvent()
    data class Error(val message: String) : GeminiStreamEvent()
    data class KeysExhausted(val message: String) : GeminiStreamEvent()
    object Done : GeminiStreamEvent()
}
