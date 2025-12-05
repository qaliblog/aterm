package com.qali.aterm.agent.utils

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time error monitoring system
 * Monitors code execution output to catch errors as they occur
 */
object ErrorMonitor {
    
    /**
     * Error event
     */
    data class ErrorEvent(
        val errorId: String,
        val errorMessage: String,
        val timestamp: Long = System.currentTimeMillis(),
        val source: String, // "shell", "file_write", "code_execution", etc.
        val severity: ErrorSeverity,
        val filePath: String? = null,
        val lineNumber: Int? = null,
        val rawOutput: String? = null // Original output that contained error
    )
    
    /**
     * Error buffer for capturing partial errors
     */
    private val errorBuffer = ConcurrentLinkedQueue<String>()
    private val maxBufferSize = 50 // Max lines in buffer
    
    /**
     * Detected errors
     */
    private val _detectedErrors = MutableStateFlow<List<ErrorEvent>>(emptyList())
    val detectedErrors: StateFlow<List<ErrorEvent>> = _detectedErrors.asStateFlow()
    
    /**
     * Error aggregation (group similar errors)
     */
    private val errorAggregation = mutableMapOf<String, MutableList<ErrorEvent>>()
    
    /**
     * Monitor shell output for errors
     * 
     * @param output Shell command output (stdout + stderr)
     * @param command Optional command that produced the output
     * @param workspaceRoot Workspace root for file path resolution
     * @return List of detected errors
     */
    fun monitorShellOutput(
        output: String,
        command: String? = null,
        workspaceRoot: String
    ): List<ErrorEvent> {
        val errors = mutableListOf<ErrorEvent>()
        
        // Add to buffer
        output.lines().forEach { line ->
            errorBuffer.offer(line)
            if (errorBuffer.size > maxBufferSize) {
                errorBuffer.poll() // Remove oldest
            }
        }
        
        // Analyze output for errors
        val detected = detectErrorsInOutput(output, workspaceRoot, "shell")
        errors.addAll(detected)
        
        // Aggregate similar errors
        detected.forEach { error ->
            val key = generateErrorKey(error)
            errorAggregation.getOrPut(key) { mutableListOf() }.add(error)
        }
        
        // Update detected errors
        val currentErrors = _detectedErrors.value.toMutableList()
        currentErrors.addAll(errors)
        _detectedErrors.value = currentErrors
        
        return errors
    }
    
    /**
     * Monitor file write operations for errors
     * 
     * @param filePath Path to file being written
     * @param content File content
     * @param workspaceRoot Workspace root
     * @return List of detected errors
     */
    fun monitorFileWrite(
        filePath: String,
        content: String,
        workspaceRoot: String
    ): List<ErrorEvent> {
        // Check for syntax errors in written code
        val errors = mutableListOf<ErrorEvent>()
        
        // Detect language
        val language = ErrorPatternLibrary.detectLanguage(filePath, content)
        val patterns = ErrorPatternLibrary.getPatternsForLanguage(language)
        
        // Check for common syntax errors in content
        val syntaxErrors = detectSyntaxErrors(content, language, filePath)
        errors.addAll(syntaxErrors)
        
        // Check for import/export issues
        val importErrors = detectImportErrors(content, filePath, workspaceRoot)
        errors.addAll(importErrors)
        
        if (errors.isNotEmpty()) {
            val currentErrors = _detectedErrors.value.toMutableList()
            currentErrors.addAll(errors)
            _detectedErrors.value = currentErrors
        }
        
        return errors
    }
    
    /**
     * Detect errors in output text
     */
    private fun detectErrorsInOutput(
        output: String,
        workspaceRoot: String,
        source: String
    ): List<ErrorEvent> {
        val errors = mutableListOf<ErrorEvent>()
        
        // Detect language from output
        val language = ErrorPatternLibrary.detectLanguage("", output)
        val patterns = ErrorPatternLibrary.getPatternsForLanguage(language)
        
        // Try all patterns
        patterns.patterns.forEach { pattern ->
            val matcher = pattern.matcher(output)
            while (matcher.find()) {
                try {
                    val filePath = matcher.group(1)?.trim()
                    val lineNum = matcher.group(2)?.toIntOrNull()
                    val colNum = if (matcher.groupCount() >= 3) matcher.group(3)?.toIntOrNull() else null
                    
                    // Extract error message from context
                    val errorMessage = extractErrorMessageFromContext(output, matcher.start())
                    
                    // Classify severity
                    val severity = ErrorSeverityClassifier.classifySeverity(errorMessage)
                    
                    // Resolve file path
                    val resolvedPath = filePath?.let { 
                        resolveFilePath(it, workspaceRoot) 
                    }
                    
                    val errorId = "error_${System.currentTimeMillis()}_${errors.size}"
                    
                    errors.add(
                        ErrorEvent(
                            errorId = errorId,
                            errorMessage = errorMessage,
                            source = source,
                            severity = severity,
                            filePath = resolvedPath,
                            lineNumber = lineNum,
                            rawOutput = output.substring(matcher.start().coerceAtLeast(0), 
                                matcher.end().coerceAtMost(output.length))
                        )
                    )
                } catch (e: Exception) {
                    Log.w("ErrorMonitor", "Failed to parse error from output: ${e.message}")
                }
            }
        }
        
        return errors.distinctBy { "${it.filePath}:${it.lineNumber}:${it.errorMessage}" }
    }
    
    /**
     * Detect syntax errors in code content
     */
    private fun detectSyntaxErrors(
        content: String,
        language: String,
        filePath: String
    ): List<ErrorEvent> {
        val errors = mutableListOf<ErrorEvent>()
        
        // Basic syntax checks
        when (language) {
            "javascript", "typescript" -> {
                // Check for unmatched brackets
                val openBraces = content.count { it == '{' }
                val closeBraces = content.count { it == '}' }
                if (openBraces != closeBraces) {
                    errors.add(
                        ErrorEvent(
                            errorId = "syntax_${filePath.hashCode()}",
                            errorMessage = "Unmatched braces: $openBraces open, $closeBraces close",
                            source = "file_write",
                            severity = ErrorSeverity.MEDIUM,
                            filePath = filePath
                        )
                    )
                }
                
                // Check for common syntax issues
                if (content.contains("function(") && !content.contains("function (")) {
                    // Potential issue
                }
            }
            "python" -> {
                // Check for indentation issues (basic)
                val lines = content.lines()
                lines.forEachIndexed { index, line ->
                    if (line.trim().startsWith("def ") || line.trim().startsWith("class ")) {
                        val nextLine = lines.getOrNull(index + 1)
                        if (nextLine != null && nextLine.isNotEmpty() && !nextLine.startsWith(" ") && !nextLine.startsWith("\t")) {
                            errors.add(
                                ErrorEvent(
                                    errorId = "syntax_${filePath.hashCode()}_$index",
                                    errorMessage = "Missing indentation after definition at line ${index + 1}",
                                    source = "file_write",
                                    severity = ErrorSeverity.MEDIUM,
                                    filePath = filePath,
                                    lineNumber = index + 1
                                )
                            )
                        }
                    }
                }
            }
        }
        
        return errors
    }
    
    /**
     * Detect import errors in code
     */
    private fun detectImportErrors(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<ErrorEvent> {
        val errors = mutableListOf<ErrorEvent>()
        
        // Extract imports
        val imports = extractImports(content)
        
        // Check if imports can be resolved
        imports.forEach { importPath ->
            // Basic check - would need full module resolution for complete check
            if (importPath.startsWith("./") || importPath.startsWith("../")) {
                val resolved = resolveFilePath(importPath, File(workspaceRoot, filePath).parent)
                if (resolved == null) {
                    errors.add(
                        ErrorEvent(
                            errorId = "import_${filePath.hashCode()}_${importPath.hashCode()}",
                            errorMessage = "Import path may not resolve: $importPath",
                            source = "file_write",
                            severity = ErrorSeverity.HIGH,
                            filePath = filePath
                        )
                    )
                }
            }
        }
        
        return errors
    }
    
    /**
     * Extract error message from context
     */
    private fun extractErrorMessageFromContext(output: String, matchStart: Int): String {
        // Get context around match (50 chars before and after)
        val start = (matchStart - 50).coerceAtLeast(0)
        val end = (matchStart + 200).coerceAtMost(output.length)
        val context = output.substring(start, end)
        
        // Try to extract error message
        val errorPattern = java.util.regex.Pattern.compile("""(?:error|Error|ERROR|exception|Exception):\s*(.+?)(?:\n|$)""", 
            java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcher = errorPattern.matcher(context)
        if (matcher.find()) {
            return matcher.group(1)?.trim() ?: "Error detected"
        }
        
        // Fallback: return match location context
        return context.take(100)
    }
    
    /**
     * Extract imports from code
     */
    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()
        
        // JavaScript/TypeScript imports
        val jsImportPattern = java.util.regex.Pattern.compile("""(?:import|require)\s*\(?['"]([^'"]+)['"]""")
        val jsMatcher = jsImportPattern.matcher(content)
        while (jsMatcher.find()) {
            jsMatcher.group(1)?.let { imports.add(it) }
        }
        
        // Python imports
        val pyImportPattern = java.util.regex.Pattern.compile("""(?:from|import)\s+['"]?([^'"]+)['"]?""")
        val pyMatcher = pyImportPattern.matcher(content)
        while (pyMatcher.find()) {
            pyMatcher.group(1)?.let { imports.add(it) }
        }
        
        return imports.distinct()
    }
    
    /**
     * Resolve file path
     */
    private fun resolveFilePath(path: String, basePath: String?): String? {
        if (basePath == null) return null
        
        return try {
            val baseFile = File(basePath)
            val targetFile = File(baseFile, path)
            if (targetFile.exists()) {
                targetFile.canonicalPath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Generate error key for aggregation
     */
    private fun generateErrorKey(error: ErrorEvent): String {
        return "${error.errorMessage.take(50)}_${error.filePath}_${error.lineNumber}"
    }
    
    /**
     * Get aggregated errors (grouped by similarity)
     */
    fun getAggregatedErrors(): Map<String, List<ErrorEvent>> {
        return errorAggregation.toMap()
    }
    
    /**
     * Clear error buffer and detected errors
     */
    fun clear() {
        errorBuffer.clear()
        _detectedErrors.value = emptyList()
        errorAggregation.clear()
    }
    
    /**
     * Get recent errors (last N)
     */
    fun getRecentErrors(count: Int = 10): List<ErrorEvent> {
        return _detectedErrors.value.takeLast(count)
    }
    
    /**
     * Check if errors were detected
     */
    fun hasErrors(): Boolean {
        return _detectedErrors.value.isNotEmpty()
    }
}
