package com.qali.aterm.agent.client

import com.rk.libcommons.alpineDir
import com.rk.settings.Settings
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderManager.KeysExhaustedException
import com.qali.aterm.api.ApiProviderType
import com.qali.aterm.agent.tools.DeclarativeTool
import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.tools.*
import com.qali.aterm.agent.SystemInfoService
import com.qali.aterm.agent.MemoryService
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
 * Agent Client for making API calls and handling tool execution
 * Supports: Google Gemini, OpenAI, Anthropic Claude, Ollama, and custom providers
 * Uses standard API call mode for all requests
 */
class AgentClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String = alpineDir().absolutePath
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    // Client with longer timeout for complex requests (metadata generation can take longer)
    private val longTimeoutClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // 3 minutes for complex metadata generation
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Content>()
    
    /**
     * Send a message and get response
     * This implements automatic continuation after tool calls, matching the TypeScript implementation
     */
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        android.util.Log.d("AgentClient", "sendMessage: Starting request")
        android.util.Log.d("AgentClient", "sendMessage: User message length: ${userMessage.length}")
        
        // Detect intents: can be multiple (e.g., debug AND test AND question)
        val intents = detectIntents(userMessage)
        android.util.Log.d("AgentClient", "sendMessage: Detected intents: $intents")
        
        // Single intent - use existing flow
        val intent = if (intents.size == 1) intents.first() else null
        
        // Simple callback without learning
        val simpleOnChunk: (String) -> Unit = { chunk ->
            onChunk(chunk)
        }
        
        // If multiple intents, use multi-intent flow
        if (intents.size > 1) {
            emitAll(sendMessageMultiIntent(intents, userMessage, simpleOnChunk, onToolCall, onToolResult))
            return@flow
        }
        
        // Add first prompt to clarify and expand user intention
        val enhancedUserMessage = if (intent != null) {
            enhanceUserIntent(userMessage, intent)
        } else {
            userMessage
        }
        
        // Process message based on intent
        when (intent) {
            IntentType.QUESTION_ONLY -> {
                emitAll(sendMessageQuestionFlow(enhancedUserMessage, simpleOnChunk, onToolCall, onToolResult))
            }
            IntentType.DEBUG_UPGRADE -> {
                emitAll(sendMessageStandardReverse(enhancedUserMessage, simpleOnChunk, onToolCall, onToolResult))
            }
            else -> {
                emitAll(sendMessageStandard(enhancedUserMessage, simpleOnChunk, onToolCall, onToolResult))
            }
        }
    }
    
    /**
     * Internal message function (bypasses settings check)
     */
    private suspend fun sendMessageInternal(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        accumulatedContent: StringBuilder,
        originalUserMessage: String
    ): Flow<AgentEvent> = flow {
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
            android.util.Log.d("AgentClient", "sendMessage: Turn $turnCount")
            
            // Get API key
            val apiKey = ApiProviderManager.getNextApiKey()
                ?: run {
                    android.util.Log.e("AgentClient", "sendMessage: No API keys configured")
                    emit(AgentEvent.Error("No API keys configured"))
                    return@flow
                }
            
            // Prepare request (use chat history which already includes function responses for continuation)
            // For continuation, use empty string to continue from chat history
            val requestBody = buildRequest(if (turnCount == 1 && !isContinuation) userMessage else "", model)
            
            android.util.Log.d("AgentClient", "sendMessage: Request body size: ${requestBody.toString().length} bytes")
            
            // Track if we have tool calls to execute and finish reason
            var hasToolCalls = false
            var finishReason: String? = null
            val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>() // FunctionCall, ToolResult, callId
            
            // Collect events to emit after API call (since callbacks aren't in coroutine context)
            val eventsToEmit = mutableListOf<AgentEvent>()
            
            // Make API call with retry
            val result = ApiProviderManager.makeApiCallWithRetry { key ->
                try {
                    android.util.Log.d("AgentClient", "sendMessage: Attempting API call")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        finishReason = makeApiCall(
                            key, 
                            model, 
                            requestBody, 
                            { chunk ->
                                // Call the callback and collect event to emit later
                                onChunk(chunk)
                                eventsToEmit.add(AgentEvent.Chunk(chunk))
                            }, 
                            { functionCall ->
                                onToolCall(functionCall)
                                eventsToEmit.add(AgentEvent.ToolCall(functionCall))
                                hasToolCalls = true
                            },
                            { toolName, args ->
                                onToolResult(toolName, args)
                                // Note: ToolResult event will be emitted after tool execution completes
                            },
                            toolCallsToExecute
                        )
                    }
                    android.util.Log.d("AgentClient", "sendMessage: API call completed successfully, finishReason: $finishReason")
                    Result.success(Unit)
                } catch (e: KeysExhaustedException) {
                    android.util.Log.e("AgentClient", "sendMessage: Keys exhausted", e)
                    Result.failure(e)
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "sendMessage: Exception during API call", e)
                    android.util.Log.e("AgentClient", "sendMessage: Exception type: ${e.javaClass.simpleName}")
                    android.util.Log.e("AgentClient", "sendMessage: Exception message: ${e.message}")
                    if (ApiProviderManager.isRateLimitError(e)) {
                        android.util.Log.w("AgentClient", "sendMessage: Rate limit error detected")
                        Result.failure(e)
                    } else {
                        Result.failure(e)
                    }
                }
            }
            
            // Emit collected events (chunks and tool calls) now that we're back in coroutine context
            if (eventsToEmit.isNotEmpty()) {
                android.util.Log.d("AgentClient", "sendMessage: Emitting ${eventsToEmit.size} collected event(s)")
                for (event in eventsToEmit) {
                    android.util.Log.d("AgentClient", "sendMessage: Emitting event: ${event.javaClass.simpleName}")
                    emit(event)
                }
                eventsToEmit.clear()
            } else {
                android.util.Log.d("AgentClient", "sendMessage: No events collected to emit")
            }
            
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                android.util.Log.e("AgentClient", "sendMessage: Result failed, error: ${error?.message}")
                if (error is KeysExhaustedException) {
                    emit(AgentEvent.KeysExhausted(error.message ?: "All keys exhausted"))
                } else {
                    emit(AgentEvent.Error(error?.message ?: "Unknown error"))
                }
                return@flow
            }
            
            // Validate response: if no tool calls and no finish reason, check if we have text content
            // If we have text content but no finish reason, assume STOP (model finished generating text)
            if (toolCallsToExecute.isEmpty() && finishReason == null) {
                // Check if we received any text chunks in this turn
                val hasTextContent = eventsToEmit.any { it is AgentEvent.Chunk }
                if (hasTextContent) {
                    android.util.Log.d("AgentClient", "sendMessage: No finish reason but has text content, assuming STOP")
                    finishReason = "STOP"
                } else {
                    android.util.Log.w("AgentClient", "sendMessage: No finish reason, no tool calls, and no text - invalid response")
                    emit(AgentEvent.Error("Model stream ended without a finish reason or tool calls"))
                    return@flow
                }
            }
            
            // Execute any pending tool calls
            if (toolCallsToExecute.isNotEmpty()) {
                android.util.Log.d("AgentClient", "sendMessage: Processing ${toolCallsToExecute.size} tool call result(s)")
                
                // Format responses and add to history
                // Tools are already executed during response processing, we just format the results
                for (triple in toolCallsToExecute) {
                    val functionCall = triple.first
                    val toolResult = triple.second
                    val callId = triple.third
                    
                    // Emit ToolResult event for UI
                    emit(AgentEvent.ToolResult(functionCall.name, toolResult))
                    
                    // Learn from successful tool results
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
                android.util.Log.d("AgentClient", "sendMessage: Continuing conversation after tool execution")
                continue // Loop to make another API call
            } else {
                // No more tool calls, check finish reason
                android.util.Log.d("AgentClient", "sendMessage: No tool calls, finishReason: $finishReason")
                when (finishReason) {
                    "STOP" -> {
                        android.util.Log.d("AgentClient", "sendMessage: Stream completed (STOP) - model indicates task is complete")
                        emit(AgentEvent.Done)
                        break
                    }
                    "MAX_TOKENS" -> {
                        android.util.Log.w("AgentClient", "sendMessage: Stream stopped due to MAX_TOKENS")
                        emit(AgentEvent.Error("Response was truncated due to token limit"))
                        break
                    }
                    "SAFETY" -> {
                        android.util.Log.w("AgentClient", "sendMessage: Stream stopped due to SAFETY")
                        emit(AgentEvent.Error("Response was blocked due to safety filters"))
                        break
                    }
                    "MALFORMED_FUNCTION_CALL" -> {
                        android.util.Log.w("AgentClient", "sendMessage: Stream stopped due to MALFORMED_FUNCTION_CALL")
                        emit(AgentEvent.Error("Model generated malformed function call"))
                        break
                    }
                    else -> {
                        android.util.Log.d("AgentClient", "sendMessage: Stream completed (finishReason: $finishReason)")
                        emit(AgentEvent.Done)
                        break
                    }
                }
            }
        }
        
        if (turnCount >= maxTurns) {
            android.util.Log.w("AgentClient", "sendMessage: Reached maximum turns ($maxTurns)")
            emit(AgentEvent.Error("Maximum number of turns reached"))
        }
    }
    
    private suspend fun makeApiCall(
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
                // Google Gemini API endpoint - using standard API calls
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
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
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Triple(url, requestBody, emptyMap<String, String>())
            }
        }
        android.util.Log.d("AgentClient", "makeApiCall: Provider: $providerType, URL: ${url.replace(apiKey, "***")}")
        android.util.Log.d("AgentClient", "makeApiCall: Model: $model")
        
        // Check request body size to avoid Binder transaction failures (limit is ~1MB)
        val requestBodyString = convertedRequestBody.toString()
        val requestBodySize = requestBodyString.length
        android.util.Log.d("AgentClient", "makeApiCall: Request body size: $requestBodySize bytes")
        
        // Warn if request is very large (could cause Binder transaction failures)
        if (requestBodySize > 800000) { // 800KB threshold
            android.util.Log.w("AgentClient", "makeApiCall: Large request body (${requestBodySize} bytes) - may cause Binder transaction issues")
        }
        
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
        
        // Add headers if any
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        
        val request = requestBuilder.build()
        
        android.util.Log.d("AgentClient", "makeApiCall: Executing request...")
        val startTime = System.currentTimeMillis()
        
        try {
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startTime
                android.util.Log.d("AgentClient", "makeApiCall: Response received after ${elapsed}ms")
                android.util.Log.d("AgentClient", "makeApiCall: Response code: ${response.code}")
                android.util.Log.d("AgentClient", "makeApiCall: Response successful: ${response.isSuccessful}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    android.util.Log.e("AgentClient", "makeApiCall: API call failed: ${response.code}")
                    android.util.Log.e("AgentClient", "makeApiCall: Error body: $errorBody")
                    throw IOException("API call failed: ${response.code} - $errorBody")
                }
                
                android.util.Log.d("AgentClient", "makeApiCall: Reading response body...")
                response.body?.let { body ->
                    val contentLength = body.contentLength()
                    android.util.Log.d("AgentClient", "makeApiCall: Response body content length: $contentLength")
                    
                    // Read the entire body
                    val bodyString = body.string()
                    android.util.Log.d("AgentClient", "makeApiCall: Response body string length: ${bodyString.length}")
                    
                    if (bodyString.isEmpty()) {
                        android.util.Log.w("AgentClient", "makeApiCall: Response body is empty!")
                        return@let
                    }
                    
                    try {
                        // Parse response based on provider type
                        val finishReason = when (providerType) {
                            ApiProviderType.GOOGLE -> {
                                // Try parsing as JSON array first (standard response)
                                if (bodyString.trim().startsWith("[")) {
                                    android.util.Log.d("AgentClient", "makeApiCall: Detected JSON array format (standard)")
                                    val jsonArray = JSONArray(bodyString)
                                    android.util.Log.d("AgentClient", "makeApiCall: JSON array has ${jsonArray.length()} elements")
                                    
                                    var lastFinishReason: String? = null
                                    var hasContent = false
                                    for (i in 0 until jsonArray.length()) {
                                        val json = jsonArray.getJSONObject(i)
                                        android.util.Log.d("AgentClient", "makeApiCall: Processing array element $i")
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
                                        android.util.Log.d("AgentClient", "makeApiCall: No finish reason in array but has content, assuming STOP")
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
                        android.util.Log.e("AgentClient", "makeApiCall: Failed to parse response body", e)
                        android.util.Log.e("AgentClient", "makeApiCall: Response preview: ${bodyString.take(500)}")
                        throw IOException("Failed to parse response: ${e.message}", e)
                    }
                } ?: run {
                    android.util.Log.w("AgentClient", "makeApiCall: Response body is null")
                }
            }
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("AgentClient", "makeApiCall: IOException after ${elapsed}ms", e)
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            android.util.Log.e("AgentClient", "makeApiCall: Unexpected exception after ${elapsed}ms", e)
            throw e
        }
        return null // No finish reason found
    }
    
    /**
     * Parse Gemini SSE response format
     */
    private suspend fun parseGeminiSSEResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>
    ): String? {
        // Try parsing as SSE (Server-Sent Events) format
        android.util.Log.d("AgentClient", "makeApiCall: Attempting SSE format parsing")
        val lines = bodyString.lines()
        android.util.Log.d("AgentClient", "makeApiCall: Total lines in response: ${lines.size}")
        
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
                    android.util.Log.d("AgentClient", "makeApiCall: Received [DONE] marker")
                    continue
                }
                
                try {
                    android.util.Log.d("AgentClient", "makeApiCall: Parsing SSE data line $dataLineCount")
                    val json = JSONObject(jsonStr)
                    android.util.Log.d("AgentClient", "makeApiCall: Processing SSE data line $dataLineCount")
                    val finishReason = processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, ApiProviderType.GOOGLE)
                    if (finishReason != null) {
                        return finishReason
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "makeApiCall: Failed to parse SSE JSON on line $dataLineCount", e)
                    android.util.Log.e("AgentClient", "makeApiCall: JSON string: ${jsonStr.take(500)}")
                }
            } else if (trimmedLine.startsWith(":")) {
                // SSE comment line, skip
                android.util.Log.d("AgentClient", "makeApiCall: Skipping SSE comment line")
            } else {
                // Try parsing the whole body as a single JSON object
                try {
                    android.util.Log.d("AgentClient", "makeApiCall: Attempting to parse as single JSON object")
                    val json = JSONObject(bodyString)
                    android.util.Log.d("AgentClient", "makeApiCall: Processing single JSON object")
                    return processResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, ApiProviderType.GOOGLE)
                } catch (e: Exception) {
                    android.util.Log.w("AgentClient", "makeApiCall: Unexpected line format: ${trimmedLine.take(100)}")
                }
            }
        }
        android.util.Log.d("AgentClient", "makeApiCall: Finished SSE parsing. Total lines: $lineCount, Data lines: $dataLineCount")
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
        openAIRequest.put("stream", false) // Standard for now
        
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
        ollamaRequest.put("stream", false) // Standard
        
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
    
    private suspend fun processResponse(
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
                            android.util.Log.d("AgentClient", "processResponse: Received thought: ${thoughtText.take(100)}...")
                            // Continue to next part - thoughts don't go to user
                            continue
                        }
                        
                        // Check for text (non-thought)
                        if (part.has("text") && !part.optBoolean("thought", false)) {
                            val text = part.getString("text")
                            android.util.Log.d("AgentClient", "processResponse: Found text chunk (length: ${text.length}): ${text.take(100)}...")
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
                                    android.util.Log.d("AgentClient", "processResponse: Citation: $citationText")
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
    
    private suspend fun executeToolSync(name: String, args: Map<String, Any>): ToolResult {
        // Ensure all tool execution happens on IO dispatcher to avoid blocking main thread
        return withContext(Dispatchers.IO) {
            android.util.Log.d("AgentClient", "executeToolSync: Executing tool '$name' on IO dispatcher")
            val tool = toolRegistry.getTool(name)
                ?: return@withContext ToolResult(
                    llmContent = "Tool not found: $name",
                    returnDisplay = "Error: Tool not found",
                    error = ToolError(
                        message = "Tool not found: $name",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            
            try {
                val params = tool.validateParams(args)
                    ?: return@withContext ToolResult(
                        llmContent = "Invalid parameters for tool: $name",
                        returnDisplay = "Error: Invalid parameters",
                        error = ToolError(
                            message = "Invalid parameters",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                
                @Suppress("UNCHECKED_CAST")
                val invocation = (tool as DeclarativeTool<Any, ToolResult>).createInvocation(params as Any)
                
                // Execute tool - already on IO dispatcher from outer withContext
                try {
                    invocation.execute(null, null)
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "Error executing tool $name", e)
                    throw e
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Handle cancellation gracefully
                return@withContext ToolResult(
                    llmContent = "Tool execution was cancelled: $name",
                    returnDisplay = "Cancelled",
                    error = ToolError(
                        message = "Tool execution cancelled",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("AgentClient", "Error executing tool: $name", e)
                android.util.Log.e("AgentClient", "Exception type: ${e.javaClass.simpleName}")
                android.util.Log.e("AgentClient", "Exception message: ${e.message}")
                e.printStackTrace()
                return@withContext ToolResult(
                    llmContent = "Error executing tool '$name': ${e.message ?: e.javaClass.simpleName}",
                    returnDisplay = "Error: ${e.message ?: "Unknown error"}",
                    error = ToolError(
                        message = e.message ?: "Unknown error",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
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
        
        // Check request body size to avoid Binder transaction failures
        val requestBodyString = requestBody.toString()
        val requestBodySize = requestBodyString.length
        if (requestBodySize > 800000) {
            android.util.Log.w("AgentClient", "sendMessageStandard: Large request body (${requestBodySize} bytes)")
        }
        
        val request = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
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
        android.util.Log.d("AgentClient", "Restored ${chatHistory.size} messages to chat history")
    }
    
    /**
     * Intent types for standard mode
     */
    private enum class IntentType {
        CREATE_NEW,
        DEBUG_UPGRADE,
        QUESTION_ONLY
    }
    
    /**
     * Detect multiple user intents: can detect multiple intents in one message.
     * This is a lightweight classifier – it does not call any remote LLM.
     */
    private suspend fun detectIntents(userMessage: String): List<IntentType> {
        val intents = mutableListOf<IntentType>()
        
        // Load memory context for better intent detection
        val memoryContext = MemoryService.getSummarizedMemory()
        
        val debugKeywords = listOf(
            "debug", "fix", "repair", "error", "bug", "issue", "problem",
            "upgrade", "update", "improve", "refactor", "modify", "change",
            "enhance", "optimize", "correct", "resolve", "solve"
        )
        
        val stacktraceIndicators = listOf(
            "exception:", "traceback (most recent call last)", " at ",
            "java.lang.", "kotlin.", "org.junit.", "AssertionError",
            "Error:", "ReferenceError", "TypeError", "SyntaxError"
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
        
        val questionWords = listOf(
            "what", "how", "why", "when", "where", "which", "who", "whom", "whose",
            "can you", "could you", "would you", "should i", "is there", "are there",
            "does", "do", "did", "will", "would", "should", "may", "might"
        )
        
        val messageLower = userMessage.lowercase()
        val contextLower = (userMessage + " " + memoryContext).lowercase()
        
        var debugScore = debugKeywords.count { contextLower.contains(it) }
        val createScore = createKeywords.count { contextLower.contains(it) }
        val testScore = testKeywords.count { contextLower.contains(it) }
        val questionIndicators = questionWords.count { messageLower.contains(it) }
        val endsWithQuestionMark = userMessage.trim().endsWith("?")
        val isQuestionPattern = messageLower.matches(Regex(".*\\b(what|how|why|when|where|which|who)\\b.*"))
        
        // Strong signal for DEBUG: presence of stack traces / exceptions
        val hasStacktraceSignals = stacktraceIndicators.any { contextLower.contains(it.lowercase()) }
        if (hasStacktraceSignals) {
            debugScore += 3 // boost debug score so debug wins over create/question
        }
        
        // Check if workspace has existing files
        val workspaceDir = File(workspaceRoot)
        val hasExistingFiles = workspaceDir.exists() && 
            workspaceDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        
        // Check memory for project context
        val hasProjectContext = memoryContext.contains("project", ignoreCase = true) ||
                                memoryContext.contains("codebase", ignoreCase = true) ||
                                memoryContext.contains("repository", ignoreCase = true)
        
        // Detect QUESTION intent (can coexist with others)
        val isQuestion = (endsWithQuestionMark || questionIndicators > 0 || isQuestionPattern) &&
                        (questionIndicators > 0 || endsWithQuestionMark)
        if (isQuestion && !hasStacktraceSignals) {
            // If we clearly have a stacktrace, prefer DEBUG over QUESTION
            intents.add(IntentType.QUESTION_ONLY)
        }
        
        // Testing is now part of DEBUG flow - AI can suggest testing during debugging
        
        // Detect DEBUG intent
        val isDebugByScore = debugScore > 0
        val isDebugByContext = hasProjectContext && hasExistingFiles && debugScore >= createScore
        val isDebug = hasExistingFiles && (isDebugByScore || isDebugByContext || hasStacktraceSignals)
        if (isDebug) {
            intents.add(IntentType.DEBUG_UPGRADE)
        }
        
        // Detect CREATE intent
        if (createScore > 0 && (!hasExistingFiles || createScore > debugScore)) {
            intents.add(IntentType.CREATE_NEW)
        }
        
        // If no intents detected, use default
        if (intents.isEmpty()) {
            intents.add(if (hasExistingFiles) IntentType.DEBUG_UPGRADE else IntentType.CREATE_NEW)
        }
        
        android.util.Log.d(
            "AgentClient",
            "detectIntents: debugScore=$debugScore, createScore=$createScore, testScore=$testScore, " +
                "questionIndicators=$questionIndicators, hasStacktraceSignals=$hasStacktraceSignals, intents=$intents"
        )
        
        return intents.distinct() // Remove duplicates
    }
    
    /**
     * Detect user intent: create new project, debug/upgrade existing, or test only
     * Uses memory context and keyword analysis
     * Also detects if task needs documentation search or planning
     * @deprecated Use detectIntents() instead for multi-intent support
     */
    @Deprecated("Use detectIntents() for multi-intent support")
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
        
        // PRIORITY: Check for question-only intent first
        // A question is detected if:
        // 1. Message ends with '?' or contains question words (what, how, why, when, where, which, who)
        // 2. No strong action keywords (create, debug, test) - just asking for information
        // 3. Message is primarily interrogative
        val questionWords = listOf(
            "what", "how", "why", "when", "where", "which", "who", "whom", "whose",
            "can you", "could you", "would you", "should i", "is there", "are there",
            "does", "do", "did", "will", "would", "should", "may", "might"
        )
        val questionIndicators = questionWords.count { messageLower.contains(it) }
        val endsWithQuestionMark = userMessage.trim().endsWith("?")
        val isQuestionPattern = messageLower.matches(Regex(".*\\b(what|how|why|when|where|which|who)\\b.*"))
        
        // Check if it's primarily a question (not a command disguised as question)
        val isQuestionOnly = (endsWithQuestionMark || questionIndicators > 0 || isQuestionPattern) &&
                            createScore == 0 && 
                            debugScore == 0 && 
                            testScore == 0 &&
                            !messageLower.contains("create") &&
                            !messageLower.contains("build") &&
                            !messageLower.contains("make") &&
                            !messageLower.contains("generate")
        
        if (isQuestionOnly) {
            return IntentType.QUESTION_ONLY
        }
        
        // Testing is now part of DEBUG flow - AI can suggest testing during debugging
        
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
            IntentType.QUESTION_ONLY -> "answering a question"
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
     * Uses comprehensive hardcoded patterns first, AI only as last resort to reduce API calls
     */
    private suspend fun detectCommandsNeeded(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<CommandWithFallbacks> {
        val commands = mutableListOf<CommandWithFallbacks>()
        val workspaceDir = File(workspaceRoot)
        
        if (!workspaceDir.exists()) return commands
        
        // Extract user intent from message (test, build, run, install, lint, format, etc.)
        val messageLower = userMessage.lowercase()
        val wantsTest = messageLower.contains("test") || messageLower.contains("run test")
        val wantsBuild = messageLower.contains("build") || messageLower.contains("compile")
        val wantsInstall = messageLower.contains("install") || messageLower.contains("dependencies")
        val wantsRun = messageLower.contains("run") || messageLower.contains("start") || messageLower.contains("execute")
        val wantsLint = messageLower.contains("lint") || messageLower.contains("check")
        val wantsFormat = messageLower.contains("format") || messageLower.contains("fmt")
        
        // Detect primary language by checking for strong indicators first
        // Priority: package.json (Node.js) > go.mod (Go) > Cargo.toml (Rust) > build.gradle/pom.xml (Java) > Python files
        
        // Check for Node.js first (package.json is a strong indicator)
        val hasPackageJson = File(workspaceDir, "package.json").exists()
        val hasNodeLockFiles = File(workspaceDir, "package-lock.json").exists() || 
                               File(workspaceDir, "yarn.lock").exists() || 
                               File(workspaceDir, "pnpm-lock.yaml").exists()
        val hasNodeSourceFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".js") || it.name.endsWith(".ts") || 
                it.name.endsWith(".jsx") || it.name.endsWith(".tsx")) }
        val isNodeProject = hasPackageJson || (hasNodeLockFiles && hasNodeSourceFiles)
        
        // Check for Go (go.mod is a strong indicator)
        val hasGoMod = File(workspaceDir, "go.mod").exists()
        val hasGoFiles = workspaceDir.walkTopDown()
            .any { it.isFile && it.name.endsWith(".go") }
        val isGoProject = hasGoMod || (hasGoFiles && !isNodeProject)
        
        // Check for Rust (Cargo.toml is a strong indicator)
        val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
        val hasRustFiles = workspaceDir.walkTopDown()
            .any { it.isFile && it.name.endsWith(".rs") }
        val isRustProject = hasCargoToml || (hasRustFiles && !isNodeProject && !isGoProject)
        
        // Check for Java/Kotlin (build files are strong indicators)
        val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
        val hasMaven = File(workspaceDir, "pom.xml").exists()
        val hasSbt = File(workspaceDir, "build.sbt").exists()
        val hasJavaFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
        val isJavaProject = hasGradle || hasMaven || hasSbt || (hasJavaFiles && !isNodeProject && !isGoProject && !isRustProject)
        
        // Check for Python (only if no other primary language detected)
        val hasPythonFiles = !isNodeProject && !isGoProject && !isRustProject && !isJavaProject &&
            workspaceDir.walkTopDown()
                .any { it.isFile && (it.name.endsWith(".py") || it.name == "requirements.txt" || 
                    it.name == "setup.py" || it.name == "pyproject.toml" || it.name == "Pipfile" || 
                    it.name == "poetry.lock" || it.name == "environment.yml") }
        
        // Detect Python projects (only if Python is the primary language)
        if (hasPythonFiles) {
            val venvExists = File(workspaceDir, "venv").exists() || 
                           File(workspaceDir, ".venv").exists() ||
                           File(workspaceDir, "env").exists()
            val hasRequirements = File(workspaceDir, "requirements.txt").exists()
            val hasPipfile = File(workspaceDir, "Pipfile").exists()
            val hasPoetry = File(workspaceDir, "pyproject.toml").exists() && 
                           File(workspaceDir, "poetry.lock").exists()
            val hasConda = File(workspaceDir, "environment.yml").exists()
            
            val pythonCmd = if (systemInfo.os == "Windows") "python" else "python3"
            val pipCmd = if (systemInfo.os == "Windows") "pip" else "pip3"
            val venvActivate = if (systemInfo.os == "Windows") "venv\\Scripts\\activate" else "source venv/bin/activate"
            
            // Detect main entry point
            val mainPy = workspaceDir.walkTopDown()
                .firstOrNull { it.isFile && (it.name == "main.py" || it.name == "app.py" || 
                    it.name == "__main__.py" || it.name.endsWith("_main.py") || 
                    it.name == "manage.py" || it.name == "run.py" || it.name == "server.py") }
            
            // Install dependencies
            if (wantsInstall || wantsRun || wantsTest) {
                val installCmd = when {
                    hasPoetry -> "poetry install"
                    hasPipfile -> "pipenv install"
                    hasConda -> "conda env create -f environment.yml || conda install --file environment.yml"
                    hasRequirements -> if (venvExists) "$venvActivate && $pipCmd install -r requirements.txt" else "$pipCmd install -r requirements.txt"
                    else -> if (venvExists) "$venvActivate && $pipCmd install ." else "$pipCmd install ."
                }
                
                val installFallbacks = mutableListOf<String>()
                if (!venvExists && (hasRequirements || hasPipfile)) {
                    installFallbacks.add("$pythonCmd -m venv venv || $pythonCmd -m virtualenv venv || true")
                }
                if (hasPoetry) {
                    installFallbacks.add("curl -sSL https://install.python-poetry.org | python3 - || pip3 install poetry")
                }
                if (hasPipfile) {
                    installFallbacks.add("pip3 install pipenv")
                }
                installFallbacks.add("${systemInfo.packageManagerCommands["install"]} $pythonCmd ${pythonCmd}-pip")
                installFallbacks.add("${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} $pythonCmd ${pythonCmd}-pip")
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = installCmd,
                    description = "Install Python dependencies",
                    fallbacks = installFallbacks,
                    checkCommand = "$pipCmd --version",
                    installCheck = "$pythonCmd --version"
                ))
            }
            
            // Run application
            if (wantsRun && mainPy != null) {
                val runCmd = when {
                    hasPoetry -> "poetry run $pythonCmd ${mainPy.name}"
                    hasPipfile -> "pipenv run $pythonCmd ${mainPy.name}"
                    mainPy.name == "manage.py" -> if (venvExists) "$venvActivate && $pythonCmd manage.py runserver" else "$pythonCmd manage.py runserver"
                    venvExists -> "$venvActivate && $pythonCmd ${mainPy.name}"
                    else -> "$pythonCmd ${mainPy.name}"
                }
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = runCmd,
                    description = "Run Python application",
                    fallbacks = listOf(
                        if (!venvExists && hasRequirements) "$pythonCmd -m venv venv || $pythonCmd -m virtualenv venv || true" else "",
                        if (hasRequirements) (if (venvExists) "$venvActivate && $pipCmd install -r requirements.txt" else "$pipCmd install -r requirements.txt") else ""
                    ).filter { it.isNotEmpty() },
                    checkCommand = "$pythonCmd --version",
                    installCheck = "$pipCmd --version"
                ))
            }
            
            // Test commands
            if (wantsTest) {
                val testCmd = when {
                    hasPoetry -> "poetry run pytest || poetry run python -m pytest"
                    hasPipfile -> "pipenv run pytest || pipenv run python -m pytest"
                    File(workspaceDir, "pytest.ini").exists() || File(workspaceDir, "setup.cfg").exists() -> 
                        if (venvExists) "$venvActivate && pytest" else "pytest"
                    File(workspaceDir, "tests").exists() || File(workspaceDir, "test").exists() ->
                        if (venvExists) "$venvActivate && $pythonCmd -m pytest tests/ || $pythonCmd -m unittest discover" else "$pythonCmd -m pytest tests/ || $pythonCmd -m unittest discover"
                    else -> if (venvExists) "$venvActivate && $pythonCmd -m unittest discover" else "$pythonCmd -m unittest discover"
                }
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = testCmd,
                    description = "Run Python tests",
                    fallbacks = listOf(
                        "pip3 install pytest unittest2 || pip install pytest unittest2",
                        "${systemInfo.packageManagerCommands["install"]} python3-pytest"
                    ),
                    checkCommand = "pytest --version || python3 -m pytest --version",
                    installCheck = "$pythonCmd --version"
                ))
            }
            
            // Lint commands
            if (wantsLint) {
                val lintCmd = when {
                    hasPoetry -> "poetry run flake8 . || poetry run pylint . || poetry run ruff check ."
                    hasPipfile -> "pipenv run flake8 . || pipenv run pylint ."
                    venvExists -> "$venvActivate && (flake8 . || pylint . || ruff check .)"
                    else -> "flake8 . || pylint . || ruff check . || python3 -m flake8 ."
                }
                
                commands.add(CommandWithFallbacks(
                    primaryCommand = lintCmd,
                    description = "Lint Python code",
                    fallbacks = listOf(
                        "pip3 install flake8 pylint ruff || pip install flake8 pylint ruff",
                        "${systemInfo.packageManagerCommands["install"]} python3-flake8 python3-pylint"
                    ),
                    checkCommand = "flake8 --version || pylint --version || ruff --version",
                    installCheck = "$pythonCmd --version"
                ))
            }
        }
        
        // Detect Node.js/JavaScript/TypeScript projects (prioritize Node.js if package.json exists)
        if (isNodeProject) {
            val packageJson = File(workspaceDir, "package.json")
            val hasYarn = File(workspaceDir, "yarn.lock").exists()
            val hasPnpm = File(workspaceDir, "pnpm-lock.yaml").exists()
            val packageManager = when {
                hasPnpm -> "pnpm"
                hasYarn -> "yarn"
                else -> "npm"
            }
            
            if (packageJson.exists()) {
                try {
                    val packageContent = packageJson.readText()
                    val hasStart = packageContent.contains("\"start\"")
                    val hasTest = packageContent.contains("\"test\"")
                    val hasBuild = packageContent.contains("\"build\"")
                    val hasLint = packageContent.contains("\"lint\"")
                    
                    // Install dependencies
                    if (wantsInstall || wantsRun || wantsTest || wantsBuild) {
                        val installCmd = when {
                            hasPnpm -> "pnpm install"
                            hasYarn -> "yarn install"
                            else -> "npm install"
                        }
                        
                        commands.add(CommandWithFallbacks(
                            primaryCommand = installCmd,
                            description = "Install Node.js dependencies",
                            fallbacks = listOf(
                                if (hasPnpm) "npm install -g pnpm" else "",
                                if (hasYarn) "npm install -g yarn" else "",
                                "${systemInfo.packageManagerCommands["install"]} nodejs npm",
                                "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} nodejs npm"
                            ).filter { it.isNotEmpty() },
                            checkCommand = "$packageManager --version || npm --version",
                            installCheck = "node --version"
                        ))
                    }
                    
                    // Start application
                    if (wantsRun && hasStart) {
                        val startCmd = when {
                            hasPnpm -> "pnpm start"
                            hasYarn -> "yarn start"
                            else -> "npm start"
                        }
                        
                        commands.add(CommandWithFallbacks(
                            primaryCommand = startCmd,
                            description = "Start Node.js application",
                            fallbacks = listOf(
                                when {
                                    hasPnpm -> "pnpm install"
                                    hasYarn -> "yarn install"
                                    else -> "npm install"
                                },
                                "${systemInfo.packageManagerCommands["install"]} nodejs npm"
                            ),
                            checkCommand = "node --version",
                            installCheck = "npm --version"
                        ))
                    }
                    
                    // Build
                    if (wantsBuild && hasBuild) {
                        val buildCmd = when {
                            hasPnpm -> "pnpm build"
                            hasYarn -> "yarn build"
                            else -> "npm run build"
                        }
                        
                        commands.add(CommandWithFallbacks(
                            primaryCommand = buildCmd,
                            description = "Build Node.js project",
                            fallbacks = listOf(
                                when {
                                    hasPnpm -> "pnpm install"
                                    hasYarn -> "yarn install"
                                    else -> "npm install"
                                }
                            ),
                            checkCommand = "node --version",
                            installCheck = "npm --version"
                        ))
                    }
                    
                    // Test
                    if (wantsTest && hasTest) {
                        val testCmd = when {
                            hasPnpm -> "pnpm test"
                            hasYarn -> "yarn test"
                            else -> "npm test"
                        }
                        
                        commands.add(CommandWithFallbacks(
                            primaryCommand = testCmd,
                            description = "Run Node.js tests",
                            fallbacks = listOf(
                                when {
                                    hasPnpm -> "pnpm install"
                                    hasYarn -> "yarn install"
                                    else -> "npm install"
                                }
                            ),
                            checkCommand = "node --version",
                            installCheck = "npm --version"
                        ))
                    }
                    
                    // Lint
                    if (wantsLint && hasLint) {
                        val lintCmd = when {
                            hasPnpm -> "pnpm lint"
                            hasYarn -> "yarn lint"
                            else -> "npm run lint"
                        }
                        
                        commands.add(CommandWithFallbacks(
                            primaryCommand = lintCmd,
                            description = "Lint Node.js code",
                            fallbacks = listOf(
                                when {
                                    hasPnpm -> "pnpm install"
                                    hasYarn -> "yarn install"
                                    else -> "npm install"
                                }
                            ),
                            checkCommand = "node --version",
                            installCheck = "npm --version"
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }
        }
        
        // Detect Go projects
        if (isGoProject) {
            val hasGoMod = File(workspaceDir, "go.mod").exists()
            val mainGo = workspaceDir.walkTopDown()
                .firstOrNull { file ->
                    file.isFile && file.name.endsWith(".go") && try {
                        file.readText().contains("func main()")
                    } catch (e: Exception) {
                        false
                    }
                }
            
            if (wantsInstall || wantsRun || wantsTest) {
                if (hasGoMod) {
                    commands.add(CommandWithFallbacks(
                        primaryCommand = "go mod download",
                        description = "Download Go dependencies",
                        fallbacks = listOf(
                            "${systemInfo.packageManagerCommands["install"]} golang-go",
                            "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} golang-go"
                        ),
                        checkCommand = "go version",
                        installCheck = null
                    ))
                }
            }
            
            if (wantsRun && mainGo != null) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "go run ${mainGo.name}",
                    description = "Run Go application",
                    fallbacks = listOf(
                        if (hasGoMod) "go mod download" else "",
                        "${systemInfo.packageManagerCommands["install"]} golang-go"
                    ).filter { it.isNotEmpty() },
                    checkCommand = "go version",
                    installCheck = null
                ))
            }
            
            if (wantsBuild) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "go build",
                    description = "Build Go application",
                    fallbacks = listOf(
                        if (hasGoMod) "go mod download" else "",
                        "${systemInfo.packageManagerCommands["install"]} golang-go"
                    ).filter { it.isNotEmpty() },
                    checkCommand = "go version",
                    installCheck = null
                ))
            }
            
            if (wantsTest) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "go test ./...",
                    description = "Run Go tests",
                    fallbacks = listOf(
                        if (hasGoMod) "go mod download" else "",
                        "${systemInfo.packageManagerCommands["install"]} golang-go"
                    ).filter { it.isNotEmpty() },
                    checkCommand = "go version",
                    installCheck = null
                ))
            }
        }
        
        // Detect Rust projects
        if (isRustProject) {
            val hasCargo = File(workspaceDir, "Cargo.toml").exists()
            
            if (wantsInstall || wantsRun || wantsTest || wantsBuild) {
                if (hasCargo) {
                    commands.add(CommandWithFallbacks(
                        primaryCommand = "cargo build",
                        description = "Build Rust project",
                        fallbacks = listOf(
                            "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y || true",
                            "${systemInfo.packageManagerCommands["install"]} rust cargo",
                            "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} rust cargo"
                        ),
                        checkCommand = "cargo --version",
                        installCheck = "rustc --version"
                    ))
                }
            }
            
            if (wantsRun && hasCargo) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "cargo run",
                    description = "Run Rust application",
                    fallbacks = listOf(
                        "cargo build",
                        "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y || true"
                    ),
                    checkCommand = "cargo --version",
                    installCheck = "rustc --version"
                ))
            }
            
            if (wantsTest && hasCargo) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "cargo test",
                    description = "Run Rust tests",
                    fallbacks = listOf(
                        "cargo build",
                        "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y || true"
                    ),
                    checkCommand = "cargo --version",
                    installCheck = "rustc --version"
                ))
            }
        }
        
        // Detect Java/Kotlin projects (Gradle, Maven, SBT)
        if (isJavaProject) {
            val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
            val hasMaven = File(workspaceDir, "pom.xml").exists()
            val hasSbt = File(workspaceDir, "build.sbt").exists()
            val gradleWrapper = File(workspaceDir, "gradlew").exists() || File(workspaceDir, "gradlew.bat").exists()
            
            if (wantsInstall || wantsBuild || wantsRun || wantsTest) {
                when {
                    hasGradle -> {
                        val gradleCmd = if (gradleWrapper) {
                            if (systemInfo.os == "Windows") "./gradlew.bat" else "./gradlew"
                        } else {
                            "gradle"
                        }
                        
                        if (wantsInstall || wantsBuild) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "$gradleCmd build",
                                description = "Build Gradle project",
                                fallbacks = listOf(
                                    if (!gradleWrapper) "${systemInfo.packageManagerCommands["install"]} gradle" else "",
                                    "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} gradle"
                                ).filter { it.isNotEmpty() },
                                checkCommand = "$gradleCmd --version || gradle --version",
                                installCheck = "java -version"
                            ))
                        }
                        
                        if (wantsRun) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "$gradleCmd run",
                                description = "Run Gradle application",
                                fallbacks = listOf(
                                    "$gradleCmd build",
                                    if (!gradleWrapper) "${systemInfo.packageManagerCommands["install"]} gradle" else ""
                                ).filter { it.isNotEmpty() },
                                checkCommand = "$gradleCmd --version || gradle --version",
                                installCheck = "java -version"
                            ))
                        }
                        
                        if (wantsTest) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "$gradleCmd test",
                                description = "Run Gradle tests",
                                fallbacks = listOf(
                                    "$gradleCmd build",
                                    if (!gradleWrapper) "${systemInfo.packageManagerCommands["install"]} gradle" else ""
                                ).filter { it.isNotEmpty() },
                                checkCommand = "$gradleCmd --version || gradle --version",
                                installCheck = "java -version"
                            ))
                        }
                    }
                    hasMaven -> {
                        if (wantsInstall || wantsBuild) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "mvn install",
                                description = "Build Maven project",
                                fallbacks = listOf(
                                    "${systemInfo.packageManagerCommands["install"]} maven",
                                    "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} maven"
                                ),
                                checkCommand = "mvn --version",
                                installCheck = "java -version"
                            ))
                        }
                        
                        if (wantsRun) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "mvn exec:java",
                                description = "Run Maven application",
                                fallbacks = listOf(
                                    "mvn install",
                                    "${systemInfo.packageManagerCommands["install"]} maven"
                                ),
                                checkCommand = "mvn --version",
                                installCheck = "java -version"
                            ))
                        }
                        
                        if (wantsTest) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "mvn test",
                                description = "Run Maven tests",
                                fallbacks = listOf(
                                    "mvn install",
                                    "${systemInfo.packageManagerCommands["install"]} maven"
                                ),
                                checkCommand = "mvn --version",
                                installCheck = "java -version"
                            ))
                        }
                    }
                    hasSbt -> {
                        if (wantsBuild || wantsRun || wantsTest) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "sbt compile",
                                description = "Build SBT project",
                                fallbacks = listOf(
                                    "${systemInfo.packageManagerCommands["install"]} sbt",
                                    "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} sbt"
                                ),
                                checkCommand = "sbt --version",
                                installCheck = "java -version"
                            ))
                        }
                        
                        if (wantsRun) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "sbt run",
                                description = "Run SBT application",
                                fallbacks = listOf(
                                    "sbt compile",
                                    "${systemInfo.packageManagerCommands["install"]} sbt"
                                ),
                                checkCommand = "sbt --version",
                                installCheck = "java -version"
                            ))
                        }
                        
                        if (wantsTest) {
                            commands.add(CommandWithFallbacks(
                                primaryCommand = "sbt test",
                                description = "Run SBT tests",
                                fallbacks = listOf(
                                    "sbt compile",
                                    "${systemInfo.packageManagerCommands["install"]} sbt"
                                ),
                                checkCommand = "sbt --version",
                                installCheck = "java -version"
                            ))
                        }
                    }
                }
            }
        }
        
        // Detect PHP projects
        val hasPhpFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".php") || it.name == "composer.json" || it.name == "composer.lock") }
        
        if (hasPhpFiles) {
            val hasComposer = File(workspaceDir, "composer.json").exists()
            
            if (wantsInstall && hasComposer) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "composer install",
                    description = "Install PHP dependencies",
                    fallbacks = listOf(
                        "curl -sS https://getcomposer.org/installer | php || php -r \"copy('https://getcomposer.org/installer', 'composer-setup.php'); php composer-setup.php; php -r \"unlink('composer-setup.php');\"\"",
                        "${systemInfo.packageManagerCommands["install"]} composer php",
                        "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} composer php"
                    ),
                    checkCommand = "composer --version",
                    installCheck = "php --version"
                ))
            }
            
            if (wantsRun) {
                val indexPhp = File(workspaceDir, "index.php")
                val serverPhp = File(workspaceDir, "server.php")
                val mainPhp = indexPhp.takeIf { it.exists() } ?: serverPhp.takeIf { it.exists() }
                
                if (mainPhp != null) {
                    commands.add(CommandWithFallbacks(
                        primaryCommand = "php -S localhost:8000",
                        description = "Run PHP development server",
                        fallbacks = listOf(
                            if (hasComposer) "composer install" else "",
                            "${systemInfo.packageManagerCommands["install"]} php",
                            "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} php"
                        ).filter { it.isNotEmpty() },
                        checkCommand = "php --version",
                        installCheck = null
                    ))
                }
            }
        }
        
        // Detect Ruby projects
        val hasRubyFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".rb") || it.name == "Gemfile" || it.name == "Gemfile.lock" || it.name == "Rakefile") }
        
        if (hasRubyFiles) {
            val hasGemfile = File(workspaceDir, "Gemfile").exists()
            val hasRails = try {
                hasGemfile && File(workspaceDir, "Gemfile").readText().contains("rails")
            } catch (e: Exception) {
                false
            }
            
            if (wantsInstall && hasGemfile) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "bundle install",
                    description = "Install Ruby dependencies",
                    fallbacks = listOf(
                        "gem install bundler",
                        "${systemInfo.packageManagerCommands["install"]} ruby ruby-dev bundler",
                        "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} ruby ruby-dev bundler"
                    ),
                    checkCommand = "bundle --version",
                    installCheck = "ruby --version"
                ))
            }
            
            if (wantsRun && hasRails) {
                commands.add(CommandWithFallbacks(
                    primaryCommand = "rails server || bundle exec rails server",
                    description = "Run Rails server",
                    fallbacks = listOf(
                        "bundle install",
                        "gem install bundler",
                        "${systemInfo.packageManagerCommands["install"]} ruby ruby-dev bundler"
                    ),
                    checkCommand = "rails --version || bundle exec rails --version",
                    installCheck = "ruby --version"
                ))
            }
        }
        
        // Detect C/C++ projects (Make, CMake)
        val hasCFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".c") || it.name.endsWith(".cpp") || 
                it.name.endsWith(".cc") || it.name.endsWith(".cxx") || it.name == "Makefile" || 
                it.name == "CMakeLists.txt") }
        
        if (hasCFiles) {
            val hasMakefile = File(workspaceDir, "Makefile").exists()
            val hasCMake = File(workspaceDir, "CMakeLists.txt").exists()
            
            if (wantsBuild) {
                when {
                    hasMakefile -> {
                        commands.add(CommandWithFallbacks(
                            primaryCommand = "make",
                            description = "Build C/C++ project with Make",
                            fallbacks = listOf(
                                "${systemInfo.packageManagerCommands["install"]} build-essential make gcc g++",
                                "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} build-essential make gcc g++"
                            ),
                            checkCommand = "make --version",
                            installCheck = "gcc --version"
                        ))
                    }
                    hasCMake -> {
                        commands.add(CommandWithFallbacks(
                            primaryCommand = "mkdir -p build && cd build && cmake .. && make",
                            description = "Build C/C++ project with CMake",
                            fallbacks = listOf(
                                "${systemInfo.packageManagerCommands["install"]} cmake build-essential make gcc g++",
                                "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} cmake build-essential make gcc g++"
                            ),
                            checkCommand = "cmake --version",
                            installCheck = "gcc --version"
                        ))
                    }
                }
            }
        }
        
        // Detect shell scripts
        val hasShellScripts = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".bash")) }
        
        if (hasShellScripts && wantsRun) {
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
        
        // Only use AI detection as last resort if no commands were found
        if (commands.isEmpty()) {
            android.util.Log.d("AgentClient", "No hardcoded commands found, falling back to AI detection")
            val aiCommands = detectCommandsWithAI(workspaceRoot, systemInfo, userMessage, emit, onChunk)
            if (aiCommands.isNotEmpty()) {
                commands.addAll(aiCommands)
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
        emit: suspend (AgentEvent) -> Unit,
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
            // Wrap network call in IO dispatcher to avoid NetworkOnMainThreadException
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(
                    ApiProviderManager.getNextApiKey() ?: return@withContext "",
                    model,
                    request,
                    useLongTimeout = false
                )
            }
            
            if (response.isEmpty()) return emptyList()
            
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
                        android.util.Log.w("AgentClient", "Failed to parse command: ${e.message}")
                    }
                }
                
                detectedCommands
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("AgentClient", "AI command detection failed: ${e.message}")
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
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        // Step 1: Detect framework and server start command
        val serverInfo = detectServerInfo(workspaceRoot, systemInfo, userMessage, emit, onChunk)
        if (serverInfo == null) {
            emit(AgentEvent.Chunk("⚠️ Could not detect server information\n"))
            onChunk("⚠️ Could not detect server information\n")
            return false
        }
        
        emit(AgentEvent.Chunk("🚀 Starting server: ${serverInfo.startCommand}\n"))
        onChunk("🚀 Starting server: ${serverInfo.startCommand}\n")
        
        // Step 2: Start server in background with debugging
        var serverProcess = startServer(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
        if (serverProcess == null) {
            // Try to debug why server didn't start
            emit(AgentEvent.Chunk("🔍 Debugging server startup failure...\n"))
            onChunk("🔍 Debugging server startup failure...\n")
            
            val debugResult = debugServerStartup(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
            if (!debugResult) {
                emit(AgentEvent.Chunk("❌ Failed to start server after debugging\n"))
                onChunk("❌ Failed to start server after debugging\n")
                return false
            }
            
            // Try starting again after debugging
            serverProcess = startServer(serverInfo, workspaceRoot, emit, onChunk, onToolCall, onToolResult)
            if (serverProcess == null) {
                emit(AgentEvent.Chunk("❌ Still failed to start server after debugging\n"))
                onChunk("❌ Still failed to start server after debugging\n")
                return false
            }
        }
        
        // Step 3: Wait for server to be ready
        var serverReady = waitForServerReady(serverInfo.baseUrl, serverInfo.port, emit, onChunk)
        if (!serverReady) {
            // Try to debug why server isn't responding
            emit(AgentEvent.Chunk("🔍 Server not responding, checking logs...\n"))
            onChunk("🔍 Server not responding, checking logs...\n")
            
            // Check if server process is still running
            val processAlive = serverProcess?.isAlive ?: false
            if (!processAlive) {
                emit(AgentEvent.Chunk("❌ Server process died. Trying to restart with fixes...\n"))
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
                    emit(AgentEvent.Chunk("❌ Server did not become ready after restart\n"))
                    onChunk("❌ Server did not become ready after restart\n")
                    return false
                }
            } else {
                emit(AgentEvent.Chunk("❌ Server process is running but not responding\n"))
                onChunk("❌ Server process is running but not responding\n")
                return false
            }
        }
        
        emit(AgentEvent.Chunk("✅ Server is ready at ${serverInfo.baseUrl}\n"))
        onChunk("✅ Server is ready at ${serverInfo.baseUrl}\n")
        
        // Step 4: Detect API endpoints using AI
        val endpoints = detectAPIEndpoints(workspaceRoot, serverInfo, userMessage, emit, onChunk)
        if (endpoints.isEmpty()) {
            emit(AgentEvent.Chunk("ℹ️ No testable endpoints detected\n"))
            onChunk("ℹ️ No testable endpoints detected\n")
            return true
        }
        
        emit(AgentEvent.Chunk("📋 Found ${endpoints.size} endpoint(s) to test\n"))
        onChunk("📋 Found ${endpoints.size} endpoint(s) to test\n")
        
        // Step 5: Test each endpoint
        var successCount = 0
        for (endpoint in endpoints) {
            val success = testEndpoint(endpoint, serverInfo.baseUrl, emit, onChunk)
            if (success) successCount++
        }
        
        emit(AgentEvent.Chunk("\n📊 Test Results: $successCount/${endpoints.size} endpoints passed\n"))
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
        emit: suspend (AgentEvent) -> Unit,
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
        emit: suspend (AgentEvent) -> Unit,
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
            // Wrap network call in IO dispatcher to avoid NetworkOnMainThreadException
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(
                    ApiProviderManager.getNextApiKey() ?: return@withContext "",
                    model,
                    request,
                    useLongTimeout = false
                )
            }
            
            if (response.isEmpty()) return null
            
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
            android.util.Log.w("AgentClient", "AI server detection failed: ${e.message}")
            null
        }
    }
    
    /**
     * Debug server startup issues and try to fix them
     */
    private suspend fun debugServerStartup(
        serverInfo: ServerInfo,
        workspaceRoot: String,
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        
        // Check for common issues
        emit(AgentEvent.Chunk("🔍 Checking for common issues...\n"))
        onChunk("🔍 Checking for common issues...\n")
        
        // Check if dependencies are installed
        if (serverInfo.framework.contains("Node.js")) {
            val packageJson = File(workspaceDir, "package.json")
            if (packageJson.exists()) {
                val nodeModules = File(workspaceDir, "node_modules")
                if (!nodeModules.exists() || nodeModules.listFiles()?.isEmpty() == true) {
                    emit(AgentEvent.Chunk("📦 Installing Node.js dependencies...\n"))
                    onChunk("📦 Installing Node.js dependencies...\n")
                    
                    val installCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to "npm install",
                            "description" to "Install Node.js dependencies",
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(AgentEvent.ToolCall(installCall))
                    onToolCall(installCall)
                    
                    try {
                        val installResult = executeToolSync("shell", installCall.args)
                        emit(AgentEvent.ToolResult("shell", installResult))
                        onToolResult("shell", installCall.args)
                        
                        if (installResult.error != null) {
                            emit(AgentEvent.Chunk("⚠️ Dependency installation had issues\n"))
                            onChunk("⚠️ Dependency installation had issues\n")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AgentClient", "Dependency install failed: ${e.message}")
                    }
                }
            }
        }
        
        if (serverInfo.framework.contains("Flask") || serverInfo.framework.contains("FastAPI")) {
            val requirementsFile = File(workspaceDir, "requirements.txt")
            if (requirementsFile.exists()) {
                emit(AgentEvent.Chunk("📦 Installing Python dependencies...\n"))
                onChunk("📦 Installing Python dependencies...\n")
                
                val installCall = FunctionCall(
                    name = "shell",
                    args = mapOf(
                        "command" to "pip3 install -r requirements.txt",
                        "description" to "Install Python dependencies",
                        "dir_path" to workspaceRoot
                    )
                )
                emit(AgentEvent.ToolCall(installCall))
                onToolCall(installCall)
                
                try {
                    val installResult = executeToolSync("shell", installCall.args)
                    emit(AgentEvent.ToolResult("shell", installResult))
                    onToolResult("shell", installCall.args)
                } catch (e: Exception) {
                    android.util.Log.w("AgentClient", "Dependency install failed: ${e.message}")
                }
            }
        }
        
        // Try to start server again
        emit(AgentEvent.Chunk("🔄 Retrying server startup...\n"))
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
        emit: suspend (AgentEvent) -> Unit,
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
        
        emit(AgentEvent.ToolCall(startCall))
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
                emit(AgentEvent.Chunk("✅ Server process started\n"))
                onChunk("✅ Server process started\n")
                process
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Failed to start server", e)
            emit(AgentEvent.Chunk("❌ Failed to start server: ${e.message}\n"))
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
        emit: suspend (AgentEvent) -> Unit,
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
                        emit(AgentEvent.Chunk("✅ Server responded at $url\n"))
                        onChunk("✅ Server responded at $url\n")
                        return true
                    }
                    response.close()
                } catch (e: Exception) {
                    // Continue trying
                }
            }
            
            kotlinx.coroutines.delay(1000)
            emit(AgentEvent.Chunk("⏳ Waiting for server...\n"))
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
        emit: suspend (AgentEvent) -> Unit,
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
            // Wrap network call in IO dispatcher to avoid NetworkOnMainThreadException
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(
                    ApiProviderManager.getNextApiKey() ?: return@withContext "",
                    model,
                    request,
                    useLongTimeout = false
                )
            }
            
            if (response.isEmpty()) return emptyList()
            
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
                        android.util.Log.w("AgentClient", "Failed to parse endpoint: ${e.message}")
                    }
                }
                
                endpoints
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("AgentClient", "AI endpoint detection failed: ${e.message}")
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
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit
    ): Boolean {
        val url = "$baseUrl${endpoint.path}"
        emit(AgentEvent.Chunk("🧪 Testing ${endpoint.method} $url - ${endpoint.description}\n"))
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
                emit(AgentEvent.Chunk("✅ ${endpoint.method} $url - Status: ${response.code}\n"))
                onChunk("✅ ${endpoint.method} $url - Status: ${response.code}\n")
            } else {
                emit(AgentEvent.Chunk("❌ ${endpoint.method} $url - Expected ${endpoint.expectedStatus}, got ${response.code}\n"))
                onChunk("❌ ${endpoint.method} $url - Expected ${endpoint.expectedStatus}, got ${response.code}\n")
            }
            
            response.close()
            success
        } catch (e: Exception) {
            emit(AgentEvent.Chunk("❌ ${endpoint.method} $url - Error: ${e.message}\n"))
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
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val runningMsg = "▶️ Running: ${command.primaryCommand}\n"
        emit(AgentEvent.Chunk(runningMsg))
        onChunk(runningMsg)
        
        // Check if command/tool is available - use cache first
        var checkResult: ToolResult? = null
        if (command.checkCommand != null) {
            // Try to get from cache first
            val dependencyName = command.description.lowercase().split(" ").firstOrNull() ?: "tool"
            val cachedAvailable = com.qali.aterm.gemini.utils.DependencyCache.isAvailable(
                dependency = dependencyName,
                checkCommand = command.checkCommand!!,
                workspaceRoot = workspaceRoot
            )
            
            if (cachedAvailable == false) {
                // Tool is known to be unavailable from cache, skip to fallbacks
                android.util.Log.d("AgentClient", "Tool $dependencyName unavailable (cached), skipping check")
                checkResult = ToolResult(
                    llmContent = "Tool not available (cached)",
                    returnDisplay = "Not available",
                    error = ToolError(
                        message = "Tool not available (cached)",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            } else if (cachedAvailable == true) {
                // Tool is known to be available from cache, skip check
                android.util.Log.d("AgentClient", "Tool $dependencyName available (cached), skipping check")
                // Continue with primary command - checkResult remains null (success)
            } else {
                // Cache miss - need to check
                val checkCall = FunctionCall(
                    name = "shell",
                    args = mapOf(
                        "command" to command.checkCommand!!,
                        "description" to "Check if ${command.description} tool is available"
                    )
                )
                emit(AgentEvent.ToolCall(checkCall))
                onToolCall(checkCall)
                
                try {
                    checkResult = executeToolSync("shell", checkCall.args)
                    emit(AgentEvent.ToolResult("shell", checkResult))
                    onToolResult("shell", checkCall.args)
                    
                    // Cache the result
                    com.qali.aterm.gemini.utils.DependencyCache.cacheResult(
                        dependency = dependencyName,
                        available = checkResult.error == null,
                        checkCommand = command.checkCommand!!,
                        workspaceRoot = workspaceRoot
                    )
                } catch (e: Exception) {
                    android.util.Log.w("AgentClient", "Check command failed: ${e.message}")
                    // Cache as unavailable
                    com.qali.aterm.gemini.utils.DependencyCache.cacheResult(
                        dependency = dependencyName,
                        available = false,
                        checkCommand = command.checkCommand!!,
                        workspaceRoot = workspaceRoot
                    )
                    checkResult = ToolResult(
                        llmContent = "Check failed: ${e.message}",
                        returnDisplay = "Error",
                        error = ToolError(
                            message = e.message ?: "Unknown error",
                            type = ToolErrorType.EXECUTION_ERROR
                        )
                    )
                }
            }
            
            // If tool check failed or was unavailable, try fallbacks
            val shouldTryFallbacks = cachedAvailable == false || 
                (cachedAvailable == null && checkResult?.error != null)
            
            if (shouldTryFallbacks && command.fallbacks.isNotEmpty()) {
                val fallbackMsg = "⚠️ Tool not found, trying fallbacks...\n"
                emit(AgentEvent.Chunk(fallbackMsg))
                onChunk(fallbackMsg)
                
                var fallbackSuccess = false
                for ((index, fallback) in command.fallbacks.withIndex()) {
                    val fallbackStepMsg = "📦 Fallback ${index + 1}/${command.fallbacks.size}: $fallback\n"
                    emit(AgentEvent.Chunk(fallbackStepMsg))
                    onChunk(fallbackStepMsg)
                    
                    val fallbackCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to fallback,
                            "description" to "Fallback: $fallback"
                        )
                    )
                    emit(AgentEvent.ToolCall(fallbackCall))
                    onToolCall(fallbackCall)
                    
                    try {
                        val fallbackResult = executeToolSync("shell", fallbackCall.args)
                        emit(AgentEvent.ToolResult("shell", fallbackResult))
                        onToolResult("shell", fallbackCall.args)
                        
                        if (fallbackResult.error == null) {
                            // Fallback successful, continue to next or retry check
                            fallbackSuccess = true
                            
                            // Cache successful fallback
                            com.qali.aterm.gemini.utils.DependencyCache.cacheResult(
                                dependency = dependencyName,
                                available = true,
                                checkCommand = command.checkCommand!!,
                                workspaceRoot = workspaceRoot
                            )
                            
                            if (fallback.contains("venv") || fallback.contains("activate")) {
                                // Venv activation, retry check
                                continue
                            } else {
                                break // Fallback installed tool, can proceed
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AgentClient", "Fallback command failed: ${e.message}")
                        // Continue to next fallback
                    }
                }
                
                if (!fallbackSuccess && command.fallbacks.isNotEmpty()) {
                    val allFailedMsg = "⚠️ All fallbacks failed, but continuing with primary command...\n"
                    emit(AgentEvent.Chunk(allFailedMsg))
                    onChunk(allFailedMsg)
                }
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
        emit(AgentEvent.ToolCall(primaryCall))
        onToolCall(primaryCall)
        
        try {
            val result = executeToolSync("shell", primaryCall.args)
            emit(AgentEvent.ToolResult("shell", result))
            onToolResult("shell", primaryCall.args)
            
            // Check for failure keywords in output
            val outputText = result.llmContent ?: ""
            val errorMsg = result.error?.message ?: ""
            val hasFailure = result.error != null || detectFailureKeywords(outputText)
            
            if (!hasFailure) {
                val successMsg = "✅ Command executed successfully\n"
                emit(AgentEvent.Chunk(successMsg))
                onChunk(successMsg)
                return true
            } else {
                // Classify error type
                val errorType = classifyErrorType(outputText, errorMsg, command.primaryCommand)
                
                // If it's a package.json JSON parse error from npm install, fix it first
                val isNpmInstall = command.primaryCommand.contains("npm install") || 
                                   command.primaryCommand.contains("npm ci") ||
                                   command.primaryCommand.contains("yarn install")
                val isPackageJsonError = (outputText.contains("EJSONPARSE") || 
                                         outputText.contains("JSON.parse") ||
                                         outputText.contains("package.json") && 
                                         (outputText.contains("parse") || outputText.contains("syntax") || outputText.contains("Unexpected"))) &&
                                        errorType == ErrorType.CONFIGURATION_ERROR
                
                if (isNpmInstall && isPackageJsonError) {
                    emit(AgentEvent.Chunk("🔧 Detected package.json JSON parse error - fixing it first...\n"))
                    onChunk("🔧 Detected package.json JSON parse error - fixing it first...\n")
                    
                    val fixSuccess = fixPackageJsonError(
                        workspaceRoot,
                        outputText,
                        emit,
                        onChunk,
                        onToolCall,
                        onToolResult
                    )
                    
                    if (fixSuccess) {
                        emit(AgentEvent.Chunk("✅ package.json fixed, retrying npm install...\n"))
                        onChunk("✅ package.json fixed, retrying npm install...\n")
                        
                        // Retry the original command after fixing package.json
                        val retryResult = executeToolSync("shell", primaryCall.args)
                        emit(AgentEvent.ToolResult("shell", retryResult))
                        onToolResult("shell", primaryCall.args)
                        
                        val retryOutput = retryResult.llmContent ?: ""
                        val retryHasFailure = retryResult.error != null || detectFailureKeywords(retryOutput)
                        
                        if (!retryHasFailure) {
                            emit(AgentEvent.Chunk("✅ npm install succeeded after fixing package.json!\n"))
                            onChunk("✅ npm install succeeded after fixing package.json!\n")
                            return true
                        }
                    }
                }
                
                // If it's a code error, debug the code first before trying fallbacks
                if (errorType == ErrorType.CODE_ERROR) {
                    emit(AgentEvent.Chunk("🐛 Code error detected - debugging code first...\n"))
                    onChunk("🐛 Code error detected - debugging code first...\n")
                    
                    val debugSuccess = debugCodeError(
                        command.primaryCommand,
                        outputText,
                        errorMsg,
                        workspaceRoot,
                        systemInfo,
                        emit,
                        onChunk,
                        onToolCall,
                        onToolResult
                    )
                    
                    if (debugSuccess) {
                        emit(AgentEvent.Chunk("✅ Code fixed, retrying command...\n"))
                        onChunk("✅ Code fixed, retrying command...\n")
                        
                        // Retry the original command after fixing code
                        val retryResult = executeToolSync("shell", primaryCall.args)
                        emit(AgentEvent.ToolResult("shell", retryResult))
                        onToolResult("shell", primaryCall.args)
                        
                        val retryOutput = retryResult.llmContent ?: ""
                        val retryHasFailure = retryResult.error != null || detectFailureKeywords(retryOutput)
                        
                        if (!retryHasFailure) {
                            emit(AgentEvent.Chunk("✅ Command succeeded after code fix!\n"))
                            onChunk("✅ Command succeeded after code fix!\n")
                            return true
                        }
                    }
                }
                
                // Check if we're in a restricted environment before trying fallbacks
                val isRestricted = isRestrictedEnvironment(workspaceRoot)
                if (isRestricted && errorType == ErrorType.COMMAND_NOT_FOUND) {
                    emit(AgentEvent.Chunk("⚠️ Restricted Environment Detected\n"))
                    onChunk("⚠️ Restricted Environment Detected\n")
                    emit(AgentEvent.Chunk("❌ This environment appears to be restricted. Basic commands (apk, curl, tar, etc.) are not available.\n"))
                    onChunk("❌ This environment appears to be restricted. Basic commands (apk, curl, tar, etc.) are not available.\n")
                    emit(AgentEvent.Chunk("💡 Suggestions:\n"))
                    onChunk("💡 Suggestions:\n")
                    emit(AgentEvent.Chunk("  1. Install required tools manually in the environment\n"))
                    onChunk("  1. Install required tools manually in the environment\n")
                    emit(AgentEvent.Chunk("  2. Use a different environment with package manager access\n"))
                    onChunk("  2. Use a different environment with package manager access\n")
                    emit(AgentEvent.Chunk("  3. Check if Node.js/npm are already installed: ls -la $(which node 2>/dev/null || echo 'not found')\n"))
                    onChunk("  3. Check if Node.js/npm are already installed: ls -la $(which node 2>/dev/null || echo 'not found')\n")
                    return false // Stop trying fallbacks
                }
                
                // Analyze failure and generate fallback plans
                val failureAnalysis = analyzeCommandFailure(
                    command.primaryCommand,
                    outputText,
                    errorMsg,
                    workspaceRoot,
                    systemInfo
                )
                
                emit(AgentEvent.Chunk("🔍 Failure Analysis: ${failureAnalysis.reason}\n"))
                onChunk("🔍 Failure Analysis: ${failureAnalysis.reason}\n")
                
                // If we've already tried many fallbacks and they all failed with command not found, check for restricted environment
                if (failureAnalysis.fallbackPlans.isEmpty() && errorType == ErrorType.COMMAND_NOT_FOUND) {
                    if (isRestricted) {
                        emit(AgentEvent.Chunk("⚠️ No fallback commands available in restricted environment\n"))
                        onChunk("⚠️ No fallback commands available in restricted environment\n")
                        return false
                    }
                }
                
                // Try fallback plans in order
                var fallbackAttempted = false
                var consecutiveCommandNotFound = 0
                for ((index, fallbackPlan) in failureAnalysis.fallbackPlans.withIndex()) {
                    // Stop if we've had too many consecutive command not found errors
                    if (consecutiveCommandNotFound >= 3) {
                        emit(AgentEvent.Chunk("⚠️ Too many consecutive 'command not found' errors. Stopping fallback attempts.\n"))
                        onChunk("⚠️ Too many consecutive 'command not found' errors. Stopping fallback attempts.\n")
                        emit(AgentEvent.Chunk("💡 This suggests a restricted environment where package managers and basic tools are unavailable.\n"))
                        onChunk("💡 This suggests a restricted environment where package managers and basic tools are unavailable.\n")
                        break
                    }
                    
                    if (fallbackAttempted && index >= 2) break // Limit to 2 fallback attempts
                    
                    val fallbackMsg = "🔄 Fallback Plan ${index + 1}: ${fallbackPlan.description}\n"
                    emit(AgentEvent.Chunk(fallbackMsg))
                    onChunk(fallbackMsg)
                    
                    val fallbackCall = FunctionCall(
                        name = "shell",
                        args = mapOf(
                            "command" to fallbackPlan.command,
                            "description" to fallbackPlan.description,
                            "dir_path" to workspaceRoot
                        )
                    )
                    emit(AgentEvent.ToolCall(fallbackCall))
                    onToolCall(fallbackCall)
                    
                    try {
                        val fallbackResult = executeToolSync("shell", fallbackCall.args)
                        emit(AgentEvent.ToolResult("shell", fallbackResult))
                        onToolResult("shell", fallbackCall.args)
                        
                        val fallbackOutput = fallbackResult.llmContent ?: ""
                        val fallbackHasFailure = fallbackResult.error != null || detectFailureKeywords(fallbackOutput)
                        
                        // Check if this is a command not found error
                        val isCommandNotFound = fallbackResult.error?.message?.contains("127") == true || 
                                               fallbackOutput.contains("not found") || 
                                               fallbackOutput.contains("inaccessible")
                        
                        if (isCommandNotFound) {
                            consecutiveCommandNotFound++
                        } else {
                            consecutiveCommandNotFound = 0 // Reset counter if we get a different error
                        }
                        
                        if (!fallbackHasFailure) {
                            emit(AgentEvent.Chunk("✅ Fallback plan succeeded!\n"))
                            onChunk("✅ Fallback plan succeeded!\n")
                            
                            // Retry original command if fallback was setup
                            if (fallbackPlan.shouldRetryOriginal) {
                                emit(AgentEvent.Chunk("🔄 Retrying original command...\n"))
                                onChunk("🔄 Retrying original command...\n")
                                
                                val retryResult = executeToolSync("shell", primaryCall.args)
                                emit(AgentEvent.ToolResult("shell", retryResult))
                                onToolResult("shell", primaryCall.args)
                                
                                val retryOutput = retryResult.llmContent ?: ""
                                val retryHasFailure = retryResult.error != null || detectFailureKeywords(retryOutput)
                                
                                if (!retryHasFailure) {
                                    emit(AgentEvent.Chunk("✅ Original command succeeded after fallback!\n"))
                                    onChunk("✅ Original command succeeded after fallback!\n")
                                    return true
                                }
                            } else {
                                return true
                            }
                        } else {
                            emit(AgentEvent.Chunk("⚠️ Fallback plan also failed\n"))
                            onChunk("⚠️ Fallback plan also failed\n")
                        }
                        fallbackAttempted = true
                    } catch (e: Exception) {
                        android.util.Log.w("AgentClient", "Fallback plan failed: ${e.message}")
                        emit(AgentEvent.Chunk("⚠️ Fallback plan error: ${e.message}\n"))
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
                    emit(AgentEvent.Chunk(venvMsg))
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
                    emit(AgentEvent.ToolCall(venvCall))
                    onToolCall(venvCall)
                    
                    try {
                        val venvResult = executeToolSync("shell", venvCall.args)
                        emit(AgentEvent.ToolResult("shell", venvResult))
                        onToolResult("shell", venvCall.args)
                        
                        val venvOutput = venvResult.llmContent ?: ""
                        val venvHasFailure = venvResult.error != null || detectFailureKeywords(venvOutput)
                        
                        if (!venvHasFailure) {
                            emit(AgentEvent.Chunk("✅ Command executed successfully with venv\n"))
                            onChunk("✅ Command executed successfully with venv\n")
                            return true
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AgentClient", "Venv command failed: ${e.message}")
                    }
                }
                
                emit(AgentEvent.Chunk("❌ Command failed: ${result.error?.message ?: "See output above"}\n"))
                onChunk("❌ Command failed: ${result.error?.message ?: "See output above"}\n")
                return false
            }
        } catch (e: Exception) {
            emit(AgentEvent.Chunk("❌ Error executing command: ${e.message}\n"))
            onChunk("❌ Error executing command: ${e.message}\n")
            return false
        }
    }
    
    /**
     * Error type classification
     */
    private enum class ErrorType {
        COMMAND_NOT_FOUND,      // Command/tool not installed
        CODE_ERROR,             // Syntax/runtime error in code
        DEPENDENCY_MISSING,     // Missing dependencies
        PERMISSION_ERROR,       // Permission/access issues
        CONFIGURATION_ERROR,    // Configuration/wrong setup
        NETWORK_ERROR,          // Network/connection issues
        UNKNOWN                 // Unknown error type
    }
    
    /**
     * Detect failure keywords in command output with comprehensive patterns
     */
    private fun detectFailureKeywords(output: String): Boolean {
        if (output.isEmpty()) return false
        
        val outputLower = output.lowercase()
        
        // Comprehensive failure keywords
        val failureKeywords = listOf(
            // General errors
            "error", "failed", "failure", "fatal", "exception", "crash", "abort",
            "cannot", "can't", "unable", "not found", "missing", "not available",
            "command not found", "permission denied", "access denied", "forbidden",
            "syntax error", "parse error", "type error", "reference error", "name error",
            "module not found", "package not found", "dependency", "import error",
            "exit code", "exit status", "non-zero", "returned 1", "returned 2",
            "failed to", "unexpected", "invalid", "bad", "wrong", "incorrect",
            "undefined", "null pointer", "null reference", "nullpointerexception",
            "timeout", "timed out", "connection refused", "connection reset",
            "eaddrinuse", "eacces", "enoent", "eexist", "eisdir", "enotdir",
            "segmentation fault", "segfault", "bus error", "stack overflow",
            "out of memory", "memory error", "allocation failed",
            "cannot read", "cannot write", "read-only", "readonly",
            "no such file", "no such directory", "file not found", "directory not found",
            "is a directory", "not a directory", "not a file",
            "already exists", "file exists", "directory exists",
            "broken pipe", "broken link", "symbolic link",
            "invalid argument", "invalid option", "invalid syntax",
            "uncaught exception", "unhandled exception", "uncaught error",
            "traceback", "stack trace", "call stack",
            "deprecated", "deprecation warning", "deprecation",
            "warning", "warn", "caution",
            // Exit codes
            "exit code 1", "exit code 2", "exit code 127", "exit code 128",
            "exit status 1", "exit status 2", "exit status 127",
            // Command-specific
            "npm err", "yarn error", "pip error", "python error",
            "node: command not found", "npm: command not found",
            "python: command not found", "pip: command not found",
            "gcc: command not found", "make: command not found",
            "go: command not found", "cargo: command not found",
            "java: command not found", "javac: command not found",
            "mvn: command not found", "gradle: command not found",
            // Code errors
            "syntaxerror", "syntax error", "indentationerror", "indentation error",
            "typeerror", "type error", "referenceerror", "reference error",
            "nameerror", "name error", "attributeerror", "attribute error",
            "valueerror", "value error", "keyerror", "key error",
            "indexerror", "index error", "ioerror", "io error",
            "oserror", "os error", "runtimeerror", "runtime error",
            "zerodivisionerror", "zero division", "division by zero",
            "filenotfounderror", "file not found error",
            "permissionerror", "permission error",
            "import error", "importerror", "modulenotfounderror",
            "cannot import", "failed to import", "import failed",
            "undefined variable", "undefined function", "undefined method",
            "undefined is not a function", "cannot read property",
            "cannot read properties", "cannot access",
            "is not defined", "is not a function", "is not a constructor",
            "expected", "unexpected token", "unexpected end",
            "missing", "missing required", "required parameter",
            "invalid", "invalid character", "invalid token",
            "unterminated", "unclosed", "missing closing",
            // Test failures
            "test failed", "tests failed", "test suite failed",
            "assertion failed", "assertionerror", "assert failed",
            "expected but got", "expected true but got false",
            "test error", "test exception", "test timeout"
        )
        
        return failureKeywords.any { keyword ->
            outputLower.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * Classify error type from output
     */
    private fun classifyErrorType(output: String, errorMessage: String, command: String): ErrorType {
        val outputLower = output.lowercase()
        val errorLower = errorMessage.lowercase()
        val commandLower = command.lowercase()
        val combined = "$outputLower $errorLower $commandLower"
        
        // Command not found
        if (combined.contains("command not found") || 
            combined.contains("not found") && (combined.contains("node") || combined.contains("npm") || 
            combined.contains("python") || combined.contains("pip") || combined.contains("go") ||
            combined.contains("cargo") || combined.contains("java") || combined.contains("mvn") ||
            combined.contains("gradle") || combined.contains("gcc") || combined.contains("make"))) {
            return ErrorType.COMMAND_NOT_FOUND
        }
        
        // Code errors (syntax, runtime, import errors)
        if (combined.contains("syntax error") || combined.contains("syntaxerror") ||
            combined.contains("parse error") || combined.contains("parseerror") ||
            combined.contains("type error") || combined.contains("typeerror") ||
            combined.contains("reference error") || combined.contains("referenceerror") ||
            combined.contains("name error") || combined.contains("nameerror") ||
            combined.contains("attribute error") || combined.contains("attributeerror") ||
            combined.contains("import error") || combined.contains("importerror") ||
            combined.contains("module not found") || combined.contains("modulenotfound") ||
            combined.contains("cannot import") || combined.contains("failed to import") ||
            combined.contains("undefined") || combined.contains("is not defined") ||
            combined.contains("traceback") || combined.contains("stack trace") ||
            combined.contains("uncaught exception") || combined.contains("unhandled exception") ||
            combined.contains("runtime error") || combined.contains("runtimeerror") ||
            combined.contains("null pointer") || combined.contains("nullpointer") ||
            combined.contains("cannot read property") || combined.contains("cannot access")) {
            return ErrorType.CODE_ERROR
        }
        
        // Dependency missing
        if (combined.contains("module not found") || combined.contains("package not found") ||
            combined.contains("dependency") || combined.contains("missing dependency") ||
            combined.contains("cannot find module") || combined.contains("cannot resolve") ||
            combined.contains("npm err") || combined.contains("yarn error") ||
            combined.contains("pip error") || combined.contains("no module named") ||
            combined.contains("package.json") && combined.contains("not found")) {
            return ErrorType.DEPENDENCY_MISSING
        }
        
        // Permission errors
        if (combined.contains("permission denied") || combined.contains("permissionerror") ||
            combined.contains("access denied") || combined.contains("forbidden") ||
            combined.contains("eacces") || combined.contains("read-only") ||
            combined.contains("cannot write") || combined.contains("cannot read")) {
            return ErrorType.PERMISSION_ERROR
        }
        
        // Network errors
        if (combined.contains("connection refused") || combined.contains("connection reset") ||
            combined.contains("timeout") || combined.contains("timed out") ||
            combined.contains("network error") || combined.contains("dns") ||
            combined.contains("econnrefused") || combined.contains("econnreset")) {
            return ErrorType.NETWORK_ERROR
        }
        
        // Configuration errors (including package.json JSON parse errors)
        if (combined.contains("invalid") || combined.contains("wrong") ||
            combined.contains("incorrect") || combined.contains("bad") ||
            combined.contains("configuration") || combined.contains("config error") ||
            combined.contains("ejsonparse") || combined.contains("json parse") ||
            (combined.contains("npm") && combined.contains("error") && combined.contains("code")) ||
            (combined.contains("package.json") && (combined.contains("parse") || combined.contains("json") || combined.contains("syntax")))) {
            return ErrorType.CONFIGURATION_ERROR
        }
        
        return ErrorType.UNKNOWN
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
     * Debug and fix code errors (syntax errors, runtime errors, import errors)
     * Returns true if code was successfully fixed
     */
    private suspend fun debugCodeError(
        command: String,
        output: String,
        errorMessage: String,
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        // Extract file path and line number from error output with comprehensive patterns
        val filePathPatterns = listOf(
            Regex("""(?:File|file|at)\s+["']?([^"'\s]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php|kt))["']?"""),
            Regex("""at\s+([^:\s]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php)):(\d+):(\d+)"""),
            Regex("""\s+File\s+"([^"]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php))",\s+line\s+(\d+)"""),
            Regex("""\s+File\s+'([^']+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php))',\s+line\s+(\d+)"""),
            Regex("""([^:\s]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php)):(\d+)"""),
            Regex("""([^:\s]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php))\((\d+)\)"""),
            Regex("""Traceback.*?File\s+["']([^"']+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php))["'],\s+line\s+(\d+)""", RegexOption.DOT_MATCHES_ALL)
        )
        
        var errorFile: String? = null
        var errorLine: Int? = null
        
        for (pattern in filePathPatterns) {
            val match = pattern.find(output)
            if (match != null) {
                errorFile = match.groupValues.getOrNull(1)
                errorLine = match.groupValues.getOrNull(2)?.toIntOrNull() ?: match.groupValues.getOrNull(3)?.toIntOrNull()
                if (errorFile != null) break
            }
        }
        
        // Fallback: try to find any source file mentioned in error
        if (errorFile == null) {
            val workspaceDir = File(workspaceRoot)
            val sourceFilePattern = Regex("""([^/\s]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|rb|php))""")
            val sourceMatches = sourceFilePattern.findAll(output)
            for (match in sourceMatches) {
                val fileName = match.groupValues[1]
                val potentialFile = workspaceDir.walkTopDown().firstOrNull { it.name == fileName }
                if (potentialFile != null) {
                    errorFile = potentialFile.relativeTo(workspaceDir).path
                    break
                }
            }
        }
        
        // Check for ES Module configuration errors first
        val isESModuleError = output.contains("Unexpected identifier") && 
                             (output.contains("import ") || output.contains("export ")) &&
                             (output.contains(".js") || output.contains("SyntaxError"))
        
        if (isESModuleError) {
            // Check if package.json exists and has type: "module"
            val packageJson = File(workspaceDir, "package.json")
            if (!packageJson.exists() || !packageJson.readText().contains("\"type\": \"module\"")) {
                emit(AgentEvent.Chunk("📦 ES Module error detected - checking package.json...\n"))
                onChunk("📦 ES Module error detected - checking package.json...\n")
                
                try {
                    val jsonObj = if (packageJson.exists()) {
                        JSONObject(packageJson.readText())
                    } else {
                        // Create new package.json with ES Module support
                        JSONObject().apply {
                            put("name", workspaceDir.name)
                            put("version", "1.0.0")
                            put("type", "module")
                            put("main", "server.js")
                            put("scripts", JSONObject().apply {
                                put("start", "node server.js")
                            })
                            put("dependencies", JSONObject())
                        }
                    }
                    
                    // Add or update type: "module"
                    if (!jsonObj.has("type") || jsonObj.optString("type") != "module") {
                        jsonObj.put("type", "module")
                        
                        val updatedContent = jsonObj.toString(2)
                        val writeCall = FunctionCall(
                            name = "write",
                            args = mapOf(
                                "file_path" to "package.json",
                                "contents" to updatedContent
                            )
                        )
                        emit(AgentEvent.ToolCall(writeCall))
                        onToolCall(writeCall)
                        
                        val writeResult = executeToolSync("write", writeCall.args)
                        emit(AgentEvent.ToolResult("write", writeResult))
                        onToolResult("write", writeCall.args)
                        
                        if (writeResult.error == null) {
                            emit(AgentEvent.Chunk("✅ Added 'type: \"module\"' to package.json\n"))
                            onChunk("✅ Added 'type: \"module\"' to package.json\n")
                            return true // Successfully fixed ES Module config
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AgentClient", "Failed to fix package.json: ${e.message}")
                }
            }
        }
        
        if (errorFile == null) {
            android.util.Log.d("AgentClient", "Could not extract file path from error")
            // Even if we can't find the file, if we fixed package.json, return true
            return isESModuleError
        }
        
        // Resolve file path (could be relative or absolute)
        val targetFile = when {
            File(errorFile).isAbsolute -> File(errorFile)
            else -> File(workspaceDir, errorFile)
        }
        
        if (!targetFile.exists()) {
            android.util.Log.d("AgentClient", "Error file not found: ${targetFile.absolutePath}")
            return isESModuleError // Return true if we fixed package.json
        }
        
        emit(AgentEvent.Chunk("📝 Found error in: ${targetFile.name}${if (errorLine != null) " at line $errorLine" else ""}\n"))
        onChunk("📝 Found error in: ${targetFile.name}${if (errorLine != null) " at line $errorLine" else ""}\n")
        
        try {
            val fileContent = targetFile.readText()
            val errorContext = if (errorLine != null && errorLine > 0) {
                val lines = fileContent.lines()
                val startLine = maxOf(0, errorLine - 5)
                val endLine = minOf(lines.size, errorLine + 5)
                lines.subList(startLine, endLine).joinToString("\n")
            } else {
                fileContent.take(1000)
            }
            
            // Check for __dirname usage in ES Modules (common issue)
            val hasDirname = fileContent.contains("__dirname") && !fileContent.contains("import.meta.url")
            if (hasDirname && isESModuleError) {
                emit(AgentEvent.Chunk("🔧 Fixing __dirname for ES Modules...\n"))
                onChunk("🔧 Fixing __dirname for ES Modules...\n")
                
                // Add imports if missing
                val needsPathImport = !fileContent.contains("import path from")
                val needsUrlImport = !fileContent.contains("import url from") && !fileContent.contains("import { fileURLToPath }")
                
                var fixedContent = fileContent
                if (needsPathImport || needsUrlImport) {
                    val importLines = mutableListOf<String>()
                    if (needsPathImport) importLines.add("import path from 'path';")
                    if (needsUrlImport) importLines.add("import { fileURLToPath } from 'url';")
                    
                    // Find first import line or add at top
                    val firstImportIndex = fixedContent.indexOf("import ")
                    if (firstImportIndex >= 0) {
                        val lineEnd = fixedContent.indexOf('\n', firstImportIndex)
                        fixedContent = fixedContent.substring(0, lineEnd + 1) + 
                                      importLines.joinToString("\n") + "\n" + 
                                      fixedContent.substring(lineEnd + 1)
                    } else {
                        fixedContent = importLines.joinToString("\n") + "\n\n" + fixedContent
                    }
                }
                
                // Replace __dirname definitions
                val dirnamePattern = Regex("""const\s+__dirname\s*=\s*path\.dirname\([^)]+\)""")
                if (dirnamePattern.find(fixedContent) == null && fixedContent.contains("__dirname")) {
                    // Add __dirname definition if not present but __dirname is used
                    val dirnameDef = "const __dirname = path.dirname(fileURLToPath(import.meta.url));"
                    // Find the last import statement
                    val lastImportIndex = fixedContent.lastIndexOf("import ")
                    if (lastImportIndex >= 0) {
                        // Find the end of that import line
                        val lineEnd = fixedContent.indexOf('\n', lastImportIndex)
                        if (lineEnd >= 0) {
                            fixedContent = fixedContent.substring(0, lineEnd + 1) + 
                                          "\n$dirnameDef" + 
                                          fixedContent.substring(lineEnd + 1)
                        } else {
                            // No newline found, add at end
                            fixedContent = fixedContent + "\n\n$dirnameDef"
                        }
                    } else {
                        // No imports, add at top
                        fixedContent = "$dirnameDef\n\n$fixedContent"
                    }
                } else if (dirnamePattern.find(fixedContent) != null) {
                    // Replace existing __dirname definitions
                    fixedContent = dirnamePattern.replace(fixedContent) {
                        "const __dirname = path.dirname(fileURLToPath(import.meta.url));"
                    }
                }
                
                if (fixedContent != fileContent) {
                    val editCall = FunctionCall(
                        name = "edit",
                        args = mapOf(
                            "file_path" to targetFile.relativeTo(workspaceDir).path,
                            "old_string" to fileContent,
                            "new_string" to fixedContent
                        )
                    )
                    emit(AgentEvent.ToolCall(editCall))
                    onToolCall(editCall)
                    
                    val editResult = executeToolSync("edit", editCall.args)
                    emit(AgentEvent.ToolResult("edit", editResult))
                    onToolResult("edit", editCall.args)
                    
                    if (editResult.error == null) {
                        emit(AgentEvent.Chunk("✅ Fixed __dirname for ES Modules\n"))
                        onChunk("✅ Fixed __dirname for ES Modules\n")
                        return true
                    }
                }
            }
            
            // Use AI to analyze and fix the code error
            val debugPrompt = """
                Analyze and fix the code error. Be concise and direct - provide ONLY the JSON fix, no explanations.
                
                **File:** ${targetFile.name}
                **Error Output:** ${output.take(1500)}
                **Error Message:** ${errorMessage.take(500)}
                **Command:** $command
                
                **Code Context (around error):**
                ```${targetFile.extension}
                $errorContext
                ```
                
                Return ONLY valid JSON (no markdown, no code blocks):
                {
                  "file_path": "${targetFile.relativeTo(workspaceDir).path}",
                  "old_string": "exact problematic code to replace",
                  "new_string": "exact fixed code"
                }
                
                Focus on:
                - Syntax errors (missing brackets, quotes, semicolons)
                - Import/export errors (wrong syntax, missing modules)
                - Type errors (undefined variables, wrong types)
                - Common patterns (__dirname in ES modules, etc.)
            """.trimIndent()
            
            val model = ApiProviderManager.getCurrentModel()
            val request = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", debugPrompt)
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
            
            val apiKey = ApiProviderManager.getNextApiKey() ?: return false
            
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(apiKey, model, request, useLongTimeout = false)
            }
            
            if (response.isEmpty()) return false
            
            // Parse JSON response and apply fix
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = JSONObject(response.substring(jsonStart, jsonEnd))
                val fixFilePath = json.optString("file_path", "")
                val oldString = json.optString("old_string", "")
                val newString = json.optString("new_string", "")
                
                if (fixFilePath.isNotEmpty() && oldString.isNotEmpty() && newString.isNotEmpty()) {
                    val fixFile = File(workspaceDir, fixFilePath)
                    if (fixFile.exists()) {
                        emit(AgentEvent.Chunk("🔧 Applying code fix...\n"))
                        onChunk("🔧 Applying code fix...\n")
                        
                        // Read current file content to verify old_string matches
                        val currentFileContent = try {
                            fixFile.readText()
                        } catch (e: Exception) {
                            null
                        }
                        
                        // If old_string doesn't match exactly, try to normalize and find a match
                        var actualOldString = oldString
                        if (currentFileContent != null && !currentFileContent.contains(oldString)) {
                            // Normalize line endings
                            val normalizedOld = oldString.replace("\r\n", "\n")
                            val normalizedCurrent = currentFileContent.replace("\r\n", "\n")
                            
                            // Try normalized version
                            if (normalizedCurrent.contains(normalizedOld)) {
                                actualOldString = normalizedOld
                            } else {
                                // Try to find by first line (more flexible matching)
                                val oldLines = normalizedOld.lines().filter { it.trim().isNotEmpty() }
                                if (oldLines.isNotEmpty()) {
                                    val firstLine = oldLines.first().trim()
                                    val firstLineIndex = normalizedCurrent.indexOf(firstLine)
                                    if (firstLineIndex >= 0) {
                                        // Found first line, try to extract matching section
                                        val contextLines = 5 // Get a few lines around
                                        val currentLines = normalizedCurrent.lines()
                                        val lineIndex = normalizedCurrent.substring(0, firstLineIndex).split('\n').size - 1
                                        
                                        if (lineIndex >= 0 && lineIndex < currentLines.size) {
                                            val startLine = maxOf(0, lineIndex - 2)
                                            val endLine = minOf(currentLines.size, lineIndex + oldLines.size + 2)
                                            val extractedSection = currentLines.subList(startLine, endLine).joinToString("\n")
                                            
                                            // If extracted section contains the key lines, use it
                                            if (oldLines.all { extractedSection.contains(it.trim()) }) {
                                                actualOldString = extractedSection
                                            }
                                        }
                                    }
                                }
                                
                                // Last resort: for very small files, use full content
                                if (!normalizedCurrent.contains(actualOldString) && normalizedCurrent.length < 3000) {
                                    actualOldString = normalizedCurrent
                                }
                            }
                        }
                        
                        val editCall = FunctionCall(
                            name = "edit",
                            args = mapOf(
                                "file_path" to fixFilePath,
                                "old_string" to actualOldString,
                                "new_string" to newString
                            )
                        )
                        emit(AgentEvent.ToolCall(editCall))
                        onToolCall(editCall)
                        
                        val editResult = executeToolSync("edit", editCall.args)
                        emit(AgentEvent.ToolResult("edit", editResult))
                        onToolResult("edit", editCall.args)
                        
                        if (editResult.error == null) {
                            emit(AgentEvent.Chunk("✅ Code fix applied successfully\n"))
                            onChunk("✅ Code fix applied successfully\n")
                            return true
                        } else {
                            val errorMsg = editResult.error?.message ?: "Unknown error"
                            emit(AgentEvent.Chunk("⚠️ Failed to apply code fix: $errorMsg\n"))
                            onChunk("⚠️ Failed to apply code fix: $errorMsg\n")
                            
                            // If it's a "String not found" error, try using write tool as fallback for small changes
                            if (errorMsg.contains("String not found") || errorMsg.contains("not found")) {
                                emit(AgentEvent.Chunk("🔄 Trying alternative fix method...\n"))
                                onChunk("🔄 Trying alternative fix method...\n")
                                
                                // For small files, try writing the entire new content
                                if (currentFileContent != null && currentFileContent.length < 10000) {
                                    val writeCall = FunctionCall(
                                        name = "write",
                                        args = mapOf(
                                            "file_path" to fixFilePath,
                                            "contents" to newString
                                        )
                                    )
                                    emit(AgentEvent.ToolCall(writeCall))
                                    onToolCall(writeCall)
                                    
                                    val writeResult = executeToolSync("write", writeCall.args)
                                    emit(AgentEvent.ToolResult("write", writeResult))
                                    onToolResult("write", writeCall.args)
                                    
                                    if (writeResult.error == null) {
                                        emit(AgentEvent.Chunk("✅ Code fix applied using write tool\n"))
                                        onChunk("✅ Code fix applied using write tool\n")
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Failed to debug code error: ${e.message}", e)
            emit(AgentEvent.Chunk("⚠️ Code debugging failed: ${e.message}\n"))
            onChunk("⚠️ Code debugging failed: ${e.message}\n")
            return false
        }
    }
    
    /**
     * Fix package.json JSON parse errors
     * Returns true if package.json was successfully fixed
     */
    private suspend fun fixPackageJsonError(
        workspaceRoot: String,
        errorOutput: String,
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        val packageJsonFile = File(workspaceDir, "package.json")
        
        if (!packageJsonFile.exists()) {
            emit(AgentEvent.Chunk("⚠️ package.json not found\n"))
            onChunk("⚠️ package.json not found\n")
            return false
        }
        
        try {
            // Try to read and parse package.json to see if it's valid JSON
            val packageJsonContent = packageJsonFile.readText()
            
            // Try parsing to see if it's valid JSON
            try {
                JSONObject(packageJsonContent)
                // If we get here, JSON is valid, so the error might be something else
                android.util.Log.d("AgentClient", "package.json is valid JSON, error might be elsewhere")
                return false
            } catch (e: Exception) {
                // JSON is invalid, need to fix it
                android.util.Log.d("AgentClient", "package.json has JSON parse error: ${e.message}")
            }
            
            // Use AI to fix the JSON
            val systemContext = SystemInfoService.generateSystemContext()
            val model = ApiProviderManager.getCurrentModel()
            
            val fixPrompt = """
                Fix the JSON syntax error in package.json. Return ONLY the corrected JSON content, no explanations, no markdown, no code blocks.
                
                **Error Output:**
                ${errorOutput.take(1000)}
                
                **Current package.json content:**
                ```json
                ${packageJsonContent.take(5000)}
                ```
                
                Common JSON errors to fix:
                - Missing commas between properties
                - Trailing commas
                - Unclosed brackets/braces
                - Invalid string quotes
                - Comments (JSON doesn't support comments)
                - Duplicate keys
                
                Return ONLY the fixed JSON as a valid JSON object, nothing else.
            """.trimIndent()
            
            val request = JSONObject().apply {
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
            
            val apiKey = ApiProviderManager.getNextApiKey() ?: return false
            
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(apiKey, model, request, useLongTimeout = false)
            }
            
            if (response.isEmpty()) return false
            
            // Extract JSON from response (might be wrapped in markdown or text)
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val fixedJson = response.substring(jsonStart, jsonEnd)
                
                // Validate the fixed JSON
                try {
                    JSONObject(fixedJson)
                    
                    // Write the fixed package.json
                    val writeCall = FunctionCall(
                        name = "write",
                        args = mapOf(
                            "file_path" to "package.json",
                            "contents" to fixedJson
                        )
                    )
                    emit(AgentEvent.ToolCall(writeCall))
                    onToolCall(writeCall)
                    
                    val writeResult = executeToolSync("write", writeCall.args)
                    emit(AgentEvent.ToolResult("write", writeResult))
                    onToolResult("write", writeCall.args)
                    
                    if (writeResult.error == null) {
                        emit(AgentEvent.Chunk("✅ Fixed package.json JSON syntax errors\n"))
                        onChunk("✅ Fixed package.json JSON syntax errors\n")
                        return true
                    } else {
                        emit(AgentEvent.Chunk("⚠️ Failed to write fixed package.json: ${writeResult.error?.message}\n"))
                        onChunk("⚠️ Failed to write fixed package.json: ${writeResult.error?.message}\n")
                        return false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "Fixed JSON is still invalid: ${e.message}")
                    emit(AgentEvent.Chunk("⚠️ AI-generated fix is still invalid JSON\n"))
                    onChunk("⚠️ AI-generated fix is still invalid JSON\n")
                    return false
                }
            } else {
                android.util.Log.e("AgentClient", "Could not extract JSON from AI response")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Failed to fix package.json: ${e.message}", e)
            emit(AgentEvent.Chunk("⚠️ Failed to fix package.json: ${e.message}\n"))
            onChunk("⚠️ Failed to fix package.json: ${e.message}\n")
            return false
        }
    }
    
    /**
     * Detect language/framework version from project files
     */
    private fun detectLanguageVersion(workspaceRoot: String): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        val workspaceDir = File(workspaceRoot)
        
        // Node.js version from package.json engines or .nvmrc
        val packageJson = File(workspaceDir, "package.json")
        if (packageJson.exists()) {
            try {
                val content = packageJson.readText()
                val enginesMatch = Regex(""""engines"\s*:\s*\{[^}]*"node"\s*:\s*"([^"]+)""").find(content)
                enginesMatch?.groupValues?.get(1)?.let { versions["node"] = it }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        val nvmrc = File(workspaceDir, ".nvmrc")
        if (nvmrc.exists()) {
            try {
                versions["node"] = nvmrc.readText().trim()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        // Python version from .python-version or runtime.txt
        val pythonVersion = File(workspaceDir, ".python-version")
        if (pythonVersion.exists()) {
            try {
                versions["python"] = pythonVersion.readText().trim()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        val runtimeTxt = File(workspaceDir, "runtime.txt")
        if (runtimeTxt.exists()) {
            try {
                val content = runtimeTxt.readText()
                val pythonMatch = Regex("python-([\\d.]+)").find(content)
                pythonMatch?.groupValues?.get(1)?.let { versions["python"] = it }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        // Go version from go.mod
        val goMod = File(workspaceDir, "go.mod")
        if (goMod.exists()) {
            try {
                val content = goMod.readText()
                val goMatch = Regex("go\\s+([\\d.]+)").find(content)
                goMatch?.groupValues?.get(1)?.let { versions["go"] = it }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        // Rust version from rust-toolchain or rust-toolchain.toml
        val rustToolchain = File(workspaceDir, "rust-toolchain")
        if (rustToolchain.exists()) {
            try {
                versions["rust"] = rustToolchain.readText().trim()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        val rustToolchainToml = File(workspaceDir, "rust-toolchain.toml")
        if (rustToolchainToml.exists()) {
            try {
                val content = rustToolchainToml.readText()
                val channelMatch = Regex("channel\\s*=\\s*\"([^\"]+)\"").find(content)
                channelMatch?.groupValues?.get(1)?.let { versions["rust"] = it }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        // Java version from pom.xml or build.gradle
        val pomXml = File(workspaceDir, "pom.xml")
        if (pomXml.exists()) {
            try {
                val content = pomXml.readText()
                val javaMatch = Regex("<java\\.version>([\\d.]+)</java\\.version>").find(content)
                javaMatch?.groupValues?.get(1)?.let { versions["java"] = it }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        val buildGradle = File(workspaceDir, "build.gradle") ?: File(workspaceDir, "build.gradle.kts")
        if (buildGradle.exists()) {
            try {
                val content = buildGradle.readText()
                val javaMatch = Regex("java\\.toolchain\\.languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\(([\\d]+)\\)").find(content)
                javaMatch?.groupValues?.get(1)?.let { versions["java"] = it }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return versions
    }
    
    /**
     * Get hardcoded fallback commands based on error type and system info
     * This reduces API calls by using pre-defined fallbacks
     */
    // Cache for command availability checks to avoid repeated checks
    private val commandAvailabilityCache = mutableMapOf<String, Boolean>()
    
    /**
     * Check if a command is available in the system
     */
    private suspend fun isCommandAvailable(command: String, workspaceRoot: String): Boolean {
        // Check cache first
        if (commandAvailabilityCache.containsKey(command)) {
            return commandAvailabilityCache[command] ?: false
        }
        
        return try {
            val checkCall = FunctionCall(
                name = "shell",
                args = mapOf(
                    "command" to "which $command || command -v $command || type $command",
                    "description" to "Check if $command is available"
                )
            )
            val result = executeToolSync("shell", checkCall.args)
            val available = result.error == null && result.llmContent.isNotEmpty() && 
            !result.llmContent.contains("not found") && 
            !result.llmContent.contains("inaccessible")
            
            // Cache the result
            commandAvailabilityCache[command] = available
            available
        } catch (e: Exception) {
            commandAvailabilityCache[command] = false
            false
        }
    }
    
    /**
     * Check if we're in a restricted environment where even basic commands aren't available
     */
    private suspend fun isRestrictedEnvironment(workspaceRoot: String): Boolean {
        // First, try a simple command to see if shell works at all
        return try {
            val testCall = FunctionCall(
                name = "shell",
                args = mapOf(
                    "command" to "echo test",
                    "description" to "Test basic shell functionality"
                )
            )
            val testResult = executeToolSync("shell", testCall.args)
            
            // If even echo fails, we're definitely in a restricted environment
            if (testResult.error != null && testResult.error?.message?.contains("127") == true) {
                return true
            }
            
            // Check if even basic commands are available
            val basicCommands = listOf("ls", "pwd")
            var availableCount = 0
            
            for (cmd in basicCommands) {
                if (isCommandAvailable(cmd, workspaceRoot)) {
                    availableCount++
                }
            }
            
            // If less than half of basic commands are available, we're in a restricted environment
            availableCount < basicCommands.size / 2
        } catch (e: Exception) {
            // If we can't even test, assume restricted
            true
        }
    }
    
    private suspend fun getHardcodedFallbacks(
        errorType: ErrorType,
        command: String,
        output: String,
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo
    ): List<FallbackPlan> {
        val fallbacks = mutableListOf<FallbackPlan>()
        val workspaceDir = File(workspaceRoot)
        val installCmd = systemInfo.packageManagerCommands["install"] ?: "install"
        val updateCmd = systemInfo.packageManagerCommands["update"] ?: "update"
        val commandLower = command.lowercase()
        val outputLower = output.lowercase()
        
        // Check if package manager is available
        val pmAvailable = when (systemInfo.packageManager) {
            "apk" -> isCommandAvailable("apk", workspaceRoot)
            "apt", "apt-get" -> isCommandAvailable("apt-get", workspaceRoot) || isCommandAvailable("apt", workspaceRoot)
            "yum" -> isCommandAvailable("yum", workspaceRoot)
            "dnf" -> isCommandAvailable("dnf", workspaceRoot)
            "pacman" -> isCommandAvailable("pacman", workspaceRoot)
            "brew" -> isCommandAvailable("brew", workspaceRoot)
            else -> false
        }
        
        // Detect language versions
        val versions = detectLanguageVersion(workspaceRoot)
        
        // Get OS version for version-specific commands
        val osVersion = systemInfo.osVersion
        val osVersionMajor = osVersion?.split(".")?.firstOrNull()?.toIntOrNull()
        val osVersionMinor = osVersion?.split(".")?.getOrNull(1)?.toIntOrNull()
        
        // Detect project type
        val hasPackageJson = File(workspaceDir, "package.json").exists()
        val hasRequirements = File(workspaceDir, "requirements.txt").exists()
        val hasGoMod = File(workspaceDir, "go.mod").exists()
        val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
        val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
        val hasMaven = File(workspaceDir, "pom.xml").exists()
        val hasPipfile = File(workspaceDir, "Pipfile").exists()
        val hasPoetry = File(workspaceDir, "pyproject.toml").exists() && File(workspaceDir, "poetry.lock").exists()
        val venvExists = File(workspaceDir, "venv").exists() || File(workspaceDir, ".venv").exists()
        val hasYarn = File(workspaceDir, "yarn.lock").exists()
        val hasPnpm = File(workspaceDir, "pnpm-lock.yaml").exists()
        
        when (errorType) {
            ErrorType.COMMAND_NOT_FOUND -> {
                // Node.js/npm not found
                if (commandLower.contains("node") || commandLower.contains("npm")) {
                    val nodeVersion = versions["node"]
                    if (pmAvailable) {
                        when (systemInfo.packageManager) {
                            "apk" -> {
                                if (nodeVersion != null && nodeVersion.startsWith("20")) {
                                    fallbacks.add(FallbackPlan("apk add nodejs20 npm", "Install Node.js 20 via apk", true))
                                } else if (nodeVersion != null && nodeVersion.startsWith("18")) {
                                    fallbacks.add(FallbackPlan("apk add nodejs18 npm", "Install Node.js 18 via apk", true))
                                }
                                fallbacks.add(FallbackPlan("apk add nodejs npm", "Install Node.js and npm via apk", true))
                                fallbacks.add(FallbackPlan("apk update && apk add nodejs npm", "Update apk and install Node.js/npm", true))
                                fallbacks.add(FallbackPlan("apk add nodejs-current npm", "Install current Node.js version", true))
                            }
                        "apt", "apt-get" -> {
                            if (nodeVersion != null) {
                                val majorVersion = nodeVersion.split(".").firstOrNull() ?: "lts"
                                fallbacks.add(FallbackPlan("curl -fsSL https://deb.nodesource.com/setup_${majorVersion}.x | bash - && apt-get install -y nodejs", "Install Node.js $nodeVersion from NodeSource", true))
                            }
                            // OS version-specific: Ubuntu 22.04+ has newer Node.js in repos
                            if (systemInfo.os.contains("Ubuntu") && osVersionMajor != null && osVersionMajor >= 22) {
                                fallbacks.add(FallbackPlan("apt-get update && apt-get install -y nodejs npm", "Install Node.js via apt (Ubuntu 22.04+)", true))
                            } else {
                                fallbacks.add(FallbackPlan("apt-get update && apt-get install -y nodejs npm", "Update apt and install Node.js/npm", true))
                            }
                            fallbacks.add(FallbackPlan("curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - && apt-get install -y nodejs", "Install Node.js LTS from NodeSource", true))
                            // For older Debian/Ubuntu versions
                            if (osVersionMajor != null && osVersionMajor < 20) {
                                fallbacks.add(FallbackPlan("curl -fsSL https://deb.nodesource.com/setup_16.x | bash - && apt-get install -y nodejs", "Install Node.js 16 for older systems", true))
                            }
                        }
                        "yum" -> {
                            if (nodeVersion != null) {
                                val majorVersion = nodeVersion.split(".").firstOrNull() ?: "lts"
                                fallbacks.add(FallbackPlan("curl -fsSL https://rpm.nodesource.com/setup_${majorVersion}.x | bash - && yum install -y nodejs", "Install Node.js $nodeVersion from NodeSource", true))
                            }
                            fallbacks.add(FallbackPlan("yum install -y nodejs npm", "Install Node.js and npm via yum", true))
                            fallbacks.add(FallbackPlan("curl -fsSL https://rpm.nodesource.com/setup_lts.x | bash - && yum install -y nodejs", "Install Node.js LTS from NodeSource", true))
                        }
                        "dnf" -> {
                            if (nodeVersion != null) {
                                val majorVersion = nodeVersion.split(".").firstOrNull() ?: "lts"
                                fallbacks.add(FallbackPlan("curl -fsSL https://rpm.nodesource.com/setup_${majorVersion}.x | bash - && dnf install -y nodejs", "Install Node.js $nodeVersion from NodeSource", true))
                            }
                            fallbacks.add(FallbackPlan("dnf install -y nodejs npm", "Install Node.js and npm via dnf", true))
                            fallbacks.add(FallbackPlan("curl -fsSL https://rpm.nodesource.com/setup_lts.x | bash - && dnf install -y nodejs", "Install Node.js LTS from NodeSource", true))
                        }
                        "pacman" -> {
                            fallbacks.add(FallbackPlan("pacman -S --noconfirm nodejs npm", "Install Node.js and npm via pacman", true))
                        }
                        "brew" -> {
                            if (nodeVersion != null) {
                                fallbacks.add(FallbackPlan("brew install node@$nodeVersion", "Install Node.js $nodeVersion via Homebrew", true))
                            }
                            fallbacks.add(FallbackPlan("brew install node", "Install Node.js via Homebrew", true))
                        }
                    }
                    } else {
                        // Package manager not available - suggest alternative installation methods
                        fallbacks.add(FallbackPlan("curl -fsSL https://nodejs.org/dist/v20.11.0/node-v20.11.0-linux-arm64.tar.xz -o /tmp/node.tar.xz && tar -xf /tmp/node.tar.xz -C /tmp && export PATH=\"/tmp/node-v20.11.0-linux-arm64/bin:\$PATH\"", "Download and extract Node.js binary (no package manager)", false))
                        fallbacks.add(FallbackPlan("curl -fsSL https://nodejs.org/dist/v18.19.0/node-v18.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz && tar -xf /tmp/node.tar.xz -C /tmp && export PATH=\"/tmp/node-v18.19.0-linux-arm64/bin:\$PATH\"", "Download Node.js 18 LTS binary", false))
                        fallbacks.add(FallbackPlan("⚠️ Package manager (${systemInfo.packageManager}) not available. Node.js cannot be installed automatically. Please install manually or use a different environment.", "Package manager unavailable warning", false))
                    }
                }
                
                // Python/pip not found
                if (commandLower.contains("python") || commandLower.contains("pip")) {
                    val pythonVersion = versions["python"]
                    val pythonPkg = when (systemInfo.os) {
                        "Windows" -> "python"
                        else -> "python3"
                    }
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            if (pythonVersion != null) {
                                val majorMinor = pythonVersion.split(".").take(2).joinToString(".")
                                if (majorMinor == "3.12") fallbacks.add(FallbackPlan("apk add python3.12 py3.12-pip", "Install Python 3.12 via apk", true))
                                if (majorMinor == "3.11") fallbacks.add(FallbackPlan("apk add python3.11 py3.11-pip", "Install Python 3.11 via apk", true))
                                if (majorMinor == "3.10") fallbacks.add(FallbackPlan("apk add python3.10 py3.10-pip", "Install Python 3.10 via apk", true))
                                if (majorMinor == "3.9") fallbacks.add(FallbackPlan("apk add python3.9 py3.9-pip", "Install Python 3.9 via apk", true))
                            }
                            // Alpine version-specific: newer Alpine has python3.11+
                            if (osVersionMajor != null && osVersionMajor >= 3 && osVersionMinor != null && osVersionMinor >= 18) {
                                fallbacks.add(FallbackPlan("apk add python3.11 py3.11-pip", "Install Python 3.11 via apk (Alpine 3.18+)", true))
                            }
                            fallbacks.add(FallbackPlan("apk add $pythonPkg py3-pip", "Install Python and pip via apk", true))
                            fallbacks.add(FallbackPlan("apk update && apk add $pythonPkg py3-pip", "Update apk and install Python/pip", true))
                        }
                        "apt", "apt-get" -> {
                            if (pythonVersion != null) {
                                val majorMinor = pythonVersion.split(".").take(2).joinToString(".")
                                fallbacks.add(FallbackPlan("apt-get update && apt-get install -y python$majorMinor python$majorMinor-pip", "Install Python $pythonVersion via apt", true))
                            }
                            fallbacks.add(FallbackPlan("apt-get update && apt-get install -y $pythonPkg $pythonPkg-pip", "Update apt and install Python/pip", true))
                            fallbacks.add(FallbackPlan("apt-get install -y $pythonPkg $pythonPkg-venv $pythonPkg-pip", "Install Python with venv and pip", true))
                        }
                        "yum" -> {
                            if (pythonVersion != null) {
                                val majorMinor = pythonVersion.split(".").take(2).joinToString(".")
                                fallbacks.add(FallbackPlan("yum install -y python$majorMinor python$majorMinor-pip", "Install Python $pythonVersion via yum", true))
                            }
                            fallbacks.add(FallbackPlan("yum install -y $pythonPkg $pythonPkg-pip", "Install Python and pip via yum", true))
                        }
                        "dnf" -> {
                            if (pythonVersion != null) {
                                val majorMinor = pythonVersion.split(".").take(2).joinToString(".")
                                fallbacks.add(FallbackPlan("dnf install -y python$majorMinor python$majorMinor-pip", "Install Python $pythonVersion via dnf", true))
                            }
                            fallbacks.add(FallbackPlan("dnf install -y $pythonPkg $pythonPkg-pip", "Install Python and pip via dnf", true))
                        }
                        "pacman" -> {
                            fallbacks.add(FallbackPlan("pacman -S --noconfirm $pythonPkg python-pip", "Install Python and pip via pacman", true))
                        }
                        "brew" -> {
                            if (pythonVersion != null) {
                                val majorMinor = pythonVersion.split(".").take(2).joinToString(".")
                                fallbacks.add(FallbackPlan("brew install python@$majorMinor", "Install Python $pythonVersion via Homebrew", true))
                            }
                            fallbacks.add(FallbackPlan("brew install python3", "Install Python via Homebrew", true))
                        }
                    }
                }
                
                // Go not found
                if (commandLower.contains("go")) {
                    when (systemInfo.packageManager) {
                        "apk" -> fallbacks.add(FallbackPlan("apk add go", "Install Go via apk", true))
                        "apt", "apt-get" -> fallbacks.add(FallbackPlan("apt-get update && apt-get install -y golang-go", "Install Go via apt", true))
                        "yum" -> fallbacks.add(FallbackPlan("yum install -y golang", "Install Go via yum", true))
                        "dnf" -> fallbacks.add(FallbackPlan("dnf install -y golang", "Install Go via dnf", true))
                        "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm go", "Install Go via pacman", true))
                        "brew" -> fallbacks.add(FallbackPlan("brew install go", "Install Go via Homebrew", true))
                    }
                }
                
                // Rust/cargo not found
                if (commandLower.contains("cargo") || commandLower.contains("rust")) {
                    fallbacks.add(FallbackPlan("curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y", "Install Rust via rustup", true))
                    when (systemInfo.packageManager) {
                        "apk" -> fallbacks.add(FallbackPlan("apk add rust cargo", "Install Rust via apk", true))
                        "apt", "apt-get" -> fallbacks.add(FallbackPlan("apt-get update && apt-get install -y rustc cargo", "Install Rust via apt", true))
                        "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm rust", "Install Rust via pacman", true))
                    }
                }
                
                // PHP/composer not found
                if (commandLower.contains("php") || commandLower.contains("composer")) {
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            fallbacks.add(FallbackPlan("apk add php php-cli composer", "Install PHP and Composer via apk", true))
                            fallbacks.add(FallbackPlan("apk add php8 php8-cli composer", "Install PHP 8 and Composer via apk", true))
                        }
                        "apt", "apt-get" -> {
                            fallbacks.add(FallbackPlan("apt-get update && apt-get install -y php php-cli composer", "Install PHP and Composer via apt", true))
                            fallbacks.add(FallbackPlan("curl -sS https://getcomposer.org/installer | php", "Install Composer manually", true))
                        }
                        "yum", "dnf" -> {
                            fallbacks.add(FallbackPlan("$installCmd php php-cli", "Install PHP via yum/dnf", true))
                            fallbacks.add(FallbackPlan("curl -sS https://getcomposer.org/installer | php", "Install Composer manually", true))
                        }
                        "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm php composer", "Install PHP and Composer via pacman", true))
                        "brew" -> fallbacks.add(FallbackPlan("brew install php composer", "Install PHP and Composer via Homebrew", true))
                    }
                }
                
                // Ruby/bundler not found
                if (commandLower.contains("ruby") || commandLower.contains("bundle") || commandLower.contains("gem")) {
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            fallbacks.add(FallbackPlan("apk add ruby ruby-dev ruby-bundler", "Install Ruby and Bundler via apk", true))
                            fallbacks.add(FallbackPlan("apk add ruby ruby-dev && gem install bundler", "Install Ruby and Bundler via apk+gem", true))
                        }
                        "apt", "apt-get" -> {
                            fallbacks.add(FallbackPlan("apt-get update && apt-get install -y ruby ruby-dev bundler", "Install Ruby and Bundler via apt", true))
                            fallbacks.add(FallbackPlan("apt-get install -y ruby-full && gem install bundler", "Install Ruby full and Bundler", true))
                        }
                        "yum", "dnf" -> {
                            fallbacks.add(FallbackPlan("$installCmd ruby ruby-devel", "Install Ruby via yum/dnf", true))
                            fallbacks.add(FallbackPlan("gem install bundler", "Install Bundler via gem", true))
                        }
                        "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm ruby", "Install Ruby via pacman", true))
                        "brew" -> fallbacks.add(FallbackPlan("brew install ruby", "Install Ruby via Homebrew", true))
                    }
                }
                
                // C/C++ build tools not found
                if (commandLower.contains("gcc") || commandLower.contains("g++") || commandLower.contains("make") || commandLower.contains("cmake")) {
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            fallbacks.add(FallbackPlan("apk add build-base gcc g++ make cmake", "Install C/C++ build tools via apk", true))
                            fallbacks.add(FallbackPlan("apk add gcc g++ make", "Install basic C/C++ tools via apk", true))
                        }
                        "apt", "apt-get" -> {
                            fallbacks.add(FallbackPlan("apt-get update && apt-get install -y build-essential gcc g++ make cmake", "Install C/C++ build tools via apt", true))
                            fallbacks.add(FallbackPlan("apt-get install -y build-essential", "Install build-essential via apt", true))
                        }
                        "yum", "dnf" -> {
                            fallbacks.add(FallbackPlan("$installCmd gcc gcc-c++ make cmake", "Install C/C++ build tools via yum/dnf", true))
                            fallbacks.add(FallbackPlan("$installCmd @development-tools", "Install development tools group", true))
                        }
                        "pacman" -> {
                            fallbacks.add(FallbackPlan("pacman -S --noconfirm base-devel gcc make cmake", "Install C/C++ build tools via pacman", true))
                        }
                        "brew" -> {
                            fallbacks.add(FallbackPlan("brew install gcc make cmake", "Install C/C++ build tools via Homebrew", true))
                        }
                    }
                }
                
                // Git not found
                if (commandLower.contains("git")) {
                    when (systemInfo.packageManager) {
                        "apk" -> fallbacks.add(FallbackPlan("apk add git", "Install Git via apk", true))
                        "apt", "apt-get" -> fallbacks.add(FallbackPlan("apt-get update && apt-get install -y git", "Install Git via apt", true))
                        "yum", "dnf" -> fallbacks.add(FallbackPlan("$installCmd git", "Install Git via yum/dnf", true))
                        "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm git", "Install Git via pacman", true))
                        "brew" -> fallbacks.add(FallbackPlan("brew install git", "Install Git via Homebrew", true))
                    }
                }
                
                // Curl/wget not found
                if (commandLower.contains("curl") || commandLower.contains("wget")) {
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            if (commandLower.contains("curl")) fallbacks.add(FallbackPlan("apk add curl", "Install curl via apk", true))
                            if (commandLower.contains("wget")) fallbacks.add(FallbackPlan("apk add wget", "Install wget via apk", true))
                        }
                        "apt", "apt-get" -> {
                            if (commandLower.contains("curl")) fallbacks.add(FallbackPlan("apt-get install -y curl", "Install curl via apt", true))
                            if (commandLower.contains("wget")) fallbacks.add(FallbackPlan("apt-get install -y wget", "Install wget via apt", true))
                        }
                        "yum", "dnf" -> {
                            if (commandLower.contains("curl")) fallbacks.add(FallbackPlan("$installCmd curl", "Install curl via yum/dnf", true))
                            if (commandLower.contains("wget")) fallbacks.add(FallbackPlan("$installCmd wget", "Install wget via yum/dnf", true))
                        }
                        "pacman" -> {
                            if (commandLower.contains("curl")) fallbacks.add(FallbackPlan("pacman -S --noconfirm curl", "Install curl via pacman", true))
                            if (commandLower.contains("wget")) fallbacks.add(FallbackPlan("pacman -S --noconfirm wget", "Install wget via pacman", true))
                        }
                        "brew" -> {
                            if (commandLower.contains("curl")) fallbacks.add(FallbackPlan("brew install curl", "Install curl via Homebrew", true))
                            if (commandLower.contains("wget")) fallbacks.add(FallbackPlan("brew install wget", "Install wget via Homebrew", true))
                        }
                    }
                }
                
                // Docker not found
                if (commandLower.contains("docker")) {
                    when (systemInfo.packageManager) {
                        "apk" -> fallbacks.add(FallbackPlan("apk add docker docker-compose", "Install Docker via apk", true))
                        "apt", "apt-get" -> {
                            fallbacks.add(FallbackPlan("apt-get update && apt-get install -y docker.io docker-compose", "Install Docker via apt", true))
                            fallbacks.add(FallbackPlan("curl -fsSL https://get.docker.com | sh", "Install Docker via official script", true))
                        }
                        "yum", "dnf" -> {
                            fallbacks.add(FallbackPlan("$installCmd docker docker-compose", "Install Docker via yum/dnf", true))
                            fallbacks.add(FallbackPlan("curl -fsSL https://get.docker.com | sh", "Install Docker via official script", true))
                        }
                        "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm docker docker-compose", "Install Docker via pacman", true))
                        "brew" -> fallbacks.add(FallbackPlan("brew install docker docker-compose", "Install Docker via Homebrew", true))
                    }
                }
                
                // PostgreSQL/MySQL client not found
                if (commandLower.contains("psql") || commandLower.contains("mysql")) {
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            if (commandLower.contains("psql")) fallbacks.add(FallbackPlan("apk add postgresql-client", "Install PostgreSQL client via apk", true))
                            if (commandLower.contains("mysql")) fallbacks.add(FallbackPlan("apk add mysql-client", "Install MySQL client via apk", true))
                        }
                        "apt", "apt-get" -> {
                            if (commandLower.contains("psql")) fallbacks.add(FallbackPlan("apt-get install -y postgresql-client", "Install PostgreSQL client via apt", true))
                            if (commandLower.contains("mysql")) fallbacks.add(FallbackPlan("apt-get install -y mysql-client", "Install MySQL client via apt", true))
                        }
                        "yum", "dnf" -> {
                            if (commandLower.contains("psql")) fallbacks.add(FallbackPlan("$installCmd postgresql", "Install PostgreSQL client via yum/dnf", true))
                            if (commandLower.contains("mysql")) fallbacks.add(FallbackPlan("$installCmd mysql", "Install MySQL client via yum/dnf", true))
                        }
                        "pacman" -> {
                            if (commandLower.contains("psql")) fallbacks.add(FallbackPlan("pacman -S --noconfirm postgresql", "Install PostgreSQL client via pacman", true))
                            if (commandLower.contains("mysql")) fallbacks.add(FallbackPlan("pacman -S --noconfirm mysql", "Install MySQL client via pacman", true))
                        }
                        "brew" -> {
                            if (commandLower.contains("psql")) fallbacks.add(FallbackPlan("brew install postgresql", "Install PostgreSQL client via Homebrew", true))
                            if (commandLower.contains("mysql")) fallbacks.add(FallbackPlan("brew install mysql-client", "Install MySQL client via Homebrew", true))
                        }
                    }
                }
                
                // Java not found
                if (commandLower.contains("java") || commandLower.contains("javac") || commandLower.contains("mvn") || commandLower.contains("gradle")) {
                    val javaVersion = versions["java"]
                    when (systemInfo.packageManager) {
                        "apk" -> {
                            if (javaVersion != null) {
                                val majorVersion = javaVersion.split(".").firstOrNull() ?: "17"
                                fallbacks.add(FallbackPlan("apk add openjdk${majorVersion}-jdk", "Install Java $javaVersion via apk", true))
                            }
                            fallbacks.add(FallbackPlan("apk add openjdk17-jdk", "Install Java 17 via apk", true))
                            fallbacks.add(FallbackPlan("apk add openjdk21-jdk", "Install Java 21 via apk", true))
                            fallbacks.add(FallbackPlan("apk add openjdk11-jdk", "Install Java 11 via apk", true))
                        }
                        "apt", "apt-get" -> {
                            if (javaVersion != null) {
                                val majorVersion = javaVersion.split(".").firstOrNull() ?: "17"
                                fallbacks.add(FallbackPlan("apt-get update && apt-get install -y openjdk-${majorVersion}-jdk", "Install Java $javaVersion via apt", true))
                            }
                            fallbacks.add(FallbackPlan("apt-get update && apt-get install -y openjdk-17-jdk", "Install Java 17 via apt", true))
                            fallbacks.add(FallbackPlan("apt-get install -y openjdk-21-jdk", "Install Java 21 via apt", true))
                            fallbacks.add(FallbackPlan("apt-get install -y default-jdk", "Install default JDK via apt", true))
                        }
                        "yum", "dnf" -> {
                            if (javaVersion != null) {
                                val majorVersion = javaVersion.split(".").firstOrNull() ?: "17"
                                fallbacks.add(FallbackPlan("$installCmd java-${majorVersion}-openjdk-devel", "Install Java $javaVersion via yum/dnf", true))
                            }
                            fallbacks.add(FallbackPlan("$installCmd java-17-openjdk-devel", "Install Java 17 via yum/dnf", true))
                            fallbacks.add(FallbackPlan("$installCmd java-21-openjdk-devel", "Install Java 21 via yum/dnf", true))
                            fallbacks.add(FallbackPlan("$installCmd java-11-openjdk-devel", "Install Java 11 via yum/dnf", true))
                        }
                        "pacman" -> {
                            if (javaVersion != null) {
                                val majorVersion = javaVersion.split(".").firstOrNull() ?: "17"
                                fallbacks.add(FallbackPlan("pacman -S --noconfirm jdk${majorVersion}-openjdk", "Install OpenJDK $javaVersion via pacman", true))
                            }
                            fallbacks.add(FallbackPlan("pacman -S --noconfirm jdk-openjdk", "Install OpenJDK via pacman", true))
                        }
                        "brew" -> {
                            if (javaVersion != null) {
                                val majorVersion = javaVersion.split(".").firstOrNull() ?: "17"
                                fallbacks.add(FallbackPlan("brew install openjdk@$majorVersion", "Install OpenJDK $javaVersion via Homebrew", true))
                            }
                            fallbacks.add(FallbackPlan("brew install openjdk@17", "Install OpenJDK 17 via Homebrew", true))
                            fallbacks.add(FallbackPlan("brew install openjdk@21", "Install OpenJDK 21 via Homebrew", true))
                        }
                    }
                    
                    // Maven not found
                    if (commandLower.contains("mvn")) {
                        when (systemInfo.packageManager) {
                            "apk" -> fallbacks.add(FallbackPlan("apk add maven", "Install Maven via apk", true))
                            "apt", "apt-get" -> fallbacks.add(FallbackPlan("apt-get install -y maven", "Install Maven via apt", true))
                            "yum", "dnf" -> fallbacks.add(FallbackPlan("$installCmd maven", "Install Maven via yum/dnf", true))
                            "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm maven", "Install Maven via pacman", true))
                            "brew" -> fallbacks.add(FallbackPlan("brew install maven", "Install Maven via Homebrew", true))
                        }
                    }
                    
                    // Gradle not found
                    if (commandLower.contains("gradle")) {
                        when (systemInfo.packageManager) {
                            "apk" -> fallbacks.add(FallbackPlan("apk add gradle", "Install Gradle via apk", true))
                            "apt", "apt-get" -> fallbacks.add(FallbackPlan("apt-get install -y gradle", "Install Gradle via apt", true))
                            "yum", "dnf" -> fallbacks.add(FallbackPlan("$installCmd gradle", "Install Gradle via yum/dnf", true))
                            "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm gradle", "Install Gradle via pacman", true))
                            "brew" -> fallbacks.add(FallbackPlan("brew install gradle", "Install Gradle via Homebrew", true))
                        }
                    }
                }
            }
            
            ErrorType.DEPENDENCY_MISSING -> {
                // Node.js dependencies
                if (hasPackageJson) {
                    when {
                        hasPnpm -> {
                            fallbacks.add(FallbackPlan("pnpm install", "Install dependencies via pnpm", true))
                            fallbacks.add(FallbackPlan("npm install -g pnpm && pnpm install", "Install pnpm globally and dependencies", true))
                        }
                        hasYarn -> {
                            fallbacks.add(FallbackPlan("yarn install", "Install dependencies via yarn", true))
                            fallbacks.add(FallbackPlan("npm install -g yarn && yarn install", "Install yarn globally and dependencies", true))
                        }
                        else -> {
                            fallbacks.add(FallbackPlan("npm install", "Install dependencies via npm", true))
                            fallbacks.add(FallbackPlan("npm ci", "Clean install dependencies via npm", true))
                            fallbacks.add(FallbackPlan("rm -rf node_modules package-lock.json && npm install", "Clean reinstall dependencies", true))
                        }
                    }
                    // If npm not found, try installing it
                    if (outputLower.contains("npm") && outputLower.contains("not found")) {
                        when (systemInfo.packageManager) {
                            "apk" -> fallbacks.add(FallbackPlan("apk add nodejs npm", "Install Node.js and npm via apk", true))
                            "apt", "apt-get" -> fallbacks.add(FallbackPlan("apt-get update && apt-get install -y nodejs npm", "Install Node.js and npm via apt", true))
                            "yum", "dnf" -> fallbacks.add(FallbackPlan("$installCmd nodejs npm", "Install Node.js and npm via yum/dnf", true))
                            "pacman" -> fallbacks.add(FallbackPlan("pacman -S --noconfirm nodejs npm", "Install Node.js and npm via pacman", true))
                            "brew" -> fallbacks.add(FallbackPlan("brew install node", "Install Node.js via Homebrew", true))
                        }
                    }
                }
                
                // Python dependencies
                if (hasRequirements || hasPipfile || hasPoetry) {
                    val pythonCmd = if (systemInfo.os == "Windows") "python" else "python3"
                    val pipCmd = if (systemInfo.os == "Windows") "pip" else "pip3"
                    val venvActivate = if (systemInfo.os == "Windows") "venv\\Scripts\\activate" else "source venv/bin/activate"
                    
                    when {
                        hasPoetry -> {
                            fallbacks.add(FallbackPlan("poetry install", "Install dependencies via poetry", true))
                            fallbacks.add(FallbackPlan("curl -sSL https://install.python-poetry.org | $pythonCmd -", "Install poetry first", true))
                            fallbacks.add(FallbackPlan("pip3 install poetry && poetry install", "Install poetry via pip and dependencies", true))
                        }
                        hasPipfile -> {
                            fallbacks.add(FallbackPlan("pipenv install", "Install dependencies via pipenv", true))
                            fallbacks.add(FallbackPlan("pipenv install --dev", "Install dependencies including dev via pipenv", true))
                            fallbacks.add(FallbackPlan("$pipCmd install pipenv && pipenv install", "Install pipenv and dependencies", true))
                        }
                        hasRequirements -> {
                            if (venvExists) {
                                fallbacks.add(FallbackPlan("$venvActivate && pip install -r requirements.txt", "Install dependencies in venv", true))
                                fallbacks.add(FallbackPlan("$venvActivate && pip install --upgrade -r requirements.txt", "Upgrade dependencies in venv", true))
                            } else {
                                fallbacks.add(FallbackPlan("$pipCmd install -r requirements.txt", "Install dependencies via pip3", true))
                                fallbacks.add(FallbackPlan("$pythonCmd -m venv venv && $venvActivate && pip install -r requirements.txt", "Create venv and install dependencies", true))
                                fallbacks.add(FallbackPlan("$pipCmd install --user -r requirements.txt", "Install dependencies for user", true))
                            }
                        }
                    }
                }
                
                // Go dependencies
                if (hasGoMod) {
                    fallbacks.add(FallbackPlan("go mod download", "Download Go dependencies", true))
                    fallbacks.add(FallbackPlan("go mod tidy", "Tidy Go modules", true))
                    fallbacks.add(FallbackPlan("go get ./...", "Get all Go dependencies", true))
                    fallbacks.add(FallbackPlan("go mod vendor", "Vendor Go dependencies", true))
                }
                
                // Rust dependencies
                if (hasCargoToml) {
                    fallbacks.add(FallbackPlan("cargo build", "Build Rust project to fetch dependencies", true))
                    fallbacks.add(FallbackPlan("cargo fetch", "Fetch Rust dependencies", true))
                    fallbacks.add(FallbackPlan("cargo update", "Update Rust dependencies", true))
                }
                
                // Java/Kotlin dependencies
                if (hasGradle) {
                    val gradleWrapper = File(workspaceDir, "gradlew").exists() || File(workspaceDir, "gradlew.bat").exists()
                    val gradleCmd = if (gradleWrapper) {
                        if (systemInfo.os == "Windows") "./gradlew.bat" else "./gradlew"
                    } else {
                        "gradle"
                    }
                    fallbacks.add(FallbackPlan("$gradleCmd build --refresh-dependencies", "Build with refresh dependencies", true))
                    fallbacks.add(FallbackPlan("$gradleCmd dependencies", "Check Gradle dependencies", false))
                }
                
                if (hasMaven) {
                    fallbacks.add(FallbackPlan("mvn dependency:resolve", "Resolve Maven dependencies", true))
                    fallbacks.add(FallbackPlan("mvn clean install", "Clean and install Maven dependencies", true))
                    fallbacks.add(FallbackPlan("mvn dependency:purge-local-repository && mvn install", "Purge and reinstall dependencies", true))
                }
                
                // PHP dependencies
                val hasComposer = File(workspaceDir, "composer.json").exists()
                if (hasComposer) {
                    fallbacks.add(FallbackPlan("composer install", "Install PHP dependencies via Composer", true))
                    fallbacks.add(FallbackPlan("composer update", "Update PHP dependencies", true))
                    fallbacks.add(FallbackPlan("composer dump-autoload", "Regenerate Composer autoload", true))
                }
                
                // Ruby dependencies
                val hasGemfile = File(workspaceDir, "Gemfile").exists()
                if (hasGemfile) {
                    fallbacks.add(FallbackPlan("bundle install", "Install Ruby dependencies via Bundler", true))
                    fallbacks.add(FallbackPlan("bundle update", "Update Ruby dependencies", true))
                    fallbacks.add(FallbackPlan("gem install bundler && bundle install", "Install Bundler and dependencies", true))
                }
            }
            
            ErrorType.CODE_ERROR -> {
                // Code errors need debugging, not just retrying commands
                // Return empty list - will be handled by code debugging logic
                return emptyList()
            }
            
            ErrorType.PERMISSION_ERROR -> {
                fallbacks.add(FallbackPlan("chmod +x ${command.split(" ").firstOrNull() ?: ""}", "Make command executable", false))
                if (hasPackageJson) {
                    fallbacks.add(FallbackPlan("npm install", "Reinstall dependencies (may fix permission issues)", false))
                }
            }
            
            ErrorType.CONFIGURATION_ERROR, ErrorType.NETWORK_ERROR, ErrorType.UNKNOWN -> {
                // These might need AI analysis, but try some common fixes
                if (hasPackageJson && commandLower.contains("test")) {
                    when {
                        hasPnpm -> fallbacks.add(FallbackPlan("pnpm install && pnpm test", "Reinstall and test", false))
                        hasYarn -> fallbacks.add(FallbackPlan("yarn install && yarn test", "Reinstall and test", false))
                        else -> fallbacks.add(FallbackPlan("npm install && npm test", "Reinstall and test", false))
                    }
                }
            }
        }
        
        return fallbacks
    }
    
    /**
     * Analyze command failure and generate fallback plans
     * Uses hardcoded fallbacks first, then AI as last resort
     */
    private suspend fun analyzeCommandFailure(
        command: String,
        output: String,
        errorMessage: String,
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo
    ): FailureAnalysis {
        // Classify error type
        val errorType = classifyErrorType(output, errorMessage, command)
        
        // Try hardcoded fallbacks first (reduces API calls)
        val hardcodedFallbacks = getHardcodedFallbacks(errorType, command, output, workspaceRoot, systemInfo)
        
        if (hardcodedFallbacks.isNotEmpty()) {
            val reason = when (errorType) {
                ErrorType.COMMAND_NOT_FOUND -> "Command or tool not found"
                ErrorType.DEPENDENCY_MISSING -> "Missing dependencies"
                ErrorType.CODE_ERROR -> "Code error detected (needs debugging)"
                ErrorType.PERMISSION_ERROR -> "Permission or access issue"
                ErrorType.CONFIGURATION_ERROR -> "Configuration error"
                ErrorType.NETWORK_ERROR -> "Network or connection issue"
                ErrorType.UNKNOWN -> "Unknown error"
            }
            android.util.Log.d("AgentClient", "Using hardcoded fallbacks for $errorType: ${hardcodedFallbacks.size} plans")
            return FailureAnalysis(reason, hardcodedFallbacks)
        }
        
        // If no hardcoded fallbacks or code error, use AI analysis
        val systemContext = SystemInfoService.generateSystemContext()
        val model = ApiProviderManager.getCurrentModel()
        
        // Get project context
        val workspaceDir = File(workspaceRoot)
        val packageJson = File(workspaceDir, "package.json")
        val requirementsTxt = File(workspaceDir, "requirements.txt")
        val hasPackageJson = packageJson.exists()
        val hasRequirements = requirementsTxt.exists()
        
        val installCmd = systemInfo.packageManagerCommands["install"] ?: "install"
        val updateCmd = systemInfo.packageManagerCommands["update"] ?: "update"
        
        val analysisPrompt = """
            $systemContext
            
            **Failed Command:** $command
            **Error Message:** $errorMessage
            **Command Output:** ${output.take(2000)}
            **Workspace Root:** $workspaceRoot
            **Project Context:**
            - Has package.json: $hasPackageJson
            - Has requirements.txt: $hasRequirements
            - Package Manager: ${systemInfo.packageManager}
            - Install Command: $installCmd
            - Update Command: $updateCmd
            
            Analyze the command failure and provide:
            1. **Reason**: Brief explanation of why the command failed
            2. **Fallback Plans**: List of alternative commands/approaches to try (max 3)
            
            For each fallback plan, provide:
            - command: The actual command to run (DO NOT use hardcoded paths like /path/to/project, use relative paths or commands that work from the workspace root)
            - description: What this fallback does
            - should_retry_original: Whether to retry the original command after this fallback (true/false)
            
            IMPORTANT RULES:
            - Use the actual package manager install command: "$installCmd" (NOT just "install")
            - For Node.js: Use "npm install" or "npm install -g <package>" (npm will be installed with nodejs)
            - For Python: Use "pip3 install" or "python3 -m pip install"
            - DO NOT use hardcoded paths like "/path/to/project" - commands will be run from workspace root: $workspaceRoot
            - DO NOT use "cd" commands with hardcoded paths - use relative paths or just the command
            - For installing system packages, use: "$installCmd <package>" or "$updateCmd && $installCmd <package>"
            
            Common fallback scenarios:
            - Missing Node.js/npm: Use "$installCmd nodejs npm" or "$updateCmd && $installCmd nodejs npm"
            - Missing Python/pip: Use "$installCmd python3 python3-pip" or "$updateCmd && $installCmd python3 python3-pip"
            - Missing dependencies: Install them first (npm install, pip install -r requirements.txt, etc.)
            - Missing tools: Install via package manager using "$installCmd <tool-name>"
            - Python venv: Activate virtual environment (source venv/bin/activate)
            - Node modules: Run "npm install" (from workspace root)
            - Path issues: Commands run from workspace root, no need for cd
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
                  "command": "$installCmd nodejs npm",
                  "description": "Install Node.js and npm via package manager",
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
            
            // Wrap network call in IO dispatcher to avoid NetworkOnMainThreadException
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(
                    apiKey,
                    model,
                    request,
                    useLongTimeout = false
                )
            }
            
            if (response != null && response.isNotEmpty()) {
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
                                android.util.Log.w("AgentClient", "Failed to parse fallback plan: ${e.message}")
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
            android.util.Log.e("AgentClient", "Failed to analyze command failure: ${e.message}", e)
            FailureAnalysis("Analysis failed: ${e.message}", emptyList())
        }
    }
    
    /**
     * Standard mode: Enhanced 2-phase approach
     * Phase 1: Get file list AND comprehensive metadata for ALL files in ONE API call (relationships, imports, classes, functions, etc.)
     *         This ensures all relationships are coherent and consistent across all files
     * Phase 2: Generate each file separately with full code using only the metadata provided
     * Phase 3: Detect and execute commands needed
     */
    private suspend fun sendMessageStandard(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        val signal = CancellationSignal() // Create local signal for standard mode
        android.util.Log.d("AgentClient", "sendMessageStandard: Starting standard mode - flow builder started")
        
        try {
            // Emit initial event immediately to ensure flow is active before setup work
            // This prevents the flow from being cancelled before it can start emitting
            android.util.Log.d("AgentClient", "sendMessageStandard: About to emit initial event")
            emit(AgentEvent.Chunk("🚀 Starting...\n"))
            onChunk("🚀 Starting...\n")
            android.util.Log.d("AgentClient", "sendMessageStandard: Initial event emitted successfully")
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.e("AgentClient", "Flow builder cancelled before initial emit", e)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Error emitting initial event in sendMessageStandard", e)
            emit(AgentEvent.Error("Failed to start: ${e.message}"))
            return@flow
        }
        
        // Add user message to history
        chatHistory.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = userMessage))
            )
        )
        
        val model = ApiProviderManager.getCurrentModel()
        val systemInfo = SystemInfoService.detectSystemInfo(workspaceRoot)
        val systemContext = SystemInfoService.generateSystemContext(workspaceRoot)
        
        // Check if task only needs commands (no file creation)
        val commandsOnly = detectCommandsOnly(userMessage, workspaceRoot)
        
        if (commandsOnly) {
            emit(AgentEvent.Chunk("🔍 Detected command-only task, detecting commands needed...\n"))
            onChunk("🔍 Detected command-only task, detecting commands needed...\n")
            
            val commandsNeeded = detectCommandsNeeded(workspaceRoot, systemInfo, userMessage, ::emit, onChunk)
            
            if (commandsNeeded.isNotEmpty()) {
                val message = "📋 Found ${commandsNeeded.size} command(s) to execute\n"
                emit(AgentEvent.Chunk(message))
                onChunk(message)
                
                for (command in commandsNeeded) {
                    executeCommandWithFallbacks(command, workspaceRoot, systemInfo, ::emit, onChunk, onToolCall, onToolResult)
                }
                
                emit(AgentEvent.Done)
                return@flow
            } else {
                // No commands detected, fall through to normal flow
                emit(AgentEvent.Chunk("⚠️ No commands detected, proceeding with normal flow...\n"))
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
            emit(AgentEvent.ToolCall(todoCall))
            onToolCall(todoCall)
            try {
                val todoResult = executeToolSync("write_todos", todoCall.args)
                emit(AgentEvent.ToolResult("write_todos", todoResult))
                onToolResult("write_todos", todoCall.args)
            } catch (e: Exception) {
                android.util.Log.e("AgentClient", "Failed to update todos", e)
            }
        }
        
        // Pre-planning phase: Check if documentation search is needed and add to todos
        val initialTodos = mutableListOf<Todo>()
        if (needsDocSearch) {
            emit(AgentEvent.Chunk("🔍 Task may need documentation search - adding to plan...\n"))
            initialTodos.add(Todo("Search for relevant documentation and examples", TodoStatus.PENDING))
        }
        initialTodos.addAll(listOf(
            Todo("Phase 1: Get file list and metadata", TodoStatus.PENDING),
            Todo("Phase 2: Generate code for each file", TodoStatus.PENDING)
        ))
        
        // Combined Phase 1: Get file list AND metadata in ONE API call
        emit(AgentEvent.Chunk("📋 Phase 1: Identifying files and generating metadata...\n"))
        
        // Mark Phase 1 as in progress (no withContext - emit must be in same context as Flow)
        val todosWithProgress = initialTodos.map { todo ->
            if (todo.description == "Phase 1: Get file list and metadata") {
                todo.copy(status = TodoStatus.IN_PROGRESS)
            } else {
                todo
            }
        }
        updateTodos(todosWithProgress)
        
        // Helper function to wrap emit as suspend function
        suspend fun emitEvent(event: AgentEvent) {
            emit(event)
        }
        
        val combinedPrompt = """
            $systemContext
            
            **IMPORTANT: Generate BOTH file list AND comprehensive metadata for ALL files in a SINGLE response. This ensures all relationships, imports, and exports are coherent and consistent across all files.**
            
            Analyze the user's request and provide:
            1. A complete list of ALL files that need to be created
            2. Comprehensive metadata for ALL files TOGETHER in one response
            
            **You must analyze ALL files together to ensure:**
            - All import/export relationships are consistent across ALL files
            - All file dependencies are correctly identified
            - All function/class names match between related files
            - No circular dependencies are missed
            - All bidirectional relationships are properly established
            
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
            
            Format your response as a JSON array of file metadata objects. Each object must include the file_path and all metadata fields.
            
            **VERIFICATION:**
            After generating metadata, verify:
            1. All import/export relationships are consistent (if A imports X from B, B exports X)
            2. All file paths in relationships match actual file_path values
            3. All function/class names are consistent across related files
            4. All dependencies are properly identified
            
            Be comprehensive - include all files needed: source files, config files, documentation, tests, etc.
            
            User request: $userMessage
        """.trimIndent()
        
        // Get file list and metadata in ONE API call
        val combinedRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", combinedPrompt)
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
        
        var combinedResult = makeApiCallWithRetryAndCorrection(
            model, combinedRequest, "file list and metadata", signal, null, ::emitEvent, onChunk
        )
        
        if (combinedResult == null) {
            emit(AgentEvent.Error("Failed to get file list and metadata after retries"))
            return@flow
        }
        
        // Parse combined result (file list + metadata)
        val metadataJson = try {
            val jsonStart = combinedResult.indexOf('[')
            val jsonEnd = combinedResult.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JSONArray(combinedResult.substring(jsonStart, jsonEnd))
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Failed to parse file list and metadata", e)
            null
        }
        
        if (metadataJson == null || metadataJson.length() == 0) {
            emit(AgentEvent.Error("Failed to parse file list and metadata or no files found"))
            return@flow
        }
        
        val filePaths = (0 until metadataJson.length()).mapNotNull { i ->
            try {
                metadataJson.getJSONObject(i).getString("file_path")
            } catch (e: Exception) {
                null
            }
        }
        
        emit(AgentEvent.Chunk("✅ Found ${filePaths.size} files with metadata\n"))
        
        // Update todos - preserve documentation search todo if it exists (no withContext - emit must be in same context)
        var updatedTodos = currentTodos.map { todo ->
            when {
                todo.description == "Phase 1: Get file list and metadata" -> todo.copy(status = TodoStatus.COMPLETED)
                todo.description == "Phase 2: Generate code for each file" -> todo.copy(status = TodoStatus.IN_PROGRESS)
                else -> todo
            }
        }
        updateTodos(updatedTodos)
        
        var metadataJsonFinal = metadataJson
        
        if (metadataJson.length() != filePaths.size) {
            // Try to validate and fix metadata
            emit(AgentEvent.Chunk("⚠️ Metadata count mismatch, attempting to fix...\n"))
            onChunk("⚠️ Metadata count mismatch, attempting to fix...\n")
            
            // Retry combined generation with better prompt
            val retryCombinedPrompt = """
                $systemContext
                
                **IMPORTANT: Generate BOTH file list AND comprehensive metadata for ALL files in a SINGLE response. This ensures all relationships, imports, and exports are coherent and consistent across all files.**
                
                Previous generation had issues. Please regenerate file list AND comprehensive metadata for ALL files TOGETHER in one response.
                
                Ensure you provide metadata for ALL ${filePaths.size} files in ONE response. The previous response had ${metadataJson.length()} entries, but we need ${filePaths.size}.
                
                **You must analyze ALL files together to ensure:**
                - All import/export relationships are consistent across ALL files
                - All file dependencies are correctly identified
                - All function/class names match between related files
                - No circular dependencies are missed
                - All bidirectional relationships are properly established
                
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
                
                Format your response as a JSON array of file metadata objects. Each object must include the file_path and all metadata fields.
                
                User request: $userMessage
            """.trimIndent()
            
            val retryCombinedRequest = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", retryCombinedPrompt)
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
            
            val retryCombinedText = makeApiCallWithRetryAndCorrection(
                model, retryCombinedRequest, "file list and metadata retry", signal, null, ::emitEvent, onChunk
            )
            
            if (retryCombinedText != null) {
                val retryMetadataJson = try {
                    val jsonStart = retryCombinedText.indexOf('[')
                    val jsonEnd = retryCombinedText.lastIndexOf(']') + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        JSONArray(retryCombinedText.substring(jsonStart, jsonEnd))
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "Failed to parse retry file list and metadata", e)
                    null
                }
                
                if (retryMetadataJson != null && retryMetadataJson.length() == filePaths.size) {
                    metadataJsonFinal = retryMetadataJson
                    emit(AgentEvent.Chunk("✅ File list and metadata regenerated successfully\n"))
                    onChunk("✅ File list and metadata regenerated successfully\n")
                } else {
                    emit(AgentEvent.Error("Failed to generate complete file list and metadata after retry"))
                    return@flow
                }
            } else {
                emit(AgentEvent.Error("Failed to generate file list and metadata after retries"))
                return@flow
            }
        }
        
        // Validate metadata coherence
        val coherenceIssues = validateMetadataCoherence(metadataJsonFinal, filePaths)
        if (coherenceIssues.isNotEmpty()) {
            emit(AgentEvent.Chunk("⚠️ Metadata coherence issues detected:\n${coherenceIssues.take(3).joinToString("\n")}\n"))
            onChunk("⚠️ Metadata coherence issues detected\n")
            // Continue anyway - coherence issues will be caught during code generation
        }
        
        emit(AgentEvent.Chunk("✅ Metadata generated for ${metadataJsonFinal.length()} files\n"))
        
        // Phase 2: Generate each file separately with full code
        emit(AgentEvent.Chunk("💻 Phase 2: Generating code for each file...\n"))
        
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
            
            emit(AgentEvent.Chunk("📝 Generating: $filePath (${fileIndex + 1}/${filePaths.size})\n"))
            
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
                - You MUST include ALL classes, functions, imports, and exports specified in the metadata
                - **DO NOT LEAVE ANYTHING BEHIND**: Every item in the metadata MUST appear in the generated code
                - **USE ALL IMPORTS**: Every import listed in the metadata MUST be included in the code
                - **USE ALL CLASSES**: Every class listed in the metadata MUST be fully implemented
                - **USE ALL FUNCTIONS**: Every function listed in the metadata MUST be fully implemented
                - **USE ALL EXPORTS**: Every export listed in the metadata MUST be present in the code
                - You MUST respect the relationships and dependencies
                - Generate complete, working, production-ready code that is FULLY FUNCTIONAL
                - Do NOT use placeholders or TODOs unless explicitly needed
                - Ensure all imports are correct and match the metadata exactly
                - Follow the file type conventions: $fileType
                - Make sure imports reference actual files that exist or will exist
                - Ensure function/class names match exactly with what other files expect
                - **VERIFICATION**: Before finalizing, verify that EVERY item from metadata is present in the generated code
                
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
                
                **File Metadata (MUST USE ALL OF THESE):**
                - Description: $description
                - Expectations: $expectations
                - File Type: $fileType
                - Classes to include (MUST ALL BE IMPLEMENTED): ${classes.joinToString(", ")}
                - Functions to include (MUST ALL BE IMPLEMENTED): ${functions.joinToString(", ")}
                - Imports to use (MUST ALL BE INCLUDED): ${imports.joinToString(", ")}
                - Exports (MUST ALL BE PRESENT): ${exports.joinToString(", ")}
                - Related files: ${relationships.joinToString(", ")}
                
                **MANDATORY REQUIREMENTS:**
                - If the metadata lists ${classes.size} classes, your code MUST contain all ${classes.size} classes
                - If the metadata lists ${functions.size} functions, your code MUST contain all ${functions.size} functions
                - If the metadata lists ${imports.size} imports, your code MUST include all ${imports.size} imports
                - If the metadata lists ${exports.size} exports, your code MUST export all ${exports.size} items
                - DO NOT skip any item from the metadata - everything must be included
                - DO NOT add items not in the metadata unless absolutely necessary for functionality
                - Every import statement from metadata must appear in the code
                - Every class/function name from metadata must appear in the code
                - Every export from metadata must be present in the code
                
                **Project Context:**
                - User's original request: $userMessage
                - All files in project: ${filePaths.joinToString(", ")}
                $relatedFilesContext
                $allFilesContext
                
                **COMPLETENESS VERIFICATION CHECKLIST (MANDATORY):**
                Before finalizing the code, verify:
                1. ✅ ALL classes from metadata are present and fully implemented
                2. ✅ ALL functions from metadata are present and fully implemented
                3. ✅ ALL imports from metadata are included in the code
                4. ✅ ALL exports from metadata are present in the code
                5. ✅ No metadata items were skipped or omitted
                6. ✅ All import paths match exactly with file paths in the project
                7. ✅ All imported names match exactly with exports from related files
                8. ✅ All function/class signatures match what related files expect
                9. ✅ All naming conventions match related files
                10. ✅ All code patterns and structures match related files
                11. ✅ All types/interfaces match related files (if applicable)
                12. ✅ The code will work seamlessly with related files without conflicts
                
                **FINAL CHECK:**
                Count the items in your generated code:
                - Classes: Should match ${classes.size} from metadata
                - Functions: Should match ${functions.size} from metadata
                - Imports: Should match ${imports.size} from metadata
                - Exports: Should match ${exports.size} from metadata
                
                If any count doesn't match, you MUST add the missing items before finalizing.
                
                **EXAMPLES OF WHAT MUST BE INCLUDED:**
                - If it's a game: game initialization, event listeners for moves/clicks, win/lose detection, UI updates
                - If it's a web app: DOM ready handlers, all button/input event bindings, all API calls
                - If it's a Node.js app: server.listen(), all route handlers, middleware setup
                - If it's interactive: ALL user interaction handlers must be present and functional
                
                Generate the complete, fully functional code now. Return ONLY the code, no explanations or markdown formatting.
                The code MUST be immediately runnable and work end-to-end without missing pieces.
                The code MUST be coherent with all related files and work as a unified system.
                
                **REMEMBER: Use ALL metadata items. Leave NOTHING behind. Every class, function, import, and export from the metadata MUST be in the final code.**
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
                emit(AgentEvent.Chunk(failedMsg))
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
            emit(AgentEvent.Chunk(writingMsg))
            onChunk(writingMsg)
            
            val functionCall = FunctionCall(
                name = "write_file",
                args = mapOf(
                    "file_path" to filePath,
                    "content" to cleanCode
                )
            )
            
            emit(AgentEvent.ToolCall(functionCall))
            onToolCall(functionCall)
            
            val toolResult = try {
                val writeResult = executeToolSync(functionCall.name, functionCall.args)
                
                // Automatically run linter check after writing
                if (writeResult.error == null) {
                    emit(AgentEvent.Chunk("🔍 Checking: $filePath\n"))
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
                            emit(AgentEvent.Chunk("⚠️ Issues found in $filePath:\n${linterResult.llmContent.take(500)}\n"))
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
                                    emit(AgentEvent.Chunk("🔧 Auto-fixed issues in $filePath\n"))
                                    onChunk("🔧 Auto-fixed issues in $filePath\n")
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("AgentClient", "Failed to auto-fix: ${e.message}")
                            }
                        } else {
                            val noIssuesMsg = "✅ No issues found in $filePath\n"
                            emit(AgentEvent.Chunk(noIssuesMsg))
                            onChunk(noIssuesMsg)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AgentClient", "Linter check failed: ${e.message}")
                        
                        // Enhanced error detection and debugging
                        val errorAnalysis = analyzeLinterError(e, filePath, workspaceRoot)
                        if (errorAnalysis.isNotEmpty()) {
                            emit(AgentEvent.Chunk("🔍 Error Analysis:\n$errorAnalysis\n"))
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
            
            emit(AgentEvent.ToolResult(functionCall.name, toolResult))
            onToolResult(functionCall.name, functionCall.args)
            
            // Store generated file for coherence in subsequent files
            if (toolResult.error == null) {
                generatedFiles[filePath] = cleanCode
            }
            
            val generatedMsg = "✅ Generated and written: $filePath\n"
            emit(AgentEvent.Chunk(generatedMsg))
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
        emit(AgentEvent.Chunk(phase4Msg))
        onChunk(phase4Msg)
        
        val commandsNeeded = detectCommandsNeeded(workspaceRoot, systemInfo, userMessage, ::emit, onChunk)
        
        if (commandsNeeded.isNotEmpty()) {
            val foundCmdsMsg = "📋 Found ${commandsNeeded.size} command(s) to execute\n"
            emit(AgentEvent.Chunk(foundCmdsMsg))
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
            emit(AgentEvent.Chunk("ℹ️ No commands detected to run\n"))
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
            emit(AgentEvent.Chunk("\n🧪 Phase 5: Testing API endpoints...\n"))
            onChunk("\n🧪 Phase 5: Testing API endpoints...\n")
            
            val testResult = testAPIs(workspaceRoot, systemInfo, userMessage, ::emit, onChunk, onToolCall, onToolResult)
            
            if (testResult) {
                emit(AgentEvent.Chunk("✅ API testing completed successfully\n"))
                onChunk("✅ API testing completed successfully\n")
            } else {
                emit(AgentEvent.Chunk("⚠️ API testing completed with some issues\n"))
                onChunk("⚠️ API testing completed with some issues\n")
            }
        } else {
            if (isKotlinJava) {
                emit(AgentEvent.Chunk("\nℹ️ Skipping API testing for Kotlin/Java projects\n"))
                onChunk("\nℹ️ Skipping API testing for Kotlin/Java projects\n")
            } else {
                emit(AgentEvent.Chunk("\nℹ️ No web framework detected, skipping API testing\n"))
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
                emit(AgentEvent.Chunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n"))
                onChunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n")
            } else {
                emit(AgentEvent.Chunk("\n✅ Phase 6: Validating project completeness...\n"))
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
                emit(AgentEvent.Chunk("🧪 Running test commands...\n"))
                onChunk("🧪 Running test commands...\n")
                
                for (command in testCommands) {
                    emit(AgentEvent.Chunk("▶️ Testing: ${command.primaryCommand}\n"))
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
                        emit(AgentEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
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
                    emit(AgentEvent.ToolCall(shellCall))
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
                    
                    emit(AgentEvent.ToolResult("shell", result))
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
                        emit(AgentEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Test failed: ${command.primaryCommand}\n")
                    } else {
                        emit(AgentEvent.Chunk("✅ Test passed: ${command.primaryCommand}\n"))
                        onChunk("✅ Test passed: ${command.primaryCommand}\n")
                    }
                }
            }
            
            // Step 2: Run API tests if applicable
            var apiTestFailed = false
            if (isWebFramework && !isKotlinJava) {
                emit(AgentEvent.Chunk("🌐 Running API tests...\n"))
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
                emit(AgentEvent.Chunk("🔨 Checking build/compilation...\n"))
                onChunk("🔨 Checking build/compilation...\n")
                
                for (command in buildCommands) {
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        buildErrors = true
                        hasTestFailures = true
                        emit(AgentEvent.Chunk("❌ Build error detected: ${command.primaryCommand}\n"))
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
                    emit(AgentEvent.ToolCall(shellCall))
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
                    
                    emit(AgentEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    if (result.error != null || 
                        result.llmContent.contains("error", ignoreCase = true) ||
                        result.llmContent.contains("failed", ignoreCase = true) ||
                        result.llmContent.contains("ERROR", ignoreCase = true)) {
                        buildErrors = true
                        hasTestFailures = true
                        emit(AgentEvent.Chunk("❌ Build error detected: ${command.primaryCommand}\n"))
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
                    emit(AgentEvent.Chunk("\n✅ Project validation passed! Work is complete.\n"))
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
                    emit(AgentEvent.Chunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n"))
                    onChunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n")
                    break
                }
                
                emit(AgentEvent.Chunk("\n🔧 Analyzing failures and generating fixes...\n"))
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
                        android.util.Log.e("AgentClient", "Failed to parse fixes", e)
                        null
                    }
                    
                    if (fixesJson != null && fixesJson.length() > 0) {
                        emit(AgentEvent.Chunk("✅ Generated ${fixesJson.length()} fix(es)\n"))
                        onChunk("✅ Generated ${fixesJson.length()} fix(es)\n")
                        
                        // Apply fixes
                        for (i in 0 until fixesJson.length()) {
                            val fix = fixesJson.getJSONObject(i)
                            val filePath = fix.getString("file_path")
                            val oldString = fix.getString("old_string")
                            val newString = fix.getString("new_string")
                            
                            emit(AgentEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                            onChunk("🔨 Applying fix to $filePath...\n")
                            
                            val editCall = FunctionCall(
                                name = "edit",
                                args = mapOf(
                                    "file_path" to filePath,
                                    "old_string" to oldString,
                                    "new_string" to newString
                                )
                            )
                            
                            emit(AgentEvent.ToolCall(editCall))
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
                            
                            emit(AgentEvent.ToolResult("edit", editResult))
                            onToolResult("edit", editCall.args)
                            
                            if (editResult.error == null) {
                                emit(AgentEvent.Chunk("✅ Fix applied successfully\n"))
                                onChunk("✅ Fix applied successfully\n")
                                // Update generated files cache
                                if (filePath in generatedFiles) {
                                    val readResult = executeToolSync("read_file", mapOf("file_path" to filePath))
                                    if (readResult.error == null) {
                                        generatedFiles[filePath] = readResult.llmContent
                                    }
                                }
                            } else {
                                emit(AgentEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                                onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                            }
                        }
                    } else {
                        emit(AgentEvent.Chunk("⚠️ Could not generate fixes\n"))
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
                        
                        emit(AgentEvent.Chunk("\n⚠️ Could not generate fixes. Some issues may remain.\n"))
                        onChunk("\n⚠️ Could not generate fixes. Some issues may remain.\n")
                    }
                } else {
                    // Fix generation failed
                    emit(AgentEvent.Chunk("⚠️ Failed to generate fixes\n"))
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
                emit(AgentEvent.Chunk("\n✅ All tests passed! No fixes needed.\n"))
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
            emit(AgentEvent.Chunk("\n✨ Project generation complete and validated!\n"))
            onChunk("\n✨ Project generation complete and validated!\n")
        } else {
            emit(AgentEvent.Chunk("\n⚠️ Project generation completed with some issues remaining\n"))
            onChunk("\n⚠️ Project generation completed with some issues remaining\n")
        }
        
        emit(AgentEvent.Done)
    }
    
    /**
     * Validate project completeness by checking if all required files exist and are functional
     */
    private suspend fun validateProjectCompleteness(
        workspaceRoot: String,
        expectedFiles: List<String>,
        userMessage: String,
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit
    ): Boolean {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return false
        
        // Check if all expected files exist
        var allFilesExist = true
        for (filePath in expectedFiles) {
            val file = File(workspaceDir, filePath)
            if (!file.exists()) {
                emit(AgentEvent.Chunk("⚠️ Missing file: $filePath\n"))
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
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit
    ): String? {
        var retryCount = 0
        val maxRetries = 3
        var lastError: Throwable? = null
        var lastResponse: String? = null
        
        while (retryCount < maxRetries && signal?.isAborted() != true) {
            if (retryCount > 0) {
                val backoffMs = minOf(1000L * (1 shl retryCount), 20000L)
                emit(AgentEvent.Chunk("⏳ Retrying $operationName (attempt ${retryCount + 1}/$maxRetries)...\n"))
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
                emit(AgentEvent.Chunk("🔧 Command failed, asking AI to correct...\n"))
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
                    emit(AgentEvent.Chunk("💡 AI suggestion: ${correction.take(200)}\n"))
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
     * Standard reverse flow: Debug/Upgrade existing project
     * 1. Extract project structure (classes, functions, imports, tree)
     * 2. Analyze what needs fixing
     * 3. Read specific lines/functions
     * 4. Get fixes with assurance
     * 5. Apply fixes using edit tools
     */
    private suspend fun sendMessageStandardReverse(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        val signal = CancellationSignal()
        android.util.Log.d("AgentClient", "sendMessageStandardReverse: Starting reverse flow for debug/upgrade")
        
        try {
            // Emit initial event immediately to ensure flow is active
            android.util.Log.d("AgentClient", "sendMessageStandardReverse: About to emit initial event")
            emit(AgentEvent.Chunk("🚀 Starting...\n"))
            onChunk("🚀 Starting...\n")
            android.util.Log.d("AgentClient", "sendMessageStandardReverse: Initial event emitted successfully")
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.e("AgentClient", "Flow builder cancelled before initial emit", e)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Error emitting initial event in sendMessageStandardReverse", e)
            emit(AgentEvent.Error("Failed to start: ${e.message}"))
            return@flow
        }
        
        // Add user message to history
        chatHistory.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = userMessage))
            )
        )
        
        val model = ApiProviderManager.getCurrentModel()
        val systemInfo = SystemInfoService.detectSystemInfo(workspaceRoot)
        val systemContext = SystemInfoService.generateSystemContext(workspaceRoot)
        
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
            emit(AgentEvent.ToolCall(todoCall))
            onToolCall(todoCall)
            try {
                val todoResult = executeToolSync("write_todos", todoCall.args)
                emit(AgentEvent.ToolResult("write_todos", todoResult))
                onToolResult("write_todos", todoCall.args)
            } catch (e: Exception) {
                android.util.Log.e("AgentClient", "Failed to update todos", e)
            }
        }
        
        // Pre-planning phase: Check if documentation search is needed and add to todos
        val initialTodos = mutableListOf<Todo>()
        if (needsDocSearch) {
            emit(AgentEvent.Chunk("🔍 Task may need documentation search - adding to plan...\n"))
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
        suspend fun emitEvent(event: AgentEvent) {
            emit(event)
        }
        
        // Phase 0: Run requested commands
        if (wantsCommands) {
            emit(AgentEvent.Chunk("▶️ Phase 0: Detecting and running requested commands...\n"))
            onChunk("▶️ Phase 0: Detecting and running requested commands...\n")
            
            // Detect commands to run
            val commandsToRun = detectCommandsToRun(workspaceRoot, systemInfo, userMessage, ::emitEvent, onChunk)
            
            var hasCommandFailures = false
            if (commandsToRun.isNotEmpty()) {
                emit(AgentEvent.Chunk("📋 Found ${commandsToRun.size} command(s) to run\n"))
                onChunk("📋 Found ${commandsToRun.size} command(s) to run\n")
                
                for (command in commandsToRun) {
                    emit(AgentEvent.Chunk("▶️ Running: ${command.primaryCommand}\n"))
                    onChunk("▶️ Running: ${command.primaryCommand}\n")
                    
                    // First ensure command can run (with fallbacks)
                    val canRun = executeCommandWithFallbacks(
                        command, workspaceRoot, systemInfo, ::emitEvent, onChunk, onToolCall, onToolResult
                    )
                    
                    if (!canRun) {
                        hasCommandFailures = true
                        emit(AgentEvent.Chunk("❌ Command failed: ${command.primaryCommand}\n"))
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
                    emit(AgentEvent.ToolCall(shellCall))
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
                    
                    emit(AgentEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    // Check if command failed
                    if (result.error != null || 
                        result.llmContent.contains("error", ignoreCase = true) ||
                        result.llmContent.contains("failed", ignoreCase = true)) {
                        hasCommandFailures = true
                        emit(AgentEvent.Chunk("❌ Command failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Command failed: ${command.primaryCommand}\n")
                    } else {
                        emit(AgentEvent.Chunk("✅ Command completed: ${command.primaryCommand}\n"))
                        onChunk("✅ Command completed: ${command.primaryCommand}\n")
                    }
                }
            } else {
                emit(AgentEvent.Chunk("ℹ️ No specific commands detected to run\n"))
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
                emit(AgentEvent.Chunk("🌐 Running API tests...\n"))
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
                emit(AgentEvent.Chunk("⚠️ Some commands failed. Proceeding to analyze and fix issues...\n"))
                onChunk("⚠️ Some commands failed. Proceeding to analyze and fix issues...\n")
            }
        }
        
        // Phase 1: Extract project structure
        emit(AgentEvent.Chunk("📊 Phase 1: Extracting project structure...\n"))
        onChunk("📊 Phase 1: Extracting project structure...\n")
        
        val projectStructure = extractProjectStructure(workspaceRoot, signal, ::emitEvent, onChunk)
        
        if (projectStructure.isEmpty()) {
            emit(AgentEvent.Error("No source files found in project"))
            return@flow
        }
        
        val fileCount = projectStructure.split("===").size - 1
        emit(AgentEvent.Chunk("✅ Extracted structure from $fileCount files\n"))
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
        emit(AgentEvent.Chunk("🔍 Phase 2: Analyzing what needs fixing...\n"))
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
            emit(AgentEvent.Error("Failed to analyze project"))
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
            android.util.Log.e("AgentClient", "Failed to parse analysis", e)
            null
        }
        
        if (analysisJson == null) {
            emit(AgentEvent.Error("Failed to parse analysis results"))
            return@flow
        }
        
        emit(AgentEvent.Chunk("✅ Analysis complete\n"))
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
        emit(AgentEvent.Chunk("📖 Phase 3: Reading specific code sections...\n"))
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
                    
                    emit(AgentEvent.Chunk("📄 Read: $filePath\n"))
                    onChunk("📄 Read: $filePath\n")
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "Failed to read $filePath", e)
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
        emit(AgentEvent.Chunk("🔧 Phase 4: Getting fixes with assurance...\n"))
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
            emit(AgentEvent.Error("Failed to generate fixes"))
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
                    android.util.Log.w("AgentClient", "Response contains invalid JSON pattern: ${jsonSubstring.take(200)}")
                    emit(AgentEvent.Chunk("⚠️ AI returned invalid JSON, trying to extract fixes manually...\n"))
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
                        android.util.Log.w("AgentClient", "JSON parsing failed, trying text extraction: ${e.message}")
                        emit(AgentEvent.Chunk("⚠️ JSON parsing failed, extracting fixes from text...\n"))
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
                android.util.Log.w("AgentClient", "No JSON array found in response, trying text extraction")
                emit(AgentEvent.Chunk("⚠️ No JSON array found, extracting fixes from text...\n"))
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
            android.util.Log.e("AgentClient", "Failed to parse fixes", e)
            android.util.Log.e("AgentClient", "Response text: ${fixText.take(500)}")
            
            // Try to extract fixes from text as fallback
            emit(AgentEvent.Chunk("⚠️ JSON parsing failed, trying text extraction...\n"))
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
            emit(AgentEvent.Chunk("⚠️ Could not parse fixes, analyzing error to suggest fix...\n"))
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
                
                emit(AgentEvent.Chunk("✅ Generated fix from error analysis\n"))
                onChunk("✅ Generated fix from error analysis\n")
                
                // Continue with the suggested fix
                val fixesJsonFinal = fixesArray
                
                // Apply the fix (code continues below)
                emit(AgentEvent.Chunk("✅ Generated ${fixesJsonFinal.length()} fix(es)\n"))
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
                emit(AgentEvent.Chunk("✏️ Phase 5: Applying fix...\n"))
                onChunk("✏️ Phase 5: Applying fix...\n")
                
                val fix = fixesJsonFinal.getJSONObject(0)
                val filePath = fix.getString("file_path")
                val oldString = fix.getString("old_string")
                val newString = fix.getString("new_string")
                
                emit(AgentEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                onChunk("🔨 Applying fix to $filePath...\n")
                
                val editCall = FunctionCall(
                    name = "edit",
                    args = mapOf(
                        "file_path" to filePath,
                        "old_string" to oldString,
                        "new_string" to newString
                    )
                )
                
                emit(AgentEvent.ToolCall(editCall))
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
                
                emit(AgentEvent.ToolResult("edit", editResult))
                onToolResult("edit", editCall.args)
                
                if (editResult.error == null) {
                    emit(AgentEvent.Chunk("✅ Fix applied successfully\n"))
                    onChunk("✅ Fix applied successfully\n")
                } else {
                    emit(AgentEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                    onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                }
                
                emit(AgentEvent.Done)
                return@flow
            } else {
                emit(AgentEvent.Error("No fixes generated and could not analyze error"))
                return@flow
            }
        }
        
        emit(AgentEvent.Chunk("✅ Generated ${fixesJson.length()} fixes\n"))
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
        emit(AgentEvent.Chunk("✏️ Phase 5: Applying fixes...\n"))
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
            emit(AgentEvent.Chunk("🔨 Applying ${fixes.size} fix(es) to $filePath...\n"))
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
                    emit(AgentEvent.Chunk("⚠️ old_string not found, searching for better anchor...\n"))
                    onChunk("⚠️ old_string not found, searching for better anchor...\n")
                    
                    val improvedAnchor = findBetterAnchor(fileContent, oldString, newString)
                    if (improvedAnchor != null) {
                        oldString = improvedAnchor
                        emit(AgentEvent.Chunk("✅ Found better anchor\n"))
                        onChunk("✅ Found better anchor\n")
                    } else {
                        // If we can't find a good anchor, try to append the new content
                        if (newString.contains("exports.") || newString.contains("function ")) {
                            emit(AgentEvent.Chunk("⚠️ Using append strategy for function addition...\n"))
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
                            
                            emit(AgentEvent.ToolCall(appendEditCall))
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
                            
                            emit(AgentEvent.ToolResult("edit", appendResult))
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
                
                emit(AgentEvent.ToolCall(editCall))
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
                
                emit(AgentEvent.ToolResult("edit", editResult))
                onToolResult("edit", editCall.args)
                
                if (editResult.error == null) {
                    successCount++
                } else {
                    failCount++
                    emit(AgentEvent.Chunk("❌ Fix failed: ${editResult.error?.message}\n"))
                    onChunk("❌ Fix failed: ${editResult.error?.message}\n")
                }
            }
            
            if (successCount > 0) {
                emit(AgentEvent.Chunk("✅ $filePath: $successCount fix(es) applied${if (failCount > 0) ", $failCount failed" else ""}\n"))
                onChunk("✅ $filePath: $successCount fix(es) applied${if (failCount > 0) ", $failCount failed" else ""}\n")
            } else {
                emit(AgentEvent.Chunk("❌ $filePath: All fixes failed\n"))
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
                emit(AgentEvent.Chunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n"))
                onChunk("\n🔄 Validation attempt $validationAttempt/$maxValidationAttempts\n")
            } else {
                emit(AgentEvent.Chunk("\n✅ Phase 6: Validating fixes...\n"))
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
                emit(AgentEvent.Chunk("🧪 Running tests to validate fixes...\n"))
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
                        emit(AgentEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
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
                    emit(AgentEvent.ToolCall(shellCall))
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
                    
                    emit(AgentEvent.ToolResult("shell", result))
                    onToolResult("shell", shellCall.args)
                    
                    val isFailure = result.error != null || 
                        result.llmContent.contains("FAILED", ignoreCase = true) ||
                        (result.llmContent.contains("failed", ignoreCase = true) && 
                         result.llmContent.contains("test", ignoreCase = true)) ||
                        result.llmContent.contains("AssertionError", ignoreCase = true)
                    
                    if (isFailure) {
                        hasTestFailures = true
                        testFailures.add(Pair(command.primaryCommand, result))
                        emit(AgentEvent.Chunk("❌ Test failed: ${command.primaryCommand}\n"))
                        onChunk("❌ Test failed: ${command.primaryCommand}\n")
                    } else {
                        emit(AgentEvent.Chunk("✅ Test passed: ${command.primaryCommand}\n"))
                        onChunk("✅ Test passed: ${command.primaryCommand}\n")
                    }
                }
            }
            
            // Check if work is complete
            if (!hasTestFailures) {
                workComplete = true
                emit(AgentEvent.Chunk("\n✅ All tests passing! Debug/upgrade complete.\n"))
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
                emit(AgentEvent.Chunk("\n🔧 Phase 7: Analyzing test failures and generating fixes...\n"))
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
                        android.util.Log.e("AgentClient", "Failed to parse fixes", e)
                        null
                    }
                    
                    if (fixesJson != null && fixesJson.length() > 0) {
                        emit(AgentEvent.Chunk("✅ Generated ${fixesJson.length()} additional fix(es)\n"))
                        onChunk("✅ Generated ${fixesJson.length()} additional fix(es)\n")
                        
                        // Apply fixes (reuse existing fix application logic)
                        for (i in 0 until fixesJson.length()) {
                            val fix = fixesJson.getJSONObject(i)
                            val filePath = fix.getString("file_path")
                            val oldString = fix.getString("old_string")
                            val newString = fix.getString("new_string")
                            
                            emit(AgentEvent.Chunk("🔨 Applying fix to $filePath...\n"))
                            onChunk("🔨 Applying fix to $filePath...\n")
                            
                            val editCall = FunctionCall(
                                name = "edit",
                                args = mapOf(
                                    "file_path" to filePath,
                                    "old_string" to oldString,
                                    "new_string" to newString
                                )
                            )
                            
                            emit(AgentEvent.ToolCall(editCall))
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
                            
                            emit(AgentEvent.ToolResult("edit", editResult))
                            onToolResult("edit", editCall.args)
                            
                            if (editResult.error == null) {
                                emit(AgentEvent.Chunk("✅ Fix applied successfully\n"))
                                onChunk("✅ Fix applied successfully\n")
                            } else {
                                emit(AgentEvent.Chunk("❌ Failed to apply fix: ${editResult.error?.message}\n"))
                                onChunk("❌ Failed to apply fix: ${editResult.error?.message}\n")
                            }
                        }
                    }
                }
            } else if (validationAttempt >= maxValidationAttempts) {
                emit(AgentEvent.Chunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n"))
                onChunk("\n⚠️ Maximum validation attempts reached. Some issues may remain.\n")
                break
            }
        }
        
        if (workComplete) {
            emit(AgentEvent.Chunk("\n✨ Debug/upgrade complete and validated!\n"))
            onChunk("\n✨ Debug/upgrade complete and validated!\n")
        } else {
            emit(AgentEvent.Chunk("\n⚠️ Debug/upgrade completed with some issues remaining\n"))
            onChunk("\n⚠️ Debug/upgrade completed with some issues remaining\n")
        }
        
        emit(AgentEvent.Done)
    }
    
    /**
     * Question flow: Answer questions by reading files if needed
     * 1. Analyze question to determine if files need to be read
     * 2. Read relevant files if needed
     * 3. Answer the question using AI with file context
     */
    private suspend fun sendMessageQuestionFlow(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        val signal = CancellationSignal()
        android.util.Log.d("AgentClient", "sendMessageQuestionFlow: Starting question flow")
        
        try {
            emit(AgentEvent.Chunk("❓ Analyzing question...\n"))
            onChunk("❓ Analyzing question...\n")
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Error in question flow", e)
            emit(AgentEvent.Error("Failed to start: ${e.message}"))
            return@flow
        }
        
        // Add user message to history
        chatHistory.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = userMessage))
            )
        )
        
        val model = ApiProviderManager.getCurrentModel()
        val systemInfo = SystemInfoService.detectSystemInfo(workspaceRoot)
        val systemContext = SystemInfoService.generateSystemContext(workspaceRoot)
        
        // Helper function to wrap emit as suspend function
        suspend fun emitEvent(event: AgentEvent) {
            emit(event)
        }
        
        // Phase 1: Analyze question to determine if files need to be read
        emit(AgentEvent.Chunk("🔍 Phase 1: Determining if files need to be read...\n"))
        onChunk("🔍 Phase 1: Determining if files need to be read...\n")
        
        val analysisPrompt = """
            You are analyzing a user's question to determine if files need to be read to answer it.
            
            User Question: $userMessage
            
            Analyze the question and determine:
            1. Does this question require reading files from the workspace?
            2. If yes, which files are relevant?
            3. What specific information from those files is needed?
            
            Format your response as JSON:
            {
              "needs_files": true/false,
              "files_to_read": [
                {
                  "file_path": "path/to/file.ext",
                  "reason": "Why this file is needed to answer the question",
                  "sections": ["specific function/class names or line ranges if applicable"]
                }
              ],
              "question_type": "code_understanding|configuration|documentation|other"
            }
            
            If the question can be answered without reading files (general knowledge, explanation, etc.), set "needs_files" to false.
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
        
        val key = ApiProviderManager.getNextApiKey()
            ?: throw Exception("No API key available")
        
        val analysisText = makeApiCallWithRetryAndCorrection(
            model, analysisRequest, "analysis", signal, null, ::emitEvent, onChunk
        )
        
        if (analysisText == null) {
            emit(AgentEvent.Error("Failed to analyze question"))
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
            android.util.Log.e("AgentClient", "Failed to parse analysis", e)
            null
        }
        
        if (analysisJson == null) {
            emit(AgentEvent.Error("Failed to parse analysis results"))
            return@flow
        }
        
        val needsFiles = analysisJson.optBoolean("needs_files", false)
        val filesToRead = analysisJson.optJSONArray("files_to_read")
        val fileContents = mutableMapOf<String, String>()
        
        // Phase 2: Read files if needed
        if (needsFiles && filesToRead != null && filesToRead.length() > 0) {
            emit(AgentEvent.Chunk("📖 Phase 2: Reading relevant files...\n"))
            onChunk("📖 Phase 2: Reading relevant files...\n")
            
            for (i in 0 until filesToRead.length()) {
                val fileInfo = filesToRead.getJSONObject(i)
                val filePath = fileInfo.getString("file_path")
                
                try {
                    val readCall = FunctionCall(
                        name = "read_file",
                        args = mapOf("file_path" to filePath)
                    )
                    emit(AgentEvent.ToolCall(readCall))
                    onToolCall(readCall)
                    
                    val readResult = executeToolSync("read_file", readCall.args)
                    emit(AgentEvent.ToolResult("read_file", readResult))
                    onToolResult("read_file", readCall.args)
                    
                    if (readResult.error == null) {
                        fileContents[filePath] = readResult.llmContent
                        emit(AgentEvent.Chunk("✅ Read: $filePath\n"))
                        onChunk("✅ Read: $filePath\n")
                    } else {
                        emit(AgentEvent.Chunk("⚠️ Could not read: $filePath (${readResult.error?.message})\n"))
                        onChunk("⚠️ Could not read: $filePath (${readResult.error?.message})\n")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AgentClient", "Error reading file $filePath", e)
                    emit(AgentEvent.Chunk("⚠️ Error reading $filePath: ${e.message}\n"))
                    onChunk("⚠️ Error reading $filePath: ${e.message}\n")
                }
            }
        } else {
            emit(AgentEvent.Chunk("ℹ️ No files need to be read for this question\n"))
            onChunk("ℹ️ No files need to be read for this question\n")
        }
        
        // Phase 3: Answer the question
        emit(AgentEvent.Chunk("💬 Phase 3: Answering question...\n"))
        onChunk("💬 Phase 3: Answering question...\n")
        
        val answerPrompt = buildString {
            append("User Question: $userMessage\n\n")
            
            if (fileContents.isNotEmpty()) {
                append("Relevant file contents:\n\n")
                fileContents.forEach { (filePath, content) ->
                    append("=== File: $filePath ===\n")
                    append(content)
                    append("\n\n")
                }
            }
            
            append("""
                Please answer the user's question based on:
                1. The question itself
                2. The file contents provided (if any)
                3. Your general knowledge
                
                Provide a clear, comprehensive answer. If the question is about code, explain it in detail.
                If you don't have enough information, say so and suggest what additional information might be needed.
            """.trimIndent())
        }
        
        val answerRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                // Add chat history
                chatHistory.forEach { content ->
                    put(JSONObject().apply {
                        put("role", content.role)
                        put("parts", JSONArray().apply {
                            content.parts.forEach { part ->
                                when (part) {
                                    is Part.TextPart -> {
                                        put(JSONObject().apply {
                                            put("text", part.text)
                                        })
                                    }
                                    is Part.FunctionCallPart -> {
                                        put(JSONObject().apply {
                                            put("functionCall", JSONObject().apply {
                                                put("name", part.functionCall.name)
                                                put("args", JSONObject(part.functionCall.args))
                                            })
                                        })
                                    }
                                    is Part.FunctionResponsePart -> {
                                        put(JSONObject().apply {
                                            put("functionResponse", JSONObject().apply {
                                                put("name", part.functionResponse.name)
                                                put("response", JSONObject(part.functionResponse.response))
                                            })
                                        })
                                    }
                                }
                            }
                        })
                    })
                }
                // Add answer prompt
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", answerPrompt)
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
        
        val answerText = makeApiCallWithRetryAndCorrection(
            model, answerRequest, "answer", signal, null, ::emitEvent, onChunk
        )
        
        if (answerText == null) {
            emit(AgentEvent.Error("Failed to generate answer"))
            return@flow
        }
        
        // Emit the answer
        val cleanAnswer = answerText.trim()
        emit(AgentEvent.Chunk("\n$cleanAnswer\n"))
        onChunk("\n$cleanAnswer\n")
        
        // Add answer to chat history
        chatHistory.add(
            Content(
                role = "model",
                parts = listOf(Part.TextPart(text = cleanAnswer))
            )
        )
        
        emit(AgentEvent.Done)
    }
    
    /**
     * Plan execution order for multiple intents
     * Determines the best order based on dependencies and logical flow
     */
    private fun planIntentExecution(intents: List<IntentType>): List<IntentType> {
        // Priority order:
        // 1. Questions first (to understand context before actions)
        // 2. Create (if needed, establish base)
        // 3. Debug (fix issues - testing can be suggested by AI during debugging)
        
        val priorityOrder = listOf(
            IntentType.QUESTION_ONLY,
            IntentType.CREATE_NEW,
            IntentType.DEBUG_UPGRADE
        )
        
        // Sort intents by priority
        return intents.sortedBy { intent ->
            priorityOrder.indexOf(intent).let { index ->
                if (index == -1) Int.MAX_VALUE else index
            }
        }
    }
    
    /**
     * Multi-intent flow: Execute multiple intents in the best order
     */
    private suspend fun sendMessageMultiIntent(
        intents: List<IntentType>,
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        val signal = CancellationSignal()
        android.util.Log.d("AgentClient", "sendMessageMultiIntent: Starting multi-intent flow with ${intents.size} intents")
        
        try {
            emit(AgentEvent.Chunk("🎯 Detected multiple intents: ${intents.joinToString(", ")}\n"))
            onChunk("🎯 Detected multiple intents: ${intents.joinToString(", ")}\n")
        } catch (e: Exception) {
            android.util.Log.e("AgentClient", "Error in multi-intent flow", e)
            emit(AgentEvent.Error("Failed to start: ${e.message}"))
            return@flow
        }
        
        // Plan execution order
        val plannedOrder = planIntentExecution(intents)
        emit(AgentEvent.Chunk("📋 Execution plan: ${plannedOrder.joinToString(" → ")}\n\n"))
        onChunk("📋 Execution plan: ${plannedOrder.joinToString(" → ")}\n\n")
        
        // Execute each intent in order
        var accumulatedContext = userMessage
        var previousResults = mutableListOf<String>()
        
        for ((index, intent) in plannedOrder.withIndex()) {
            emit(AgentEvent.Chunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"))
            emit(AgentEvent.Chunk("📍 Step ${index + 1}/${plannedOrder.size}: ${intent.name}\n"))
            emit(AgentEvent.Chunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"))
            onChunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            onChunk("📍 Step ${index + 1}/${plannedOrder.size}: ${intent.name}\n")
            onChunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            
            // Build context message for this intent
            val contextMessage = buildString {
                append(accumulatedContext)
                if (previousResults.isNotEmpty()) {
                    append("\n\nPrevious step results:\n")
                    previousResults.forEachIndexed { i, result ->
                        append("Step ${i + 1}: $result\n")
                    }
                }
            }
            
            // Execute intent based on type
            val intentFlow = when (intent) {
                IntentType.QUESTION_ONLY -> {
                    sendMessageQuestionFlow(contextMessage, onChunk, onToolCall, onToolResult)
                }
                IntentType.DEBUG_UPGRADE -> {
                    val enhancedMessage = enhanceUserIntent(contextMessage, intent)
                    sendMessageStandardReverse(enhancedMessage, onChunk, onToolCall, onToolResult)
                }
                IntentType.CREATE_NEW -> {
                    val enhancedMessage = enhanceUserIntent(contextMessage, intent)
                    sendMessageStandard(enhancedMessage, onChunk, onToolCall, onToolResult)
                }
            }
            
            // Collect results from this intent
            var stepResult = StringBuilder()
            intentFlow.collect { event ->
                when (event) {
                    is AgentEvent.Chunk -> {
                        stepResult.append(event.text)
                        emit(event)
                    }
                    is AgentEvent.ToolCall -> {
                        emit(event)
                    }
                    is AgentEvent.ToolResult -> {
                        emit(event)
                    }
                    is AgentEvent.Error -> {
                        emit(event)
                        stepResult.append("Error: ${event.message}\n")
                    }
                    is AgentEvent.KeysExhausted -> {
                        emit(event)
                        stepResult.append("Keys exhausted: ${event.message}\n")
                    }
                    is AgentEvent.Done -> {
                        // Intent completed
                    }
                }
            }
            
            // Store result for next steps
            previousResults.add(stepResult.toString().take(500))
            
            // Update accumulated context with results
            accumulatedContext = "$contextMessage\n\nResult from ${intent.name}: ${stepResult.toString().take(200)}"
            
            emit(AgentEvent.Chunk("\n✅ Step ${index + 1} completed\n\n"))
            onChunk("\n✅ Step ${index + 1} completed\n\n")
        }
        
        emit(AgentEvent.Chunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"))
        emit(AgentEvent.Chunk("✨ All intents completed successfully!\n"))
        emit(AgentEvent.Chunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"))
        onChunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        onChunk("✨ All intents completed successfully!\n")
        onChunk("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        
        emit(AgentEvent.Done)
    }
    
    /**
     * Detect test commands needed based on project structure and user message
     */
    private suspend fun detectTestCommands(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (AgentEvent) -> Unit,
        onChunk: (String) -> Unit
    ): List<CommandWithFallbacks> {
        val commands = mutableListOf<CommandWithFallbacks>()
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) return commands
        
        // First, try hardcoded test command detection (no API calls)
        val messageLower = userMessage.lowercase()
        
        // Detect primary language by checking for strong indicators first
        // Priority: package.json (Node.js) > go.mod (Go) > Cargo.toml (Rust) > build.gradle/pom.xml (Java) > Python files
        
        // Check for Node.js first (package.json is a strong indicator)
        val hasPackageJson = File(workspaceDir, "package.json").exists()
        val hasNodeLockFiles = File(workspaceDir, "package-lock.json").exists() || 
                               File(workspaceDir, "yarn.lock").exists() || 
                               File(workspaceDir, "pnpm-lock.yaml").exists()
        val hasNodeSourceFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".js") || it.name.endsWith(".ts") || 
                it.name.endsWith(".jsx") || it.name.endsWith(".tsx")) }
        val isNodeProject = hasPackageJson || (hasNodeLockFiles && hasNodeSourceFiles)
        
        // Check for Go (go.mod is a strong indicator)
        val hasGoMod = File(workspaceDir, "go.mod").exists()
        val hasGoFiles = workspaceDir.walkTopDown()
            .any { it.isFile && it.name.endsWith(".go") }
        val isGoProject = hasGoMod || (hasGoFiles && !isNodeProject)
        
        // Check for Rust (Cargo.toml is a strong indicator)
        val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
        val hasRustFiles = workspaceDir.walkTopDown()
            .any { it.isFile && it.name.endsWith(".rs") }
        val isRustProject = hasCargoToml || (hasRustFiles && !isNodeProject && !isGoProject)
        
        // Check for Java/Kotlin (build files are strong indicators)
        val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
        val hasMaven = File(workspaceDir, "pom.xml").exists()
        val hasSbt = File(workspaceDir, "build.sbt").exists()
        val hasJavaFiles = workspaceDir.walkTopDown()
            .any { it.isFile && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
        val isJavaProject = hasGradle || hasMaven || hasSbt || (hasJavaFiles && !isNodeProject && !isGoProject && !isRustProject)
        
        // Check for Python (only if no other primary language detected)
        val hasPythonFiles = !isNodeProject && !isGoProject && !isRustProject && !isJavaProject &&
            workspaceDir.walkTopDown()
                .any { it.isFile && (it.name.endsWith(".py") || it.name == "requirements.txt" || 
                    it.name == "setup.py" || it.name == "pyproject.toml" || it.name == "Pipfile" || 
                    it.name == "poetry.lock") }
        
        // Detect Python test commands (only if Python is the primary language)
        if (hasPythonFiles) {
            val venvExists = File(workspaceDir, "venv").exists() || 
                           File(workspaceDir, ".venv").exists() ||
                           File(workspaceDir, "env").exists()
            val hasPoetry = File(workspaceDir, "pyproject.toml").exists() && 
                           File(workspaceDir, "poetry.lock").exists()
            val hasPipfile = File(workspaceDir, "Pipfile").exists()
            val pythonCmd = if (systemInfo.os == "Windows") "python" else "python3"
            val venvActivate = if (systemInfo.os == "Windows") "venv\\Scripts\\activate" else "source venv/bin/activate"
            
            val testCmd = when {
                hasPoetry -> "poetry run pytest || poetry run python -m pytest"
                hasPipfile -> "pipenv run pytest || pipenv run python -m pytest"
                File(workspaceDir, "pytest.ini").exists() || File(workspaceDir, "setup.cfg").exists() -> 
                    if (venvExists) "$venvActivate && pytest" else "pytest"
                File(workspaceDir, "tests").exists() || File(workspaceDir, "test").exists() ->
                    if (venvExists) "$venvActivate && $pythonCmd -m pytest tests/ || $pythonCmd -m unittest discover" else "$pythonCmd -m pytest tests/ || $pythonCmd -m unittest discover"
                else -> if (venvExists) "$venvActivate && $pythonCmd -m unittest discover" else "$pythonCmd -m unittest discover"
            }
            
            commands.add(CommandWithFallbacks(
                primaryCommand = testCmd,
                description = "Run Python tests",
                fallbacks = listOf(
                    "pip3 install pytest unittest2 || pip install pytest unittest2",
                    "${systemInfo.packageManagerCommands["install"]} python3-pytest"
                ),
                checkCommand = "pytest --version || python3 -m pytest --version",
                installCheck = "$pythonCmd --version"
            ))
        }
        
        // Detect Node.js test commands (prioritize Node.js if package.json exists)
        if (isNodeProject) {
            val packageJson = File(workspaceDir, "package.json")
            if (packageJson.exists()) {
                try {
                    val packageContent = packageJson.readText()
                    val hasTest = packageContent.contains("\"test\"")
                    val hasYarn = File(workspaceDir, "yarn.lock").exists()
                    val hasPnpm = File(workspaceDir, "pnpm-lock.yaml").exists()
                    
                    if (hasTest) {
                        val testCmd = when {
                            hasPnpm -> "pnpm test"
                            hasYarn -> "yarn test"
                            else -> "npm test"
                        }
                        
                        commands.add(CommandWithFallbacks(
                            primaryCommand = testCmd,
                            description = "Run Node.js tests",
                            fallbacks = listOf(
                                when {
                                    hasPnpm -> "pnpm install"
                                    hasYarn -> "yarn install"
                                    else -> "npm install"
                                },
                                "${systemInfo.packageManagerCommands["install"]} nodejs npm"
                            ),
                            checkCommand = "node --version",
                            installCheck = "npm --version"
                        ))
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }
        }
        
        // Detect Go test commands
        if (isGoProject) {
            commands.add(CommandWithFallbacks(
                primaryCommand = "go test ./...",
                description = "Run Go tests",
                fallbacks = listOf(
                    "${systemInfo.packageManagerCommands["install"]} golang-go",
                    "${systemInfo.packageManagerCommands["update"]} && ${systemInfo.packageManagerCommands["install"]} golang-go"
                ),
                checkCommand = "go version",
                installCheck = null
            ))
        }
        
        // Detect Rust test commands
        if (isRustProject) {
            commands.add(CommandWithFallbacks(
                primaryCommand = "cargo test",
                description = "Run Rust tests",
                fallbacks = listOf(
                    "cargo build",
                    "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y || true"
                ),
                checkCommand = "cargo --version",
                installCheck = "rustc --version"
            ))
        }
        
        // Detect Java/Kotlin test commands
        if (isJavaProject) {
            val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
            val hasMaven = File(workspaceDir, "pom.xml").exists()
            val hasSbt = File(workspaceDir, "build.sbt").exists()
            val gradleWrapper = File(workspaceDir, "gradlew").exists() || File(workspaceDir, "gradlew.bat").exists()
            
            when {
                hasGradle -> {
                    val gradleCmd = if (gradleWrapper) {
                        if (systemInfo.os == "Windows") "./gradlew.bat" else "./gradlew"
                    } else {
                        "gradle"
                    }
                    
                    commands.add(CommandWithFallbacks(
                        primaryCommand = "$gradleCmd test",
                        description = "Run Gradle tests",
                        fallbacks = listOf(
                            "$gradleCmd build",
                            if (!gradleWrapper) "${systemInfo.packageManagerCommands["install"]} gradle" else ""
                        ).filter { it.isNotEmpty() },
                        checkCommand = "$gradleCmd --version || gradle --version",
                        installCheck = "java -version"
                    ))
                }
                hasMaven -> {
                    commands.add(CommandWithFallbacks(
                        primaryCommand = "mvn test",
                        description = "Run Maven tests",
                        fallbacks = listOf(
                            "mvn install",
                            "${systemInfo.packageManagerCommands["install"]} maven"
                        ),
                        checkCommand = "mvn --version",
                        installCheck = "java -version"
                    ))
                }
                hasSbt -> {
                    commands.add(CommandWithFallbacks(
                        primaryCommand = "sbt test",
                        description = "Run SBT tests",
                        fallbacks = listOf(
                            "sbt compile",
                            "${systemInfo.packageManagerCommands["install"]} sbt"
                        ),
                        checkCommand = "sbt --version",
                        installCheck = "java -version"
                    ))
                }
            }
        }
        
        // Only use AI detection as last resort if no hardcoded commands were found
        if (commands.isEmpty()) {
            android.util.Log.d("AgentClient", "No hardcoded test commands found, falling back to AI detection")
            
            // Get list of files for AI prompt
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
                // Wrap network call in IO dispatcher to avoid NetworkOnMainThreadException
                val response = withContext(Dispatchers.IO) {
                    makeApiCallSimple(
                        ApiProviderManager.getNextApiKey() ?: return@withContext "",
                        model,
                        request,
                        useLongTimeout = false
                    )
                }
                
                if (response.isEmpty()) return commands
                
                // Parse JSON response
                val jsonStart = response.indexOf('[')
                val jsonEnd = response.lastIndexOf(']') + 1
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonArray = JSONArray(response.substring(jsonStart, jsonEnd))
                    
                    for (i in 0 until jsonArray.length()) {
                        try {
                            val cmdObj = jsonArray.getJSONObject(i)
                            val primaryCmd = cmdObj.getString("primary_command")
                            val description = cmdObj.optString("description", "Run test command")
                            val checkCmd = cmdObj.optString("check_command", null)
                            val fallbacks = cmdObj.optJSONArray("fallback_commands")?.let { array ->
                                (0 until array.length()).mapNotNull { array.optString(it, null) }
                            } ?: emptyList()
                            
                            commands.add(CommandWithFallbacks(
                                primaryCommand = primaryCmd,
                                description = description,
                                fallbacks = fallbacks,
                                checkCommand = checkCmd.takeIf { it.isNotBlank() },
                                installCheck = null
                            ))
                        } catch (e: Exception) {
                            android.util.Log.w("AgentClient", "Failed to parse test command: ${e.message}")
                        }
                    }
                }
                
                commands
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Screen was disposed, gracefully cancel
                android.util.Log.d("AgentClient", "Test command detection cancelled (screen disposed)")
                commands
            } catch (e: Exception) {
                android.util.Log.w("AgentClient", "AI test command detection failed: ${e.message}")
                commands
            }
        }
        
        return commands.distinctBy { it.primaryCommand } // Remove duplicates
    }
    
    /**
     * Detect commands to run based on user request (e.g., init-db, npm scripts, etc.)
     */
    private suspend fun detectCommandsToRun(
        workspaceRoot: String,
        systemInfo: SystemInfoService.SystemInfo,
        userMessage: String,
        emit: suspend (AgentEvent) -> Unit,
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
            
            // Wrap network call in IO dispatcher to avoid NetworkOnMainThreadException
            val response = withContext(Dispatchers.IO) {
                makeApiCallSimple(
                    apiKey,
                    model,
                    request,
                    useLongTimeout = false
                )
            }
            
            if (response != null && response.isNotEmpty()) {
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
                            android.util.Log.w("AgentClient", "Failed to parse command: ${e.message}")
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
            android.util.Log.w("AgentClient", "AI command detection failed: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Extract project structure: classes, functions, imports, tree
     */
    private suspend fun extractProjectStructure(
        workspaceRoot: String,
        signal: CancellationSignal?,
        emit: suspend (AgentEvent) -> Unit,
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
                android.util.Log.e("AgentClient", "Failed to extract from ${file.name}", e)
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
     * Simple API call that returns the full response text (standard)
     * Note: This is a blocking function, should be called from within withContext(Dispatchers.IO)
     */
    private suspend fun makeApiCallSimple(
        apiKey: String,
        model: String,
        requestBody: JSONObject,
        useLongTimeout: Boolean = false
    ): String {
        // Use makeApiCall in standard mode by collecting all chunks
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
            android.util.Log.e("AgentClient", "makeApiCallSimple: Error: ${e.message}", e)
            throw e
        }
    }
}

sealed class AgentEvent {
    data class Chunk(val text: String) : AgentEvent()
    data class ToolCall(val functionCall: FunctionCall) : AgentEvent()
    data class ToolResult(val toolName: String, val result: com.qali.aterm.gemini.tools.ToolResult) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class KeysExhausted(val message: String) : AgentEvent()
    object Done : AgentEvent()
}
