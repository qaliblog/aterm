package com.qali.aterm.agent.ppe.models

/**
 * Represents a control flow block (if, while, for, match)
 */
data class PpeControlFlowBlock(
    /**
     * Type: if, while, for, match, pipe
     */
    val type: String,
    
    /**
     * Condition or expression
     */
    val condition: String? = null,
    
    /**
     * Then block (for if)
     */
    val thenBlock: List<PpeInstruction>? = null,
    
    /**
     * Else block (for if)
     */
    val elseBlock: List<PpeInstruction>? = null,
    
    /**
     * Do block (for while)
     */
    val doBlock: List<PpeInstruction>? = null,
    
    /**
     * Match cases (for match)
     */
    val cases: Map<String, List<PpeInstruction>>? = null,
    
    /**
     * Pipe chain (for pipe)
     */
    val pipeChain: List<String>? = null
)
