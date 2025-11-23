package com.qali.aterm.gemini.client

import com.rk.libcommons.alpineDir
import com.rk.settings.Settings
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderManager.KeysExhaustedException
import com.qali.aterm.api.ApiProviderType
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
 * Multi-Provider Agent Client for making API calls and handling tool execution
 * Supports: Google Gemini, OpenAI, Anthropic Claude, Ollama, and custom providers
 * Handles both streaming and non-streaming modes with multi-phase workflows
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
        
        // Detect intent: create new project vs debug/upgrade existing
        val intent = detectIntent(userMessage)
        android.util.Log.d("GeminiClient", "sendMessageStream: Detected intent: $intent")
        
        // Add first prompt to clarify and expand user intention
        val enhancedUserMessage = enhanceUserIntent(userMessage, intent)
        
        // Check if streaming is enabled
        if (!Settings.enable_streaming) {
            android.util.Log.d("GeminiClient", "sendMessageStream: Streaming disabled, using non-streaming mode")
            
            when (intent) {
                IntentType.TEST_ONLY -> {
                    emitAll(sendMessageTestOnly(enhancedUserMessage, onChunk, onToolCall, onToolResult))
                }
                IntentType.DEBUG_UPGRADE -> {
                    emitAll(sendMessageNonStreamingReverse(enhancedUserMessage, onChunk, onToolCall, onToolResult))
                }
                else -> {
                    emitAll(sendMessageNonStreaming(enhancedUserMessage, onChunk, onToolCall, onToolResult))
                }
            }
            return@flow
        }
        
        // Use internal streaming function with enhanced message
        emitAll(sendMessageStreamInternal(enhancedUserMessage, onChunk, onToolCall, onToolResult))
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
        // Add user message to history (only if it's not already a function response or continuation)
        val isContinuation = userMessage == "__CONTINUE__"
        if (userMessage.isNotEmpty() && !isContinuation) {
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
            // For continuation, use empty string to continue from chat history
            val requestBody = buildRequest(if (turnCount == 1 && !isContinuation) userMessage else "", model)
            
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
        // Determine API endpoint and convert request based on provider type
        val providerType = ApiProviderManager.selectedProvider
        val (url, convertedRequestBody, headers) = when (providerType) {
            ApiProviderType.GOOGLE -> {
                // Google Gemini API endpoint - using SSE (Server-Sent Events) for streaming
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey"
                Triple(url, requestBody, emptyMap<String, String>())
            }
            ApiProviderType.OPENAI -> {
                // OpenAI API endpoint
                val url = "https://api.openai.com/v1/chat/completions"
                val headers = mapOf("Authorization" to "Bearer $apiKey")
                val convertedBody = convertRequestToOpenAI(requestBody, model)
                Triple(url, convertedBody, headers)
            }
            ApiProviderType.ANTHROPIC -> {
                // Anthropic Claude API endpoint
                val url = "https://api.anthropic.com/v1/messages"
                val headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
                val convertedBody = convertRequestToAnthropic(requestBody, model)
                Triple(url, convertedBody, headers)
            }
            ApiProviderType.CUSTOM -> {
                // Custom provider - check if it's Ollama
                if (apiKey.contains("localhost") || apiKey.contains("127.0.0.1") || apiKey.contains("ollama") || apiKey.contains(":11434")) {
                    // Ollama format: http://localhost:11434/api/chat
                    val baseUrl = when {
                        apiKey.startsWith("http") -> apiKey.split("/api").first()
                        apiKey.contains(":11434") -> "http://$apiKey"
                        else -> "http://localhost:11434"
                    }
                    val url = "$baseUrl/api/chat"
                    val convertedBody = convertRequestToOllama(requestBody, model)
                    Triple(url, convertedBody, emptyMap<String, String>())
                } else {
                    // Generic custom API - assume it's a full URL and Gemini-compatible format
                    Triple(apiKey, requestBody, emptyMap<String, String>())
                }
            }
            else -> {
                // For other providers, use Gemini-compatible endpoint for now
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey"
                Triple(url, requestBody, emptyMap<String, String>())
            }
        }
        android.util.Log.d("GeminiClient", "makeApiCall: Provider: $providerType, URL: ${url.replace(apiKey, "***")}")
        android.util.Log.d("GeminiClient", "makeApiCall: Model: $model")
        
        val requestBuilder = Request.Builder()
            .url(url)
            .post(convertedRequestBody.toString().toRequestBody("application/json".toMediaType()))
        
        // Add headers if any
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        
        val request = requestBuilder.build()
        
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
                        // Parse response based on provider type
                        val finishReason = when (providerType) {
                            ApiProviderType.GOOGLE -> {
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
                                        val fr = processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, providerType)
                                        if (fr != null) {
                                            lastFinishReason = fr
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
                                        "STOP"
                                    } else {
                                        lastFinishReason
                                    }
                                } else {
                                    // Try parsing as SSE (Server-Sent Events) format
                                    parseGeminiSSEResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                                }
                            }
                            ApiProviderType.OPENAI -> {
                                parseOpenAIResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                            }
                            ApiProviderType.ANTHROPIC -> {
                                parseAnthropicResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                            }
                            ApiProviderType.CUSTOM -> {
                                if (apiKey.contains("localhost") || apiKey.contains("127.0.0.1") || apiKey.contains("ollama") || apiKey.contains(":11434")) {
                                    parseOllamaResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                                } else {
                                    // Generic custom - try Gemini format
                                    parseGeminiSSEResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                                }
                            }
                            else -> {
                                // Default to Gemini format
                                parseGeminiSSEResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute)
                            }
                        }
                        return finishReason
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
    
    /**
     * Parse Gemini SSE response format
     */
    private fun parseGeminiSSEResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
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
                    val finishReason = processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, ApiProviderType.GOOGLE)
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
                    return processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, ApiProviderType.GOOGLE)
                } catch (e: Exception) {
                    android.util.Log.w("GeminiClient", "makeApiCall: Unexpected line format: ${trimmedLine.take(100)}")
                }
            }
        }
        android.util.Log.d("GeminiClient", "makeApiCall: Finished SSE parsing. Total lines: $lineCount, Data lines: $dataLineCount")
        return null
    }
    
    /**
     * Parse OpenAI response format
     */
    private fun parseOpenAIResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
        val json = JSONObject(bodyString)
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message")
            if (message != null) {
                val content = message.optString("content", "")
                if (content.isNotEmpty()) {
                    onChunk(content)
                }
                
                // Handle tool calls
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val toolCall = toolCalls.getJSONObject(i)
                        val function = toolCall.optJSONObject("function")
                        if (function != null) {
                            val functionName = function.getString("name")
                            val functionArgs = function.optString("arguments", "{}")
                            val callId = toolCall.optString("id", "")
                            
                            val argsMap = try {
                                jsonObjectToMap(JSONObject(functionArgs))
                            } catch (e: Exception) {
                                emptyMap<String, Any>()
                            }
                            
                            val functionCall = FunctionCall(
                                name = functionName,
                                args = argsMap,
                                id = callId
                            )
                            onToolCall(functionCall)
                            toolCallsToExecute.add(Triple(functionCall, ToolResult(llmContent = "", returnDisplay = ""), callId))
                        }
                    }
                }
                
                val finishReason = choice.optString("finish_reason", "stop")
                return when (finishReason) {
                    "stop" -> "STOP"
                    "length" -> "MAX_TOKENS"
                    "tool_calls" -> null // Continue for tool calls
                    else -> finishReason.uppercase()
                }
            }
        }
        return "STOP"
    }
    
    /**
     * Parse Anthropic response format
     */
    private fun parseAnthropicResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
        val json = JSONObject(bodyString)
        val content = json.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val contentItem = content.getJSONObject(i)
                val type = contentItem.optString("type", "")
                when (type) {
                    "text" -> {
                        val text = contentItem.optString("text", "")
                        if (text.isNotEmpty()) {
                            onChunk(text)
                        }
                    }
                    "tool_use" -> {
                        val toolId = contentItem.optString("id", "")
                        val toolName = contentItem.optString("name", "")
                        val toolInput = contentItem.optJSONObject("input")
                        val argsMap = if (toolInput != null) {
                            jsonObjectToMap(toolInput)
                        } else {
                            emptyMap<String, Any>()
                        }
                        
                        val functionCall = FunctionCall(
                            name = toolName,
                            args = argsMap,
                            id = toolId
                        )
                        onToolCall(functionCall)
                        toolCallsToExecute.add(Triple(functionCall, ToolResult(llmContent = "", returnDisplay = ""), toolId))
                    }
                }
            }
        }
        
        val stopReason = json.optString("stop_reason", "end_turn")
        return when (stopReason) {
            "end_turn" -> "STOP"
            "max_tokens" -> "MAX_TOKENS"
            "stop_sequence" -> "STOP"
            else -> "STOP"
        }
    }
    
    /**
     * Parse Ollama response format
     */
    private fun parseOllamaResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
        val json = JSONObject(bodyString)
        val message = json.optJSONObject("message")
        if (message != null) {
            val content = message.optString("content", "")
            if (content.isNotEmpty()) {
                onChunk(content)
            }
            
            // Ollama tool calls (if supported)
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (i in 0 until toolCalls.length()) {
                    val toolCall = toolCalls.getJSONObject(i)
                    val function = toolCall.optJSONObject("function")
                    if (function != null) {
                        val functionName = function.getString("name")
                        val functionArgs = function.optString("arguments", "{}")
                        val callId = toolCall.optString("id", "")
                        
                        val argsMap = try {
                            jsonObjectToMap(JSONObject(functionArgs))
                        } catch (e: Exception) {
                            emptyMap<String, Any>()
                        }
                        
                        val functionCall = FunctionCall(
                            name = functionName,
                            args = argsMap,
                            id = callId
                        )
                        onToolCall(functionCall)
                        toolCallsToExecute.add(Triple(functionCall, ToolResult(llmContent = "", returnDisplay = ""), callId))
                    }
                }
            }
        }
        
        val done = json.optBoolean("done", false)
        return if (done) "STOP" else null
    }
    
    /**
     * Convert Gemini request format to OpenAI format
     */
    private fun convertRequestToOpenAI(geminiRequest: JSONObject, model: String): JSONObject {
        val openAIRequest = JSONObject()
        openAIRequest.put("model", model)
        openAIRequest.put("stream", false) // Non-streaming for now
        
        val messages = JSONArray()
        val contents = geminiRequest.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val content = contents.getJSONObject(i)
                val role = content.optString("role", "user")
                val parts = content.optJSONArray("parts")
                if (parts != null) {
                    val messageContent = StringBuilder()
                    val toolCalls = JSONArray()
                    
                    for (j in 0 until parts.length()) {
                        val part = parts.getJSONObject(j)
                        if (part.has("text")) {
                            messageContent.append(part.getString("text"))
                        } else if (part.has("functionCall")) {
                            // Convert function call to OpenAI format
                            val functionCall = part.getJSONObject("functionCall")
                            val toolCall = JSONObject()
                            toolCall.put("id", functionCall.optString("id", ""))
                            toolCall.put("type", "function")
                            val function = JSONObject()
                            function.put("name", functionCall.getString("name"))
                            val argsJson = functionCall.getJSONObject("args")
                            function.put("arguments", argsJson.toString())
                            toolCall.put("function", function)
                            toolCalls.put(toolCall)
                        } else if (part.has("functionResponse")) {
                            // Function response - add as assistant message with tool_calls
                            val functionResponse = part.getJSONObject("functionResponse")
                            val assistantMsg = JSONObject()
                            assistantMsg.put("role", "assistant")
                            assistantMsg.put("content", null)
                            val toolCallsArray = JSONArray()
                            val toolCall = JSONObject()
                            toolCall.put("id", functionResponse.optString("id", ""))
                            toolCall.put("type", "function")
                            val function = JSONObject()
                            function.put("name", functionResponse.getString("name"))
                            function.put("arguments", "{}")
                            toolCall.put("function", function)
                            toolCallsArray.put(toolCall)
                            assistantMsg.put("tool_calls", toolCallsArray)
                            messages.put(assistantMsg)
                            
                            // Add tool result as separate message
                            val toolMsg = JSONObject()
                            toolMsg.put("role", "tool")
                            toolMsg.put("tool_call_id", functionResponse.optString("id", ""))
                            val responseJson = functionResponse.getJSONObject("response")
                            toolMsg.put("content", responseJson.toString())
                            messages.put(toolMsg)
                            continue
                        }
                    }
                    
                    if (messageContent.isNotEmpty() || toolCalls.length() > 0) {
                        val message = JSONObject()
                        message.put("role", when (role) {
                            "model" -> "assistant"
                            else -> role
                        })
                        if (messageContent.isNotEmpty()) {
                            message.put("content", messageContent.toString())
                        }
                        if (toolCalls.length() > 0) {
                            message.put("tool_calls", toolCalls)
                        }
                        messages.put(message)
                    }
                }
            }
        }
        openAIRequest.put("messages", messages)
        
        // Convert tools
        val tools = geminiRequest.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val tool = tools.getJSONObject(0)
            val functionDeclarations = tool.optJSONArray("functionDeclarations")
            if (functionDeclarations != null) {
                val openAITools = JSONArray()
                for (i in 0 until functionDeclarations.length()) {
                    val decl = functionDeclarations.getJSONObject(i)
                    val toolObj = JSONObject()
                    toolObj.put("type", "function")
                    val function = JSONObject()
                    function.put("name", decl.getString("name"))
                    function.put("description", decl.optString("description", ""))
                    function.put("parameters", decl.getJSONObject("parameters"))
                    toolObj.put("function", function)
                    openAITools.put(toolObj)
                }
                openAIRequest.put("tools", openAITools)
            }
        }
        
        return openAIRequest
    }
    
    /**
     * Convert Gemini request format to Anthropic format
     */
    private fun convertRequestToAnthropic(geminiRequest: JSONObject, model: String): JSONObject {
        val anthropicRequest = JSONObject()
        anthropicRequest.put("model", model)
        anthropicRequest.put("max_tokens", 4096)
        
        val messages = JSONArray()
        val contents = geminiRequest.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val content = contents.getJSONObject(i)
                val role = content.optString("role", "user")
                val parts = content.optJSONArray("parts")
                if (parts != null) {
                    val message = JSONObject()
                    message.put("role", when (role) {
                        "model" -> "assistant"
                        else -> role
                    })
                    
                    val contentArray = JSONArray()
                    for (j in 0 until parts.length()) {
                        val part = parts.getJSONObject(j)
                        if (part.has("text")) {
                            val textObj = JSONObject()
                            textObj.put("type", "text")
                            textObj.put("text", part.getString("text"))
                            contentArray.put(textObj)
                        } else if (part.has("functionResponse")) {
                            // Anthropic uses tool_use blocks
                            val functionResponse = part.getJSONObject("functionResponse")
                            val toolUse = JSONObject()
                            toolUse.put("type", "tool_use")
                            toolUse.put("id", functionResponse.optString("id", ""))
                            toolUse.put("name", functionResponse.getString("name"))
                            toolUse.put("input", functionResponse.getJSONObject("response"))
                            contentArray.put(toolUse)
                        }
                    }
                    message.put("content", contentArray)
                    messages.put(message)
                }
            }
        }
        anthropicRequest.put("messages", messages)
        
        // Convert tools
        val tools = geminiRequest.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val tool = tools.getJSONObject(0)
            val functionDeclarations = tool.optJSONArray("functionDeclarations")
            if (functionDeclarations != null) {
                val anthropicTools = JSONArray()
                for (i in 0 until functionDeclarations.length()) {
                    val decl = functionDeclarations.getJSONObject(i)
                    val toolObj = JSONObject()
                    toolObj.put("name", decl.getString("name"))
                    toolObj.put("description", decl.optString("description", ""))
                    toolObj.put("input_schema", decl.getJSONObject("parameters"))
                    anthropicTools.put(toolObj)
                }
                anthropicRequest.put("tools", anthropicTools)
            }
        }
        
        return anthropicRequest
    }
    
    /**
     * Convert Gemini request format to Ollama format
     */
    private fun convertRequestToOllama(geminiRequest: JSONObject, model: String): JSONObject {
        val ollamaRequest = JSONObject()
        ollamaRequest.put("model", model)
        ollamaRequest.put("stream", false) // Non-streaming
        
        val messages = JSONArray()
        val contents = geminiRequest.optJSONArray("contents")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val content = contents.getJSONObject(i)
                val role = content.optString("role", "user")
                val parts = content.optJSONArray("parts")
                if (parts != null) {
                    val messageContent = StringBuilder()
                    for (j in 0 until parts.length()) {
                        val part = parts.getJSONObject(j)
                        if (part.has("text")) {
                            messageContent.append(part.getString("text"))
                        }
                    }
                    
                    if (messageContent.isNotEmpty()) {
                        val message = JSONObject()
                        message.put("role", when (role) {
                            "model" -> "assistant"
                            else -> role
                        })
                        message.put("content", messageContent.toString())
                        messages.put(message)
                    }
                }
            }
        }
        ollamaRequest.put("messages", messages)
        
        // Ollama tools (if supported)
        val tools = geminiRequest.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val tool = tools.getJSONObject(0)
            val functionDeclarations = tool.optJSONArray("functionDeclarations")
            if (functionDeclarations != null) {
                val ollamaTools = JSONArray()
                for (i in 0 until functionDeclarations.length()) {
                    val decl = functionDeclarations.getJSONObject(i)
                    val toolObj = JSONObject()
                    toolObj.put("type", "function")
                    val function = JSONObject()
                    function.put("name", decl.getString("name"))
                    function.put("description", decl.optString("description", ""))
                    function.put("parameters", decl.getJSONObject("parameters"))
                    toolObj.put("function", function)
                    ollamaTools.put(toolObj)
                }
                ollamaRequest.put("tools", ollamaTools)
            }
        }
        
        return ollamaRequest
    }
    
    private fun processResponse(
        json: JSONObject,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        providerType: ApiProviderType = ApiProviderType.GOOGLE
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
        DEBUG_UPGRADE,
        TEST_ONLY
    }
    
    /**
     * Detect user intent: create new project, debug/upgrade existing, or test only
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
        
        val testKeywords = listOf(
            "test", "run test", "run tests", "test api", "test endpoint", "test endpoints",
            "api test", "api testing", "test server", "test the", "testing", "test suite",
            "unit test", "integration test", "e2e test", "end to end test", "test coverage",
            "pytest", "jest", "mocha", "npm test", "test command", "execute test"
        )
        
        val messageLower = userMessage.lowercase()
        val contextLower = (userMessage + " " + memoryContext).lowercase()
        
        val debugScore = debugKeywords.count { contextLower.contains(it) }
        val createScore = createKeywords.count { contextLower.contains(it) }
        val testScore = testKeywords.count { contextLower.contains(it) }
        
        // Check if workspace has existing files
        val workspaceDir = File(workspaceRoot)
        val hasExistingFiles = workspaceDir.exists() && 
            workspaceDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        
        // Check memory for project context
        val hasProjectContext = memoryContext.contains("project", ignoreCase = true) ||
                                memoryContext.contains("codebase", ignoreCase = true) ||
                                memoryContext.contains("repository", ignoreCase = true)
        
        // PRIORITY: Check for test-only intent first (if test keywords are strong and no create keywords)
        // Test intent should be detected when:
        // 1. Strong test keywords present
        // 2. Workspace has existing files (project exists)
        // 3. No strong create keywords (not creating new project)
        // 4. May have debug keywords (fixing tests is okay)
        // 5. NOT just a simple command execution (like "npm start", "init-db", etc.)
        if (testScore > 0 && hasExistingFiles && 
            (testScore >= createScore || (testScore > 0 && createScore == 0))) {
            // Additional check: if message is primarily about testing
            val isPrimarilyTest = testScore > debugScore && testScore > createScore
            val mentionsTestCommand = messageLower.contains("npm test") || 
                                     messageLower.contains("pytest") || 
                                     messageLower.contains("jest") ||
                                     messageLower.contains("test api") ||
                                     messageLower.contains("test endpoint") ||
                                     messageLower.contains("run test") ||
                                     messageLower.contains("run tests")
            
            // Exclude simple command execution (not tests)
            val isSimpleCommand = messageLower.matches(Regex(".*@\\d+\\.\\d+.*")) || // versioned commands like "app@1.0.0 init-db"
                                 messageLower.contains("init") && !messageLower.contains("test") ||
                                 (messageLower.contains("start") || messageLower.contains("run")) && 
                                 !messageLower.contains("test") && testScore <= 1
            
            if ((isPrimarilyTest || mentionsTestCommand) && !isSimpleCommand) {
                return IntentType.TEST_ONLY
            }
        }
        
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
     * Enhance user intent with clarifying guidance to help AI understand goals, do's, and don'ts
     * Adds helpful context directly to the prompt without extra API calls
     */
    private suspend fun enhanceUserIntent(userMessage: String, intent: IntentType): String {
        val memoryContext = MemoryService.getSummarizedMemory()
        
        val intentDescription = when (intent) {
            IntentType.CREATE_NEW -> "creating a new project"
            IntentType.DEBUG_UPGRADE -> "debugging or upgrading an existing project"
            IntentType.TEST_ONLY -> "running tests and fixing issues"
        }
        
        // Add helpful guidance directly to the user message
        val enhancement = """
            === User Request ===
            $userMessage
            
            === Context & Guidance ===
            Task Type: $intentDescription
            
            Please ensure you understand:
            - **Primary Goal**: What should the end result accomplish? What is the main objective?
            - **Key Requirements**: What are the must-have features, constraints, or specifications?
            - **Best Practices**: What should be included? Follow industry standards and best practices.
            - **What to Avoid**: What are common pitfalls or anti-patterns to avoid?
            - **Success Criteria**: How will we verify the project is complete and working correctly?
            
            Be thorough, consider edge cases, and ensure the implementation is production-ready and functional.
            ${if (memoryContext.isNotEmpty()) "\nPrevious context:\n$memoryContext" else ""}
        """.trimIndent()
        
        return enhancement
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
     * Detect if task only needs commands to run (no file creation needed)
     */
    private suspend fun detectCommandsOnly(userMessage: String, workspaceRoot: String): Boolean {
        val messageLower = userMessage.lowercase()
        
        // Keywords indicating command-only tasks
        val commandOnlyKeywords = listOf(
            "run", "execute", "install", "start", "launch", "test", "build", "compile",
            "deploy", "migrate", "update", "upgrade", "setup", "configure", "init",
            "install dependencies", "run tests", "start server", "build project"
        )
        
        // Check if message is primarily about running commands
        val hasCommandKeywords = commandOnlyKeywords.any { messageLower.contains(it) }
        
        // Check if workspace has existing files (suggests command-only task)
        val workspaceDir = File(workspaceRoot)
        val hasExistingFiles = workspaceDir.exists() && 
            workspaceDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        
        // If has command keywords and existing files, likely command-only
        return hasCommandKeywords && hasExistingFiles && 
               !messageLower.contains("create") && 
               !messageLower.contains("write") && 
               !messageLower.contains("generate") &&
               !messageLower.contains("make")
    }
    
    /**
     * Detect commands needed to run based on project structure, OS, and user message
     * Uses AI to intelligently detect commands with OS-specific fallbacks
     */
    private suspend fun detectCommandsNeeded(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<CommandWithFallbacks> {
        val commands = mutableListOf<CommandWithFallbacks>()
        val workspaceDir = File(workspaceRoot)
        
        if (!workspaceDir.exists()) return commands
        
        // First, try AI-based command detection
        val aiCommands = detectCommandsWithAI(workspaceRoot, systemInfo, userMessage, emit, onChunk)
        if (aiCommands.isNotEmpty()) {
            commands.addAll(aiCommands)
        }
        
        // Also add pattern-based detection as fallback
        
        // Check for Python projects
        val hasPythonFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".py") || it.name == "requirements.txt" || it.name == "setup.py" || it.name == "pyproject.toml") }
        
        if (hasPythonFiles) {
            val mainPy = workspaceDir.walkTopDown()
                .firstOrNull { it.isFile && (it.name == "main.py" || it.name == "app.py" || it.name == "__main__.py" || it.name.endsWith("_main.py")) }
            
            // Check if venv exists
            val venvExists = File(workspaceDir, "venv").exists() || 
                           File(workspaceDir, ".venv").exists() ||
                           File(workspaceDir, "env").exists()
            
            // Check for requirements.txt
            val requirementsFile = File(workspaceDir, "requirements.txt")
            val hasRequirements = requirementsFile.exists()
            
            if (mainPy != null) {
                val pythonCmd = "python3"
                val runCommand = if (venvExists) {
                    "source venv/bin/activate && $pythonCmd ${mainPy.name}"
                } else {
                    "$pythonCmd ${mainPy.name}"
                }
                
                val fallbacks = mutableListOf<String>()
                
                // Add venv creation if needed
                if (!venvExists && hasRequirements) {
                    fallbacks.add("python3 -m venv venv || python3 -m virtualenv venv || true")
                }
                
                // Add dependency installation (with venv activation if venv exists)
                if (hasRequirements) {
                    if (venvExists) {
                        fallbacks.add("source venv/bin/activate && pip install -r requirements.txt || pip3 install -r requirements.txt")
                    } else {
                        fallbacks.add("pip3 install -r requirements.txt")
                    }
                }
                
                // Add Python/pip installation
                fallbacks.add("${systemInfo.packageManagerCommands["install"]} python3 python3-pip")
                fallbacks.add("${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} python3 python3-pip")
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = runCommand,
                    description = "Run Python application",
                    fallbacks = fallbacks,
                    checkCommand = "python3 --version",
                    installCheck = "pip3 --version"
                ))
            }
            
            // Check for requirements.txt - install dependencies
            if (hasRequirements) {
                val installCommand = if (venvExists) {
                    "source venv/bin/activate && pip install -r requirements.txt"
                } else {
                    "pip3 install -r requirements.txt"
                }
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = installCommand,
                    description = "Install Python dependencies",
                    fallbacks = listOf(
                        if (!venvExists) "python3 -m venv venv || python3 -m virtualenv venv || true" else "",
                        "${systemInfo.packageManagerCommands["install"]} python3-pip",
                        "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} python3 python3-pip"
                    ).filter { it.isNotEmpty() },
                    checkCommand = "pip3 --version",
                    installCheck = "python3 --version"
                ))
            }
        }
        
        // Check for Node.js projects
        val hasNodeFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name == "package.json" || it.name == "package-lock.json" || it.name.endsWith(".js") || it.name.endsWith(".ts")) }
        
        if (hasNodeFiles) {
            val packageJson = File(workspaceDir, "package.json")
            if (packageJson.exists()) {
                // Check for start script
                try {
                    val packageContent = packageJson.readText()
                    if (packageContent.contains("\"start\"")) {
                        commands.add(CommandWithFallbacks(
                            primaryCommand = "npm start",
                            description = "Start Node.js application",
                            fallbacks = listOf(
                                "npm install",
                                "${systemInfo.packageManagerCommands["install"]} nodejs npm",
                                "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} nodejs npm"
                            ),
                            checkCommand = "node --version",
                            installCheck = "npm --version"
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = "npm install",
                    description = "Install Node.js dependencies",
                    fallbacks = listOf(
                        "${systemInfo.packageManagerCommands["install"]} nodejs npm",
                        "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} nodejs npm"
                    ),
                    checkCommand = "npm --version",
                    installCheck = "node --version"
                ))
            }
        }
        
        // Check for shell scripts
        val hasShellScripts = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".bash")) }
        
        if (hasShellScripts) {
            val mainScript = workspaceDir.walkTopDown()
                .firstOrNull { it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".bash")) && 
                    (it.name.contains("main") || it.name.contains("run") || it.name.contains("start")) }
            
            if (mainScript != null) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "bash ${mainScript.name}",
                    description = "Run shell script",
                    fallbacks = listOf(
                        "chmod +x ${mainScript.name} && bash ${mainScript.name}",
                        "${systemInfo.packageManagerCommands["install"]} bash"
                    ),
                    checkCommand = "bash --version",
                    installCheck = null
                ))
            }
        }
        
        return commands.distinctBy { it.primaryCommand } // Remove duplicates
    }
    
    /**
     * Use AI to detect commands needed based on project structure and user intent
     */
    private suspend fun detectCommandsWithAI(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<CommandWithFallbacks> {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return emptyList()
        
        // Get list of files in workspace
        val files = workspaceDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .take(20) // Limit to first 20 files
            .map { it.name }
            .toList()
        
        val systemContext = SystemInfoService.generateSystemContext()
        val packageManager = systemInfo.packageManager
        val installCmd = systemInfo.packageManagerCommands["install"] ?: "install"
        val updateCmd = systemInfo.packageManagerCommands["update"] ?: "update"
        
        val commandDetectionPrompt = """
            $systemContext
            
            Analyze the project structure and user request to determine what commands need to be executed.
            
            Files in workspace:
            ${files.joinToString("\n") { "- $it" }}
            
            User request: $userMessage
            
            Based on the files and user request, determine:
            1. What is the primary command to run? (e.g., "python3 app.py", "npm start", "node server.js")
            2. What dependencies need to be installed first?
            3. What fallback commands are needed if tools are missing?
            4. Does the project need a virtual environment (venv)? Check for requirements.txt, pyproject.toml, or virtualenv indicators
            5. What if the language/runtime is not installed? (e.g., Python, Node.js, etc.)
            
            For each command needed, provide:
            - primary_command: The main command to execute
            - description: What this command does
            - check_command: Command to check if tool is available (e.g., "python3 --version")
            - fallback_commands: List of commands to run if tool is missing (in order of preference)
              - First: Check and create virtual environment if needed (e.g., "python3 -m venv venv" or check if venv exists)
              - Second: Install dependencies (e.g., "pip3 install -r requirements.txt" or "source venv/bin/activate && pip install -r requirements.txt")
              - Third: Install tool via package manager (e.g., "$installCmd python3 python3-pip")
              - Fourth: Update package manager and install (e.g., "$updateCmd && $installCmd python3 python3-pip")
              - Fifth: For Python projects, check if venv needs activation: "source venv/bin/activate" or ". venv/bin/activate"
            
            IMPORTANT: Handle all these cases in one comprehensive response:
            - Language not installed (Python, Node.js, etc.)
            - Virtual environment needed but not created
            - Virtual environment exists but not activated
            - Dependencies not installed
            - Package manager needs update
            
            Format as JSON array:
            [
              {
                "primary_command": "python3 app.py",
                "description": "Run Python application",
                "check_command": "python3 --version",
                "fallback_commands": [
                  "python3 -m venv venv || true",
                  "source venv/bin/activate && pip install -r requirements.txt || pip3 install -r requirements.txt",
                  "$installCmd python3 python3-pip",
                  "$updateCmd && $installCmd python3 python3-pip"
                ]
              }
            ]
            
            Only include commands that are actually needed based on the files and user request.
            If no commands are needed, return an empty array [].
        """.trimIndent()
        
        val model = ApiProviderManager.getCurrentModel()
        val request = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", commandDetectionPrompt)
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
        
        return try {
            val response = makeApiCallSimple(
                ApiProviderManager.getNextApiKey() ?: return emptyList(),
                model,
                request,
                useLongTimeout = false
            )
            
            // Parse JSON response
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonArray = JSONArray(response.substring(jsonStart, jsonEnd))
                val detectedCommands = mutableListOf<CommandWithFallbacks>()
                
                for (i in 0 until jsonArray.length()) {
                    try {
                        val cmdObj = jsonArray.getJSONObject(i)
                        val primaryCmd = cmdObj.getString("primary_command")
                        val description = cmdObj.optString("description", "Execute command")
                        val checkCmd = cmdObj.optString("check_command", null)
                        val fallbacks = cmdObj.optJSONArray("fallback_commands")?.let { array ->
                            (0 until array.length()).mapNotNull { array.optString(it, null) }
                        } ?: emptyList()
                        
                        detectedCommands.add(CommandWithFallbacks(
                            primaryCommand = primaryCmd,
                            description = description,
                            fallbacks = fallbacks,
                            checkCommand = checkCmd.takeIf { it.isNotBlank() },
                            installCheck = null
                        ))
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Failed to parse command: ${e.message}")
                    }
                }
                
                detectedCommands
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "AI command detection failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Detect if project is a web framework (Node.js, Python Flask/FastAPI, etc.)
     */
    private fun detectWebFramework(workspaceRoot: String): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        // Check for Node.js frameworks
        val hasNodeFramework = workspaceDir.walkTopDown().any { file ->
            file.isFile && (
                file.name == "package.json" && file.readText().contains(Regex("express|koa|fastify|nest|hapi|restify|sails", RegexOption.IGNORE_CASE)) ||
                file.name.endsWith(".js") && file.readText().contains(Regex("express|app\\.listen|server\\.listen|fastify|koa", RegexOption.IGNORE_CASE))
            )
        }
        
        // Check for Python web frameworks
        val hasPythonFramework = workspaceDir.walkTopDown().any { file ->
            file.isFile && (
                (file.name.endsWith(".py") && file.readText().contains(Regex("flask|fastapi|django|bottle|tornado|sanic", RegexOption.IGNORE_CASE))) ||
                file.name == "requirements.txt" && file.readText().contains(Regex("flask|fastapi|django|bottle|tornado|sanic", RegexOption.IGNORE_CASE))
            )
        }
        
        // Check for other frameworks
        val hasOtherFramework = workspaceDir.walkTopDown().any { file ->
            file.isFile && (
                file.name.endsWith(".php") && file.readText().contains(Regex("laravel|symfony|codeigniter", RegexOption.IGNORE_CASE)) ||
                file.name.endsWith(".rb") && file.readText().contains(Regex("rails|sinatra", RegexOption.IGNORE_CASE)) ||
                file.name.endsWith(".go") && file.readText().contains(Regex("gin|echo|fiber|gorilla", RegexOption.IGNORE_CASE))
            )
        }
        
        return hasNodeFramework || hasPythonFramework || hasOtherFramework
    }
    
    /**
     * Detect if project is Kotlin or Java (skip API testing)
     */
    private fun detectKotlinJava(workspaceRoot: String): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        return workspaceDir.walkTopDown().any { file ->
            file.isFile && (file.name.endsWith(".kt") || file.name.endsWith(".java") || 
                file.name == "build.gradle" || file.name == "build.gradle.kts" || 
                file.name == "pom.xml" || file.name == "build.sbt")
        }
    }
    
    /**
     * Test APIs: Start server, detect endpoints, and test them
     */
    private suspend fun testAPIs(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        // Step 1: Detect framework and server start command
        val serverInfo = detectServerInfo(workspaceRoot, systemInfo, userMessage, emit, onChunk)
        if (serverInfo == null) {
            emit(GeminiStreamEvent.Chunk("⚠️ Could not detect server information\n"))
            onChunk("⚠️ Could not detect server information\n")
            return false
        }
        
        emit(GeminiStreamEvent.Chunk("🚀 Starting server: ${serverInfo.startCommand}\n"))
        onChunk("🚀 Starting server: ${serverInfo.startCommand}\n")
        
        // Step 2: Start server in background with debugging
        var serverProcess = startServer(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
        if (serverProcess == null) {
            // Try to debug why server didn't start
            emit(GeminiStreamEvent.Chunk("🔍 Debugging server startup failure...\n"))
            onChunk("🔍 Debugging server startup failure...\n")
            
            val debugResult = debugServerStartup(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
            if (!debugResult) {
                emit(GeminiStreamEvent.Chunk("❌ Failed to start server after debugging\n"))
                onChunk("❌ Failed to start server after debugging\n")
                return false
            }
            
            // Try starting again after debugging
            serverProcess = startServer(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
            if (serverProcess == null) {
                emit(GeminiStreamEvent.Chunk("❌ Still failed to start server after debugging\n"))
                onChunk("❌ Still failed to start server after debugging\n")
                return false
            }
        }
        
        // Step 3: Wait for server to be ready
        var serverReady = waitForServerReady(serverInfo.baseUrl, serverInfo.port, emit, onChunk)
        if (!serverReady) {
            // Try to debug why server isn't responding
            emit(GeminiStreamEvent.Chunk("🔍 Server not responding, checking logs...\n"))
            onChunk("🔍 Server not responding, checking logs...\n")
            
            // Check if server process is still running
            val processAlive = serverProcess?.isAlive ?: false
            if (!processAlive) {
                emit(GeminiStreamEvent.Chunk("❌ Server process died. Trying to restart with fixes...\n"))
                onChunk("❌ Server process died. Trying to restart with fixes...\n")
                
                val restartResult = debugServerStartup(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
                if (!restartResult) {
                    return false
                }
                
                // Try starting again
                serverProcess = startServer(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
                if (serverProcess == null) {
                    return false
                }
                
                // Wait again after restart
                serverReady = waitForServerReady(serverInfo.baseUrl, serverInfo.port, emit, onChunk)
                if (!serverReady) {
                    emit(GeminiStreamEvent.Chunk("❌ Server did not become ready after restart\n"))
                    onChunk("❌ Server did not become ready after restart\n")
                    return false
                }
            } else {
                emit(GeminiStreamEvent.Chunk("❌ Server process is running but not responding\n"))
                onChunk("❌ Server process is running but not responding\n")
                return false
            }
        }
        
        emit(GeminiStreamEvent.Chunk("✅ Server is ready at ${serverInfo.baseUrl}\n"))
        onChunk("✅ Server is ready at ${serverInfo.baseUrl}\n")
        
        // Step 4: Detect API endpoints using AI
        val endpoints = detectAPIEndpoints(workspaceRoot, serverInfo, userMessage, emit, onChunk)
        if (endpoints.isEmpty()) {
            emit(GeminiStreamEvent.Chunk("ℹ️ No testable endpoints detected\n"))
            onChunk("ℹ️ No testable endpoints detected\n")
            return true
        }
        
        emit(GeminiStreamEvent.Chunk("📋 Found ${endpoints.size} endpoint(s) to test\n"))
        onChunk("📋 Found ${endpoints.size} endpoint(s) to test\n")
        
        // Step 5: Test each endpoint
        var successCount = 0
        for (endpoint in endpoints) {
            val success = testEndpoint(endpoint, serverInfo.baseUrl, emit, onChunk)
            if (success) successCount++
        }
        
        emit(GeminiStreamEvent.Chunk("\n📊 Test Results: $successCount/${endpoints.size} endpoints passed\n"))
        onChunk("\n📊 Test Results: $successCount/${endpoints.size} endpoints passed\n")
        
        return successCount > 0
    }
    
    /**
     * Server information
     */
    private data class ServerInfo(
        val framework: String,
        val startCommand: String,
        val baseUrl: String,
        val port: Int,
        val healthCheckPath: String? = null
    )
    
    /**
     * API endpoint to test
     */
    private data class APIEndpoint(
        val path: String,
        val method: String, // GET, POST, PUT, DELETE, etc.
        val description: String,
        val requestBody: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val expectedStatus: Int = 200
    )
    
    /**
     * Detect server information (framework, start command, port, base URL)
     */
    private suspend fun detectServerInfo(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): ServerInfo? {
        val workspaceDir = File(workspaceRoot)
        
        // Try AI-based detection first
        val aiServerInfo = detectServerInfoWithAI(workspaceRoot, systemInfo, userMessage, emit, onChunk)
        if (aiServerInfo != null) return aiServerInfo
        
        // Fallback to pattern-based detection
        // Node.js
        val packageJson = File(workspaceDir, "package.json")
        if (packageJson.exists()) {
            try {
                val content = packageJson.readText()
                if (content.contains("express") || content.contains("koa") || content.contains("fastify")) {
                    // Check for start script
                    val startScript = if (content.contains("\"start\"")) "npm start" else "node server.js"
                    return ServerInfo(
                        framework = "Node.js",
                        startCommand = startScript,
                        baseUrl = "http://localhost:3000",
                        port = 3000,
                        healthCheckPath = "/"
                    )
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        // Python Flask/FastAPI
        val hasFlask = workspaceDir.walkTopDown().any { 
            it.isFile && it.name.endsWith(".py") && it.readText().contains("flask", ignoreCase = true)
        }
        val hasFastAPI = workspaceDir.walkTopDown().any { 
            it.isFile && it.name.endsWith(".py") && it.readText().contains("fastapi", ignoreCase = true)
        }
        
        if (hasFlask) {
            return ServerInfo(
                framework = "Flask",
                startCommand = "python3 app.py",
                baseUrl = "http://localhost:5000",
                port = 5000,
                healthCheckPath = "/"
            )
        }
        
        if (hasFastAPI) {
            return ServerInfo(
                framework = "FastAPI",
                startCommand = "uvicorn main:app --reload",
                baseUrl = "http://localhost:8000",
                port = 8000,
                healthCheckPath = "/docs"
            )
        }
        
        return null
    }
    
    /**
     * Use AI to detect server information
     */
    private suspend fun detectServerInfoWithAI(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): ServerInfo? {
        val workspaceDir = File(workspaceRoot)
        val files = workspaceDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .take(15)
            .map { "${it.name} (${it.length()} bytes)" }
            .toList()
        
        val prompt = """
            Analyze the project structure and determine server information.
            
            Files in workspace:
            ${files.joinToString("\n") { "- $it" }}
            
            User request: $userMessage
            
            Determine:
            1. Framework type (Node.js/Express, Python/Flask, Python/FastAPI, etc.)
            2. Command to start the server (e.g., "npm start", "python3 app.py", "uvicorn main:app")
            3. Default port (e.g., 3000 for Node.js, 5000 for Flask, 8000 for FastAPI)
            4. Base URL (e.g., "http://localhost:3000")
            5. Health check path (e.g., "/" or "/health" or "/api/health")
            
            Return JSON:
            {
              "framework": "Node.js/Express",
              "start_command": "npm start",
              "port": 3000,
              "base_url": "http://localhost:3000",
              "health_check_path": "/"
            }
        """.trimIndent()
        
        val model = ApiProviderManager.getCurrentModel()
        val request = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
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
        
        return try {
            val response = makeApiCallSimple(
                ApiProviderManager.getNextApiKey() ?: return null,
                model,
                request,
                useLongTimeout = false
            )
            
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = JSONObject(response.substring(jsonStart, jsonEnd))
                ServerInfo(
                    framework = json.optString("framework", "Unknown"),
                    startCommand = json.getString("start_command"),
                    baseUrl = json.optString("base_url", "http://localhost:${json.optInt("port", 3000)}"),
                    port = json.optInt("port", 3000),
                    healthCheckPath = json.optString("health_check_path", null)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "AI server detection failed: ${e.message}")
            null
        }
    }
    
    /**
     * Debug server startup issues and try to fix them
     */
    private suspend fun debugServerStartup(
        serverInfo: ServerInfo,
        workspaceRoot: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        
        // Check for common issues
        emit(GeminiStreamEvent.Chunk("🔍 Checking for common issues...\n"))
        onChunk("🔍 Checking for common issues...\n")
        
        // Check if dependencies are installed
        if (serverInfo.framework.contains("Node.js")) {
            val packageJson = File(workspaceDir, "package.json")
            if (packageJson.exists()) {
                val nodeModules = File(workspaceDir, "node_modules")
                if (!nodeModules.exists() || nodeModules.listFiles()?.isEmpty() == true) {
                    emit(GeminiStreamEvent.Chunk("📦 Installing Node.js dependencies...\n"))
                    onChunk("📦 Installing Node.js dependencies...\n")
                    
                    val installCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to "npm install",
                            "description" to "Install Node.js dependencies",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(installCall))
                    onToolCall(installCall)
                    
                    try {
                        val installResult = executeToolSync("shell", installCall.args)
                        emit(GeminiStreamEvent.ToolResult("shell", installResult))
                        onToolResult("shell", installCall.args)
                        
                        if (installResult.error != null) {
                            emit(GeminiStreamEvent.Chunk("⚠️ Dependency installation had issues\n"))
                            onChunk("⚠️ Dependency installation had issues\n")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Dependency install failed: ${e.message}")
                    }
                }
            }
        }
        
        if (serverInfo.framework.contains("Flask") || serverInfo.framework.contains("FastAPI")) {
            val requirementsFile = File(workspaceDir, "requirements.txt")
            if (requirementsFile.exists()) {
                emit(GeminiStreamEvent.Chunk("📦 Installing Python dependencies...\n"))
                onChunk("📦 Installing Python dependencies...\n")
                
                val installCall = FunctionCall(
                    name = "shell",
                    args = mapOf(
                        "command" to "pip3 install -r requirements.txt",
                        "description" to "Install Python dependencies",
                        "dir_path" to workspaceRoot
                    )
                )
                emit(GeminiStreamEvent.ToolCall(installCall))
                onToolCall(installCall)
                
                try {
                    val installResult = executeToolSync("shell", installCall.args)
                    emit(GeminiStreamEvent.ToolResult("shell", installResult))
                    onToolResult("shell", installCall.args)
                } catch (e: Exception) {
                    android.util.Log.w("GeminiClient", "Dependency install failed: ${e.message}")
                }
            }
        }
        
        // Try to start server again
        emit(GeminiStreamEvent.Chunk("🔄 Retrying server startup...\n"))
        onChunk("🔄 Retrying server startup...\n")
        
        val serverProcess = startServer(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
        return serverProcess != null
    }
    
    /**
     * Start server in background
     */
    private suspend fun startServer(
        serverInfo: ServerInfo,
        workspaceRoot: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Process? {
        // Use shell tool to start server
        val startCall = FunctionCall(
            name = "shell",
            args = mapOf(
                "command" to serverInfo.startCommand,
                "description" to "Start ${serverInfo.framework} server",
                "dir_path" to workspaceRoot
            )
        )
        
        emit(GeminiStreamEvent.ToolCall(startCall))
        onToolCall(startCall)
        
        // Start server in background (non-blocking)
        return try {
            val workingDir = File(workspaceRoot)
            val processBuilder = ProcessBuilder()
                .command("sh", "-c", serverInfo.startCommand)
                .directory(workingDir)
                .redirectErrorStream(true)
            
            val env = processBuilder.environment()
            env["PATH"] = "/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/system/bin:/system/xbin:${env["PATH"] ?: ""}"
            env["HOME"] = env["HOME"] ?: "/root"
            env["TERM"] = "xterm-256color"
            
            val process = processBuilder.start()
            
            // Give it a moment to start
            kotlinx.coroutines.delay(2000)
            
            if (process.isAlive) {
                emit(GeminiStreamEvent.Chunk("✅ Server process started\n"))
                onChunk("✅ Server process started\n")
                process
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to start server", e)
            emit(GeminiStreamEvent.Chunk("❌ Failed to start server: ${e.message}\n"))
            onChunk("❌ Failed to start server: ${e.message}\n")
            null
        }
    }
    
    /**
     * Wait for server to be ready
     */
    private suspend fun waitForServerReady(
        baseUrl: String,
        port: Int,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit,
        maxWaitSeconds: Int = 30
    ): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        
        val healthPaths = listOf("/", "/health", "/api/health", "/status")
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < maxWaitSeconds * 1000L) {
            for (path in healthPaths) {
                try {
                    val url = "$baseUrl$path"
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful || response.code in 200..499) {
                        emit(GeminiStreamEvent.Chunk("✅ Server responded at $url\n"))
                        onChunk("✅ Server responded at $url\n")
                        return true
                    }
                    response.close()
                } catch (e: Exception) {
                    // Continue trying
                }
            }
            
            kotlinx.coroutines.delay(1000)
            emit(GeminiStreamEvent.Chunk("⏳ Waiting for server...\n"))
            onChunk("⏳ Waiting for server...\n")
        }
        
        return false
    }
    
    /**
     * Detect API endpoints using AI
     */
    private suspend fun detectAPIEndpoints(
        workspaceRoot: String,
        serverInfo: ServerInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<APIEndpoint> {
        val workspaceDir = File(workspaceRoot)
        val routeFiles = workspaceDir.walkTopDown()
            .filter { it.isFile && (
                it.name.endsWith(".js") || it.name.endsWith(".ts") || 
                it.name.endsWith(".py") || it.name.contains("route") || 
                it.name.contains("api") || it.name.contains("controller")
            )}
            .take(10)
            .map { "${it.name}: ${it.readText().take(500)}" }
            .toList()
        
        val prompt = """
            Analyze the project to detect API endpoints that can be tested.
            
            Framework: ${serverInfo.framework}
            Base URL: ${serverInfo.baseUrl}
            
            Route files:
            ${routeFiles.joinToString("\n\n") { "---\n$it" }}
            
            User request: $userMessage
            
            Detect testable API endpoints. For each endpoint, provide:
            - path: The endpoint path (e.g., "/api/login", "/posts", "/users/:id")
            - method: HTTP method (GET, POST, PUT, DELETE, PATCH)
            - description: What this endpoint does
            - request_body: JSON body for POST/PUT requests (if needed)
            - headers: Required headers (e.g., {"Content-Type": "application/json"})
            - expected_status: Expected HTTP status code (200, 201, etc.)
            
            Focus on common CRUD operations:
            - Login/Authentication endpoints
            - Create operations (POST)
            - Read operations (GET)
            - Update operations (PUT/PATCH)
            - Delete operations (DELETE)
            
            Return JSON array:
            [
              {
                "path": "/api/login",
                "method": "POST",
                "description": "User login",
                "request_body": "{\"username\":\"test\",\"password\":\"test\"}",
                "headers": {"Content-Type": "application/json"},
                "expected_status": 200
              },
              {
                "path": "/api/posts",
                "method": "GET",
                "description": "Get all posts",
                "request_body": null,
                "headers": {},
                "expected_status": 200
              }
            ]
            
            Only include endpoints that are actually testable (not requiring complex setup).
            Limit to 5-10 most important endpoints.
        """.trimIndent()
        
        val model = ApiProviderManager.getCurrentModel()
        val request = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
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
        
        return try {
            val response = makeApiCallSimple(
                ApiProviderManager.getNextApiKey() ?: return emptyList(),
                model,
                request,
                useLongTimeout = false
            )
            
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonArray = JSONArray(response.substring(jsonStart, jsonEnd))
                val endpoints = mutableListOf<APIEndpoint>()
                
                for (i in 0 until jsonArray.length()) {
                    try {
                        val epObj = jsonArray.getJSONObject(i)
                        val headersObj = epObj.optJSONObject("headers")
                        val headers = if (headersObj != null) {
                            (0 until headersObj.length()).associate {
                                headersObj.names().getString(it) to headersObj.getString(headersObj.names().getString(it))
                            }
                        } else {
                            emptyMap()
                        }
                        
                        endpoints.add(APIEndpoint(
                            path = epObj.getString("path"),
                            method = epObj.getString("method"),
                            description = epObj.optString("description", "API endpoint"),
                            requestBody = epObj.optString("request_body", null),
                            headers = headers,
                            expectedStatus = epObj.optInt("expected_status", 200)
                        ))
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Failed to parse endpoint: ${e.message}")
                    }
                }
                
                endpoints
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "AI endpoint detection failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Extract error message from user message
     */
    private fun extractErrorMessageFromUserMessage(userMessage: String): String {
        // Look for error patterns in the user message
        val errorPatterns = listOf(
            Regex("""Error:.*?(?=\n|$)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""Error:.*?at.*?\([^)]+\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""got a \[object Undefined\].*?at.*?\(([^:]+):(\d+):(\d+)\)"""),
            Regex("""Route\.(post|get|put|delete)\(\) requires.*?but got.*?\[object Undefined\]"""),
            Regex("""Cannot find module.*?at.*?\(([^:]+):(\d+):(\d+)\)"""),
            Regex("""TypeError:.*?at.*?\(([^:]+):(\d+):(\d+)\)"""),
            Regex("""ReferenceError:.*?at.*?\(([^:]+):(\d+):(\d+)\)""")
        )
        
        for (pattern in errorPatterns) {
            val match = pattern.find(userMessage)
            if (match != null) {
                return match.value
            }
        }
        
        // If no pattern matches, return the full message (might contain error)
        return userMessage
    }
    
    /**
     * Extract fixes from text when JSON parsing fails
     */
    private fun extractFixesFromText(
        text: String,
        workspaceRoot: String
    ): List<Triple<String, String, String>> {
        val fixes = mutableListOf<Triple<String, String, String>>()
        
        // Look for file_path, old_string, new_string patterns
        val filePathRegex = Regex("""["']file_path["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val oldStringRegex = Regex("""["']old_string["']\s*:\s*["']([^"']*(?:\n[^"']*)*)["']""", RegexOption.IGNORE_CASE)
        val newStringRegex = Regex("""["']new_string["']\s*:\s*["']([^"']*(?:\n[^"']*)*)["']""", RegexOption.IGNORE_CASE)
        
        // Try to find code blocks with file paths
        val codeBlockRegex = Regex("""```(?:json|javascript|js)?\s*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
        val codeBlocks = codeBlockRegex.findAll(text)
        
        for (block in codeBlocks) {
            val blockText = block.groupValues[1]
            val filePathMatch = filePathRegex.find(blockText)
            val oldStringMatch = oldStringRegex.find(blockText)
            val newStringMatch = newStringRegex.find(blockText)
            
            if (filePathMatch != null && oldStringMatch != null && newStringMatch != null) {
                val filePath = filePathMatch.groupValues[1]
                val oldString = oldStringMatch.groupValues[1].replace("\\n", "\n")
                val newString = newStringMatch.groupValues[1].replace("\\n", "\n")
                
                fixes.add(Triple(filePath, oldString, newString))
            }
        }
        
        return fixes
    }
    
    /**
     * Find a better anchor string in file content when old_string doesn't match
     */
    private fun findBetterAnchor(
        fileContent: String,
        originalOldString: String,
        newString: String
    ): String? {
        // Extract key words from old_string
        val keywords = originalOldString.split(Regex("\\s+"))
            .filter { it.length > 3 && !it.matches(Regex("""^[{}();,\[\]]+$""")) }
            .take(3)
        
        if (keywords.isEmpty()) return null
        
        // Try to find lines containing these keywords
        val lines = fileContent.lines()
        for (line in lines) {
            if (keywords.all { line.contains(it, ignoreCase = true) }) {
                // Found a line with all keywords, use it as anchor
                return line
            }
        }
        
        // Try to find a line with at least one keyword near the end (for appending)
        for (i in lines.size - 1 downTo maxOf(0, lines.size - 20)) {
            val line = lines[i]
            if (keywords.any { line.contains(it, ignoreCase = true) }) {
                return line
            }
        }
        
        return null
    }
    
    /**
     * Analyze error message and suggest a fix
     */
    private fun analyzeErrorAndSuggestFix(
        errorMessage: String,
        workspaceRoot: String
    ): Triple<String, String, String>? {
        // Check for undefined controller function errors
        val undefinedRegex = Regex("""got a \[object Undefined\].*?at.*?\(([^:]+):(\d+):(\d+)\)""")
        val undefinedMatch = undefinedRegex.find(errorMessage)
        
        if (undefinedMatch != null) {
            val filePath = undefinedMatch.groupValues[1]
            val lineNum = undefinedMatch.groupValues[2].toIntOrNull() ?: return null
            
            // Read the file to see what's missing
            val file = File(workspaceRoot, filePath)
            if (file.exists()) {
                val lines = file.readLines()
                if (lineNum <= lines.size) {
                    val line = lines[lineNum - 1]
                    
                    // Check for controller function calls
                    val controllerCallRegex = Regex("""(\w+Controller)\.(\w+)""")
                    val controllerMatch = controllerCallRegex.find(line)
                    
                    if (controllerMatch != null) {
                        val controllerName = controllerMatch.groupValues[1]
                        val functionName = controllerMatch.groupValues[2]
                        
                        // Find the controller file
                        val controllerFile = File(workspaceRoot, "controllers/${controllerName.replace("Controller", "").lowercase()}Controller.js")
                        if (controllerFile.exists()) {
                            val controllerContent = controllerFile.readText()
                            
                            // Check if function exists
                            if (!controllerContent.contains("exports.$functionName") && 
                                !controllerContent.contains("$functionName =")) {
                                
                                // Generate a basic function
                                val functionNameFormatted = functionName.replace(Regex("([A-Z])"), " $1").trim()
                                val functionCode = """
// GET /admin/$functionName - Display form for $functionName
exports.$functionName = (req, res, next) => {
    res.render('admin/$functionName', {
        pageTitle: '$functionNameFormatted',
        path: '/admin/$functionName'
    });
};
"""
                                
                                // Find a good place to insert (before POST handler if exists)
                                val postHandlerRegex = Regex("""// POST.*?$functionName""")
                                val postMatch = postHandlerRegex.find(controllerContent)
                                
                                if (postMatch != null) {
                                    val insertPos = postMatch.range.first
                                    val beforePost = controllerContent.substring(0, insertPos)
                                    val afterPost = controllerContent.substring(insertPos)
                                    
                                    return Triple(
                                        controllerFile.relativeTo(File(workspaceRoot)).path,
                                        beforePost.takeLast(50) + afterPost.take(50),
                                        beforePost + functionCode + "\n" + afterPost
                                    )
                                } else {
                                    // Append at the end
                                    return Triple(
                                        controllerFile.relativeTo(File(workspaceRoot)).path,
                                        controllerContent.takeLast(20),
                                        controllerContent + "\n\n" + functionCode
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Analyze linter errors to identify problematic files and code sections
     */
    private fun analyzeLinterError(
        error: Exception,
        filePath: String,
        workspaceRoot: String
    ): String {
        val analysis = StringBuilder()
        
        analysis.appendLine("=== Error Analysis ===")
        analysis.appendLine("File: $filePath")
        analysis.appendLine("Error Type: ${error.javaClass.simpleName}")
        analysis.appendLine("Error Message: ${error.message}")
        
        // Check for permission issues
        if (error.message?.contains("Permission denied") == true) {
            analysis.appendLine("\n⚠️ Permission Denied Detected")
            analysis.appendLine("Root Cause: Command execution failed due to permissions")
            analysis.appendLine("Solution: Commands should run through ShellTool (rootfs environment)")
            analysis.appendLine("Status: Fixed - linter now uses ShellTool")
        }
        
        // Check for command not found
        if (error.message?.contains("Cannot run program") == true) {
            analysis.appendLine("\n⚠️ Command Not Found")
            val commandMatch = Regex("""Cannot run program "([^"]+)"""").find(error.message ?: "")
            if (commandMatch != null) {
                val command = commandMatch.groupValues[1]
                analysis.appendLine("Missing Command: $command")
                analysis.appendLine("Possible Solutions:")
                analysis.appendLine("  1. Install the tool in the rootfs environment")
                analysis.appendLine("  2. Use alternative linter (fallback to basic detection)")
                analysis.appendLine("  3. Check if command is in PATH")
            }
        }
        
        // Analyze the file for potential issues
        val file = File(workspaceRoot, filePath)
        if (file.exists() && file.isFile) {
            try {
                val content = file.readText()
                val lines = content.lines()
                
                analysis.appendLine("\n📄 File Analysis:")
                analysis.appendLine("  Lines: ${lines.size}")
                analysis.appendLine("  Size: ${file.length()} bytes")
                
                // Check for common issues in the file
                val issues = mutableListOf<String>()
                
                // Check for syntax issues
                lines.forEachIndexed { index, line ->
                    val lineNum = index + 1
                    
                    // Check for unclosed brackets (basic)
                    val openBraces = line.count { it == '{' }
                    val closeBraces = line.count { it == '}' }
                    if (openBraces != closeBraces && lineNum <= 50) { // Only check first 50 lines
                        issues.add("Line $lineNum: Possible unclosed braces (${openBraces} open, ${closeBraces} close)")
                    }
                    
                    // Check for common JavaScript issues
                    if (filePath.endsWith(".js")) {
                        if (line.contains("require(") && !line.contains("const") && !line.contains("let") && !line.contains("var")) {
                            issues.add("Line $lineNum: require() not assigned to variable")
                        }
                    }
                }
                
                if (issues.isNotEmpty()) {
                    analysis.appendLine("\n⚠️ Potential Issues Found:")
                    issues.take(5).forEach { issue ->
                        analysis.appendLine("  - $issue")
                    }
                    if (issues.size > 5) {
                        analysis.appendLine("  ... and ${issues.size - 5} more")
                    }
                } else {
                    analysis.appendLine("  ✓ No obvious syntax issues detected")
                }
            } catch (e: Exception) {
                analysis.appendLine("  ⚠️ Could not read file: ${e.message}")
            }
        } else {
            analysis.appendLine("\n⚠️ File not found or not accessible")
        }
        
        // Suggest fixes
        analysis.appendLine("\n💡 Suggested Actions:")
        if (error.message?.contains("Permission denied") == true) {
            analysis.appendLine("  1. ✓ Fixed: Linter now uses ShellTool")
            analysis.appendLine("  2. Check if file has correct permissions")
            analysis.appendLine("  3. Verify rootfs environment is properly initialized")
        } else if (error.message?.contains("Cannot run program") == true) {
            analysis.appendLine("  1. Install missing tools in rootfs (e.g., node, python3)")
            analysis.appendLine("  2. Use basic error detection (fallback)")
            analysis.appendLine("  3. Check PATH environment variable")
        } else {
            analysis.appendLine("  1. Review error message above")
            analysis.appendLine("  2. Check file syntax manually")
            analysis.appendLine("  3. Use syntax_fix tool to attempt automatic fixes")
        }
        
        return analysis.toString()
    }
    
    /**
     * Test a single API endpoint
     */
    private suspend fun testEndpoint(
        endpoint: APIEndpoint,
        baseUrl: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): Boolean {
        val url = "$baseUrl${endpoint.path}"
        emit(GeminiStreamEvent.Chunk("🧪 Testing ${endpoint.method} $url - ${endpoint.description}\n"))
        onChunk("🧪 Testing ${endpoint.method} $url - ${endpoint.description}\n")
        
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        
        return try {
            val requestBuilder = Request.Builder().url(url)
            
            // Add headers
            val headersBuilder = Headers.Builder()
            headersBuilder.add("Content-Type", "application/json")
            endpoint.headers.forEach { (key, value) ->
                headersBuilder.add(key, value)
            }
            requestBuilder.headers(headersBuilder.build())
            
            // Add request body for POST/PUT/PATCH
            if (endpoint.requestBody != null && endpoint.method in listOf("POST", "PUT", "PATCH")) {
                val body = RequestBody.create("application/json".toMediaType(), endpoint.requestBody)
                when (endpoint.method) {
                    "POST" -> requestBuilder.post(body)
                    "PUT" -> requestBuilder.put(body)
                    "PATCH" -> requestBuilder.patch(body)
                    else -> requestBuilder.get()
                }
            } else {
                requestBuilder.method(endpoint.method, null)
            }
            
            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            
            val success = response.code == endpoint.expectedStatus || 
                         (response.code in 200..299 && endpoint.expectedStatus in 200..299)
            
            if (success) {
                emit(GeminiStreamEvent.Chunk("✅ ${endpoint.method} $url - Status: ${response.code}\n"))
                onChunk("✅ ${endpoint.method} $url - Status: ${response.code}\n")
            } else {
                emit(GeminiStreamEvent.Chunk("❌ ${endpoint.method} $url - Expected ${endpoint.expectedStatus}, got ${response.code}\n"))
                onChunk("❌ ${endpoint.method} $url - Expected ${endpoint.expectedStatus}, got ${response.code}\n")
            }
            
            response.close()
            success
        } catch (e: Exception) {
            emit(GeminiStreamEvent.Chunk("❌ ${endpoint.method} $url - Error: ${e.message}\n"))
            onChunk("❌ ${endpoint.method} $url - Error: ${e.message}\n")
            false
        }
    }
    
    /**
     * Data class for commands with fallbacks
     */
    private data class CommandWithFallbacks(
        val primaryCommand: String,
        val description: String,
        val fallbacks: List<String>,
        val checkCommand: String? = null, // Command to check if tool is installed
        val installCheck: String? = null // Command to check if installer is available
    )
    
    /**
     * Execute command with fallbacks if it fails
     * Handles all cases: missing language, venv, dependencies, etc.
     */
    private suspend fun executeCommandWithFallbacks(
        command: CommandWithFallbacks,
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val runningMsg = "▶️ Running: ${command.primaryCommand}\n"
        emit(GeminiStreamEvent.Chunk(runningMsg))
        onChunk(runningMsg)
        
        // Check if command/tool is available
        if (command.checkCommand != null) {
            val checkCall = FunctionCall(
                name = "shell",
                args = mapOf(
                    "command" to command.checkCommand!!,
                    "description" to "Check if ${command.description} tool is available"
                )
            )
            emit(GeminiStreamEvent.ToolCall(checkCall))
            onToolCall(checkCall)
            
            try {
                val checkResult = executeToolSync("shell", checkCall.args)
                emit(GeminiStreamEvent.ToolResult("shell", checkResult))
                onToolResult("shell", checkCall.args)
                
                if (checkResult.error != null) {
                    // Tool not available, try fallbacks in order
                    val fallbackMsg = "⚠️ Tool not found, trying fallbacks...\n"
                    emit(GeminiStreamEvent.Chunk(fallbackMsg))
                    onChunk(fallbackMsg)
                    
                    var fallbackSuccess = false
                    for ((index, fallback) in command.fallbacks.withIndex()) {
                        val fallbackStepMsg = "📦 Fallback ${index + 1}/${command.fallbacks.size}: $fallback\n"
                        emit(GeminiStreamEvent.Chunk(fallbackStepMsg))
                        onChunk(fallbackStepMsg)
                        
                        val fallbackCall = FunctionCall(
                            name = "shell",
                            args = mapOf(
                                "command" to fallback,
                                "description" to "Setup/install for ${command.description}",
                                "dir_path" to workspaceRoot
                            )
                        )
                        emit(GeminiStreamEvent.ToolCall(fallbackCall))
                        onToolCall(fallbackCall)
                        
                        try {
                            val fallbackResult = executeToolSync("shell", fallbackCall.args)
                            emit(GeminiStreamEvent.ToolResult("shell", fallbackResult))
                            onToolResult("shell", fallbackCall.args)
                            
                            if (fallbackResult.error == null) {
                                // Fallback successful, continue to next or retry check
                                fallbackSuccess = true
                                
                                // If this was venv creation or activation, re-check the tool
                                if (fallback.contains("venv") || fallback.contains("activate")) {
                                    emit(GeminiStreamEvent.Chunk("✅ Environment setup complete, rechecking tool...\n"))
                                    onChunk("✅ Environment setup complete, rechecking tool...\n")
                                    
                                    val recheckCall = FunctionCall(
                                        name = "shell",
                                        args = mapOf(
                                            "command" to command.checkCommand!!,
                                            "description" to "Recheck if ${command.description} tool is available"
                                        )
                                    )
                                    emit(GeminiStreamEvent.ToolCall(recheckCall))
                                    onToolCall(recheckCall)
                                    
                                    val recheckResult = executeToolSync("shell", recheckCall.args)
                                    emit(GeminiStreamEvent.ToolResult("shell", recheckResult))
                                    onToolResult("shell", recheckCall.args)
                                    
                                    if (recheckResult.error == null) {
                                        break // Tool is now available
                                    }
                                } else {
                                    // For install commands, break and try primary command
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("GeminiClient", "Fallback command failed: ${e.message}")
                            // Continue to next fallback
                        }
                    }
                    
                    if (!fallbackSuccess && command.fallbacks.isNotEmpty()) {
                        val allFailedMsg = "⚠️ All fallbacks failed, but continuing with primary command...\n"
                        emit(GeminiStreamEvent.Chunk(allFailedMsg))
                        onChunk(allFailedMsg)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("GeminiClient", "Check command failed: ${e.message}")
                // Continue to try primary command anyway
            }
        }
        
        // Execute primary command (may need venv activation)
        val primaryCommand = if (command.primaryCommand.contains("python") && 
            File(workspaceRoot, "venv").exists()) {
            // Check if venv needs activation
            "source venv/bin/activate && ${command.primaryCommand}"
        } else {
            command.primaryCommand
        }
        
        val primaryCall = FunctionCall(
            name = "shell",
            args = mapOf(
                "command" to primaryCommand,
                "description" to command.description,
                "dir_path" to workspaceRoot
            )
        )
        emit(GeminiStreamEvent.ToolCall(primaryCall))
        onToolCall(primaryCall)
        
        try {
            val result = executeToolSync("shell", primaryCall.args)
            emit(GeminiStreamEvent.ToolResult("shell", result))
            onToolResult("shell", primaryCall.args)
            
            // Check for failure keywords in output
            val outputText = result.llmContent ?: ""
            val hasFailure = result.error != null || detectFailureKeywords(outputText)
            
            if (!hasFailure) {
                val successMsg = "✅ Command executed successfully\n"
                emit(GeminiStreamEvent.Chunk(successMsg))
                onChunk(successMsg)
                return true
            } else {
                // Analyze failure and generate fallback plans
                val failureAnalysis = analyzeCommandFailure(
                    command.primaryCommand,
                    outputText,
                    result.error?.message ?: "",
                    workspaceRoot,
                    systemInfo
                )
                
                emit(GeminiStreamEvent.Chunk("🔍 Failure Analysis: ${failureAnalysis.reason}\n"))
                onChunk("🔍 Failure Analysis: ${failureAnalysis.reason}\n")
                
                // Try fallback plans in order
                var fallbackAttempted = false
                for ((index, fallbackPlan) in failureAnalysis.fallbackPlans.withIndex()) {
                    if (fallbackAttempted && index >= 2) break // Limit to 2 fallback attempts
                    
                    val fallbackMsg = "🔄 Fallback Plan ${index + 1}: ${fallbackPlan.description}\n"
                    emit(GeminiStreamEvent.Chunk(fallbackMsg))
                    onChunk(fallbackMsg)
                    
                    val fallbackCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to fallbackPlan.command,
                            "description" to fallbackPlan.description,
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(fallbackCall))
                    onToolCall(fallbackCall)
                    
                    try {
                        val fallbackResult = executeToolSync("shell", fallbackCall.args)
                        emit(GeminiStreamEvent.ToolResult("shell", fallbackResult))
                        onToolResult("shell", fallbackCall.args)
                        
                        val fallbackOutput = fallbackResult.llmContent ?: ""
                        val fallbackHasFailure = fallbackResult.error != null || detectFailureKeywords(fallbackOutput)
                        
                        if (!fallbackHasFailure) {
                            emit(GeminiStreamEvent.Chunk("✅ Fallback plan succeeded!\n"))
                            onChunk("✅ Fallback plan succeeded!\n")
                            
                            // Retry original command if fallback was setup
                            if (fallbackPlan.shouldRetryOriginal) {
                                emit(GeminiStreamEvent.Chunk("🔄 Retrying original command...\n"))
                                onChunk("🔄 Retrying original command...\n")
                                
                                val retryResult = executeToolSync("shell", primaryCall.args)
                                emit(GeminiStreamEvent.ToolResult("shell", retryResult))
                                onToolResult("shell", primaryCall.args)
                                
                                val retryOutput = retryResult.llmContent ?: ""
                                val retryHasFailure = retryResult.error != null || detectFailureKeywords(retryOutput)
                                
                                if (!retryHasFailure) {
                                    emit(GeminiStreamEvent.Chunk("✅ Original command succeeded after fallback!\n"))
                                    onChunk("✅ Original command succeeded after fallback!\n")
                                    return true
                                }
                            } else {
                                return true
                            }
                        } else {
                            emit(GeminiStreamEvent.Chunk("⚠️ Fallback plan also failed\n"))
                            onChunk("⚠️ Fallback plan also failed\n")
                        }
                        fallbackAttempted = true
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Fallback plan failed: ${e.message}")
                        emit(GeminiStreamEvent.Chunk("⚠️ Fallback plan error: ${e.message}\n"))
                        onChunk("⚠️ Fallback plan error: ${e.message}\n")
                        fallbackAttempted = true
                    }
                }
                
                // If command failed, try with venv activation if it's Python (legacy fallback)
                if (command.primaryCommand.contains("python") && 
                    !primaryCommand.contains("venv") &&
                    File(workspaceRoot, "venv").exists() &&
                    !fallbackAttempted) {
                    val venvMsg = "⚠️ Trying with venv activation...\n"
                    emit(GeminiStreamEvent.Chunk(venvMsg))
                    onChunk(venvMsg)
                    
                    val venvCommand = "source venv/bin/activate && ${command.primaryCommand}"
                    val venvCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to venvCommand,
                            "description" to "${command.description} (with venv)",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(venvCall))
                    onToolCall(venvCall)
                    
                    try {
                        val venvResult = executeToolSync("shell", venvCall.args)
                        emit(GeminiStreamEvent.ToolResult("shell", venvResult))
                        onToolResult("shell", venvCall.args)
                        
                        val venvOutput = venvResult.llmContent ?: ""
                        val venvHasFailure = venvResult.error != null || detectFailureKeywords(venvOutput)
                        
                        if (!venvHasFailure) {
                            emit(GeminiStreamEvent.Chunk("✅ Command executed successfully with venv\n"))
                            onChunk("✅ Command executed successfully with venv\n")
                            return true
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Venv command failed: ${e.message}")
                    }
                }
                
                emit(GeminiStreamEvent.Chunk("❌ Command failed: ${result.error?.message ?: "See output above"}\n"))
                onChunk("❌ Command failed: ${result.error?.message ?: "See output above"}\n")
                return false
            }
        } catch (e: Exception) {
            emit(GeminiStreamEvent.Chunk("❌ Error executing command: ${e.message}\n"))
            onChunk("❌ Error executing command: ${e.message}\n")
            return false
        }
    }
    
    /**
     * Detect failure keywords in command output
     */
    private fun detectFailureKeywords(output: String): Boolean {
        if (output.isEmpty()) return false
        
        val failureKeywords = listOf(
            "error", "failed", "failure", "fatal", "exception",
            "cannot", "can't", "unable", "not found", "missing",
            "command not found", "permission denied", "access denied",
            "syntax error", "parse error", "type error", "reference error",
            "module not found", "package not found", "dependency",
            "exit code", "exit status", "non-zero", "returned 1",
            "failed to", "unexpected", "invalid", "bad", "wrong"
        )
        
        val outputLower = output.lowercase()
        return failureKeywords.any { keyword ->
            outputLower.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * Data class for failure analysis
     */
    private data class FailureAnalysis(
        val reason: String,
        val fallbackPlans: List<FallbackPlan>
    )
    
    /**
     * Data class for fallback plan
     */
    private data class FallbackPlan(
        val command: String,
        val description: String,
        val shouldRetryOriginal: Boolean = false
    )
    
    /**
     * Analyze command failure and generate fallback plans using AI
     */
    private suspend fun analyzeCommandFailure(
        command: String,
        output: String,
        errorMessage: String,
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo
    ): FailureAnalysis {
        val systemContext = SystemInfoService.generateSystemContext()
        val model = ApiProviderManager.getCurrentModel()
        
        // Get project context
        val workspaceDir = File(workspaceRoot)
        val packageJson = File(workspaceDir, "package.json")
        val requirementsTxt = File(workspaceDir, "requirements.txt")
        val hasPackageJson = packageJson.exists()
        val hasRequirements = requirementsTxt.exists()
        
        val analysisPrompt = """
            $systemContext
            
            **Failed Command:** $command
            **Error Message:** $errorMessage
            **Command Output:** ${output.take(2000)}
            **Project Context:**
            - Has package.json: $hasPackageJson
            - Has requirements.txt: $hasRequirements
            - Package Manager: ${systemInfo.packageManager}
            
            Analyze the command failure and provide:
            1. **Reason**: Brief explanation of why the command failed
            2. **Fallback Plans**: List of alternative commands/approaches to try (max 3)
            
            For each fallback plan, provide:
            - command: The actual command to run
            - description: What this fallback does
            - should_retry_original: Whether to retry the original command after this fallback (true/false)
            
            Common fallback scenarios:
            - Missing dependencies: Install them first
            - Missing tools: Install via package manager
            - Python venv: Activate virtual environment
            - Node modules: Run npm install
            - Path issues: Use absolute paths or cd to correct directory
            - Permission issues: Check file permissions
            - Wrong command syntax: Try alternative syntax
            
            Format as JSON:
            {
              "reason": "Brief explanation of failure",
              "fallback_plans": [
                {
                  "command": "npm install",
                  "description": "Install missing dependencies",
                  "should_retry_original": true
                },
                {
                  "command": "python3 -m venv venv && source venv/bin/activate && pip install -r requirements.txt",
                  "description": "Create venv and install dependencies",
                  "should_retry_original": true
                }
              ]
            }
            
            Only include fallback plans that are likely to help. If no good fallbacks exist, return empty array.
        """.trimIndent()
        
        val request = JSONObject().apply {
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
        
        return try {
            val apiKey = ApiProviderManager.getNextApiKey() ?: return FailureAnalysis(
                "Unable to analyze failure (no API key)",
                emptyList()
            )
            val response = makeApiCallSimple(
                apiKey,
                model,
                request,
                useLongTimeout = false
            )
            
            if (response != null) {
                val jsonStart = response.indexOf('{')
                val jsonEnd = response.lastIndexOf('}') + 1
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val json = JSONObject(response.substring(jsonStart, jsonEnd))
                    val reason = json.optString("reason", "Unknown failure")
                    val fallbackPlansArray = json.optJSONArray("fallback_plans")
                    
                    val fallbackPlans = if (fallbackPlansArray != null) {
                        (0 until fallbackPlansArray.length()).mapNotNull { i ->
                            try {
                                val planObj = fallbackPlansArray.getJSONObject(i)
                                FallbackPlan(
                                    command = planObj.getString("command"),
                                    description = planObj.optString("description", "Fallback plan"),
                                    shouldRetryOriginal = planObj.optBoolean("should_retry_original", false)
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("GeminiClient", "Failed to parse fallback plan: ${e.message}")
                                null
                            }
                        }
                    } else {
                        emptyList()
                    }
                    
                    FailureAnalysis(reason, fallbackPlans)
                } else {
                    FailureAnalysis("Failed to parse analysis response", emptyList())
                }
            } else {
                FailureAnalysis("No response from AI analysis", emptyList())
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to analyze command failure: ${e.message}", e)
            FailureAnalysis("Analysis failed: ${e.message}", emptyList())
        }
    }
    
    /**
     * Non-streaming mode: Enhanced 3-phase approach
     * Phase 1: Get list of all files needed
     * Phase 2: Get comprehensive metadata for all files (relationships, imports, classes, functions, etc.)
     * Phase 3: Generate each file separately with full code using only the metadata provided
     * Phase 4: Detect and execute commands needed
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
        
        // Check if task only needs commands (no file creation)
        val commandsOnly = detectCommandsOnly(userMessage, workspaceRoot)
        
        if (commandsOnly) {
            emit(GeminiStreamEvent.Chunk("🔍 Detected command-only task, detecting commands needed...\n"))
            onChunk("🔍 Detected command-only task, detecting commands needed...\n")
            
            val commandsNeeded = detectCommandsNeeded(workspaceRoot, systemInfo, userMessage, ::emit, onChunk)
            
            if (commandsNeeded.isNotEmpty()) {
                val message = "📋 Found ${commandsNeeded.size} command(s) to execute\n"
                emit(GeminiStreamEvent.Chunk(message))
                onChunk(message)
                
                for (command in commandsNeeded) {
                    executeCommandWithFallbacks(command, workspaceRoot, systemInfo, ::emit, onChunk, onToolCall, onToolResult)
                }
                
                emit(GeminiStreamEvent.Done)
                return@flow
            } else {
                // No commands detected, fall through to normal flow
                emit(GeminiStreamEvent.Chunk("⚠️ No commands detected, proceeding with normal flow...\n"))
                onChunk("⚠️ No commands detected, proceeding with normal flow...\n")
            }
        }
        
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
            - exports: List of what this file exports (classes, functions, constants, etc.) - be SPECIFIC with exact names
            - metadata_tags: Unique tags for categorization (e.g., "db", "auth", "api", "ui", "config", "test")
            - relationships: List of other files this file depends on (use file_path values) - be COMPLETE and ACCURATE
            - dependencies: List of external dependencies/packages needed
            - description: Detailed description of the file's purpose and role
            - expectations: What this file should accomplish and how it fits in the project
            - file_type: Type of file (e.g., "javascript", "typescript", "python", "html", "css", "json", "config")
            
            **CRITICAL FOR CODE COHERENCE:**
            - For imports: Specify the EXACT import paths and what is imported (e.g., "import { ClassName } from './other-file.js'")
            - For exports: Specify the EXACT export names and types (e.g., "export class MyClass", "export function myFunction")
            - For relationships: Include ALL files that this file imports from, depends on, or references
            - For functions: Include function signatures with parameter names and types (if applicable)
            - For classes: Include class names and any interfaces/classes they implement or extend
            - Ensure all relationships are bidirectional - if File A imports from File B, File B should be in File A's relationships
            
            **IMPORT/EXPORT COHERENCE REQUIREMENTS:**
            - If File A imports something from File B, File B MUST export that exact item
            - Import paths must match file paths exactly (respecting relative paths)
            - Export names must match import names exactly
            - Function/class signatures must be consistent across files
            - All circular dependencies should be identified and handled appropriately
            
            For HTML files, also include:
            - links: CSS files, JS files, images, etc. referenced
            - ids: HTML element IDs used
            - classes: CSS classes used
            
            For CSS files, also include:
            - selectors: CSS selectors defined
            - imports: @import statements
            - variables: CSS variables defined
            
            Format your response as a JSON array of file metadata objects.
            
            **VERIFICATION:**
            After generating metadata, verify:
            1. All import/export relationships are consistent (if A imports X from B, B exports X)
            2. All file paths in relationships match actual file_path values
            3. All function/class names are consistent across related files
            4. All dependencies are properly identified
            
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
        
        var metadataJsonFinal = metadataJson
        
        if (metadataJson == null || metadataJson.length() != filePaths.size) {
            // Try to validate and fix metadata
            emit(GeminiStreamEvent.Chunk("⚠️ Metadata count mismatch, attempting to fix...\n"))
            onChunk("⚠️ Metadata count mismatch, attempting to fix...\n")
            
            // Retry metadata generation with better prompt
            val retryMetadataPrompt = """
                $systemContext
                
                Previous metadata generation had issues. Please regenerate comprehensive metadata for ALL files.
                
                Files to create:
                ${filePaths.joinToString("\n") { "- $it" }}
                
                Ensure you provide metadata for ALL ${filePaths.size} files. The previous response had ${metadataJson?.length() ?: 0} entries, but we need ${filePaths.size}.
                
                ${metadataPrompt.substringAfter("For each file, provide COMPREHENSIVE metadata:")}
            """.trimIndent()
            
            val retryMetadataRequest = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", retryMetadataPrompt)
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
            
            val retryMetadataText = makeApiCallWithRetryAndCorrection(
                model, retryMetadataRequest, "metadata retry", signal, null, ::emitEvent, onChunk
            )
            
            if (retryMetadataText != null) {
                val retryMetadataJson = try {
                    val jsonStart = retryMetadataText.indexOf('[')
                    val jsonEnd = retryMetadataText.lastIndexOf(']') + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        JSONArray(retryMetadataText.substring(jsonStart, jsonEnd))
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GeminiClient", "Failed to parse retry metadata", e)
                    null
                }
                
                if (retryMetadataJson != null && retryMetadataJson.length() == filePaths.size) {
                    metadataJsonFinal = retryMetadataJson
                    emit(GeminiStreamEvent.Chunk("✅ Metadata regenerated successfully\n"))
                    onChunk("✅ Metadata regenerated successfully\n")
                } else {
                    emit(GeminiStreamEvent.Error("Failed to generate complete metadata after retry"))
                    return@flow
                }
            } else {
                emit(GeminiStreamEvent.Error("Failed to generate metadata after retries"))
                return@flow
            }
        }
        
        // Validate metadata coherence
        val coherenceIssues = validateMetadataCoherence(metadataJsonFinal, filePaths)
        if (coherenceIssues.isNotEmpty()) {
            emit(GeminiStreamEvent.Chunk("⚠️ Metadata coherence issues detected:\n${coherenceIssues.take(3).joinToString("\n")}\n"))
            onChunk("⚠️ Metadata coherence issues detected\n")
            // Continue anyway - coherence issues will be caught during code generation
        }
        
        emit(GeminiStreamEvent.Chunk("✅ Metadata generated for ${metadataJsonFinal.length()} files\n"))
        
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
        for (i in 0 until metadataJsonFinal.length()) {
            val fileMeta = metadataJsonFinal.getJSONObject(i)
            val filePath = fileMeta.getString("file_path")
            metadataMap[filePath] = fileMeta
        }
        
        // Track generated files for coherence
        val generatedFiles = mutableMapOf<String, String>() // filePath to content
        
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
            
            // Build context from already-generated related files with enhanced coherence information
            val relatedFilesContext = buildString {
                relationships.forEach { relatedPath ->
                    generatedFiles[relatedPath]?.let { content ->
                        append("\n\n=== Related file: $relatedPath ===\n")
                        
                        // Extract key information for coherence
                        val lines = content.lines()
                        val exports = mutableListOf<String>()
                        val imports = mutableListOf<String>()
                        val classes = mutableListOf<String>()
                        val functions = mutableListOf<String>()
                        
                        // Extract exports, imports, classes, and functions for better coherence
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            when {
                                trimmed.startsWith("export ") -> exports.add(trimmed.take(150))
                                trimmed.startsWith("import ") || trimmed.startsWith("from ") -> imports.add(trimmed.take(150))
                                trimmed.matches(Regex("^(export\\s+)?(class|interface|type|enum)\\s+\\w+")) -> classes.add(trimmed.take(150))
                                trimmed.matches(Regex("^(export\\s+)?(function|const|let|var)\\s+\\w+")) -> functions.add(trimmed.take(150))
                            }
                        }
                        
                        // Include key coherence information first
                        if (exports.isNotEmpty()) {
                            append("Exports: ${exports.take(5).joinToString(", ")}\n")
                        }
                        if (imports.isNotEmpty()) {
                            append("Imports: ${imports.take(5).joinToString(", ")}\n")
                        }
                        if (classes.isNotEmpty()) {
                            append("Classes: ${classes.take(5).joinToString(", ")}\n")
                        }
                        if (functions.isNotEmpty()) {
                            append("Functions: ${functions.take(5).joinToString(", ")}\n")
                        }
                        
                        append("\n--- Code Preview ---\n")
                        // Include first 600 chars for better context
                        val preview = content.take(600)
                        append(preview)
                        if (content.length > 600) append("\n... (truncated)")
                    }
                }
            }
            
            // Build context from all previously generated files (for consistency) - limit to most relevant
            val allFilesContext = if (generatedFiles.isNotEmpty()) {
                buildString {
                    append("\n\n=== Previously Generated Files (for reference and consistency) ===\n")
                    // Only include last 3-5 files to reduce token usage and speed up generation
                    val recentFiles = generatedFiles.toList().takeLast(5)
                    recentFiles.forEach { (path, content) ->
                        append("\n--- $path ---\n")
                        // Reduce preview size to 200 chars for speed
                        append(content.take(200))
                        if (content.length > 200) append("\n...")
                    }
                }
            } else ""
            
            val codePrompt = """
                $systemContext
                
                Generate the COMPLETE, FULL, FUNCTIONAL code for file: $filePath
                
                **CRITICAL INSTRUCTIONS FOR COMPLETE FUNCTIONALITY:**
                - You MUST use ONLY the metadata provided below
                - You MUST include ALL classes, functions, imports, and exports specified
                - You MUST respect the relationships and dependencies
                - Generate complete, working, production-ready code that is FULLY FUNCTIONAL
                - Do NOT use placeholders or TODOs unless explicitly needed
                - Ensure all imports are correct and match the metadata
                - Follow the file type conventions: $fileType
                - Make sure imports reference actual files that exist or will exist
                - Ensure function/class names match exactly with what other files expect
                
                **CODE COHERENCE REQUIREMENTS (CRITICAL FOR WORKING CODE):**
                - MATCH PATTERNS: Study the related files and previously generated files below. Match their coding style, patterns, and conventions exactly
                - IMPORT CONSISTENCY: All imports must match the exact paths, names, and export patterns used in related files
                - EXPORT CONSISTENCY: All exports must match exactly what other files are importing (same names, same signatures)
                - API CONSISTENCY: If this file calls functions/classes from related files, use the EXACT same function names, parameter names, and signatures as shown in those files
                - NAMING CONSISTENCY: Use the same naming conventions (camelCase, PascalCase, snake_case, etc.) as related files
                - STRUCTURE CONSISTENCY: Follow the same code organization patterns (imports order, class structure, function placement) as related files
                - TYPE CONSISTENCY: If using TypeScript or typed languages, ensure types match exactly with related files
                - INTERFACE CONSISTENCY: If implementing interfaces or extending classes from related files, match their exact structure
                - ERROR HANDLING: Use the same error handling patterns as related files
                - LOGGING/DEBUGGING: Use the same logging/debugging patterns as related files
                - CONFIGURATION: Use the same configuration patterns and constants as related files
                
                **COMPLETENESS REQUIREMENTS (CRITICAL):**
                - For games/applications: Include ALL initialization code (DOMContentLoaded, window.onload, etc.)
                - For games/applications: Include ALL event handlers and bindings (click, input, etc.)
                - For games/applications: Include ALL game logic (start game, make move, check win, etc.)
                - For interactive apps: Ensure ALL user interactions are wired up and functional
                - For web apps: Include ALL DOM manipulation and event listeners
                - For Node.js apps: Include ALL server startup and route handlers
                - For Python apps: Include ALL main execution blocks and function calls
                - NO MISSING PIECES: Every function must be complete, every event must be bound, every feature must work
                - The code MUST be immediately runnable and functional - no setup steps missing
                - ALL dependencies must be properly imported and used correctly
                - ALL exported items must be fully implemented and match what other files expect
                
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
                $relatedFilesContext
                $allFilesContext
                
                **COHERENCE VERIFICATION CHECKLIST:**
                Before generating, verify:
                1. All import paths match exactly with file paths in the project
                2. All imported names match exactly with exports from related files
                3. All function/class signatures match what related files expect
                4. All naming conventions match related files
                5. All code patterns and structures match related files
                6. All types/interfaces match related files (if applicable)
                7. The code will work seamlessly with related files without conflicts
                
                **EXAMPLES OF WHAT MUST BE INCLUDED:**
                - If it's a game: game initialization, event listeners for moves/clicks, win/lose detection, UI updates
                - If it's a web app: DOM ready handlers, all button/input event bindings, all API calls
                - If it's a Node.js app: server.listen(), all route handlers, middleware setup
                - If it's interactive: ALL user interaction handlers must be present and functional
                
                Generate the complete, fully functional code now. Return ONLY the code, no explanations or markdown formatting.
                The code MUST be immediately runnable and work end-to-end without missing pieces.
                The code MUST be coherent with all related files and work as a unified system.
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
                val failedMsg = "⚠️ Failed to generate: $filePath\n"
                emit(GeminiStreamEvent.Chunk(failedMsg))
                onChunk(failedMsg)
                continue
            }
            
            // Extract code (remove markdown code blocks if present)
            val cleanCode = codeContent
                .replace(Regex("```[\\w]*\\n"), "")
                .replace(Regex("```\\n?"), "")
                .trim()
            
            // Write file immediately instead of storing in memory
            val writingMsg = "📝 Writing: $filePath\n"
            emit(GeminiStreamEvent.Chunk(writingMsg))
            onChunk(writingMsg)
            
            val functionCall = FunctionCall(
                name = "write_file",
                args = mapOf(
                    "file_path" to filePath,
                    "content" to cleanCode
                )
            )
            
            emit(GeminiStreamEvent.ToolCall(functionCall))
            onToolCall(functionCall)
            
            val toolResult = try {
                val writeResult = executeToolSync(functionCall.name, functionCall.args)
                
                // Automatically run linter check after writing
                if (writeResult.error == null) {
                    emit(GeminiStreamEvent.Chunk("🔍 Checking: $filePath\n"))
                    onChunk("🔍 Checking: $filePath\n")
                    
                    try {
                        val linterCall = FunctionCall(
                            name = "language_linter",
                            args = mapOf(
                                "file_path" to filePath,
                                "strict" to false
                            )
                        )
                        val linterResult = executeToolSync(linterCall.name, linterCall.args)
                        
                        if (linterResult.llmContent.contains("Found") && 
                            (linterResult.llmContent.contains("error") || linterResult.llmContent.contains("issue"))) {
                            emit(GeminiStreamEvent.Chunk("⚠️ Issues found in $filePath:\n${linterResult.llmContent.take(500)}\n"))
                            onChunk("⚠️ Issues found in $filePath\n")
                            
                            // Add fix task
                            val fixCall = FunctionCall(
                                name = "syntax_fix",
                                args = mapOf(
                                    "file_path" to filePath,
                                    "auto_fix" to true
                                )
                            )
                            try {
                                val fixResult = executeToolSync(fixCall.name, fixCall.args)
                                if (fixResult.error == null) {
                                    emit(GeminiStreamEvent.Chunk("🔧 Auto-fixed issues in $filePath\n"))
                                    onChunk("🔧 Auto-fixed issues in $filePath\n")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("GeminiClient", "Failed to auto-fix: ${e.message}")
                            }
                        } else {
                            val noIssuesMsg = "✅ No issues found in $filePath\n"
                            emit(GeminiStreamEvent.Chunk(noIssuesMsg))
                            onChunk(noIssuesMsg)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Linter check failed: ${e.message}")
                        
                        // Enhanced error detection and debugging
                        val errorAnalysis = analyzeLinterError(e, filePath, workspaceRoot)
                        if (errorAnalysis.isNotEmpty()) {
                            emit(GeminiStreamEvent.Chunk("🔍 Error Analysis:\n$errorAnalysis\n"))
                            onChunk("🔍 Error Analysis:\n$errorAnalysis\n")
                        }
                        // Continue even if linter check fails
                    }
                }
                
                writeResult
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
            
            // Store generated file for coherence in subsequent files
            if (toolResult.error == null) {
                generatedFiles[filePath] = cleanCode
            }
            
            val generatedMsg = "✅ Generated and written: $filePath\n"
            emit(GeminiStreamEvent.Chunk(generatedMsg))
            onChunk(generatedMsg)
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
        
        // Phase 4: Detect and execute commands needed after file creation
        val phase4Msg = "\n🔍 Phase 4: Detecting commands to run...\n"
        emit(GeminiStreamEvent.Chunk(phase4Msg))
        onChunk(phase4Msg)
        
        val commandsNeeded = detectCommandsNeeded(workspaceRoot, systemInfo, userMessage, ::emit, onChunk)
        
        if (commandsNeeded.isNotEmpty()) {
            val foundCmdsMsg = "📋 Found ${commandsNeeded.size} command(s) to execute\n"
            emit(GeminiStreamEvent.Chunk(foundCmdsMsg))
            onChunk(foundCmdsMsg)
            
            // Add command execution to todos
            val todosWithCommands = updatedTodos + commandsNeeded.mapIndexed { index, cmd ->
                Todo("Execute: ${cmd.primaryCommand}", TodoStatus.PENDING)
            }
            updateTodos(todosWithCommands)
            
            for ((index, command) in commandsNeeded.withIndex()) {
                val success = executeCommandWithFallbacks(command, workspaceRoot, systemInfo, ::emit, onChunk, onToolCall, onToolResult)
                
                // Update todo status
                val updatedTodosWithStatus = currentTodos.map { todo ->
                    if (todo.description == "Execute: ${command.primaryCommand}") {
                        todo.copy(status = if (success) TodoStatus.COMPLETED else TodoStatus.PENDING)
                    } else {
                        todo
                    }
                }
                updateTodos(updatedTodosWithStatus)
            }
        } else {
            emit(GeminiStreamEvent.Chunk("ℹ️ No commands detected to run\n"))
            onChunk("ℹ️ No commands detected to run\n")
        }
        
        // Phase 5: API Testing (for web frameworks only, skip Kotlin/Java)
        val isWebFramework = detectWebFramework(workspaceRoot)
        val isKotlinJava = detectKotlinJava(workspaceRoot)
        
        updatedTodos = currentTodos.map { todo ->
            if (todo.description == "Phase 3: Generate code for each file") {
                todo.copy(status = TodoStatus.COMPLETED)
            } else {
                todo
            }
        }
        updateTodos(updatedTodos)
        
        // Add Phase 5 and 6 to todos
        val todosWithTesting = updatedTodos + listOf(
            Todo("Phase 5: Run tests and validate", TodoStatus.PENDING),
            Todo("Phase 6: Fix issues and retry (if needed)", TodoStatus.PENDING)
        )
        updateTodos(todosWithTesting)
        
        if (isWebFramework && !isKotlinJava) {
            emit(GeminiStreamEvent.Chunk("\n🧪 Phase 5: Testing API endpoints...\n"))
            onChunk("\n🧪 Phase 5: Testing API endpoints...\n")
            
            val testResult = testAPIs(workspaceRoot, systemInfo, userMessage, ::emit, onChunk, onToolCall, onToolResult)
            
            if (testResult) {
                emit(GeminiStreamEvent.Chunk("✅ API testing completed successfully\n"))
                onChunk("✅ API testing completed successfully\n")
            } else {
                emit(GeminiStreamEvent.Chunk("⚠️ API testing completed with some issues\n"))
                onChunk("⚠️ API testing completed with some issues\n")
            }
        } else {
            if (isKotlinJava) {
                emit(GeminiStreamEvent.Chunk("\nℹ️ Skipping API testing for Kotlin/Java projects\n"))
                onChunk("\nℹ️ Skipping API testing for Kotlin/Java projects\n")
            } else {
                emit(GeminiStreamEvent.Chunk("\nℹ️ No web framework detected, skipping API testing\n"))
                onChunk("\nℹ️ No web framework detected, skipping API testing\n")
            }
        }
        
        // Phase 6: Comprehensive validation and testing with retry loop
        var validationAttempt = 0
        val maxValidationAttempts = 5
        var workComplete = false
        
        while (!workComplete && validationAttempt < maxValidationAttempts && signal?.isAborted() != true) {
            validationAttempt++
            
            if (validationAttempt > 1) {
                emit(GeminiStreamEvent.Chunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n"))
                onChunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n")
            } else {
                emit(GeminiStreamEvent.Chunk("\n✅ Phase 6: Validating project completeness...\n"))
                onChunk("\n✅ Phase 6: Validating project completeness...\n")
            }
            
            updatedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 5: Run tests and validate" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    todo.description == "Phase 6: Fix issues and retry (if needed)" -> todo.copy(status = if (validationAttempt > 1) TodoStatus.IN_PROGRESS else TodoStatus.PENDING)
                    else -> todo
                }
            }
            updateTodos(updatedTodos)
            
            // Step 1: Run test commands if available
            val testCommands = detectTestCommands(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk)
            var testFailures = mutableListOf<Pair<String, ToolResult>>()
            var hasTestFailures = false
            
            if (testCommands.isNotEmpty()) {
                emit(GeminiStreamEvent.Chunk("🧪 Running test commands...\n"))
                onChunk("🧪 Running test commands...\n")
                
                for (command in testCommands) {
                    emit(GeminiStreamEvent.Chunk("▶️ Testing: ${command.primaryCommand}\n"))
                    onChunk("▶️ Testing: ${command.primaryCommand}\n")
                    
                    // First ensure command can run (with fallbacks)
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        hasTestFailures = true
                        testFailures.add(Pair(command.primaryCommand, ToolResult(
                            llmContent = "Command execution failed",
                            returnDisplay = "Failed",
                            error = ToolError(
                                message = "Command could not be executed",
                                type = ToolErrorType.EXECUTION_ERROR
                            )
                        )))
                        emit(GeminiStreamEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Test failed: ${command.primaryCommand}\n")
                        continue
                    }
                    
                    // Execute the command to get actual result
                    val shellCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to command.primaryCommand,
                            "description" to "Run test: ${command.description}",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(shellCall))
                    onToolCall(shellCall)
                    
                    val result = try {
                        executeToolSync("shell", shellCall.args)
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
                    
                    emit(GeminiStreamEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    val isFailure = result.error != null || 
                        result.llmContent.contains("FAILED", ignoreCase = true) ||
                        (result.llmContent.contains("failed", ignoreCase = true) && 
                         result.llmContent.contains("test", ignoreCase = true)) ||
                        (result.llmContent.contains("error", ignoreCase = true) && 
                         result.llmContent.contains("test", ignoreCase = true)) ||
                        result.llmContent.contains("AssertionError", ignoreCase = true) ||
                        result.llmContent.contains("TestError", ignoreCase = true)
                    
                    if (isFailure) {
                        hasTestFailures = true
                        testFailures.add(Pair(command.primaryCommand, result))
                        emit(GeminiStreamEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Test failed: ${command.primaryCommand}\n")
                    } else {
                        emit(GeminiStreamEvent.Chunk("✅ Test passed: ${command.primaryCommand}\n"))
                        onChunk("✅ Test passed: ${command.primaryCommand}\n")
                    }
                }
            }
            
            // Step 2: Run API tests if applicable
            var apiTestFailed = false
            if (isWebFramework && !isKotlinJava) {
                emit(GeminiStreamEvent.Chunk("🌐 Running API tests...\n"))
                onChunk("🌐 Running API tests...\n")
                
                val apiTestResult = testAPIs(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk, onToolCall, onToolResult)
                if (!apiTestResult) {
                    apiTestFailed = true
                    hasTestFailures = true
                }
            }
            
            // Step 3: Check for compilation/build errors
            var buildErrors = false
            val buildCommands = detectCommandsNeeded(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk)
                .filter { cmd -> 
                    val cmdLower = cmd.primaryCommand.lowercase()
                    cmdLower.contains("build") || cmdLower.contains("compile") || 
                    cmdLower.contains("make") || cmdLower.contains("gradle") ||
                    cmdLower.contains("maven") || cmdLower.contains("cmake")
                }
            
            if (buildCommands.isNotEmpty()) {
                emit(GeminiStreamEvent.Chunk("🔨 Checking build/compilation...\n"))
                onChunk("🔨 Checking build/compilation...\n")
                
                for (command in buildCommands) {
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        buildErrors = true
                        hasTestFailures = true
                        emit(GeminiStreamEvent.Chunk("❌ Build error detected: ${command.primaryCommand}\n"))
                        onChunk("❌ Build error detected: ${command.primaryCommand}\n")
                        continue
                    }
                    
                    // Execute the command to get actual result
                    val shellCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to command.primaryCommand,
                            "description" to "Build: ${command.description}",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(shellCall))
                    onToolCall(shellCall)
                    
                    val result = try {
                        executeToolSync("shell", shellCall.args)
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
                    
                    emit(GeminiStreamEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    if (result.error != null || 
                        result.llmContent.contains("error", ignoreCase = true) ||
                        result.llmContent.contains("failed", ignoreCase = true) ||
                        result.llmContent.contains("ERROR", ignoreCase = true)) {
                        buildErrors = true
                        hasTestFailures = true
                        emit(GeminiStreamEvent.Chunk("❌ Build error detected: ${command.primaryCommand}\n"))
                        onChunk("❌ Build error detected: ${command.primaryCommand}\n")
                    }
                }
            }
            
            // Step 4: Work completion detection
            if (!hasTestFailures && !apiTestFailed && !buildErrors) {
                // Additional validation: Check if project structure is complete
                val completenessCheck = validateProjectCompleteness(
                    workspaceRoot, filePaths, userMessage, ::emitEvent, onChunk
                )
                
                if (completenessCheck) {
                    workComplete = true
                    emit(GeminiStreamEvent.Chunk("\n✅ Project validation passed! Work is complete.\n"))
                    onChunk("\n✅ Project validation passed! Work is complete.\n")
                    
                    updatedTodos = currentTodos.map { todo ->
                        when {
                            todo.description == "Phase 5: Run tests and validate" -> todo.copy(status = TodoStatus.COMPLETED)
                            todo.description == "Phase 6: Fix issues and retry (if needed)" -> todo.copy(status = TodoStatus.COMPLETED)
                            else -> todo
                        }
                    }
                    updateTodos(updatedTodos)
                    break
                }
            }
            
            // Step 5: If failures detected, analyze and fix
            if (hasTestFailures || apiTestFailed || buildErrors) {
                if (validationAttempt >= maxValidationAttempts) {
                    emit(GeminiStreamEvent.Chunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n"))
                    onChunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n")
                    break
                }
                
                emit(GeminiStreamEvent.Chunk("\n🔧 Analyzing failures and generating fixes...\n"))
                onChunk("\n🔧 Analyzing failures and generating fixes...\n")
                
                updatedTodos = currentTodos.map { todo ->
                    if (todo.description == "Phase 6: Fix issues and retry (if needed)") {
                        todo.copy(status = TodoStatus.IN_PROGRESS)
                    } else {
                        todo
                    }
                }
                updateTodos(updatedTodos)
                
                // Collect failure information
                val failureInfo = buildString {
                    if (testFailures.isNotEmpty()) {
                        append("Test Failures:\n")
                        testFailures.forEach { (cmd, result) ->
                            append("- Command: $cmd\n")
                            append("  Error: ${result.error?.message ?: "Test failure"}\n")
                            append("  Output: ${result.llmContent.take(800)}\n\n")
                        }
                    }
                    if (apiTestFailed) {
                        append("API Testing: Failed\n")
                    }
                    if (buildErrors) {
                        append("Build/Compilation: Errors detected\n")
                    }
                }
                
                // Use reverse flow to fix issues
                val projectStructure = extractProjectStructure(workspaceRoot, signal, ::emitEvent, onChunk)
                
                val fixPrompt = """
                    $systemContext
                    
                    **Project Goal:** $userMessage
                    
                    **Failures Detected:**
                    $failureInfo
                    
                    **Project Structure:**
                    $projectStructure
                    
                    Analyze the failures and provide fixes. For each fix, provide:
                    1. The file path
                    2. The exact old_string to replace (include enough context - at least 5-10 lines before and after)
                    3. The exact new_string (complete fixed code)
                    4. Confidence level (high/medium/low)
                    
                    **CRITICAL CODE COHERENCE REQUIREMENTS FOR FIXES:**
                    - MAINTAIN CONSISTENCY: All fixes must maintain consistency with the existing codebase patterns, style, and conventions
                    - PRESERVE IMPORTS: Ensure all imports remain correct and match what the fixed code uses
                    - PRESERVE EXPORTS: Ensure all exports remain consistent with what other files expect
                    - MATCH SIGNATURES: If fixing function/class signatures, ensure they match what other files call/import
                    - MATCH PATTERNS: Use the same coding patterns, error handling, and structure as the rest of the codebase
                    
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
                
                val fixText = makeApiCallWithRetryAndCorrection(
                    model, fixRequest, "fixes", signal, null, ::emitEvent, onChunk
                )
                
                if (fixText != null) {
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
                    
                    if (fixesJson != null && fixesJson.length() > 0) {
                        emit(GeminiStreamEvent.Chunk("✅ Generated ${fixesJson.length()} fix(es)\n"))
                        onChunk("✅ Generated ${fixesJson.length()} fix(es)\n")
                        
                        // Apply fixes
                        for (i in 0 until fixesJson.length()) {
                            val fix = fixesJson.getJSONObject(i)
                            val filePath = fix.getString("file_path")
                            val oldString = fix.getString("old_string")
                            val newString = fix.getString("new_string")
                            
                            emit(GeminiStreamEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                            onChunk("🔨 Applying fix to $filePath...\n")
                            
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
                                emit(GeminiStreamEvent.Chunk("✅ Fix applied successfully\n"))
                                onChunk("✅ Fix applied successfully\n")
                                // Update generated files cache
                                if (filePath in generatedFiles) {
                                    val readResult = executeToolSync("read_file", mapOf("file_path" to filePath))
                                    if (readResult.error == null) {
                                        generatedFiles[filePath] = readResult.llmContent
                                    }
                                }
                            } else {
                                emit(GeminiStreamEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                                onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                            }
                        }
                    } else {
                        emit(GeminiStreamEvent.Chunk("⚠️ Could not generate fixes\n"))
                        onChunk("⚠️ Could not generate fixes\n")
                        
                        // If we can't generate fixes, mark phase as completed and exit
                        updatedTodos = currentTodos.map { todo ->
                            when {
                                todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.COMPLETED)
                                todo.description == "Phase 5: Re-run tests to verify" -> todo.copy(status = TodoStatus.COMPLETED)
                                else -> todo
                            }
                        }
                        updateTodos(updatedTodos)
                        
                        emit(GeminiStreamEvent.Chunk("\n⚠️ Could not generate fixes. Some issues may remain.\n"))
                        onChunk("\n⚠️ Could not generate fixes. Some issues may remain.\n")
                    }
                } else {
                    // Fix generation failed
                    emit(GeminiStreamEvent.Chunk("⚠️ Failed to generate fixes\n"))
                    onChunk("⚠️ Failed to generate fixes\n")
                    
                    updatedTodos = currentTodos.map { todo ->
                        when {
                            todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.COMPLETED)
                            todo.description == "Phase 5: Re-run tests to verify" -> todo.copy(status = TodoStatus.COMPLETED)
                            else -> todo
                        }
                    }
                    updateTodos(updatedTodos)
                }
            } else {
                // No failures to fix
                emit(GeminiStreamEvent.Chunk("\n✅ All tests passed! No fixes needed.\n"))
                onChunk("\n✅ All tests passed! No fixes needed.\n")
                
                updatedTodos = currentTodos.map { todo ->
                    when {
                        todo.description == "Phase 3: Analyze test failures" -> todo.copy(status = TodoStatus.COMPLETED)
                        todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.COMPLETED)
                        todo.description == "Phase 5: Re-run tests to verify" -> todo.copy(status = TodoStatus.COMPLETED)
                        else -> todo
                    }
                }
                updateTodos(updatedTodos)
            }
        }
        
        if (workComplete) {
            emit(GeminiStreamEvent.Chunk("\n✨ Project generation complete and validated!\n"))
            onChunk("\n✨ Project generation complete and validated!\n")
        } else {
            emit(GeminiStreamEvent.Chunk("\n⚠️ Project generation completed with some issues remaining\n"))
            onChunk("\n⚠️ Project generation completed with some issues remaining\n")
        }
        
        emit(GeminiStreamEvent.Done)
    }
    
    /**
     * Validate project completeness by checking if all required files exist and are functional
     */
    private suspend fun validateProjectCompleteness(
        workspaceRoot: String,
        expectedFiles: List<String>,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        // Check if all expected files exist
        var allFilesExist = true
        for (filePath in expectedFiles) {
            val file = File(workspaceDir, filePath)
            if (!file.exists()) {
                emit(GeminiStreamEvent.Chunk("⚠️ Missing file: $filePath\n"))
                onChunk("⚠️ Missing file: $filePath\n")
                allFilesExist = false
            }
        }
        
        if (!allFilesExist) {
            return false
        }
        
        // Check for critical files based on project type
        val hasPackageJson = File(workspaceDir, "package.json").exists()
        val hasRequirementsTxt = File(workspaceDir, "requirements.txt").exists()
        val hasMainFile = expectedFiles.any { 
            it.contains("main", ignoreCase = true) || 
            it.contains("index", ignoreCase = true) ||
            it.contains("app", ignoreCase = true)
        }
        
        // Basic validation passed
        return true
    }
    
    /**
     * Validate metadata coherence - check import/export consistency
     */
    private fun validateMetadataCoherence(
        metadataJson: JSONArray,
        filePaths: List<String>
    ): List<String> {
        val issues = mutableListOf<String>()
        val metadataMap = mutableMapOf<String, JSONObject>()
        
        // Build metadata map
        for (i in 0 until metadataJson.length()) {
            try {
                val fileMeta = metadataJson.getJSONObject(i)
                val filePath = fileMeta.getString("file_path")
                metadataMap[filePath] = fileMeta
            } catch (e: Exception) {
                issues.add("Failed to parse metadata entry $i: ${e.message}")
            }
        }
        
        // Check import/export consistency
        for ((filePath, fileMeta) in metadataMap) {
            try {
                val imports = if (fileMeta.has("imports")) {
                    fileMeta.getJSONArray("imports").let { arr ->
                        (0 until arr.length()).mapNotNull { 
                            try { arr.getString(it) } catch (e: Exception) { null }
                        }
                    }
                } else emptyList()
                
                val exports = if (fileMeta.has("exports")) {
                    fileMeta.getJSONArray("exports").let { arr ->
                        (0 until arr.length()).mapNotNull { 
                            try { arr.getString(it) } catch (e: Exception) { null }
                        }
                    }
                } else emptyList()
                
                val relationships = if (fileMeta.has("relationships")) {
                    fileMeta.getJSONArray("relationships").let { arr ->
                        (0 until arr.length()).mapNotNull { 
                            try { arr.getString(it) } catch (e: Exception) { null }
                        }
                    }
                } else emptyList()
                
                // Check if imported files exist in metadata
                imports.forEach { importStmt ->
                    // Extract file path from import statement (simplified check)
                    val importedFile = relationships.find { 
                        importStmt.contains(it, ignoreCase = true) 
                    }
                    if (importedFile != null && !metadataMap.containsKey(importedFile)) {
                        issues.add("$filePath imports from $importedFile but it's not in metadata")
                    }
                }
                
                // Check if relationships reference valid files
                relationships.forEach { relatedFile ->
                    if (!metadataMap.containsKey(relatedFile) && !filePaths.contains(relatedFile)) {
                        issues.add("$filePath has relationship to $relatedFile but it's not in file list")
                    }
                }
            } catch (e: Exception) {
                issues.add("Error validating $filePath: ${e.message}")
            }
        }
        
        return issues
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
        val systemInfo = SystemInfoService.detectSystemInfo()
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
        
        // Phase 0: Detect and run commands if requested (e.g., init-db, test APIs)
        val messageLower = userMessage.lowercase()
        val wantsCommands = messageLower.contains("run") || 
                           messageLower.contains("init") || 
                           messageLower.contains("test") ||
                           messageLower.contains("execute") ||
                           messageLower.contains("api test") ||
                           messageLower.contains("check api")
        
        if (wantsCommands) {
            initialTodos.add(Todo("Phase 0: Run requested commands", TodoStatus.PENDING))
        }
        
        initialTodos.addAll(listOf(
            Todo("Phase 1: Extract project structure", TodoStatus.PENDING),
            Todo("Phase 2: Analyze what needs fixing", TodoStatus.PENDING),
            Todo("Phase 3: Read specific lines/functions", TodoStatus.PENDING),
            Todo("Phase 4: Get fixes with assurance", TodoStatus.PENDING),
            Todo("Phase 5: Apply fixes", TodoStatus.PENDING)
        ))
        
        // Mark Phase 0 or Phase 1 as in progress
        val todosWithProgress = initialTodos.map { todo ->
            when {
                todo.description == "Phase 0: Run requested commands" && wantsCommands -> todo.copy(status = TodoStatus.IN_PROGRESS)
                todo.description == "Phase 1: Extract project structure" && !wantsCommands -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(todosWithProgress)
        
        // Helper function to wrap emit as suspend function (used in multiple phases)
        suspend fun emitEvent(event: GeminiStreamEvent) {
            emit(event)
        }
        
        // Phase 0: Run requested commands
        if (wantsCommands) {
            emit(GeminiStreamEvent.Chunk("▶️ Phase 0: Detecting and running requested commands...\n"))
            onChunk("▶️ Phase 0: Detecting and running requested commands...\n")
            
            // Detect commands to run
            val commandsToRun = detectCommandsToRun(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk)
            
            var hasCommandFailures = false
            if (commandsToRun.isNotEmpty()) {
                emit(GeminiStreamEvent.Chunk("📋 Found ${commandsToRun.size} command(s) to run\n"))
                onChunk("📋 Found ${commandsToRun.size} command(s) to run\n")
                
                for (command in commandsToRun) {
                    emit(GeminiStreamEvent.Chunk("▶️ Running: ${command.primaryCommand}\n"))
                    onChunk("▶️ Running: ${command.primaryCommand}\n")
                    
                    // First ensure command can run (with fallbacks)
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        hasCommandFailures = true
                        emit(GeminiStreamEvent.Chunk("❌ Command failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Command failed: ${command.primaryCommand}\n")
                        continue
                    }
                    
                    // Execute the command to get actual result
                    val shellCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to command.primaryCommand,
                            "description" to command.description,
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(shellCall))
                    onToolCall(shellCall)
                    
                    val result = try {
                        executeToolSync("shell", shellCall.args)
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
                    
                    emit(GeminiStreamEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    // Check if command failed
                    if (result.error != null || 
                        result.llmContent.contains("error", ignoreCase = true) ||
                        result.llmContent.contains("failed", ignoreCase = true)) {
                        hasCommandFailures = true
                        emit(GeminiStreamEvent.Chunk("❌ Command failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Command failed: ${command.primaryCommand}\n")
                    } else {
                        emit(GeminiStreamEvent.Chunk("✅ Command completed: ${command.primaryCommand}\n"))
                        onChunk("✅ Command completed: ${command.primaryCommand}\n")
                    }
                }
            } else {
                emit(GeminiStreamEvent.Chunk("ℹ️ No specific commands detected to run\n"))
                onChunk("ℹ️ No specific commands detected to run\n")
            }
            
            // Run API tests if requested
            val wantsApiTest = messageLower.contains("api") || 
                             messageLower.contains("endpoint") || 
                             messageLower.contains("test api") ||
                             messageLower.contains("check api")
            
            val isWebFramework = detectWebFramework(workspaceRoot)
            val isKotlinJava = detectKotlinJava(workspaceRoot)
            
            if (wantsApiTest && isWebFramework && !isKotlinJava) {
                emit(GeminiStreamEvent.Chunk("🌐 Running API tests...\n"))
                onChunk("🌐 Running API tests...\n")
                
                val apiTestResult = testAPIs(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk, onToolCall, onToolResult)
                if (!apiTestResult) {
                    hasCommandFailures = true
                }
            }
            
            // Update Phase 0 todo
            val phase0CompletedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 0: Run requested commands" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 1: Extract project structure" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    else -> todo
                }
            }
            updateTodos(phase0CompletedTodos)
            
            // If commands failed, we'll continue to code analysis to fix issues
            if (hasCommandFailures) {
                emit(GeminiStreamEvent.Chunk("⚠️ Some commands failed. Proceeding to analyze and fix issues...\n"))
                onChunk("⚠️ Some commands failed. Proceeding to analyze and fix issues...\n")
            }
        }
        
        // Phase 1: Extract project structure
        emit(GeminiStreamEvent.Chunk("📊 Phase 1: Extracting project structure...\n"))
        onChunk("📊 Phase 1: Extracting project structure...\n")
        
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
            2. The exact old_string to replace (include enough context - at least 5-10 lines before and after)
            3. The exact new_string (complete fixed code)
            4. Confidence level (high/medium/low)
            
            **CRITICAL CODE COHERENCE REQUIREMENTS FOR FIXES:**
            - MAINTAIN CONSISTENCY: All fixes must maintain consistency with the existing codebase patterns, style, and conventions
            - PRESERVE IMPORTS: Ensure all imports remain correct and match what the fixed code uses
            - PRESERVE EXPORTS: Ensure all exports remain consistent with what other files expect
            - MATCH SIGNATURES: If fixing function/class signatures, ensure they match what other files call/import
            - MATCH PATTERNS: Use the same coding patterns, error handling, and structure as the rest of the codebase
            - PRESERVE INTERFACES: If fixing classes/interfaces, ensure they maintain compatibility with existing code
            - VERIFY DEPENDENCIES: Ensure fixes don't break dependencies or require changes in other files (unless explicitly needed)
            - MAINTAIN TYPES: If using TypeScript or typed languages, ensure types remain consistent
            - PRESERVE API: If fixing public APIs, ensure backward compatibility or update all callers consistently
            
            **FIX QUALITY REQUIREMENTS:**
            - Each fix must be complete and functional - no partial fixes
            - Each fix must include sufficient context (old_string) to uniquely identify the location
            - Each fix must be self-contained and not break other parts of the code
            - If a fix requires changes in multiple files, provide all necessary fixes
            - All fixes must work together as a cohesive system
            
            Format as JSON array:
            [
              {
                "file_path": "path/to/file.ext",
                "old_string": "exact code to replace with context (include 5-10 lines before and after for uniqueness)",
                "new_string": "complete fixed code",
                "confidence": "high|medium|low",
                "description": "What this fix does and why it maintains code coherence"
              }
            ]
            
            **VERIFICATION CHECKLIST:**
            Before providing fixes, verify:
            1. All fixes maintain consistency with existing code patterns
            2. All imports/exports remain correct and consistent
            3. All function/class signatures match what other files expect
            4. All fixes work together without conflicts
            5. All fixes are complete and functional
            6. No breaking changes unless explicitly required by the goal
            
            Be thorough and ensure all fixes are complete, correct, and maintain code coherence.
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
        
        // Parse fixes with better error handling
        val fixesJson = try {
            // Clean the response - remove any markdown code blocks
            var cleanedText = fixText
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            
            // Try to find JSON array
            val jsonStart = cleanedText.indexOf('[')
            val jsonEnd = cleanedText.lastIndexOf(']') + 1
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonSubstring = cleanedText.substring(jsonStart, jsonEnd)
                
                // Check for actual invalid JSON patterns (not just the presence of these strings)
                // Only flag if it's clearly invalid JSON, not if these are valid string values in description fields
                // Check if [object Undefined] appears outside of quoted strings (actual JSON error)
                val hasInvalidPattern = try {
                    // Try to parse first - if it fails, then check for patterns
                    JSONArray(jsonSubstring)
                    false // Valid JSON, no invalid pattern
                } catch (e: Exception) {
                    // JSON parsing failed, check if it's due to [object Undefined] pattern
                    // Only flag if [object appears at the start of array/object (not in description strings)
                    jsonSubstring.trimStart().startsWith("[object") || 
                    jsonSubstring.contains("[object Undefined]") && !jsonSubstring.contains("\"[object Undefined]\"") ||
                    (jsonSubstring.contains("[object") && !jsonSubstring.contains("\"[object") && jsonSubstring.indexOf("[object") < 50)
                }
                
                if (hasInvalidPattern) {
                    android.util.Log.w("GeminiClient", "Response contains invalid JSON pattern: ${jsonSubstring.take(200)}")
                    emit(GeminiStreamEvent.Chunk("⚠️ AI returned invalid JSON, trying to extract fixes manually...\n"))
                    onChunk("⚠️ AI returned invalid JSON, trying to extract fixes manually...\n")
                    
                    // Try to extract fixes from the text description
                    val extractedFixes = extractFixesFromText(fixText, workspaceRoot)
                    if (extractedFixes.isNotEmpty()) {
                        // Convert to JSONArray format
                        val fixesArray = JSONArray()
                        for (fix in extractedFixes) {
                            fixesArray.put(JSONObject().apply {
                                put("file_path", fix.first)
                                put("old_string", fix.second)
                                put("new_string", fix.third)
                                put("confidence", "medium")
                                put("description", "Extracted from AI response")
                            })
                        }
                        fixesArray
                    } else {
                        null
                    }
                } else {
                    // Try to parse the JSON - if it fails, then it's actually invalid
                    try {
                        JSONArray(jsonSubstring)
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "JSON parsing failed, trying text extraction: ${e.message}")
                        emit(GeminiStreamEvent.Chunk("⚠️ JSON parsing failed, extracting fixes from text...\n"))
                        onChunk("⚠️ JSON parsing failed, extracting fixes from text...\n")
                        
                        val extractedFixes = extractFixesFromText(fixText, workspaceRoot)
                        if (extractedFixes.isNotEmpty()) {
                            val fixesArray = JSONArray()
                            for (fix in extractedFixes) {
                                fixesArray.put(JSONObject().apply {
                                    put("file_path", fix.first)
                                    put("old_string", fix.second)
                                    put("new_string", fix.third)
                                    put("confidence", "medium")
                                    put("description", "Extracted from AI response after JSON parse failure")
                                })
                            }
                            fixesArray
                        } else {
                            null
                        }
                    }
                }
            } else {
                // No JSON array found, try to extract from text
                android.util.Log.w("GeminiClient", "No JSON array found in response, trying text extraction")
                emit(GeminiStreamEvent.Chunk("⚠️ No JSON array found, extracting fixes from text...\n"))
                onChunk("⚠️ No JSON array found, extracting fixes from text...\n")
                
                val extractedFixes = extractFixesFromText(fixText, workspaceRoot)
                if (extractedFixes.isNotEmpty()) {
                    val fixesArray = JSONArray()
                    for (fix in extractedFixes) {
                        fixesArray.put(JSONObject().apply {
                            put("file_path", fix.first)
                            put("old_string", fix.second)
                            put("new_string", fix.third)
                            put("confidence", "medium")
                            put("description", "Extracted from AI response")
                        })
                    }
                    fixesArray
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "Failed to parse fixes", e)
            android.util.Log.e("GeminiClient", "Response text: ${fixText.take(500)}")
            
            // Try to extract fixes from text as fallback
            emit(GeminiStreamEvent.Chunk("⚠️ JSON parsing failed, trying text extraction...\n"))
            onChunk("⚠️ JSON parsing failed, trying text extraction...\n")
            
            val extractedFixes = extractFixesFromText(fixText, workspaceRoot)
            if (extractedFixes.isNotEmpty()) {
                val fixesArray = JSONArray()
                for (fix in extractedFixes) {
                    fixesArray.put(JSONObject().apply {
                        put("file_path", fix.first)
                        put("old_string", fix.second)
                        put("new_string", fix.third)
                        put("confidence", "low")
                        put("description", "Extracted from AI response after JSON parse failure")
                    })
                }
                fixesArray
            } else {
                null
            }
        }
        
        if (fixesJson == null || fixesJson.length() == 0) {
            // Try one more time with a simpler approach - look for the actual error and suggest a fix
            emit(GeminiStreamEvent.Chunk("⚠️ Could not parse fixes, analyzing error to suggest fix...\n"))
            onChunk("⚠️ Could not parse fixes, analyzing error to suggest fix...\n")
            
            // Extract error message from user message or recent error logs
            val errorMessage = extractErrorMessageFromUserMessage(userMessage)
            val suggestedFix = analyzeErrorAndSuggestFix(errorMessage, workspaceRoot)
            if (suggestedFix != null) {
                val fixesArray = JSONArray()
                fixesArray.put(JSONObject().apply {
                    put("file_path", suggestedFix.first)
                    put("old_string", suggestedFix.second)
                    put("new_string", suggestedFix.third)
                    put("confidence", "high")
                    put("description", "Auto-generated fix based on error analysis")
                })
                
                emit(GeminiStreamEvent.Chunk("✅ Generated fix from error analysis\n"))
                onChunk("✅ Generated fix from error analysis\n")
                
                // Continue with the suggested fix
                val fixesJsonFinal = fixesArray
                
                // Apply the fix (code continues below)
                emit(GeminiStreamEvent.Chunk("✅ Generated ${fixesJsonFinal.length()} fix(es)\n"))
                onChunk("✅ Generated ${fixesJsonFinal.length()} fix(es)\n")
                
                // Update todos
                updatedTodos = currentTodos.map { todo ->
                    when {
                        todo.description == "Phase 4: Get fixes with assurance" -> todo.copy(status = TodoStatus.COMPLETED)
                        todo.description == "Phase 5: Apply fixes" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                        else -> todo
                    }
                }
                updateTodos(updatedTodos)
                
                // Apply the fix
                emit(GeminiStreamEvent.Chunk("✏️ Phase 5: Applying fix...\n"))
                onChunk("✏️ Phase 5: Applying fix...\n")
                
                val fix = fixesJsonFinal.getJSONObject(0)
                val filePath = fix.getString("file_path")
                val oldString = fix.getString("old_string")
                val newString = fix.getString("new_string")
                
                emit(GeminiStreamEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                onChunk("🔨 Applying fix to $filePath...\n")
                
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
                    emit(GeminiStreamEvent.Chunk("✅ Fix applied successfully\n"))
                    onChunk("✅ Fix applied successfully\n")
                } else {
                    emit(GeminiStreamEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                    onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                }
                
                emit(GeminiStreamEvent.Done)
                return@flow
            } else {
                emit(GeminiStreamEvent.Error("No fixes generated and could not analyze error"))
                return@flow
            }
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
            
            // Read the file first to help with matching
            val fileContent = try {
                val readResult = executeToolSync("read_file", mapOf("file_path" to filePath))
                if (readResult.error == null) readResult.llmContent else null
            } catch (e: Exception) {
                null
            }
            
            var successCount = 0
            var failCount = 0
            
            for (fix in fixes) {
                var oldString = fix.getString("old_string")
                val newString = fix.getString("new_string")
                val confidence = fix.optString("confidence", "medium")
                val description = fix.optString("description", "")
                
                // If we have file content and old_string doesn't match, try to find a better anchor
                if (fileContent != null && !fileContent.contains(oldString)) {
                    emit(GeminiStreamEvent.Chunk("⚠️ old_string not found, searching for better anchor...\n"))
                    onChunk("⚠️ old_string not found, searching for better anchor...\n")
                    
                    val improvedAnchor = findBetterAnchor(fileContent, oldString, newString)
                    if (improvedAnchor != null) {
                        oldString = improvedAnchor
                        emit(GeminiStreamEvent.Chunk("✅ Found better anchor\n"))
                        onChunk("✅ Found better anchor\n")
                    } else {
                        // If we can't find a good anchor, try to append the new content
                        if (newString.contains("exports.") || newString.contains("function ")) {
                            emit(GeminiStreamEvent.Chunk("⚠️ Using append strategy for function addition...\n"))
                            onChunk("⚠️ Using append strategy for function addition...\n")
                            
                            // Try to append at the end of the file
                            val lastLine = fileContent.lines().lastOrNull() ?: ""
                            oldString = lastLine
                            val appendNewString = if (lastLine.isNotEmpty() && !lastLine.endsWith("\n")) {
                                "\n\n$newString"
                            } else {
                                newString
                            }
                            
                            val appendEditCall = FunctionCall(
                                name = "edit",
                                args = mapOf(
                                    "file_path" to filePath,
                                    "old_string" to oldString,
                                    "new_string" to (oldString + appendNewString)
                                )
                            )
                            
                            emit(GeminiStreamEvent.ToolCall(appendEditCall))
                            onToolCall(appendEditCall)
                            
                            val appendResult = try {
                                executeToolSync("edit", appendEditCall.args)
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
                            
                            emit(GeminiStreamEvent.ToolResult("edit", appendResult))
                            onToolResult("edit", appendEditCall.args)
                            
                            if (appendResult.error == null) {
                                successCount++
                                continue
                            }
                        }
                    }
                }
                
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
                    emit(GeminiStreamEvent.Chunk("❌ Fix failed: ${editResult.error?.message}\n"))
                    onChunk("❌ Fix failed: ${editResult.error?.message}\n")
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
        
        // Phase 6: Validation and testing with retry loop
        var validationAttempt = 0
        val maxValidationAttempts = 5
        var workComplete = false
        
        // Add validation phase to todos
        val todosWithValidation = currentTodos + listOf(
            Todo("Phase 6: Validate fixes and test", TodoStatus.PENDING),
            Todo("Phase 7: Retry fixes if needed", TodoStatus.PENDING)
        )
        updateTodos(todosWithValidation)
        
        while (!workComplete && validationAttempt < maxValidationAttempts && signal?.isAborted() != true) {
            validationAttempt++
            
            if (validationAttempt > 1) {
                emit(GeminiStreamEvent.Chunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n"))
                onChunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n")
            } else {
                emit(GeminiStreamEvent.Chunk("\n✅ Phase 6: Validating fixes...\n"))
                onChunk("\n✅ Phase 6: Validating fixes...\n")
            }
            
            updatedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 6: Validate fixes and test" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    todo.description == "Phase 7: Retry fixes if needed" -> todo.copy(status = if (validationAttempt > 1) TodoStatus.IN_PROGRESS else TodoStatus.PENDING)
                    else -> todo
                }
            }
            updateTodos(updatedTodos)
            
            // Run tests if available
            val testCommands = detectTestCommands(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk)
            var hasTestFailures = false
            var testFailures = mutableListOf<Pair<String, ToolResult>>()
            
            if (testCommands.isNotEmpty()) {
                emit(GeminiStreamEvent.Chunk("🧪 Running tests to validate fixes...\n"))
                onChunk("🧪 Running tests to validate fixes...\n")
                
                for (command in testCommands) {
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        hasTestFailures = true
                        testFailures.add(Pair(command.primaryCommand, ToolResult(
                            llmContent = "Command execution failed",
                            returnDisplay = "Failed",
                            error = ToolError(
                                message = "Command could not be executed",
                                type = ToolErrorType.EXECUTION_ERROR
                            )
                        )))
                        emit(GeminiStreamEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Test failed: ${command.primaryCommand}\n")
                        continue
                    }
                    
                    // Execute the command to get actual result
                    val shellCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to command.primaryCommand,
                            "description" to "Run test: ${command.description}",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(shellCall))
                    onToolCall(shellCall)
                    
                    val result = try {
                        executeToolSync("shell", shellCall.args)
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
                    
                    emit(GeminiStreamEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    val isFailure = result.error != null || 
                        result.llmContent.contains("FAILED", ignoreCase = true) ||
                        (result.llmContent.contains("failed", ignoreCase = true) && 
                         result.llmContent.contains("test", ignoreCase = true)) ||
                        result.llmContent.contains("AssertionError", ignoreCase = true)
                    
                    if (isFailure) {
                        hasTestFailures = true
                        testFailures.add(Pair(command.primaryCommand, result))
                        emit(GeminiStreamEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Test failed: ${command.primaryCommand}\n")
                    } else {
                        emit(GeminiStreamEvent.Chunk("✅ Test passed: ${command.primaryCommand}\n"))
                        onChunk("✅ Test passed: ${command.primaryCommand}\n")
                    }
                }
            }
            
            // Check if work is complete
            if (!hasTestFailures) {
                workComplete = true
                emit(GeminiStreamEvent.Chunk("\n✅ All tests passing! Debug/upgrade complete.\n"))
                onChunk("\n✅ All tests passing! Debug/upgrade complete.\n")
                
                updatedTodos = currentTodos.map { todo ->
                    when {
                        todo.description == "Phase 6: Validate fixes and test" -> todo.copy(status = TodoStatus.COMPLETED)
                        todo.description == "Phase 7: Retry fixes if needed" -> todo.copy(status = TodoStatus.COMPLETED)
                        else -> todo
                    }
                }
                updateTodos(updatedTodos)
                break
            }
            
            // If failures, retry fixes
            if (hasTestFailures && validationAttempt < maxValidationAttempts) {
                emit(GeminiStreamEvent.Chunk("\n🔧 Phase 7: Analyzing test failures and generating fixes...\n"))
                onChunk("\n🔧 Phase 7: Analyzing test failures and generating fixes...\n")
                
                updatedTodos = currentTodos.map { todo ->
                    if (todo.description == "Phase 7: Retry fixes if needed") {
                        todo.copy(status = TodoStatus.IN_PROGRESS)
                    } else {
                        todo
                    }
                }
                updateTodos(updatedTodos)
                
                // Collect failure info and generate fixes (reuse fix logic from above)
                val failureInfo = buildString {
                    testFailures.forEach { (cmd, result) ->
                        append("- Command: $cmd\n")
                        append("  Error: ${result.error?.message ?: "Test failure"}\n")
                        append("  Output: ${result.llmContent.take(800)}\n\n")
                    }
                }
                
                val projectStructure = extractProjectStructure(workspaceRoot, signal, ::emitEvent, onChunk)
                
                val fixPrompt = """
                    $systemContext
                    
                    **Project Goal:** $userMessage
                    
                    **Test Failures After Fixes:**
                    $failureInfo
                    
                    **Project Structure:**
                    $projectStructure
                    
                    Analyze the test failures and provide additional fixes. For each fix, provide:
                    1. The file path
                    2. The exact old_string to replace (include enough context)
                    3. The exact new_string (complete fixed code)
                    4. Confidence level (high/medium/low)
                    
                    **CRITICAL CODE COHERENCE REQUIREMENTS FOR FIXES:**
                    - MAINTAIN CONSISTENCY: All fixes must maintain consistency with the existing codebase patterns, style, and conventions
                    - PRESERVE IMPORTS: Ensure all imports remain correct and match what the fixed code uses
                    - PRESERVE EXPORTS: Ensure all exports remain consistent with what other files expect
                    - MATCH SIGNATURES: If fixing function/class signatures, ensure they match what other files call/import
                    - MATCH PATTERNS: Use the same coding patterns, error handling, and structure as the rest of the codebase
                    
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
                
                val fixText = makeApiCallWithRetryAndCorrection(
                    model, fixRequest, "fixes", signal, null, ::emitEvent, onChunk
                )
                
                if (fixText != null) {
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
                    
                    if (fixesJson != null && fixesJson.length() > 0) {
                        emit(GeminiStreamEvent.Chunk("✅ Generated ${fixesJson.length()} additional fix(es)\n"))
                        onChunk("✅ Generated ${fixesJson.length()} additional fix(es)\n")
                        
                        // Apply fixes (reuse existing fix application logic)
                        for (i in 0 until fixesJson.length()) {
                            val fix = fixesJson.getJSONObject(i)
                            val filePath = fix.getString("file_path")
                            val oldString = fix.getString("old_string")
                            val newString = fix.getString("new_string")
                            
                            emit(GeminiStreamEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                            onChunk("🔨 Applying fix to $filePath...\n")
                            
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
                                emit(GeminiStreamEvent.Chunk("✅ Fix applied successfully\n"))
                                onChunk("✅ Fix applied successfully\n")
                            } else {
                                emit(GeminiStreamEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                                onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                            }
                        }
                    }
                }
            } else if (validationAttempt >= maxValidationAttempts) {
                emit(GeminiStreamEvent.Chunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n"))
                onChunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n")
                break
            }
        }
        
        if (workComplete) {
            emit(GeminiStreamEvent.Chunk("\n✨ Debug/upgrade complete and validated!\n"))
            onChunk("\n✨ Debug/upgrade complete and validated!\n")
        } else {
            emit(GeminiStreamEvent.Chunk("\n⚠️ Debug/upgrade completed with some issues remaining\n"))
            onChunk("\n⚠️ Debug/upgrade completed with some issues remaining\n")
        }
        
        emit(GeminiStreamEvent.Done)
    }
    
    /**
     * Test-only flow: Run tests, API tests, and fix issues
     * 1. Detect and run test commands
     * 2. Run API tests if applicable
     * 3. Analyze failures
     * 4. Make fixes
     * 5. Re-run tests to verify
     */
    private suspend fun sendMessageTestOnly(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
        val signal = CancellationSignal()
        android.util.Log.d("GeminiClient", "sendMessageTestOnly: Starting test-only flow")
        
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
        
        // Helper function to wrap emit as suspend function
        suspend fun emitEvent(event: GeminiStreamEvent) {
            emit(event)
        }
        
        // Initialize todos
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
        
        val initialTodos = listOf(
            Todo("Phase 1: Detect and run test commands", TodoStatus.PENDING),
            Todo("Phase 2: Run API tests (if applicable)", TodoStatus.PENDING),
            Todo("Phase 3: Analyze test failures", TodoStatus.PENDING),
            Todo("Phase 4: Fix issues", TodoStatus.PENDING),
            Todo("Phase 5: Re-run tests to verify", TodoStatus.PENDING)
        )
        updateTodos(initialTodos)
        
        // Phase 1: Detect and run test commands
        emit(GeminiStreamEvent.Chunk("🧪 Phase 1: Detecting and running test commands...\n"))
        onChunk("🧪 Phase 1: Detecting and running test commands...\n")
        
        var updatedTodos = currentTodos.map { todo ->
            if (todo.description == "Phase 1: Detect and run test commands") {
                todo.copy(status = TodoStatus.IN_PROGRESS)
            } else {
                todo
            }
        }
        updateTodos(updatedTodos)
        
        // Detect test commands using AI
        val testCommands = detectTestCommands(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk)
        
        var testResults = mutableListOf<Pair<String, ToolResult>>() // command -> result
        var hasTestFailures = false
        
        if (testCommands.isNotEmpty()) {
            emit(GeminiStreamEvent.Chunk("📋 Found ${testCommands.size} test command(s) to run\n"))
            onChunk("📋 Found ${testCommands.size} test command(s) to run\n")
            
            for (command in testCommands) {
                emit(GeminiStreamEvent.Chunk("▶️ Running: ${command.primaryCommand}\n"))
                onChunk("▶️ Running: ${command.primaryCommand}\n")
                
                // First ensure command can run (with fallbacks)
                val canRun = executeCommandWithFallbacks(
                    command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                )
                
                if (!canRun) {
                    hasTestFailures = true
                    testResults.add(Pair(command.primaryCommand, ToolResult(
                        llmContent = "Command execution failed",
                        returnDisplay = "Failed",
                        error = ToolError(
                            message = "Command could not be executed",
                            type = ToolErrorType.EXECUTION_ERROR
                        )
                    )))
                    emit(GeminiStreamEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                    onChunk("❌ Test failed: ${command.primaryCommand}\n")
                    continue
                }
                
                // Execute the command to get actual result
                val shellCall = FunctionCall(
                    name = "shell",
                    args = mapOf(
                        "command" to command.primaryCommand,
                        "description" to "Run test: ${command.description}",
                        "dir_path" to workspaceRoot
                    )
                )
                emit(GeminiStreamEvent.ToolCall(shellCall))
                onToolCall(shellCall)
                
                val result = try {
                    executeToolSync("shell", shellCall.args)
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
                
                emit(GeminiStreamEvent.ToolResult("shell", result))
                onToolResult("shell", shellCall.args)
                
                testResults.add(Pair(command.primaryCommand, result))
                
                // Check if test failed
                if (result.error != null || 
                    (result.llmContent.contains("FAILED", ignoreCase = true)) ||
                    (result.llmContent.contains("failed", ignoreCase = true) && 
                     result.llmContent.contains("test", ignoreCase = true)) ||
                    (result.llmContent.contains("error", ignoreCase = true) && 
                     result.llmContent.contains("test", ignoreCase = true))) {
                    hasTestFailures = true
                    emit(GeminiStreamEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                    onChunk("❌ Test failed: ${command.primaryCommand}\n")
                } else {
                    emit(GeminiStreamEvent.Chunk("✅ Test passed: ${command.primaryCommand}\n"))
                    onChunk("✅ Test passed: ${command.primaryCommand}\n")
                }
            }
        } else {
            emit(GeminiStreamEvent.Chunk("ℹ️ No test commands detected\n"))
            onChunk("ℹ️ No test commands detected\n")
        }
        
        updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 1: Detect and run test commands" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 2: Run API tests (if applicable)" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Phase 2: Run API tests if applicable
        emit(GeminiStreamEvent.Chunk("\n🌐 Phase 2: Running API tests (if applicable)...\n"))
        onChunk("\n🌐 Phase 2: Running API tests (if applicable)...\n")
        
        val isWebFramework = detectWebFramework(workspaceRoot)
        val isKotlinJava = detectKotlinJava(workspaceRoot)
        var apiTestResult = true
        val messageLower = userMessage.lowercase()
        val wantsApiTest = messageLower.contains("api") || 
                          messageLower.contains("endpoint") || 
                          messageLower.contains("test api") ||
                          messageLower.contains("test endpoint")
        
        if (isWebFramework && !isKotlinJava) {
            if (wantsApiTest || testCommands.isEmpty()) {
                apiTestResult = testAPIs(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk, onToolCall, onToolResult)
                if (!apiTestResult) {
                    hasTestFailures = true
                }
            } else {
                emit(GeminiStreamEvent.Chunk("ℹ️ Skipping API tests (unit tests were run)\n"))
                onChunk("ℹ️ Skipping API tests (unit tests were run)\n")
            }
        } else {
            if (isKotlinJava) {
                emit(GeminiStreamEvent.Chunk("ℹ️ Skipping API testing for Kotlin/Java projects\n"))
                onChunk("ℹ️ Skipping API testing for Kotlin/Java projects\n")
            } else {
                emit(GeminiStreamEvent.Chunk("ℹ️ No web framework detected, skipping API testing\n"))
                onChunk("ℹ️ No web framework detected, skipping API testing\n")
            }
        }
        
        updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 2: Run API tests (if applicable)" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 3: Analyze test failures" -> if (hasTestFailures || !apiTestResult) todo.copy(status = TodoStatus.IN_PROGRESS) else todo.copy(status = TodoStatus.COMPLETED)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        // Early exit if no test commands detected and no API tests needed
        val needsApiTest = isWebFramework && !isKotlinJava && (wantsApiTest || testCommands.isEmpty())
        if (testCommands.isEmpty() && !needsApiTest) {
            emit(GeminiStreamEvent.Chunk("\n✅ No tests to run. Task complete.\n"))
            onChunk("\n✅ No tests to run. Task complete.\n")
            
            updatedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 1: Detect and run test commands" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 2: Run API tests (if applicable)" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 3: Analyze test failures" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 5: Re-run tests to verify" -> todo.copy(status = TodoStatus.COMPLETED)
                    else -> todo
                }
            }
            updateTodos(updatedTodos)
            
            emit(GeminiStreamEvent.Chunk("\n✨ Test flow complete!\n"))
            onChunk("\n✨ Test flow complete!\n")
            emit(GeminiStreamEvent.Done)
            return@flow
        }
        
        // Phase 3: Analyze test failures
        if (hasTestFailures || !apiTestResult) {
            emit(GeminiStreamEvent.Chunk("\n🔍 Phase 3: Analyzing test failures...\n"))
            onChunk("\n🔍 Phase 3: Analyzing test failures...\n")
            
            // Collect all failure information
            val failureInfo = buildString {
                append("Test Failures Summary:\n\n")
                testResults.forEach { (cmd, result) ->
                    if (result.error != null || 
                        result.llmContent.contains("FAILED", ignoreCase = true) ||
                        (result.llmContent.contains("failed", ignoreCase = true) && 
                         result.llmContent.contains("test", ignoreCase = true))) {
                        append("Command: $cmd\n")
                        append("Error: ${result.error?.message ?: "Test failure"}\n")
                        append("Output: ${result.llmContent.take(1000)}\n\n")
                    }
                }
                if (!apiTestResult) {
                    append("API Testing: Failed\n")
                }
            }
            
            emit(GeminiStreamEvent.Chunk("📊 Failure Analysis:\n$failureInfo\n"))
            onChunk("📊 Failure Analysis:\n$failureInfo\n")
            
            updatedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 3: Analyze test failures" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    else -> todo
                }
            }
            updateTodos(updatedTodos)
            
            // Phase 4: Fix issues
            emit(GeminiStreamEvent.Chunk("\n🔧 Phase 4: Fixing issues...\n"))
            onChunk("\n🔧 Phase 4: Fixing issues...\n")
            
            // Use reverse flow to analyze and fix
            val projectStructure = extractProjectStructure(workspaceRoot, signal, ::emitEvent, onChunk)
            
            val fixPrompt = """
                $systemContext
                
                **Test Failures:**
                $failureInfo
                
                **Project Structure:**
                $projectStructure
                
                **User Request:** $userMessage
                
                Analyze the test failures and provide fixes. For each fix, provide:
                1. The file path
                2. The exact old_string to replace (include enough context - at least 5-10 lines before and after)
                3. The exact new_string (complete fixed code)
                4. Confidence level (high/medium/low)
                
                **CRITICAL CODE COHERENCE REQUIREMENTS FOR FIXES:**
                - MAINTAIN CONSISTENCY: All fixes must maintain consistency with the existing codebase patterns, style, and conventions
                - PRESERVE IMPORTS: Ensure all imports remain correct and match what the fixed code uses
                - PRESERVE EXPORTS: Ensure all exports remain consistent with what other files expect
                - MATCH SIGNATURES: If fixing function/class signatures, ensure they match what other files call/import
                - MATCH PATTERNS: Use the same coding patterns, error handling, and structure as the rest of the codebase
                
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
            
            val fixText = try {
                makeApiCallWithRetryAndCorrection(
                    model, fixRequest, "fixes", signal, null, ::emitEvent, onChunk
                )
            } catch (e: Exception) {
                android.util.Log.e("GeminiClient", "Failed to generate fixes: ${e.message}", e)
                emit(GeminiStreamEvent.Chunk("⚠️ Error generating fixes: ${e.message}\n"))
                onChunk("⚠️ Error generating fixes: ${e.message}\n")
                null
            }
            
            if (fixText != null && fixText.isNotBlank()) {
                // Parse fixes (similar to reverse flow)
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
                
                if (fixesJson != null && fixesJson.length() > 0) {
                    emit(GeminiStreamEvent.Chunk("✅ Generated ${fixesJson.length()} fix(es)\n"))
                    onChunk("✅ Generated ${fixesJson.length()} fix(es)\n")
                    
                    // Apply fixes
                    for (i in 0 until fixesJson.length()) {
                        val fix = fixesJson.getJSONObject(i)
                        val filePath = fix.getString("file_path")
                        val oldString = fix.getString("old_string")
                        val newString = fix.getString("new_string")
                        
                        emit(GeminiStreamEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                        onChunk("🔨 Applying fix to $filePath...\n")
                        
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
                            emit(GeminiStreamEvent.Chunk("✅ Fix applied successfully\n"))
                            onChunk("✅ Fix applied successfully\n")
                        } else {
                            emit(GeminiStreamEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                            onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                        }
                    }
                } else {
                    emit(GeminiStreamEvent.Chunk("⚠️ Could not generate fixes\n"))
                    onChunk("⚠️ Could not generate fixes\n")
                }
            }
            
            updatedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 5: Re-run tests to verify" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                    else -> todo
                }
            }
            updateTodos(updatedTodos)
            
            // Phase 5: Re-run tests to verify
            emit(GeminiStreamEvent.Chunk("\n🔄 Phase 5: Re-running tests to verify fixes...\n"))
            onChunk("\n🔄 Phase 5: Re-running tests to verify fixes...\n")
            
            // Re-run test commands
            if (testCommands.isNotEmpty()) {
                var allPassed = true
                for (command in testCommands) {
                    emit(GeminiStreamEvent.Chunk("▶️ Re-running: ${command.primaryCommand}\n"))
                    onChunk("▶️ Re-running: ${command.primaryCommand}\n")
                    
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        allPassed = false
                        emit(GeminiStreamEvent.Chunk("❌ Test still failing: ${command.primaryCommand}\n"))
                        onChunk("❌ Test still failing: ${command.primaryCommand}\n")
                        continue
                    }
                    
                    // Execute the command to get actual result
                    val shellCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to command.primaryCommand,
                            "description" to "Re-run test: ${command.description}",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(GeminiStreamEvent.ToolCall(shellCall))
                    onToolCall(shellCall)
                    
                    val result = try {
                        executeToolSync("shell", shellCall.args)
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
                    
                    emit(GeminiStreamEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    if (result.error != null || 
                        (result.llmContent.contains("FAILED", ignoreCase = true)) ||
                        (result.llmContent.contains("failed", ignoreCase = true) && 
                         result.llmContent.contains("test", ignoreCase = true))) {
                        allPassed = false
                        emit(GeminiStreamEvent.Chunk("❌ Test still failing: ${command.primaryCommand}\n"))
                        onChunk("❌ Test still failing: ${command.primaryCommand}\n")
                    } else {
                        emit(GeminiStreamEvent.Chunk("✅ Test passed: ${command.primaryCommand}\n"))
                        onChunk("✅ Test passed: ${command.primaryCommand}\n")
                    }
                }
                
                if (allPassed) {
                    emit(GeminiStreamEvent.Chunk("\n✅ All tests passing after fixes!\n"))
                    onChunk("\n✅ All tests passing after fixes!\n")
                } else {
                    emit(GeminiStreamEvent.Chunk("\n⚠️ Some tests still failing\n"))
                    onChunk("\n⚠️ Some tests still failing\n")
                }
            }
            
            // Re-run API tests if applicable
            if (isWebFramework && !isKotlinJava && !apiTestResult) {
                emit(GeminiStreamEvent.Chunk("🔄 Re-running API tests...\n"))
                onChunk("🔄 Re-running API tests...\n")
                
                val retestResult = testAPIs(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk, onToolCall, onToolResult)
                if (retestResult) {
                    emit(GeminiStreamEvent.Chunk("✅ API tests passing after fixes!\n"))
                    onChunk("✅ API tests passing after fixes!\n")
                } else {
                    emit(GeminiStreamEvent.Chunk("⚠️ API tests still failing\n"))
                    onChunk("⚠️ API tests still failing\n")
                }
            }
        } else {
            // No failures detected - all tests passed
            emit(GeminiStreamEvent.Chunk("\n✅ All tests passed! No fixes needed.\n"))
            onChunk("\n✅ All tests passed! No fixes needed.\n")
            
            updatedTodos = currentTodos.map { todo ->
                when {
                    todo.description == "Phase 3: Analyze test failures" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 4: Fix issues" -> todo.copy(status = TodoStatus.COMPLETED)
                    todo.description == "Phase 5: Re-run tests to verify" -> todo.copy(status = TodoStatus.COMPLETED)
                    else -> todo
                }
            }
            updateTodos(updatedTodos)
        }
        
        // Final todo update
        updatedTodos = currentTodos.map { todo ->
            if (todo.description == "Phase 5: Re-run tests to verify" && todo.status != TodoStatus.COMPLETED) {
                todo.copy(status = TodoStatus.COMPLETED)
            } else {
                todo
            }
        }
        updateTodos(updatedTodos)
        
        emit(GeminiStreamEvent.Chunk("\n✨ Test flow complete!\n"))
        onChunk("\n✨ Test flow complete!\n")
        emit(GeminiStreamEvent.Done)
    }
    
    /**
     * Detect test commands needed based on project structure and user message
     */
    private suspend fun detectTestCommands(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<CommandWithFallbacks> {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return emptyList()
        
        // Get list of files
        val files = workspaceDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.relativeTo(workspaceDir).path }
            .take(50)
            .toList()
        
        val systemContext = SystemInfoService.generateSystemContext()
        
        val installCmd = when (systemInfo.packageManager.lowercase()) {
            "apk" -> "apk add"
            "apt", "apt-get" -> "apt-get install -y"
            "yum" -> "yum install -y"
            "dnf" -> "dnf install -y"
            "pacman" -> "pacman -S --noconfirm"
            else -> systemInfo.packageManagerCommands["install"] ?: "apk add"
        }
        
        val updateCmd = when (systemInfo.packageManager.lowercase()) {
            "apk" -> "apk update"
            "apt", "apt-get" -> "apt-get update"
            "yum" -> "yum update -y"
            "dnf" -> "dnf update -y"
            "pacman" -> "pacman -Sy"
            else -> systemInfo.packageManagerCommands["update"] ?: "apk update"
        }
        
        val testDetectionPrompt = """
            $systemContext
            
            Analyze the project structure and user request to determine what TEST commands need to be executed.
            
            Files in workspace:
            ${files.joinToString("\n") { "- $it" }}
            
            User request: $userMessage
            
            Based on the files and user request, determine:
            1. What test commands should be run? (e.g., "npm test", "pytest", "jest", "python -m pytest", etc.)
            2. What dependencies need to be installed first?
            3. What fallback commands are needed if test tools are missing?
            
            Focus ONLY on test commands. Do not include build, start, or other non-test commands.
            
            For each test command needed, provide:
            - primary_command: The main test command to execute
            - description: What this test command does
            - check_command: Command to check if test tool is available
            - fallback_commands: List of commands to run if tool is missing (install test dependencies, etc.)
              - First: Install dependencies (e.g., "npm install", "pip install -r requirements.txt")
              - Second: Install tool via package manager (e.g., "$installCmd nodejs npm")
              - Third: Update package manager and install (e.g., "$updateCmd && $installCmd nodejs npm")
            
            Format as JSON array:
            [
              {
                "primary_command": "npm test",
                "description": "Run npm test suite",
                "check_command": "npm --version",
                "fallback_commands": [
                  "npm install",
                  "$installCmd nodejs npm",
                  "$updateCmd && $installCmd nodejs npm"
                ]
              }
            ]
            
            Only include test commands that are actually needed. If no test commands are needed, return an empty array [].
        """.trimIndent()
        
        val model = ApiProviderManager.getCurrentModel()
        val request = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", testDetectionPrompt)
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
        
        return try {
            val response = makeApiCallSimple(
                ApiProviderManager.getNextApiKey() ?: return emptyList(),
                model,
                request,
                useLongTimeout = false
            )
            
            // Parse JSON response
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonArray = JSONArray(response.substring(jsonStart, jsonEnd))
                val detectedCommands = mutableListOf<CommandWithFallbacks>()
                
                for (i in 0 until jsonArray.length()) {
                    try {
                        val cmdObj = jsonArray.getJSONObject(i)
                        val primaryCmd = cmdObj.getString("primary_command")
                        val description = cmdObj.optString("description", "Run test command")
                        val checkCmd = cmdObj.optString("check_command", null)
                        val fallbacks = cmdObj.optJSONArray("fallback_commands")?.let { array ->
                            (0 until array.length()).mapNotNull { array.optString(it, null) }
                        } ?: emptyList()
                        
                        detectedCommands.add(CommandWithFallbacks(
                            primaryCommand = primaryCmd,
                            description = description,
                            fallbacks = fallbacks,
                            checkCommand = checkCmd.takeIf { it.isNotBlank() },
                            installCheck = null
                        ))
                    } catch (e: Exception) {
                        android.util.Log.w("GeminiClient", "Failed to parse test command: ${e.message}")
                    }
                }
                
                detectedCommands
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "AI test command detection failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Detect commands to run based on user request (e.g., init-db, npm scripts, etc.)
     */
    private suspend fun detectCommandsToRun(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (GeminiStreamEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<CommandWithFallbacks> {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return emptyList()
        
        // Get list of files
        val files = workspaceDir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.relativeTo(workspaceDir).path }
            .take(50)
            .toList()
        
        val systemContext = SystemInfoService.generateSystemContext()
        
        val installCmd = when (systemInfo.packageManager.lowercase()) {
            "apk" -> "apk add"
            "apt", "apt-get" -> "apt-get install -y"
            "yum" -> "yum install -y"
            "dnf" -> "dnf install -y"
            "pacman" -> "pacman -S --noconfirm"
            else -> systemInfo.packageManagerCommands["install"] ?: "apk add"
        }
        
        val updateCmd = when (systemInfo.packageManager.lowercase()) {
            "apk" -> "apk update"
            "apt", "apt-get" -> "apt-get update"
            "yum" -> "yum update -y"
            "dnf" -> "dnf update -y"
            "pacman" -> "pacman -Sy"
            else -> systemInfo.packageManagerCommands["update"] ?: "apk update"
        }
        
        // Check package.json for scripts
        val packageJson = File(workspaceDir, "package.json")
        var packageScripts = ""
        if (packageJson.exists()) {
            try {
                val packageContent = packageJson.readText()
                packageScripts = packageContent
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        val commandDetectionPrompt = """
            $systemContext
            
            Analyze the project structure and user request to determine what commands need to be executed.
            
            Files in workspace:
            ${files.joinToString("\n") { "- $it" }}
            
            ${if (packageScripts.isNotEmpty()) "package.json scripts:\n$packageScripts\n" else ""}
            
            User request: $userMessage
            
            Based on the files, package.json scripts (if any), and user request, determine:
            1. What commands should be run? (e.g., "npm run init-db", "npm run init-db", "python manage.py migrate", etc.)
            2. What dependencies need to be installed first?
            3. What fallback commands are needed if tools are missing?
            
            Focus on commands mentioned in the user request (e.g., "init-db", "init db", "run init-db", etc.).
            Look for npm scripts, python commands, or other project-specific commands.
            
            For each command needed, provide:
            - primary_command: The main command to execute (e.g., "npm run init-db")
            - description: What this command does
            - check_command: Command to check if tool is available (e.g., "npm --version")
            - fallback_commands: List of commands to run if tool is missing
              - First: Install dependencies (e.g., "npm install")
              - Second: Install tool via package manager (e.g., "$installCmd nodejs npm")
              - Third: Update package manager and install (e.g., "$updateCmd && $installCmd nodejs npm")
            
            Format as JSON array:
            [
              {
                "primary_command": "npm run init-db",
                "description": "Initialize database",
                "check_command": "npm --version",
                "fallback_commands": [
                  "npm install",
                  "$installCmd nodejs npm",
                  "$updateCmd && $installCmd nodejs npm"
                ]
              }
            ]
            
            Only include commands that are actually needed based on the user request. If no commands are needed, return an empty array [].
        """.trimIndent()
        
        val model = ApiProviderManager.getCurrentModel()
        val request = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", commandDetectionPrompt)
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
        
        return try {
            val apiKey = ApiProviderManager.getNextApiKey() ?: return emptyList()
            val response = makeApiCallSimple(
                apiKey,
                model,
                request,
                useLongTimeout = false
            )
            
            if (response != null) {
                val jsonStart = response.indexOf('[')
                val jsonEnd = response.lastIndexOf(']') + 1
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonArray = JSONArray(response.substring(jsonStart, jsonEnd))
                    val detectedCommands = mutableListOf<CommandWithFallbacks>()
                    
                    for (i in 0 until jsonArray.length()) {
                        try {
                            val cmdObj = jsonArray.getJSONObject(i)
                            val primaryCmd = cmdObj.getString("primary_command")
                            val description = cmdObj.optString("description", "Run command")
                            val checkCmd = cmdObj.optString("check_command", null)
                            val fallbacks = cmdObj.optJSONArray("fallback_commands")?.let { array ->
                                (0 until array.length()).mapNotNull { array.optString(it, null) }
                            } ?: emptyList()
                            
                            detectedCommands.add(CommandWithFallbacks(
                                primaryCommand = primaryCmd,
                                description = description,
                                fallbacks = fallbacks,
                                checkCommand = checkCmd.takeIf { it.isNotBlank() },
                                installCheck = null
                            ))
                        } catch (e: Exception) {
                            android.util.Log.w("GeminiClient", "Failed to parse command: ${e.message}")
                        }
                    }
                    
                    detectedCommands
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiClient", "AI command detection failed: ${e.message}")
            emptyList()
        }
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
        // Use makeApiCall in non-streaming mode by collecting all chunks
        val chunks = mutableListOf<String>()
        val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>()
        
        try {
            val finishReason = makeApiCall(
                apiKey,
                model,
                requestBody,
                onChunk = { chunk -> chunks.add(chunk) },
                onToolCall = { /* Ignore tool calls in simple mode */ },
                onToolResult = { _, _ -> /* Ignore tool results in simple mode */ },
                toolCallsToExecute = toolCallsToExecute
            )
            
            // Return all collected chunks as a single string
            return chunks.joinToString("")
        } catch (e: Exception) {
            android.util.Log.e("GeminiClient", "makeApiCallSimple: Error: ${e.message}", e)
            throw e
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
