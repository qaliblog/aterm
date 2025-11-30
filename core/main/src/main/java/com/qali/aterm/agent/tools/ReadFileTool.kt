package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import java.io.File

data class ReadFileToolParams(
    val file_path: String,
    val offset: Int? = null,
    val limit: Int? = null
)

class ReadFileToolInvocation(
    toolParams: ReadFileToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<ReadFileToolParams, ToolResult> {
    
    override val params: ReadFileToolParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.file_path).absolutePath
    
    override fun getDescription(): String {
        return "Reading file: ${params.file_path}"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return listOf(ToolLocation(resolvedPath, params.offset))
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "File read cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val file = File(resolvedPath)
        
        if (!file.exists()) {
            return ToolResult(
                llmContent = "File not found: ${params.file_path}",
                returnDisplay = "Error: File not found",
                error = ToolError(
                    message = "File not found: ${params.file_path}",
                    type = ToolErrorType.FILE_NOT_FOUND
                )
            )
        }
        
        if (!file.canRead()) {
            return ToolResult(
                llmContent = "Permission denied: ${params.file_path}",
                returnDisplay = "Error: Permission denied",
                error = ToolError(
                    message = "Permission denied",
                    type = ToolErrorType.PERMISSION_DENIED
                )
            )
        }
        
        return try {
            val content = if (file.isFile) {
                readFileContent(file, params.offset, params.limit)
            } else {
                "Error: Path is a directory, not a file"
            }
            
            ToolResult(
                llmContent = content,
                returnDisplay = "Read ${file.name}"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error reading file: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun readFileContent(file: File, offset: Int?, limit: Int?): String {
        val lines = file.readLines()
        val totalLines = lines.size
        
        return when {
            offset != null && limit != null -> {
                val start = offset.coerceAtLeast(0)
                val end = (offset + limit).coerceAtMost(totalLines)
                val selectedLines = lines.subList(start, end)
                
                if (end < totalLines) {
                    """
                    IMPORTANT: The file content has been truncated.
                    Status: Showing lines ${start + 1}-${end} of $totalLines total lines.
                    Action: To read more, use offset: $end with limit parameter.
                    
                    --- FILE CONTENT (truncated) ---
                    ${selectedLines.joinToString("\n")}
                    """.trimIndent()
                } else {
                    selectedLines.joinToString("\n")
                }
            }
            offset != null -> {
                val start = offset.coerceAtLeast(0)
                val end = totalLines
                lines.subList(start, end).joinToString("\n")
            }
            else -> {
                // Read entire file, but limit to reasonable size
                val maxLines = 1000
                if (lines.size > maxLines) {
                    val truncated = lines.take(maxLines).joinToString("\n")
                    """
                    IMPORTANT: File truncated (showing first $maxLines of $totalLines lines).
                    Use offset and limit parameters to read specific sections.
                    
                    --- FILE CONTENT (truncated) ---
                    $truncated
                    """.trimIndent()
                } else {
                    lines.joinToString("\n")
                }
            }
        }
    }
}

class ReadFileTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<ReadFileToolParams, ToolResult>() {
    
    override val name = "read_file"
    override val displayName = "ReadFile"
    override val description = "Reads and returns the content of a specified file. If the file is large, the content will be truncated. The tool's response will clearly indicate if truncation has occurred and will provide details on how to read more of the file using the 'offset' and 'limit' parameters."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to read."
            ),
            "offset" to PropertySchema(
                type = "number",
                description = "Optional: For text files, the 0-based line number to start reading from. Requires 'limit' to be set."
            ),
            "limit" to PropertySchema(
                type = "number",
                description = "Optional: For text files, maximum number of lines to read. Use with 'offset' to paginate through large files."
            )
        ),
        required = listOf("file_path")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: ReadFileToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ReadFileToolParams, ToolResult> {
        return ReadFileToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ReadFileToolParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        
        val offset = (params["offset"] as? Number)?.toInt()
        val limit = (params["limit"] as? Number)?.toInt()
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        if (offset != null && offset < 0) {
            throw IllegalArgumentException("offset must be non-negative")
        }
        
        if (limit != null && limit <= 0) {
            throw IllegalArgumentException("limit must be positive")
        }
        
        return ReadFileToolParams(
            file_path = filePath,
            offset = offset,
            limit = limit
        )
    }
}
