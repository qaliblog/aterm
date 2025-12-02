package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.ppe.PpeApiClient
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
import java.io.File
import java.util.regex.Pattern

/**
 * Parameters for intelligent error analysis
 */
data class IntelligentErrorAnalysisToolParams(
    val errorMessage: String,  // Can be error message, change request, update request, or problem description
    val workspaceContext: String? = null
)

/**
 * Result of error analysis containing files to read and suggested fixes
 */
data class ErrorAnalysisResult(
    val detectedFiles: List<String>,
    val functionsToInvestigate: List<String>,
    val filesToRead: List<FileReadInfo>,
    val suggestedFixes: String? = null
)

data class FileReadInfo(
    val filePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val reason: String
)

/**
 * Tool invocation for intelligent error analysis
 */
class IntelligentErrorAnalysisToolInvocation(
    toolParams: IntelligentErrorAnalysisToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val toolRegistry: com.qali.aterm.agent.tools.ToolRegistry? = null,
    private val ollamaUrl: String? = null,
    private val ollamaModel: String? = null
) : ToolInvocation<IntelligentErrorAnalysisToolParams, ToolResult> {
    
    // Lazy initialization of API client
    private var apiClient: PpeApiClient? = null
    
    private fun getApiClient(): PpeApiClient? {
        if (apiClient == null && toolRegistry != null) {
            apiClient = PpeApiClient(toolRegistry, ollamaUrl, ollamaModel)
        }
        return apiClient
    }
    
    override val params: IntelligentErrorAnalysisToolParams = toolParams
    
    override fun getDescription(): String {
        val requestType = ErrorDetectionService.detectRequestType(params.errorMessage)
        val label = when (requestType) {
            ErrorDetectionService.RequestType.ERROR -> "error"
            ErrorDetectionService.RequestType.CHANGE_REQUEST -> "change request"
            ErrorDetectionService.RequestType.UPDATE_REQUEST -> "update request"
            ErrorDetectionService.RequestType.PROBLEM -> "problem"
            else -> "request"
        }
        return "Analyzing $label: ${params.errorMessage.take(100)}..."
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Error analysis cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return try {
            updateOutput?.invoke("🔍 Analyzing request and extracting file names...\n")
            
            // Step 1: Detect if this is an error, change request, update request, or problem
            val requestType = ErrorDetectionService.detectRequestType(params.errorMessage)
            if (requestType == ErrorDetectionService.RequestType.UNKNOWN) {
                return ToolResult(
                    llmContent = "No code-related request detected in the message. This tool is designed for error analysis, change requests, update requests, and problem resolution.",
                    returnDisplay = "No code-related request detected"
                )
            }
            
            val requestTypeLabel = when (requestType) {
                ErrorDetectionService.RequestType.ERROR -> "Error"
                ErrorDetectionService.RequestType.CHANGE_REQUEST -> "Change Request"
                ErrorDetectionService.RequestType.UPDATE_REQUEST -> "Update Request"
                ErrorDetectionService.RequestType.PROBLEM -> "Problem"
                else -> "Request"
            }
            
            updateOutput?.invoke("✅ $requestTypeLabel detected. Extracting file names...\n")
            
            // Step 2: Extract file names from error message
            val fileNames = FileNameExtractor.extractFileNames(params.errorMessage, workspaceRoot)
            
            if (fileNames.isEmpty()) {
                return ToolResult(
                    llmContent = "No file names found in error message. Please provide file names or paths in the error description.",
                    returnDisplay = "No files found"
                )
            }
            
            updateOutput?.invoke("📁 Found ${fileNames.size} file(s): ${fileNames.joinToString(", ")}\n")
            
            // Step 3: Search for files in workspace
            val foundFiles = fileNames.mapNotNull { fileName ->
                findFileInWorkspace(fileName)
            }
            
            if (foundFiles.isEmpty()) {
                return ToolResult(
                    llmContent = "Files not found in workspace: ${fileNames.joinToString(", ")}",
                    returnDisplay = "Files not found",
                    error = ToolError(
                        message = "Files not found",
                        type = ToolErrorType.FILE_NOT_FOUND
                    )
                )
            }
            
            updateOutput?.invoke("✅ Found ${foundFiles.size} file(s) in workspace\n")
            
            // Step 4: Extract function names and error details from error message
            val functionNames = FunctionAnalyzer.extractFunctionNames(params.errorMessage)
            val errorDetails = ErrorDetectionService.extractErrorDetails(params.errorMessage)
            
            updateOutput?.invoke("🔎 Analyzing functions: ${functionNames.joinToString(", ")}\n")
            
            // Step 5: Analyze files to find relevant functions and determine what to read
            val filesToRead = mutableListOf<FileReadInfo>()
            
            for (file in foundFiles) {
                try {
                    val content = file.readText()
                    val metadata = CodeDependencyAnalyzer.analyzeFile(file.absolutePath, content, workspaceRoot)
                    
                    // Find functions mentioned in error
                    val relevantFunctions = if (functionNames.isNotEmpty()) {
                        FunctionAnalyzer.findFunctionsInFile(content, functionNames, metadata)
                    } else {
                        // If no specific functions mentioned, analyze error location
                        FunctionAnalyzer.findErrorLocation(content, errorDetails)
                    }
                    
                    if (relevantFunctions.isNotEmpty()) {
                        relevantFunctions.forEach { funcInfo ->
                            val reasonLabel = when (requestType) {
                                ErrorDetectionService.RequestType.ERROR -> "mentioned in error"
                                ErrorDetectionService.RequestType.CHANGE_REQUEST -> "mentioned in change request"
                                ErrorDetectionService.RequestType.UPDATE_REQUEST -> "mentioned in update request"
                                ErrorDetectionService.RequestType.PROBLEM -> "mentioned in problem"
                                else -> "mentioned in request"
                            }
                            filesToRead.add(
                                FileReadInfo(
                                    filePath = file.absolutePath,
                                    startLine = funcInfo.startLine,
                                    endLine = funcInfo.endLine,
                                    reason = "Function '${funcInfo.functionName}' $reasonLabel"
                                )
                            )
                        }
                    } else {
                        // If no specific functions found, read the whole file
                        val reasonLabel = when (requestType) {
                            ErrorDetectionService.RequestType.ERROR -> "mentioned in error but no specific function found"
                            ErrorDetectionService.RequestType.CHANGE_REQUEST -> "mentioned in change request but no specific function found"
                            ErrorDetectionService.RequestType.UPDATE_REQUEST -> "mentioned in update request but no specific function found"
                            ErrorDetectionService.RequestType.PROBLEM -> "mentioned in problem but no specific function found"
                            else -> "mentioned in request but no specific function found"
                        }
                        filesToRead.add(
                            FileReadInfo(
                                filePath = file.absolutePath,
                                reason = reasonLabel
                            )
                        )
                    }
                } catch (e: Exception) {
                    DebugLogger.e("IntelligentErrorAnalysisTool", "Error analyzing file: ${file.absolutePath}", emptyMap(), e)
                    // Still add file to read list
                    filesToRead.add(
                        FileReadInfo(
                            filePath = file.absolutePath,
                            reason = "Error analyzing file: ${e.message}"
                        )
                    )
                }
            }
            
            updateOutput?.invoke("📋 Determined ${filesToRead.size} file section(s) to read\n")
            
            // Step 6: Make API call with context to determine fixes/changes/updates
            val apiClientInstance = getApiClient()
            val suggestedFixes = if (apiClientInstance != null && filesToRead.isNotEmpty()) {
                val actionLabel = when (requestType) {
                    ErrorDetectionService.RequestType.ERROR -> "fix suggestions"
                    ErrorDetectionService.RequestType.CHANGE_REQUEST -> "change plan"
                    ErrorDetectionService.RequestType.UPDATE_REQUEST -> "update plan"
                    ErrorDetectionService.RequestType.PROBLEM -> "solution"
                    else -> "suggestions"
                }
                updateOutput?.invoke("🤖 Consulting AI for $actionLabel...\n")
                generateFixSuggestions(apiClientInstance, params.errorMessage, filesToRead, foundFiles, requestType)
            } else {
                null
            }
            
            // Step 7: Read the relevant files
            updateOutput?.invoke("📖 Reading relevant file sections...\n")
            val fileContents = readFileSections(filesToRead)
            
            // Build comprehensive result
            val reportTitle = when (requestType) {
                ErrorDetectionService.RequestType.ERROR -> "Error Analysis Report"
                ErrorDetectionService.RequestType.CHANGE_REQUEST -> "Change Request Analysis Report"
                ErrorDetectionService.RequestType.UPDATE_REQUEST -> "Update Request Analysis Report"
                ErrorDetectionService.RequestType.PROBLEM -> "Problem Analysis Report"
                else -> "Code Analysis Report"
            }
            
            val result = buildString {
                appendLine("## $reportTitle")
                appendLine()
                appendLine("**Request Type:** $requestTypeLabel")
                appendLine()
                appendLine("### Detected Files")
                foundFiles.forEach { file ->
                    appendLine("- ${file.absolutePath}")
                }
                appendLine()
                
                if (functionNames.isNotEmpty()) {
                    val functionLabel = when (requestType) {
                        ErrorDetectionService.RequestType.ERROR -> "Functions Mentioned in Error"
                        else -> "Functions Mentioned in Request"
                    }
                    appendLine("### $functionLabel")
                    functionNames.forEach { func ->
                        appendLine("- $func")
                    }
                    appendLine()
                }
                
                appendLine("### Files to Read")
                filesToRead.forEach { info ->
                    appendLine("- ${info.filePath}")
                    if (info.startLine != null && info.endLine != null) {
                        appendLine("  Lines: ${info.startLine}-${info.endLine}")
                    }
                    appendLine("  Reason: ${info.reason}")
                }
                appendLine()
                
                appendLine("### File Contents")
                fileContents.forEach { (filePath, content) ->
                    appendLine("#### $filePath")
                    appendLine("```")
                    appendLine(content)
                    appendLine("```")
                    appendLine()
                }
                
                if (suggestedFixes != null) {
                    val suggestionLabel = when (requestType) {
                        ErrorDetectionService.RequestType.ERROR -> "Suggested Fixes"
                        ErrorDetectionService.RequestType.CHANGE_REQUEST -> "Suggested Changes"
                        ErrorDetectionService.RequestType.UPDATE_REQUEST -> "Update Plan"
                        ErrorDetectionService.RequestType.PROBLEM -> "Suggested Solution"
                        else -> "Suggestions"
                    }
                    appendLine("### $suggestionLabel")
                    appendLine(suggestedFixes)
                    appendLine()
                }
            }
            
            updateOutput?.invoke("✅ Analysis complete!\n")
            
            ToolResult(
                llmContent = result,
                returnDisplay = "Analyzed ${foundFiles.size} file(s), ${filesToRead.size} section(s) to read"
            )
        } catch (e: Exception) {
            DebugLogger.e("IntelligentErrorAnalysisTool", "Error during analysis", emptyMap(), e)
            ToolResult(
                llmContent = "Error during analysis: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    /**
     * Find file in workspace by name or path
     */
    private fun findFileInWorkspace(fileName: String): File? {
        // Try as absolute path first
        val absoluteFile = File(fileName)
        if (absoluteFile.exists() && absoluteFile.isFile) {
            return absoluteFile
        }
        
        // Try relative to workspace root
        val relativeFile = File(workspaceRoot, fileName)
        if (relativeFile.exists() && relativeFile.isFile) {
            return relativeFile
        }
        
        // Search recursively in workspace
        return searchFileRecursively(File(workspaceRoot), fileName)
    }
    
    /**
     * Recursively search for file in workspace
     */
    private fun searchFileRecursively(dir: File, fileName: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory && !file.name.startsWith(".")) {
                val found = searchFileRecursively(file, fileName)
                if (found != null) return found
            } else if (file.isFile && (file.name == fileName || file.name.endsWith(fileName))) {
                return file
            }
        }
        
        return null
    }
    
    /**
     * Read file sections based on FileReadInfo
     */
    private fun readFileSections(filesToRead: List<FileReadInfo>): Map<String, String> {
        val contents = mutableMapOf<String, String>()
        
        filesToRead.forEach { info ->
            try {
                val file = File(info.filePath)
                if (!file.exists()) {
                    contents[info.filePath] = "File not found: ${info.filePath}"
                    return@forEach
                }
                
                val lines = file.readLines()
                
                val content = if (info.startLine != null && info.endLine != null) {
                    // Read specific line range
                    val start = (info.startLine - 1).coerceAtLeast(0)
                    val end = info.endLine.coerceAtMost(lines.size)
                    lines.subList(start, end).joinToString("\n")
                } else {
                    // Read entire file (limit to first 500 lines to avoid huge files)
                    lines.take(500).joinToString("\n")
                }
                
                contents[info.filePath] = content
            } catch (e: Exception) {
                contents[info.filePath] = "Error reading file: ${e.message}"
            }
        }
        
        return contents
    }
    
    /**
     * Generate fix suggestions, change plans, or update plans using API
     */
    private suspend fun generateFixSuggestions(
        apiClient: PpeApiClient,
        errorMessage: String,
        filesToRead: List<FileReadInfo>,
        foundFiles: List<File>,
        requestType: ErrorDetectionService.RequestType = ErrorDetectionService.RequestType.ERROR
    ): String? {
        return try {
            // Read file contents first
            val fileContents = readFileSections(filesToRead)
            
            // Build context for API call
            val requestLabel = when (requestType) {
                ErrorDetectionService.RequestType.ERROR -> "Error Message"
                ErrorDetectionService.RequestType.CHANGE_REQUEST -> "Change Request"
                ErrorDetectionService.RequestType.UPDATE_REQUEST -> "Update Request"
                ErrorDetectionService.RequestType.PROBLEM -> "Problem Description"
                else -> "Request"
            }
            
            val context = buildString {
                appendLine("$requestLabel:")
                appendLine(errorMessage)
                appendLine()
                appendLine("Files Involved:")
                foundFiles.forEach { file ->
                    appendLine("- ${file.absolutePath}")
                }
                appendLine()
                appendLine("Relevant Code Sections:")
                fileContents.forEach { (filePath, content) ->
                    appendLine("### $filePath")
                    appendLine("```")
                    appendLine(content)
                    appendLine("```")
                    appendLine()
                }
            }
            
            // Build prompt based on request type
            val prompt = when (requestType) {
                ErrorDetectionService.RequestType.ERROR -> {
                    """Analyze the following error and code sections. Provide specific, actionable fix suggestions.

$context

Please provide:
1. Root cause analysis
2. Specific code changes needed
3. Step-by-step fix instructions"""
                }
                ErrorDetectionService.RequestType.CHANGE_REQUEST -> {
                    """Analyze the following change request and code sections. Provide a detailed plan for implementing the changes.

$context

Please provide:
1. Analysis of current code state
2. Specific changes needed to implement the request
3. Step-by-step implementation plan
4. Files that need to be modified
5. Any new files that need to be created"""
                }
                ErrorDetectionService.RequestType.UPDATE_REQUEST -> {
                    """Analyze the following update request and code sections. Provide a detailed plan for updating the codebase.

$context

Please provide:
1. Analysis of current code state
2. What needs to be updated and why
3. Specific code changes needed
4. Step-by-step update plan
5. Files that need to be modified
6. Dependencies or considerations"""
                }
                ErrorDetectionService.RequestType.PROBLEM -> {
                    """Analyze the following problem description and code sections. Provide a comprehensive solution.

$context

Please provide:
1. Problem analysis and root cause
2. Impact assessment
3. Specific solution approach
4. Code changes needed
5. Step-by-step resolution plan
6. Testing considerations"""
                }
                else -> {
                    """Analyze the following request and code sections. Provide specific, actionable suggestions.

$context

Please provide:
1. Analysis of the request
2. Specific actions needed
3. Step-by-step implementation plan"""
                }
            }
            
            // Make API call to get suggestions
            val messages = listOf(
                com.qali.aterm.agent.core.Content(
                    role = "user",
                    parts = listOf(
                        com.qali.aterm.agent.core.Part.TextPart(text = prompt)
                    )
                )
            )
            
            val result = apiClient.callApi(
                messages = messages,
                temperature = 0.3, // Lower temperature for more focused analysis
                disableTools = true // Don't use tools for this analysis
            )
            
            result.getOrNull()?.text
        } catch (e: Exception) {
            DebugLogger.e("IntelligentErrorAnalysisTool", "Error generating fix suggestions", emptyMap(), e)
            null
        }
    }
}

/**
 * Function information found in file
 */
data class FunctionInfo(
    val functionName: String,
    val startLine: Int,
    val endLine: Int
)

/**
 * Service for detecting errors, change requests, update requests, and problems in user messages
 */
object ErrorDetectionService {
    private val errorKeywords = listOf(
        "error", "exception", "failed", "failure", "crash", "bug", "issue",
        "problem", "wrong", "incorrect", "doesn't work", "not working",
        "compile error", "runtime error", "syntax error", "type error",
        "undefined", "null pointer", "index out of bounds", "stack overflow",
        "cannot", "unable", "invalid", "missing", "not found"
    )
    
    private val changeRequestKeywords = listOf(
        "change", "modify", "update", "upgrade", "improve", "refactor",
        "add", "remove", "delete", "create", "implement", "fix",
        "enhance", "optimize", "rewrite", "replace", "adjust", "edit"
    )
    
    private val problemKeywords = listOf(
        "problem", "issue", "bug", "broken", "not working", "doesn't work",
        "failing", "incorrect", "wrong", "broken", "malfunction", "defect"
    )
    
    private val requestPatterns = listOf(
        Pattern.compile("""(?:please|can you|could you|i need|i want)\s+(?:to\s+)?(change|modify|update|fix|add|remove|create|implement)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:change|modify|update|fix|add|remove|create|implement)\s+(?:the\s+)?(.+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:make|do)\s+(?:a\s+)?(change|update|modification)""", Pattern.CASE_INSENSITIVE)
    )
    
    private val errorPatterns = listOf(
        Pattern.compile("""error\s*:?\s*(.+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""exception\s*:?\s*(.+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""failed\s+to\s+(.+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(.+)\s+error""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(.+)\s+exception""", Pattern.CASE_INSENSITIVE)
    )
    
    enum class RequestType {
        ERROR,           // Error or exception
        CHANGE_REQUEST, // Change, modify, update request
        PROBLEM,        // Problem in current state
        UPDATE_REQUEST, // Update or upgrade request
        UNKNOWN         // Not a code-related request
    }
    
    /**
     * Detect if message contains an error, change request, update request, or problem
     */
    fun detectError(message: String): Boolean {
        return detectRequestType(message) != RequestType.UNKNOWN
    }
    
    /**
     * Detect the type of request in the message
     */
    fun detectRequestType(message: String): RequestType {
        val lowerMessage = message.lowercase()
        
        // Check for error keywords first (highest priority)
        if (errorKeywords.any { lowerMessage.contains(it) }) {
            // Check for stack traces or error codes
            if (message.contains("at ") && message.contains("(") && message.contains(")")) {
                return RequestType.ERROR
            }
            // Check for error patterns
            if (errorPatterns.any { it.matcher(message).find() }) {
                return RequestType.ERROR
            }
            // If it's a problem keyword, it could be a problem or error
            if (problemKeywords.any { lowerMessage.contains(it) }) {
                return RequestType.PROBLEM
            }
            return RequestType.ERROR
        }
        
        // Check for change/update request keywords
        if (changeRequestKeywords.any { lowerMessage.contains(it) }) {
            // Check for specific request patterns
            if (requestPatterns.any { it.matcher(message).find() }) {
                if (lowerMessage.contains("update") || lowerMessage.contains("upgrade")) {
                    return RequestType.UPDATE_REQUEST
                }
                return RequestType.CHANGE_REQUEST
            }
            // If it contains "update" or "upgrade", it's an update request
            if (lowerMessage.contains("update") || lowerMessage.contains("upgrade")) {
                return RequestType.UPDATE_REQUEST
            }
            return RequestType.CHANGE_REQUEST
        }
        
        // Check for problem keywords
        if (problemKeywords.any { lowerMessage.contains(it) }) {
            return RequestType.PROBLEM
        }
        
        // Check for file names or code references (indicates code-related request)
        if (FileNameExtractor.extractFileNames(message, "").isNotEmpty()) {
            // If it mentions files, it's likely a change/update request
            return RequestType.CHANGE_REQUEST
        }
        
        return RequestType.UNKNOWN
    }
    
    /**
     * Extract error details from message
     */
    fun extractErrorDetails(message: String): Map<String, String> {
        val details = mutableMapOf<String, String>()
        
        // Try to extract error type
        errorPatterns.forEach { pattern ->
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                details["error_type"] = matcher.group(1)?.trim() ?: ""
            }
        }
        
        // Extract line numbers
        val linePattern = Pattern.compile("""line\s+(\d+)""", Pattern.CASE_INSENSITIVE)
        val lineMatcher = linePattern.matcher(message)
        if (lineMatcher.find()) {
            details["line_number"] = lineMatcher.group(1)
        }
        
        // Extract file paths
        val filePattern = Pattern.compile("""([/\w\-\.]+\.(?:kt|java|js|ts|py|go|rs|cpp|h))""")
        val fileMatcher = filePattern.matcher(message)
        if (fileMatcher.find()) {
            details["file_path"] = fileMatcher.group(1)
        }
        
        return details
    }
}

/**
 * Service for extracting file names from error messages, change requests, and update requests
 */
object FileNameExtractor {
    private val filePatterns = listOf(
        // Standard file paths
        Pattern.compile("""([/\w\-\.]+\.(?:kt|java|js|ts|jsx|tsx|py|go|rs|cpp|h|hpp|c|cc|xml|json|yaml|yml|md|txt))"""),
        // File names without extension (common in errors)
        Pattern.compile("""['"]([\w\-\.]+\.(?:kt|java|js|ts|py|go|rs))['"]"""),
        // Relative paths
        Pattern.compile("""(?:file|path|in|from|at)\s*:?\s*['"]?([/\w\-\.]+\.(?:kt|java|js|ts|py|go|rs))['"]?"""),
        // Stack trace format
        Pattern.compile("""at\s+[\w\.]+\s*\(([/\w\-\.]+\.(?:kt|java|js|ts|py)):\d+\)"""),
        // Change/update request patterns
        Pattern.compile("""(?:change|modify|update|fix|edit|in)\s+(?:the\s+)?(?:file\s+)?['"]?([/\w\-\.]+\.(?:kt|java|js|ts|py|go|rs))['"]?"""),
        Pattern.compile("""(?:update|modify|change)\s+['"]?([/\w\-\.]+\.(?:kt|java|js|ts|py|go|rs))['"]?"""),
        // Directory + file patterns
        Pattern.compile("""([/\w\-\.]+/[\w\-\.]+\.(?:kt|java|js|ts|py|go|rs))""")
    )
    
    /**
     * Extract file names from error message
     */
    fun extractFileNames(message: String, workspaceRoot: String): List<String> {
        val fileNames = mutableSetOf<String>()
        
        filePatterns.forEach { pattern ->
            val matcher = pattern.matcher(message)
            while (matcher.find()) {
                val fileName = matcher.group(1)?.trim()
                if (fileName != null && fileName.isNotEmpty()) {
                    // Normalize path
                    val normalized = normalizePath(fileName, workspaceRoot)
                    fileNames.add(normalized)
                }
            }
        }
        
        return fileNames.toList()
    }
    
    private fun normalizePath(path: String, workspaceRoot: String): String {
        // If it's already absolute, return as is
        if (path.startsWith("/")) {
            return path
        }
        
        // If it starts with workspace root, return as is
        if (path.startsWith(workspaceRoot)) {
            return path
        }
        
        // Otherwise, assume it's relative to workspace
        return path
    }
}

/**
 * Service for analyzing functions mentioned in errors
 */
object FunctionAnalyzer {
    private val functionPatterns = listOf(
        // Function name patterns in code
        Pattern.compile("""function\s+(\w+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""def\s+(\w+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""fun\s+(\w+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(\w+)\s*\([^)]*\)\s*\{"""),
        Pattern.compile("""(\w+)\s*\([^)]*\)\s*:"""),
        // In error messages
        Pattern.compile("""(?:function|method|call)\s+['"]?(\w+)['"]?"""),
        Pattern.compile("""['"](\w+)['"]\s+(?:function|method)"""),
        // In change/update requests
        Pattern.compile("""(?:change|modify|update|fix|edit|in)\s+(?:the\s+)?(?:function|method)\s+['"]?(\w+)['"]?"""),
        Pattern.compile("""(?:function|method)\s+['"]?(\w+)['"]?\s+(?:in|to|from)"""),
        Pattern.compile("""['"](\w+)['"]\s+(?:function|method)"""),
        // Function calls in requests
        Pattern.compile("""(\w+)\s*\([^)]*\)\s*(?:in|to|from|at)""")
    )
    
    /**
     * Extract function names from error message
     */
    fun extractFunctionNames(message: String): List<String> {
        val functionNames = mutableSetOf<String>()
        
        functionPatterns.forEach { pattern ->
            val matcher = pattern.matcher(message)
            while (matcher.find()) {
                val funcName = matcher.group(1)?.trim()
                if (funcName != null && funcName.isNotEmpty() && !isKeyword(funcName)) {
                    functionNames.add(funcName)
                }
            }
        }
        
        return functionNames.toList()
    }
    
    /**
     * Find functions in file content
     */
    fun findFunctionsInFile(
        content: String,
        functionNames: List<String>,
        metadata: CodeDependencyAnalyzer.CodeMetadata
    ): List<FunctionInfo> {
        val foundFunctions = mutableListOf<FunctionInfo>()
        val lines = content.lines()
        
        // First check metadata functions
        functionNames.forEach { funcName ->
            // Search in file content
            lines.forEachIndexed { index, line ->
                // Look for function definition
                val patterns = listOf(
                    Pattern.compile("""fun\s+$funcName\s*[<(]"""), // Kotlin
                    Pattern.compile("""function\s+$funcName\s*[<(]"""), // JavaScript
                    Pattern.compile("""def\s+$funcName\s*[<(]"""), // Python
                    Pattern.compile("""\w+\s+$funcName\s*[<(]""") // Java/C style
                )
                
                patterns.forEach { pattern ->
                    if (pattern.matcher(line).find()) {
                        // Find function end (simplified - looks for closing brace)
                        var endLine = index + 1
                        var braceCount = 0
                        var foundStart = false
                        
                        for (i in index until lines.size.coerceAtMost(index + 200)) {
                            val currentLine = lines[i]
                            if (currentLine.contains("{")) {
                                braceCount++
                                foundStart = true
                            }
                            if (currentLine.contains("}")) {
                                braceCount--
                                if (foundStart && braceCount == 0) {
                                    endLine = i + 1
                                    break
                                }
                            }
                        }
                        
                        foundFunctions.add(
                            FunctionInfo(
                                functionName = funcName,
                                startLine = index + 1,
                                endLine = endLine
                            )
                        )
                        return@forEach
                    }
                }
            }
        }
        
        return foundFunctions
    }
    
    /**
     * Find error location in file based on error details
     */
    fun findErrorLocation(
        content: String,
        errorDetails: Map<String, String>
    ): List<FunctionInfo> {
        val locations = mutableListOf<FunctionInfo>()
        val lines = content.lines()
        
        // If line number is specified, find function containing that line
        errorDetails["line_number"]?.toIntOrNull()?.let { lineNum ->
            if (lineNum > 0 && lineNum <= lines.size) {
                // Find function containing this line
                var currentFunction: String? = null
                var functionStart = 0
                var braceCount = 0
                
                for (i in 0 until lineNum) {
                    val line = lines[i]
                    
                    // Detect function start
                    val funcPattern = Pattern.compile("""(?:fun|function|def)\s+(\w+)""")
                    val matcher = funcPattern.matcher(line)
                    if (matcher.find()) {
                        currentFunction = matcher.group(1)
                        functionStart = i + 1
                        braceCount = 0
                    }
                    
                    // Track braces
                    line.forEach { char ->
                        when (char) {
                            '{' -> braceCount++
                            '}' -> {
                                braceCount--
                                if (braceCount == 0 && currentFunction != null) {
                                    locations.add(
                                        FunctionInfo(
                                            functionName = currentFunction ?: "unknown",
                                            startLine = functionStart,
                                            endLine = i + 1
                                        )
                                    )
                                    currentFunction = null
                                }
                            }
                        }
                    }
                }
                
                // If we're still in a function, add it
                if (currentFunction != null) {
                    locations.add(
                        FunctionInfo(
                            functionName = currentFunction,
                            startLine = functionStart,
                            endLine = lineNum + 10 // Approximate end
                        )
                    )
                }
            }
        }
        
        return locations
    }
    
    private fun isKeyword(word: String): Boolean {
        val keywords = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "return", "break", "continue", "try", "catch", "finally",
            "class", "interface", "enum", "package", "import", "public",
            "private", "protected", "static", "final", "abstract", "extends",
            "implements", "new", "this", "super", "null", "true", "false"
        )
        return keywords.contains(word.lowercase())
    }
}

/**
 * Intelligent Error Analysis Tool
 * Automatically detects errors, extracts file names, analyzes functions, and suggests fixes
 */
class IntelligentErrorAnalysisTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val toolRegistry: com.qali.aterm.agent.tools.ToolRegistry? = null,
    private val ollamaUrl: String? = null,
    private val ollamaModel: String? = null
) : DeclarativeTool<IntelligentErrorAnalysisToolParams, ToolResult>() {
    
    override val name = "intelligent_error_analysis"
    override val displayName = "Intelligent Code Analysis"
    override val description = """
        Automatically detects if a user message contains a code error, change request, update request, or problem,
        extracts file names from the message, searches for those files in the workspace, analyzes functions mentioned,
        determines which files and line ranges to read, makes an API call with context to figure out fixes/changes/updates,
        and reads the relevant files. This tool provides comprehensive analysis for errors, change requests, update requests,
        and problem resolution with actionable suggestions.
    """.trimIndent()
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "errorMessage" to PropertySchema(
                type = "string",
                description = "The error message or problem description containing file names and error details."
            ),
            "workspaceContext" to PropertySchema(
                type = "string",
                description = "Optional workspace context or additional information about the project."
            )
        ),
        required = listOf("errorMessage")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: IntelligentErrorAnalysisToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<IntelligentErrorAnalysisToolParams, ToolResult> {
        return IntelligentErrorAnalysisToolInvocation(params, workspaceRoot, toolRegistry, ollamaUrl, ollamaModel)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): IntelligentErrorAnalysisToolParams {
        val errorMessage = params["errorMessage"] as? String
            ?: throw IllegalArgumentException("errorMessage is required")
        
        if (errorMessage.trim().isEmpty()) {
            throw IllegalArgumentException("errorMessage must be non-empty")
        }
        
        return IntelligentErrorAnalysisToolParams(
            errorMessage = errorMessage,
            workspaceContext = params["workspaceContext"] as? String
        )
    }
}
