package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import java.io.File
import java.util.regex.Pattern

data class RipGrepToolParams(
    val pattern: String,
    val dir_path: String? = null,
    val include: String? = null,
    val case_sensitive: Boolean? = null,
    val fixed_strings: Boolean? = null,
    val context: Int? = null,
    val after: Int? = null,
    val before: Int? = null,
    val no_ignore: Boolean? = null
)

data class RipGrepMatch(
    val filePath: String,
    val lineNumber: Int,
    val line: String,
    val contextBefore: List<String> = emptyList(),
    val contextAfter: List<String> = emptyList()
)

class RipGrepToolInvocation(
    toolParams: RipGrepToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<RipGrepToolParams, ToolResult> {
    
    override val params: RipGrepToolParams = toolParams
    
    private val maxMatches = 20000
    
    override fun getDescription(): String {
        return "RipGrep search: ${params.pattern}"
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
                llmContent = "Directory not found: ${params.dir_path ?: "root"}",
                returnDisplay = "Error: Directory not found",
                error = ToolError(
                    message = "Directory not found",
                    type = ToolErrorType.FILE_NOT_FOUND
                )
            )
        }
        
        // Build regex pattern
        val regexPattern = if (params.fixed_strings == true) {
            Pattern.quote(params.pattern)
        } else {
            params.pattern
        }
        
        val pattern = try {
            if (params.case_sensitive == true) {
                Pattern.compile(regexPattern)
            } else {
                Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE)
            }
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
        
        val matches = mutableListOf<RipGrepMatch>()
        val contextLines = (params.context ?: 0).coerceAtLeast(0)
        val afterLines = (params.after ?: contextLines).coerceAtLeast(0)
        val beforeLines = (params.before ?: contextLines).coerceAtLeast(0)
        
        searchFiles(searchDir, pattern, params.include, matches, beforeLines, afterLines, signal)
        
        if (matches.isEmpty()) {
            val locationDesc = if (params.dir_path != null) "in path \"${params.dir_path}\"" else "in workspace"
            return ToolResult(
                llmContent = "No matches found for pattern \"${params.pattern}\" $locationDesc${if (params.include != null) " (filter: \"${params.include}\")" else ""}.",
                returnDisplay = "No matches found"
            )
        }
        
        // Limit results
        val limitedMatches = if (matches.size > maxMatches) {
            matches.take(maxMatches)
        } else {
            matches
        }
        
        // Group by file
        val matchesByFile = limitedMatches.groupBy { it.filePath }
            .mapValues { (_, matches) -> matches.sortedBy { it.lineNumber } }
        
        val matchCount = limitedMatches.size
        val matchTerm = if (matchCount == 1) "match" else "matches"
        val wasTruncated = matches.size > maxMatches
        
        val locationDesc = if (params.dir_path != null) "in path \"${params.dir_path}\"" else "in workspace"
        var llmContent = "Found $matchCount $matchTerm for pattern \"${params.pattern}\" $locationDesc${if (params.include != null) " (filter: \"${params.include}\")" else ""}"
        
        if (wasTruncated) {
            llmContent += " (results limited to $maxMatches matches for performance)"
        }
        
        llmContent += ":\n---\n"
        
        for ((filePath, fileMatches) in matchesByFile) {
            llmContent += "File: $filePath\n"
            for (match in fileMatches) {
                // Add context before
                for (contextLine in match.contextBefore) {
                    llmContent += "  $contextLine\n"
                }
                // Add match line
                llmContent += "L${match.lineNumber}: ${match.line.trim()}\n"
                // Add context after
                for (contextLine in match.contextAfter) {
                    llmContent += "  $contextLine\n"
                }
            }
            llmContent += "---\n"
        }
        
        val displayMessage = "Found $matchCount $matchTerm" + if (wasTruncated) " (limited)" else ""
        updateOutput?.invoke(displayMessage)
        
        return ToolResult(
            llmContent = llmContent.trim(),
            returnDisplay = displayMessage
        )
    }
    
    private fun searchFiles(
        dir: File,
        pattern: Pattern,
        include: String?,
        matches: MutableList<RipGrepMatch>,
        beforeLines: Int,
        afterLines: Int,
        signal: CancellationSignal?
    ) {
        if (signal?.isAborted() == true) return
        if (!dir.exists() || !dir.isDirectory) return
        
        dir.listFiles()?.forEach { file ->
            if (signal?.isAborted() == true) return
            
            if (file.isDirectory && !file.name.startsWith(".")) {
                searchFiles(file, pattern, include, matches, beforeLines, afterLines, signal)
            } else if (file.isFile) {
                // Check include pattern
                if (include != null && !matchesIncludePattern(file.name, include)) {
                    return@forEach
                }
                
                // Skip large files
                if (file.length() > 10 * 1024 * 1024) { // 10MB limit
                    return@forEach
                }
                
                try {
                    val lines = file.readLines()
                    for (i in lines.indices) {
                        if (pattern.matcher(lines[i]).find()) {
                            val contextBefore = if (beforeLines > 0) {
                                lines.subList(maxOf(0, i - beforeLines), i)
                            } else {
                                emptyList()
                            }
                            
                            val contextAfter = if (afterLines > 0) {
                                lines.subList(i + 1, minOf(lines.size, i + 1 + afterLines))
                            } else {
                                emptyList()
                            }
                            
                            matches.add(
                                RipGrepMatch(
                                    filePath = file.absolutePath,
                                    lineNumber = i + 1,
                                    line = lines[i],
                                    contextBefore = contextBefore,
                                    contextAfter = contextAfter
                                )
                            )
                            
                            if (matches.size >= maxMatches) {
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }
        }
    }
    
    private fun matchesIncludePattern(filename: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return filename.matches(regex.toRegex())
    }
}

class RipGrepTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<RipGrepToolParams, ToolResult>() {
    
    override val name = "ripgrep"
    override val displayName = "RipGrep"
    override val description = "Advanced regex search tool with context lines, case sensitivity options, and file filtering. More powerful than the basic grep tool."
    
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
            ),
            "case_sensitive" to PropertySchema(
                type = "boolean",
                description = "If true, searches case-sensitively. Defaults to false."
            ),
            "fixed_strings" to PropertySchema(
                type = "boolean",
                description = "If true, treats pattern as a literal string. Defaults to false."
            ),
            "context" to PropertySchema(
                type = "number",
                description = "Show num lines of context around each match."
            ),
            "after" to PropertySchema(
                type = "number",
                description = "Show num lines after each match."
            ),
            "before" to PropertySchema(
                type = "number",
                description = "Show num lines before each match."
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
        params: RipGrepToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<RipGrepToolParams, ToolResult> {
        return RipGrepToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): RipGrepToolParams {
        val pattern = params["pattern"] as? String
            ?: throw IllegalArgumentException("pattern is required")
        
        return RipGrepToolParams(
            pattern = pattern,
            dir_path = params["dir_path"] as? String,
            include = params["include"] as? String,
            case_sensitive = params["case_sensitive"] as? Boolean,
            fixed_strings = params["fixed_strings"] as? Boolean,
            context = (params["context"] as? Number)?.toInt(),
            after = (params["after"] as? Number)?.toInt(),
            before = (params["before"] as? Number)?.toInt(),
            no_ignore = params["no_ignore"] as? Boolean
        )
    }
}
