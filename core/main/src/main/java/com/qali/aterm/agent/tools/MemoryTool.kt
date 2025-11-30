package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.rk.libcommons.alpineHomeDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.utils.createPatch
import java.io.File

data class MemoryToolParams(
    val fact: String,
    val modified_by_user: Boolean = false,
    val modified_content: String? = null
)

private const val DEFAULT_CONTEXT_FILENAME = "GEMINI.md"
private const val MEMORY_SECTION_HEADER = "## Gemini Added Memories"

class MemoryToolInvocation(
    toolParams: MemoryToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<MemoryToolParams, ToolResult> {
    
    override val params: MemoryToolParams = toolParams
    
    private val memoryFilePath: String
        get() = File(alpineHomeDir(), DEFAULT_CONTEXT_FILENAME).absolutePath
    
    override fun getDescription(): String {
        return "Saving memory to $memoryFilePath"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return listOf(ToolLocation(memoryFilePath))
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Memory save cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return try {
            val memoryFile = File(memoryFilePath)
            val currentContent = if (memoryFile.exists()) {
                memoryFile.readText()
            } else {
                ""
            }
            
            val newContent = computeNewContent(currentContent, params.fact)
            
            // Create parent directory if needed
            memoryFile.parentFile?.mkdirs()
            memoryFile.writeText(newContent)
            
            updateOutput?.invoke("Memory saved")
            
            ToolResult(
                llmContent = "Successfully saved memory: ${params.fact}",
                returnDisplay = "Memory saved to $DEFAULT_CONTEXT_FILENAME"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error saving memory: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun computeNewContent(currentContent: String, fact: String): String {
        var processedText = fact.trim()
        processedText = processedText.replace(Regex("^(-+\\s*)+"), "").trim()
        val newMemoryItem = "- $processedText"
        
        val headerIndex = currentContent.indexOf(MEMORY_SECTION_HEADER)
        
        return if (headerIndex == -1) {
            // Header not found, append header and then the entry
            val separator = ensureNewlineSeparation(currentContent)
            currentContent + "$separator$MEMORY_SECTION_HEADER\n$newMemoryItem\n"
        } else {
            // Header found, insert the new memory entry
            val startOfSectionContent = headerIndex + MEMORY_SECTION_HEADER.length
            var endOfSectionIndex = currentContent.indexOf("\n## ", startOfSectionContent)
            if (endOfSectionIndex == -1) {
                endOfSectionIndex = currentContent.length
            }
            
            val beforeSectionMarker = currentContent.substring(0, startOfSectionContent).trimEnd()
            var sectionContent = currentContent.substring(startOfSectionContent, endOfSectionIndex).trimEnd()
            val afterSectionMarker = currentContent.substring(endOfSectionIndex)
            
            sectionContent += "\n$newMemoryItem"
            "${beforeSectionMarker}\n${sectionContent.trimStart()}\n$afterSectionMarker".trimEnd() + "\n"
        }
    }
    
    private fun ensureNewlineSeparation(currentContent: String): String {
        if (currentContent.isEmpty()) return ""
        if (currentContent.endsWith("\n\n") || currentContent.endsWith("\r\n\r\n")) return ""
        if (currentContent.endsWith("\n") || currentContent.endsWith("\r\n")) return "\n"
        return "\n\n"
    }
}

class MemoryTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<MemoryToolParams, ToolResult>() {
    
    override val name = "memory"
    override val displayName = "Memory"
    override val description = """
        Saves a specific piece of information or fact to your long-term memory. Use this when the user explicitly asks you to remember something, or when they state a clear, concise fact that seems important to retain for future interactions.
        
        Use this tool:
        - When the user explicitly asks you to remember something (e.g., "Remember that I like pineapple on pizza", "Please save this: my cat's name is Whiskers").
        - When the user states a clear, concise fact about themselves, their preferences, or their environment that seems important for you to retain for future interactions.
        
        Do NOT use this tool:
        - To remember conversational context that is only relevant for the current session.
        - To save long, complex, or rambling pieces of text. The fact should be relatively short and to the point.
    """.trimIndent()
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "fact" to PropertySchema(
                type = "string",
                description = "The specific fact or piece of information to remember. Should be a clear, self-contained statement."
            )
        ),
        required = listOf("fact")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: MemoryToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<MemoryToolParams, ToolResult> {
        return MemoryToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): MemoryToolParams {
        val fact = params["fact"] as? String
            ?: throw IllegalArgumentException("fact is required")
        
        if (fact.trim().isEmpty()) {
            throw IllegalArgumentException("fact must be non-empty")
        }
        
        return MemoryToolParams(
            fact = fact,
            modified_by_user = (params["modified_by_user"] as? Boolean) ?: false,
            modified_content = params["modified_content"] as? String
        )
    }
}
