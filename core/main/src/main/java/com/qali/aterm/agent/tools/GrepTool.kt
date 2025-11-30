package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import java.io.File
import java.util.regex.Pattern

data class GrepToolParams(
    val pattern: String,
    val dir_path: String? = null,
    val include: String? = null
)

data class GrepMatch(
    val filePath: String,
    val lineNumber: Int,
    val line: String
)

class GrepToolInvocation(
    toolParams: GrepToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<GrepToolParams, ToolResult> {
    
    override val params: GrepToolParams = toolParams
    
    override fun getDescription(): String {
        return "Searching for pattern: ${params.pattern}"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Search cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return try {
            val searchDir = if (params.dir_path != null) {
                File(workspaceRoot, params.dir_path)
            } else {
                File(workspaceRoot)
            }
            
            if (!searchDir.exists() || !searchDir.isDirectory) {
                return ToolResult(
                    llmContent = "Directory not found: ${params.dir_path ?: "root"}",
                    returnDisplay = "Error: Directory not found",
                    error = ToolError(
                        message = "Directory not found",
                        type = ToolErrorType.FILE_NOT_FOUND
                    )
                )
            }
            
            val pattern = try {
                Pattern.compile(params.pattern)
            } catch (e: Exception) {
                return ToolResult(
                    llmContent = "Invalid regex pattern: ${e.message}",
                    returnDisplay = "Error: Invalid pattern",
                    error = ToolError(
                        message = "Invalid regex pattern",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            val matches = mutableListOf<GrepMatch>()
            searchFiles(searchDir, pattern, params.include, matches, signal)
            
            val output = if (matches.isEmpty()) {
                "No matches found for pattern: ${params.pattern}"
            } else {
                buildString {
                    appendLine("Found ${matches.size} matches for pattern: ${params.pattern}")
                    appendLine()
                    matches.forEach { match ->
                        appendLine("${match.filePath}:${match.lineNumber}: ${match.line.trim()}")
                    }
                }
            }
            
            updateOutput?.invoke(output)
            
            ToolResult(
                llmContent = output,
                returnDisplay = "Found ${matches.size} matches"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error searching: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun searchFiles(
        dir: File,
        pattern: Pattern,
        include: String?,
        matches: MutableList<GrepMatch>,
        signal: CancellationSignal?
    ) {
        if (signal?.isAborted() == true) return
        
        dir.listFiles()?.forEach { file ->
            if (signal?.isAborted() == true) return
            
            if (file.isDirectory) {
                // Skip hidden directories
                if (!file.name.startsWith(".")) {
                    searchFiles(file, pattern, include, matches, signal)
                }
            } else if (file.isFile) {
                // Check include pattern if specified
                if (include != null && !matchesIncludePattern(file.name, include)) {
                    return@forEach
                }
                
                // Skip binary files and very large files
                if (file.length() > 10 * 1024 * 1024) { // 10MB limit
                    return@forEach
                }
                
                try {
                    val lines = file.readLines()
                    lines.forEachIndexed { index, line ->
                        if (pattern.matcher(line).find()) {
                            matches.add(
                                GrepMatch(
                                    filePath = file.absolutePath,
                                    lineNumber = index + 1,
                                    line = line
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }
        }
    }
    
    private fun matchesIncludePattern(filename: String, pattern: String): Boolean {
        // Simple glob pattern matching
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return filename.matches(regex.toRegex())
    }
}

class GrepTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<GrepToolParams, ToolResult>() {
    
    override val name = "grep"
    override val displayName = "Grep"
    override val description = "Searches for a pattern in files within a directory. Returns matching lines with file paths and line numbers."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "pattern" to PropertySchema(
                type = "string",
                description = "The regular expression pattern to search for."
            ),
            "dir_path" to PropertySchema(
                type = "string",
                description = "Optional directory path to search in. Defaults to workspace root."
            ),
            "include" to PropertySchema(
                type = "string",
                description = "Optional file pattern to include (e.g., '*.kt', '*.java')."
            )
        ),
        required = listOf("pattern")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: GrepToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<GrepToolParams, ToolResult> {
        return GrepToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): GrepToolParams {
        val pattern = params["pattern"] as? String
            ?: throw IllegalArgumentException("pattern is required")
        
        if (pattern.trim().isEmpty()) {
            throw IllegalArgumentException("pattern must be non-empty")
        }
        
        return GrepToolParams(
            pattern = pattern,
            dir_path = params["dir_path"] as? String,
            include = params["include"] as? String
        )
    }
}
