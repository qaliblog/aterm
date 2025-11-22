package com.qali.aterm.gemini

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.client.GeminiClient
import com.qali.aterm.gemini.client.GeminiStreamEvent
import com.qali.aterm.gemini.client.OllamaClient
import com.qali.aterm.gemini.tools.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for initializing and managing Gemini/Ollama client with tools
 */
object GeminiService {
    private var client: GeminiClient? = null
    private var ollamaClient: OllamaClient? = null
    private var useOllama: Boolean = false
    private var currentWorkspaceRoot: String = alpineDir().absolutePath
    
    fun initialize(workspaceRoot: String = alpineDir().absolutePath, useOllama: Boolean = false, ollamaUrl: String = "http://localhost:11434", ollamaModel: String = "llama3.2"): Any {
        val workspaceChanged = currentWorkspaceRoot != workspaceRoot
        val useOllamaChanged = this.useOllama != useOllama
        
        currentWorkspaceRoot = workspaceRoot
        this.useOllama = useOllama
        
        if (useOllama) {
            // Recreate client if workspace changed, useOllama changed, or client doesn't exist
            if (ollamaClient == null || workspaceChanged || useOllamaChanged) {
                val toolRegistry = ToolRegistry()
                registerAllTools(toolRegistry, workspaceRoot)
                
                // For Ollama, we need a GeminiClient for custom search (it uses Gemini API for AI analysis)
                // Create a temporary client just for custom search tool
                val tempGeminiClient = GeminiClient(toolRegistry, workspaceRoot)
                toolRegistry.registerTool(CustomWebSearchTool(tempGeminiClient, workspaceRoot))
                
                ollamaClient = OllamaClient(toolRegistry, workspaceRoot, ollamaUrl, ollamaModel)
            }
            return ollamaClient!!
        } else {
            // Recreate client if workspace changed, useOllama changed, or client doesn't exist
            if (client == null || workspaceChanged || useOllamaChanged) {
                val toolRegistry = ToolRegistry()
                registerAllTools(toolRegistry, workspaceRoot)
                
                val newClient = GeminiClient(toolRegistry, workspaceRoot)
                client = newClient
                
                // Register web search tool (requires client reference)
                toolRegistry.registerTool(WebSearchTool(newClient, workspaceRoot))
                
                // Register custom web search tool (always available as fallback)
                toolRegistry.registerTool(CustomWebSearchTool(newClient, workspaceRoot))
            }
            return client!!
        }
    }
    
    private fun registerAllTools(toolRegistry: ToolRegistry, workspaceRoot: String) {
        toolRegistry.registerTool(ReadFileTool(workspaceRoot))
        toolRegistry.registerTool(WriteFileTool(workspaceRoot))
        toolRegistry.registerTool(EditTool(workspaceRoot))
        toolRegistry.registerTool(SmartEditTool(workspaceRoot))
        toolRegistry.registerTool(ShellTool(workspaceRoot))
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
    }
    
    fun getClient(): GeminiClient? = client
    fun getOllamaClient(): OllamaClient? = ollamaClient
    fun isUsingOllama(): Boolean = useOllama
    fun getWorkspaceRoot(): String = currentWorkspaceRoot
    
    fun reset() {
        client?.resetChat()
        ollamaClient?.resetChat()
    }
}
