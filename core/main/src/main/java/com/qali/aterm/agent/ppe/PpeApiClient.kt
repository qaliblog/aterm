package com.qali.aterm.agent.ppe

import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderManager.KeysExhaustedException
import com.qali.aterm.api.ProviderConfig
import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.client.api.ApiRequestBuilder
import com.qali.aterm.agent.client.api.ProviderAdapter
import com.qali.aterm.agent.client.api.ApiResponseParser
import com.qali.aterm.agent.tools.ToolResult
import com.qali.aterm.agent.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Extended exception that includes retry delay information for rate-limited exhausted keys
 * Uses composition instead of inheritance since KeysExhaustedException is final
 */
class KeysExhaustedExceptionWithRetry(
    val originalException: KeysExhaustedException,
    val retryDelayMs: Long
) : Exception(originalException.message, originalException.cause)

/**
 * API client for PPE scripts - non-streaming only
 * Uses existing ApiProviderManager for API cycling with reprompt fallback
 * Supports Ollama when configured (bypasses ApiProviderManager)
 */
class PpeApiClient(
    private val toolRegistry: com.qali.aterm.agent.tools.ToolRegistry,
    private val ollamaUrl: String? = null,
    private val ollamaModel: String? = null
) {
    // Separate client for Ollama with longer timeouts (some models are very slow, especially large models)
    private val ollamaClient = OkHttpClient.Builder()
        .connectTimeout(PpeConfig.OLLAMA_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PpeConfig.OLLAMA_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PpeConfig.OLLAMA_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(PpeConfig.DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PpeConfig.DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PpeConfig.DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    // Separate client for Gemini with model-specific timeouts to prevent infinite "thinking"
    // Flash/lite models: 20s, Pro models: 60s
    private val geminiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // 60 seconds to support pro models (actual timeout enforced at coroutine level)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    /**
     * Make a non-streaming API call
     * Returns the assistant response text
     * If BUILTIN_LOCAL provider is selected, uses LocalLlamaModel; 
     * If Ollama is configured, uses Ollama directly; otherwise uses ApiProviderManager
     */
    suspend fun callApi(
        messages: List<Content>,
        model: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        tools: List<Tool>? = null,
        disableTools: Boolean = false // When true, ensures no tools are included in request
    ): Result<PpeApiResponse> {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val callId = "api-call-${System.currentTimeMillis()}"
            
            // Check if using built-in local model
            val currentProvider = com.qali.aterm.api.ApiProviderManager.selectedProvider
            if (currentProvider == com.qali.aterm.api.ApiProviderType.BUILTIN_LOCAL) {
                // Use local model for generation
                val prompt = messages.joinToString("\n") { msg ->
                    val role = when (msg.role) {
                        "user" -> "User"
                        "model", "assistant" -> "Assistant"
                        else -> msg.role
                    }
                    val text = msg.parts.joinToString(" ") { part ->
                        when (part) {
                            is Part.TextPart -> part.text
                            else -> ""
                        }
                    }
                    "$role: $text"
                }
                
                val response = try {
                    com.qali.aterm.llm.LocalLlamaModel.generate(prompt)
                } catch (e: Exception) {
                    Log.e("PpeApiClient", "Local model generation failed: ${e.message}", e)
                    return@withContext Result.failure(e)
                }
                
                return@withContext Result.success(
                    PpeApiResponse(
                        text = response,
                        finishReason = "STOP",
                        functionCalls = emptyList()
                    )
                )
            }
            
            try {
                // Calculate prompt length for dynamic parameter adjustment
                val promptLength = messages.sumOf { content ->
                    content.parts.sumOf { part ->
                        when (part) {
                            is Part.TextPart -> (part.text ?: "").length
                            else -> 0
                        }
                    }
                }
                
                // Detect if prompt is creative (contains creative keywords) vs deterministic
                val promptText = messages.joinToString(" ") { content ->
                    content.parts.joinToString(" ") { part ->
                        when (part) {
                            is Part.TextPart -> part.text ?: ""
                            else -> ""
                        }
                    }
                }.lowercase()
                
                val isCreative = promptText.contains(Regex("""\b(create|write|generate|imagine|design|story|poem|creative|artistic|novel|unique|original)\b"""))
                
                // Get current config and adjust based on prompt length and type
                val currentConfig = ApiProviderManager.getCurrentConfig()
                val adjustedConfig = ProviderConfig.adjustForPromptLength(currentConfig, promptLength, isCreative)
                
                // Use provided parameters or fall back to adjusted config
                val finalTemperature = (temperature?.toFloat() ?: adjustedConfig.temperature)
                    .let { ProviderConfig.clampTemperature(it) }
                val finalMaxTokens = adjustedConfig.maxTokens
                val finalTopP = (topP?.toFloat() ?: adjustedConfig.topP)
                    .coerceIn(0.0f, 1.0f)
                
                DebugLogger.d("PpeApiClient", "Starting API call", mapOf(
                    "call_id" to callId,
                    "model" to (model ?: "default"),
                    "messages_count" to messages.size,
                    "has_tools" to (tools != null && tools.isNotEmpty()),
                    "prompt_length" to promptLength,
                    "temperature" to finalTemperature,
                    "max_tokens" to finalMaxTokens,
                    "top_p" to finalTopP,
                    "streaming" to false
                ))
                
                // If Ollama is configured, use it directly (bypass ApiProviderManager)
                if (ollamaUrl != null && ollamaModel != null) {
                    val actualModel = model ?: ollamaModel
                    
                    DebugLogger.d("PpeApiClient", "Using Ollama provider", mapOf(
                        "call_id" to callId,
                        "ollama_url" to ollamaUrl,
                        "model" to actualModel
                    ))
                    
                    // Build request using existing ApiRequestBuilder
                    val requestBuilder = ApiRequestBuilder(toolRegistry)
                    val requestBody = requestBuilder.buildRequest(
                        chatHistory = messages,
                        model = actualModel,
                        includeTools = !disableTools // Don't include tools if disableTools is true
                    )
                    
                    // Always add generation config parameters (required for Gemini Lite models)
                    val generationConfig = JSONObject()
                    generationConfig.put("temperature", finalTemperature.toDouble())
                    generationConfig.put("max_output_tokens", finalMaxTokens)
                    generationConfig.put("topP", finalTopP.toDouble())
                    topK?.let { generationConfig.put("topK", it) }
                    requestBody.put("generationConfig", generationConfig)
                    
                    // Convert to Ollama format
                    val convertedBody = ProviderAdapter.convertRequestToOllama(requestBody, actualModel)
                    
                    // Make direct Ollama API call (non-streaming)
                    val response = makeDirectOllamaCall(ollamaUrl, actualModel, convertedBody, finalTemperature.toDouble(), finalTopP.toDouble(), topK)
                    
                    val duration = System.currentTimeMillis() - startTime
                    
                    // Record API call metrics
                    val estimatedTokens = ContextWindowManager.estimateTokens(messages) + 
                                         ContextWindowManager.estimateTokens(listOf(
                                             Content(role = "model", parts = listOf(Part.TextPart(text = response.text)))
                                         ))
                    val cost = Observability.estimateCost(actualModel, estimatedTokens)
                    Observability.recordApiCall("current-operation", estimatedTokens, cost)
                    
                    // Log API call
                    DebugLogger.logApiCall(
                        tag = "PpeApiClient",
                        provider = "Ollama",
                        model = actualModel,
                        requestSize = requestBody.toString().length,
                        responseSize = response.text.length,
                        duration = duration,
                        success = true,
                        sanitizedRequest = sanitizeRequest(requestBody.toString()),
                        sanitizedResponse = sanitizeResponse(response.text)
                    )
                    
                    return@withContext Result.success(response)
                }
                
                // Otherwise, use ApiProviderManager (existing flow)
                val actualModel = model ?: ApiProviderManager.getCurrentModel()
                val providerType = ApiProviderManager.selectedProvider
                val providerTypeName = providerType.name
                
                DebugLogger.d("PpeApiClient", "Using ApiProviderManager", mapOf(
                    "call_id" to callId,
                    "provider" to providerTypeName,
                    "model" to actualModel
                ))
                
                // Build request using existing ApiRequestBuilder
                val requestBuilder = ApiRequestBuilder(toolRegistry)
                val requestBody = requestBuilder.buildRequest(
                    chatHistory = messages,
                    model = actualModel,
                    includeTools = !disableTools // Don't include tools if disableTools is true
                )
                
                // Always add generation config parameters (required for Gemini Lite models)
                val generationConfig = JSONObject()
                generationConfig.put("temperature", finalTemperature.toDouble())
                generationConfig.put("max_output_tokens", finalMaxTokens)
                generationConfig.put("topP", finalTopP.toDouble())
                topK?.let { generationConfig.put("topK", it) }
                requestBody.put("generationConfig", generationConfig)
                
                // Make API call with retry (non-streaming) - uses existing API cycling
                // Add hard timeout for Gemini to prevent infinite "thinking"
                // Use longer timeout for pro models (gemini-2.5-pro, gemini-pro) which are slower
                val result = if (providerType == com.qali.aterm.api.ApiProviderType.GOOGLE) {
                    // Determine timeout based on model type
                    val timeoutMs = if (actualModel.contains("pro", ignoreCase = true) && 
                                       !actualModel.contains("flash", ignoreCase = true)) {
                        PpeConfig.GEMINI_PRO_API_TIMEOUT_MS // 60 seconds for pro models
                    } else {
                        PpeConfig.GEMINI_API_TIMEOUT_MS // 20 seconds for flash/lite models
                    }
                    
                    // Use coroutine timeout for Gemini API calls
                    try {
                        withTimeout(timeoutMs) {
                            ApiProviderManager.makeApiCallWithRetry { apiKey ->
                                try {
                                    val response = makeNonStreamingApiCall(apiKey, actualModel, requestBody, finalTemperature.toDouble(), finalTopP.toDouble(), topK, finalMaxTokens)
                                    Result.success(response)
                                } catch (e: KeysExhaustedException) {
                                    Result.failure(e)
                                } catch (e: Exception) {
                                    if (ApiProviderManager.isRateLimitError(e)) {
                                        Result.failure(e)
                                    } else {
                                        Result.failure(e)
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Result.failure(IOException("Gemini API call timed out after ${timeoutMs / 1000} seconds. The API may be slow or unresponsive. Please try again or check your network connection."))
                    }
                } else {
                    ApiProviderManager.makeApiCallWithRetry { apiKey ->
                        try {
                            val response = makeNonStreamingApiCall(apiKey, actualModel, requestBody, finalTemperature.toDouble(), finalTopP.toDouble(), topK, finalMaxTokens)
                            Result.success(response)
                        } catch (e: KeysExhaustedException) {
                            Result.failure(e)
                        } catch (e: Exception) {
                            if (ApiProviderManager.isRateLimitError(e)) {
                                Result.failure(e)
                            } else {
                                Result.failure(e)
                            }
                        }
                    }
                }
                
                val duration = System.currentTimeMillis() - startTime
                
                val finalResult = if (result.isSuccess) {
                    val response = result.getOrNull()!!
                    
                    // Record API call metrics
                    val estimatedTokens = ContextWindowManager.estimateTokens(messages) + 
                                         ContextWindowManager.estimateTokens(listOf(
                                             Content(role = "model", parts = listOf(Part.TextPart(text = response.text)))
                                         ))
                    val cost = Observability.estimateCost(actualModel, estimatedTokens)
                    Observability.recordApiCall("current-operation", estimatedTokens, cost)
                    
                    // Log successful API call
                    DebugLogger.logApiCall(
                        tag = "PpeApiClient",
                        provider = providerTypeName,
                        model = actualModel,
                        requestSize = requestBody.toString().length,
                        responseSize = response.text.length,
                        duration = duration,
                        success = true,
                        sanitizedRequest = sanitizeRequest(requestBody.toString()),
                        sanitizedResponse = sanitizeResponse(response.text)
                    )
                    
                    Result.success(response)
                } else {
                    val error = result.exceptionOrNull()
                    val errorMessage = error?.message ?: "Unknown API error"
                    
                    // Classify error and get recovery information
                    val errorContext = mapOf(
                        "provider" to providerTypeName,
                        "model" to actualModel,
                        "call_id" to callId,
                        "duration_ms" to duration
                    )
                    val classification = if (error != null) {
                        ErrorRecoveryManager.classifyError(error, errorContext)
                    } else {
                        ErrorRecoveryManager.ErrorClassification(
                            category = ErrorRecoveryManager.ErrorCategory.UNKNOWN,
                            severity = "medium",
                            isRetryable = false,
                            retryDelay = 0,
                            maxRetries = 0,
                            recoverySuggestion = "Unknown error occurred",
                            context = errorContext
                        )
                    }
                    
                    // Generate error report
                    val errorReport = if (error != null) {
                        ErrorRecoveryManager.generateErrorReport(error, classification, errorContext)
                    } else {
                        "Error: $errorMessage"
                    }
                    
                    // Log failed API call with enhanced error information
                    DebugLogger.logApiCall(
                        tag = "PpeApiClient",
                        provider = providerTypeName,
                        model = actualModel,
                        requestSize = requestBody.toString().length,
                        responseSize = 0,
                        duration = duration,
                        success = false,
                        error = errorMessage,
                        sanitizedRequest = sanitizeRequest(requestBody.toString())
                    )
                    
                    // Log error classification
                    DebugLogger.e("PpeApiClient", "API call failed with classification", mapOf(
                        "call_id" to callId,
                        "category" to classification.category.name,
                        "severity" to classification.severity,
                        "retryable" to classification.isRetryable,
                        "retry_delay_ms" to classification.retryDelay,
                        "recovery_suggestion" to (classification.recoverySuggestion ?: "none")
                    ), error)
                    
                    // If it's a KeysExhaustedException with rate limit errors, wrap it with retry info
                    val finalError = if (error is KeysExhaustedException && classification.category == ErrorRecoveryManager.ErrorCategory.RATE_LIMIT && classification.isRetryable) {
                        // Create a new exception that includes retry delay information
                        KeysExhaustedExceptionWithRetry(
                            error,
                            classification.retryDelay
                        )
                    } else {
                        error ?: Exception(errorMessage)
                    }
                    
                    Result.failure(finalError)
                }
                
                return@withContext finalResult
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                
                // Classify error and get recovery information
                val errorContext = mapOf(
                    "call_id" to callId,
                    "duration_ms" to duration,
                    "model" to (model ?: "default")
                )
                val classification = ErrorRecoveryManager.classifyError(e, errorContext)
                
                // Generate error report
                val errorReport = ErrorRecoveryManager.generateErrorReport(e, classification, errorContext)
                
                Log.e("PpeApiClient", "API call failed: ${classification.category}", e)
                DebugLogger.e("PpeApiClient", "API call exception with classification", mapOf(
                    "call_id" to callId,
                    "duration_ms" to duration,
                    "error" to (e.message ?: "Unknown error"),
                    "category" to classification.category.name,
                    "severity" to classification.severity,
                    "retryable" to classification.isRetryable,
                    "recovery_suggestion" to (classification.recoverySuggestion ?: "none")
                ), e)
                
                Result.failure(e)
            }
        }
    }
    
    /**
     * Sanitize request for logging (remove sensitive data)
     */
    private fun sanitizeRequest(request: String): String {
        // Remove API keys and sensitive tokens
        return request
            .replace(Regex("""["']?key["']?\s*[:=]\s*["']?[^"']+["']?"""), "\"key\": \"***\"")
            .replace(Regex("""["']?api[_-]?key["']?\s*[:=]\s*["']?[^"']+["']?"""), "\"api_key\": \"***\"")
            .replace(Regex("""["']?token["']?\s*[:=]\s*["']?[^"']+["']?"""), "\"token\": \"***\"")
            .take(1000) // Limit length
    }
    
    /**
     * Sanitize response for logging
     */
    private fun sanitizeResponse(response: String): String {
        return response.take(500) // Limit length
    }
    
    /**
     * Make direct Ollama API call (non-streaming)
     * Retries without tools if tools are not supported by the model
     */
    private suspend fun makeDirectOllamaCall(
        ollamaUrl: String,
        model: String,
        requestBody: JSONObject,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null
    ): PpeApiResponse {
        // Ensure URL ends with /api/chat
        val baseUrl = ollamaUrl.trimEnd('/')
        val url = "$baseUrl/api/chat"
        
        // Add parameters to Ollama request if provided
        temperature?.let { requestBody.put("temperature", it) }
        topP?.let { requestBody.put("top_p", it) }
        topK?.let { requestBody.put("top_k", it) }
        
        // Check if request has tools
        val hasTools = requestBody.has("tools") && !requestBody.isNull("tools")
        val originalTools = if (hasTools) requestBody.optJSONArray("tools") else null
        
        // Try with tools first (if available)
        if (hasTools && originalTools != null && originalTools.length() > 0) {
            try {
                return makeOllamaRequest(ollamaClient, url, requestBody, model)
            } catch (e: Exception) {
                val errorMsg = e.message?.lowercase() ?: ""
                // If tools are not supported, retry without tools
                if (errorMsg.contains("tool") || errorMsg.contains("function") || 
                    errorMsg.contains("not support") || errorMsg.contains("unsupported")) {
                    Log.w("PpeApiClient", "Ollama model $model does not support tools, retrying without tools")
                    // Remove tools from request
                    requestBody.remove("tools")
                    // Continue to make request without tools
                } else {
                    // Re-throw if it's a different error (timeout, network, etc.)
                    throw e
                }
            }
        }
        
        // Make request (without tools or if tools were removed)
        return makeOllamaRequest(ollamaClient, url, requestBody, model)
    }
    
    /**
     * Make actual Ollama HTTP request
     */
    private suspend fun makeOllamaRequest(
        httpClient: OkHttpClient,
        url: String,
        requestBody: JSONObject,
        model: String
    ): PpeApiResponse {
        // Make HTTP request (non-streaming)
        val requestBodyString = requestBody.toString()
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(httpRequest).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            val error = IOException("Ollama API call failed: ${response.code} - $errorBody")
            
            // Classify error for better error reporting
            val classification = ErrorRecoveryManager.classifyError(error, mapOf(
                "http_code" to response.code,
                "model" to model,
                "error_body" to errorBody.take(200)
            ))
            
            DebugLogger.e("PpeApiClient", "Ollama API call failed", mapOf(
                "http_code" to response.code,
                "category" to classification.category.name,
                "recovery_suggestion" to (classification.recoverySuggestion ?: "none")
            ), error)
            
            throw error
        }
        
        val bodyString = response.body?.string() ?: ""
        if (bodyString.isEmpty()) {
            return PpeApiResponse(text = "", finishReason = "STOP")
        }
        
        // Parse Ollama response
        val chunks = mutableListOf<String>()
        val functionCalls = mutableListOf<FunctionCall>()
        val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>()
        
        // Helper to convert JSONObject to Map
        fun jsonObjectToMap(obj: JSONObject): Map<String, Any> {
            val map = mutableMapOf<String, Any>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.get(key)
                map[key] = when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            list.add(value.get(i))
                        }
                        list
                    }
                    else -> value
                }
            }
            return map
        }
        
        val finishReason = ApiResponseParser.parseOllamaResponse(
            bodyString,
            onChunk = { chunks.add(it) },
            onToolCall = { functionCalls.add(it) },
            onToolResult = { _, _ -> },
            toolCallsToExecute = toolCallsToExecute,
            jsonObjectToMap = ::jsonObjectToMap
        )
        
        return PpeApiResponse(
            text = chunks.joinToString(""),
            finishReason = finishReason ?: "STOP",
            functionCalls = functionCalls
        )
    }
    
    /**
     * Make non-streaming API call - collects all chunks into single response
     */
    private suspend fun makeNonStreamingApiCall(
        apiKey: String,
        model: String,
        requestBody: JSONObject,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        maxTokens: Int? = null
    ): PpeApiResponse {
        val providerType = ApiProviderManager.selectedProvider
        
        // Determine endpoint and convert request
        // Use geminiClient for Google provider to enforce 20-second timeout
        val httpClient = if (providerType == com.qali.aterm.api.ApiProviderType.GOOGLE) {
            geminiClient
        } else {
            client
        }
        
        val (url, convertedRequestBody, headers) = when (providerType) {
            com.qali.aterm.api.ApiProviderType.GOOGLE -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                // Generation config is already added to requestBody above
                Triple(url, requestBody, emptyMap<String, String>())
            }
            com.qali.aterm.api.ApiProviderType.OPENAI -> {
                val url = "https://api.openai.com/v1/chat/completions"
                val headers = mapOf("Authorization" to "Bearer $apiKey")
                val convertedBody = ProviderAdapter.convertRequestToOpenAI(requestBody, model)
                // Always add parameters to OpenAI request (never skip)
                convertedBody.put("temperature", temperature ?: 0.7)
                convertedBody.put("top_p", topP ?: 1.0)
                convertedBody.put("max_tokens", maxTokens ?: 2048)
                Triple(url, convertedBody, headers)
            }
            com.qali.aterm.api.ApiProviderType.ANTHROPIC -> {
                val url = "https://api.anthropic.com/v1/messages"
                val headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
                val convertedBody = ProviderAdapter.convertRequestToAnthropic(requestBody, model)
                // Always add parameters to Anthropic request (never skip)
                convertedBody.put("temperature", temperature ?: 0.7)
                convertedBody.put("top_p", topP ?: 1.0)
                convertedBody.put("max_tokens", maxTokens ?: 2048)
                Triple(url, convertedBody, headers)
            }
            com.qali.aterm.api.ApiProviderType.CUSTOM -> {
                // Custom provider - use baseUrl from ApiProviderManager
                val baseUrl = com.qali.aterm.api.ApiProviderManager.getCurrentBaseUrl()
                if (baseUrl.isNotEmpty()) {
                    // Use configured base URL
                    val url = if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") || baseUrl.contains("ollama") || baseUrl.contains(":11434")) {
                        // Ollama format: append /api/chat if not already present
                        if (baseUrl.endsWith("/api/chat")) {
                            baseUrl
                        } else {
                            val cleanBaseUrl = baseUrl.trimEnd('/')
                            "$cleanBaseUrl/api/chat"
                        }
                    } else {
                        // Generic custom API - use baseUrl as-is (should be full endpoint URL)
                        baseUrl
                    }
                    val convertedBody = if (url.contains("ollama") || url.contains(":11434")) {
                        ProviderAdapter.convertRequestToOllama(requestBody, model)
                    } else {
                        requestBody // Assume Gemini-compatible format
                    }
                    Triple(url, convertedBody, emptyMap<String, String>())
                } else {
                    // Fallback: check if apiKey contains URL (for backward compatibility)
                    if (apiKey.contains("localhost") || apiKey.contains("127.0.0.1") || apiKey.contains("ollama") || apiKey.contains(":11434")) {
                        val fallbackBaseUrl = when {
                            apiKey.startsWith("http") -> apiKey.split("/api").first()
                            apiKey.contains(":11434") -> "http://$apiKey"
                            else -> "http://localhost:11434"
                        }
                        val url = "$fallbackBaseUrl/api/chat"
                        val convertedBody = ProviderAdapter.convertRequestToOllama(requestBody, model)
                        Triple(url, convertedBody, emptyMap<String, String>())
                    } else {
                        // Generic custom API - assume it's a full URL and Gemini-compatible format
                        Triple(apiKey, requestBody, emptyMap<String, String>())
                    }
                }
            }
            else -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Triple(url, requestBody, emptyMap<String, String>())
            }
        }
        
        // Make HTTP request (non-streaming)
        val requestBodyString = convertedRequestBody.toString()
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        val response = httpClient.newCall(httpRequest).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            val error = IOException("API call failed: ${response.code} - $errorBody")
            
            // Classify error for better error reporting
            val classification = ErrorRecoveryManager.classifyError(error, mapOf(
                "http_code" to response.code,
                "provider" to providerType.name,
                "model" to model,
                "error_body" to errorBody.take(200)
            ))
            
            DebugLogger.e("PpeApiClient", "API call failed", mapOf(
                "http_code" to response.code,
                "provider" to providerType.name,
                "category" to classification.category.name,
                "recovery_suggestion" to (classification.recoverySuggestion ?: "none")
            ), error)
            
            throw error
        }
        
        val bodyString = response.body?.string() ?: ""
        if (bodyString.isEmpty()) {
            return PpeApiResponse(text = "", finishReason = "STOP")
        }
        
        // Parse response based on provider - use existing parser with result collection
        val chunks = mutableListOf<String>()
        val functionCalls = mutableListOf<FunctionCall>()
        val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>()
        
        // Helper to convert JSONObject to Map
        fun jsonObjectToMap(obj: JSONObject): Map<String, Any> {
            val map = mutableMapOf<String, Any>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.get(key)
                map[key] = when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            list.add(value.get(i))
                        }
                        list
                    }
                    else -> value
                }
            }
            return map
        }
        
        var finishReason: String? = null
        
        when (providerType) {
            com.qali.aterm.api.ApiProviderType.GOOGLE -> {
                finishReason = ApiResponseParser.parseGeminiSSEResponse(
                    bodyString,
                    onChunk = { chunks.add(it) },
                    onToolCall = { functionCalls.add(it) },
                    onToolResult = { _, _ -> },
                    toolCallsToExecute = toolCallsToExecute,
                    jsonObjectToMap = ::jsonObjectToMap
                )
            }
            com.qali.aterm.api.ApiProviderType.OPENAI -> {
                finishReason = ApiResponseParser.parseOpenAIResponse(
                    bodyString,
                    onChunk = { chunks.add(it) },
                    onToolCall = { functionCalls.add(it) },
                    onToolResult = { _, _ -> },
                    toolCallsToExecute = toolCallsToExecute,
                    jsonObjectToMap = ::jsonObjectToMap
                )
            }
            com.qali.aterm.api.ApiProviderType.ANTHROPIC -> {
                finishReason = ApiResponseParser.parseAnthropicResponse(
                    bodyString,
                    onChunk = { chunks.add(it) },
                    onToolCall = { functionCalls.add(it) },
                    onToolResult = { _, _ -> },
                    toolCallsToExecute = toolCallsToExecute,
                    jsonObjectToMap = ::jsonObjectToMap
                )
            }
            com.qali.aterm.api.ApiProviderType.CUSTOM -> {
                if (apiKey.contains("ollama") || apiKey.contains(":11434")) {
                    finishReason = ApiResponseParser.parseOllamaResponse(
                        bodyString,
                        onChunk = { chunks.add(it) },
                        onToolCall = { functionCalls.add(it) },
                        onToolResult = { _, _ -> },
                        toolCallsToExecute = toolCallsToExecute,
                        jsonObjectToMap = ::jsonObjectToMap
                    )
                } else {
                    finishReason = ApiResponseParser.parseGeminiSSEResponse(
                        bodyString,
                        onChunk = { chunks.add(it) },
                        onToolCall = { functionCalls.add(it) },
                        onToolResult = { _, _ -> },
                        toolCallsToExecute = toolCallsToExecute,
                        jsonObjectToMap = ::jsonObjectToMap
                    )
                }
            }
            else -> {
                finishReason = ApiResponseParser.parseGeminiSSEResponse(
                    bodyString,
                    onChunk = { chunks.add(it) },
                    onToolCall = { functionCalls.add(it) },
                    onToolResult = { _, _ -> },
                    toolCallsToExecute = toolCallsToExecute,
                    jsonObjectToMap = ::jsonObjectToMap
                )
            }
        }
        
        return PpeApiResponse(
            text = chunks.joinToString(""),
            finishReason = finishReason ?: "STOP",
            functionCalls = functionCalls
        )
    }
}

/**
 * Response from PPE API call
 */
data class PpeApiResponse(
    val text: String,
    val finishReason: String? = null,
    val functionCalls: List<FunctionCall> = emptyList(),
    val usage: Map<String, Int>? = null
)
