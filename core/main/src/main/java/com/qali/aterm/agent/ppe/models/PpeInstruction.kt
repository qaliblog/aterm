package com.qali.aterm.agent.ppe.models

/**
 * Represents an instruction in a PPE script (e.g., $echo, $set, $if)
 */
data class PpeInstruction(
    /**
     * Instruction name (e.g., "echo", "set", "if")
     */
    val name: String,
    
    /**
     * Instruction arguments
     */
    val args: Map<String, Any> = emptyMap(),
    
    /**
     * Raw instruction content (for complex instructions)
     */
    val rawContent: String? = null
)
