package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.tools.ToolRegistry
import com.qali.aterm.agent.ppe.models.PpeScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import android.util.Log
import java.io.File

/**
 * CLI-based agent client that replaces the old AgentClient
 * Uses PPE (Programmable Prompt Engine) scripts for agentic workflow
 */
class CliBasedAgentClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String = "/data/data/com.termux/files/home",
    private val ollamaUrl: String? = null,
    private val ollamaModel: String? = null
) {
    private val executionEngine = PpeExecutionEngine(toolRegistry, workspaceRoot, ollamaUrl, ollamaModel)
    private val apiClient = PpeApiClient(toolRegistry, ollamaUrl, ollamaModel)
    private val processAnalyzer = ProcessDataAnalyzer(
        workspaceRoot = workspaceRoot,
        apiClient = apiClient,
        tasksPerAnalysis = 1
    )
    
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
        systemContext: String? = null,  // System information (OS, package manager, etc.)
        sessionId: String = "main"  // Session ID for tracking and analysis
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
            
            // Use Channel to queue events from non-suspend callbacks and emit them immediately
            val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)
            
            // Launch coroutine to consume events from channel and emit them
            // Use coroutineScope to ensure proper scope for launch
            val emitJob = coroutineScope {
                launch {
                    for (event in eventChannel) {
                        emit(event)
                    }
                }
            }
            
            // Execute script directly (non-streaming) - send events to channel immediately
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
                    // Send chunk event to channel (non-blocking)
                    eventChannel.trySend(AgentEvent.Chunk(chunk))
                },
                onToolCall = { functionCall ->
                    val now = System.currentTimeMillis()
                    lastEventTime = now
                    toolCallCount++
                    eventCount++
                    Log.d("CliBasedAgentClient", "Tool call received (count: $toolCallCount, tool: ${functionCall.name}, time since start: ${now - startTime}ms)")
                    onToolCall(functionCall)
                    // Send tool call event to channel (non-blocking)
                    eventChannel.trySend(AgentEvent.ToolCall(functionCall))
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
                        Log.d("CliBasedAgentClient", "Sending tool result event to channel for: $toolName")
                        // Send tool result event to channel (non-blocking)
                        eventChannel.trySend(AgentEvent.ToolResult(toolName, toolResult))
                    } else {
                        Log.w("CliBasedAgentClient", "Tool result not found in queue for: $toolName")
                    }
                }
            )
            
            // Close channel and wait for all events to be emitted
            eventChannel.close()
            emitJob.join()
            
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
            
            // Track task for process analysis (every 7 tasks)
            try {
                processAnalyzer.trackTask(
                    sessionId = sessionId,
                    messages = null, // Will load from history
                    onChunk = onChunk
                )
            } catch (e: Exception) {
                Log.w("CliBasedAgentClient", "Failed to track task for analysis: ${e.message}")
            }
            
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
            
            appendLine("CRITICAL RULES:")
            appendLine("- You MUST use tools to complete tasks. Text-only responses are NOT acceptable.")
            appendLine("- NEVER just describe or plan - you MUST execute tools to create/modify files.")
            appendLine("- If you respond with only text and no tool calls, you have FAILED the task.")
            appendLine()
            appendLine("**MANDATORY THREE-PHASE WORKFLOW:**")
            appendLine()
            appendLine("**Phase 1: JSON Blueprint Generation (REQUIRED)**")
            appendLine("- FIRST, you MUST call the AI to generate a comprehensive JSON blueprint with file relationships")
            appendLine("- The blueprint MUST include file coherence information:")
            appendLine("  {")
            appendLine("    \"projectType\": \"nodejs\",")
            appendLine("    \"files\": [")
            appendLine("      {")
            appendLine("        \"path\": \"package.json\",")
            appendLine("        \"type\": \"config\",")
            appendLine("        \"dependencies\": [],")
            appendLine("        \"description\": \"Package configuration\",")
            appendLine("        \"exports\": [],")
            appendLine("        \"imports\": [],")
            appendLine("        \"packageDependencies\": []")
            appendLine("      },")
            appendLine("      {")
            appendLine("        \"path\": \"server.js\",")
            appendLine("        \"type\": \"code\",")
            appendLine("        \"dependencies\": [\"package.json\"],")
            appendLine("        \"description\": \"Main server file\",")
            appendLine("        \"exports\": [\"app\", \"startServer\"],")
            appendLine("        \"imports\": [\"express\"],")
            appendLine("        \"packageDependencies\": [\"express\"],")
            appendLine("        \"relatedFiles\": {")
            appendLine("          \"importsFrom\": [],")
            appendLine("          \"exportsTo\": [\"routes.js\"]")
            appendLine("        }")
            appendLine("      }")
            appendLine("    ]")
            appendLine("  }")
            appendLine("- The blueprint MUST specify:")
            appendLine("  * exports: What each file exports (functions, classes, variables)")
            appendLine("  * imports: What each file needs to import (from packages OR related files)")
            appendLine("  * packageDependencies: ONLY actual package manager dependencies (npm, pip, etc.)")
            appendLine("  * relatedFiles: File relationships (importsFrom, exportsTo)")
            appendLine("- After generating the blueprint, you MUST parse it and extract the file list")
            appendLine()
            appendLine("**Phase 2: File Creation (REQUIRED)**")
            appendLine("- For EACH file in the blueprint, you MUST call write_file tool")
            appendLine("- Start with dependency files first (package.json, config files)")
            appendLine("- Then create code files in dependency order")
            appendLine("- CRITICAL: Use ONLY the imports/exports specified in the blueprint")
            appendLine("- DO NOT skip any files - create ALL files from the blueprint")
            appendLine()
            appendLine("**Phase 3: Verification (REQUIRED)**")
            appendLine("- After creating all files, verify the project works")
            appendLine("- Use shell tool to run commands if needed (npm install, etc.)")
            appendLine()
            appendLine("**TOOL USAGE REQUIREMENTS:**")
            appendLine("- For new projects: You MUST call write_file at least 3-5 times (package.json + code files)")
            appendLine("- For Node.js projects: Create package.json, server/index.js, HTML, CSS, and JavaScript files")
            appendLine("- After each write_file, continue with the next file automatically")
            appendLine("- If you finish with less than 3 tool calls, you are NOT done - create more files")
            appendLine()
            appendLine("**CODE COHERENCE RULES (CRITICAL):**")
            appendLine("- Use ONLY imports/exports that are specified in the parsed blueprint")
            appendLine("- For package dependencies: Use ONLY packages listed in blueprint's packageDependencies")
            appendLine("- For file imports: Import ONLY from related files listed in blueprint, use ONLY their exports")
            appendLine("- Do NOT use any imports that are NOT in the blueprint")
            appendLine("- Ensure all exports match what's specified in the blueprint")
            appendLine("- Ensure all imports match what's available from related files or package dependencies")
            appendLine("- Maintain consistent style and structure across all files")
            appendLine("- The blueprint is the SOURCE OF TRUTH for all file relationships and imports/exports")
            
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
