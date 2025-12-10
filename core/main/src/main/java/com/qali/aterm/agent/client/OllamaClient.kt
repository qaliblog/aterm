package com.qali.aterm.agent.client

import com.qali.aterm.agent.client.api.ApiResponseParser
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OllamaClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "gptfree",
    private val apiKey: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Map<String, Any>>()
    
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        android.util.Log.d("OllamaClient", "Sending message to ChatGPT Python Script API")
        
        synchronized(chatHistory) {
            chatHistory.add(mapOf("role" to "user", "content" to userMessage))
        }
        
        try {
            // Build request body manually using explicit put calls to avoid ambiguity
            val requestJson = JSONObject()
            requestJson.put("model", model)
            
            val messagesArray = JSONArray()
            synchronized(chatHistory) {
                chatHistory.forEach { msg ->
                    val msgObj = JSONObject()
                    msgObj.put("role", msg["role"])
                    msgObj.put("content", msg["content"])
                    messagesArray.put(msgObj)
                }
            }
            requestJson.put("messages", messagesArray)
            requestJson.put("stream", false)
            
            // Tool listing temporarily disabled as getTools() API is unresolved
            /*
            val tools = toolRegistry.getTools() 
            if (tools.isNotEmpty()) {
                val toolsArray = JSONArray()
                tools.forEach { tool ->
                    val toolObj = JSONObject()
                    toolObj.put("type", "function")
                    
                    val funcObj = JSONObject()
                    funcObj.put("name", tool.name)
                    funcObj.put("description", tool.description ?: "")
                    
                    tool.parameters?.let { params ->
                        funcObj.put("parameters", JSONObject(params))
                    }
                    
                    toolObj.put("function", funcObj)
                    toolsArray.put(toolObj)
                }
                requestJson.put("tools", toolsArray)
            }
            */

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(requestBody)
            
            apiKey?.let { key ->
                requestBuilder.addHeader("Authorization", "Bearer $key")
                requestBuilder.addHeader("API-Key", key)
            }
            
            requestBuilder.addHeader("X-Requested-With", "XMLHttpRequest")
            
            val request = requestBuilder.build()
            
            // Use standard try-finally for response handling to avoid closure issues
            var response: Response? = null
            try {
                response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    emit(AgentEvent.Error("ChatGPT API error: ${response.code} - $errorBody"))
                    return@flow
                }
                
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val capturedChunks = mutableListOf<String>()
                    val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>()
                    
                    val finishReason = ApiResponseParser.parseChatGPTPythonResponse(
                        responseBody,
                        { chunk: String -> capturedChunks.add(chunk) },
                        onToolCall,
                        onToolResult,
                        toolCallsToExecute,
                        { json: JSONObject ->
                            val map = mutableMapOf<String, Any>()
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = json.get(key)
                                map[key] = value
                            }
                            map
                        }
                    )
                    
                    capturedChunks.forEach { chunk ->
                        emit(AgentEvent.Chunk(chunk))
                    }
                    
                    if (toolCallsToExecute.isNotEmpty()) {
                        for ((functionCall, toolResult, callId) in toolCallsToExecute) {
                            val tool = toolRegistry.getTool(functionCall.name)
                            if (tool != null) {
                                try {
                                    // Validate and convert parameters
                                    val params = tool.validateParams(functionCall.args)
                                    if (params == null) {
                                        emit(AgentEvent.Error("Invalid parameters for tool: ${functionCall.name}"))
                                        continue
                                    }
                                    
                                    // Create invocation and execute
                                    @Suppress("UNCHECKED_CAST")
                                    val invocation = (tool as com.qali.aterm.agent.tools.DeclarativeTool<Any, com.qali.aterm.agent.tools.ToolResult>).createInvocation(params as Any)
                                    val result = invocation.execute()
                                    
                                    emit(AgentEvent.ToolResult(functionCall.name, result))
                                    
                                    synchronized(chatHistory) {
                                        chatHistory.add(mapOf(
                                            "role" to "tool",
                                            "name" to functionCall.name,
                                            "content" to result.llmContent,
                                            "tool_call_id" to callId
                                        ))
                                    }
                                    onToolResult(callId, mapOf("content" to result.llmContent))
                                } catch (e: Exception) {
                                    emit(AgentEvent.Error("Tool execution failed: ${e.message}"))
                                }
                            } else {
                                emit(AgentEvent.Error("Tool not found: ${functionCall.name}"))
                            }
                        }
                    } else {
                        val fullResponse = capturedChunks.joinToString("")
                        if (fullResponse.isNotEmpty()) {
                            synchronized(chatHistory) {
                                chatHistory.add(mapOf("role" to "assistant", "content" to fullResponse))
                            }
                        }
                    }
                    
                    // Fixed: Use AgentEvent.Done (object, not data class)
                    emit(AgentEvent.Done)
                } else {
                    emit(AgentEvent.Error("Empty response body"))
                }
            } finally {
                response?.close()
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            emit(AgentEvent.Error("Error: $errorMsg"))
        }
    }.flowOn(Dispatchers.IO)
    
    fun resetChat() {
        synchronized(chatHistory) {
            chatHistory.clear()
        }
    }
    
    fun getHistory(): List<Map<String, Any>> = synchronized(chatHistory) {
        chatHistory.toList()
    }
}