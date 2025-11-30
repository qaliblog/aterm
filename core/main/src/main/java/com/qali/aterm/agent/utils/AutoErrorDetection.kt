package com.qali.aterm.agent.utils

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.tools.LanguageLinterToolInvocation
import com.qali.aterm.agent.tools.LanguageLinterParams
import com.qali.aterm.agent.tools.LinterError
import com.qali.aterm.agent.tools.ToolResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Automatically detects errors in files after creation/modification
 * and creates tasks to fix them
 */
object AutoErrorDetection {
    
    /**
     * Automatically detect errors in a file after it's been created or modified
     * Returns a list of error fixing tasks if errors are found
     */
    suspend fun detectAndCreateFixTasks(
        filePath: String,
        workspaceRoot: String = alpineDir().absolutePath
    ): List<String> {
        val file = File(workspaceRoot, filePath)
        
        if (!file.exists() || !file.isFile) {
            return emptyList()
        }
        
        // Check if file is a code file that should be linted
        if (!shouldLintFile(file)) {
            return emptyList()
        }
        
        return try {
            val linter = LanguageLinterToolInvocation(
                LanguageLinterParams(
                    file_path = filePath,
                    language = null, // Auto-detect
                    strict = false // Only errors, not warnings
                ),
                workspaceRoot
            )
            
            val result = withContext(Dispatchers.IO) {
                linter.execute(null, null)
            }
            
            // Parse errors from result
            val errors = parseErrorsFromLinterResult(result, file)
            
            if (errors.isNotEmpty()) {
                // Create fix tasks
                createFixTasks(errors, filePath)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("AutoErrorDetection", "Error detecting errors in $filePath: ${e.message}")
            emptyList()
        }
    }
    
    private fun shouldLintFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in listOf(
            "py", "js", "jsx", "ts", "tsx", "kt", "kts", "java", 
            "go", "rs", "cpp", "cc", "cxx", "c", "rb", "php", 
            "swift", "sh", "bash"
        )
    }
    
    private fun parseErrorsFromLinterResult(result: ToolResult, file: File): List<LinterError> {
        val errors = mutableListOf<LinterError>()
        val content = result.llmContent
        
        if (content.contains("No linting errors found") || content.contains("No linting issues found")) {
            return emptyList()
        }
        
        // Parse the formatted output
        val lines = content.lines()
        var currentError: MutableMap<String, String?> = mutableMapOf()
        
        for (line in lines) {
            when {
                line.startsWith("Error ") && line.contains(":") -> {
                    val parts = line.split(":")
                    if (parts.size >= 2) {
                        currentError["type"] = parts[1].trim().uppercase()
                    }
                }
                line.contains("Line:") -> {
                    val linePart = line.substringAfter("Line:")
                    val lineNum = linePart.substringBefore(",").trim().toIntOrNull()
                    currentError["line"] = lineNum?.toString()
                    val colPart = linePart.substringAfter(",", "").trim()
                    if (colPart.startsWith("Column:")) {
                        currentError["column"] = colPart.substringAfter("Column:").trim()
                    }
                }
                line.contains("Message:") -> {
                    currentError["message"] = line.substringAfter("Message:").trim()
                }
                line.contains("Code:") -> {
                    currentError["code"] = line.substringAfter("Code:").trim()
                }
                line.trim().isEmpty() && currentError.isNotEmpty() -> {
                    if (currentError["message"] != null) {
                        errors.add(
                            LinterError(
                                filePath = file.absolutePath,
                                lineNumber = currentError["line"]?.toIntOrNull(),
                                column = currentError["column"]?.toIntOrNull(),
                                message = currentError["message"] ?: "Unknown error",
                                errorType = currentError["type"]?.lowercase() ?: "error",
                                code = currentError["code"]
                            )
                        )
                    }
                    currentError.clear()
                }
            }
        }
        
        // Add last error if exists
        if (currentError.isNotEmpty() && currentError["message"] != null) {
            errors.add(
                LinterError(
                    filePath = file.absolutePath,
                    lineNumber = currentError["line"]?.toIntOrNull(),
                    column = currentError["column"]?.toIntOrNull(),
                    message = currentError["message"] ?: "Unknown error",
                    errorType = currentError["type"]?.lowercase() ?: "error",
                    code = currentError["code"]
                )
            )
        }
        
        return errors
    }
    
    private fun createFixTasks(errors: List<LinterError>, filePath: String): List<String> {
        val tasks = mutableListOf<String>()
        
        // Group errors by type
        val syntaxErrors = errors.filter { it.errorType == "error" && (it.code?.contains("SYNTAX") == true || it.message.contains("syntax", ignoreCase = true)) }
        val otherErrors = errors.filter { it.errorType == "error" && it !in syntaxErrors }
        
        if (syntaxErrors.isNotEmpty()) {
            tasks.add("Fix ${syntaxErrors.size} syntax error(s) in $filePath using syntax_fix tool")
        }
        
        if (otherErrors.isNotEmpty()) {
            val errorSummary = otherErrors.take(3).joinToString(", ") { 
                "line ${it.lineNumber ?: "?"}: ${it.message.take(50)}"
            }
            tasks.add("Fix ${otherErrors.size} linting error(s) in $filePath: $errorSummary")
        }
        
        return tasks
    }
    
    /**
     * Get a formatted message about detected errors that can be added to tool results
     */
    fun formatErrorDetectionMessage(tasks: List<String>): String {
        if (tasks.isEmpty()) {
            return ""
        }
        
        return "\n\n⚠️  Auto-detected ${tasks.size} error(s) after file operation. " +
                "Suggested fix tasks:\n" +
                tasks.joinToString("\n") { "  - $it" } +
                "\n\nConsider running language_linter on this file to see detailed errors, " +
                "then use syntax_fix or manual edits to resolve them."
    }
}
