package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.ppe.models.*
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.util.regex.Pattern

/**
 * Enhanced parser for PPE (Programmable Prompt Engine) script files
 * Full implementation supporting all CLI folder features
 */
object PpeScriptParserEnhanced {
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
        
        // Extract all front-matter fields
        val parameters = (frontMatter["parameters"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) } ?: emptyMap()
        
        val input = (frontMatter["input"] as? List<*>)?.mapNotNull { it?.toString() }
        
        val output = (frontMatter["output"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) }
        
        val responseFormat = (frontMatter["response_format"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) }
        
        val type = frontMatter["type"]?.toString()
        
        val imports = when (val imp = frontMatter["import"]) {
            is String -> listOf(imp)
            is List<*> -> imp.mapNotNull { it?.toString() }
            else -> null
        }
        
        val functions = extractFunctions(content)
        
        val autoRunLLM = (frontMatter["autoRunLLMIfPromptAvailable"] as? Boolean) ?: true
        
        val promptConfig = (frontMatter["prompt"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.mapValues { convertValue(it.value) }
        
        // Extract other metadata
        val metadata = frontMatter.filterKeys { 
            it !in listOf("parameters", "input", "output", "response_format", "type", "import", "autoRunLLMIfPromptAvailable", "prompt")
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
            sourcePath = sourcePath,
            type = type,
            imports = imports,
            functions = functions,
            autoRunLLMIfPromptAvailable = autoRunLLM,
            promptConfig = promptConfig
        )
    }
    
    /**
     * Extract function declarations (!fn)
     */
    private fun extractFunctions(content: String): List<String> {
        val functions = mutableListOf<String>()
        val fnRegex = Regex("""!fn\s*\|\s*-\s*\n((?:[^\n]+\n?)+)""", RegexOption.MULTILINE)
        fnRegex.findAll(content).forEach { match ->
            functions.add(match.groupValues[1].trim())
        }
        return functions
    }
    
    /**
     * Parse a single turn (section between --- markers)
     */
    private fun parseTurn(turnText: String): PpeTurn? {
        val lines = turnText.lines()
        val messages = mutableListOf<PpeMessage>()
        val instructions = mutableListOf<PpeInstruction>()
        val controlFlowBlocks = mutableListOf<PpeControlFlowBlock>()
        var chainTo: String? = null
        var chainParams: Map<String, Any>? = null
        
        var currentRole: String? = null
        var currentContent = StringBuilder()
        var inMultiLine = false
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            
            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (inMultiLine && currentRole != null) {
                    currentContent.append("\n")
                }
                i++
                continue
            }
            
            // Check for control flow blocks ($if, $while, etc.)
            if (trimmed.startsWith("$if") || trimmed.startsWith("$while") || trimmed.startsWith("$for") || trimmed.startsWith("$match")) {
                // Save current message if any
                if (currentRole != null && currentContent.isNotEmpty()) {
                    messages.add(createMessage(currentRole, currentContent.toString().trim()))
                    currentRole = null
                    currentContent.clear()
                }
                
                val block = parseControlFlowBlock(lines, i)
                if (block != null) {
                    controlFlowBlocks.add(block.first)
                    i = block.second // Skip processed lines
                    continue
                }
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
                i++
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
                i++
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
            i++
        }
        
        // Save last message if any
        if (currentRole != null && currentContent.isNotEmpty()) {
            messages.add(createMessage(currentRole, currentContent.toString().trim()))
        }
        
        if (messages.isEmpty() && instructions.isEmpty() && chainTo == null && controlFlowBlocks.isEmpty()) {
            return null
        }
        
        return PpeTurn(
            messages = messages,
            instructions = instructions,
            chainTo = chainTo,
            chainParams = chainParams,
            controlFlowBlocks = controlFlowBlocks
        )
    }
    
    /**
     * Parse control flow block ($if, $while, etc.)
     */
    private fun parseControlFlowBlock(lines: List<String>, startIndex: Int): Pair<PpeControlFlowBlock, Int>? {
        if (startIndex >= lines.size) return null
        
        val firstLine = lines[startIndex].trim()
        
        when {
            firstLine.startsWith("$if") -> {
                return parseIfBlock(lines, startIndex)
            }
            firstLine.startsWith("$while") -> {
                return parseWhileBlock(lines, startIndex)
            }
            firstLine.startsWith("$for") -> {
                return parseForBlock(lines, startIndex)
            }
            firstLine.startsWith("$match") -> {
                return parseMatchBlock(lines, startIndex)
            }
            firstLine.startsWith("$pipe") -> {
                return parsePipeBlock(lines, startIndex)
            }
            else -> return null
        }
    }
    
    /**
     * Parse $if block
     */
    private fun parseIfBlock(lines: List<String>, startIndex: Int): Pair<PpeControlFlowBlock, Int> {
        val firstLine = lines[startIndex].trim()
        val condition = firstLine.substring(3).trim().removeSurrounding("\"").removeSurrounding("'")
        
        var i = startIndex + 1
        val thenBlock = mutableListOf<PpeInstruction>()
        val elseBlock = mutableListOf<PpeInstruction>()
        var inThen = false
        var inElse = false
        var indentLevel = 0
        
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }.length
            
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            
            if (trimmed == "then:" || trimmed.startsWith("then:")) {
                inThen = true
                inElse = false
                indentLevel = currentIndent
                i++
                continue
            }
            
            if (trimmed == "else:" || trimmed.startsWith("else:")) {
                inElse = true
                inThen = false
                indentLevel = currentIndent
                i++
                continue
            }
            
            // Check if we're still in the block (same or greater indent)
            if (currentIndent > indentLevel || (currentIndent == indentLevel && (inThen || inElse))) {
                if (trimmed.startsWith("$")) {
                    val instruction = parseInstruction(trimmed)
                    if (instruction != null) {
                        if (inThen) {
                            thenBlock.add(instruction)
                        } else if (inElse) {
                            elseBlock.add(instruction)
                        }
                    }
                } else if (trimmed.startsWith("-")) {
                    // YAML list item
                    val itemContent = trimmed.substring(1).trim()
                    if (itemContent.startsWith("$")) {
                        val instruction = parseInstruction(itemContent)
                        if (instruction != null) {
                            if (inThen) {
                                thenBlock.add(instruction)
                            } else if (inElse) {
                                elseBlock.add(instruction)
                            }
                        }
                    }
                }
                i++
            } else {
                // Block ended
                break
            }
        }
        
        return Pair(
            PpeControlFlowBlock(
                type = "if",
                condition = condition,
                thenBlock = thenBlock,
                elseBlock = elseBlock.takeIf { it.isNotEmpty() }
            ),
            i - 1
        )
    }
    
    /**
     * Parse $while block
     */
    private fun parseWhileBlock(lines: List<String>, startIndex: Int): Pair<PpeControlFlowBlock, Int> {
        val firstLine = lines[startIndex].trim()
        val condition = firstLine.substring(6).trim().removeSurrounding("\"").removeSurrounding("'")
        
        var i = startIndex + 1
        val doBlock = mutableListOf<PpeInstruction>()
        var inDo = false
        var indentLevel = 0
        
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }.length
            
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            
            if (trimmed == "do:" || trimmed.startsWith("do:")) {
                inDo = true
                indentLevel = currentIndent
                i++
                continue
            }
            
            if (currentIndent > indentLevel || (currentIndent == indentLevel && inDo)) {
                if (trimmed.startsWith("$")) {
                    val instruction = parseInstruction(trimmed)
                    if (instruction != null && inDo) {
                        doBlock.add(instruction)
                    }
                } else if (trimmed.startsWith("-")) {
                    val itemContent = trimmed.substring(1).trim()
                    if (itemContent.startsWith("$")) {
                        val instruction = parseInstruction(itemContent)
                        if (instruction != null && inDo) {
                            doBlock.add(instruction)
                        }
                    }
                }
                i++
            } else {
                break
            }
        }
        
        return Pair(
            PpeControlFlowBlock(
                type = "while",
                condition = condition,
                doBlock = doBlock
            ),
            i - 1
        )
    }
    
    /**
     * Parse $for block (simplified - similar to while)
     */
    private fun parseForBlock(lines: List<String>, startIndex: Int): Pair<PpeControlFlowBlock, Int> {
        // Similar to while, but with iteration variable
        return parseWhileBlock(lines, startIndex).let { (block, index) ->
            Pair(block.copy(type = "for"), index)
        }
    }
    
    /**
     * Parse $match block
     */
    private fun parseMatchBlock(lines: List<String>, startIndex: Int): Pair<PpeControlFlowBlock, Int> {
        val firstLine = lines[startIndex].trim()
        val expression = firstLine.substring(6).trim().removeSurrounding("\"").removeSurrounding("'")
        
        var i = startIndex + 1
        val cases = mutableMapOf<String, List<PpeInstruction>>()
        var currentCase: String? = null
        var currentInstructions = mutableListOf<PpeInstruction>()
        var indentLevel = 0
        
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }.length
            
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            
            // Check for case pattern
            if (trimmed.contains(":") && !trimmed.startsWith("$")) {
                // Save previous case
                currentCase?.let { cases[it] = currentInstructions }
                currentCase = trimmed.substringBefore(":").trim()
                currentInstructions = mutableListOf()
                indentLevel = currentIndent
                i++
                continue
            }
            
            if (currentIndent > indentLevel && currentCase != null) {
                if (trimmed.startsWith("$")) {
                    val instruction = parseInstruction(trimmed)
                    if (instruction != null) {
                        currentInstructions.add(instruction)
                    }
                } else if (trimmed.startsWith("-")) {
                    val itemContent = trimmed.substring(1).trim()
                    if (itemContent.startsWith("$")) {
                        val instruction = parseInstruction(itemContent)
                        if (instruction != null) {
                            currentInstructions.add(instruction)
                        }
                    }
                }
                i++
            } else {
                break
            }
        }
        
        // Save last case
        currentCase?.let { cases[it] = currentInstructions }
        
        return Pair(
            PpeControlFlowBlock(
                type = "match",
                condition = expression,
                cases = cases.takeIf { it.isNotEmpty() }
            ),
            i - 1
        )
    }
    
    /**
     * Parse $pipe block
     */
    private fun parsePipeBlock(lines: List<String>, startIndex: Int): Pair<PpeControlFlowBlock, Int> {
        val firstLine = lines[startIndex].trim()
        val pipeChain = firstLine.substring(5).trim().split("->").map { it.trim() }
        
        return Pair(
            PpeControlFlowBlock(
                type = "pipe",
                pipeChain = pipeChain
            ),
            startIndex
        )
    }
    
    /**
     * Create a PpeMessage from role and content with full feature support
     */
    private fun createMessage(role: String, content: String): PpeMessage {
        var processedContent = content
        val immediateFormat = content.startsWith("#")
        if (immediateFormat) {
            processedContent = content.substring(1)
        }
        
        // Extract all replacements and placeholders
        val scriptReplacements = mutableListOf<PpeScriptReplacement>()
        val instructionReplacements = mutableListOf<PpeInstructionReplacement>()
        val regexReplacements = mutableListOf<PpeRegexReplacement>()
        
        // Parse script replacements: [[@script_name(params)]]
        val scriptReplacementRegex = Regex("""\[\[@(\w+)(?:\((.*?)\))?\]\]""")
        scriptReplacementRegex.findAll(processedContent).forEach { match ->
            val scriptName = match.groupValues[1]
            val paramsStr = match.groupValues.getOrNull(2) ?: ""
            val params = if (paramsStr.isNotEmpty()) {
                parseChainParams(paramsStr)
            } else {
                emptyMap()
            }
            scriptReplacements.add(
                PpeScriptReplacement(
                    scriptName = scriptName,
                    params = params,
                    placeholder = match.value
                )
            )
        }
        
        // Parse instruction replacements: [[@$instruction(params)]]
        val instructionReplacementRegex = Regex("""\[\[@\$(\w+)(?:\((.*?)\))?\]\]""")
        instructionReplacementRegex.findAll(processedContent).forEach { match ->
            val instructionName = match.groupValues[1]
            val paramsStr = match.groupValues.getOrNull(2) ?: ""
            val params = if (paramsStr.isNotEmpty()) {
                parseChainParams(paramsStr)
            } else {
                emptyMap()
            }
            instructionReplacements.add(
                PpeInstructionReplacement(
                    instructionName = instructionName,
                    params = params,
                    placeholder = match.value
                )
            )
        }
        
        // Parse regex replacements: /RegExp/[opts]:VAR[:index]
        val regexReplacementRegex = Regex("""/([^/]+)/([^:]*):(\w+)(?::(\d+|[a-zA-Z_][a-zA-Z0-9_]*))?""")
        regexReplacementRegex.findAll(processedContent).forEach { match ->
            val pattern = match.groupValues[1]
            val options = match.groupValues[2]
            val variable = match.groupValues[3]
            val indexOrName = match.groupValues.getOrNull(4)
            val groupIndex = indexOrName?.toIntOrNull()
            val groupName = if (groupIndex == null && indexOrName != null && indexOrName.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*"""))) {
                indexOrName
            } else {
                null
            }
            regexReplacements.add(
                PpeRegexReplacement(
                    pattern = pattern,
                    options = options,
                    variable = variable,
                    groupIndex = groupIndex,
                    groupName = groupName,
                    placeholder = match.value
                )
            )
        }
        
        // Parse AI placeholders with full support
        val aiPlaceholderRegex = Regex("""\[\[(\w+)(?::(.*?))?\]\]""")
        val aiMatch = aiPlaceholderRegex.find(processedContent)
        
        val hasAiPlaceholder = aiMatch != null
        val aiPlaceholderVar = aiMatch?.groupValues?.get(1)
        
        // Parse AI parameters (support comma-separated: temperature=0.7,top_p=0.8)
        val aiPlaceholderParams = aiMatch?.groupValues?.getOrNull(2)?.let { parseAiParams(it) }
        
        // Parse constrained AI options: [[VAR:|option1|option2]]
        val constrainedRegex = Regex("""\[\[(\w+):\s*\|([^\]]+)\]\]""")
        val constrainedMatch = constrainedRegex.find(processedContent)
        val constrainedOptions = constrainedMatch?.groupValues?.get(2)?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val constrainedCount = constrainedMatch?.let {
            // Check for count: [[VAR:|opt1|opt2:2]]
            val countMatch = Regex("""\[\[(\w+):\s*\|[^\]]+:(\d+)\]\]""").find(processedContent)
            countMatch?.groupValues?.get(2)?.toIntOrNull()
        }
        val constrainedRandom = constrainedMatch?.let {
            // Check for random: [[VAR:|opt1|opt2:random]] or [[VAR:|opt1|opt2:type=random]]
            processedContent.contains(":random") || processedContent.contains("type=random")
        } ?: false
        
        return PpeMessage(
            role = role,
            content = processedContent,
            hasAiPlaceholder = hasAiPlaceholder,
            aiPlaceholderVar = aiPlaceholderVar,
            aiPlaceholderParams = aiPlaceholderParams,
            immediateFormat = immediateFormat,
            scriptReplacements = scriptReplacements,
            instructionReplacements = instructionReplacements,
            regexReplacements = regexReplacements,
            constrainedOptions = constrainedOptions,
            constrainedCount = constrainedCount,
            constrainedRandom = constrainedRandom
        )
    }
    
    /**
     * Parse AI placeholder parameters (e.g., model='...', temperature=0.7,top_p=0.8)
     */
    private fun parseAiParams(paramsStr: String): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        // Support comma-separated parameters
        val paramRegex = Regex("""(\w+)=(?:'([^']*)'|"([^"]*)"|([^\s,|]+))""")
        paramRegex.findAll(paramsStr).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].takeIf { it.isNotEmpty() }
                ?: match.groupValues[3].takeIf { it.isNotEmpty() }
                ?: match.groupValues[4]
            // Try to parse as number if possible
            params[key] = value.toDoubleOrNull() ?: value.toIntOrNull() ?: value
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
     * Parse an instruction (public for use in execution engine)
     */
    fun parseInstruction(instructionStr: String): PpeInstruction? {
        val trimmed = instructionStr.substring(1).trim() // Remove $
        
        // Check for : separator (e.g., $echo: "text")
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex > 0) {
            val name = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1).trim()
            
            // Check for ?= prefix (template variable reference)
            val isTemplateRef = value.startsWith("?=")
            val actualValue = if (isTemplateRef) {
                value.substring(2).trim()
            } else {
                value.removeSurrounding("\"").removeSurrounding("'")
            }
            
            return PpeInstruction(
                name = name,
                args = mapOf("value" to actualValue, "isTemplateRef" to isTemplateRef),
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
