package com.qali.aterm.gemini.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.core.FunctionDeclaration
import com.qali.aterm.gemini.core.FunctionParameters
import com.qali.aterm.gemini.core.PropertySchema
import com.qali.aterm.gemini.utils.FileCoherenceManager
import java.io.File
import java.util.regex.Pattern

data class SedToolParams(
    val file_path: String,
    val pattern: String,
    val replacement: String,
    val global: Boolean = false, // Replace all occurrences (g flag)
    val case_insensitive: Boolean = false, // Case insensitive matching (i flag)
    val multiline: Boolean = false // Multiline mode (m flag)
)

class SedToolInvocation(
    toolParams: SedToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<SedToolParams, ToolResult> {
    
    override val params: SedToolParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.file_path).absolutePath
    
    override fun getDescription(): String {
        return "Applying sed pattern to ${params.file_path}: ${params.pattern} -> ${params.replacement}"
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
                llmContent = "Sed operation cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val file = File(resolvedPath)
        
        if (!file.exists() || !file.isFile) {
            return ToolResult(
                llmContent = "File not found: ${params.file_path}",
                returnDisplay = "Error: File not found",
                error = ToolError(
                    message = "File not found",
                    type = ToolErrorType.FILE_NOT_FOUND
                )
            )
        }
        
        return try {
            val originalContent = file.readText()
            
            // Build regex pattern with flags
            var flags = 0
            if (params.case_insensitive) {
                flags = flags or Pattern.CASE_INSENSITIVE
            }
            if (params.multiline) {
                flags = flags or Pattern.MULTILINE
            }
            
            val pattern = try {
                Pattern.compile(params.pattern, flags)
            } catch (e: Exception) {
                return ToolResult(
                    llmContent = "Invalid regex pattern: ${e.message}",
                    returnDisplay = "Error: Invalid pattern",
                    error = ToolError(
                        message = "Invalid regex pattern: ${e.message}",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            val matcher = pattern.matcher(originalContent)
            
            // Count matches before replacement
            var matchCount = 0
            while (matcher.find()) {
                matchCount++
                if (!params.global) {
                    break // Only replace first occurrence if not global
                }
            }
            
            if (matchCount == 0) {
                return ToolResult(
                    llmContent = "No matches found for pattern: ${params.pattern}",
                    returnDisplay = "No matches found",
                    error = ToolError(
                        message = "Pattern not found",
                        type = ToolErrorType.EDIT_NO_OCCURRENCE_FOUND
                    )
                )
            }
            
            // Perform replacement
            val newContent = if (params.global) {
                matcher.reset()
                matcher.replaceAll(params.replacement)
            } else {
                matcher.reset()
                matcher.replaceFirst(params.replacement)
            }
            
            if (originalContent == newContent) {
                return ToolResult(
                    llmContent = "No changes made. Replacement resulted in identical content.",
                    returnDisplay = "No changes",
                    error = ToolError(
                        message = "No changes made",
                        type = ToolErrorType.EDIT_NO_CHANGE
                    )
                )
            }
            
            // Write file with coherence guarantees
            val writeSuccess = FileCoherenceManager.writeFileWithCoherence(file, newContent)
            if (!writeSuccess) {
                return ToolResult(
                    llmContent = "File was modified by another operation. Please retry the sed operation.",
                    returnDisplay = "Error: Write conflict",
                    error = ToolError(
                        message = "File was modified concurrently",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
            
            val replacementCount = if (params.global) matchCount else 1
            val message = "Successfully replaced $replacementCount occurrence(s) in ${params.file_path}"
            
            updateOutput?.invoke(message)
            
            ToolResult(
                llmContent = message,
                returnDisplay = "Replaced $replacementCount occurrence(s)"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error applying sed: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
}

class SedTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<SedToolParams, ToolResult>() {
    
    override val name = "sed"
    override val displayName = "Sed"
    override val description = "Performs regex-based search and replace operations on a file, similar to sed command. Supports global replacement, case-insensitive matching, and multiline mode. Use this for pattern-based replacements that are more flexible than literal string replacement."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to modify."
            ),
            "pattern" to PropertySchema(
                type = "string",
                description = "The regular expression pattern to search for. Use Java regex syntax."
            ),
            "replacement" to PropertySchema(
                type = "string",
                description = "The replacement string. Can include capture groups using $1, $2, etc."
            ),
            "global" to PropertySchema(
                type = "boolean",
                description = "If true, replace all occurrences. If false, replace only the first occurrence. Defaults to false."
            ),
            "case_insensitive" to PropertySchema(
                type = "boolean",
                description = "If true, perform case-insensitive matching. Defaults to false."
            ),
            "multiline" to PropertySchema(
                type = "boolean",
                description = "If true, enable multiline mode (^ and $ match line boundaries). Defaults to false."
            )
        ),
        required = listOf("file_path", "pattern", "replacement")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: SedToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<SedToolParams, ToolResult> {
        return SedToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): SedToolParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        val pattern = params["pattern"] as? String
            ?: throw IllegalArgumentException("pattern is required")
        val replacement = params["replacement"] as? String
            ?: throw IllegalArgumentException("replacement is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        if (pattern.trim().isEmpty()) {
            throw IllegalArgumentException("pattern must be non-empty")
        }
        
        return SedToolParams(
            file_path = filePath,
            pattern = pattern,
            replacement = replacement,
            global = (params["global"] as? Boolean) ?: false,
            case_insensitive = (params["case_insensitive"] as? Boolean) ?: false,
            multiline = (params["multiline"] as? Boolean) ?: false
        )
    }
}
