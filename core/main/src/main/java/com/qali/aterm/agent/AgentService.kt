package com.qali.aterm.agent

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.client.AgentClient
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.client.OllamaClient
import com.qali.aterm.agent.ppe.CliBasedAgentClient
import com.qali.aterm.agent.tools.*
import com.qali.aterm.ui.activities.terminal.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for initializing and managing agent client with tools (supports multiple API providers)
 * Now uses CLI-based agent (PPE scripts) by default
 */
object AgentService {
    private var client: AgentClient? = null
    private var cliClient: CliBasedAgentClient? = null
    private var ollamaClient: OllamaClient? = null
    private var useOllama: Boolean = false
    private var useCliAgent: Boolean = true // Use CLI-based agent by default
    private var currentWorkspaceRoot: String = alpineDir().absolutePath
    
    fun initialize(workspaceRoot: String = alpineDir().absolutePath, useOllama: Boolean = false, ollamaUrl: String = "http://localhost:11434", ollamaModel: String = "llama3.2", sessionId: String? = null, mainActivity: MainActivity? = null): Any {
        val workspaceChanged = currentWorkspaceRoot != workspaceRoot
        val useOllamaChanged = this.useOllama != useOllama
        
        currentWorkspaceRoot = workspaceRoot
        this.useOllama = useOllama
        
        if (useOllama) {
            // Recreate client if workspace changed, useOllama changed, or client doesn't exist
            if (ollamaClient == null || workspaceChanged || useOllamaChanged) {
                val toolRegistry = ToolRegistry()
                registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity)
                
                // For Ollama, we need an AgentClient for custom search (it uses API for AI analysis)
                // Create a temporary client just for custom search tool
                val tempAgentClient = AgentClient(toolRegistry, workspaceRoot)
                toolRegistry.registerTool(CustomWebSearchTool(tempAgentClient, workspaceRoot))
                
                ollamaClient = OllamaClient(toolRegistry, workspaceRoot, ollamaUrl, ollamaModel)
            }
            return ollamaClient!!
        } else {
            // Use CLI-based agent by default
            if (useCliAgent) {
                // Recreate CLI client if workspace changed or client doesn't exist
                if (cliClient == null || workspaceChanged) {
                    val toolRegistry = ToolRegistry()
                    registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity)
                    
                    // Create CLI-based client
                    val newCliClient = CliBasedAgentClient(toolRegistry, workspaceRoot)
                    cliClient = newCliClient
                    
                    // Register web search tools (need AgentClient for these, so create a temporary one)
                    val tempAgentClient = AgentClient(toolRegistry, workspaceRoot)
                    toolRegistry.registerTool(WebSearchTool(tempAgentClient, workspaceRoot))
                    toolRegistry.registerTool(CustomWebSearchTool(tempAgentClient, workspaceRoot))
                }
                return cliClient!!
            } else {
                // Fallback to old AgentClient if CLI agent is disabled
                if (client == null || workspaceChanged || useOllamaChanged) {
                    val toolRegistry = ToolRegistry()
                    registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity)
                    
                    val newClient = AgentClient(toolRegistry, workspaceRoot)
                    client = newClient
                    
                    // Register web search tool (requires client reference)
                    toolRegistry.registerTool(WebSearchTool(newClient, workspaceRoot))
                    
                    // Register custom web search tool (always available as fallback)
                    toolRegistry.registerTool(CustomWebSearchTool(newClient, workspaceRoot))
                }
                return client!!
            }
        }
    }
    
    private fun registerAllTools(toolRegistry: ToolRegistry, workspaceRoot: String, sessionId: String? = null, mainActivity: MainActivity? = null) {
        toolRegistry.registerTool(ReadFileTool(workspaceRoot))
        toolRegistry.registerTool(WriteFileTool(workspaceRoot))
        toolRegistry.registerTool(EditTool(workspaceRoot))
        toolRegistry.registerTool(SmartEditTool(workspaceRoot))
        toolRegistry.registerTool(ShellTool(workspaceRoot, sessionId, mainActivity))
        toolRegistry.registerTool(LSTool(workspaceRoot))
        toolRegistry.registerTool(GrepTool(workspaceRoot))
        toolRegistry.registerTool(RipGrepTool(workspaceRoot))
        toolRegistry.registerTool(ReadManyFilesTool(workspaceRoot))
        toolRegistry.registerTool(GlobTool(workspaceRoot))
        toolRegistry.registerTool(WriteTodosTool())
        toolRegistry.registerTool(WebFetchTool())
        toolRegistry.registerTool(MemoryTool(workspaceRoot))
        // New enhanced tools for better file structure and syntax handling
        toolRegistry.registerTool(FileStructureTool(workspaceRoot))
        toolRegistry.registerTool(SedTool(workspaceRoot))
        toolRegistry.registerTool(SyntaxErrorDetectionTool(workspaceRoot))
        toolRegistry.registerTool(SyntaxFixTool(workspaceRoot))
        toolRegistry.registerTool(LanguageLinterTool(workspaceRoot))
    }
    
    fun getClient(): AgentClient? = client
    fun getCliClient(): CliBasedAgentClient? = cliClient
    fun getOllamaClient(): OllamaClient? = ollamaClient
    fun isUsingOllama(): Boolean = useOllama
    fun isUsingCliAgent(): Boolean = useCliAgent
    fun getWorkspaceRoot(): String = currentWorkspaceRoot
    
    /**
     * Get the active client (CLI-based or old client)
     */
    fun getActiveClient(): Any? {
        return when {
            useOllama -> ollamaClient
            useCliAgent -> cliClient
            else -> client
        }
    }
    
    fun reset() {
        client?.resetChat()
        cliClient = null // CLI client doesn't have reset, just recreate
        ollamaClient?.resetChat()
    }
    
    /**
     * Enable or disable CLI-based agent
     */
    fun setUseCliAgent(use: Boolean) {
        useCliAgent = use
        // Reset clients to force recreation
        client = null
        cliClient = null
    }
}
