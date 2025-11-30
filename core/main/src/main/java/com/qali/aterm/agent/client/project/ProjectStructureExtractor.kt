package com.qali.aterm.agent.client.project

import android.os.CancellationSignal
import java.io.File

/**
 * Extracts project structure information from codebase
 */
object ProjectStructureExtractor {
    
    /**
     * Extract project structure: classes, functions, imports, tree
     */
    suspend fun extractProjectStructure(
        workspaceRoot: String,
        signal: CancellationSignal? = null
    ): String {
        val structure = StringBuilder()
        val workspaceDir = File(workspaceRoot)
        
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return ""
        }
        
        // Get project tree
        val projectTree = buildProjectTree(workspaceDir, maxDepth = 3)
        structure.append("**Project Tree:**\n$projectTree\n\n")
        
        // Extract from source files
        val sourceFiles = findSourceFiles(workspaceDir)
        structure.append("**Files with Code Structure:**\n\n")
        
        for (file in sourceFiles.take(50)) { // Limit to 50 files
            if (signal?.isAborted() == true) break
            
            try {
                val relativePath = file.relativeTo(workspaceDir).path
                val content = file.readText()
                
                structure.append("=== $relativePath ===\n")
                
                // Extract imports
                val imports = extractImports(content, file.extension)
                if (imports.isNotEmpty()) {
                    structure.append("Imports: ${imports.joinToString(", ")}\n")
                }
                
                // Extract classes
                val classes = extractClasses(content, file.extension)
                if (classes.isNotEmpty()) {
                    classes.forEach { (name, line) ->
                        structure.append("Class: $name (line $line)\n")
                    }
                }
                
                // Extract functions
                val functions = extractFunctions(content, file.extension)
                if (functions.isNotEmpty()) {
                    functions.forEach { (name, line) ->
                        structure.append("Function: $name (line $line)\n")
                    }
                }
                
                structure.append("\n")
            } catch (e: Exception) {
                android.util.Log.e("ProjectStructureExtractor", "Failed to extract from ${file.name}", e)
            }
        }
        
        return structure.toString()
    }
    
    /**
     * Build project tree structure
     */
    fun buildProjectTree(dir: File, prefix: String = "", maxDepth: Int = 3, currentDepth: Int = 0): String {
        if (currentDepth >= maxDepth) return ""
        
        val builder = StringBuilder()
        val files = dir.listFiles()?.sortedBy { !it.isDirectory } ?: return ""
        
        for ((index, file) in files.withIndex()) {
            if (file.name.startsWith(".")) continue
            
            val isLast = index == files.size - 1
            val currentPrefix = if (isLast) "└── " else "├── "
            builder.append("$prefix$currentPrefix${file.name}\n")
            
            if (file.isDirectory) {
                val nextPrefix = prefix + if (isLast) "    " else "│   "
                builder.append(buildProjectTree(file, nextPrefix, maxDepth, currentDepth + 1))
            }
        }
        
        return builder.toString()
    }
    
    /**
     * Find source files in project
     */
    fun findSourceFiles(dir: File): List<File> {
        val sourceExtensions = setOf(
            "kt", "java", "js", "ts", "jsx", "tsx", "py", "go", "rs", "cpp", "c", "h",
            "html", "css", "xml", "json", "yaml", "yml", "md"
        )
        
        val files = mutableListOf<File>()
        
        fun traverse(currentDir: File) {
            if (!currentDir.exists() || !currentDir.isDirectory) return
            
            currentDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(".")) return
                if (file.isDirectory && file.name != "node_modules" && file.name != ".git") {
                    traverse(file)
                } else if (file.isFile) {
                    val ext = file.extension.lowercase()
                    if (ext in sourceExtensions) {
                        files.add(file)
                    }
                }
            }
        }
        
        traverse(dir)
        return files
    }
    
    /**
     * Extract imports from file content
     */
    fun extractImports(content: String, extension: String): List<String> {
        return when (extension.lowercase()) {
            "kt", "java" -> {
                Regex("^import\\s+([^;]+);", RegexOption.MULTILINE)
                    .findAll(content)
                    .map { it.groupValues[1].trim() }
                    .toList()
            }
            "js", "ts", "jsx", "tsx" -> {
                Regex("^import\\s+.*?from\\s+['\"]([^'\"]+)['\"]", RegexOption.MULTILINE)
                    .findAll(content)
                    .map { it.groupValues[1].trim() }
                    .toList()
            }
            "py" -> {
                Regex("^import\\s+([^\\n]+)|^from\\s+([^\\s]+)\\s+import", RegexOption.MULTILINE)
                    .findAll(content)
                    .mapNotNull { it.groupValues[1].takeIf { it.isNotEmpty() } ?: it.groupValues[2] }
                    .toList()
            }
            else -> emptyList()
        }
    }
    
    /**
     * Extract classes from file content
     */
    fun extractClasses(content: String, extension: String): List<Pair<String, Int>> {
        val classes = mutableListOf<Pair<String, Int>>()
        val lines = content.lines()
        
        when (extension.lowercase()) {
            "kt", "java" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:class|interface|enum)\\s+(\\w+)").find(line)?.let {
                        classes.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
            "js", "ts", "jsx", "tsx" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:class|interface|enum|type|const)\\s+(\\w+)").find(line)?.let {
                        classes.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
            "py" -> {
                lines.forEachIndexed { index, line ->
                    Regex("class\\s+(\\w+)").find(line)?.let {
                        classes.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
        }
        
        return classes
    }
    
    /**
     * Extract functions from file content
     */
    fun extractFunctions(content: String, extension: String): List<Pair<String, Int>> {
        val functions = mutableListOf<Pair<String, Int>>()
        val lines = content.lines()
        
        when (extension.lowercase()) {
            "kt", "java" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:fun|private|public|protected)?\\s*(?:fun)?\\s*(\\w+)\\s*\\(").find(line)?.let {
                        functions.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
            "js", "ts", "jsx", "tsx" -> {
                lines.forEachIndexed { index, line ->
                    Regex("(?:function|const|let|var)\\s+(\\w+)\\s*[=(]|(\\w+)\\s*:\\s*function").find(line)?.let {
                        val name = it.groupValues[1].takeIf { it.isNotEmpty() } ?: it.groupValues[2]
                        if (name.isNotEmpty()) {
                            functions.add(Pair(name, index + 1))
                        }
                    }
                }
            }
            "py" -> {
                lines.forEachIndexed { index, line ->
                    Regex("def\\s+(\\w+)\\s*\\(").find(line)?.let {
                        functions.add(Pair(it.groupValues[1], index + 1))
                    }
                }
            }
        }
        
        return functions
    }
    
    /**
     * Extract specific code sections (functions or line ranges)
     */
    fun extractCodeSections(
        content: String,
        filePath: String,
        functionNames: List<String>,
        lineRanges: List<Pair<Int, Int>>
    ): String {
        val lines = content.lines()
        val sections = mutableListOf<String>()
        
        // Extract functions
        for (funcName in functionNames) {
            val funcPattern = when {
                filePath.endsWith(".kt") || filePath.endsWith(".java") -> 
                    Regex("fun\\s+$funcName\\s*\\(")
                filePath.endsWith(".js") || filePath.endsWith(".ts") -> 
                    Regex("(?:function|const|let|var)\\s+$funcName\\s*[=(]")
                filePath.endsWith(".py") -> 
                    Regex("def\\s+$funcName\\s*\\(")
                else -> Regex("$funcName\\s*\\(")
            }
            
            lines.forEachIndexed { index, line ->
                if (funcPattern.find(line) != null) {
                    // Extract function with context (next 50 lines or until next function)
                    val endLine = minOf(index + 50, lines.size)
                    val funcCode = lines.subList(index, endLine).joinToString("\n")
                    sections.add("// Function: $funcName (line ${index + 1})\n$funcCode")
                }
            }
        }
        
        // Extract line ranges
        for ((start, end) in lineRanges) {
            val startIdx = (start - 1).coerceAtLeast(0)
            val endIdx = end.coerceAtMost(lines.size)
            if (startIdx < endIdx) {
                val rangeCode = lines.subList(startIdx, endIdx).joinToString("\n")
                sections.add("// Lines $start-$end\n$rangeCode")
            }
        }
        
        return sections.joinToString("\n\n---\n\n")
    }
}
