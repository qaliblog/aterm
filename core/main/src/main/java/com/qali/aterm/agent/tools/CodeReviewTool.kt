package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.ppe.PpeApiClient
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Code review tool parameters
 */
data class CodeReviewToolParams(
    val filePath: String? = null, // File to review
    val projectPath: String? = null, // Project path (for reviewing multiple files)
    val language: String? = null, // Language hint (auto-detected if not provided)
    val focus: List<String>? = null, // Focus areas: "bugs", "security", "style", "performance", "best_practices", "all"
    val severity: String? = null // Minimum severity: "info", "warning", "error" (default: "info")
)

/**
 * Code review result
 */
data class CodeReviewResult(
    val filePath: String?,
    val language: String,
    val issues: List<ReviewIssue>,
    val summary: ReviewSummary,
    val suggestions: List<String>,
    val overallRating: String // "excellent", "good", "fair", "poor"
)

data class ReviewIssue(
    val type: String, // "bug", "security", "style", "performance", "best_practice"
    val severity: String, // "info", "warning", "error"
    val title: String,
    val description: String,
    val location: IssueLocation,
    val suggestion: String?,
    val codeSnippet: String?
)

data class IssueLocation(
    val file: String,
    val line: Int?,
    val column: Int?,
    val function: String?,
    val class: String?
)

data class ReviewSummary(
    val totalIssues: Int,
    val bugs: Int,
    val security: Int,
    val style: Int,
    val performance: Int,
    val bestPractices: Int,
    val criticalIssues: Int,
    val warnings: Int,
    val info: Int
)

class CodeReviewToolInvocation(
    toolParams: CodeReviewToolParams,
    private val workspaceRoot: String,
    private val apiClient: PpeApiClient? = null
) : ToolInvocation<CodeReviewToolParams, ToolResult> {
    
    override val params: CodeReviewToolParams = toolParams
    
    override fun getDescription(): String {
        val target = params.filePath ?: params.projectPath ?: "code"
        return "Reviewing code: $target"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return params.filePath?.let { path ->
            val file = if (File(path).isAbsolute) File(path) else File(workspaceRoot, path)
            if (file.exists()) listOf(ToolLocation(file.absolutePath)) else emptyList()
        } ?: emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit?
    ): ToolResult = withContext(Dispatchers.IO) {
        if (signal?.isAborted() == true) {
            return@withContext ToolResult(
                llmContent = "Code review cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            // Determine files to review
            val filesToReview = if (params.filePath != null) {
                val file = if (File(params.filePath).isAbsolute) {
                    File(params.filePath)
                } else {
                    File(workspaceRoot, params.filePath)
                }
                if (!file.exists()) {
                    return@withContext ToolResult(
                        llmContent = "File not found: ${params.filePath}",
                        returnDisplay = "Error: File not found",
                        error = ToolError(
                            message = "File not found: ${params.filePath}",
                            type = ToolErrorType.FILE_NOT_FOUND
                        )
                    )
                }
                listOf(file)
            } else if (params.projectPath != null) {
                val projectDir = if (File(params.projectPath).isAbsolute) {
                    File(params.projectPath)
                } else {
                    File(workspaceRoot, params.projectPath)
                }
                if (!projectDir.exists() || !projectDir.isDirectory) {
                    return@withContext ToolResult(
                        llmContent = "Project path does not exist: ${params.projectPath}",
                        returnDisplay = "Error: Path not found",
                        error = ToolError(
                            message = "Project path does not exist",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
                getCodeFiles(projectDir, params.language)
            } else {
                return@withContext ToolResult(
                    llmContent = "Either filePath or projectPath must be provided",
                    returnDisplay = "Error: Missing path",
                    error = ToolError(
                        message = "Either filePath or projectPath must be provided",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            if (filesToReview.isEmpty()) {
                return@withContext ToolResult(
                    llmContent = "No code files found to review",
                    returnDisplay = "No files found"
                )
            }
            
            updateOutput?.invoke("🔍 Reviewing ${filesToReview.size} file(s)...")
            
            val focusAreas = params.focus ?: listOf("all")
            val minSeverity = params.severity ?: "info"
            
            val allIssues = mutableListOf<ReviewIssue>()
            val allSuggestions = mutableSetOf<String>()
            
            filesToReview.forEach { file ->
                if (signal?.isAborted() == true) {
                    return@withContext ToolResult(
                        llmContent = "Review cancelled",
                        returnDisplay = "Cancelled"
                    )
                }
                
                val relativePath = file.relativeTo(if (params.projectPath != null) {
                    if (File(params.projectPath).isAbsolute) File(params.projectPath) else File(workspaceRoot, params.projectPath)
                } else {
                    workspaceRoot
                }).path
                
                updateOutput?.invoke("Reviewing: $relativePath")
                
                val content = file.readText()
                val language = params.language ?: detectLanguage(file)
                
                // Static analysis
                val staticIssues = performStaticAnalysis(content, language, file, relativePath, focusAreas)
                allIssues.addAll(staticIssues)
                
                // AI-powered review if available
                if (apiClient != null && content.length < 20000) {
                    val aiIssues = performAIReview(content, language, file, relativePath, focusAreas, apiClient)
                    allIssues.addAll(aiIssues)
                }
                
                // Generate suggestions
                val fileSuggestions = generateSuggestions(content, language, staticIssues)
                allSuggestions.addAll(fileSuggestions)
            }
            
            // Filter by severity
            val filteredIssues = allIssues.filter { issue ->
                when (minSeverity) {
                    "error" -> issue.severity == "error"
                    "warning" -> issue.severity in listOf("error", "warning")
                    else -> true
                }
            }
            
            // Create summary
            val summary = ReviewSummary(
                totalIssues = filteredIssues.size,
                bugs = filteredIssues.count { it.type == "bug" },
                security = filteredIssues.count { it.type == "security" },
                style = filteredIssues.count { it.type == "style" },
                performance = filteredIssues.count { it.type == "performance" },
                bestPractices = filteredIssues.count { it.type == "best_practice" },
                criticalIssues = filteredIssues.count { it.severity == "error" },
                warnings = filteredIssues.count { it.severity == "warning" },
                info = filteredIssues.count { it.severity == "info" }
            )
            
            // Determine overall rating
            val overallRating = when {
                summary.criticalIssues > 5 -> "poor"
                summary.criticalIssues > 0 || summary.warnings > 10 -> "fair"
                summary.warnings > 5 -> "good"
                else -> "excellent"
            }
            
            val result = CodeReviewResult(
                filePath = params.filePath,
                language = params.language ?: "unknown",
                issues = filteredIssues,
                summary = summary,
                suggestions = allSuggestions.toList(),
                overallRating = overallRating
            )
            
            val output = formatReviewReport(result, filesToReview.size)
            
            DebugLogger.i("CodeReviewTool", "Code review completed", mapOf(
                "files" to filesToReview.size,
                "issues" to summary.totalIssues,
                "rating" to overallRating
            ))
            
            ToolResult(
                llmContent = output,
                returnDisplay = "Review: ${summary.totalIssues} issues (${overallRating})"
            )
        } catch (e: Exception) {
            DebugLogger.e("CodeReviewTool", "Error reviewing code", exception = e)
            ToolResult(
                llmContent = "Error reviewing code: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun getCodeFiles(directory: File, language: String?): List<File> {
        val extensions = when (language?.lowercase()) {
            "kotlin" -> listOf(".kt", ".kts")
            "java" -> listOf(".java")
            "javascript", "js" -> listOf(".js", ".jsx", ".mjs")
            "typescript", "ts" -> listOf(".ts", ".tsx")
            "python" -> listOf(".py", ".pyw")
            "go" -> listOf(".go")
            "rust" -> listOf(".rs")
            "cpp", "c++" -> listOf(".cpp", ".cc", ".cxx", ".hpp", ".h")
            "c" -> listOf(".c", ".h")
            else -> listOf(".kt", ".java", ".js", ".ts", ".py", ".go", ".rs", ".cpp", ".c", ".h")
        }
        
        return directory.walkTopDown()
            .filter { it.isFile && extensions.any { ext -> it.name.endsWith(ext, ignoreCase = true) } }
            .take(50) // Limit to 50 files
            .toList()
    }
    
    private fun detectLanguage(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "jsx", "mjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py", "pyw" -> "python"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "c", "h" -> "c"
            else -> "unknown"
        }
    }
    
    private fun performStaticAnalysis(
        content: String,
        language: String,
        file: File,
        relativePath: String,
        focusAreas: List<String>
    ): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        val lines = content.lines()
        
        if (focusAreas.contains("all") || focusAreas.contains("bugs")) {
            issues.addAll(detectBugs(content, lines, language, relativePath))
        }
        
        if (focusAreas.contains("all") || focusAreas.contains("security")) {
            issues.addAll(detectSecurityIssues(content, lines, language, relativePath))
        }
        
        if (focusAreas.contains("all") || focusAreas.contains("style")) {
            issues.addAll(detectStyleIssues(content, lines, language, relativePath))
        }
        
        if (focusAreas.contains("all") || focusAreas.contains("performance")) {
            issues.addAll(detectPerformanceIssues(content, lines, language, relativePath))
        }
        
        if (focusAreas.contains("all") || focusAreas.contains("best_practices")) {
            issues.addAll(detectBestPracticeViolations(content, lines, language, relativePath))
        }
        
        return issues
    }
    
    private fun detectBugs(content: String, lines: List<String>, language: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        // Null pointer dereference patterns
        lines.forEachIndexed { index, line ->
            if (language in listOf("kotlin", "java")) {
                // Potential NPE: variable.field without null check
                if (Regex("\\w+\\.\\w+").findAll(line).any { match ->
                    val before = line.substring(0, match.range.first)
                    !before.contains("?.") && !before.contains("if") && !before.contains("null")
                }) {
                    // Check if it's actually safe (simplified check)
                    if (!line.contains("?.") && !line.contains("!!") && line.contains(".")) {
                        issues.add(ReviewIssue(
                            type = "bug",
                            severity = "warning",
                            title = "Potential null pointer dereference",
                            description = "Variable may be null before accessing its members",
                            location = IssueLocation(filePath, index + 1, null, null, null),
                            suggestion = "Use safe call operator (?.) or add null check",
                            codeSnippet = line.trim()
                        ))
                    }
                }
            }
            
            // Division by zero
            if (Regex("/\\s*\\w+").findAll(line).any { match ->
                val divisor = line.substring(match.range.last + 1).split(Regex("[^\\w]")).firstOrNull()
                divisor != null && divisor !in listOf("0", "1", "2") && !line.contains("if") && !line.contains("check")
            }) {
                issues.add(ReviewIssue(
                    type = "bug",
                    severity = "warning",
                    title = "Potential division by zero",
                    description = "Division operation without explicit zero check",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Add check to ensure divisor is not zero",
                    codeSnippet = line.trim()
                ))
            }
            
            // Array/List index out of bounds
            if (Regex("\\[\\s*\\w+\\s*\\]").findAll(line).any() && !line.contains("size") && !line.contains("length") && !line.contains("isEmpty")) {
                issues.add(ReviewIssue(
                    type = "bug",
                    severity = "warning",
                    title = "Potential index out of bounds",
                    description = "Array/List access without bounds checking",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Check array/list size before accessing by index",
                    codeSnippet = line.trim()
                ))
            }
        }
        
        // Unclosed resources
        if (language == "java" || language == "kotlin") {
            val tryPattern = Regex("try\\s*\\{")
            val resourcePattern = Regex("(FileInputStream|FileOutputStream|BufferedReader|BufferedWriter|Connection|Statement|ResultSet)")
            content.split("\n").forEachIndexed { index, line ->
                if (resourcePattern.containsMatchIn(line) && !line.contains("use(") && !line.contains("use {")) {
                    issues.add(ReviewIssue(
                        type = "bug",
                        severity = "error",
                        title = "Unclosed resource",
                        description = "Resource may not be properly closed",
                        location = IssueLocation(filePath, index + 1, null, null, null),
                        suggestion = "Use try-with-resources or ensure resource is closed in finally block",
                        codeSnippet = line.trim()
                    ))
                }
            }
        }
        
        return issues
    }
    
    private fun detectSecurityIssues(content: String, lines: List<String>, language: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        lines.forEachIndexed { index, line ->
            val lowerLine = line.lowercase()
            
            // Hardcoded credentials
            if (Regex("(password|api[_-]?key|secret|token)\\s*=\\s*[\"'][^\"']+[\"']", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                issues.add(ReviewIssue(
                    type = "security",
                    severity = "error",
                    title = "Hardcoded credentials",
                    description = "Sensitive information (password, API key, secret) is hardcoded",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Use environment variables or secure configuration management",
                    codeSnippet = line.trim()
                ))
            }
            
            // SQL injection
            if (Regex("(executeQuery|executeUpdate|prepareStatement|Statement\\.execute)").containsMatchIn(line) &&
                Regex("\\+.*\\$|\\+.*\\{").containsMatchIn(line)) {
                issues.add(ReviewIssue(
                    type = "security",
                    severity = "error",
                    title = "Potential SQL injection",
                    description = "SQL query constructed with string concatenation",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Use parameterized queries or prepared statements",
                    codeSnippet = line.trim()
                ))
            }
            
            // Command injection
            if (Regex("(Runtime\\.getRuntime|ProcessBuilder|exec|system)").containsMatchIn(line) &&
                Regex("\\+.*\\$|\\+.*\\{").containsMatchIn(line)) {
                issues.add(ReviewIssue(
                    type = "security",
                    severity = "error",
                    title = "Potential command injection",
                    description = "Command execution with user input concatenation",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Validate and sanitize user input before executing commands",
                    codeSnippet = line.trim()
                ))
            }
            
            // Weak cryptography
            if (Regex("(MD5|SHA1|DES|RC4)").containsMatchIn(line)) {
                issues.add(ReviewIssue(
                    type = "security",
                    severity = "warning",
                    title = "Weak cryptographic algorithm",
                    description = "Use of deprecated or weak cryptographic algorithm",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Use stronger algorithms like SHA-256, AES-256, or bcrypt",
                    codeSnippet = line.trim()
                ))
            }
            
            // XSS vulnerability
            if (language in listOf("javascript", "typescript") && 
                Regex("innerHTML|document\\.write|eval\\(|dangerouslySetInnerHTML").containsMatchIn(line) &&
                Regex("\\+.*\\$|\\+.*\\{").containsMatchIn(line)) {
                issues.add(ReviewIssue(
                    type = "security",
                    severity = "warning",
                    title = "Potential XSS vulnerability",
                    description = "Unsanitized user input used in DOM manipulation",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Sanitize user input or use textContent instead of innerHTML",
                    codeSnippet = line.trim()
                ))
            }
        }
        
        return issues
    }
    
    private fun detectStyleIssues(content: String, lines: List<String>, language: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        lines.forEachIndexed { index, line ->
            // Long lines
            if (line.length > 120) {
                issues.add(ReviewIssue(
                    type = "style",
                    severity = "info",
                    title = "Line too long",
                    description = "Line exceeds 120 characters (${line.length} chars)",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Break long lines for better readability",
                    codeSnippet = line.trim().take(100) + "..."
                ))
            }
            
            // Trailing whitespace
            if (line.endsWith(" ") || line.endsWith("\t")) {
                issues.add(ReviewIssue(
                    type = "style",
                    severity = "info",
                    title = "Trailing whitespace",
                    description = "Line has trailing whitespace",
                    location = IssueLocation(filePath, index + 1, null, null, null),
                    suggestion = "Remove trailing whitespace",
                    codeSnippet = line.trim()
                ))
            }
            
            // Inconsistent indentation
            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") && index > 0 && 
                lines[index - 1].isNotEmpty() && lines[index - 1].startsWith(" ")) {
                // Check if it's actually inconsistent (simplified)
                if (line.trim().startsWith("}") || line.trim().startsWith("]") || line.trim().startsWith(")")) {
                    // This is probably fine
                } else {
                    issues.add(ReviewIssue(
                        type = "style",
                        severity = "info",
                        title = "Inconsistent indentation",
                        description = "Indentation style may be inconsistent",
                        location = IssueLocation(filePath, index + 1, null, null, null),
                        suggestion = "Use consistent indentation (spaces or tabs)",
                        codeSnippet = line.trim()
                    ))
                }
            }
        }
        
        // Magic numbers
        val magicNumberPattern = Regex("\\b\\d{3,}\\b")
        lines.forEachIndexed { index, line ->
            if (!line.trim().startsWith("//") && !line.trim().startsWith("*") && !line.trim().startsWith("/*")) {
                magicNumberPattern.findAll(line).forEach { match ->
                    val number = match.value.toIntOrNull()
                    if (number != null && number !in listOf(0, 1, 100, 1000, 1024, 60, 24, 7, 30, 365)) {
                        issues.add(ReviewIssue(
                            type = "style",
                            severity = "info",
                            title = "Magic number",
                            description = "Numeric literal should be a named constant",
                            location = IssueLocation(filePath, index + 1, null, null, null),
                            suggestion = "Extract magic number to a named constant",
                            codeSnippet = line.trim()
                        ))
                    }
                }
            }
        }
        
        return issues
    }
    
    private fun detectPerformanceIssues(content: String, lines: List<String>, language: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        lines.forEachIndexed { index, line ->
            // String concatenation in loops
            if (Regex("for|while").containsMatchIn(line) && index < lines.size - 1) {
                val nextLines = lines.subList(index + 1, minOf(index + 10, lines.size))
                if (nextLines.any { it.contains("+") && it.contains("\"") }) {
                    issues.add(ReviewIssue(
                        type = "performance",
                        severity = "warning",
                        title = "String concatenation in loop",
                        description = "String concatenation in loop can be inefficient",
                        location = IssueLocation(filePath, index + 1, null, null, null),
                        suggestion = "Use StringBuilder or string builder equivalent",
                        codeSnippet = line.trim()
                    ))
                }
            }
            
            // N+1 query pattern (simplified detection)
            if (language in listOf("java", "kotlin") && 
                Regex("for.*in|for\\(.*:.*\\)").containsMatchIn(line) &&
                index < lines.size - 1) {
                val nextLines = lines.subList(index + 1, minOf(index + 5, lines.size))
                if (nextLines.any { it.contains("query") || it.contains("find") || it.contains("get") }) {
                    issues.add(ReviewIssue(
                        type = "performance",
                        severity = "warning",
                        title = "Potential N+1 query problem",
                        description = "Database query inside loop may cause N+1 queries",
                        location = IssueLocation(filePath, index + 1, null, null, null),
                        suggestion = "Use batch loading or eager fetching",
                        codeSnippet = line.trim()
                    ))
                }
            }
        }
        
        return issues
    }
    
    private fun detectBestPracticeViolations(content: String, lines: List<String>, language: String, filePath: String): List<ReviewIssue> {
        val issues = mutableListOf<ReviewIssue>()
        
        // Missing documentation
        val functionPattern = when (language) {
            "kotlin" -> Regex("fun\\s+\\w+")
            "java" -> Regex("(public|private|protected)?\\s*\\w+\\s+\\w+\\s*\\(")
            "python" -> Regex("def\\s+\\w+")
            "javascript", "typescript" -> Regex("(function|const|let|var)\\s+\\w+\\s*[=:]?\\s*\\(")
            else -> null
        }
        
        functionPattern?.let { pattern ->
            lines.forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line) && index > 0) {
                    val prevLines = lines.subList(maxOf(0, index - 3), index)
                    if (prevLines.none { it.trim().startsWith("//") || it.trim().startsWith("/**") || it.trim().startsWith("*") }) {
                        issues.add(ReviewIssue(
                            type = "best_practice",
                            severity = "info",
                            title = "Missing function documentation",
                            description = "Function lacks documentation comment",
                            location = IssueLocation(filePath, index + 1, null, extractFunctionName(line), null),
                            suggestion = "Add documentation comment describing function purpose and parameters",
                            codeSnippet = line.trim()
                        ))
                    }
                }
            }
        }
        
        // Empty catch blocks
        lines.forEachIndexed { index, line ->
            if (line.trim() == "} catch {" || line.trim() == "} catch (Exception e) {" || 
                line.trim() == "} catch (Exception e) {}" || line.trim().startsWith("catch") && index < lines.size - 1) {
                val nextLine = lines.getOrNull(index + 1)
                if (nextLine?.trim() == "}" || nextLine?.trim()?.isEmpty() == true) {
                    issues.add(ReviewIssue(
                        type = "best_practice",
                        severity = "warning",
                        title = "Empty catch block",
                        description = "Exception is caught but not handled",
                        location = IssueLocation(filePath, index + 1, null, null, null),
                        suggestion = "Handle exception appropriately or log it",
                        codeSnippet = line.trim()
                    ))
                }
            }
        }
        
        return issues
    }
    
    private fun extractFunctionName(line: String): String? {
        return Regex("fun\\s+(\\w+)").find(line)?.groupValues?.get(1) ?:
               Regex("def\\s+(\\w+)").find(line)?.groupValues?.get(1) ?:
               Regex("(?:function|const|let|var)\\s+(\\w+)").find(line)?.groupValues?.get(1)
    }
    
    private suspend fun performAIReview(
        content: String,
        language: String,
        file: File,
        relativePath: String,
        focusAreas: List<String>,
        apiClient: PpeApiClient
    ): List<ReviewIssue> {
        return try {
            val focusText = if (focusAreas.contains("all")) {
                "bugs, security issues, code style, performance problems, and best practices"
            } else {
                focusAreas.joinToString(", ")
            }
            
            val prompt = """
                Review the following $language code and identify issues related to: $focusText
                
                For each issue found, provide:
                - Type: bug, security, style, performance, or best_practice
                - Severity: error, warning, or info
                - Title: Brief title
                - Description: Detailed description
                - Line number: Approximate line number
                - Suggestion: How to fix it
                
                Code:
                ```$language
                ${content.take(15000)}
                ```
                
                Return your review as a JSON array of issues, each with: type, severity, title, description, line (optional), suggestion.
            """.trimIndent()
            
            val messages = listOf(Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            ))
            
            val result = apiClient.callApi(messages, temperature = 0.3)
            val response = result.getOrNull()?.text ?: return emptyList()
            
            // Parse AI response (simplified - would need better parsing)
            parseAIReviewResponse(response, relativePath)
        } catch (e: Exception) {
            DebugLogger.w("CodeReviewTool", "AI review failed", exception = e)
            emptyList()
        }
    }
    
    private fun parseAIReviewResponse(response: String, filePath: String): List<ReviewIssue> {
        // Try to extract JSON array from response
        return try {
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = org.json.JSONArray(response.substring(jsonStart, jsonEnd))
                (0 until json.length()).mapNotNull { i ->
                    try {
                        val issue = json.getJSONObject(i)
                        ReviewIssue(
                            type = issue.optString("type", "best_practice"),
                            severity = issue.optString("severity", "info"),
                            title = issue.optString("title", "Issue found"),
                            description = issue.optString("description", ""),
                            location = IssueLocation(
                                filePath,
                                issue.optInt("line").takeIf { it > 0 },
                                null,
                                null,
                                null
                            ),
                            suggestion = issue.optString("suggestion"),
                            codeSnippet = issue.optString("codeSnippet")
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun generateSuggestions(content: String, language: String, issues: List<ReviewIssue>): List<String> {
        val suggestions = mutableSetOf<String>()
        
        if (issues.any { it.type == "security" }) {
            suggestions.add("Review security issues and ensure sensitive data is properly protected")
        }
        
        if (issues.any { it.type == "bug" && it.severity == "error" }) {
            suggestions.add("Fix critical bugs before deployment")
        }
        
        if (issues.count { it.type == "style" } > 10) {
            suggestions.add("Consider running a code formatter to fix style issues")
        }
        
        if (issues.any { it.type == "performance" }) {
            suggestions.add("Review performance issues and optimize critical paths")
        }
        
        if (issues.count { it.type == "best_practice" && it.severity == "info" } > 5) {
            suggestions.add("Add documentation and follow language-specific best practices")
        }
        
        return suggestions.toList()
    }
    
    private fun formatReviewReport(result: CodeReviewResult, fileCount: Int): String {
        return buildString {
            appendLine("# Code Review Report")
            appendLine()
            appendLine("**Files Reviewed:** $fileCount")
            appendLine("**Language:** ${result.language}")
            appendLine("**Overall Rating:** ${result.overallRating.uppercase()}")
            appendLine()
            
            appendLine("## Summary")
            appendLine()
            appendLine("| Category | Count |")
            appendLine("|----------|-------|")
            appendLine("| **Total Issues** | ${result.summary.totalIssues} |")
            appendLine("| Bugs | ${result.summary.bugs} |")
            appendLine("| Security | ${result.summary.security} |")
            appendLine("| Style | ${result.summary.style} |")
            appendLine("| Performance | ${result.summary.performance} |")
            appendLine("| Best Practices | ${result.summary.bestPractices} |")
            appendLine("| **Critical (Error)** | ${result.summary.criticalIssues} |")
            appendLine("| **Warnings** | ${result.summary.warnings} |")
            appendLine("| **Info** | ${result.summary.info} |")
            appendLine()
            
            if (result.issues.isNotEmpty()) {
                appendLine("## Issues")
                appendLine()
                
                // Group by type
                val byType = result.issues.groupBy { it.type }
                byType.forEach { (type, issues) ->
                    appendLine("### ${type.replace("_", " ").uppercase()}")
                    appendLine()
                    issues.take(20).forEach { issue ->
                        appendLine("#### ${issue.title} [${issue.severity.uppercase()}]")
                        appendLine()
                        appendLine("**Location:** ${issue.location.file}:${issue.location.line ?: "?"}")
                        appendLine("**Description:** ${issue.description}")
                        if (issue.suggestion != null) {
                            appendLine("**Suggestion:** ${issue.suggestion}")
                        }
                        if (issue.codeSnippet != null) {
                            appendLine("**Code:**")
                            appendLine("```")
                            appendLine(issue.codeSnippet)
                            appendLine("```")
                        }
                        appendLine()
                    }
                    if (issues.size > 20) {
                        appendLine("... (${issues.size - 20} more issues of this type)")
                        appendLine()
                    }
                }
            }
            
            if (result.suggestions.isNotEmpty()) {
                appendLine("## General Suggestions")
                appendLine()
                result.suggestions.forEach { suggestion ->
                    appendLine("- $suggestion")
                }
                appendLine()
            }
        }
    }
}

/**
 * Code review tool
 */
class CodeReviewTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val apiClient: PpeApiClient? = null
) : DeclarativeTool<CodeReviewToolParams, ToolResult>() {
    
    override val name: String = "code_review"
    override val displayName: String = "Code Review"
    override val description: String = """
        Automated code review tool that analyzes code for bugs, security issues, style problems, performance issues, and best practice violations.
        
        Features:
        - Bug detection (null pointers, division by zero, index out of bounds, unclosed resources)
        - Security analysis (hardcoded credentials, SQL injection, command injection, weak crypto, XSS)
        - Style checking (long lines, trailing whitespace, inconsistent indentation, magic numbers)
        - Performance analysis (string concatenation in loops, N+1 queries)
        - Best practices (missing documentation, empty catch blocks)
        - AI-powered review (when API client available)
        - Comprehensive review reports
        
        Focus areas:
        - bugs: Detect potential bugs
        - security: Find security vulnerabilities
        - style: Check code style issues
        - performance: Identify performance problems
        - best_practices: Check against best practices
        - all: Review all areas (default)
        
        Severity levels:
        - error: Critical issues only
        - warning: Warnings and errors
        - info: All issues (default)
        
        Examples:
        - code_review(filePath="src/main.kt") - Review single file
        - code_review(projectPath="src", focus=["security", "bugs"]) - Review project for security and bugs
        - code_review(filePath="app.js", severity="warning") - Review with warnings and errors only
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "filePath" to PropertySchema(
                type = "string",
                description = "Path to file to review (relative to workspace or absolute)"
            ),
            "projectPath" to PropertySchema(
                type = "string",
                description = "Path to project directory to review (relative to workspace or absolute)"
            ),
            "language" to PropertySchema(
                type = "string",
                description = "Language hint: kotlin, java, javascript, typescript, python, go, rust, cpp, c (auto-detected if not provided)"
            ),
            "focus" to PropertySchema(
                type = "array",
                description = "Focus areas: bugs, security, style, performance, best_practices, all (default: all)",
                items = PropertySchema(type = "string")
            ),
            "severity" to PropertySchema(
                type = "string",
                description = "Minimum severity: info, warning, error (default: info)",
                enum = listOf("info", "warning", "error")
            )
        )
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: CodeReviewToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<CodeReviewToolParams, ToolResult> {
        return CodeReviewToolInvocation(params, workspaceRoot, apiClient)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): CodeReviewToolParams {
        val focusList = (params["focus"] as? List<*>)?.mapNotNull { it as? String }
        
        return CodeReviewToolParams(
            filePath = params["filePath"] as? String,
            projectPath = params["projectPath"] as? String,
            language = params["language"] as? String,
            focus = focusList,
            severity = params["severity"] as? String
        )
    }
}
