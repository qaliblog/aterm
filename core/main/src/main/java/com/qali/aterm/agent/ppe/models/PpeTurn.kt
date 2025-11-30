package com.qali.aterm.agent.ppe.models

/**
 * Represents a dialogue turn in a PPE script
 * Separated by --- or ***
 */
data class PpeTurn(
    /**
     * List of messages in this turn
     */
    val messages: List<PpeMessage> = emptyList(),
    
    /**
     * Instructions to execute after this turn (e.g., $echo, $set, $if, $while)
     */
    val instructions: List<PpeInstruction> = emptyList(),
    
    /**
     * Agent chaining (-> agent_name)
     */
    val chainTo: String? = null,
    
    /**
     * Chain parameters
     */
    val chainParams: Map<String, Any>? = null,
    
    /**
     * Control flow blocks (for $if, $while, etc.)
     */
    val controlFlowBlocks: List<PpeControlFlowBlock> = emptyList()
)
