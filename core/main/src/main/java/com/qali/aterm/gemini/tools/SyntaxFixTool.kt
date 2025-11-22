package com.qali.aterm.gemini.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.core.FunctionDeclaration
import com.qali.aterm.gemini.core.FunctionParameters
import com.qali.aterm.gemini.core.PropertySchema
import com.qali.aterm.gemini.utils.FileCoherenceManager
import java.io.File

data class SyntaxFixParams(
    val file_path: String,
    val fix_type: String? = null, // "all", "brackets", "indentation", "strings", etc.
    val auto_fix: Boolean = true // Automatically apply fixes
)

class SyntaxFixToolInvocation(
    toolParams: SyntaxFixParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<SyntaxFixParams, ToolResult> {
    
    override val params: SyntaxFixParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.file_path).absolutePath
    
    override fun getDescription(): String {
        return "Fixing syntax errors in ${params.file_path}"
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
                llmContent = "Syntax fix cancelled",
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
            var content = file.readText()
            val originalContent = content
            val fixes = mutableListOf<String>()
            
            // First, detect errors
            val detectionTool = SyntaxErrorDetectionToolInvocation(
                SyntaxErrorDetectionParams(
                    file_path = params.file_path,
                    check_types = true,
                    check_syntax = true,
                    suggest_fixes = true
                ),
                workspaceRoot
            )
            
            // We'll fix common issues directly
            val fixType = params.fix_type ?: "all"
            
            when (fixType) {
                "all", "brackets" -> {
                    val bracketFixes = fixBrackets(content)
                    content = bracketFixes.first
                    fixes.addAll(bracketFixes.second)
                }
            }
            
            when (fixType) {
                "all", "strings" -> {
                    val stringFixes = fixUnclosedStrings(content)
                    content = stringFixes.first
                    fixes.addAll(stringFixes.second)
                }
            }
            
            when (fixType) {
                "all", "indentation" -> {
                    val indentFixes = fixIndentation(content, file.extension.lowercase())
                    content = indentFixes.first
                    fixes.addAll(indentFixes.second)
                }
            }
            
            if (content != originalContent && params.auto_fix) {
                val writeSuccess = FileCoherenceManager.writeFileWithCoherence(file, content)
                if (!writeSuccess) {
                    return ToolResult(
                        llmContent = "File was modified by another operation. Please retry the syntax fix.",
                        returnDisplay = "Error: Write conflict",
                        error = ToolError(
                            message = "File was modified concurrently",
                            type = ToolErrorType.EXECUTION_ERROR
                        )
                    )
                }
                val message = "Fixed ${fixes.size} issue(s): ${fixes.joinToString(", ")}"
                updateOutput?.invoke(message)
                
                ToolResult(
                    llmContent = message,
                    returnDisplay = "Fixed ${fixes.size} issue(s)"
                )
            } else if (fixes.isNotEmpty() && !params.auto_fix) {
                ToolResult(
                    llmContent = "Would fix ${fixes.size} issue(s): ${fixes.joinToString(", ")}\n\nSet auto_fix=true to apply fixes.",
                    returnDisplay = "Found ${fixes.size} fixable issue(s)"
                )
            } else {
                ToolResult(
                    llmContent = "No fixable issues found.",
                    returnDisplay = "No fixes needed"
                )
            }
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error fixing syntax: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun fixBrackets(content: String): Pair<String, List<String>> {
        var fixed = content
        val fixes = mutableListOf<String>()
        
        var braceCount = 0
        var parenCount = 0
        var bracketCount = 0
        
        // Count brackets
        content.forEach { char ->
            when (char) {
                '{' -> braceCount++
                '}' -> braceCount--
                '(' -> parenCount++
                ')' -> parenCount--
                '[' -> bracketCount++
                ']' -> bracketCount--
            }
        }
        
        // Add missing closing brackets at the end
        if (braceCount > 0) {
            fixed += "\n" + "}".repeat(braceCount)
            fixes.add("Added $braceCount closing brace(s)")
        }
        if (parenCount > 0) {
            fixed += "\n" + ")".repeat(parenCount)
            fixes.add("Added $parenCount closing parenthesis(es)")
        }
        if (bracketCount > 0) {
            fixed += "\n" + "]".repeat(bracketCount)
            fixes.add("Added $bracketCount closing bracket(s)")
        }
        
        return Pair(fixed, fixes)
    }
    
    private fun fixUnclosedStrings(content: String): Pair<String, List<String>> {
        var fixed = content
        val fixes = mutableListOf<String>()
        
        var inString = false
        var stringChar: Char? = null
        var stringStart = 0
        
        content.forEachIndexed { index, char ->
            if (char == '"' || char == '\'' || char == '`') {
                if (!inString) {
                    inString = true
                    stringChar = char
                    stringStart = index
                } else if (char == stringChar && (index == 0 || content[index - 1] != '\\')) {
                    inString = false
                    stringChar = null
                }
            }
        }
        
        if (inString && stringChar != null) {
            // Close the string at the end
            fixed += stringChar
            fixes.add("Closed unclosed string literal")
        }
        
        return Pair(fixed, fixes)
    }
    
    private fun fixIndentation(content: String, fileExtension: String): Pair<String, List<String>> {
        if (fileExtension != "py") {
            return Pair(content, emptyList())
        }
        
        val fixes = mutableListOf<String>()
        val lines = content.lines().toMutableList()
        var fixed = false
        
        lines.forEachIndexed { index, line ->
            if (index == 0) return@forEachIndexed
            
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEachIndexed
            }
            
            val prevLine = lines[index - 1].trim()
            if (prevLine.endsWith(":") && !trimmed.startsWith(" ") && !trimmed.startsWith("\t")) {
                // Missing indentation after colon
                val indent = lines[index - 1].takeWhile { it == ' ' || it == '\t' }
                lines[index] = indent + "    " + trimmed // Python standard is 4 spaces
                fixed = true
            }
        }
        
        if (fixed) {
            fixes.add("Fixed indentation issues")
        }
        
        return Pair(lines.joinToString("\n"), fixes)
    }
}

class SyntaxFixTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<SyntaxFixParams, ToolResult>() {
    
    override val name = "syntax_fix"
    override val displayName = "SyntaxFix"
    override val description = "Automatically fixes common syntax errors in code files including unmatched brackets, unclosed strings, and indentation issues. Use after syntax_error_detection to apply fixes."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to fix."
            ),
            "fix_type" to PropertySchema(
                type = "string",
                description = "Type of fixes to apply: 'all', 'brackets', 'strings', 'indentation'. Defaults to 'all'."
            ),
            "auto_fix" to PropertySchema(
                type = "boolean",
                description = "If true, automatically apply fixes to the file. If false, only report what would be fixed. Defaults to true."
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
        params: SyntaxFixParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<SyntaxFixParams, ToolResult> {
        return SyntaxFixToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): SyntaxFixParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        return SyntaxFixParams(
            file_path = filePath,
            fix_type = params["fix_type"] as? String,
            auto_fix = (params["auto_fix"] as? Boolean) ?: true
        )
    }
}
