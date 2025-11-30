package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema

/**
 * Registry for managing all available tools
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, DeclarativeTool<*, *>>()
    
    fun registerTool(tool: DeclarativeTool<*, *>) {
        tools[tool.name] = tool
    }
    
    fun getTool(name: String): DeclarativeTool<*, *>? {
        return tools[name]
    }
    
    fun getAllTools(): List<DeclarativeTool<*, *>> {
        return tools.values.toList()
    }
    
    fun getFunctionDeclarations(): List<FunctionDeclaration> {
        return tools.values.map { it.getFunctionDeclaration() }
    }
}

/**
 * Base class for declarative tools
 */
abstract class DeclarativeTool<TParams, TResult : ToolResult> {
    abstract val name: String
    abstract val displayName: String
    abstract val description: String
    abstract val parameterSchema: FunctionParameters
    
    abstract fun getFunctionDeclaration(): FunctionDeclaration
    
    abstract fun createInvocation(
        params: TParams,
        toolName: String? = null,
        toolDisplayName: String? = null
    ): ToolInvocation<TParams, TResult>
    
    fun validateParams(params: Map<String, Any>): TParams? {
        // Basic validation - can be extended
        return try {
            validateAndConvertParams(params)
        } catch (e: Exception) {
            null
        }
    }
    
    protected abstract fun validateAndConvertParams(params: Map<String, Any>): TParams
}
