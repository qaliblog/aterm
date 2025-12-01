package com.qali.aterm.agent.debug

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Breakpoint manager for debugging script execution
 * Allows setting breakpoints at specific turns or instructions and inspecting state
 */
object BreakpointManager {
    
    data class Breakpoint(
        val id: String,
        val type: BreakpointType,
        val location: String, // turn number, instruction index, or condition
        val condition: String? = null, // Optional condition (e.g., variable value check)
        val enabled: Boolean = true,
        val hitCount: Int = 0
    )
    
    enum class BreakpointType {
        TURN,           // Break at specific turn number
        INSTRUCTION,    // Break at specific instruction index
        CONDITION,      // Break when condition is met
        VARIABLE,       // Break when variable changes
        TOOL_CALL       // Break before/after tool call
    }
    
    data class BreakpointState(
        val breakpoint: Breakpoint,
        val operationId: String,
        val context: Map<String, Any>, // Variables, turn info, etc.
        val timestamp: Long
    )
    
    private val breakpoints = ConcurrentHashMap<String, Breakpoint>()
    private val activeBreakpoints = ConcurrentHashMap<String, BreakpointState>()
    private val breakpointMutex = Mutex()
    private var isPaused = false
    private var pausedOperationId: String? = null
    
    /**
     * Set a breakpoint
     */
    fun setBreakpoint(
        type: BreakpointType,
        location: String,
        condition: String? = null
    ): Breakpoint {
        val id = "bp-${System.currentTimeMillis()}-${breakpoints.size}"
        val breakpoint = Breakpoint(
            id = id,
            type = type,
            location = location,
            condition = condition,
            enabled = true,
            hitCount = 0
        )
        breakpoints[id] = breakpoint
        Log.d("BreakpointManager", "Breakpoint set: $type at $location")
        return breakpoint
    }
    
    /**
     * Remove a breakpoint
     */
    suspend fun removeBreakpoint(breakpointId: String): Boolean {
        return breakpointMutex.withLock {
            val removed = breakpoints.remove(breakpointId) != null
            if (removed) {
                Log.d("BreakpointManager", "Breakpoint removed: $breakpointId")
            }
            removed
        }
    }
    
    /**
     * Enable/disable a breakpoint
     */
    suspend fun setBreakpointEnabled(breakpointId: String, enabled: Boolean): Boolean {
        return breakpointMutex.withLock {
            val breakpoint = breakpoints[breakpointId]
            if (breakpoint != null) {
                breakpoints[breakpointId] = breakpoint.copy(enabled = enabled)
                Log.d("BreakpointManager", "Breakpoint $breakpointId ${if (enabled) "enabled" else "disabled"}")
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Get all breakpoints
     */
    fun getAllBreakpoints(): List<Breakpoint> {
        return breakpoints.values.toList()
    }
    
    /**
     * Get breakpoint by ID
     */
    fun getBreakpoint(breakpointId: String): Breakpoint? {
        return breakpoints[breakpointId]
    }
    
    /**
     * Check if execution should break at current location
     */
    suspend fun checkBreakpoint(
        operationId: String,
        type: BreakpointType,
        location: String,
        context: Map<String, Any> = emptyMap()
    ): Boolean {
        return breakpointMutex.withLock {
            if (isPaused && pausedOperationId == operationId) {
                return@withLock false // Already paused
            }
            
            val matchingBreakpoints = breakpoints.values.filter { bp ->
                bp.enabled && bp.type == type && matchesLocation(bp, location, context)
            }
            
            if (matchingBreakpoints.isNotEmpty()) {
                // Check conditions if any
                val shouldBreak = matchingBreakpoints.all { bp ->
                    if (bp.condition != null) {
                        evaluateCondition(bp.condition, context)
                    } else {
                        true
                    }
                }
                
                if (shouldBreak) {
                    // Increment hit count
                    matchingBreakpoints.forEach { bp ->
                        breakpoints[bp.id] = bp.copy(hitCount = bp.hitCount + 1)
                    }
                    
                    // Create breakpoint state
                    val state = BreakpointState(
                        breakpoint = matchingBreakpoints.first(),
                        operationId = operationId,
                        context = context,
                        timestamp = System.currentTimeMillis()
                    )
                    activeBreakpoints[operationId] = state
                    isPaused = true
                    pausedOperationId = operationId
                    
                    Log.d("BreakpointManager", "Breakpoint hit: ${matchingBreakpoints.first().id} at $location")
                    return@withLock true
                }
            }
            
            false
        }
    }
    
    /**
     * Check if execution is currently paused
     */
    fun isPaused(operationId: String? = null): Boolean {
        return if (operationId != null) {
            isPaused && pausedOperationId == operationId
        } else {
            isPaused
        }
    }
    
    /**
     * Get current breakpoint state
     */
    fun getBreakpointState(operationId: String): BreakpointState? {
        return activeBreakpoints[operationId]
    }
    
    /**
     * Continue execution after breakpoint
     */
    suspend fun continueExecution(operationId: String): Boolean {
        return breakpointMutex.withLock {
            if (isPaused && pausedOperationId == operationId) {
                isPaused = false
                pausedOperationId = null
                activeBreakpoints.remove(operationId)
                Log.d("BreakpointManager", "Continuing execution: $operationId")
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Step to next instruction/turn
     */
    suspend fun step(operationId: String): Boolean {
        return breakpointMutex.withLock {
            if (isPaused && pausedOperationId == operationId) {
                // Temporarily disable breakpoints for one step
                // This is a simplified implementation
                isPaused = false
                pausedOperationId = null
                Log.d("BreakpointManager", "Stepping: $operationId")
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Clear all breakpoints
     */
    suspend fun clearAllBreakpoints() {
        breakpointMutex.withLock {
            breakpoints.clear()
            activeBreakpoints.clear()
            isPaused = false
            pausedOperationId = null
            Log.d("BreakpointManager", "All breakpoints cleared")
        }
    }
    
    /**
     * Get variables at breakpoint
     */
    fun getVariablesAtBreakpoint(operationId: String): Map<String, Any>? {
        val state = activeBreakpoints[operationId]
        return state?.context?.get("variables") as? Map<String, Any>
    }
    
    /**
     * Check if location matches breakpoint
     */
    private fun matchesLocation(
        breakpoint: Breakpoint,
        location: String,
        context: Map<String, Any>
    ): Boolean {
        return when (breakpoint.type) {
            BreakpointType.TURN -> {
                val turnNumber = context["turnNumber"] as? Int
                breakpoint.location == turnNumber.toString() || breakpoint.location == location
            }
            BreakpointType.INSTRUCTION -> {
                breakpoint.location == location
            }
            BreakpointType.CONDITION -> {
                // Condition-based breakpoints are checked separately
                true
            }
            BreakpointType.VARIABLE -> {
                val varName = breakpoint.location
                context.containsKey("variable_$varName") || context["variables"]?.let { 
                    (it as? Map<*, *>)?.containsKey(varName) == true
                } == true
            }
            BreakpointType.TOOL_CALL -> {
                val toolName = context["toolName"] as? String
                breakpoint.location == toolName || breakpoint.location == "*"
            }
        }
    }
    
    /**
     * Evaluate breakpoint condition
     */
    private fun evaluateCondition(condition: String, context: Map<String, Any>): Boolean {
        // Simple condition evaluation
        // Supports: variable == value, variable != value, variable > value, etc.
        try {
            val variables = (context["variables"] as? Map<*, *>) ?: emptyMap<Any, Any>()
            
            // Parse simple conditions like "varName == value" or "varName > 5"
            val operators = listOf("==", "!=", ">", "<", ">=", "<=")
            for (op in operators) {
                if (condition.contains(op)) {
                    val parts = condition.split(op).map { it.trim() }
                    if (parts.size == 2) {
                        val varName = parts[0]
                        val value = parts[1]
                        val varValue = variables[varName]?.toString() ?: ""
                        
                        return when (op) {
                            "==" -> varValue == value
                            "!=" -> varValue != value
                            ">" -> varValue.toDoubleOrNull()?.let { it > value.toDoubleOrNull() ?: 0.0 } ?: false
                            "<" -> varValue.toDoubleOrNull()?.let { it < value.toDoubleOrNull() ?: 0.0 } ?: false
                            ">=" -> varValue.toDoubleOrNull()?.let { it >= value.toDoubleOrNull() ?: 0.0 } ?: false
                            "<=" -> varValue.toDoubleOrNull()?.let { it <= value.toDoubleOrNull() ?: 0.0 } ?: false
                            else -> false
                        }
                    }
                }
            }
            
            // Default: check if variable exists
            return variables.containsKey(condition.trim())
        } catch (e: Exception) {
            Log.w("BreakpointManager", "Error evaluating condition: $condition", e)
            return false
        }
    }
}
