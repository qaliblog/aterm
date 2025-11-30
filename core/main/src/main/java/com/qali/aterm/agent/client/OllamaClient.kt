package com.qali.aterm.agent.client

import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ollama Client for local LLM inference
 */
class OllamaClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "llama3.2"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Map<String, Any>>()
    
    /**
     * Send a message and get streaming response
     * 
     * This function returns a Flow that runs entirely on the IO dispatcher to avoid
     * NetworkOnMainThreadException. The flow builder itself is moved to IO using flowOn(),
     * ensuring all network operations happen off the main thread.
     */
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        // Add user message to history (thread-safe operation on mutable list)
        synchronized(chatHistory) {
            chatHistory.add(mapOf("role" to "user", "content" to userMessage))
        }
        
        try {
            // Build request body
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    // Access chat history in synchronized block
                    synchronized(chatHistory) {
                        chatHistory.forEach { msg ->
                            put(JSONObject().apply {
                                put("role", msg["role"])
                                put("content", msg["content"])
                            })
                        }
                    }
                })
                put("stream", true)
            }
            
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            // Execute network call - flow is already on IO dispatcher due to flowOn()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body?.string() ?: "Unknown error"
                    } catch (e: Exception) {
                        "Failed to read error body: ${e.message}"
                    }
                    emit(AgentEvent.Error("Ollama API error: ${response.code} - $errorBody"))
                    return@flow
                }
                
                response.body?.source()?.let { source ->
                    var buffer = ""
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        
                        try {
                            val json = JSONObject(line)
                            val message = json.optJSONObject("message")
                            val content = message?.optString("content", "") ?: ""
                            
                            if (content.isNotEmpty()) {
                                buffer += content
                                // Emit event immediately (flow collection handles threading)
                                emit(AgentEvent.Chunk(content))
                                // Dispatch callback to main thread for immediate UI updates
                                // Note: This is called from IO thread, so we dispatch to Main
                                withContext(Dispatchers.Main) {
                                    onChunk(content)
                                }
                            }
                            
                            if (json.optBoolean("done", false)) {
                                // Add assistant response to history (thread-safe)
                                synchronized(chatHistory) {
                                    chatHistory.add(mapOf("role" to "assistant", "content" to buffer))
                                }
                                break
                            }
                        } catch (e: Exception) {
                            // Skip malformed JSON
                            android.util.Log.d("OllamaClient", "Failed to parse JSON: ${e.message}")
                            emit(AgentEvent.Error("Failed to parse Ollama response: ${e.message ?: "Unknown error"}"))
                            break
                        }
                    }
                }
            }
        } catch (e: IOException) {
            val errorMsg = e.message ?: "Network error"
            android.util.Log.e("OllamaClient", "Network error", e)
            emit(AgentEvent.Error("Network error: $errorMsg"))
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            android.util.Log.e("OllamaClient", "Error", e)
            emit(AgentEvent.Error("Error: $errorMsg"))
        }
    }.flowOn(Dispatchers.IO) // Move entire flow execution to IO dispatcher
    
    fun resetChat() {
        synchronized(chatHistory) {
            chatHistory.clear()
        }
    }
    
    fun getHistory(): List<Map<String, Any>> = synchronized(chatHistory) {
        chatHistory.toList()
    }
}
