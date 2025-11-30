package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.regex.Pattern

data class GlobToolParams(
    val pattern: String,
    val dir_path: String? = null,
    val case_sensitive: Boolean? = null,
    val respect_git_ignore: Boolean? = null,
    val respect_gemini_ignore: Boolean? = null
)

data class FileEntry(
    val path: String,
    val modifiedTime: Long
)

class GlobToolInvocation(
    toolParams: GlobToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<GlobToolParams, ToolResult> {
    
    override val params: GlobToolParams = toolParams
    
    override fun getDescription(): String {
        var desc = "'${params.pattern}'"
        if (params.dir_path != null) {
            desc += " within ${params.dir_path}"
        }
        return desc
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
        
        val searchDir = if (params.dir_path != null) {
            File(workspaceRoot, params.dir_path)
        } else {
            File(workspaceRoot)
        }
        
        if (!searchDir.exists() || !searchDir.isDirectory) {
            return ToolResult(
                llmContent = "Search path does not exist or is not a directory: ${params.dir_path ?: workspaceRoot}",
                returnDisplay = "Error: Invalid search path",
                error = ToolError(
                    message = "Invalid search path",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        // Convert glob pattern to regex
        val regexPattern = convertGlobToRegex(params.pattern, params.case_sensitive ?: false)
        val pattern = if (params.case_sensitive == true) {
            Pattern.compile(regexPattern)
        } else {
            Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE)
        }
        
        val matchingFiles = mutableListOf<FileEntry>()
        searchFiles(searchDir, pattern, matchingFiles, signal)
        
        if (matchingFiles.isEmpty()) {
            return ToolResult(
                llmContent = "No files found matching pattern \"${params.pattern}\"",
                returnDisplay = "No files found"
            )
        }
        
        // Sort by modification time (newest first)
        matchingFiles.sortByDescending { it.modifiedTime }
        
        val fileList = matchingFiles.joinToString("\n") { it.path }
        val resultMessage = "Found ${matchingFiles.size} file(s) matching \"${params.pattern}\", sorted by modification time (newest first):\n$fileList"
        
        updateOutput?.invoke("Found ${matchingFiles.size} matching file(s)")
        
        return ToolResult(
            llmContent = resultMessage,
            returnDisplay = "Found ${matchingFiles.size} matching file(s)"
        )
    }
    
    private fun searchFiles(
        dir: File,
        pattern: Pattern,
        matchingFiles: MutableList<FileEntry>,
        signal: CancellationSignal?
    ) {
        if (signal?.isAborted() == true) return
        if (!dir.exists() || !dir.isDirectory) return
        
        dir.listFiles()?.forEach { file ->
            if (signal?.isAborted() == true) return
            
            val relativePath = file.relativeTo(File(workspaceRoot)).path
            val absolutePath = file.absolutePath
            
            if (file.isFile) {
                if (pattern.matcher(relativePath).find() || pattern.matcher(absolutePath).find()) {
                    val modifiedTime = try {
                        Files.getLastModifiedTime(file.toPath()).toMillis()
                    } catch (e: Exception) {
                        file.lastModified()
                    }
                    matchingFiles.add(FileEntry(absolutePath, modifiedTime))
                }
            } else if (file.isDirectory && !file.name.startsWith(".")) {
                searchFiles(file, pattern, matchingFiles, signal)
            }
        }
    }
    
    private fun convertGlobToRegex(glob: String, caseSensitive: Boolean): String {
        var regex = glob
            .replace("\\", "/") // Normalize separators
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        
        // Handle ** for recursive matching
        regex = regex.replace("**", ".*")
        
        return "^$regex$"
    }
}

class GlobTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<GlobToolParams, ToolResult>() {
    
    override val name = "glob"
    override val displayName = "FindFiles"
    override val description = "Efficiently finds files matching specific glob patterns (e.g., src/**/*.kt, **/*.md), returning absolute paths sorted by modification time (newest first). Ideal for quickly locating files based on their name or path structure."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "pattern" to PropertySchema(
                type = "string",
                description = "The glob pattern to match against (e.g., '**/*.py', 'docs/*.md')."
            ),
            "dir_path" to PropertySchema(
                type = "string",
                description = "Optional: The directory path to search within. If omitted, searches the root directory."
            ),
            "case_sensitive" to PropertySchema(
                type = "boolean",
                description = "Optional: Whether the search should be case-sensitive. Defaults to false."
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
        params: GlobToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<GlobToolParams, ToolResult> {
        return GlobToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): GlobToolParams {
        val pattern = params["pattern"] as? String
            ?: throw IllegalArgumentException("pattern is required")
        
        if (pattern.trim().isEmpty()) {
            throw IllegalArgumentException("pattern cannot be empty")
        }
        
        return GlobToolParams(
            pattern = pattern,
            dir_path = params["dir_path"] as? String,
            case_sensitive = params["case_sensitive"] as? Boolean,
            respect_git_ignore = params["respect_git_ignore"] as? Boolean,
            respect_gemini_ignore = params["respect_gemini_ignore"] as? Boolean
        )
    }
}
