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
     * Send a message and get response using PPE script
     * Maintains same interface as old AgentClient for UI compatibility
     */
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit,
        scriptPath: String? = null
    ): Flow<AgentEvent> = flow {
        try {
            // Load script (use provided path or default)
            val script = when {
                scriptPath != null -> {
                    val scriptFile = File(scriptPath)
                    if (scriptFile.exists()) {
                        PpeScriptLoader.loadScript(scriptFile)
                    } else {
                        createInlineDefaultScript(userMessage)
                    }
                }
                defaultScriptPath != null -> {
                    val scriptFile = File(defaultScriptPath!!)
                    if (scriptFile.exists()) {
                        PpeScriptLoader.loadScript(scriptFile)
                    } else {
                        createInlineDefaultScript(userMessage)
                    }
                }
                else -> {
                    // Try to find default script in workspace
                    val defaultScript = File(workspaceRoot, "agent-scripts/default.ai.yaml")
                    if (defaultScript.exists()) {
                        PpeScriptLoader.loadScript(defaultScript)
                    } else {
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
            
            // Execute script
            executionEngine.executeScript(
                script = script,
                inputParams = inputParams,
                onChunk = { chunk ->
                    onChunk(chunk)
                    emit(AgentEvent.Chunk(chunk))
                },
                onToolCall = { functionCall ->
                    onToolCall(functionCall)
                    emit(AgentEvent.ToolCall(functionCall))
                },
                onToolResult = { toolName, args ->
                    onToolResult(toolName, args)
                    
                    // Get tool result from execution engine (FIFO queue)
                    val toolResult = executionEngine.getNextToolResult(toolName)
                    if (toolResult != null) {
                        // Emit tool result - AgentScreen will extract file diffs from this
                        emit(AgentEvent.ToolResult(toolName, toolResult))
                    }
                }
            ).collect { result ->
                if (result.success) {
                    // Emit final result if any
                    if (result.finalResult.isNotEmpty()) {
                        emit(AgentEvent.Chunk(result.finalResult))
                        onChunk(result.finalResult)
                    }
                    emit(AgentEvent.Done)
                } else {
                    emit(AgentEvent.Error(result.error ?: "Script execution failed"))
                }
            }
            
        } catch (e: Exception) {
            Log.e("CliBasedAgentClient", "Error executing script", e)
            emit(AgentEvent.Error(e.message ?: "Unknown error"))
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
$echo: "?=response"
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
