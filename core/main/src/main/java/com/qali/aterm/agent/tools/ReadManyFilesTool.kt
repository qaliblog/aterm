package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

data class ReadManyFilesParams(
    val include: List<String>,
    val exclude: List<String>? = null,
    val recursive: Boolean? = null,
    val useDefaultExcludes: Boolean? = true,
    val max_files: Int? = null, // AI can specify how many files to read (replaces hardcoded 4)
    val priority_patterns: List<String>? = null // Patterns to prioritize when selecting files
)

class ReadManyFilesToolInvocation(
    toolParams: ReadManyFilesParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<ReadManyFilesParams, ToolResult> {
    
    override val params: ReadManyFilesParams = toolParams
    
    override fun getDescription(): String {
        val includeDesc = params.include.joinToString("`, `", prefix = "`", postfix = "`")
        val excludeDesc = if (params.exclude.isNullOrEmpty()) {
            "none specified"
        } else {
            params.exclude.take(2).joinToString("`, `", prefix = "`", postfix = "`") +
                if (params.exclude.size > 2) "..." else ""
        }
        return "Will attempt to read and concatenate files using patterns: $includeDesc. Excluding: $excludeDesc."
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList() // Multiple files, can't specify single location
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Read cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val filesToRead = mutableListOf<File>()
        val skippedFiles = mutableListOf<Pair<String, String>>()
        
        // Collect files matching include patterns
        val includePatterns = params.include.map { pattern ->
            convertGlobToRegex(pattern)
        }
        
        val excludePatterns = (params.exclude ?: emptyList()).map { pattern ->
            convertGlobToRegex(pattern)
        }
        
        val rootDir = File(workspaceRoot)
        collectFiles(rootDir, includePatterns, excludePatterns, filesToRead, skippedFiles, signal)
        
        if (filesToRead.isEmpty()) {
            return ToolResult(
                llmContent = "No files found matching the include patterns.",
                returnDisplay = "No files found"
            )
        }
        
        // Apply max_files limit and priority if specified
        val filesToProcess = if (params.max_files != null && filesToRead.size > params.max_files) {
            // Prioritize files matching priority_patterns
            val priorityPatterns = params.priority_patterns?.map { pattern ->
                convertGlobToRegex(pattern)
            } ?: emptyList()
            
            if (priorityPatterns.isNotEmpty()) {
                val priorityFiles = mutableListOf<File>()
                val otherFiles = mutableListOf<File>()
                
                filesToRead.forEach { file ->
                    val relativePath = file.relativeTo(File(workspaceRoot)).path
                    val matchesPriority = priorityPatterns.any { pattern ->
                        pattern.matches(relativePath)
                    }
                    
                    if (matchesPriority) {
                        priorityFiles.add(file)
                    } else {
                        otherFiles.add(file)
                    }
                }
                
                // Take priority files first, then fill remaining slots with other files
                (priorityFiles.take(params.max_files) + otherFiles.take(params.max_files - priorityFiles.size)).take(params.max_files)
            } else {
                filesToRead.take(params.max_files)
            }
        } else {
            filesToRead
        }
        
        // Read and concatenate files
        val contentParts = mutableListOf<String>()
        var successCount = 0
        var errorCount = 0
        
        for (file in filesToProcess) {
            if (signal?.isAborted() == true) break
            
            try {
                val content = file.readText()
                val relativePath = file.relativeTo(rootDir).path
                contentParts.add("--- $relativePath ---")
                contentParts.add(content)
                contentParts.add("--- End of content ---")
                successCount++
            } catch (e: Exception) {
                skippedFiles.add(file.absolutePath to "Error reading: ${e.message}")
                errorCount++
            }
        }
        
        val result = contentParts.joinToString("\n\n")
        val totalFound = filesToRead.size
        val totalRead = filesToProcess.size
        val summary = if (params.max_files != null && totalFound > totalRead) {
            "Read $successCount of $totalRead selected file(s) (from $totalFound total matches)." + 
            if (errorCount > 0) " $errorCount file(s) had errors." else ""
        } else {
            "Read $successCount file(s)." + 
            if (errorCount > 0) " $errorCount file(s) had errors." else ""
        }
        
        updateOutput?.invoke(summary)
        
        return ToolResult(
            llmContent = result,
            returnDisplay = summary
        )
    }
    
    private fun collectFiles(
        dir: File,
        includePatterns: List<Regex>,
        excludePatterns: List<Regex>,
        filesToRead: MutableList<File>,
        skippedFiles: MutableList<Pair<String, String>>,
        signal: CancellationSignal?
    ) {
        if (signal?.isAborted() == true) return
        if (!dir.exists() || !dir.isDirectory) return
        
        dir.listFiles()?.forEach { file ->
            if (signal?.isAborted() == true) return
            
            val relativePath = file.relativeTo(File(workspaceRoot)).path
            
            // Check exclude patterns
            val isExcluded = excludePatterns.any { pattern ->
                pattern.matches(relativePath)
            }
            
            if (isExcluded) {
                skippedFiles.add(relativePath to "Excluded by pattern")
                return@forEach
            }
            
            if (file.isFile) {
                // Check include patterns
                val matches = includePatterns.any { pattern ->
                    pattern.matches(relativePath)
                }
                
                if (matches) {
                    filesToRead.add(file)
                }
            } else if (file.isDirectory) {
                // Recursively search subdirectories
                collectFiles(file, includePatterns, excludePatterns, filesToRead, skippedFiles, signal)
            }
        }
    }
    
    private fun convertGlobToRegex(glob: String): Regex {
        // Convert glob pattern to regex
        var regex = glob
            .replace("\\", "/") // Normalize separators
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        
        // Handle ** for recursive matching
        regex = regex.replace("**", ".*")
        
        return Regex("^$regex$")
    }
}

class ReadManyFilesTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<ReadManyFilesParams, ToolResult>() {
    
    override val name = "read_many_files"
    override val displayName = "ReadManyFiles"
    override val description = "Reads and concatenates multiple files matching glob patterns. Useful for reading multiple related files at once. You can specify max_files to limit how many files to read (replaces the old hardcoded limit of 4). Use priority_patterns to prioritize certain files when max_files is set."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "include" to PropertySchema(
                type = "array",
                description = "Glob patterns for files to include. Example: [\"*.kt\", \"src/**/*.java\"]",
                items = PropertySchema(
                    type = "string",
                    description = "A glob pattern"
                )
            ),
            "exclude" to PropertySchema(
                type = "array",
                description = "Optional. Glob patterns for files/directories to exclude.",
                items = PropertySchema(
                    type = "string",
                    description = "A glob pattern to exclude"
                )
            ),
            "recursive" to PropertySchema(
                type = "boolean",
                description = "Optional. Search directories recursively (controlled by ** in glob patterns)."
            ),
            "useDefaultExcludes" to PropertySchema(
                type = "boolean",
                description = "Optional. Apply default exclusion patterns. Defaults to true."
            ),
            "max_files" to PropertySchema(
                type = "number",
                description = "Optional. Maximum number of files to read. If more files match, priority_patterns will be used to select which ones. If not specified, all matching files will be read."
            ),
            "priority_patterns" to PropertySchema(
                type = "array",
                description = "Optional. Glob patterns for files to prioritize when max_files is specified. Example: [\"*.kt\", \"src/**/*.java\"]",
                items = PropertySchema(
                    type = "string",
                    description = "A glob pattern for priority files"
                )
            )
        ),
        required = listOf("include")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: ReadManyFilesParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ReadManyFilesParams, ToolResult> {
        return ReadManyFilesToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ReadManyFilesParams {
        val include = (params["include"] as? List<*>)?.mapNotNull { it as? String }
            ?: throw IllegalArgumentException("include is required and must be an array")
        
        if (include.isEmpty()) {
            throw IllegalArgumentException("include must contain at least one pattern")
        }
        
        val exclude = (params["exclude"] as? List<*>)?.mapNotNull { it as? String }
        val recursive = params["recursive"] as? Boolean
        val useDefaultExcludes = params["useDefaultExcludes"] as? Boolean ?: true
        val maxFiles = (params["max_files"] as? Number)?.toInt()
        val priorityPatterns = (params["priority_patterns"] as? List<*>)?.mapNotNull { it as? String }
        
        return ReadManyFilesParams(
            include = include,
            exclude = exclude,
            recursive = recursive,
            useDefaultExcludes = useDefaultExcludes,
            max_files = maxFiles,
            priority_patterns = priorityPatterns
        )
    }
}
