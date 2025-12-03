package com.qali.aterm.agent.client.api

import com.qali.aterm.api.ApiProviderType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapter for converting requests between different API provider formats
 */
object ProviderAdapter {
    
    /**
     * Convert Gemini request format to OpenAI format
     */
    fun convertRequestToOpenAI(geminiRequest: JSONObject, model: String): JSONObject {
        val openAIRequest = JSONObject()
        openAIRequest.put("model", model)
        openAIRequest.put("stream", false) // Standard API mode
        
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
    fun convertRequestToAnthropic(geminiRequest: JSONObject, model: String): JSONObject {
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
    fun convertRequestToOllama(geminiRequest: JSONObject, model: String): JSONObject {
        val ollamaRequest = JSONObject()
        ollamaRequest.put("model", model)
        ollamaRequest.put("stream", false) // Standard API mode
        
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
    
    /**
     * Get API endpoint URL and headers for a provider
     */
    fun getApiEndpoint(
        providerType: ApiProviderType,
        model: String,
        apiKey: String
    ): Pair<String, Map<String, String>> {
        return when (providerType) {
            ApiProviderType.GOOGLE -> {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Pair(url, emptyMap())
            }
            ApiProviderType.OPENAI -> {
                val url = "https://api.openai.com/v1/chat/completions"
                val headers = mapOf("Authorization" to "Bearer $apiKey")
                Pair(url, headers)
            }
            ApiProviderType.ANTHROPIC -> {
                val url = "https://api.anthropic.com/v1/messages"
                val headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
                Pair(url, headers)
            }
            ApiProviderType.CUSTOM -> {
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
                    Pair(url, emptyMap())
                } else {
                    // Fallback: check if apiKey contains URL (for backward compatibility)
                    if (apiKey.contains("localhost") || apiKey.contains("127.0.0.1") || apiKey.contains("ollama") || apiKey.contains(":11434")) {
                        val fallbackBaseUrl = when {
                            apiKey.startsWith("http") -> apiKey.split("/api").first()
                            apiKey.contains(":11434") -> "http://$apiKey"
                            else -> "http://localhost:11434"
                        }
                        val url = "$fallbackBaseUrl/api/chat"
                        Pair(url, emptyMap())
                    } else {
                        // Generic custom API - assume it's a full URL and Gemini-compatible format
                        Pair(apiKey, emptyMap())
                    }
                }
            }
            else -> {
                // For other providers, use Gemini-compatible endpoint for now
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Pair(url, emptyMap())
            }
        }
    }
}
