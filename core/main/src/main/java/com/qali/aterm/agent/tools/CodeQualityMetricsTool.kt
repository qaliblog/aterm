package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Code quality metrics tool
 * Comprehensive code quality analysis with complexity metrics, code smells, and coding standards
 */
data class CodeQualityMetricsToolParams(
    val projectPath: String? = null, // Project root path, or null for workspace root
    val filePath: String? = null, // Specific file to analyze, or null for entire project
    val metrics: List<String>? = null, // Specific metrics to calculate: "complexity", "smells", "standards", "all"
    val language: String? = null // Language filter: "kotlin", "java", "javascript", "python", etc.
)

data class CodeQualityResult(
    val overallScore: Double, // 0.0 to 100.0
    val complexityMetrics: ComplexityMetrics,
    val codeSmells: List<CodeSmell>,
    val standardsViolations: List<StandardsViolation>,
    val fileMetrics: Map<String, FileMetrics>,
    val summary: String
)

data class ComplexityMetrics(
    val cyclomaticComplexity: Int,
    val cognitiveComplexity: Int,
    val maxFunctionComplexity: Int,
    val averageFunctionComplexity: Double,
    val totalFunctions: Int,
    val highComplexityFunctions: List<ComplexityIssue>
)

data class ComplexityIssue(
    val file: String,
    val function: String,
    val complexity: Int,
    val threshold: Int,
    val line: Int
)

data class CodeSmell(
    val type: String, // "long_method", "large_class", "duplicate_code", "magic_number", "dead_code", etc.
    val severity: String, // "low", "medium", "high"
    val file: String,
    val location: String, // function/class name or line number
    val description: String,
    val suggestion: String?
)

data class StandardsViolation(
    val rule: String,
    val severity: String, // "info", "warning", "error"
    val file: String,
    val line: Int?,
    val message: String,
    val suggestion: String?
)

data class FileMetrics(
    val filePath: String,
    val linesOfCode: Int,
    val linesOfComments: Int,
    val blankLines: Int,
    val functions: Int,
    val classes: Int,
    val cyclomaticComplexity: Int,
    val cognitiveComplexity: Int,
    val maintainabilityIndex: Double // 0.0 to 100.0
)

class CodeQualityMetricsToolInvocation(
    toolParams: CodeQualityMetricsToolParams,
    private val workspaceRoot: String
) : ToolInvocation<CodeQualityMetricsToolParams, ToolResult> {
    
    override val params: CodeQualityMetricsToolParams = toolParams
    
    override fun getDescription(): String {
        val target = params.filePath ?: "project"
        return "Analyzing code quality metrics for: $target"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult = withContext(Dispatchers.IO) {
        if (signal?.isAborted() == true) {
            return@withContext ToolResult(
                llmContent = "Code quality analysis cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            val projectPath = params.projectPath?.let { File(it) } 
                ?: File(workspaceRoot)
            
            if (!projectPath.exists()) {
                return@withContext ToolResult(
                    llmContent = "Project path does not exist: ${projectPath.absolutePath}",
                    returnDisplay = "Error: Path not found",
                    error = ToolError(
                        message = "Project path does not exist",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            val filesToAnalyze = if (params.filePath != null) {
                listOf(File(projectPath, params.filePath))
            } else {
                getCodeFiles(projectPath, params.language)
            }
            
            updateOutput?.invoke("📊 Analyzing ${filesToAnalyze.size} files...")
            
            val fileMetrics = mutableMapOf<String, FileMetrics>()
            val allCodeSmells = mutableListOf<CodeSmell>()
            val allStandardsViolations = mutableListOf<StandardsViolation>()
            val allComplexityIssues = mutableListOf<ComplexityIssue>()
            var totalCyclomatic = 0
            var totalCognitive = 0
            var totalFunctions = 0
            
            filesToAnalyze.forEach { file ->
                if (signal?.isAborted() == true) return@withContext ToolResult(
                    llmContent = "Analysis cancelled",
                    returnDisplay = "Cancelled"
                )
                
                val relativePath = file.relativeTo(projectPath).path
                updateOutput?.invoke("Analyzing: $relativePath")
                
                val content = file.readText()
                val language = detectLanguage(file)
                
                val metrics = analyzeFile(file, content, language)
                fileMetrics[relativePath] = metrics
                
                totalCyclomatic += metrics.cyclomaticComplexity
                totalCognitive += metrics.cognitiveComplexity
                totalFunctions += metrics.functions
                
                // Detect code smells
                val smells = detectCodeSmells(file, content, language, metrics)
                allCodeSmells.addAll(smells)
                
                // Check coding standards
                val violations = checkCodingStandards(file, content, language)
                allStandardsViolations.addAll(violations)
                
                // Find high complexity functions
                val complexityIssues = findHighComplexityFunctions(file, content, language)
                allComplexityIssues.addAll(complexityIssues)
            }
            
            val avgCyclomatic = if (totalFunctions > 0) totalCyclomatic.toDouble() / totalFunctions else 0.0
            val maxComplexity = allComplexityIssues.maxOfOrNull { it.complexity } ?: 0
            
            val complexityMetrics = ComplexityMetrics(
                cyclomaticComplexity = totalCyclomatic,
                cognitiveComplexity = totalCognitive,
                maxFunctionComplexity = maxComplexity,
                averageFunctionComplexity = avgCyclomatic,
                totalFunctions = totalFunctions,
                highComplexityFunctions = allComplexityIssues
            )
            
            // Calculate overall quality score
            val overallScore = calculateQualityScore(
                complexityMetrics,
                allCodeSmells,
                allStandardsViolations,
                fileMetrics
            )
            
            val result = CodeQualityResult(
                overallScore = overallScore,
                complexityMetrics = complexityMetrics,
                codeSmells = allCodeSmells,
                standardsViolations = allStandardsViolations,
                fileMetrics = fileMetrics,
                summary = generateSummary(overallScore, complexityMetrics, allCodeSmells, allStandardsViolations)
            )
            
            val formattedResult = formatQualityReport(result, params.metrics ?: listOf("all"))
            
            DebugLogger.i("CodeQualityMetricsTool", "Quality analysis completed", mapOf(
                "files" to filesToAnalyze.size,
                "overall_score" to overallScore,
                "smells" to allCodeSmells.size,
                "violations" to allStandardsViolations.size
            ))
            
            ToolResult(
                llmContent = formattedResult,
                returnDisplay = "Quality Score: ${String.format("%.1f", overallScore)}/100 (${allCodeSmells.size} smells, ${allStandardsViolations.size} violations)"
            )
        } catch (e: Exception) {
            DebugLogger.e("CodeQualityMetricsTool", "Error analyzing code quality", exception = e)
            ToolResult(
                llmContent = "Error analyzing code quality: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun getCodeFiles(projectPath: File, language: String?): List<File> {
        val extensions = when (language?.lowercase()) {
            "kotlin" -> listOf("kt", "kts")
            "java" -> listOf("java")
            "javascript" -> listOf("js", "jsx", "mjs")
            "typescript" -> listOf("ts", "tsx")
            "python" -> listOf("py")
            "go" -> listOf("go")
            "rust" -> listOf("rs")
            "cpp", "c++" -> listOf("cpp", "cc", "cxx", "c")
            else -> listOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "go", "rs", "cpp", "cc", "c")
        }
        
        return projectPath.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.lowercase() in extensions }
            .filter { !it.path.contains("/.git/") }
            .filter { !it.path.contains("/node_modules/") }
            .filter { !it.path.contains("/build/") }
            .filter { !it.path.contains("/.gradle/") }
            .toList()
    }
    
    private fun detectLanguage(file: File): String {
        return when (file.extension.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "jsx", "mjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx", "c" -> "cpp"
            else -> "unknown"
        }
    }
    
    private fun analyzeFile(file: File, content: String, language: String): FileMetrics {
        val lines = content.lines()
        val linesOfCode = lines.count { it.trim().isNotEmpty() && !it.trim().startsWith("//") && !it.trim().startsWith("/*") }
        val linesOfComments = lines.count { 
            it.trim().startsWith("//") || 
            it.trim().startsWith("/*") || 
            it.trim().startsWith("*") ||
            it.contains("/*") || 
            it.contains("*/")
        }
        val blankLines = lines.count { it.trim().isEmpty() }
        
        val functions = countFunctions(content, language)
        val classes = countClasses(content, language)
        val cyclomatic = calculateCyclomaticComplexity(content, language)
        val cognitive = calculateCognitiveComplexity(content, language)
        val maintainability = calculateMaintainabilityIndex(linesOfCode, cyclomatic, cognitive)
        
        return FileMetrics(
            filePath = file.path,
            linesOfCode = linesOfCode,
            linesOfComments = linesOfComments,
            blankLines = blankLines,
            functions = functions,
            classes = classes,
            cyclomaticComplexity = cyclomatic,
            cognitiveComplexity = cognitive,
            maintainabilityIndex = maintainability
        )
    }
    
    private fun countFunctions(content: String, language: String): Int {
        return when (language) {
            "kotlin", "java" -> {
                Regex("""(fun|function|def)\s+\w+\s*[\(<]""").findAll(content).count()
            }
            "javascript", "typescript" -> {
                Regex("""(function\s+\w+|const\s+\w+\s*=\s*(?:async\s+)?\(|=>\s*\{)""").findAll(content).count()
            }
            "python" -> {
                Regex("""def\s+\w+\s*\(""").findAll(content).count()
            }
            "go" -> {
                Regex("""func\s+\w+\s*\(""").findAll(content).count()
            }
            "rust" -> {
                Regex("""fn\s+\w+\s*\(""").findAll(content).count()
            }
            else -> 0
        }
    }
    
    private fun countClasses(content: String, language: String): Int {
        return when (language) {
            "kotlin", "java" -> {
                Regex("""(class|interface|object|enum\s+class)\s+\w+""").findAll(content).count()
            }
            "javascript", "typescript" -> {
                Regex("""(class|interface)\s+\w+""").findAll(content).count()
            }
            "python" -> {
                Regex("""class\s+\w+""").findAll(content).count()
            }
            "go" -> {
                Regex("""type\s+\w+\s+struct""").findAll(content).count()
            }
            "rust" -> {
                Regex("""(struct|enum|trait|impl)\s+\w+""").findAll(content).count()
            }
            else -> 0
        }
    }
    
    private fun calculateCyclomaticComplexity(content: String, language: String): Int {
        // Cyclomatic complexity = 1 + number of decision points
        val decisionPoints = when (language) {
            "kotlin", "java", "javascript", "typescript", "go", "rust", "cpp" -> {
                var count = 0
                // if, else if, while, for, switch/case, catch, &&, ||, ?:
                count += Regex("""\bif\s*\(""").findAll(content).count()
                count += Regex("""\belse\s+if\s*\(""").findAll(content).count()
                count += Regex("""\bwhile\s*\(""").findAll(content).count()
                count += Regex("""\bfor\s*\(""").findAll(content).count()
                count += Regex("""\bswitch\s*\(""").findAll(content).count()
                count += Regex("""\bcase\s+""").findAll(content).count()
                count += Regex("""\bcatch\s*\(""").findAll(content).count()
                count += Regex("""\?\s*[^:]*:""").findAll(content).count() // ternary
                count += Regex("""&&""").findAll(content).count()
                count += Regex("""\|\|""").findAll(content).count()
                count
            }
            "python" -> {
                var count = 0
                count += Regex("""\bif\s+""").findAll(content).count()
                count += Regex("""\belif\s+""").findAll(content).count()
                count += Regex("""\bwhile\s+""").findAll(content).count()
                count += Regex("""\bfor\s+""").findAll(content).count()
                count += Regex("""\bexcept\s+""").findAll(content).count()
                count += Regex("""\band\b""").findAll(content).count()
                count += Regex("""\bor\b""").findAll(content).count()
                count
            }
            else -> 0
        }
        return 1 + decisionPoints
    }
    
    private fun calculateCognitiveComplexity(content: String, language: String): Int {
        // Cognitive complexity is similar but penalizes nesting more
        var complexity = 0
        var nestingLevel = 0
        
        content.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("if ") || trimmed.startsWith("if(") -> {
                    complexity += 1 + nestingLevel
                    nestingLevel++
                }
                trimmed.startsWith("else") -> {
                    nestingLevel++
                }
                trimmed.startsWith("while") || trimmed.startsWith("for") -> {
                    complexity += 1 + nestingLevel
                    nestingLevel++
                }
                trimmed.startsWith("switch") || trimmed.startsWith("when") -> {
                    complexity += 1 + nestingLevel
                    nestingLevel++
                }
                trimmed.startsWith("}") || trimmed.startsWith("]") || trimmed.startsWith(")") -> {
                    if (nestingLevel > 0) nestingLevel--
                }
            }
        }
        
        return complexity
    }
    
    private fun calculateMaintainabilityIndex(
        linesOfCode: Int,
        cyclomaticComplexity: Int,
        cognitiveComplexity: Int
    ): Double {
        // Simplified maintainability index
        // MI = 171 - 5.2 * ln(Halstead Volume) - 0.23 * (Cyclomatic Complexity) - 16.2 * ln(Lines of Code)
        // Simplified version: 100 - (complexity * 2) - (lines / 100)
        val complexityPenalty = (cyclomaticComplexity + cognitiveComplexity) * 0.5
        val sizePenalty = linesOfCode / 100.0
        val mi = 100.0 - complexityPenalty - sizePenalty
        return mi.coerceIn(0.0, 100.0)
    }
    
    private fun detectCodeSmells(
        file: File,
        content: String,
        language: String,
        metrics: FileMetrics
    ): List<CodeSmell> {
        val smells = mutableListOf<CodeSmell>()
        val lines = content.lines()
        
        // Long method/function
        if (metrics.linesOfCode > 100) {
            smells.add(CodeSmell(
                type = "long_method",
                severity = if (metrics.linesOfCode > 200) "high" else "medium",
                file = file.name,
                location = "file",
                description = "File has ${metrics.linesOfCode} lines of code (recommended: < 100)",
                suggestion = "Consider splitting into smaller functions or classes"
            ))
        }
        
        // Large class
        if (metrics.classes > 0 && metrics.linesOfCode / metrics.classes > 300) {
            smells.add(CodeSmell(
                type = "large_class",
                severity = "medium",
                file = file.name,
                location = "class",
                description = "Class has ${metrics.linesOfCode / metrics.classes} lines per class (recommended: < 300)",
                suggestion = "Consider splitting into smaller classes"
            ))
        }
        
        // High complexity
        if (metrics.cyclomaticComplexity > 20) {
            smells.add(CodeSmell(
                type = "high_complexity",
                severity = if (metrics.cyclomaticComplexity > 50) "high" else "medium",
                file = file.name,
                location = "file",
                description = "Cyclomatic complexity is ${metrics.cyclomaticComplexity} (recommended: < 20)",
                suggestion = "Refactor to reduce complexity by extracting methods"
            ))
        }
        
        // Magic numbers
        val magicNumberPattern = Regex("""\b\d{3,}\b""")
        val magicNumbers = magicNumberPattern.findAll(content).count()
        if (magicNumbers > 5) {
            smells.add(CodeSmell(
                type = "magic_number",
                severity = "low",
                file = file.name,
                location = "file",
                description = "Found $magicNumbers potential magic numbers",
                suggestion = "Replace magic numbers with named constants"
            ))
        }
        
        // Dead code (commented out code)
        val commentedCodeBlocks = content.split("/*").size - 1
        if (commentedCodeBlocks > 3) {
            smells.add(CodeSmell(
                type = "dead_code",
                severity = "low",
                file = file.name,
                location = "file",
                description = "Found $commentedCodeBlocks commented code blocks",
                suggestion = "Remove commented code or document why it's kept"
            ))
        }
        
        // Low maintainability
        if (metrics.maintainabilityIndex < 50) {
            smells.add(CodeSmell(
                type = "low_maintainability",
                severity = "medium",
                file = file.name,
                location = "file",
                description = "Maintainability index is ${String.format("%.1f", metrics.maintainabilityIndex)} (recommended: > 50)",
                suggestion = "Refactor to improve maintainability"
            ))
        }
        
        return smells
    }
    
    private fun checkCodingStandards(
        file: File,
        content: String,
        language: String
    ): List<StandardsViolation> {
        val violations = mutableListOf<StandardsViolation>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trim()
            
            // Long lines
            if (line.length > 120) {
                violations.add(StandardsViolation(
                    rule = "max_line_length",
                    severity = "warning",
                    file = file.name,
                    line = lineNum,
                    message = "Line exceeds 120 characters (${line.length} chars)",
                    suggestion = "Break long lines for better readability"
                ))
            }
            
            // Trailing whitespace
            if (line.endsWith(" ") || line.endsWith("\t")) {
                violations.add(StandardsViolation(
                    rule = "no_trailing_whitespace",
                    severity = "info",
                    file = file.name,
                    line = lineNum,
                    message = "Trailing whitespace detected",
                    suggestion = "Remove trailing whitespace"
                ))
            }
            
            // TODO/FIXME comments
            if (trimmed.contains("TODO", ignoreCase = true) || trimmed.contains("FIXME", ignoreCase = true)) {
                violations.add(StandardsViolation(
                    rule = "todo_comments",
                    severity = "info",
                    file = file.name,
                    line = lineNum,
                    message = "TODO/FIXME comment found",
                    suggestion = "Address TODO/FIXME comments"
                ))
            }
        }
        
        // Missing documentation
        if (language in listOf("kotlin", "java", "python") && !content.contains("/**") && !content.contains("\"\"\"")) {
            violations.add(StandardsViolation(
                rule = "documentation",
                severity = "warning",
                file = file.name,
                line = null,
                message = "No documentation comments found",
                suggestion = "Add documentation comments for public APIs"
            ))
        }
        
        return violations
    }
    
    private fun findHighComplexityFunctions(
        file: File,
        content: String,
        language: String
    ): List<ComplexityIssue> {
        val issues = mutableListOf<ComplexityIssue>()
        val lines = content.lines()
        var currentFunction: String? = null
        var functionStartLine = 0
        var functionComplexity = 0
        var braceDepth = 0
        var inFunction = false
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trim()
            
            // Detect function start
            when (language) {
                "kotlin", "java" -> {
                    if (Regex("""fun\s+(\w+)""").find(trimmed) != null || 
                        Regex("""\w+\s+(\w+)\s*\([^)]*\)\s*\{""").find(trimmed) != null) {
                        if (inFunction && functionComplexity > 10) {
                            issues.add(ComplexityIssue(
                                file = file.name,
                                function = currentFunction ?: "unknown",
                                complexity = functionComplexity,
                                threshold = 10,
                                line = functionStartLine
                            ))
                        }
                        currentFunction = Regex("""(\w+)\s*\(""").find(trimmed)?.groupValues?.get(1)
                        functionStartLine = lineNum
                        functionComplexity = 1
                        inFunction = true
                        braceDepth = 0
                    }
                }
            }
            
            // Count complexity within function
            if (inFunction) {
                functionComplexity += calculateCyclomaticComplexity(line, language) - 1
                
                braceDepth += line.count { it == '{' } - line.count { it == '}' }
                if (braceDepth <= 0 && trimmed.isNotEmpty() && !trimmed.startsWith("//")) {
                    if (functionComplexity > 10) {
                        issues.add(ComplexityIssue(
                            file = file.name,
                            function = currentFunction ?: "unknown",
                            complexity = functionComplexity,
                            threshold = 10,
                            line = functionStartLine
                        ))
                    }
                    inFunction = false
                }
            }
        }
        
        return issues
    }
    
    private fun calculateQualityScore(
        complexity: ComplexityMetrics,
        smells: List<CodeSmell>,
        violations: List<StandardsViolation>,
        fileMetrics: Map<String, FileMetrics>
    ): Double {
        var score = 100.0
        
        // Penalize high complexity
        if (complexity.averageFunctionComplexity > 10) {
            score -= (complexity.averageFunctionComplexity - 10) * 2
        }
        
        // Penalize code smells
        smells.forEach { smell ->
            when (smell.severity) {
                "high" -> score -= 5.0
                "medium" -> score -= 2.0
                "low" -> score -= 0.5
            }
        }
        
        // Penalize standards violations
        violations.forEach { violation ->
            when (violation.severity) {
                "error" -> score -= 3.0
                "warning" -> score -= 1.0
                "info" -> score -= 0.2
            }
        }
        
        // Penalize low maintainability
        val avgMaintainability = fileMetrics.values.map { it.maintainabilityIndex }.average()
        if (avgMaintainability < 50) {
            score -= (50 - avgMaintainability) * 0.5
        }
        
        return score.coerceIn(0.0, 100.0)
    }
    
    private fun generateSummary(
        score: Double,
        complexity: ComplexityMetrics,
        smells: List<CodeSmell>,
        violations: List<StandardsViolation>
    ): String {
        return buildString {
            appendLine("Overall Quality Score: ${String.format("%.1f", score)}/100")
            appendLine("Complexity: ${complexity.cyclomaticComplexity} (avg: ${String.format("%.1f", complexity.averageFunctionComplexity)})")
            appendLine("Code Smells: ${smells.size}")
            appendLine("Standards Violations: ${violations.size}")
        }
    }
    
    private fun formatQualityReport(
        result: CodeQualityResult,
        metrics: List<String>
    ): String {
        val includeAll = metrics.contains("all") || metrics.isEmpty()
        
        return buildString {
            appendLine("# Code Quality Metrics Report")
            appendLine()
            appendLine("## Overall Quality Score")
            appendLine()
            appendLine("**Score:** ${String.format("%.1f", result.overallScore)}/100")
            appendLine()
            appendLine(result.summary)
            appendLine()
            
            if (includeAll || metrics.contains("complexity")) {
                appendLine("## Complexity Metrics")
                appendLine()
                appendLine("- **Total Cyclomatic Complexity:** ${result.complexityMetrics.cyclomaticComplexity}")
                appendLine("- **Total Cognitive Complexity:** ${result.complexityMetrics.cognitiveComplexity}")
                appendLine("- **Total Functions:** ${result.complexityMetrics.totalFunctions}")
                appendLine("- **Average Function Complexity:** ${String.format("%.2f", result.complexityMetrics.averageFunctionComplexity)}")
                appendLine("- **Max Function Complexity:** ${result.complexityMetrics.maxFunctionComplexity}")
                appendLine()
                
                if (result.complexityMetrics.highComplexityFunctions.isNotEmpty()) {
                    appendLine("### High Complexity Functions")
                    result.complexityMetrics.highComplexityFunctions.take(10).forEach { issue ->
                        appendLine("- **${issue.function}** in `${issue.file}` (line ${issue.line}): complexity ${issue.complexity} (threshold: ${issue.threshold})")
                    }
                    appendLine()
                }
            }
            
            if (includeAll || metrics.contains("smells")) {
                appendLine("## Code Smells")
                appendLine()
                if (result.codeSmells.isEmpty()) {
                    appendLine("✅ No code smells detected!")
                } else {
                    val bySeverity = result.codeSmells.groupBy { it.severity }
                    listOf("high", "medium", "low").forEach { severity ->
                        bySeverity[severity]?.forEach { smell ->
                            appendLine("### ${severity.uppercase()}: ${smell.type}")
                            appendLine("- **File:** ${smell.file}")
                            appendLine("- **Location:** ${smell.location}")
                            appendLine("- **Description:** ${smell.description}")
                            if (smell.suggestion != null) {
                                appendLine("- **Suggestion:** ${smell.suggestion}")
                            }
                            appendLine()
                        }
                    }
                }
                appendLine()
            }
            
            if (includeAll || metrics.contains("standards")) {
                appendLine("## Coding Standards Violations")
                appendLine()
                if (result.standardsViolations.isEmpty()) {
                    appendLine("✅ No standards violations found!")
                } else {
                    val bySeverity = result.standardsViolations.groupBy { it.severity }
                    listOf("error", "warning", "info").forEach { severity ->
                        bySeverity[severity]?.forEach { violation ->
                            appendLine("### ${severity.uppercase()}: ${violation.rule}")
                            appendLine("- **File:** ${violation.file}")
                            if (violation.line != null) {
                                appendLine("- **Line:** ${violation.line}")
                            }
                            appendLine("- **Message:** ${violation.message}")
                            if (violation.suggestion != null) {
                                appendLine("- **Suggestion:** ${violation.suggestion}")
                            }
                            appendLine()
                        }
                    }
                }
                appendLine()
            }
            
            if (includeAll) {
                appendLine("## File Metrics Summary")
                appendLine()
                appendLine("| File | LOC | Functions | Classes | Complexity | Maintainability |")
                appendLine("|------|-----|-----------|---------|------------|-----------------|")
                result.fileMetrics.entries.take(20).forEach { (path, metrics) ->
                    appendLine("| ${File(path).name} | ${metrics.linesOfCode} | ${metrics.functions} | ${metrics.classes} | ${metrics.cyclomaticComplexity} | ${String.format("%.1f", metrics.maintainabilityIndex)} |")
                }
                if (result.fileMetrics.size > 20) {
                    appendLine("| ... | ... | ... | ... | ... | ... |")
                }
                appendLine()
            }
        }
    }
}

/**
 * Code quality metrics tool
 */
class CodeQualityMetricsTool(
    private val workspaceRoot: String
) : DeclarativeTool<CodeQualityMetricsToolParams, ToolResult>() {
    
    override val name: String = "analyze_code_quality"
    override val displayName: String = "Code Quality Metrics"
    override val description: String = """
        Comprehensive code quality analysis tool. Calculates complexity metrics, detects code smells, and checks coding standards.
        
        Metrics:
        - Complexity metrics (cyclomatic, cognitive)
        - Code smells detection
        - Coding standards compliance
        - Maintainability index
        - File-level metrics
        
        Supported Languages:
        - Kotlin, Java
        - JavaScript, TypeScript
        - Python
        - Go, Rust
        - C/C++
        
        Examples:
        - analyze_code_quality() - Analyze entire project
        - analyze_code_quality(filePath="src/main.kt") - Analyze specific file
        - analyze_code_quality(metrics=["complexity", "smells"]) - Specific metrics only
        - analyze_code_quality(language="kotlin") - Filter by language
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path, or omit for workspace root"
            ),
            "filePath" to PropertySchema(
                type = "string",
                description = "Specific file to analyze (relative to project root)"
            ),
            "metrics" to PropertySchema(
                type = "array",
                description = "Specific metrics to calculate: 'complexity', 'smells', 'standards', or 'all'",
                items = PropertySchema(type = "string", enum = listOf("complexity", "smells", "standards", "all"))
            ),
            "language" to PropertySchema(
                type = "string",
                description = "Language filter: 'kotlin', 'java', 'javascript', 'python', etc."
            )
        ),
        required = emptyList()
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: CodeQualityMetricsToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<CodeQualityMetricsToolParams, ToolResult> {
        return CodeQualityMetricsToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): CodeQualityMetricsToolParams {
        @Suppress("UNCHECKED_CAST")
        val metricsList = params["metrics"] as? List<*>
        return CodeQualityMetricsToolParams(
            projectPath = params["projectPath"] as? String,
            filePath = params["filePath"] as? String,
            metrics = metricsList?.map { it.toString() } as? List<String>,
            language = params["language"] as? String
        )
    }
}
