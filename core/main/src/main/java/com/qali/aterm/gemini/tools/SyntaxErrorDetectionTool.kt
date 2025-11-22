package com.qali.aterm.gemini.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.core.FunctionDeclaration
import com.qali.aterm.gemini.core.FunctionParameters
import com.qali.aterm.gemini.core.PropertySchema
import java.io.File

/**
 * Represents a syntax error found in a file
 */
data class SyntaxError(
    val filePath: String,
    val lineNumber: Int,
    val column: Int?,
    val message: String,
    val errorType: String, // "syntax", "type", "missing", "extra", etc.
    val suggestedFix: String? = null,
    val context: String? = null // Surrounding code context
)

data class SyntaxErrorDetectionParams(
    val file_path: String,
    val check_types: Boolean = true,
    val check_syntax: Boolean = true,
    val suggest_fixes: Boolean = true
)

class SyntaxErrorDetectionToolInvocation(
    toolParams: SyntaxErrorDetectionParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<SyntaxErrorDetectionParams, ToolResult> {
    
    override val params: SyntaxErrorDetectionParams = toolParams
    
    override fun getDescription(): String {
        return "Detecting syntax errors in ${params.file_path}"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        val file = File(workspaceRoot, params.file_path)
        return listOf(ToolLocation(file.absolutePath))
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Syntax check cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val file = File(workspaceRoot, params.file_path)
        
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
            val content = file.readText()
            val lines = content.lines()
            val errors = mutableListOf<SyntaxError>()
            
            // Basic syntax error detection
            if (params.check_syntax) {
                errors.addAll(detectBasicSyntaxErrors(content, lines, params.file_path))
            }
            
            // Type-specific error detection
            val fileExtension = file.extension.lowercase()
            when {
                fileExtension == "kt" || fileExtension == "kts" -> {
                    if (params.check_types) {
                        errors.addAll(detectKotlinErrors(content, lines, params.file_path))
                    }
                }
                fileExtension == "java" -> {
                    if (params.check_types) {
                        errors.addAll(detectJavaErrors(content, lines, params.file_path))
                    }
                }
                fileExtension == "ts" || fileExtension == "tsx" -> {
                    if (params.check_types) {
                        errors.addAll(detectTypeScriptErrors(content, lines, params.file_path))
                    }
                }
                fileExtension == "js" || fileExtension == "jsx" -> {
                    if (params.check_syntax) {
                        errors.addAll(detectJavaScriptErrors(content, lines, params.file_path))
                    }
                }
                fileExtension == "py" -> {
                    if (params.check_syntax) {
                        errors.addAll(detectPythonErrors(content, lines, params.file_path))
                    }
                }
            }
            
            val output = formatErrors(errors, params.suggest_fixes)
            
            updateOutput?.invoke("Found ${errors.size} error(s) in ${params.file_path}")
            
            ToolResult(
                llmContent = output,
                returnDisplay = if (errors.isEmpty()) "No errors found" else "Found ${errors.size} error(s)"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error detecting syntax errors: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun detectBasicSyntaxErrors(
        content: String,
        lines: List<String>,
        filePath: String
    ): List<SyntaxError> {
        val errors = mutableListOf<SyntaxError>()
        var braceCount = 0
        var parenCount = 0
        var bracketCount = 0
        var quoteCount = 0
        var inString = false
        var stringChar: Char? = null
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            var i = 0
            while (i < line.length) {
                val char = line[i]
                
                // Track string state
                if (char == '"' || char == '\'' || char == '`') {
                    if (!inString) {
                        inString = true
                        stringChar = char
                        quoteCount++
                    } else if (char == stringChar && (i == 0 || line[i - 1] != '\\')) {
                        inString = false
                        stringChar = null
                        quoteCount--
                    }
                }
                
                if (!inString) {
                    when (char) {
                        '{' -> braceCount++
                        '}' -> {
                            braceCount--
                            if (braceCount < 0) {
                                errors.add(
                                    SyntaxError(
                                        filePath = filePath,
                                        lineNumber = lineNum,
                                        column = i + 1,
                                        message = "Unmatched closing brace '}'",
                                        errorType = "syntax",
                                        suggestedFix = "Remove this brace or add opening brace",
                                        context = getContext(lines, index, i)
                                    )
                                )
                                braceCount = 0
                            }
                        }
                        '(' -> parenCount++
                        ')' -> {
                            parenCount--
                            if (parenCount < 0) {
                                errors.add(
                                    SyntaxError(
                                        filePath = filePath,
                                        lineNumber = lineNum,
                                        column = i + 1,
                                        message = "Unmatched closing parenthesis ')'",
                                        errorType = "syntax",
                                        suggestedFix = "Remove this parenthesis or add opening parenthesis",
                                        context = getContext(lines, index, i)
                                    )
                                )
                                parenCount = 0
                            }
                        }
                        '[' -> bracketCount++
                        ']' -> {
                            bracketCount--
                            if (bracketCount < 0) {
                                errors.add(
                                    SyntaxError(
                                        filePath = filePath,
                                        lineNumber = lineNum,
                                        column = i + 1,
                                        message = "Unmatched closing bracket ']'",
                                        errorType = "syntax",
                                        suggestedFix = "Remove this bracket or add opening bracket",
                                        context = getContext(lines, index, i)
                                    )
                                )
                                bracketCount = 0
                            }
                        }
                    }
                }
                i++
            }
        }
        
        // Check for unclosed brackets/braces/parens
        if (braceCount > 0) {
            errors.add(
                SyntaxError(
                    filePath = filePath,
                    lineNumber = lines.size,
                    column = null,
                    message = "Unclosed brace '{' (missing $braceCount closing brace(s))",
                    errorType = "syntax",
                    suggestedFix = "Add $braceCount closing brace(s)",
                    context = null
                )
            )
        }
        if (parenCount > 0) {
            errors.add(
                SyntaxError(
                    filePath = filePath,
                    lineNumber = lines.size,
                    column = null,
                    message = "Unclosed parenthesis '(' (missing $parenCount closing parenthesis(es))",
                    errorType = "syntax",
                    suggestedFix = "Add $parenCount closing parenthesis(es)",
                    context = null
                )
            )
        }
        if (bracketCount > 0) {
            errors.add(
                SyntaxError(
                    filePath = filePath,
                    lineNumber = lines.size,
                    column = null,
                    message = "Unclosed bracket '[' (missing $bracketCount closing bracket(s))",
                    errorType = "syntax",
                    suggestedFix = "Add $bracketCount closing bracket(s)",
                    context = null
                )
            )
        }
        if (quoteCount % 2 != 0) {
            errors.add(
                SyntaxError(
                    filePath = filePath,
                    lineNumber = lines.size,
                    column = null,
                    message = "Unclosed string literal",
                    errorType = "syntax",
                    suggestedFix = "Close the string literal",
                    context = null
                )
            )
        }
        
        return errors
    }
    
    private fun detectKotlinErrors(
        content: String,
        lines: List<String>,
        filePath: String
    ): List<SyntaxError> {
        val errors = mutableListOf<SyntaxError>()
        
        // Check for common Kotlin syntax issues
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trim()
            
            // Check for missing return type in function declaration
            if (trimmed.startsWith("fun ") && !trimmed.contains(":") && !trimmed.contains("=") && !trimmed.contains("{")) {
                if (!trimmed.endsWith(")")) {
                    errors.add(
                        SyntaxError(
                            filePath = filePath,
                            lineNumber = lineNum,
                            column = null,
                            message = "Function declaration may be missing return type or body",
                            errorType = "type",
                            suggestedFix = "Add return type (e.g., : Unit) or function body",
                            context = trimmed
                        )
                    )
                }
            }
            
            // Check for missing type in variable declaration
            if (trimmed.matches(Regex("^\\s*(val|var)\\s+\\w+\\s*=\\s*$"))) {
                errors.add(
                    SyntaxError(
                        filePath = filePath,
                        lineNumber = lineNum,
                        column = null,
                        message = "Variable declaration missing value",
                        errorType = "syntax",
                        suggestedFix = "Add value after =",
                        context = trimmed
                    )
                )
            }
        }
        
        return errors
    }
    
    private fun detectJavaErrors(
        content: String,
        lines: List<String>,
        filePath: String
    ): List<SyntaxError> {
        val errors = mutableListOf<SyntaxError>()
        
        // Similar checks for Java
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trim()
            
            // Check for missing semicolon
            if (trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && 
                !trimmed.startsWith("*") && !trimmed.startsWith("@") && 
                !trimmed.endsWith("{") && !trimmed.endsWith("}") && 
                !trimmed.endsWith(";") && !trimmed.contains("class ") && 
                !trimmed.contains("interface ") && !trimmed.contains("enum ") &&
                !trimmed.contains("if ") && !trimmed.contains("for ") && 
                !trimmed.contains("while ") && !trimmed.contains("switch ")) {
                // This is a heuristic - may have false positives
            }
        }
        
        return errors
    }
    
    private fun detectTypeScriptErrors(
        content: String,
        lines: List<String>,
        filePath: String
    ): List<SyntaxError> {
        // TypeScript-specific checks
        return emptyList()
    }
    
    private fun detectJavaScriptErrors(
        content: String,
        lines: List<String>,
        filePath: String
    ): List<SyntaxError> {
        // JavaScript-specific checks
        return emptyList()
    }
    
    private fun detectPythonErrors(
        content: String,
        lines: List<String>,
        filePath: String
    ): List<SyntaxError> {
        val errors = mutableListOf<SyntaxError>()
        var indentStack = mutableListOf<Int>()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trim()
            
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEachIndexed
            }
            
            val indent = line.length - line.trimStart().length
            
            // Check indentation consistency
            if (trimmed.endsWith(":") && index < lines.size - 1) {
                // Next line should be indented
                val nextLine = lines.getOrNull(index + 1)
                if (nextLine != null && nextLine.trim().isNotEmpty()) {
                    val nextIndent = nextLine.length - nextLine.trimStart().length
                    val expectedIndent = indent + 4 // Python standard is 4 spaces
                    if (nextIndent <= indent) {
                        errors.add(
                            SyntaxError(
                                filePath = filePath,
                                lineNumber = lineNum + 1,
                                column = 1,
                                message = "Expected indentation after ':'",
                                errorType = "syntax",
                                suggestedFix = "Indent the next line",
                                context = "$trimmed\n${nextLine.trim()}"
                            )
                        )
                    }
                }
            }
        }
        
        return errors
    }
    
    private fun getContext(lines: List<String>, lineIndex: Int, columnIndex: Int): String {
        val start = maxOf(0, lineIndex - 2)
        val end = minOf(lines.size, lineIndex + 3)
        return lines.subList(start, end).joinToString("\n")
    }
    
    private fun formatErrors(errors: List<SyntaxError>, suggestFixes: Boolean): String {
        if (errors.isEmpty()) {
            return "No syntax errors detected."
        }
        
        val sb = StringBuilder()
        sb.appendLine("=== Syntax Errors Found (${errors.size}) ===")
        sb.appendLine()
        
        errors.forEachIndexed { index, error ->
            sb.appendLine("Error ${index + 1}: ${error.errorType.uppercase()}")
            sb.appendLine("  File: ${error.filePath}")
            sb.appendLine("  Line: ${error.lineNumber}${error.column?.let { ", Column: $it" } ?: ""}")
            sb.appendLine("  Message: ${error.message}")
            if (error.context != null) {
                sb.appendLine("  Context:")
                error.context.lines().take(5).forEach { contextLine ->
                    sb.appendLine("    $contextLine")
                }
            }
            if (suggestFixes && error.suggestedFix != null) {
                sb.appendLine("  Suggested Fix: ${error.suggestedFix}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("=== Summary ===")
        sb.appendLine("Use the syntax_fix tool to automatically fix some of these errors.")
        
        return sb.toString()
    }
}

class SyntaxErrorDetectionTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<SyntaxErrorDetectionParams, ToolResult>() {
    
    override val name = "syntax_error_detection"
    override val displayName = "SyntaxErrorDetection"
    override val description = "Detects syntax errors in code files including unmatched brackets, unclosed strings, indentation issues, and type-specific errors. Provides suggestions for fixes."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to check."
            ),
            "check_types" to PropertySchema(
                type = "boolean",
                description = "Whether to check type-related errors. Defaults to true."
            ),
            "check_syntax" to PropertySchema(
                type = "boolean",
                description = "Whether to check basic syntax errors. Defaults to true."
            ),
            "suggest_fixes" to PropertySchema(
                type = "boolean",
                description = "Whether to suggest fixes for detected errors. Defaults to true."
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
        params: SyntaxErrorDetectionParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<SyntaxErrorDetectionParams, ToolResult> {
        return SyntaxErrorDetectionToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): SyntaxErrorDetectionParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        return SyntaxErrorDetectionParams(
            file_path = filePath,
            check_types = (params["check_types"] as? Boolean) ?: true,
            check_syntax = (params["check_syntax"] as? Boolean) ?: true,
            suggest_fixes = (params["suggest_fixes"] as? Boolean) ?: true
        )
    }
}
