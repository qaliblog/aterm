package com.qali.aterm.gemini.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.core.FunctionDeclaration
import com.qali.aterm.gemini.core.FunctionParameters
import com.qali.aterm.gemini.core.PropertySchema
import com.qali.aterm.gemini.utils.safeLiteralReplace
import com.qali.aterm.gemini.utils.createPatch
import com.qali.aterm.gemini.utils.getDiffStat
import com.qali.aterm.gemini.utils.FileCoherenceManager
import com.qali.aterm.gemini.utils.AutoErrorDetection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EditToolParams(
    val file_path: String,
    val old_string: String,
    val new_string: String,
    val expected_replacements: Int? = null,
    val modified_by_user: Boolean = false,
    val ai_proposed_content: String? = null
)

/**
 * Apply replacement to content
 */
fun applyReplacement(
    currentContent: String?,
    oldString: String,
    newString: String,
    isNewFile: Boolean
): String {
    if (isNewFile) {
        return newString
    }
    if (currentContent == null) {
        return if (oldString.isEmpty()) newString else ""
    }
    if (oldString.isEmpty() && !isNewFile) {
        return currentContent
    }
    return safeLiteralReplace(currentContent, oldString, newString)
}

class EditToolInvocation(
    toolParams: EditToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<EditToolParams, ToolResult> {
    
    override val params: EditToolParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.file_path).absolutePath
    
    override fun getDescription(): String {
        val relativePath = params.file_path
        if (params.old_string.isEmpty()) {
            return "Create $relativePath"
        }
        
        val oldSnippet = params.old_string.split("\n")[0].take(30) + 
            if (params.old_string.length > 30) "..." else ""
        val newSnippet = params.new_string.split("\n")[0].take(30) + 
            if (params.new_string.length > 30) "..." else ""
        
        if (params.old_string == params.new_string) {
            return "No file changes to $relativePath"
        }
        return "$relativePath: $oldSnippet => $newSnippet"
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
        val expectedReplacements = params.expected_replacements ?: 1
        var currentContent: String? = null
        val fileExists = file.exists()
        val isNewFile = params.old_string.isEmpty() && !fileExists
        
        // Read current content if file exists
        if (fileExists) {
            try {
                currentContent = file.readText()
                // Normalize line endings
                currentContent = currentContent.replace("\r\n", "\n")
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
        }
        
        // Validate edit
        if (params.old_string.isEmpty() && !fileExists) {
            // Creating new file - OK
        } else if (!fileExists) {
            return ToolResult(
                llmContent = "File not found. Cannot apply edit. Use an empty old_string to create a new file.",
                returnDisplay = "Error: File not found",
                error = ToolError(
                    message = "File not found: $resolvedPath",
                    type = ToolErrorType.FILE_NOT_FOUND
                )
            )
        } else if (params.old_string.isEmpty() && fileExists) {
            return ToolResult(
                llmContent = "Failed to edit. Attempted to create a file that already exists.",
                returnDisplay = "Error: File already exists",
                error = ToolError(
                    message = "File already exists: $resolvedPath",
                    type = ToolErrorType.ATTEMPT_TO_CREATE_EXISTING_FILE
                )
            )
        }
        
        // Count occurrences
        val occurrences = if (currentContent != null) {
            currentContent.split(params.old_string).size - 1
        } else {
            0
        }
        
        if (occurrences == 0 && !isNewFile) {
            return ToolResult(
                llmContent = "Failed to edit, could not find the string to replace. The exact text in old_string was not found. Ensure you're not escaping content incorrectly and check whitespace, indentation, and context. Use read_file tool to verify.",
                returnDisplay = "Error: String not found",
                error = ToolError(
                    message = "0 occurrences found for old_string",
                    type = ToolErrorType.EDIT_NO_OCCURRENCE_FOUND
                )
            )
        }
        
        if (occurrences != expectedReplacements && !isNewFile) {
            return ToolResult(
                llmContent = "Failed to edit, expected $expectedReplacements occurrence(s) but found $occurrences.",
                returnDisplay = "Error: Occurrence mismatch",
                error = ToolError(
                    message = "Expected $expectedReplacements but found $occurrences",
                    type = ToolErrorType.EDIT_EXPECTED_OCCURRENCE_MISMATCH
                )
            )
        }
        
        // Apply replacement
        val newContent = applyReplacement(
            currentContent,
            params.old_string,
            params.new_string,
            isNewFile
        )
        
        if (currentContent == newContent && !isNewFile) {
            // Check if the fix was already applied (maybe by a previous attempt)
            // Or if old_string doesn't match but new_string is already in the file
            val newStringInFile = currentContent?.contains(params.new_string) == true
            val oldStringInFile = currentContent?.contains(params.old_string) == true
            
            if (newStringInFile && !oldStringInFile) {
                // The new content is already in the file, fix was likely already applied
                return ToolResult(
                    llmContent = "Fix already applied. The new content is already present in the file.",
                    returnDisplay = "Already applied",
                    error = null // Not an error, just informational
                )
            }
            
            return ToolResult(
                llmContent = "No changes to apply. The new content is identical to the current content. If you expected changes, verify that old_string matches the file content exactly.",
                returnDisplay = "No changes",
                error = ToolError(
                    message = "No changes to apply",
                    type = ToolErrorType.EDIT_NO_CHANGE
                )
            )
        }
        
        // Write file with coherence guarantees
        return try {
            // Create parent directories
            file.parentFile?.mkdirs()
            
            // Use FileCoherenceManager for atomic write in streaming mode
            val writeSuccess = FileCoherenceManager.writeFileWithCoherence(file, newContent)
            if (!writeSuccess) {
                return ToolResult(
                    llmContent = "File was modified by another operation. Please retry the edit.",
                    returnDisplay = "Error: Write conflict",
                    error = ToolError(
                        message = "File was modified concurrently",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
            
            val fileName = file.name
            val originallyProposedContent = params.ai_proposed_content ?: newContent
            val diffStat = getDiffStat(
                fileName,
                currentContent ?: "",
                originallyProposedContent,
                newContent
            )
            
            val fileDiff = createPatch(
                fileName,
                currentContent ?: "",
                newContent
            )
            
            val displayResult = mapOf(
                "fileDiff" to fileDiff,
                "fileName" to fileName,
                "originalContent" to (currentContent ?: ""),
                "newContent" to newContent,
                "diffStat" to diffStat
            )
            
            val successMessage = if (isNewFile) {
                "Created new file: $resolvedPath with provided content."
            } else {
                "Successfully modified file: $resolvedPath ($occurrences replacements)."
            }
            
            val finalMessage = if (params.modified_by_user) {
                "$successMessage User modified the new_string content."
            } else {
                successMessage
            }
            
            // Automatically detect errors after file modification
            val errorTasks = try {
                withContext(Dispatchers.IO) {
                    AutoErrorDetection.detectAndCreateFixTasks(
                        filePath = params.file_path,
                        workspaceRoot = workspaceRoot
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("EditTool", "Error in auto error detection: ${e.message}")
                emptyList()
            }
            
            val messageWithErrors = if (errorTasks.isNotEmpty()) {
                finalMessage + AutoErrorDetection.formatErrorDetectionMessage(errorTasks)
            } else {
                finalMessage
            }
            
            ToolResult(
                llmContent = messageWithErrors,
                returnDisplay = displayResult.toString()
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error executing edit: ${e.message}",
                returnDisplay = "Error writing file: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.FILE_WRITE_FAILURE
                )
            )
        }
    }
}

class EditTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<EditToolParams, ToolResult>() {
    
    override val name = "edit"
    override val displayName = "Edit"
    override val description = "Replaces text within a file. By default, replaces a single occurrence, but can replace multiple occurrences when expected_replacements is specified. This tool requires providing significant context around the change to ensure precise targeting. Always use the read_file tool to examine the file's current content before attempting a text replacement."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to modify."
            ),
            "old_string" to PropertySchema(
                type = "string",
                description = "The exact literal text to replace, preferably unescaped. For single replacements (default), include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace and indentation precisely."
            ),
            "new_string" to PropertySchema(
                type = "string",
                description = "The exact literal text to replace old_string with, preferably unescaped. Provide the EXACT text. Ensure the resulting code is correct and idiomatic."
            ),
            "expected_replacements" to PropertySchema(
                type = "number",
                description = "Number of replacements expected. Defaults to 1 if not specified. Use when you want to replace multiple occurrences."
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
        params: EditToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<EditToolParams, ToolResult> {
        return EditToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): EditToolParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        val oldString = params["old_string"] as? String
            ?: throw IllegalArgumentException("old_string is required")
        val newString = params["new_string"] as? String
            ?: throw IllegalArgumentException("new_string is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        return EditToolParams(
            file_path = filePath,
            old_string = oldString,
            new_string = newString,
            expected_replacements = (params["expected_replacements"] as? Number)?.toInt(),
            modified_by_user = (params["modified_by_user"] as? Boolean) ?: false,
            ai_proposed_content = params["ai_proposed_content"] as? String
        )
    }
}
