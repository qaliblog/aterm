package com.qali.aterm.agent.ppe

import java.util.regex.Pattern

/**
 * Simple template engine for PPE scripts
 * Supports {{variable}} syntax (Jinja2-like)
 */
object PpeTemplateEngine {
    private val templatePattern = Pattern.compile("\\{\\{([^}]+)\\}\\}")
    
    /**
     * Render template with variables
     */
    fun render(template: String, variables: Map<String, Any>): String {
        val matcher = templatePattern.matcher(template)
        val result = StringBuffer()
        
        while (matcher.find()) {
            val varName = matcher.group(1).trim()
            val value = getVariableValue(varName, variables)
            matcher.appendReplacement(result, escapeReplacement(value.toString()))
        }
        matcher.appendTail(result)
        
        return result.toString()
    }
    
    /**
     * Get variable value, supporting dot notation (e.g., "user.name")
     */
    private fun getVariableValue(varName: String, variables: Map<String, Any>): Any {
        // Handle filters (e.g., {{name | upper}})
        val parts = varName.split("|").map { it.trim() }
        val actualVarName = parts[0]
        
        // Handle dot notation
        val value = getNestedValue(actualVarName, variables)
        
        // Apply filters if any
        if (parts.size > 1) {
            return applyFilters(value, parts.drop(1))
        }
        
        return value ?: ""
    }
    
    /**
     * Get nested value using dot notation
     */
    private fun getNestedValue(path: String, variables: Map<String, Any>): Any? {
        val parts = path.split(".")
        var current: Any? = variables
        
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                else -> null
            }
            if (current == null) break
        }
        
        return current
    }
    
    /**
     * Apply filters to value
     */
    private fun applyFilters(value: Any?, filters: List<String>): Any {
        var result = value
        for (filter in filters) {
            result = when (filter.lowercase()) {
                "upper" -> result?.toString()?.uppercase() ?: ""
                "lower" -> result?.toString()?.lowercase() ?: ""
                "trim" -> result?.toString()?.trim() ?: ""
                else -> result
            }
        }
        return result ?: ""
    }
    
    /**
     * Escape replacement string for use in Matcher.appendReplacement
     */
    private fun escapeReplacement(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("$", "\\$")
    }
    
    /**
     * Check if template contains variables
     */
    fun hasVariables(template: String): Boolean {
        return templatePattern.matcher(template).find()
    }
    
    /**
     * Extract variable names from template
     */
    fun extractVariables(template: String): List<String> {
        val variables = mutableListOf<String>()
        val matcher = templatePattern.matcher(template)
        while (matcher.find()) {
            val varName = matcher.group(1).trim().split("|")[0].trim()
            variables.add(varName)
        }
        return variables.distinct()
    }
}
