package com.qali.aterm.gemini.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.gemini.core.FunctionDeclaration
import com.qali.aterm.gemini.core.FunctionParameters
import com.qali.aterm.gemini.core.PropertySchema
import com.qali.aterm.gemini.tools.ShellToolParams
import com.qali.aterm.gemini.tools.ShellToolInvocation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Represents a linting error found by a language-specific linter
 */
data class LinterError(
    val filePath: String,
    val lineNumber: Int?,
    val column: Int?,
    val message: String,
    val errorType: String, // "error", "warning", "info"
    val code: String? = null, // Error code (e.g., "E501", "F401")
    val rule: String? = null // Linter rule name
)

data class LanguageLinterParams(
    val file_path: String,
    val language: String? = null, // Auto-detect if not provided
    val strict: Boolean = false // Fail on warnings too
)

class LanguageLinterToolInvocation(
    toolParams: LanguageLinterParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<LanguageLinterParams, ToolResult> {
    
    override val params: LanguageLinterParams = toolParams
    
    override fun getDescription(): String {
        return "Linting ${params.file_path} with language-specific linter"
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
                llmContent = "Linting cancelled",
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
            val language = params.language ?: detectLanguage(file)
            val errors = withContext(Dispatchers.IO) {
                lintFile(file, language, params.strict)
            }
            
            val output = formatLinterErrors(errors, file.name)
            
            updateOutput?.invoke("Found ${errors.size} linting issue(s) in ${params.file_path}")
            
            ToolResult(
                llmContent = output,
                returnDisplay = if (errors.isEmpty()) "No linting issues found" else "Found ${errors.size} issue(s)"
            )
        } catch (e: Exception) {
            // Enhanced error reporting with debugging information
            val errorDetails = buildString {
                appendLine("Error linting file: ${params.file_path}")
                appendLine("Error type: ${e.javaClass.simpleName}")
                appendLine("Error message: ${e.message}")
                appendLine("File exists: ${file.exists()}")
                appendLine("File readable: ${file.canRead()}")
                appendLine("File path: ${file.absolutePath}")
                appendLine("Workspace root: $workspaceRoot")
                
                // Try to get more context about the error
                if (e is java.io.IOException) {
                    appendLine("IO Error - possible causes:")
                    appendLine("  - File permissions issue")
                    appendLine("  - Command not found in PATH")
                    appendLine("  - Rootfs environment not properly initialized")
                }
                
                if (e.message?.contains("Permission denied") == true) {
                    appendLine("\n⚠️ Permission denied - commands should run through ShellTool")
                    appendLine("This usually means the linter tried to run commands directly")
                    appendLine("instead of through the rootfs environment.")
                }
            }
            
            android.util.Log.e("LanguageLinterTool", errorDetails, e)
            
            ToolResult(
                llmContent = errorDetails,
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun detectLanguage(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "sh", "bash" -> "bash"
            else -> "unknown"
        }
    }
    
    private suspend fun lintFile(file: File, language: String, strict: Boolean): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        when (language) {
            "python" -> errors.addAll(lintPython(file))
            "javascript", "typescript" -> errors.addAll(lintJavaScript(file, language))
            "bash" -> errors.addAll(lintBash(file))
            else -> {
                // Fallback to basic syntax detection
                errors.addAll(detectBasicErrors(file))
            }
        }
        
        // Filter by strictness
        return if (strict) {
            errors
        } else {
            errors.filter { it.errorType == "error" }
        }
    }
    
    private suspend fun lintPython(file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        try {
            // Try pyflakes first (lightweight, fast)
            val pyflakesResult = runLinterCommand(
                command = listOf("pyflakes", file.absolutePath),
                file = file
            )
            errors.addAll(parsePyflakesOutput(pyflakesResult, file))
            
            // If pyflakes not available, try python -m py_compile
            if (errors.isEmpty() && pyflakesResult.isEmpty()) {
                val compileResult = runLinterCommand(
                    command = listOf("python3", "-m", "py_compile", file.absolutePath),
                    file = file
                )
                if (compileResult.isNotEmpty() && !compileResult.contains("OK")) {
                    errors.addAll(parsePythonCompileOutput(compileResult, file))
                }
            }
        } catch (e: Exception) {
            // If linter not available, fall back to basic detection
            android.util.Log.w("LanguageLinterTool", "Python linter not available: ${e.message}")
            errors.addAll(detectBasicErrors(file))
        }
        
        return errors
    }
    
    private suspend fun lintJavaScript(file: File, language: String): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        try {
            // Try node syntax check first (more reliable, doesn't need dependencies)
            val nodeResult = runLinterCommand(
                command = listOf("node", "--check", file.absolutePath),
                file = file
            )
            if (nodeResult.isNotEmpty() && !nodeResult.contains("Error:") && !nodeResult.contains("Permission denied")) {
                errors.addAll(parseNodeCheckOutput(nodeResult, file))
            }
            
            // Try eslint if available (only if node check passed or wasn't available)
            if (errors.isEmpty() || nodeResult.contains("Permission denied") || nodeResult.contains("Error:")) {
                val eslintResult = runLinterCommand(
                    command = listOf("npx", "--yes", "eslint", "--format", "compact", file.absolutePath),
                    file = file
                )
                if (eslintResult.isNotEmpty() && 
                    !eslintResult.contains("No ESLint configuration") && 
                    !eslintResult.contains("Permission denied") &&
                    !eslintResult.contains("Error:")) {
                    errors.addAll(parseESLintOutput(eslintResult, file))
                }
            }
            
            // If all linters failed due to permission/availability, use basic detection
            if (errors.isEmpty() && (nodeResult.contains("Permission denied") || nodeResult.contains("Error:"))) {
                android.util.Log.w("LanguageLinterTool", "JavaScript linter not available, using basic detection")
                errors.addAll(detectBasicErrors(file))
            }
        } catch (e: Exception) {
            // If linter not available, fall back to basic detection
            android.util.Log.w("LanguageLinterTool", "JavaScript linter not available: ${e.message}")
            errors.addAll(detectBasicErrors(file))
        }
        
        return errors
    }
    
    private suspend fun lintBash(file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        try {
            // Use shellcheck if available
            val shellcheckResult = runLinterCommand(
                command = listOf("shellcheck", "-f", "gcc", file.absolutePath),
                file = file
            )
            if (shellcheckResult.isNotEmpty()) {
                errors.addAll(parseShellcheckOutput(shellcheckResult, file))
            } else {
                // Fallback to bash -n (syntax check)
                val bashResult = runLinterCommand(
                    command = listOf("bash", "-n", file.absolutePath),
                    file = file
                )
                if (bashResult.isNotEmpty()) {
                    errors.addAll(parseBashCheckOutput(bashResult, file))
                }
            }
        } catch (e: Exception) {
            // If linter not available, fall back to basic detection
            android.util.Log.w("LanguageLinterTool", "Bash linter not available: ${e.message}")
            errors.addAll(detectBasicErrors(file))
        }
        
        return errors
    }
    
    private suspend fun runLinterCommand(command: List<String>, file: File): String {
        return try {
            // Use ShellTool to run commands through proper rootfs environment
            val commandStr = command.joinToString(" ")
            val shellTool = ShellToolInvocation(
                ShellToolParams(
                    command = commandStr,
                    description = "Lint ${file.name}",
                    dir_path = file.parentFile?.absolutePath
                ),
                workspaceRoot
            )
            
            val result = shellTool.execute(null, null)
            
            // Return output if there was an error or if output contains useful information
            if (result.error != null || result.llmContent.isNotEmpty()) {
                result.llmContent + if (result.error != null) "\nError: ${result.error?.message}" else ""
            } else {
                ""
            }
        } catch (e: Exception) {
            android.util.Log.d("LanguageLinterTool", "Command failed: ${command.joinToString(" ")} - ${e.message}")
            // Return error message for better debugging
            "Error: ${e.message}\nCommand: ${command.joinToString(" ")}\nFile: ${file.absolutePath}"
        }
    }
    
    private fun parsePyflakesOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val lines = output.lines()
        
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            
            // pyflakes format: file:line: message
            val regex = Regex("""(.+):(\d+):(\d+)?:?\s*(.+)""")
            val match = regex.find(line)
            
            if (match != null) {
                val (_, lineNum, colNum, message) = match.destructured
                errors.add(
                    LinterError(
                        filePath = file.absolutePath,
                        lineNumber = lineNum.toIntOrNull(),
                        column = colNum.toIntOrNull(),
                        message = message.trim(),
                        errorType = "error",
                        code = extractErrorCode(message)
                    )
                )
            }
        }
        
        return errors
    }
    
    private fun parsePythonCompileOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val lines = output.lines()
        
        for (line in lines) {
            if (line.contains("SyntaxError") || line.contains("IndentationError")) {
                val regex = Regex("""File\s+"(.+)",\s+line\s+(\d+)""")
                val match = regex.find(line)
                if (match != null) {
                    val (_, lineNum) = match.destructured
                    errors.add(
                        LinterError(
                            filePath = file.absolutePath,
                            lineNumber = lineNum.toIntOrNull(),
                            column = null,
                            message = line.substringAfter(":").trim(),
                            errorType = "error",
                            code = "SYNTAX_ERROR"
                        )
                    )
                }
            }
        }
        
        return errors
    }
    
    private fun parseESLintOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val lines = output.lines()
        
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            
            // ESLint compact format: file:line:col: level message (rule)
            val regex = Regex("""(.+):(\d+):(\d+):\s*(error|warning|info)\s+(.+?)(?:\s+\((.+?)\))?$""")
            val match = regex.find(line)
            
            if (match != null) {
                val (_, lineNum, colNum, level, message, rule) = match.destructured
                errors.add(
                    LinterError(
                        filePath = file.absolutePath,
                        lineNumber = lineNum.toIntOrNull(),
                        column = colNum.toIntOrNull(),
                        message = message.trim(),
                        errorType = level,
                        code = rule.takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        
        return errors
    }
    
    private fun parseNodeCheckOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        // Node --check format: SyntaxError: message at file:line:col
        val regex = Regex("""SyntaxError:\s*(.+?)\s+at\s+(.+?):(\d+):(\d+)""")
        val match = regex.find(output)
        
        if (match != null) {
            val (message, _, lineNum, colNum) = match.destructured
            errors.add(
                LinterError(
                    filePath = file.absolutePath,
                    lineNumber = lineNum.toIntOrNull(),
                    column = colNum.toIntOrNull(),
                    message = message.trim(),
                    errorType = "error",
                    code = "SYNTAX_ERROR"
                )
            )
        }
        
        return errors
    }
    
    private fun parseShellcheckOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val lines = output.lines()
        
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            
            // shellcheck gcc format: file:line:col: level: message [SCcode]
            val regex = Regex("""(.+):(\d+):(\d+):\s*(error|warning|info|note):\s*(.+?)(?:\s+\[SC(\d+)\])?$""")
            val match = regex.find(line)
            
            if (match != null) {
                val (_, lineNum, colNum, level, message, code) = match.destructured
                errors.add(
                    LinterError(
                        filePath = file.absolutePath,
                        lineNumber = lineNum.toIntOrNull(),
                        column = colNum.toIntOrNull(),
                        message = message.trim(),
                        errorType = level,
                        code = code.takeIf { it.isNotEmpty() }?.let { "SC$it" }
                    )
                )
            }
        }
        
        return errors
    }
    
    private fun parseBashCheckOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        // bash -n format: file: line N: message
        val regex = Regex("""(.+):\s+line\s+(\d+):\s*(.+)""")
        val match = regex.find(output)
        
        if (match != null) {
            val (_, lineNum, message) = match.destructured
            errors.add(
                LinterError(
                    filePath = file.absolutePath,
                    lineNumber = lineNum.toIntOrNull(),
                    column = null,
                    message = message.trim(),
                    errorType = "error",
                    code = "SYNTAX_ERROR"
                )
            )
        }
        
        return errors
    }
    
    private suspend fun detectBasicErrors(file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        
        // First, try to read and analyze the file content directly
        try {
            val content = file.readText()
            errors.addAll(analyzeFileContent(content, file))
        } catch (e: Exception) {
            android.util.Log.w("LanguageLinterTool", "Failed to read file: ${e.message}")
        }
        
        // Also use syntax error detection tool for additional analysis
        try {
            val detectionTool = SyntaxErrorDetectionToolInvocation(
                SyntaxErrorDetectionParams(
                    file_path = file.relativeTo(File(workspaceRoot)).path,
                    check_types = true,
                    check_syntax = true,
                    suggest_fixes = false
                ),
                workspaceRoot
            )
            
            // Use coroutineScope instead of runBlocking to avoid blocking
            val result = detectionTool.execute(null, null)
            
            // Parse the result to extract errors
            errors.addAll(parseBasicErrorOutput(result.llmContent, file))
        } catch (e: Exception) {
            android.util.Log.w("LanguageLinterTool", "Syntax detection tool failed: ${e.message}")
        }
        
        return errors.distinctBy { "${it.lineNumber}:${it.message}" }
    }
    
    /**
     * Analyze file content for common errors
     */
    private fun analyzeFileContent(content: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val lines = content.lines()
        val extension = file.extension.lowercase()
        
        // Check for common syntax issues
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            
            // JavaScript/TypeScript specific checks
            if (extension in listOf("js", "jsx", "ts", "tsx")) {
                // Check for unclosed brackets
                val openBraces = line.count { it == '{' }
                val closeBraces = line.count { it == '}' }
                val openParens = line.count { it == '(' }
                val closeParens = line.count { it == ')' }
                val openBrackets = line.count { it == '[' }
                val closeBrackets = line.count { it == ']' }
                
                // Check for common issues
                if (line.contains("require(") && !line.contains("const") && !line.contains("let") && !line.contains("var")) {
                    errors.add(LinterError(
                        filePath = file.absolutePath,
                        lineNumber = lineNum,
                        column = line.indexOf("require"),
                        message = "require() should be assigned to a variable",
                        errorType = "warning",
                        code = "REQUIRE_ASSIGNMENT"
                    ))
                }
                
                // Check for undefined variables (basic check)
                if (line.matches(Regex("""^\s*[a-zA-Z_$][a-zA-Z0-9_$]*\s*=""")) && 
                    !line.contains("const") && !line.contains("let") && !line.contains("var")) {
                    errors.add(LinterError(
                        filePath = file.absolutePath,
                        lineNumber = lineNum,
                        column = 0,
                        message = "Variable declaration missing const/let/var",
                        errorType = "error",
                        code = "MISSING_DECLARATION"
                    ))
                }
            }
            
            // Python specific checks
            if (extension == "py") {
                // Check for indentation issues (basic)
                if (line.isNotEmpty() && line[0] == ' ' && line.trim().isNotEmpty()) {
                    val indent = line.takeWhile { it == ' ' }.length
                    if (indent % 4 != 0 && indent > 0) {
                        errors.add(LinterError(
                            filePath = file.absolutePath,
                            lineNumber = lineNum,
                            column = 0,
                            message = "Inconsistent indentation (should be multiple of 4 spaces)",
                            errorType = "warning",
                            code = "INDENTATION"
                        ))
                    }
                }
                
                // Check for common syntax errors
                if (line.contains("print ") && !line.contains("print(")) {
                    errors.add(LinterError(
                        filePath = file.absolutePath,
                        lineNumber = lineNum,
                        column = line.indexOf("print"),
                        message = "print statement should use print() function (Python 3)",
                        errorType = "error",
                        code = "PRINT_STATEMENT"
                    ))
                }
            }
            
            // Check for trailing whitespace
            if (line.isNotEmpty() && line.last() == ' ') {
                errors.add(LinterError(
                    filePath = file.absolutePath,
                    lineNumber = lineNum,
                    column = line.length - 1,
                    message = "Trailing whitespace",
                    errorType = "warning",
                    code = "TRAILING_WHITESPACE"
                ))
            }
        }
        
        // Check for common file-level issues
        if (extension in listOf("js", "jsx", "ts", "tsx")) {
            // Check for missing semicolons in critical places
            val lastLine = lines.lastOrNull()
            if (lastLine != null && lastLine.trim().isNotEmpty() && 
                !lastLine.trim().endsWith(";") && 
                !lastLine.trim().endsWith("{") && 
                !lastLine.trim().endsWith("}") &&
                !lastLine.contains("function") &&
                !lastLine.contains("=>")) {
                // This is just a warning, not always an error
            }
        }
        
        return errors
    }
    
    private fun parseBasicErrorOutput(output: String, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val lines = output.lines()
        
        var currentError: MutableMap<String, String?> = mutableMapOf()
        
        for (line in lines) {
            when {
                line.startsWith("Error ") -> {
                    currentError["type"] = line.substringAfter(": ").substringBefore(" ").uppercase()
                }
                line.contains("Line:") -> {
                    val lineNum = line.substringAfter("Line: ").substringBefore(",").trim().toIntOrNull()
                    currentError["line"] = lineNum?.toString()
                }
                line.contains("Message:") -> {
                    currentError["message"] = line.substringAfter("Message: ").trim()
                }
                line.trim().isEmpty() && currentError.isNotEmpty() -> {
                    errors.add(
                        LinterError(
                            filePath = file.absolutePath,
                            lineNumber = currentError["line"]?.toIntOrNull(),
                            column = null,
                            message = currentError["message"] ?: "Unknown error",
                            errorType = currentError["type"]?.lowercase() ?: "error",
                            code = null
                        )
                    )
                    currentError.clear()
                }
            }
        }
        
        return errors
    }
    
    private fun extractErrorCode(message: String): String? {
        // Try to extract error codes like E501, F401, etc.
        val regex = Regex("""([A-Z]\d{3})""")
        return regex.find(message)?.value
    }
    
    private fun formatLinterErrors(errors: List<LinterError>, fileName: String): String {
        if (errors.isEmpty()) {
            return "No linting errors found in $fileName."
        }
        
        val sb = StringBuilder()
        sb.appendLine("=== Linting Errors Found (${errors.size}) ===")
        sb.appendLine()
        
        errors.forEachIndexed { index, error ->
            sb.appendLine("Error ${index + 1}: ${error.errorType.uppercase()}")
            sb.appendLine("  File: ${error.filePath}")
            if (error.lineNumber != null) {
                sb.append("  Line: ${error.lineNumber}")
                if (error.column != null) {
                    sb.append(", Column: ${error.column}")
                }
                sb.appendLine()
            }
            sb.appendLine("  Message: ${error.message}")
            if (error.code != null) {
                sb.appendLine("  Code: ${error.code}")
            }
            if (error.rule != null) {
                sb.appendLine("  Rule: ${error.rule}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("=== Summary ===")
        sb.appendLine("Use the syntax_fix tool or manually fix these errors.")
        if (errors.any { it.errorType == "error" }) {
            sb.appendLine("⚠️  Critical errors found that must be fixed.")
        }
        
        return sb.toString()
    }
}

class LanguageLinterTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<LanguageLinterParams, ToolResult>() {
    
    override val name = "language_linter"
    override val displayName = "LanguageLinter"
    override val description = "Uses language-specific linters (pyflakes for Python, eslint for JavaScript/TypeScript, shellcheck for Bash) to detect syntax and functional errors in code files. Provides IDE-like error detection with line numbers, error codes, and detailed messages."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to lint."
            ),
            "language" to PropertySchema(
                type = "string",
                description = "The programming language (python, javascript, typescript, bash, etc.). Auto-detected from file extension if not provided."
            ),
            "strict" to PropertySchema(
                type = "boolean",
                description = "If true, include warnings and info messages. If false, only show errors. Defaults to false."
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
        params: LanguageLinterParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<LanguageLinterParams, ToolResult> {
        return LanguageLinterToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): LanguageLinterParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        return LanguageLinterParams(
            file_path = filePath,
            language = params["language"] as? String,
            strict = (params["strict"] as? Boolean) ?: false
        )
    }
}
