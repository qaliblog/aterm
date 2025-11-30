package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.ppe.models.*
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/**
 * Parser for PPE (Programmable Prompt Engine) script files
 * Parses .ai.yaml files into PpeScript objects
 */
object PpeScriptParser {
    private val yamlLoader = Load(LoadSettings.builder().build())
    
    /**
     * Parse a PPE script file
     */
    fun parse(file: File): PpeScript {
        val content = file.readText()
        return parse(content, file.absolutePath)
    }
    
    /**
     * Parse a PPE script from string content
     */
    fun parse(content: String, sourcePath: String? = null): PpeScript {
        // Split content by --- or *** to separate front-matter from turns
        val parts = content.split(Regex("(^---|^\\*\\*\\*)"), RegexOption.MULTILINE)
        
        if (parts.isEmpty()) {
            return PpeScript(sourcePath = sourcePath)
        }
        
        // First part is front-matter (before first ---)
        val frontMatterText = parts[0].trim()
        val frontMatter = if (frontMatterText.isNotEmpty()) {
            try {
                yamlLoader.loadFromString(frontMatterText) as? Map<*, *> ?: emptyMap()
            } catch (e: Exception) {
                android.util.Log.w("PpeScriptParser", "Failed to parse front-matter: ${e.message}")
                emptyMap()
            }
        } else {
            emptyMap()
        }
        
        // Extract parameters, input, output, response_format from front-matter
        val parameters = (frontMatter["parameters"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) } ?: emptyMap()
        
        val input = (frontMatter["input"] as? List<*>)?.mapNotNull { it?.toString() }
        
        val output = (frontMatter["output"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) }
        
        val responseFormat = (frontMatter["response_format"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) }
        
        // Extract other metadata
        val metadata = frontMatter.filterKeys { 
            it !in listOf("parameters", "input", "output", "response_format")
        }.mapKeys { it.key.toString() }.mapValues { convertValue(it.value) }
        
        // Parse turns (everything after first ---)
        val turns = mutableListOf<PpeTurn>()
        for (i in 1 until parts.size) {
            val turnText = parts[i].trim()
            if (turnText.isNotEmpty()) {
                val turn = parseTurn(turnText)
                if (turn != null) {
                    turns.add(turn)
                }
            }
        }
        
        return PpeScript(
            parameters = parameters,
            input = input,
            output = output,
            responseFormat = responseFormat,
            turns = turns,
            metadata = metadata,
            sourcePath = sourcePath
        )
    }
    
    /**
     * Parse a single turn (section between --- markers)
     */
    private fun parseTurn(turnText: String): PpeTurn? {
        val lines = turnText.lines()
        val messages = mutableListOf<PpeMessage>()
        val instructions = mutableListOf<PpeInstruction>()
        var chainTo: String? = null
        var chainParams: Map<String, Any>? = null
        
        var currentRole: String? = null
        var currentContent = StringBuilder()
        var inMultiLine = false
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (inMultiLine && currentRole != null) {
                    currentContent.append("\n")
                }
                continue
            }
            
            // Check for agent chaining (-> agent_name or -> agent_name(params))
            if (trimmed.startsWith("->")) {
                // Save current message if any
                if (currentRole != null && currentContent.isNotEmpty()) {
                    messages.add(createMessage(currentRole, currentContent.toString().trim()))
                    currentRole = null
                    currentContent.clear()
                }
                
                val chainPart = trimmed.substring(2).trim()
                val chainMatch = Regex("""^(\w+)(?:\((.*)\))?$""").find(chainPart)
                if (chainMatch != null) {
                    chainTo = chainMatch.groupValues[1]
                    val paramsStr = chainMatch.groupValues[2]
                    if (paramsStr.isNotEmpty()) {
                        chainParams = parseChainParams(paramsStr)
                    }
                }
                continue
            }
            
            // Check for instructions ($instruction or $instruction: value)
            if (trimmed.startsWith("$")) {
                // Save current message if any
                if (currentRole != null && currentContent.isNotEmpty()) {
                    messages.add(createMessage(currentRole, currentContent.toString().trim()))
                    currentRole = null
                    currentContent.clear()
                }
                
                val instruction = parseInstruction(trimmed)
                if (instruction != null) {
                    instructions.add(instruction)
                }
                continue
            }
            
            // Check for role: content pattern (system:, user:, assistant:)
            val roleMatch = Regex("""^(\w+):\s*(.*)$""").find(trimmed)
            if (roleMatch != null) {
                val role = roleMatch.groupValues[1]
                val content = roleMatch.groupValues[2]
                
                // Save previous message if any
                if (currentRole != null && currentContent.isNotEmpty()) {
                    messages.add(createMessage(currentRole, currentContent.toString().trim()))
                }
                
                // Check for multi-line indicator (| or |-)
                if (content == "|" || content == "|-") {
                    currentRole = role
                    inMultiLine = true
                    currentContent.clear()
                } else {
                    currentRole = role
                    inMultiLine = false
                    currentContent.clear()
                    if (content.isNotEmpty()) {
                        currentContent.append(content)
                    }
                }
            } else {
                // Continuation of current message
                if (currentRole != null) {
                    if (currentContent.isNotEmpty()) {
                        currentContent.append("\n")
                    }
                    currentContent.append(trimmed)
                } else {
                    // Default to user message if no role specified
                    currentRole = "user"
                    currentContent.append(trimmed)
                }
            }
        }
        
        // Save last message if any
        if (currentRole != null && currentContent.isNotEmpty()) {
            messages.add(createMessage(currentRole, currentContent.toString().trim()))
        }
        
        if (messages.isEmpty() && instructions.isEmpty() && chainTo == null) {
            return null
        }
        
        return PpeTurn(
            messages = messages,
            instructions = instructions,
            chainTo = chainTo,
            chainParams = chainParams
        )
    }
    
    /**
     * Create a PpeMessage from role and content
     */
    private fun createMessage(role: String, content: String): PpeMessage {
        // Check for AI placeholders: [[AI]] or [[AI:model='...']]
        val aiPlaceholderRegex = Regex("""\[\[(\w+)(?::(.*))?\]\]""")
        val aiMatch = aiPlaceholderRegex.find(content)
        
        val hasAiPlaceholder = aiMatch != null
        val aiPlaceholderVar = aiMatch?.groupValues?.get(1)
        val aiPlaceholderParams = aiMatch?.groupValues?.getOrNull(2)?.let { parseAiParams(it) }
        
        return PpeMessage(
            role = role,
            content = content,
            hasAiPlaceholder = hasAiPlaceholder,
            aiPlaceholderVar = aiPlaceholderVar,
            aiPlaceholderParams = aiPlaceholderParams
        )
    }
    
    /**
     * Parse AI placeholder parameters (e.g., model='...', temperature=0.7)
     */
    private fun parseAiParams(paramsStr: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val paramRegex = Regex("""(\w+)=(?:'([^']*)'|"([^"]*)"|([^\s,]+))""")
        paramRegex.findAll(paramsStr).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].takeIf { it.isNotEmpty() }
                ?: match.groupValues[3].takeIf { it.isNotEmpty() }
                ?: match.groupValues[4]
            params[key] = value
        }
        return params
    }
    
    /**
     * Parse chain parameters (e.g., key1=value1, key2=value2)
     */
    private fun parseChainParams(paramsStr: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val paramRegex = Regex("""(\w+)=(?:'([^']*)'|"([^"]*)"|([^\s,]+))""")
        paramRegex.findAll(paramsStr).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].takeIf { it.isNotEmpty() }
                ?: match.groupValues[3].takeIf { it.isNotEmpty() }
                ?: match.groupValues[4]
            params[key] = value
        }
        return params
    }
    
    /**
     * Parse an instruction (e.g., $echo: "text" or $set: key=value)
     */
    private fun parseInstruction(instructionStr: String): PpeInstruction? {
        val trimmed = instructionStr.substring(1).trim() // Remove $
        
        // Check for : separator (e.g., $echo: "text")
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex > 0) {
            val name = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1).trim()
            
            // Remove quotes if present
            val unquotedValue = value.removeSurrounding("\"").removeSurrounding("'")
            
            return PpeInstruction(
                name = name,
                args = mapOf("value" to unquotedValue),
                rawContent = value
            )
        }
        
        // Check for function call syntax (e.g., $fn(param1=value1))
        val funcMatch = Regex("""^(\w+)(?:\((.*)\))?$""").find(trimmed)
        if (funcMatch != null) {
            val name = funcMatch.groupValues[1]
            val paramsStr = funcMatch.groupValues.getOrNull(2) ?: ""
            val args = if (paramsStr.isNotEmpty()) {
                parseChainParams(paramsStr)
            } else {
                emptyMap()
            }
            return PpeInstruction(name = name, args = args)
        }
        
        // Simple instruction name
        return PpeInstruction(name = trimmed, args = emptyMap())
    }
    
    /**
     * Convert YAML value to Kotlin Any
     */
    private fun convertValue(value: Any?): Any {
        return when (value) {
            null -> ""
            is Map<*, *> -> value.mapKeys { it.key.toString() }.mapValues { convertValue(it.value) }
            is List<*> -> value.mapNotNull { convertValue(it) }
            else -> value
        }
    }
}
