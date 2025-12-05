package com.qali.aterm.agent.utils

import android.util.Log
import java.io.File

/**
 * Manages .atermignore file for excluding files and directories from agent scanning
 * Similar to .gitignore but specifically for aTerm agent operations
 */
object AtermIgnoreManager {
    
    private const val ATERM_IGNORE_FILE = ".atermignore"
    
    /**
     * Default ignore patterns for aTerm
     * These are automatically included even if .atermignore doesn't exist
     */
    private val DEFAULT_IGNORE_PATTERNS = listOf(
        // Dependencies and build artifacts
        "node_modules/",
        ".npm/",
        ".cache/",
        "build/",
        "dist/",
        "out/",
        ".gradle/",
        "target/",
        "__pycache__/",
        ".pytest_cache/",
        ".venv/",
        "venv/",
        
        // Version control
        ".git/",
        ".svn/",
        ".hg/",
        
        // IDE and editor files
        ".vscode/",
        ".idea/",
        ".vs/",
        "*.swp",
        "*.swo",
        "*~",
        ".DS_Store",
        
        // Logs and temporary files
        "*.log",
        "*.tmp",
        "*.temp",
        ".tmp/",
        
        // OS files
        "Thumbs.db",
        ".DS_Store",
        
        // Compiled files
        "*.class",
        "*.jar",
        "*.war",
        "*.pyc",
        "*.pyo",
        "*.o",
        "*.so",
        "*.dll",
        "*.exe",
        
        // Coverage and test artifacts
        "coverage/",
        ".nyc_output/",
        ".coverage/",
        "htmlcov/",
        
        // Package manager files
        "package-lock.json",
        "yarn.lock",
        "pnpm-lock.yaml"
    )
    
    /**
     * Check if a file or directory should be ignored
     * @param filePath Relative path from workspace root
     * @param workspaceRoot Absolute path to workspace root
     * @return true if the path should be ignored
     */
    fun shouldIgnore(filePath: String, workspaceRoot: String): Boolean {
        val ignorePatterns = loadIgnorePatterns(workspaceRoot)
        val normalizedPath = filePath.replace("\\", "/")
        
        // Check against all patterns
        return ignorePatterns.any { pattern ->
            matchesPattern(normalizedPath, pattern)
        }
    }
    
    /**
     * Check if a file should be ignored (absolute path version)
     */
    fun shouldIgnoreFile(file: File, workspaceRoot: String): Boolean {
        val relativePath = try {
            file.relativeTo(File(workspaceRoot)).path.replace("\\", "/")
        } catch (e: Exception) {
            // If file is not under workspace, check by name
            file.name
        }
        return shouldIgnore(relativePath, workspaceRoot)
    }
    
    /**
     * Load ignore patterns from .atermignore file and merge with defaults
     */
    fun loadIgnorePatterns(workspaceRoot: String): List<String> {
        val patterns = mutableListOf<String>()
        
        // Add default patterns first
        patterns.addAll(DEFAULT_IGNORE_PATTERNS)
        
        // Load from .atermignore if it exists
        val atermIgnoreFile = File(workspaceRoot, ATERM_IGNORE_FILE)
        if (atermIgnoreFile.exists() && atermIgnoreFile.isFile) {
            try {
                atermIgnoreFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    // Skip empty lines and comments
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        patterns.add(trimmed)
                    }
                }
                Log.d("AtermIgnoreManager", "Loaded ${patterns.size - DEFAULT_IGNORE_PATTERNS.size} patterns from .atermignore")
            } catch (e: Exception) {
                Log.w("AtermIgnoreManager", "Failed to read .atermignore: ${e.message}")
            }
        }
        
        return patterns.distinct()
    }
    
    /**
     * Check if a path matches an ignore pattern.
     * Supports exact matches, directory matches, wildcards, and path patterns.
     */
    private fun matchesPattern(path: String, pattern: String): Boolean {
        val normalizedPattern = pattern.trim()
        
        // Handle directory patterns (ending with /)
        val isDirectoryPattern = normalizedPattern.endsWith("/")
        val patternWithoutSlash = normalizedPattern.removeSuffix("/")
        
        // Handle wildcards
        if (normalizedPattern.contains("*")) {
            val regex = normalizedPattern
                .replace(".", "\\.")
                .replace("**", "___DOUBLE_STAR___")
                .replace("*", "[^/]*")
                .replace("___DOUBLE_STAR___", ".*")
                .toRegex()
            
            // Check if pattern matches path or any segment
            if (regex.matches(path)) return true
            
            // For directory patterns, also check if any parent matches
            if (isDirectoryPattern) {
                val pathSegments = path.split("/")
                for (i in pathSegments.indices) {
                    val subPath = pathSegments.subList(0, i + 1).joinToString("/")
                    if (regex.matches(subPath)) return true
                }
            }
            
            return false
        }
        
        // Exact match
        if (path == normalizedPattern || path == patternWithoutSlash) return true
        
        // Directory match (pattern ends with /)
        if (isDirectoryPattern) {
            // Check if path starts with pattern
            if (path.startsWith(patternWithoutSlash + "/") || 
                path == patternWithoutSlash) {
                return true
            }
        }
        
        // Check if path contains pattern as a segment
        val pathSegments = path.split("/")
        if (pathSegments.contains(normalizedPattern) || 
            pathSegments.contains(patternWithoutSlash)) {
            return true
        }
        
        // Check if any parent directory matches
        if (isDirectoryPattern) {
            for (i in pathSegments.indices) {
                val parentPath = pathSegments.subList(0, i + 1).joinToString("/")
                if (parentPath == patternWithoutSlash || 
                    parentPath.startsWith(patternWithoutSlash + "/")) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * Create default .atermignore file in workspace if it doesn't exist
     */
    fun createDefaultAtermIgnore(workspaceRoot: String): Boolean {
        val atermIgnoreFile = File(workspaceRoot, ATERM_IGNORE_FILE)
        
        if (atermIgnoreFile.exists()) {
            return false // Already exists
        }
        
        return try {
            val content = buildString {
                appendLine("# aTerm ignore patterns")
                appendLine("# Files and directories matching these patterns will be excluded from agent scanning")
                appendLine("# This file is similar to .gitignore but specific to aTerm agent operations")
                appendLine()
                appendLine("# Dependencies")
                appendLine("node_modules/")
                appendLine(".npm/")
                appendLine(".cache/")
                appendLine()
                appendLine("# Build artifacts")
                appendLine("build/")
                appendLine("dist/")
                appendLine("out/")
                appendLine(".gradle/")
                appendLine("target/")
                appendLine()
                appendLine("# Version control")
                appendLine(".git/")
                appendLine()
                appendLine("# IDE files")
                appendLine(".vscode/")
                appendLine(".idea/")
                appendLine()
                appendLine("# Logs")
                appendLine("*.log")
                appendLine()
                appendLine("# Add your custom ignore patterns below")
            }
            
            atermIgnoreFile.writeText(content)
            Log.d("AtermIgnoreManager", "Created default .atermignore file")
            true
        } catch (e: Exception) {
            Log.e("AtermIgnoreManager", "Failed to create .atermignore: ${e.message}")
            false
        }
    }
    
    /**
     * Get priority files for a project type
     * These are files that should be read first when analyzing a project
     */
    fun getPriorityFiles(workspaceRoot: String, projectType: String? = null): List<String> {
        val priorityFiles = mutableListOf<String>()
        val workspaceDir = File(workspaceRoot)
        
        if (!workspaceDir.exists()) return emptyList()
        
        // Common priority files (framework-agnostic)
        val commonPriority = listOf(
            "package.json",
            "server.js",
            "app.js",
            "index.js",
            "main.js",
            "config.js",
            "database.js",
            "db.js",
            "routes.js",
            "app.py",
            "main.py",
            "requirements.txt",
            "README.md"
        )
        
        // Node.js specific
        if (projectType == null || projectType.contains("node", ignoreCase = true)) {
            priorityFiles.addAll(listOf(
                "package.json",
                "server.js",
                "app.js",
                "index.js",
                "routes/",
                "controllers/",
                "models/",
                "database.js",
                "db.js",
                "config.js"
            ))
        }
        
        // Python specific
        if (projectType?.contains("python", ignoreCase = true) == true) {
            priorityFiles.addAll(listOf(
                "requirements.txt",
                "app.py",
                "main.py",
                "config.py",
                "settings.py"
            ))
        }
        
        // Check which files actually exist
        return priorityFiles.filter { pattern ->
            val file = File(workspaceDir, pattern)
            file.exists() && !shouldIgnoreFile(file, workspaceRoot)
        }
    }
}
