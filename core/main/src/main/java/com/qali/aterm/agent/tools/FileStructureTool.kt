package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import java.io.File

/**
 * Represents a function or class in a file with its location
 */
data class CodeStructure(
    val name: String,
    val type: String, // "function", "class", "interface", "method", etc.
    val startLine: Int,
    val endLine: Int,
    val signature: String,
    val filePath: String
)

/**
 * Represents the structure of a file with annotations
 */
data class FileStructure(
    val filePath: String,
    val totalLines: Int,
    val structures: List<CodeStructure>,
    val changeLocations: List<Int> // Line numbers where changes might be needed
)

data class FileStructureToolParams(
    val file_path: String,
    val include_functions: Boolean = true,
    val include_classes: Boolean = true,
    val include_interfaces: Boolean = true,
    val include_methods: Boolean = true,
    val annotate_change_locations: Boolean = true
)

class FileStructureToolInvocation(
    toolParams: FileStructureToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<FileStructureToolParams, ToolResult> {
    
    override val params: FileStructureToolParams = toolParams
    
    override fun getDescription(): String {
        return "Extracting structure from ${params.file_path}"
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
                llmContent = "Structure extraction cancelled",
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
            val structure = extractStructure(
                content,
                lines,
                params.file_path,
                params
            )
            
            val output = formatStructure(structure)
            
            updateOutput?.invoke("Extracted structure from ${params.file_path}")
            
            ToolResult(
                llmContent = output,
                returnDisplay = "Found ${structure.structures.size} structures, ${structure.changeLocations.size} change locations"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error extracting structure: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun extractStructure(
        content: String,
        lines: List<String>,
        filePath: String,
        params: FileStructureToolParams
    ): FileStructure {
        val structures = mutableListOf<CodeStructure>()
        val changeLocations = mutableListOf<Int>()
        
        // Detect file type
        val fileExtension = File(filePath).extension.lowercase()
        val isKotlin = fileExtension == "kt" || fileExtension == "kts"
        val isJava = fileExtension == "java"
        val isTypeScript = fileExtension == "ts" || fileExtension == "tsx"
        val isJavaScript = fileExtension == "js" || fileExtension == "jsx"
        val isPython = fileExtension == "py"
        
        var braceDepth = 0
        var parenDepth = 0
        var currentFunction: CodeStructure? = null
        var currentClass: CodeStructure? = null
        var functionStartLine = 0
        var classStartLine = 0
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trim()
            
            // Detect potential change locations (TODO, FIXME, XXX, etc.)
            if (params.annotate_change_locations) {
                if (trimmed.contains("TODO", ignoreCase = true) ||
                    trimmed.contains("FIXME", ignoreCase = true) ||
                    trimmed.contains("XXX", ignoreCase = true) ||
                    trimmed.contains("HACK", ignoreCase = true) ||
                    trimmed.contains("BUG", ignoreCase = true) ||
                    trimmed.contains("NOTE", ignoreCase = true)) {
                    changeLocations.add(lineNum)
                }
            }
            
            when {
                // Kotlin/Java class detection
                (isKotlin || isJava) && params.include_classes -> {
                    when {
                        trimmed.startsWith("class ") && !trimmed.contains("//") -> {
                            val className = extractClassName(trimmed, isKotlin)
                            classStartLine = lineNum
                            currentClass = CodeStructure(
                                name = className,
                                type = "class",
                                startLine = lineNum,
                                endLine = lineNum, // Will be updated
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                        trimmed.startsWith("interface ") && params.include_interfaces && !trimmed.contains("//") -> {
                            val interfaceName = extractInterfaceName(trimmed, isKotlin)
                            structures.add(
                                CodeStructure(
                                    name = interfaceName,
                                    type = "interface",
                                    startLine = lineNum,
                                    endLine = lineNum,
                                    signature = trimmed,
                                    filePath = filePath
                                )
                            )
                        }
                        trimmed.startsWith("fun ") && params.include_functions && isKotlin && !trimmed.contains("//") -> {
                            val functionName = extractFunctionName(trimmed, isKotlin)
                            functionStartLine = lineNum
                            currentFunction = CodeStructure(
                                name = functionName,
                                type = "function",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                        trimmed.matches(Regex("^\\s*(public|private|protected|internal)?\\s*(static)?\\s*\\w+\\s+\\w+\\s*\\(.*\\)\\s*\\{?\\s*$")) && 
                        params.include_functions && isJava && !trimmed.contains("//") -> {
                            val functionName = extractFunctionName(trimmed, isJava)
                            functionStartLine = lineNum
                            currentFunction = CodeStructure(
                                name = functionName,
                                type = "function",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                    }
                }
                
                // TypeScript/JavaScript detection
                (isTypeScript || isJavaScript) && params.include_functions -> {
                    when {
                        trimmed.startsWith("function ") && !trimmed.contains("//") -> {
                            val functionName = extractFunctionName(trimmed, false)
                            functionStartLine = lineNum
                            currentFunction = CodeStructure(
                                name = functionName,
                                type = "function",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                        trimmed.matches(Regex("^\\s*(export\\s+)?(const|let|var)\\s+\\w+\\s*=\\s*(async\\s+)?\\(.*\\)\\s*=>")) && !trimmed.contains("//") -> {
                            val functionName = extractArrowFunctionName(trimmed)
                            functionStartLine = lineNum
                            currentFunction = CodeStructure(
                                name = functionName,
                                type = "function",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                        trimmed.matches(Regex("^\\s*(export\\s+)?class\\s+\\w+")) && params.include_classes && !trimmed.contains("//") -> {
                            val className = extractClassName(trimmed, false)
                            classStartLine = lineNum
                            currentClass = CodeStructure(
                                name = className,
                                type = "class",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                    }
                }
                
                // Python detection
                isPython && params.include_functions -> {
                    when {
                        trimmed.startsWith("def ") && !trimmed.startsWith("#") -> {
                            val functionName = extractPythonFunctionName(trimmed)
                            functionStartLine = lineNum
                            currentFunction = CodeStructure(
                                name = functionName,
                                type = "function",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                        trimmed.startsWith("class ") && params.include_classes && !trimmed.startsWith("#") -> {
                            val className = extractPythonClassName(trimmed)
                            classStartLine = lineNum
                            currentClass = CodeStructure(
                                name = className,
                                type = "class",
                                startLine = lineNum,
                                endLine = lineNum,
                                signature = trimmed,
                                filePath = filePath
                            )
                        }
                    }
                }
            }
            
            // Track brace depth for structure boundaries
            braceDepth += line.count { it == '{' } - line.count { it == '}' }
            parenDepth += line.count { it == '(' } - line.count { it == ')' }
            
            // Close current function/class when brace depth returns to previous level
            if (braceDepth == 0 && currentFunction != null && functionStartLine < lineNum) {
                structures.add(
                    currentFunction!!.copy(endLine = lineNum)
                )
                currentFunction = null
            }
            
            if (braceDepth == 0 && currentClass != null && classStartLine < lineNum) {
                structures.add(
                    currentClass!!.copy(endLine = lineNum)
                )
                currentClass = null
            }
        }
        
        // Add any remaining open structures
        currentFunction?.let { structures.add(it) }
        currentClass?.let { structures.add(it) }
        
        return FileStructure(
            filePath = filePath,
            totalLines = lines.size,
            structures = structures,
            changeLocations = changeLocations
        )
    }
    
    private fun extractClassName(line: String, isKotlin: Boolean): String {
        return when {
            isKotlin -> {
                Regex("class\\s+(\\w+)").find(line)?.groupValues?.get(1) ?: "Unknown"
            }
            else -> {
                Regex("class\\s+(\\w+)").find(line)?.groupValues?.get(1) ?: "Unknown"
            }
        }
    }
    
    private fun extractInterfaceName(line: String, isKotlin: Boolean): String {
        return Regex("interface\\s+(\\w+)").find(line)?.groupValues?.get(1) ?: "Unknown"
    }
    
    private fun extractFunctionName(line: String, isKotlin: Boolean): String {
        return when {
            isKotlin -> {
                Regex("fun\\s+(\\w+)").find(line)?.groupValues?.get(1) ?: "Unknown"
            }
            else -> {
                Regex("(\\w+)\\s*\\(.*\\)").find(line)?.groupValues?.get(1) ?: "Unknown"
            }
        }
    }
    
    private fun extractArrowFunctionName(line: String): String {
        return Regex("(const|let|var)\\s+(\\w+)").find(line)?.groupValues?.get(2) ?: "anonymous"
    }
    
    private fun extractPythonFunctionName(line: String): String {
        return Regex("def\\s+(\\w+)").find(line)?.groupValues?.get(1) ?: "Unknown"
    }
    
    private fun extractPythonClassName(line: String): String {
        return Regex("class\\s+(\\w+)").find(line)?.groupValues?.get(1) ?: "Unknown"
    }
    
    private fun formatStructure(structure: FileStructure): String {
        val sb = StringBuilder()
        sb.appendLine("=== File Structure: ${structure.filePath} ===")
        sb.appendLine("Total lines: ${structure.totalLines}")
        sb.appendLine()
        
        if (structure.structures.isEmpty()) {
            sb.appendLine("No structures found.")
        } else {
            sb.appendLine("=== Structures (${structure.structures.size}) ===")
            structure.structures.forEach { codeStruct ->
                sb.appendLine("${codeStruct.type.uppercase()}: ${codeStruct.name}")
                sb.appendLine("  Lines: ${codeStruct.startLine}-${codeStruct.endLine}")
                sb.appendLine("  Signature: ${codeStruct.signature.take(100)}${if (codeStruct.signature.length > 100) "..." else ""}")
                sb.appendLine()
            }
        }
        
        if (structure.changeLocations.isNotEmpty()) {
            sb.appendLine("=== Change Locations (${structure.changeLocations.size}) ===")
            sb.appendLine("Lines that may need changes:")
            structure.changeLocations.forEach { lineNum ->
                sb.appendLine("  - Line $lineNum")
            }
            sb.appendLine()
        }
        
        sb.appendLine("=== Summary ===")
        sb.appendLine("To modify this file:")
        sb.appendLine("1. Use read_file to read the full content")
        sb.appendLine("2. Use edit tool with old_string/new_string targeting specific lines")
        sb.appendLine("3. Structures above show function/class boundaries for context")
        if (structure.changeLocations.isNotEmpty()) {
            sb.appendLine("4. Change locations marked above indicate areas that may need updates")
        }
        
        return sb.toString()
    }
}

class FileStructureTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<FileStructureToolParams, ToolResult>() {
    
    override val name = "file_structure"
    override val displayName = "FileStructure"
    override val description = "Extracts the structure of a file including functions, classes, interfaces, and their line numbers. Also identifies potential change locations (TODO, FIXME, etc.). Use this to understand file organization before making changes."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to analyze."
            ),
            "include_functions" to PropertySchema(
                type = "boolean",
                description = "Whether to include functions. Defaults to true."
            ),
            "include_classes" to PropertySchema(
                type = "boolean",
                description = "Whether to include classes. Defaults to true."
            ),
            "include_interfaces" to PropertySchema(
                type = "boolean",
                description = "Whether to include interfaces. Defaults to true."
            ),
            "include_methods" to PropertySchema(
                type = "boolean",
                description = "Whether to include methods. Defaults to true."
            ),
            "annotate_change_locations" to PropertySchema(
                type = "boolean",
                description = "Whether to annotate potential change locations (TODO, FIXME, etc.). Defaults to true."
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
        params: FileStructureToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<FileStructureToolParams, ToolResult> {
        return FileStructureToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): FileStructureToolParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        return FileStructureToolParams(
            file_path = filePath,
            include_functions = (params["include_functions"] as? Boolean) ?: true,
            include_classes = (params["include_classes"] as? Boolean) ?: true,
            include_interfaces = (params["include_interfaces"] as? Boolean) ?: true,
            include_methods = (params["include_methods"] as? Boolean) ?: true,
            annotate_change_locations = (params["annotate_change_locations"] as? Boolean) ?: true
        )
    }
}
