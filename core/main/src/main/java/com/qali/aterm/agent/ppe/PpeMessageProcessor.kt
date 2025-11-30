package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.ppe.models.*
import java.util.regex.Pattern

/**
 * Processes PPE messages with all replacements and formatting
 * Handles script replacements, instruction replacements, regex replacements, etc.
 */
object PpeMessageProcessor {
    
    /**
     * Process a message with all replacements
     * Returns the fully processed content
     */
    suspend fun processMessage(
        message: PpeMessage,
        variables: Map<String, Any>,
        executionEngine: PpeExecutionEngine,
        scriptSourcePath: String?
    ): String {
        var content = message.content
        
        // Handle immediate formatting (# prefix) - format templates immediately
        if (message.immediateFormat) {
            content = PpeTemplateEngine.render(content, variables)
        }
        
        // Process script replacements: [[@script_name(params)]]
        for (replacement in message.scriptReplacements) {
            val replacementResult = executeScriptReplacement(
                replacement,
                variables,
                executionEngine,
                scriptSourcePath
            )
            content = content.replace(replacement.placeholder, replacementResult)
        }
        
        // Process instruction replacements: [[@$instruction(params)]]
        for (replacement in message.instructionReplacements) {
            val replacementResult = executeInstructionReplacement(
                replacement,
                variables,
                executionEngine
            )
            content = content.replace(replacement.placeholder, replacementResult)
        }
        
        // Process regex replacements: /pattern/:VAR
        for (replacement in message.regexReplacements) {
            val replacementResult = executeRegexReplacement(
                replacement,
                variables
            )
            content = content.replace(replacement.placeholder, replacementResult)
        }
        
        // If not immediate format, render templates now (after all replacements)
        if (!message.immediateFormat) {
            content = PpeTemplateEngine.render(content, variables)
        }
        
        return content
    }
    
    /**
     * Execute script replacement [[@script_name(params)]]
     */
    private suspend fun executeScriptReplacement(
        replacement: PpeScriptReplacement,
        variables: Map<String, Any>,
        executionEngine: PpeExecutionEngine,
        scriptSourcePath: String?
    ): String {
        return try {
            // Load and execute the script
            val script = loadScriptForReplacement(replacement.scriptName, scriptSourcePath)
            if (script != null) {
                // Merge params with variables
                val scriptParams = mutableMapOf<String, Any>().apply {
                    putAll(variables)
                    putAll(replacement.params)
                }
                
                // Execute script and get result
                var result = ""
                executionEngine.executeScript(
                    script = script,
                    inputParams = scriptParams,
                    onChunk = { chunk -> result += chunk },
                    onToolCall = { },
                    onToolResult = { _, _ -> }
                ).collect { execResult ->
                    if (execResult.success) {
                        result = execResult.finalResult
                    }
                }
                
                result
            } else {
                "Script not found: ${replacement.scriptName}"
            }
        } catch (e: Exception) {
            android.util.Log.e("PpeMessageProcessor", "Script replacement failed", e)
            "Error: ${e.message}"
        }
    }
    
    /**
     * Execute instruction replacement [[@$instruction(params)]]
     */
    private suspend fun executeInstructionReplacement(
        replacement: PpeInstructionReplacement,
        variables: Map<String, Any>,
        executionEngine: PpeExecutionEngine
    ): String {
        return try {
            // Execute instruction and get result
            val instruction = PpeInstruction(
                name = replacement.instructionName,
                args = replacement.params
            )
            var result = ""
            executionEngine.executeInstructionForReplacement(instruction, variables) { output ->
                result = output
            }
            result
        } catch (e: Exception) {
            android.util.Log.e("PpeMessageProcessor", "Instruction replacement failed", e)
            "Error: ${e.message}"
        }
    }
    
    /**
     * Execute regex replacement /pattern/:VAR
     */
    private fun executeRegexReplacement(
        replacement: PpeRegexReplacement,
        variables: Map<String, Any>
    ): String {
        return try {
            val variableValue = getNestedValue(replacement.variable, variables)?.toString() ?: ""
            if (variableValue.isEmpty()) {
                return variableValue
            }
            
            // Build regex pattern with options
            val flags = when {
                replacement.options.contains("i", ignoreCase = true) -> Pattern.CASE_INSENSITIVE
                else -> 0
            }
            val pattern = Pattern.compile(replacement.pattern, flags)
            val matcher = pattern.matcher(variableValue)
            
            if (matcher.find()) {
                when {
                    replacement.groupName != null -> {
                        try {
                            matcher.group(replacement.groupName) ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                    }
                    replacement.groupIndex != null -> {
                        if (replacement.groupIndex <= matcher.groupCount()) {
                            matcher.group(replacement.groupIndex) ?: ""
                        } else {
                            ""
                        }
                    }
                    else -> {
                        // Default: use group 1 if available, else full match
                        if (matcher.groupCount() > 0) {
                            matcher.group(1) ?: matcher.group(0) ?: ""
                        } else {
                            matcher.group(0) ?: ""
                        }
                    }
                }
            } else {
                variableValue // No match, return original
            }
        } catch (e: Exception) {
            android.util.Log.e("PpeMessageProcessor", "Regex replacement failed", e)
            ""
        }
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
     * Load script for replacement
     */
    private fun loadScriptForReplacement(scriptName: String, currentScriptPath: String?): PpeScript? {
        return try {
            val scriptPath = when {
                currentScriptPath != null -> {
                    val currentDir = java.io.File(currentScriptPath).parentFile
                    java.io.File(currentDir, "$scriptName.ai.yaml")
                }
                else -> java.io.File("$scriptName.ai.yaml")
            }
            
            if (scriptPath.exists()) {
                PpeScriptLoader.loadScript(scriptPath)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
