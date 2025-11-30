package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.utils.safeLiteralReplace
import java.io.File
import java.util.regex.Pattern

data class SmartEditToolParams(
    val file_path: String,
    val old_string: String,
    val new_string: String,
    val expected_replacements: Int? = null
)

data class ReplacementResult(
    val newContent: String,
    val occurrences: Int,
    val finalOldString: String,
    val finalNewString: String
)

class SmartEditToolInvocation(
    toolParams: SmartEditToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<SmartEditToolParams, ToolResult> {
    
    override val params: SmartEditToolParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.file_path).absolutePath
    
    override fun getDescription(): String {
        return "Smart edit: ${params.file_path}"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return listOf(ToolLocation(resolvedPath))
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Edit cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val file = File(resolvedPath)
        if (!file.exists()) {
            return ToolResult(
                llmContent = "File not found: ${params.file_path}",
                returnDisplay = "Error: File not found",
                error = ToolError(
                    message = "File not found",
                    type = ToolErrorType.FILE_NOT_FOUND
                )
            )
        }
        
        val currentContent = try {
            file.readText().replace("\r\n", "\n")
        } catch (e: Exception) {
            return ToolResult(
                llmContent = "Error reading file: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.READ_CONTENT_FAILURE
                )
            )
        }
        
        // Try different replacement strategies
        val replacement = calculateReplacement(currentContent, params.old_string, params.new_string)
        
        if (replacement.occurrences == 0) {
            return ToolResult(
                llmContent = "Could not find the string to replace. The exact text in old_string was not found. Ensure you're not escaping content incorrectly and check whitespace, indentation, and context.",
                returnDisplay = "Error: String not found",
                error = ToolError(
                    message = "0 occurrences found",
                    type = ToolErrorType.EDIT_NO_OCCURRENCE_FOUND
                )
            )
        }
        
        val expectedReplacements = params.expected_replacements ?: 1
        if (replacement.occurrences != expectedReplacements) {
            return ToolResult(
                llmContent = "Expected $expectedReplacements occurrence(s) but found ${replacement.occurrences}.",
                returnDisplay = "Error: Occurrence mismatch",
                error = ToolError(
                    message = "Expected $expectedReplacements but found ${replacement.occurrences}",
                    type = ToolErrorType.EDIT_EXPECTED_OCCURRENCE_MISMATCH
                )
            )
        }
        
        // Write the new content
        return try {
            file.writeText(replacement.newContent)
            updateOutput?.invoke("Smart edit completed")
            
            ToolResult(
                llmContent = "Successfully modified file: $resolvedPath (${replacement.occurrences} replacements).",
                returnDisplay = "Smart edit completed"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error writing file: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.FILE_WRITE_FAILURE
                )
            )
        }
    }
    
    private fun calculateReplacement(
        currentContent: String,
        oldString: String,
        newString: String
    ): ReplacementResult {
        val normalizedSearch = oldString.replace("\r\n", "\n")
        val normalizedReplace = newString.replace("\r\n", "\n")
        
        if (normalizedSearch.isEmpty()) {
            return ReplacementResult(currentContent, 0, normalizedSearch, normalizedReplace)
        }
        
        // Strategy 1: Exact replacement
        val exactOccurrences = currentContent.split(normalizedSearch).size - 1
        if (exactOccurrences > 0) {
            val modifiedCode = safeLiteralReplace(currentContent, normalizedSearch, normalizedReplace)
            val restored = restoreTrailingNewline(currentContent, modifiedCode)
            return ReplacementResult(restored, exactOccurrences, normalizedSearch, normalizedReplace)
        }
        
        // Strategy 2: Flexible replacement (ignore leading whitespace)
        val flexibleResult = calculateFlexibleReplacement(currentContent, normalizedSearch, normalizedReplace)
        if (flexibleResult != null) {
            return flexibleResult
        }
        
        // Strategy 3: Regex-based replacement
        val regexResult = calculateRegexReplacement(currentContent, normalizedSearch, normalizedReplace)
        if (regexResult != null) {
            return regexResult
        }
        
        return ReplacementResult(currentContent, 0, normalizedSearch, normalizedReplace)
    }
    
    private fun calculateFlexibleReplacement(
        currentContent: String,
        normalizedSearch: String,
        normalizedReplace: String
    ): ReplacementResult? {
        val sourceLines = currentContent.split("\n").toMutableList()
        val searchLinesStripped = normalizedSearch.split("\n").map { it.trim() }
        val replaceLines = normalizedReplace.split("\n")
        
        var flexibleOccurrences = 0
        var i = 0
        
        while (i <= sourceLines.size - searchLinesStripped.size) {
            val window = sourceLines.subList(i, i + searchLinesStripped.size)
            val windowStripped = window.map { it.trim() }
            val isMatch = windowStripped.zip(searchLinesStripped).all { (a, b) -> a == b }
            
            if (isMatch) {
                flexibleOccurrences++
                val firstLineInMatch = window[0]
                val indentation = firstLineInMatch.takeWhile { it.isWhitespace() }
                val newBlockWithIndent = replaceLines.map { "$indentation$it" }
                
                sourceLines.subList(i, i + searchLinesStripped.size).clear()
                sourceLines.addAll(i, newBlockWithIndent)
                i += replaceLines.size
            } else {
                i++
            }
        }
        
        if (flexibleOccurrences > 0) {
            val modifiedCode = sourceLines.joinToString("\n")
            val restored = restoreTrailingNewline(currentContent, modifiedCode)
            return ReplacementResult(restored, flexibleOccurrences, normalizedSearch, normalizedReplace)
        }
        
        return null
    }
    
    private fun calculateRegexReplacement(
        currentContent: String,
        normalizedSearch: String,
        normalizedReplace: String
    ): ReplacementResult? {
        val delimiters = listOf("(", ")", ":", "[", "]", "{", "}", ">", "<", "=")
        
        var processedString = normalizedSearch
        for (delim in delimiters) {
            processedString = processedString.replace(delim, " $delim ")
        }
        
        val tokens = processedString.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        
        val escapedTokens = tokens.map { Pattern.quote(it) }
        val pattern = escapedTokens.joinToString("\\s*")
        val finalPattern = "^(\\s*)$pattern"
        
        val regex = Pattern.compile(finalPattern, Pattern.MULTILINE)
        val matcher = regex.matcher(currentContent)
        
        if (!matcher.find()) return null
        
        val indentation = matcher.group(1) ?: ""
        val newLines = normalizedReplace.split("\n")
        val newBlockWithIndent = newLines.joinToString("\n") { "$indentation$it" }
        
        val modifiedCode = matcher.replaceFirst(newBlockWithIndent)
        val restored = restoreTrailingNewline(currentContent, modifiedCode)
        
        return ReplacementResult(restored, 1, normalizedSearch, normalizedReplace)
    }
    
    private fun restoreTrailingNewline(originalContent: String, modifiedContent: String): String {
        val hadTrailingNewline = originalContent.endsWith("\n")
        return when {
            hadTrailingNewline && !modifiedContent.endsWith("\n") -> modifiedContent + "\n"
            !hadTrailingNewline && modifiedContent.endsWith("\n") -> modifiedContent.dropLast(1)
            else -> modifiedContent
        }
    }
}

class SmartEditTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<SmartEditToolParams, ToolResult>() {
    
    override val name = "smart_edit"
    override val displayName = "SmartEdit"
    override val description = "Intelligently edits files using multiple strategies: exact match, flexible whitespace matching, and regex-based matching. More forgiving than the standard edit tool."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to modify."
            ),
            "old_string" to PropertySchema(
                type = "string",
                description = "The text to replace (can be approximate, tool will try multiple matching strategies)."
            ),
            "new_string" to PropertySchema(
                type = "string",
                description = "The text to replace old_string with."
            ),
            "expected_replacements" to PropertySchema(
                type = "number",
                description = "Number of replacements expected. Defaults to 1."
            )
        ),
        required = listOf("file_path", "old_string", "new_string")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: SmartEditToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<SmartEditToolParams, ToolResult> {
        return SmartEditToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): SmartEditToolParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        val oldString = params["old_string"] as? String
            ?: throw IllegalArgumentException("old_string is required")
        val newString = params["new_string"] as? String
            ?: throw IllegalArgumentException("new_string is required")
        
        return SmartEditToolParams(
            file_path = filePath,
            old_string = oldString,
            new_string = newString,
            expected_replacements = (params["expected_replacements"] as? Number)?.toInt()
        )
    }
}
