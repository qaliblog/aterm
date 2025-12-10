package com.qali.aterm.agent

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.client.AgentClient
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.client.OllamaClient
import com.qali.aterm.agent.ppe.CliBasedAgentClient
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.tools.*
import com.qali.aterm.agent.tools.IntelligentErrorAnalysisTool
import com.qali.aterm.ui.activities.terminal.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

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
    private var currentOllamaUrl: String? = null
    private var currentOllamaModel: String? = null
    
    fun initialize(workspaceRoot: String = alpineDir().absolutePath, useOllama: Boolean = false, ollamaUrl: String = "http://localhost:11434", ollamaModel: String = "llama3.2", sessionId: String? = null, mainActivity: MainActivity? = null): Any {
        // Initialize debug logger (console-only; disable file logging to avoid .aterm files)
        DebugLogger.initialize(
            logLevel = DebugLogger.LogLevel.INFO,
            enableFileLogging = false,
            logDir = null,
            maxFileSize = 10 * 1024 * 1024, // Unused when file logging is disabled
            maxFiles = 0
        )
        
        val workspaceChanged = currentWorkspaceRoot != workspaceRoot
        val useOllamaChanged = this.useOllama != useOllama
        val ollamaConfigChanged = this.currentOllamaUrl != ollamaUrl || this.currentOllamaModel != ollamaModel
        
        currentWorkspaceRoot = workspaceRoot
        this.useOllama = useOllama
        this.currentOllamaUrl = if (useOllama) ollamaUrl else null
        this.currentOllamaModel = if (useOllama) ollamaModel else null
        
        DebugLogger.i("AgentService", "Initializing agent service", mapOf(
            "workspace" to workspaceRoot,
            "use_ollama" to useOllama,
            "ollama_url" to (ollamaUrl.takeIf { useOllama } ?: "none"),
            "ollama_model" to (ollamaModel.takeIf { useOllama } ?: "none")
        ))
        
        if (useOllama) {
            // Use CLI-based agent for Ollama too (non-streaming flow)
            if (useCliAgent) {
                // Recreate CLI client if workspace changed, useOllama changed, Ollama config changed, or client doesn't exist
                if (cliClient == null || workspaceChanged || useOllamaChanged || ollamaConfigChanged) {
                    val toolRegistry = ToolRegistry()
                    registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity, ollamaUrl, ollamaModel)
                    
                    // Create CLI-based client with Ollama configuration (bypasses ApiProviderManager)
                    val newCliClient = CliBasedAgentClient(toolRegistry, workspaceRoot, ollamaUrl, ollamaModel)
                    cliClient = newCliClient
                    
                    // Register web search tools (need AgentClient for these, so create a temporary one)
                    val tempAgentClient = AgentClient(toolRegistry, workspaceRoot)
                    toolRegistry.registerTool(WebSearchTool(tempAgentClient, workspaceRoot))
                    toolRegistry.registerTool(CustomWebSearchTool(tempAgentClient, workspaceRoot))
                }
                return cliClient!!
            } else {
                // Fallback to old OllamaClient if CLI agent is disabled
                if (ollamaClient == null || workspaceChanged || useOllamaChanged || ollamaConfigChanged) {
                    val toolRegistry = ToolRegistry()
                    registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity, ollamaUrl, ollamaModel)
                    
                    // For Ollama, we need an AgentClient for custom search (it uses API for AI analysis)
                    // Create a temporary client just for custom search tool
                    val tempAgentClient = AgentClient(toolRegistry, workspaceRoot)
                    toolRegistry.registerTool(CustomWebSearchTool(tempAgentClient, workspaceRoot))
                    
                    ollamaClient = OllamaClient(toolRegistry, workspaceRoot, ollamaUrl, ollamaModel)
                }
                return ollamaClient!!
            }
        } else {
            // Use CLI-based agent by default (without Ollama)
            if (useCliAgent) {
                // Recreate CLI client if workspace changed or client doesn't exist
                if (cliClient == null || workspaceChanged || useOllamaChanged) {
                    val toolRegistry = ToolRegistry()
                    registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity, null, null)
                    
                    // Create CLI-based client (no Ollama config - uses ApiProviderManager)
                    val newCliClient = CliBasedAgentClient(toolRegistry, workspaceRoot, null, null)
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
                    registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity, null, null)
                    
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
    
    /**
     * Create a ToolRegistry with all tools registered
     */
    fun createToolRegistry(
        workspaceRoot: String,
        sessionId: String? = null,
        mainActivity: MainActivity? = null,
        ollamaUrl: String? = null,
        ollamaModel: String? = null
    ): ToolRegistry {
        val toolRegistry = ToolRegistry()
        registerAllTools(toolRegistry, workspaceRoot, sessionId, mainActivity, ollamaUrl, ollamaModel)
        return toolRegistry
    }
    
    private fun registerAllTools(
        toolRegistry: ToolRegistry, 
        workspaceRoot: String, 
        sessionId: String? = null, 
        mainActivity: MainActivity? = null,
        ollamaUrl: String? = null,
        ollamaModel: String? = null
    ) {
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
        // Debug tool for inspecting execution state
        toolRegistry.registerTool(DebugTool())
        // Test execution tool
        toolRegistry.registerTool(TestExecutionTool(workspaceRoot))
        // Code coverage analysis tool
        toolRegistry.registerTool(CoverageAnalysisTool(workspaceRoot))
        // Project analysis tool
        toolRegistry.registerTool(ProjectAnalysisTool(workspaceRoot))
        // Dependency management tool
        toolRegistry.registerTool(DependencyManagementTool(workspaceRoot))
        // Architecture analysis tool
        toolRegistry.registerTool(ArchitectureAnalysisTool(workspaceRoot))
        // Code quality metrics tool
        toolRegistry.registerTool(CodeQualityMetricsTool(workspaceRoot))
        // Performance profiling tool
        toolRegistry.registerTool(PerformanceProfilingTool(workspaceRoot))
        // Execution trace tool
        toolRegistry.registerTool(ExecutionTraceTool(workspaceRoot))
        // Variable inspector tool
        toolRegistry.registerTool(VariableInspectorTool(workspaceRoot))
        // Command history tool
        toolRegistry.registerTool(CommandHistoryTool(workspaceRoot))
        // Interactive shell tool
        toolRegistry.registerTool(InteractiveShellTool(workspaceRoot, mainActivity))
        // Document analysis tool
        toolRegistry.registerTool(DocumentAnalysisTool(workspaceRoot))
        // Code review tool
        toolRegistry.registerTool(CodeReviewTool(workspaceRoot))
        // Documentation generation tool
        toolRegistry.registerTool(DocumentationGenerationTool(workspaceRoot))
        // Intelligent error analysis tool (with toolRegistry for API access)
        toolRegistry.registerTool(IntelligentErrorAnalysisTool(workspaceRoot, toolRegistry, ollamaUrl, ollamaModel))
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
