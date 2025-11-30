package com.qali.aterm.agent.ppe.models

/**
 * Represents a PPE (Programmable Prompt Engine) script
 */
data class PpeScript(
    /**
     * Front-matter parameters (default values)
     */
    val parameters: Map<String, Any> = emptyMap(),
    
    /**
     * Input schema definition
     */
    val input: List<String>? = null,
    
    /**
     * Output schema definition (JSON Schema)
     */
    val output: Map<String, Any>? = null,
    
    /**
     * Response format configuration
     */
    val responseFormat: Map<String, Any>? = null,
    
    /**
     * List of message turns in the script
     */
    val turns: List<PpeTurn> = emptyList(),
    
    /**
     * Metadata from front-matter
     */
    val metadata: Map<String, Any> = emptyMap(),
    
    /**
     * Source file path
     */
    val sourcePath: String? = null,
    
    /**
     * Script type (for inheritance)
     */
    val type: String? = null,
    
    /**
     * Import statements
     */
    val imports: List<String>? = null,
    
    /**
     * Custom function declarations (!fn)
     */
    val functions: List<String> = emptyList(),
    
    /**
     * Auto-run LLM if prompt available
     */
    val autoRunLLMIfPromptAvailable: Boolean = true,
    
    /**
     * Prompt configuration (for prompt.messages)
     */
    val promptConfig: Map<String, Any>? = null
)
