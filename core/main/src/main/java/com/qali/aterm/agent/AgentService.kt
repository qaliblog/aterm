package com.qali.aterm.agent

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.client.AgentClient
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.client.OllamaClient
import com.qali.aterm.agent.tools.*
import com.qali.aterm.ui.activities.terminal.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for initializing and managing agent client with tools (supports multiple API providers)
 */
object AgentService {
    private var client: AgentClient? = null
    private var ollamaClient: OllamaClient? = null
    private var useOllama: Boolean = false
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
            // Recreate client if workspace changed, useOllama changed, or client doesn't exist
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
    fun getOllamaClient(): OllamaClient? = ollamaClient
    fun isUsingOllama(): Boolean = useOllama
    fun getWorkspaceRoot(): String = currentWorkspaceRoot
    
    fun reset() {
        client?.resetChat()
        ollamaClient?.resetChat()
    }
}
