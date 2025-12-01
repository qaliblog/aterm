package com.qali.aterm.agent.utils

import java.io.File
import android.util.Log

/**
 * Analyzes code files to extract imports, exports, and build dependency relationships.
 * Maintains a matrix of file relativeness to ensure code coherence.
 */
object CodeDependencyAnalyzer {
    
    data class CodeMetadata(
        val filePath: String,
        val imports: List<String> = emptyList(),
        val exports: List<String> = emptyList(),
        val classes: List<String> = emptyList(),
        val functions: List<String> = emptyList(),
        val language: String = "unknown"
    )
    
    data class DependencyMatrix(
        val files: Map<String, CodeMetadata> = emptyMap(),
        val dependencies: Map<String, Set<String>> = emptyMap() // file -> set of files it depends on
    )
    
    private val dependencyMatrix = mutableMapOf<String, DependencyMatrix>()
    private val workspaceMatrices = mutableMapOf<String, MutableMap<String, CodeMetadata>>()
    
    /**
     * Analyzes a code file and extracts important metadata (imports, exports, functions, classes)
     */
    fun analyzeFile(filePath: String, content: String, workspaceRoot: String): CodeMetadata {
        val language = detectLanguage(filePath)
        val normalizedPath = normalizePath(filePath, workspaceRoot)
        
        return when (language) {
            "javascript", "typescript" -> analyzeJavaScript(content, normalizedPath)
            "python" -> analyzePython(content, normalizedPath)
            "java", "kotlin" -> analyzeJavaKotlin(content, normalizedPath)
            else -> CodeMetadata(filePath = normalizedPath, language = language)
        }
    }
    
    /**
     * Updates the dependency matrix for a workspace
     */
    fun updateDependencyMatrix(workspaceRoot: String, metadata: CodeMetadata) {
        val normalizedRoot = File(workspaceRoot).canonicalPath
        val matrix = workspaceMatrices.getOrPut(normalizedRoot) { mutableMapOf() }
        matrix[metadata.filePath] = metadata
        
        // Rebuild dependencies
        val dependencies = mutableMapOf<String, MutableSet<String>>()
        matrix.values.forEach { fileMeta ->
            val deps = mutableSetOf<String>()
            fileMeta.imports.forEach { importPath ->
                // Find which file this import refers to
                val targetFile = findFileForImport(importPath, fileMeta.filePath, matrix.keys)
                if (targetFile != null) {
                    deps.add(targetFile)
                }
            }
            dependencies[fileMeta.filePath] = deps
        }
        
        dependencyMatrix[normalizedRoot] = DependencyMatrix(
            files = matrix.toMap(),
            dependencies = dependencies.mapValues { it.value.toSet() }
        )
    }
    
    /**
     * Gets the dependency matrix for a workspace
     */
    fun getDependencyMatrix(workspaceRoot: String): DependencyMatrix {
        val normalizedRoot = File(workspaceRoot).canonicalPath
        return dependencyMatrix[normalizedRoot] ?: DependencyMatrix()
    }
    
    /**
     * Gets code metadata summary for chat history (important chunks)
     */
    fun getCodeSummaryForHistory(metadata: CodeMetadata): String {
        val parts = mutableListOf<String>()
        
        if (metadata.imports.isNotEmpty()) {
            parts.add("Imports: ${metadata.imports.joinToString(", ")}")
        }
        if (metadata.exports.isNotEmpty()) {
            parts.add("Exports: ${metadata.exports.joinToString(", ")}")
        }
        if (metadata.classes.isNotEmpty()) {
            parts.add("Classes: ${metadata.classes.joinToString(", ")}")
        }
        if (metadata.functions.isNotEmpty()) {
            parts.add("Functions: ${metadata.functions.take(10).joinToString(", ")}${if (metadata.functions.size > 10) "..." else ""}")
        }
        
        return if (parts.isNotEmpty()) {
            "[Code Structure: ${metadata.filePath}]\n${parts.joinToString("\n")}"
        } else {
            ""
        }
    }
    
    /**
     * Gets relativeness summary - which files are related to the given file
     */
    fun getRelativenessSummary(filePath: String, workspaceRoot: String): String {
        val matrix = getDependencyMatrix(workspaceRoot)
        val normalizedPath = normalizePath(filePath, workspaceRoot)
        
        val relatedFiles = mutableSetOf<String>()
        
        // Files that this file depends on
        matrix.dependencies[normalizedPath]?.forEach { relatedFiles.add(it) }
        
        // Files that depend on this file
        matrix.dependencies.forEach { (depFile, deps) ->
            if (deps.contains(normalizedPath)) {
                relatedFiles.add(depFile)
            }
        }
        
        if (relatedFiles.isEmpty()) {
            return ""
        }
        
        return "[File Relativeness: $normalizedPath]\nRelated files: ${relatedFiles.joinToString(", ")}"
    }
    
    private fun detectLanguage(filePath: String): String {
        return when {
            filePath.endsWith(".js") || filePath.endsWith(".mjs") -> "javascript"
            filePath.endsWith(".ts") || filePath.endsWith(".tsx") -> "typescript"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".kt") -> "kotlin"
            else -> "unknown"
        }
    }
    
    private fun analyzeJavaScript(content: String, filePath: String): CodeMetadata {
        val imports = mutableListOf<String>()
        val exports = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val classes = mutableListOf<String>()
        
        val lines = content.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Extract imports: import ... from '...' or require('...')
            when {
                trimmed.startsWith("import ") && "'" in trimmed -> {
                    val match = Regex("import\\s+.*?\\s+from\\s+['\"]([^'\"]+)['\"]").find(trimmed)
                    match?.groupValues?.get(1)?.let { imports.add(it) }
                }
                trimmed.startsWith("import ") && trimmed.contains("require") -> {
                    val match = Regex("require\\(['\"]([^'\"]+)['\"]\\)").find(trimmed)
                    match?.groupValues?.get(1)?.let { imports.add(it) }
                }
                trimmed.contains("require(") -> {
                    val match = Regex("require\\(['\"]([^'\"]+)['\"]\\)").find(trimmed)
                    match?.groupValues?.get(1)?.let { imports.add(it) }
                }
            }
            
            // Extract exports: export ... or module.exports
            when {
                trimmed.startsWith("export ") -> {
                    val match = Regex("export\\s+(?:default\\s+)?(?:function|class|const|let|var)?\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)").find(trimmed)
                    match?.groupValues?.get(1)?.let { exports.add(it) }
                }
                trimmed.contains("module.exports") || trimmed.contains("exports.") -> {
                    val match = Regex("(?:module\\.)?exports\\.?([a-zA-Z_$][a-zA-Z0-9_$]*)").find(trimmed)
                    match?.groupValues?.get(1)?.let { if (it.isNotEmpty()) exports.add(it) else exports.add("default") }
                }
            }
            
            // Extract function declarations
            Regex("(?:export\\s+)?(?:async\\s+)?function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)").find(trimmed)?.groupValues?.get(1)?.let { functions.add(it) }
            Regex("(?:export\\s+)?const\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*(?:async\\s+)?\\(|=>").find(trimmed)?.groupValues?.get(1)?.let { functions.add(it) }
            
            // Extract class declarations
            Regex("(?:export\\s+)?class\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)").find(trimmed)?.groupValues?.get(1)?.let { classes.add(it) }
        }
        
        return CodeMetadata(
            filePath = filePath,
            imports = imports.distinct(),
            exports = exports.distinct(),
            classes = classes.distinct(),
            functions = functions.distinct(),
            language = "javascript"
        )
    }
    
    private fun analyzePython(content: String, filePath: String): CodeMetadata {
        val imports = mutableListOf<String>()
        val exports = mutableListOf<String>()
        val functions = mutableListOf<String>()
        val classes = mutableListOf<String>()
        
        val lines = content.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Extract imports: import ... or from ... import ...
            when {
                trimmed.startsWith("import ") -> {
                    val match = Regex("import\\s+([a-zA-Z0-9_.]+)").find(trimmed)
                    match?.groupValues?.get(1)?.let { imports.add(it) }
                }
                trimmed.startsWith("from ") -> {
                    val match = Regex("from\\s+([a-zA-Z0-9_.]+)\\s+import").find(trimmed)
                    match?.groupValues?.get(1)?.let { imports.add(it) }
                }
            }
            
            // Extract function definitions
            Regex("def\\s+([a-zA-Z_][a-zA-Z0-9_]*)").find(trimmed)?.groupValues?.get(1)?.let { functions.add(it) }
            
            // Extract class definitions
            Regex("class\\s+([a-zA-Z_][a-zA-Z0-9_]*)").find(trimmed)?.groupValues?.get(1)?.let { classes.add(it) }
        }
        
        // Python exports are typically __all__ or what's imported from the module
        exports.addAll(classes)
        exports.addAll(functions)
        
        return CodeMetadata(
            filePath = filePath,
            imports = imports.distinct(),
            exports = exports.distinct(),
            classes = classes.distinct(),
            functions = functions.distinct(),
            language = "python"
        )
    }
    
    private fun analyzeJavaKotlin(content: String, filePath: String): CodeMetadata {
        val imports = mutableListOf<String>()
        val classes = mutableListOf<String>()
        val functions = mutableListOf<String>()
        
        val lines = content.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Extract imports
            Regex("import\\s+(?:static\\s+)?([a-zA-Z0-9_.*]+)").find(trimmed)?.groupValues?.get(1)?.let { imports.add(it) }
            
            // Extract class/interface declarations
            Regex("(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?(?:class|interface|enum)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)").find(trimmed)?.groupValues?.get(1)?.let { classes.add(it) }
            
            // Extract function/method declarations
            Regex("(?:public|private|protected)?\\s*(?:static\\s+)?(?:fun\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(").find(trimmed)?.groupValues?.get(1)?.let { functions.add(it) }
        }
        
        return CodeMetadata(
            filePath = filePath,
            imports = imports.distinct(),
            exports = classes, // In Java/Kotlin, public classes are the exports
            classes = classes.distinct(),
            functions = functions.distinct(),
            language = if (filePath.endsWith(".kt")) "kotlin" else "java"
        )
    }
    
    private fun normalizePath(filePath: String, workspaceRoot: String): String {
        return try {
            val file = File(workspaceRoot, filePath)
            file.canonicalPath.removePrefix(File(workspaceRoot).canonicalPath + File.separator)
        } catch (e: Exception) {
            filePath
        }
    }
    
    private fun findFileForImport(importPath: String, fromFile: String, availableFiles: Set<String>): String? {
        // Try to match import path to actual file paths
        // This is a simplified matching - could be improved with proper module resolution
        
        val importName = importPath.replace(".", File.separator)
        val importNameWithExt = listOf("$importName.js", "$importName.ts", "$importName.py", "$importName/index.js")
        
        for (file in availableFiles) {
            if (file.endsWith(importName) || importNameWithExt.any { file.endsWith(it) }) {
                return file
            }
            // Also check if file name matches
            val fileName = File(file).nameWithoutExtension
            if (importPath.endsWith(fileName) || importPath.contains(fileName)) {
                return file
            }
        }
        
        return null
    }
    
    /**
     * Generate a code coherence blueprint - file names, locations, functions, imports, exports
     * This is used to enforce code coherence when writing files
     */
    fun generateCoherenceBlueprint(workspaceRoot: String): String {
        val matrix = getDependencyMatrix(workspaceRoot)
        if (matrix.files.isEmpty()) {
            return ""
        }
        
        return buildString {
            appendLine("## Code Dependency Matrix Blueprint")
            appendLine()
            appendLine("**IMPORTANT:** When writing code, you MUST use ONLY the imports, exports, functions, and classes listed below. Do NOT add new imports or use names that don't exist in this matrix.")
            appendLine()
            
            matrix.files.forEach { (filePath, metadata) ->
                appendLine("### File: $filePath")
                if (metadata.imports.isNotEmpty()) {
                    appendLine("  - **Imports:** ${metadata.imports.joinToString(", ")}")
                }
                if (metadata.exports.isNotEmpty()) {
                    appendLine("  - **Exports:** ${metadata.exports.joinToString(", ")}")
                }
                if (metadata.functions.isNotEmpty()) {
                    appendLine("  - **Functions:** ${metadata.functions.joinToString(", ")}")
                }
                if (metadata.classes.isNotEmpty()) {
                    appendLine("  - **Classes:** ${metadata.classes.joinToString(", ")}")
                }
                appendLine()
            }
            
            appendLine("**Rules:**")
            appendLine("- Only use imports that exist in the files listed above")
            appendLine("- Only use function/class names that are exported from related files")
            appendLine("- Do NOT create new imports or exports unless absolutely necessary")
            appendLine("- Check the relativeness information to see which files are related")
        }
    }
    
    /**
     * Generate comprehensive blueprint by analyzing all files in workspace
     * This should be called first to build the complete dependency matrix
     */
    fun generateComprehensiveBlueprint(workspaceRoot: String): String {
        val matrix = getDependencyMatrix(workspaceRoot)
        
        return buildString {
            appendLine("## Code Dependency Matrix Blueprint - Complete Project Analysis")
            appendLine()
            appendLine("**PHASE 1: Blueprint Generation**")
            appendLine("This blueprint contains all file names, locations, functions, imports, and exports from existing files.")
            appendLine("Use this to maintain code coherence when writing new files.")
            appendLine()
            
            if (matrix.files.isEmpty()) {
                appendLine("**No existing files found.** This is a new project. You should:")
                appendLine("1. First, suggest the file structure (file names, locations, and order to write them)")
                appendLine("2. Suggest which files should be referenced/tagged when writing each main file")
                appendLine("3. Then write files one by one, each with a separate prompt using this blueprint")
            } else {
                appendLine("**Existing Files Analysis:**")
                appendLine()
                
                matrix.files.forEach { (filePath, metadata) ->
                    appendLine("### File: $filePath")
                    appendLine("  - **Language:** ${metadata.language}")
                    if (metadata.imports.isNotEmpty()) {
                        appendLine("  - **Imports:** ${metadata.imports.joinToString(", ")}")
                    }
                    if (metadata.exports.isNotEmpty()) {
                        appendLine("  - **Exports:** ${metadata.exports.joinToString(", ")}")
                    }
                    if (metadata.functions.isNotEmpty()) {
                        appendLine("  - **Functions:** ${metadata.functions.joinToString(", ")}")
                    }
                    if (metadata.classes.isNotEmpty()) {
                        appendLine("  - **Classes:** ${metadata.classes.joinToString(", ")}")
                    }
                    
                    // Show dependencies
                    val deps = matrix.dependencies[filePath]
                    if (deps != null && deps.isNotEmpty()) {
                        appendLine("  - **Depends on:** ${deps.joinToString(", ")}")
                    }
                    
                    // Show dependents
                    val dependents = matrix.dependencies.filter { it.value.contains(filePath) }.keys
                    if (dependents.isNotEmpty()) {
                        appendLine("  - **Used by:** ${dependents.joinToString(", ")}")
                    }
                    
                    appendLine()
                }
                
                appendLine("**Dependency Graph:**")
                matrix.dependencies.forEach { (file, deps) ->
                    if (deps.isNotEmpty()) {
                        appendLine("  - $file → ${deps.joinToString(", ")}")
                    }
                }
                appendLine()
            }
            
            appendLine("**Instructions for AI:**")
            appendLine("1. After reviewing this blueprint, suggest:")
            appendLine("   - File names and locations in the best format and order to write them")
            appendLine("   - Which files should be referenced/tagged when writing each main file for better code coherence")
            appendLine("2. Then write files one by one, each with a separate prompt")
            appendLine("3. Each file writing prompt should include:")
            appendLine("   - The blueprint metadata for that specific file")
            appendLine("   - Related files and their exports/functions/classes")
            appendLine("   - Instructions to use ONLY the names/imports from the blueprint")
        }
    }
    
    /**
     * Generate file writing plan with suggested order and related files
     */
    fun generateFileWritingPlan(targetFiles: List<String>, workspaceRoot: String): String {
        val matrix = getDependencyMatrix(workspaceRoot)
        
        return buildString {
            appendLine("## File Writing Plan")
            appendLine()
            appendLine("**Suggested File Order (based on dependencies):**")
            
            // Sort files by dependency order (files with no dependencies first)
            val sortedFiles = targetFiles.sortedBy { filePath ->
                val deps = matrix.dependencies[filePath]?.size ?: 0
                deps
            }
            
            sortedFiles.forEachIndexed { index, filePath ->
                val metadata = matrix.files[filePath]
                val deps = matrix.dependencies[filePath] ?: emptySet()
                val dependents = matrix.dependencies.filter { it.value.contains(filePath) }.keys
                
                appendLine("${index + 1}. **$filePath**")
                if (deps.isNotEmpty()) {
                    appendLine("   - Depends on: ${deps.joinToString(", ")}")
                    appendLine("   - **Suggested files to reference:** ${deps.joinToString(", ")}")
                } else {
                    appendLine("   - No dependencies (can be written first)")
                }
                if (dependents.isNotEmpty()) {
                    appendLine("   - Will be used by: ${dependents.joinToString(", ")}")
                }
                if (metadata != null) {
                    if (metadata.exports.isNotEmpty()) {
                        appendLine("   - Exports: ${metadata.exports.joinToString(", ")}")
                    }
                }
                appendLine()
            }
        }
    }
    
    /**
     * Generate a coherence constraint prompt for a specific file being written
     * This enforces using only names/imports from the dependency matrix
     */
    fun generateCoherenceConstraintForFile(filePath: String, workspaceRoot: String): String {
        val matrix = getDependencyMatrix(workspaceRoot)
        val normalizedPath = normalizePath(filePath, workspaceRoot)
        
        // Get related files (dependencies and dependents)
        val relatedFiles = mutableSetOf<String>()
        matrix.dependencies[normalizedPath]?.forEach { relatedFiles.add(it) }
        matrix.dependencies.forEach { (depFile, deps) ->
            if (deps.contains(normalizedPath)) {
                relatedFiles.add(depFile)
            }
        }
        
        if (relatedFiles.isEmpty() && matrix.files.isEmpty()) {
            return ""
        }
        
        return buildString {
            appendLine("## Code Coherence Constraints for: $normalizedPath")
            appendLine()
            
            if (relatedFiles.isNotEmpty()) {
                appendLine("**Related Files:** ${relatedFiles.joinToString(", ")}")
                appendLine()
                appendLine("**Available Imports/Exports from Related Files:**")
                relatedFiles.forEach { relatedFile ->
                    val metadata = matrix.files[relatedFile]
                    if (metadata != null) {
                        appendLine("  - **$relatedFile:**")
                        if (metadata.exports.isNotEmpty()) {
                            appendLine("    - Exports: ${metadata.exports.joinToString(", ")}")
                        }
                        if (metadata.functions.isNotEmpty()) {
                            appendLine("    - Functions: ${metadata.functions.joinToString(", ")}")
                        }
                        if (metadata.classes.isNotEmpty()) {
                            appendLine("    - Classes: ${metadata.classes.joinToString(", ")}")
                        }
                    }
                }
                appendLine()
            }
            
            appendLine("**CRITICAL RULES:**")
            appendLine("- Use ONLY the imports/exports/functions/classes listed above")
            appendLine("- Do NOT add new imports that don't exist in related files")
            appendLine("- Do NOT use function/class names that aren't exported from related files")
            appendLine("- If you need something that doesn't exist, create it first, then use it")
            appendLine("- Maintain code coherence by following the dependency matrix")
            
            // Add suggested files to reference
            if (relatedFiles.isNotEmpty()) {
                appendLine()
                appendLine("**Suggested Files to Reference/Tag:**")
                appendLine("When writing this file, reference these related files to ensure coherence:")
                relatedFiles.forEach { relatedFile ->
                    appendLine("  - $relatedFile")
                }
            }
        }
    }
    
    /**
     * Scan all code files in workspace and build comprehensive blueprint
     * This should be called before writing files to analyze existing codebase
     */
    fun scanAndBuildBlueprint(workspaceRoot: String): String {
        val file = java.io.File(workspaceRoot)
        if (!file.exists() || !file.isDirectory) {
            return "Workspace root does not exist or is not a directory: $workspaceRoot"
        }
        
        val codeFiles = mutableListOf<Pair<String, String>>()
        
        // Scan for code files
        file.walkTopDown().forEach { fileEntry ->
            if (fileEntry.isFile) {
                val fileName = fileEntry.name
                val relativePath = fileEntry.relativeTo(file).path
                
                when {
                    fileName.endsWith(".js") || fileName.endsWith(".mjs") ||
                    fileName.endsWith(".ts") || fileName.endsWith(".tsx") ||
                    fileName.endsWith(".py") ||
                    fileName.endsWith(".java") || fileName.endsWith(".kt") -> {
                        try {
                            val content = fileEntry.readText()
                            codeFiles.add(relativePath to content)
                        } catch (e: Exception) {
                            android.util.Log.w("CodeDependencyAnalyzer", "Failed to read file $relativePath: ${e.message}")
                        }
                    }
                }
            }
        }
        
        // Analyze all files and build matrix
        codeFiles.forEach { (filePath, content) ->
            try {
                val metadata = analyzeFile(filePath, content, workspaceRoot)
                updateDependencyMatrix(workspaceRoot, metadata)
            } catch (e: Exception) {
                android.util.Log.w("CodeDependencyAnalyzer", "Failed to analyze file $filePath: ${e.message}")
            }
        }
        
        // Generate comprehensive blueprint
        return generateComprehensiveBlueprint(workspaceRoot)
    }
}
