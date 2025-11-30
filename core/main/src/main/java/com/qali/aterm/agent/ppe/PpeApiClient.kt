package com.qali.aterm.agent.ppe

import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderManager.KeysExhaustedException
import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.client.api.ApiRequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API client for PPE scripts - non-streaming only
 * Uses existing ApiProviderManager for API cycling with reprompt fallback
 */
class PpeApiClient(
    private val toolRegistry: com.qali.aterm.agent.tools.ToolRegistry
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    /**
     * Make a non-streaming API call
     * Returns the assistant response text
     */
    suspend fun callApi(
        messages: List<Content>,
        model: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        tools: List<Tool>? = null
    ): Result<PpeApiResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val actualModel = model ?: ApiProviderManager.getCurrentModel()
                
                // Build request using existing ApiRequestBuilder
                val requestBuilder = ApiRequestBuilder(toolRegistry)
                val requestBody = requestBuilder.buildRequest(
                    chatHistory = messages,
                    model = actualModel
                )
                
                // Add generation config parameters (temperature, topP, topK) if provided
                if (temperature != null || topP != null || topK != null) {
                    val generationConfig = JSONObject()
                    temperature?.let { generationConfig.put("temperature", it) }
                    topP?.let { generationConfig.put("topP", it) }
                    topK?.let { generationConfig.put("topK", it) }
                    requestBody.put("generationConfig", generationConfig)
                }
                
                // Make API call with retry (non-streaming) - uses existing API cycling
                val result = ApiProviderManager.makeApiCallWithRetry { apiKey ->
                    try {
                        val response = makeNonStreamingApiCall(apiKey, actualModel, requestBody, temperature, topP, topK)
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
                
                if (result.isSuccess) {
                    Result.success(result.getOrNull()!!)
                } else {
                    val error = result.exceptionOrNull()
                    Result.failure(error ?: Exception("Unknown API error"))
                }
            } catch (e: Exception) {
                Log.e("PpeApiClient", "API call failed", e)
                Result.failure(e)
            }
        }
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
        topK: Int? = null
    ): PpeApiResponse {
        val providerType = ApiProviderManager.selectedProvider
        val requestBuilder = ApiRequestBuilder()
        
        // Determine endpoint and convert request
        val (url, convertedRequestBody, headers) = when (providerType) {
            com.qali.aterm.api.ApiProviderType.GOOGLE -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Triple(url, requestBody, emptyMap<String, String>())
            }
            com.qali.aterm.api.ApiProviderType.OPENAI -> {
                val url = "https://api.openai.com/v1/chat/completions"
                val headers = mapOf("Authorization" to "Bearer $apiKey")
                val convertedBody = requestBuilder.convertRequestToOpenAI(requestBody, model)
                // Add parameters to OpenAI request
                temperature?.let { convertedBody.put("temperature", it) }
                topP?.let { convertedBody.put("top_p", it) }
                Triple(url, convertedBody, headers)
            }
            com.qali.aterm.api.ApiProviderType.ANTHROPIC -> {
                val url = "https://api.anthropic.com/v1/messages"
                val headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
                val convertedBody = requestBuilder.convertRequestToAnthropic(requestBody, model)
                // Add parameters to Anthropic request
                temperature?.let { convertedBody.put("temperature", it) }
                topP?.let { convertedBody.put("top_p", it) }
                Triple(url, convertedBody, headers)
            }
            com.qali.aterm.api.ApiProviderType.CUSTOM -> {
                if (apiKey.contains("localhost") || apiKey.contains("127.0.0.1") || apiKey.contains("ollama") || apiKey.contains(":11434")) {
                    val baseUrl = when {
                        apiKey.startsWith("http") -> apiKey.split("/api").first()
                        apiKey.contains(":11434") -> "http://$apiKey"
                        else -> "http://localhost:11434"
                    }
                    val url = "$baseUrl/api/chat"
                    val convertedBody = requestBuilder.convertRequestToOllama(requestBody, model)
                    Triple(url, convertedBody, emptyMap<String, String>())
                } else {
                    Triple(apiKey, requestBody, emptyMap<String, String>())
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
        
        val response = client.newCall(httpRequest).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw IOException("API call failed: ${response.code} - $errorBody")
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
