package com.qali.aterm.agent.utils

import android.util.Log
import java.io.File
import java.util.regex.Pattern

/**
 * Utilities for detecting and parsing errors from stack traces and error messages
 * Helps the agent locate relevant files and lines when debugging
 */
object ErrorDetectionUtils {
    
    /**
     * Parse error message to extract file paths and line numbers
     * Supports various error formats:
     * - JavaScript: "at /path/to/file.js:123:45"
     * - Python: "File \"/path/to/file.py\", line 123"
     * - Java: "at com.example.Class.method(Class.java:123)"
     * - Generic: "Error in file.js:123"
     */
    data class ErrorLocation(
        val filePath: String,
        val lineNumber: Int? = null,
        val columnNumber: Int? = null,
        val functionName: String? = null
    )
    
    /**
     * Extract error locations from error message or stack trace
     */
    fun parseErrorLocations(errorMessage: String, workspaceRoot: String): List<ErrorLocation> {
        val locations = mutableListOf<ErrorLocation>()
        
        // JavaScript/Node.js stack trace patterns
        val jsPatterns = listOf(
            // "at functionName (/path/to/file.js:123:45)"
            Pattern.compile("at\\s+(?:[^\\s]+\\s+)?\\(?([^:]+):(\\d+):(\\d+)\\)?"),
            // "at /path/to/file.js:123:45"
            Pattern.compile("at\\s+([^:]+):(\\d+):(\\d+)"),
            // "Error: message\n    at file.js:123"
            Pattern.compile("at\\s+([^\\s]+):(\\d+)"),
        )
        
        // Python stack trace patterns
        val pythonPatterns = listOf(
            // "File \"/path/to/file.py\", line 123"
            Pattern.compile("File\\s+[\"']([^\"']+)[\"'],\\s*line\\s+(\\d+)"),
            // "  File \"/path/to/file.py\", line 123, in function"
            Pattern.compile("File\\s+[\"']([^\"']+)[\"'],\\s*line\\s+(\\d+)(?:,\\s*in\\s+(\\w+))?"),
        )
        
        // Java/Kotlin stack trace patterns
        val javaPatterns = listOf(
            // "at com.example.Class.method(Class.java:123)"
            Pattern.compile("at\\s+[^\\s]+\\(([^:]+):(\\d+)\\)"),
            // "Caused by: ... at Class.java:123"
            Pattern.compile("at\\s+[^\\s]+\\(([^:]+):(\\d+)\\)"),
        )
        
        // Generic patterns
        val genericPatterns = listOf(
            // "Error in file.js:123"
            Pattern.compile("(?:error|Error|ERROR)\\s+(?:in|at|on)\\s+([^:]+):(\\d+)"),
            // "file.js:123:45"
            Pattern.compile("([^\\s]+):(\\d+)(?::(\\d+))?"),
        )
        
        // Try all patterns
        val allPatterns = jsPatterns + pythonPatterns + javaPatterns + genericPatterns
        
        for (pattern in allPatterns) {
            val matcher = pattern.matcher(errorMessage)
            while (matcher.find()) {
                try {
                    val filePath = matcher.group(1)?.trim() ?: continue
                    val lineNum = matcher.group(2)?.toIntOrNull()
                    val colNum = matcher.group(3)?.toIntOrNull()
                    val funcName = if (matcher.groupCount() >= 4) matcher.group(4) else null
                    
                    // Resolve relative paths
                    val resolvedPath = resolveFilePath(filePath, workspaceRoot)
                    if (resolvedPath != null) {
                        locations.add(ErrorLocation(
                            filePath = resolvedPath,
                            lineNumber = lineNum,
                            columnNumber = colNum,
                            functionName = funcName
                        ))
                    }
                } catch (e: Exception) {
                    Log.w("ErrorDetectionUtils", "Failed to parse error location: ${e.message}")
                }
            }
        }
        
        return locations.distinctBy { "${it.filePath}:${it.lineNumber}" }
    }
    
    /**
     * Resolve file path from error message to actual file in workspace
     */
    private fun resolveFilePath(pathFromError: String, workspaceRoot: String): String? {
        val workspaceDir = File(workspaceRoot)
        
        // Remove quotes if present
        val cleanPath = pathFromError.trim().removeSurrounding("\"").removeSurrounding("'")
        
        // Try as absolute path first
        val absoluteFile = File(cleanPath)
        if (absoluteFile.exists() && absoluteFile.isFile) {
            try {
                val relative = absoluteFile.relativeTo(workspaceDir).path.replace("\\", "/")
                return relative
            } catch (e: Exception) {
                // File is outside workspace, use as-is
                return cleanPath
            }
        }
        
        // Try as relative path from workspace root
        val relativeFile = File(workspaceDir, cleanPath)
        if (relativeFile.exists() && relativeFile.isFile) {
            return cleanPath.replace("\\", "/")
        }
        
        // Try to find file by name only (last component)
        val fileName = File(cleanPath).name
        workspaceDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name == fileName) {
                try {
                    val relative = file.relativeTo(workspaceDir).path.replace("\\", "/")
                    // Don't return files in ignored directories
                    if (!AtermIgnoreManager.shouldIgnoreFile(file, workspaceRoot)) {
                        return relative
                    }
                } catch (e: Exception) {
                    // Skip
                }
            }
        }
        
        return null
    }
    
    /**
     * Detect common API mismatches from error messages
     * e.g., "db.execute is not a function" suggests SQLite vs MySQL API mismatch
     */
    data class ApiMismatch(
        val errorType: String,
        val suggestedFix: String,
        val affectedFiles: List<String>
    )
    
    fun detectApiMismatch(errorMessage: String): ApiMismatch? {
        val lowerError = errorMessage.lowercase()
        
        // SQLite vs MySQL detection
        if (lowerError.contains("execute") && lowerError.contains("not a function")) {
            return ApiMismatch(
                errorType = "SQLite API Mismatch",
                suggestedFix = "SQLite uses db.all(), db.get(), db.run() instead of db.execute(). Check database.js for correct API usage.",
                affectedFiles = listOf("database.js", "db.js", "routes/", "controllers/")
            )
        }
        
        if (lowerError.contains("query") && lowerError.contains("not a function")) {
            return ApiMismatch(
                errorType = "Database API Mismatch",
                suggestedFix = "Check if using correct database library API. SQLite uses different methods than MySQL/PostgreSQL.",
                affectedFiles = listOf("database.js", "db.js")
            )
        }
        
        // Promise vs callback detection
        if (lowerError.contains("cannot read property") && lowerError.contains("then")) {
            return ApiMismatch(
                errorType = "Promise/Callback Mismatch",
                suggestedFix = "Function may return a callback instead of a Promise. Use callback pattern or promisify the function.",
                affectedFiles = emptyList()
            )
        }
        
        return null
    }
    
    /**
     * Get related files for debugging based on error location
     * Returns files that are likely related to the error (routes, controllers, models, etc.)
     */
    fun getRelatedFilesForError(errorLocation: ErrorLocation, workspaceRoot: String): List<String> {
        val relatedFiles = mutableListOf<String>()
        val workspaceDir = File(workspaceRoot)
        
        // If error is in a route file, also check controllers and models
        if (errorLocation.filePath.contains("route", ignoreCase = true) ||
            errorLocation.filePath.contains("api", ignoreCase = true)) {
            relatedFiles.addAll(findFilesByPattern(workspaceDir, listOf(
                "controllers/", "models/", "database.js", "db.js", "config.js"
            )))
        }
        
        // If error is in database file, check routes and models
        if (errorLocation.filePath.contains("database", ignoreCase = true) ||
            errorLocation.filePath.contains("db", ignoreCase = true)) {
            relatedFiles.addAll(findFilesByPattern(workspaceDir, listOf(
                "routes/", "models/", "server.js", "app.js"
            )))
        }
        
        // If error is in a model, check database and routes
        if (errorLocation.filePath.contains("model", ignoreCase = true)) {
            relatedFiles.addAll(findFilesByPattern(workspaceDir, listOf(
                "database.js", "db.js", "routes/", "controllers/"
            )))
        }
        
        return relatedFiles.distinct().filter { file ->
            val fileObj = File(workspaceDir, file)
            fileObj.exists() && !AtermIgnoreManager.shouldIgnoreFile(fileObj, workspaceRoot)
        }
    }
    
    private fun findFilesByPattern(workspaceDir: File, patterns: List<String>): List<String> {
        val files = mutableListOf<String>()
        
        for (pattern in patterns) {
            if (pattern.endsWith("/")) {
                // Directory pattern
                val dir = File(workspaceDir, pattern.removeSuffix("/"))
                if (dir.exists() && dir.isDirectory) {
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile && !AtermIgnoreManager.shouldIgnoreFile(file, workspaceDir.absolutePath)) {
                            try {
                                files.add(file.relativeTo(workspaceDir).path.replace("\\", "/"))
                            } catch (e: Exception) {
                                // Skip
                            }
                        }
                    }
                }
            } else {
                // File pattern
                val file = File(workspaceDir, pattern)
                if (file.exists() && file.isFile) {
                    try {
                        files.add(file.relativeTo(workspaceDir).path.replace("\\", "/"))
                    } catch (e: Exception) {
                        // Skip
                    }
                }
            }
        }
        
        return files
    }
}
