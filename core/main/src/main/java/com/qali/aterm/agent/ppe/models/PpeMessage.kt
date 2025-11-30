package com.qali.aterm.agent.ppe.models

/**
 * Represents a message in a PPE script
 */
data class PpeMessage(
    /**
     * Message role: system, user, assistant
     */
    val role: String,
    
    /**
     * Message content (may contain templates and AI placeholders)
     */
    val content: String,
    
    /**
     * Whether this message contains AI placeholders that need execution
     */
    val hasAiPlaceholder: Boolean = false,
    
    /**
     * AI placeholder variable name (if any)
     */
    val aiPlaceholderVar: String? = null,
    
    /**
     * AI placeholder parameters (e.g., model, temperature)
     */
    val aiPlaceholderParams: Map<String, Any>? = null,
    
    /**
     * Whether content has immediate formatting (# prefix)
     */
    val immediateFormat: Boolean = false,
    
    /**
     * Script replacements in content ([[@script_name(params)]])
     */
    val scriptReplacements: List<PpeScriptReplacement> = emptyList(),
    
    /**
     * Instruction replacements in content ([[@$instruction(params)]])
     */
    val instructionReplacements: List<PpeInstructionReplacement> = emptyList(),
    
    /**
     * Regular expression replacements (/RegExp/:VAR)
     */
    val regexReplacements: List<PpeRegexReplacement> = emptyList(),
    
    /**
     * Constrained AI options ([[VAR:|option1|option2]])
     */
    val constrainedOptions: List<String>? = null,
    
    /**
     * Constrained selection count (for multiple selection)
     */
    val constrainedCount: Int? = null,
    
    /**
     * Constrained random selection
     */
    val constrainedRandom: Boolean = false
)

/**
 * Script replacement in message content
 */
data class PpeScriptReplacement(
    val scriptName: String,
    val params: Map<String, Any> = emptyMap(),
    val placeholder: String // The full placeholder text to replace
)

/**
 * Instruction replacement in message content
 */
data class PpeInstructionReplacement(
    val instructionName: String,
    val params: Map<String, Any> = emptyMap(),
    val placeholder: String // The full placeholder text to replace
)

/**
 * Regular expression replacement
 */
data class PpeRegexReplacement(
    val pattern: String,
    val options: String = "",
    val variable: String,
    val groupIndex: Int? = null,
    val groupName: String? = null,
    val placeholder: String // The full placeholder text to replace
)

