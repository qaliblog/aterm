package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.tools.ToolRegistry
import com.qali.aterm.agent.ppe.models.PpeScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File

/**
 * CLI-based agent client that replaces the old AgentClient
 * Uses PPE (Programmable Prompt Engine) scripts for agentic workflow
 */
class CliBasedAgentClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String = "/data/data/com.termux/files/home"
) {
    private val executionEngine = PpeExecutionEngine(toolRegistry, workspaceRoot)
    
    /**
     * Default script path - can be overridden
     */
    var defaultScriptPath: String? = null
    
    /**
     * Send a message and get response using PPE script
     * Maintains same interface as old AgentClient for UI compatibility
     */
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        scriptPath: String? = null
    ): Flow<AgentEvent> = channelFlow {
        val startTime = System.currentTimeMillis()
        var eventCount = 0
        var lastEventTime = startTime
        var toolCallCount = 0
        var toolResultCount = 0
        var chunkCount = 0
        
        // Use a channel to collect events from callbacks (which are not suspend functions)
        // Declare outside try block so it's accessible in catch blocks
        val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        
        try {
            Log.d("CliBasedAgentClient", "Starting sendMessage for: ${userMessage.take(50)}...")
            
            // Load script (use provided path or default)
            val script = when {
                scriptPath != null -> {
                    val scriptFile = File(scriptPath)
                    if (scriptFile.exists()) {
                        Log.d("CliBasedAgentClient", "Loading script from: $scriptPath")
                        PpeScriptLoader.loadScript(scriptFile)
                    } else {
                        Log.d("CliBasedAgentClient", "Script file not found, using inline default")
                        createInlineDefaultScript(userMessage)
                    }
                }
                defaultScriptPath != null -> {
                    val scriptFile = File(defaultScriptPath!!)
                    if (scriptFile.exists()) {
                        Log.d("CliBasedAgentClient", "Loading default script from: $defaultScriptPath")
                        PpeScriptLoader.loadScript(scriptFile)
                    } else {
                        Log.d("CliBasedAgentClient", "Default script file not found, using inline default")
                        createInlineDefaultScript(userMessage)
                    }
                }
                else -> {
                    // Try to find default script in workspace
                    val defaultScript = File(workspaceRoot, "agent-scripts/default.ai.yaml")
                    if (defaultScript.exists()) {
                        Log.d("CliBasedAgentClient", "Loading workspace script from: ${defaultScript.absolutePath}")
                        PpeScriptLoader.loadScript(defaultScript)
                    } else {
                        Log.d("CliBasedAgentClient", "No script file found, using inline default")
                        // Use inline default script
                        createInlineDefaultScript(userMessage)
                    }
                }
            }
            
            // Prepare input parameters
            val inputParams = mapOf(
                "userMessage" to userMessage,
                "content" to userMessage
            )
            
            Log.d("CliBasedAgentClient", "Starting script execution")
            
            // Launch a coroutine to emit events from the channel
            // Using channelFlow allows emissions from multiple coroutines
            launch {
                try {
                    eventChannel.consumeEach { event ->
                        send(event) // Use send() in channelFlow instead of emit()
                    }
                } catch (e: Exception) {
                    Log.e("CliBasedAgentClient", "Error emitting events from channel", e)
                }
            }
            
            // Execute script and send events to channel in real-time
            executionEngine.executeScript(
                script = script,
                inputParams = inputParams,
                onChunk = { chunk ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    chunkCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Chunk received (count: $chunkCount, size: ${chunk.length}, time since start: ${now - startTime}ms)")
                    onChunk(chunk)
                    // Send to channel (non-blocking, can be called from non-suspend context)
                    try {
                        eventChannel.trySend(AgentEvent.Chunk(chunk))
                    } catch (e: Exception) {
                        Log.e("CliBasedAgentClient", "Error sending chunk to channel", e)
                    }
                },
                onToolCall = { functionCall ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    toolCallCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Tool call received (count: $toolCallCount, tool: ${functionCall.name}, time since start: ${now - startTime}ms)")
                    onToolCall(functionCall)
                    // Send to channel
                    try {
                        eventChannel.trySend(AgentEvent.ToolCall(functionCall))
                    } catch (e: Exception) {
                        Log.e("CliBasedAgentClient", "Error sending tool call to channel", e)
                    }
                },
                onToolResult = { toolName, args ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    toolResultCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Tool result callback (count: $toolResultCount, tool: $toolName, time since start: ${now - startTime}ms)")
                    onToolResult(toolName, args)
                    
                    // Get tool result from execution engine (FIFO queue)
                    val toolResult = executionEngine.getNextToolResult(toolName)
                    if (toolResult != null) {
                        Log.d("CliBasedAgentClient", "Sending tool result to channel for: $toolName")
                        // Send to channel - AgentScreen will extract file diffs from this
                        try {
                            eventChannel.trySend(AgentEvent.ToolResult(toolName, toolResult))
                        } catch (e: Exception) {
                            Log.e("CliBasedAgentClient", "Error sending tool result to channel", e)
                        }
                    } else {
                        Log.w("CliBasedAgentClient", "Tool result not found in queue for: $toolName")
                    }
                }
            ).collect { result ->
                val now = System.currentTimeMillis()
                val totalTime = now - startTime
                val timeSinceLastEvent = now - lastEventTime
                
                Log.d("CliBasedAgentClient", "Execution result received (success: ${result.success}, total time: ${totalTime}ms, time since last event: ${timeSinceLastEvent}ms)")
                Log.d("CliBasedAgentClient", "Event summary - Total: $eventCount, Chunks: $chunkCount, ToolCalls: $toolCallCount, ToolResults: $toolResultCount")
                
                if (result.success) {
                    // Emit final result if any
                    if (result.finalResult.isNotEmpty()) {
                        Log.d("CliBasedAgentClient", "Sending final result to channel (length: ${result.finalResult.length})")
                        eventChannel.trySend(AgentEvent.Chunk(result.finalResult))
                        onChunk(result.finalResult)
                    } else {
                        Log.d("CliBasedAgentClient", "No final result to send")
                    }
                    Log.d("CliBasedAgentClient", "Sending Done event to channel")
                    eventChannel.trySend(AgentEvent.Done)
                } else {
                    val errorMsg = result.error ?: "Script execution failed"
                    Log.e("CliBasedAgentClient", "Script execution failed: $errorMsg")
                    eventChannel.trySend(AgentEvent.Error(errorMsg))
                }
                
                Log.d("CliBasedAgentClient", "Script execution completed successfully (total time: ${totalTime}ms)")
                
                // Wait for any pending events (like continuation responses) to be sent to the channel
                // Continuation responses can arrive asynchronously after execution completes
                // Give enough time for these to be captured (continuation API calls can take time)
                kotlinx.coroutines.delay(500)
                
                Log.d("CliBasedAgentClient", "Closing channel after delay")
                // Close the channel to signal completion
                eventChannel.close()
                
                // Give the channel consumer time to process remaining events and the close signal
                kotlinx.coroutines.delay(200)
                Log.d("CliBasedAgentClient", "Channel consumer should have finished processing")
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            val totalTime = System.currentTimeMillis() - startTime
            val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime
            Log.w("CliBasedAgentClient", "Script execution cancelled (total time: ${totalTime}ms, time since last event: ${timeSinceLastEvent}ms, events: $eventCount)")
            Log.w("CliBasedAgentClient", "Cancellation details - Chunks: $chunkCount, ToolCalls: $toolCallCount, ToolResults: $toolResultCount")
            // Close channel on cancellation
            eventChannel.close()
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime
            Log.e("CliBasedAgentClient", "Error executing script (total time: ${totalTime}ms, time since last event: ${timeSinceLastEvent}ms)", e)
            Log.e("CliBasedAgentClient", "Error details - Events: $eventCount, Chunks: $chunkCount, ToolCalls: $toolCallCount, ToolResults: $toolResultCount")
            Log.e("CliBasedAgentClient", "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
            e.printStackTrace()
            // Send error to channel and close
            eventChannel.trySend(AgentEvent.Error(e.message ?: "Unknown error: ${e.javaClass.simpleName}"))
            eventChannel.close()
        }
    }
    
    /**
     * Create a default script file if it doesn't exist
     */
    private fun createDefaultScript(userMessage: String): File {
        val scriptsDir = File(workspaceRoot, "agent-scripts")
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs()
        }
        
        val defaultScript = File(scriptsDir, "default.ai.yaml")
        if (!defaultScript.exists()) {
            val scriptContent = """
---
parameters:
  userMessage: ""
---
system: |-
  You are an interactive CLI agent specializing in software engineering tasks.
  Your primary goal is to help users safely and efficiently, adhering strictly to the following instructions and utilizing your available tools.
  
  # Core Mandates
  
  - **Conventions:** Rigorously adhere to existing project conventions when reading or modifying code. Analyze surrounding code, tests, and configuration first.
  - **Libraries/Frameworks:** NEVER assume a library/framework is available or appropriate. Verify its established usage within the project before employing it.
  - **Style & Structure:** Mimic the style, structure, framework choices, typing, and architectural patterns of existing code in the project.
  - **Proactiveness:** Fulfill the user's request thoroughly. When adding features or fixing bugs, this includes adding tests to ensure quality.
  - **Explaining Changes:** After completing a code modification or file operation, do not provide summaries unless asked.
  
  # Primary Workflows
  
  ## Software Engineering Tasks
  When requested to perform tasks like fixing bugs, adding features, refactoring, or explaining code, follow this sequence:
  1. **Understand:** Think about the user's request and the relevant codebase context. Use search tools extensively to understand file structures, existing code patterns, and conventions.
  2. **Plan:** Build a coherent and grounded plan for how you intend to resolve the user's task.
  3. **Execute:** Implement your plan using the available tools, making sure to follow existing patterns and conventions.
  4. **Verify:** Check your work and ensure it meets the requirements.
---
user: "{{userMessage}}"
assistant: "[[response]]"
---
${'$'}echo: "?=response"
            """.trimIndent()
            
            defaultScript.writeText(scriptContent)
        }
        
        return defaultScript
    }
    
    /**
     * Create an inline default script (when file doesn't exist)
     */
    private fun createInlineDefaultScript(userMessage: String): PpeScript {
        return PpeScript(
            parameters = mapOf("userMessage" to userMessage),
            turns = listOf(
                com.qali.aterm.agent.ppe.models.PpeTurn(
                    messages = listOf(
                        com.qali.aterm.agent.ppe.models.PpeMessage(
                            role = "system",
                            content = """You are an interactive CLI agent specializing in software engineering tasks.
Your primary goal is to help users safely and efficiently, adhering strictly to the following instructions and utilizing your available tools.

# Core Mandates

- **Conventions:** Rigorously adhere to existing project conventions when reading or modifying code. Analyze surrounding code, tests, and configuration first.
- **Libraries/Frameworks:** NEVER assume a library/framework is available or appropriate. Verify its established usage within the project before employing it.
- **Style & Structure:** Mimic the style, structure, framework choices, typing, and architectural patterns of existing code in the project.
- **Proactiveness:** Fulfill the user's request thoroughly. When adding features or fixing bugs, this includes adding tests to ensure quality.
- **Explaining Changes:** After completing a code modification or file operation, do not provide summaries unless asked."""
                        ),
                        com.qali.aterm.agent.ppe.models.PpeMessage(
                            role = "user",
                            content = "{{userMessage}}"
                        ),
                        com.qali.aterm.agent.ppe.models.PpeMessage(
                            role = "assistant",
                            content = "[[response]]",
                            hasAiPlaceholder = true,
                            aiPlaceholderVar = "response"
                        )
                    ),
                    instructions = listOf(
                        com.qali.aterm.agent.ppe.models.PpeInstruction(
                            name = "echo",
                            args = mapOf("value" to "{{response}}")
                        )
                    )
                )
            )
        )
    }
    
    /**
     * Load a custom script
     */
    fun loadScript(scriptPath: String): PpeScript {
        return PpeScriptLoader.loadScript(scriptPath)
    }
    
    /**
     * Set default script path
     */
    fun setDefaultScript(scriptPath: String) {
        defaultScriptPath = scriptPath
    }
}
