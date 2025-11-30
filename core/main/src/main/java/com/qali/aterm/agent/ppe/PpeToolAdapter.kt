package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.tools.ToolRegistry
import com.qali.aterm.agent.tools.ToolResult

/**
 * Adapter to bridge PPE tool calls to aterm tool system
 * This ensures compatibility with existing tool infrastructure
 */
object PpeToolAdapter {
    /**
     * Execute a tool call from PPE script
     */
    suspend fun executeTool(
        functionCall: FunctionCall,
        toolRegistry: ToolRegistry,
        workspaceRoot: String
    ): ToolResult {
        val tool = toolRegistry.getTool(functionCall.name)
        if (tool == null) {
            return ToolResult(
                llmContent = "Tool not found: ${functionCall.name}",
                error = com.qali.aterm.agent.tools.ToolError(
                    message = "Tool not found: ${functionCall.name}",
                    type = com.qali.aterm.agent.tools.ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Validate and convert parameters
        val params = tool?.validateParams(functionCall.args)
        if (params == null) {
            return ToolResult(
                llmContent = "Invalid parameters for tool: ${functionCall.name}",
                error = com.qali.aterm.agent.tools.ToolError(
                    message = "Invalid parameters",
                    type = com.qali.aterm.agent.tools.ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Create invocation and execute
        val invocation = tool?.createInvocation(params)
        return invocation?.execute() ?: ToolResult(
            llmContent = "Failed to execute tool: ${functionCall.name}",
            error = com.qali.aterm.agent.tools.ToolError(
                message = "Execution failed",
                type = com.qali.aterm.agent.tools.ToolErrorType.EXECUTION_ERROR
            )
        )
    }
}
