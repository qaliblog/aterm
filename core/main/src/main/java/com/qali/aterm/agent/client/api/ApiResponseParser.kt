package com.qali.aterm.agent.client.api

import com.qali.aterm.api.ApiProviderType
import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses API responses from different providers
 * Includes JSON cleaning utilities similar to gemini-cli
 */
object ApiResponseParser {
    
    /**
     * Clean JSON response by removing markdown code blocks (like gemini-cli's cleanJsonResponse)
     * Handles responses from script.py that may include markdown formatting
     */
    fun cleanJsonResponse(text: String, model: String = "gptfree"): String {
        val trimmed = text.trim()
        
        // Remove markdown code blocks (```json ... ```)
        val jsonPrefix = "```json"
        val jsonSuffix = "```"
        if (trimmed.startsWith(jsonPrefix, ignoreCase = true) && trimmed.endsWith(jsonSuffix)) {
            android.util.Log.d("ApiResponseParser", "Removing markdown code blocks from JSON response")
            return trimmed.substring(jsonPrefix.length, trimmed.length - jsonSuffix.length).trim()
        }
        
        // Remove generic code blocks (``` ... ```)
        val codePrefix = "```"
        if (trimmed.startsWith(codePrefix) && trimmed.endsWith(codePrefix) && trimmed.length > codePrefix.length * 2) {
            val withoutPrefix = trimmed.substring(codePrefix.length)
            if (withoutPrefix.endsWith(codePrefix)) {
                android.util.Log.d("ApiResponseParser", "Removing generic code blocks from response")
                return withoutPrefix.substring(0, withoutPrefix.length - codePrefix.length).trim()
            }
        }
        
        return trimmed
    }
    
    /**
     * Extract JSON from mixed response text (handles cases where JSON is embedded in text)
     */
    fun extractJsonFromText(text: String): String? {
        val trimmed = text.trim()
        
        // Try to find JSON object or array
        val jsonObjectPattern = Regex("""\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""", RegexOption.DOT_MATCHES_ALL)
        val jsonArrayPattern = Regex("""\[[^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*\]""", RegexOption.DOT_MATCHES_ALL)
        
        // Try object first
        val objectMatch = jsonObjectPattern.find(trimmed)
        if (objectMatch != null) {
            try {
                JSONObject(objectMatch.value)
                return objectMatch.value
            } catch (e: Exception) {
                // Not valid JSON, continue
            }
        }
        
        // Try array
        val arrayMatch = jsonArrayPattern.find(trimmed)
        if (arrayMatch != null) {
            try {
                JSONArray(arrayMatch.value)
                return arrayMatch.value
            } catch (e: Exception) {
                // Not valid JSON, continue
            }
        }
        
        return null
    }
    
    /**
     * Parse ChatGPT Python Script API response format
     */
    fun parseChatGPTPythonResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        jsonObjectToMap: (JSONObject) -> Map<String, Any>
    ): String? {
        android.util.Log.d("ApiResponseParser", "Parsing ChatGPT Python Script response")
        
        try {
            // First, try to parse as single JSON response (non-streaming)
            val json = JSONObject(bodyString)
            
            // Check for OpenAI/Ollama format
            if (json.has("choices") && json.has("model")) {
                return parseOpenAIResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute, jsonObjectToMap)
            }
            
            // Check for Ollama format
            if (json.has("message") && json.has("done")) {
                return parseOllamaResponse(bodyString, onChunk, onToolCall, onToolResult, toolCallsToExecute, jsonObjectToMap)
            }
            
            // Check for Gemini format
            if (json.has("candidates")) {
                return processGeminiResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, jsonObjectToMap)
            }
            
            // Default ChatGPT Python Script format
            val model = json.optString("model", "gptfree")
            val message = json.optJSONObject("message")
            
            if (message != null) {
                val role = message.optString("role", "assistant")
                val content = message.optString("content", "")
                
                if (content.isNotEmpty()) {
                    android.util.Log.d("ApiResponseParser", "Received content: ${content.take(100)}...")
                    // Check if content is JSON (for script2.py responses)
                    // If it's JSON, try to format it nicely, otherwise pass as-is
                    val trimmedContent = content.trim()
                    if (trimmedContent.startsWith("{") || trimmedContent.startsWith("[")) {
                        try {
                            // Try to parse and format JSON for better readability
                            val jsonContent = JSONObject(trimmedContent)
                            // Format JSON with indentation
                            val formatted = jsonContent.toString(2)
                            android.util.Log.d("ApiResponseParser", "Content is valid JSON, formatting...")
                            onChunk(formatted)
                        } catch (e: Exception) {
                            // Not valid JSON or is array, pass as-is
                            android.util.Log.d("ApiResponseParser", "Content is not valid JSON object, passing as-is")
                            onChunk(content)
                        }
                    } else {
                        onChunk(content)
                    }
                }
                
                // Check for tool calls (if the script supports them)
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val toolCall = toolCalls.getJSONObject(i)
                        val function = toolCall.optJSONObject("function")
                        if (function != null) {
                            val functionName = function.getString("name")
                            val functionArgs = function.optString("arguments", "{}")
                            val callId = toolCall.optString("id", "${functionName}-${System.currentTimeMillis()}")
                            
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
            
            // Check for done reason
            val doneReason = json.optString("done_reason", "stop")
            return when (doneReason.uppercase()) {
                "STOP" -> "STOP"
                "LENGTH", "MAX_TOKENS" -> "MAX_TOKENS"
                else -> doneReason.uppercase()
            }
            
        } catch (e: Exception) {
            android.util.Log.e("ApiResponseParser", "Failed to parse as JSON, trying SSE", e)
            
            // Try SSE parsing
            return parseGeminiSSEResponse(
                bodyString,
                onChunk,
                onToolCall,
                onToolResult,
                toolCallsToExecute,
                jsonObjectToMap
            ) ?: "STOP"
        }
    }
    
    /**
     * Parse Gemini SSE response format
     */
    fun parseGeminiSSEResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        jsonObjectToMap: (JSONObject) -> Map<String, Any>
    ): String? {
        // Try parsing as SSE (Server-Sent Events) format
        android.util.Log.d("ApiResponseParser", "Attempting SSE format parsing")
        val lines = bodyString.lines()
        android.util.Log.d("ApiResponseParser", "Total lines in response: ${lines.size}")
        
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
                    android.util.Log.d("ApiResponseParser", "Received [DONE] marker")
                    continue
                }
                
                try {
                    android.util.Log.d("ApiResponseParser", "Parsing SSE data line $dataLineCount")
                    val json = JSONObject(jsonStr)
                    android.util.Log.d("ApiResponseParser", "Processing SSE data line $dataLineCount")
                    val finishReason = processGeminiResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, jsonObjectToMap)
                    if (finishReason != null) {
                        return finishReason
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ApiResponseParser", "Failed to parse SSE JSON on line $dataLineCount", e)
                    android.util.Log.e("ApiResponseParser", "JSON string: ${jsonStr.take(500)}")
                }
            } else if (trimmedLine.startsWith(":")) {
                // SSE comment line, skip
                android.util.Log.d("ApiResponseParser", "Skipping SSE comment line")
            } else {
                // Try parsing the whole body as a single JSON object
                try {
                    android.util.Log.d("ApiResponseParser", "Attempting to parse as single JSON object")
                    val json = JSONObject(bodyString)
                    android.util.Log.d("ApiResponseParser", "Processing single JSON object")
                    return processGeminiResponse(json, onChunk, onToolCall, onToolResult, toolCallsToExecute, jsonObjectToMap)
                } catch (e: Exception) {
                    android.util.Log.w("ApiResponseParser", "Unexpected line format: ${trimmedLine.take(100)}")
                }
            }
        }
        android.util.Log.d("ApiResponseParser", "Finished SSE parsing. Total lines: $lineCount, Data lines: $dataLineCount")
        return null
    }
    
    /**
     * Parse OpenAI response format
     */
    fun parseOpenAIResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        jsonObjectToMap: (JSONObject) -> Map<String, Any>
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
    fun parseAnthropicResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        jsonObjectToMap: (JSONObject) -> Map<String, Any>
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
    fun parseOllamaResponse(
        bodyString: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        jsonObjectToMap: (JSONObject) -> Map<String, Any>
    ): String? {
        android.util.Log.d("ApiResponseParser", "Parsing Ollama format response (script.py)")
        val json = JSONObject(bodyString)
        val message = json.optJSONObject("message")
        val model = json.optString("model", "gptfree")
        
        if (message != null) {
            val content = message.optString("content", "")
            if (content.isNotEmpty()) {
                android.util.Log.d("ApiResponseParser", "Ollama content: ${content.take(100)}...")
                
                // Clean JSON response (remove markdown code blocks like gemini-cli)
                val cleanedContent = cleanJsonResponse(content, model)
                
                // Check if content is JSON (for script.py responses)
                val trimmedContent = cleanedContent.trim()
                if (trimmedContent.startsWith("{") || trimmedContent.startsWith("[")) {
                    try {
                        // Try to parse and format JSON for better readability
                        val jsonContent = if (trimmedContent.startsWith("{")) {
                            JSONObject(trimmedContent)
                        } else {
                            // It's an array, format it
                            val arrayContent = JSONArray(trimmedContent)
                            // Format array with indentation
                            val formatted = arrayContent.toString(2)
                            android.util.Log.d("ApiResponseParser", "Content is valid JSON array, formatting...")
                            onChunk(formatted)
                            return json.optBoolean("done", false).let { if (it) "STOP" else null }
                        }
                        // Format JSON object with indentation
                        val formatted = jsonContent.toString(2)
                        android.util.Log.d("ApiResponseParser", "Content is valid JSON object, formatting...")
                        onChunk(formatted)
                    } catch (e: Exception) {
                        // Not valid JSON, try to extract JSON from text
                        val extractedJson = extractJsonFromText(cleanedContent)
                        if (extractedJson != null) {
                            try {
                                val jsonContent = JSONObject(extractedJson)
                                val formatted = jsonContent.toString(2)
                                android.util.Log.d("ApiResponseParser", "Extracted and formatted JSON from text")
                                onChunk(formatted)
                            } catch (e2: Exception) {
                                android.util.Log.d("ApiResponseParser", "Could not parse extracted JSON, passing cleaned content as-is")
                                onChunk(cleanedContent)
                            }
                        } else {
                            android.util.Log.d("ApiResponseParser", "Content is not valid JSON, passing cleaned content as-is")
                            onChunk(cleanedContent)
                        }
                    }
                } else {
                    // Not JSON, pass cleaned content as-is
                    onChunk(cleanedContent)
                }
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
        android.util.Log.d("ApiResponseParser", "Ollama done: $done")
        return if (done) "STOP" else null
    }
    
    /**
     * Process Gemini response (common logic for both SSE and JSON array formats)
     */
    fun processGeminiResponse(
        json: JSONObject,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        toolCallsToExecute: MutableList<Triple<FunctionCall, ToolResult, String>>,
        jsonObjectToMap: (JSONObject) -> Map<String, Any>
    ): String? {
        var finishReason: String? = null
        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val candidate = candidates.getJSONObject(0)
            
            // Extract finish reason if present
            if (candidate.has("finishReason")) {
                finishReason = candidate.getString("finishReason")
            }
            
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
                            android.util.Log.d("ApiResponseParser", "Received thought: ${thoughtText.take(100)}...")
                            // Continue to next part - thoughts don't go to user
                            continue
                        }
                        
                        // Check for text (non-thought)
                        if (part.has("text") && !part.optBoolean("thought", false)) {
                            val text = part.getString("text")
                            android.util.Log.d("ApiResponseParser", "Found text chunk (length: ${text.length}): ${text.take(100)}...")
                            onChunk(text)
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
                                    android.util.Log.d("ApiResponseParser", "Citation: $citationText")
                                    // Citations are typically shown at the end, we can emit them if needed
                                }
                            }
                        }
                    }
                    
                    // Note: Function call execution is handled by the caller (AgentClient)
                    // This parser only extracts and reports function calls
                }
            }
        }
        return finishReason
    }
}