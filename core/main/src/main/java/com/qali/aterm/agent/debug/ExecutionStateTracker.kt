package com.qali.aterm.agent.debug

import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.ppe.Observability
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks execution state for debug inspection
 * Thread-safe singleton that stores current execution context
 */
object ExecutionStateTracker {
    
    data class ExecutionState(
        val operationId: String,
        val variables: Map<String, Any> = emptyMap(),
        val chatHistory: List<Content> = emptyList(),
        val toolCalls: List<ToolCallInfo> = emptyList(),
        val scriptPath: String? = null,
        val currentTurn: Int = 0,
        val totalTurns: Int = 0,
        val startTime: Long = System.currentTimeMillis()
    )
    
    data class ToolCallInfo(
        val name: String,
        val args: Map<String, Any>,
        val timestamp: Long,
        val duration: Long? = null,
        val success: Boolean? = null,
        val error: String? = null
    )
    
    private val activeExecutions = ConcurrentHashMap<String, ExecutionState>()
    private val toolCallHistory = ConcurrentHashMap<String, MutableList<ToolCallInfo>>()
    private val variableHistory = ConcurrentHashMap<String, MutableMap<String, Any>>()
    
    /**
     * Start tracking an execution
     */
    fun startExecution(
        operationId: String,
        scriptPath: String? = null,
        initialVariables: Map<String, Any> = emptyMap()
    ) {
        activeExecutions[operationId] = ExecutionState(
            operationId = operationId,
            variables = initialVariables,
            scriptPath = scriptPath,
            startTime = System.currentTimeMillis()
        )
        variableHistory[operationId] = initialVariables.toMutableMap()
        toolCallHistory[operationId] = mutableListOf()
    }
    
    /**
     * Update execution variables
     */
    fun updateVariables(operationId: String, variables: Map<String, Any>) {
        activeExecutions[operationId]?.let { state ->
            activeExecutions[operationId] = state.copy(variables = variables)
            variableHistory[operationId]?.putAll(variables)
        }
    }
    
    /**
     * Update chat history
     */
    fun updateChatHistory(operationId: String, chatHistory: List<Content>) {
        activeExecutions[operationId]?.let { state ->
            activeExecutions[operationId] = state.copy(chatHistory = chatHistory)
        }
    }
    
    /**
     * Update turn information
     */
    fun updateTurn(operationId: String, currentTurn: Int, totalTurns: Int) {
        activeExecutions[operationId]?.let { state ->
            activeExecutions[operationId] = state.copy(
                currentTurn = currentTurn,
                totalTurns = totalTurns
            )
        }
    }
    
    /**
     * Record tool call
     */
    fun recordToolCall(
        operationId: String,
        functionCall: FunctionCall,
        duration: Long? = null,
        success: Boolean? = null,
        error: String? = null
    ) {
        val toolCallInfo = ToolCallInfo(
            name = functionCall.name,
            args = functionCall.args,
            timestamp = System.currentTimeMillis(),
            duration = duration,
            success = success,
            error = error
        )
        
        toolCallHistory[operationId]?.add(toolCallInfo)
        
        activeExecutions[operationId]?.let { state ->
            activeExecutions[operationId] = state.copy(
                toolCalls = toolCallHistory[operationId]?.toList() ?: emptyList()
            )
        }
    }
    
    /**
     * Get current execution state
     */
    fun getExecutionState(operationId: String? = null): ExecutionState? {
        return if (operationId != null) {
            activeExecutions[operationId]
        } else {
            // Return most recent execution
            activeExecutions.values.maxByOrNull { it.startTime }
        }
    }
    
    /**
     * Get all active executions
     */
    fun getAllActiveExecutions(): List<ExecutionState> {
        return activeExecutions.values.toList()
    }
    
    /**
     * Get operation metrics from Observability
     */
    suspend fun getOperationMetrics(operationId: String): Observability.OperationMetrics? {
        return Observability.getOperationMetrics(operationId)
    }
    
    /**
     * Get global statistics
     */
    fun getGlobalStats(): Map<String, Any> {
        return Observability.getGlobalStats()
    }
    
    /**
     * End execution tracking
     */
    fun endExecution(operationId: String) {
        activeExecutions.remove(operationId)
        toolCallHistory.remove(operationId)
        variableHistory.remove(operationId)
    }
    
    /**
     * Clear all tracking data
     */
    fun clear() {
        activeExecutions.clear()
        toolCallHistory.clear()
        variableHistory.clear()
    }
}
