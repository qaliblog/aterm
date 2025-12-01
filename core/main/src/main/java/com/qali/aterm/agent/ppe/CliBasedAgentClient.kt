package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.tools.ToolRegistry
import com.qali.aterm.agent.ppe.models.PpeScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
     * Send a message and get response using PPE script (non-streaming)
     * Maintains same interface as old AgentClient for UI compatibility
     */
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        scriptPath: String? = null,
        memory: String? = null,  // Agent memory context from previous interactions
        systemContext: String? = null  // System information (OS, package manager, etc.)
    ): Flow<AgentEvent> = flow {
        val startTime = System.currentTimeMillis()
        var eventCount = 0
        var lastEventTime = startTime
        var toolCallCount = 0
        var toolResultCount = 0
        var chunkCount = 0
        
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
                        createInlineDefaultScript(userMessage, memory, systemContext)
                    }
                }
                defaultScriptPath != null -> {
                    val scriptFile = File(defaultScriptPath!!)
                    if (scriptFile.exists()) {
                        Log.d("CliBasedAgentClient", "Loading default script from: $defaultScriptPath")
                        PpeScriptLoader.loadScript(scriptFile)
                    } else {
                        Log.d("CliBasedAgentClient", "Default script file not found, using inline default")
                        createInlineDefaultScript(userMessage, memory, systemContext)
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
                        createInlineDefaultScript(userMessage, memory, systemContext)
                    }
                }
            }
            
            // Prepare input parameters
            val inputParams = mapOf(
                "userMessage" to userMessage,
                "content" to userMessage
            )
            
            Log.d("CliBasedAgentClient", "Starting script execution (non-streaming)")
            
            // Collect events during execution (non-streaming) - callbacks are called synchronously
            val events = mutableListOf<AgentEvent>()
            
            // Execute script directly (non-streaming) - callbacks are called synchronously
            val result = executionEngine.executeScript(
                script = script,
                inputParams = inputParams,
                onChunk = { chunk ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    chunkCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Chunk received (count: $chunkCount, size: ${chunk.length}, time since start: ${now - startTime}ms)")
                    onChunk(chunk)
                    // Collect chunk event
                    events.add(AgentEvent.Chunk(chunk))
                },
                onToolCall = { functionCall ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    toolCallCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Tool call received (count: $toolCallCount, tool: ${functionCall.name}, time since start: ${now - startTime}ms)")
                    onToolCall(functionCall)
                    // Collect tool call event
                    events.add(AgentEvent.ToolCall(functionCall))
                },
                onToolResult = { toolName, args ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    toolResultCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Tool result callback (count: $toolResultCount, tool: $toolName, time since start: ${now - startTime}ms)")
                    onToolResult(toolName, args)
                    
                    // Get tool result from execution engine (FIFO queue)
                    val toolResult: com.qali.aterm.agent.tools.ToolResult? = executionEngine.getNextToolResult(toolName)
                    if (toolResult != null) {
                        Log.d("CliBasedAgentClient", "Sending tool result event for: $toolName")
                        // Collect tool result event - AgentScreen will extract file diffs from this
                        events.add(AgentEvent.ToolResult(toolName, toolResult))
                    } else {
                        Log.w("CliBasedAgentClient", "Tool result not found in queue for: $toolName")
                    }
                }
            )
            
            // Emit all collected events
            events.forEach { event ->
                emit(event)
            }
            
            val now = System.currentTimeMillis()
            val totalTime = now - startTime
            val timeSinceLastEvent = now - lastEventTime
            
            Log.d("CliBasedAgentClient", "Execution result received (success: ${result.success}, total time: ${totalTime}ms, time since last event: ${timeSinceLastEvent}ms)")
            Log.d("CliBasedAgentClient", "Event summary - Total: $eventCount, Chunks: $chunkCount, ToolCalls: $toolCallCount, ToolResults: $toolResultCount")
            
            if (result.success) {
                // Emit final result if any
                if (result.finalResult.isNotEmpty()) {
                    Log.d("CliBasedAgentClient", "Sending final result (length: ${result.finalResult.length})")
                    emit(AgentEvent.Chunk(result.finalResult))
                    onChunk(result.finalResult)
                } else {
                    Log.d("CliBasedAgentClient", "No final result to send")
                }
                Log.d("CliBasedAgentClient", "Sending Done event")
                emit(AgentEvent.Done)
            } else {
                val errorMsg = result.error ?: "Script execution failed"
                Log.e("CliBasedAgentClient", "Script execution failed: $errorMsg")
                emit(AgentEvent.Error(errorMsg))
            }
            
            Log.d("CliBasedAgentClient", "Script execution completed successfully (total time: ${totalTime}ms)")
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            val totalTime = System.currentTimeMillis() - startTime
            val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime
            Log.w("CliBasedAgentClient", "Script execution cancelled (total time: ${totalTime}ms, time since last event: ${timeSinceLastEvent}ms, events: $eventCount)")
            Log.w("CliBasedAgentClient", "Cancellation details - Chunks: $chunkCount, ToolCalls: $toolCallCount, ToolResults: $toolResultCount")
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            val timeSinceLastEvent = System.currentTimeMillis() - lastEventTime
            Log.e("CliBasedAgentClient", "Error executing script (total time: ${totalTime}ms, time since last event: ${timeSinceLastEvent}ms)", e)
            Log.e("CliBasedAgentClient", "Error details - Events: $eventCount, Chunks: $chunkCount, ToolCalls: $toolCallCount, ToolResults: $toolResultCount")
            Log.e("CliBasedAgentClient", "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
            e.printStackTrace()
            // Emit error event
            emit(AgentEvent.Error(e.message ?: "Unknown error: ${e.javaClass.simpleName}"))
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
    private fun createInlineDefaultScript(userMessage: String, memory: String? = null, systemContext: String? = null): PpeScript {
        // Build system prompt with context
        val systemPrompt = buildString {
            appendLine("You are a CLI agent that helps users complete software tasks.")
            appendLine()
            
            // Add system context if available
            if (systemContext != null && systemContext.isNotEmpty()) {
                appendLine(systemContext)
                appendLine()
            }
            
            // Add memory if available (for subsequent prompts in same session)
            if (memory != null && memory.isNotEmpty()) {
                appendLine(memory)
                appendLine()
            }
            
            appendLine("Rules:")
            appendLine("- Use tools to complete tasks. Don't just plan - implement.")
            appendLine("- Read existing files (read tool) before modifying them to keep code consistent.")
            appendLine("- Use write_file to create/modify files. Use shell to run commands.")
            appendLine("- Complete the entire task. Keep working until done.")
            appendLine("- For Node.js projects: create package.json, server files, HTML, CSS, and JavaScript.")
            appendLine("- After each tool call, continue with the next step automatically.")
            appendLine()
            appendLine("**TWO-PHASE APPROACH:**")
            appendLine("**Phase 1: Blueprint Generation**")
            appendLine("- First, analyze all existing files to build a comprehensive dependency matrix blueprint")
            appendLine("- The blueprint will show: file names, locations, functions, imports, exports, and dependencies")
            appendLine("- After reviewing the blueprint, suggest:")
            appendLine("  * File names and locations in the best format and order to write them")
            appendLine("  * Which files should be referenced/tagged when writing each main file for better code coherence")
            appendLine()
            appendLine("**Phase 2: Sequential File Writing**")
            appendLine("- Write files one by one, each with a separate prompt")
            appendLine("- Each file writing prompt includes:")
            appendLine("  * The blueprint metadata for that specific file")
            appendLine("  * Related files and their exports/functions/classes")
            appendLine("  * Instructions to use ONLY the names/imports from the blueprint")
            appendLine("- Maintain code coherence: check existing code structure and style before writing new code.")
            appendLine("- IMPORTANT: Code dependency matrix is maintained automatically. When writing files, check the relativeness information in tool results. Only use imports/exports that exist in related files. Do not add extra imports or unrelated function names.")
            
            // Add system-specific guidance if context is available
            if (systemContext != null && systemContext.contains("Package Manager")) {
                appendLine()
                appendLine("- IMPORTANT: Use the correct package manager commands for this system. Check the system information above for the correct commands.")
            }
        }
        
        return PpeScript(
            parameters = mapOf("userMessage" to userMessage),
            turns = listOf(
                com.qali.aterm.agent.ppe.models.PpeTurn(
                    messages = listOf(
                        com.qali.aterm.agent.ppe.models.PpeMessage(
                            role = "system",
                            content = systemPrompt.trim()
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
