package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Code coverage analysis tool
 * Analyzes code coverage and identifies untested areas
 */
data class CoverageAnalysisToolParams(
    val projectPath: String? = null, // Project root path, or null for workspace root
    val framework: String? = null, // "gradle", "maven", "pytest", "npm", or null for auto-detect
    val generateReport: Boolean = true, // Whether to generate coverage report
    val minCoverage: Double? = null // Minimum coverage threshold (0.0-1.0)
)

data class CoverageResult(
    val framework: String,
    val overallCoverage: Double, // 0.0-1.0
    val lineCoverage: Double? = null,
    val branchCoverage: Double? = null,
    val functionCoverage: Double? = null,
    val fileCoverage: Map<String, Double>, // File path -> coverage percentage
    val uncoveredFiles: List<String>,
    val lowCoverageFiles: List<Pair<String, Double>>, // Files with coverage < threshold
    val reportPath: String? = null,
    val output: String
)

class CoverageAnalysisToolInvocation(
    toolParams: CoverageAnalysisToolParams,
    private val workspaceRoot: String
) : ToolInvocation<CoverageAnalysisToolParams, ToolResult> {
    
    override val params: CoverageAnalysisToolParams = toolParams
    
    override fun getDescription(): String {
        val framework = params.framework ?: "auto-detect"
        return "Analyzing code coverage (framework: $framework)"
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
                llmContent = "Coverage analysis cancelled",
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
            
            updateOutput?.invoke("🔍 Detecting test framework...")
            
            // Detect test framework
            val framework = params.framework?.lowercase() 
                ?: detectTestFramework(projectPath)
            
            if (framework == null) {
                return@withContext ToolResult(
                    llmContent = "Could not detect test framework. Please specify framework parameter.",
                    returnDisplay = "Error: Framework not detected",
                    error = ToolError(
                        message = "Test framework not detected",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            updateOutput?.invoke("📊 Generating coverage report with $framework...")
            
            // Generate coverage report
            val coverageResult = when (framework) {
                "gradle" -> generateGradleCoverage(projectPath, params.generateReport, updateOutput, signal)
                "maven" -> generateMavenCoverage(projectPath, params.generateReport, updateOutput, signal)
                "pytest" -> generatePytestCoverage(projectPath, params.generateReport, updateOutput, signal)
                "npm", "jest" -> generateNpmCoverage(projectPath, params.generateReport, updateOutput, signal)
                else -> {
                    return@withContext ToolResult(
                        llmContent = "Unsupported test framework: $framework",
                        returnDisplay = "Error: Unsupported framework",
                        error = ToolError(
                            message = "Unsupported test framework",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
            }
            
            updateOutput?.invoke("✅ Coverage analysis completed")
            
            // Filter low coverage files if threshold specified
            val minCoverage = params.minCoverage ?: 0.0
            val lowCoverageFiles = coverageResult.fileCoverage
                .filter { it.value < minCoverage }
                .map { Pair(it.key, it.value) }
                .sortedByDescending { it.second }
            
            val filteredResult = coverageResult.copy(
                lowCoverageFiles = lowCoverageFiles
            )
            
            // Format results
            val formattedResult = formatCoverageResults(filteredResult, minCoverage)
            
            DebugLogger.i("CoverageAnalysisTool", "Coverage analysis completed", mapOf(
                "framework" to framework,
                "overall_coverage" to filteredResult.overallCoverage,
                "files_analyzed" to filteredResult.fileCoverage.size,
                "uncovered_files" to filteredResult.uncoveredFiles.size,
                "low_coverage_files" to lowCoverageFiles.size
            ))
            
            ToolResult(
                llmContent = formattedResult,
                returnDisplay = "Coverage: ${(filteredResult.overallCoverage * 100).toInt()}%"
            )
        } catch (e: Exception) {
            DebugLogger.e("CoverageAnalysisTool", "Error analyzing coverage", exception = e)
            ToolResult(
                llmContent = "Error analyzing coverage: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun detectTestFramework(projectPath: File): String? {
        // Check for Gradle
        if (File(projectPath, "build.gradle").exists() || 
            File(projectPath, "build.gradle.kts").exists() ||
            File(projectPath, "settings.gradle").exists() ||
            File(projectPath, "settings.gradle.kts").exists()) {
            return "gradle"
        }
        
        // Check for Maven
        if (File(projectPath, "pom.xml").exists()) {
            return "maven"
        }
        
        // Check for pytest
        if (File(projectPath, "pytest.ini").exists() ||
            File(projectPath, "setup.py").exists() ||
            File(projectPath, "pyproject.toml").exists()) {
            return "pytest"
        }
        
        // Check for npm/Jest
        if (File(projectPath, "package.json").exists()) {
            val packageJson = File(projectPath, "package.json").readText()
            if (packageJson.contains("\"jest\"") || 
                packageJson.contains("\"coverage\"")) {
                return "npm"
            }
        }
        
        return null
    }
    
    private suspend fun generateGradleCoverage(
        projectPath: File,
        generateReport: Boolean,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): CoverageResult = withContext(Dispatchers.IO) {
        val command = if (generateReport) {
            "./gradlew test jacocoTestReport"
        } else {
            "./gradlew test"
        }
        
        val output = executeCommand(command, projectPath, updateOutput, signal)
        
        // Parse Gradle/JaCoCo coverage
        val coverage = parseGradleCoverage(output, projectPath)
        coverage
    }
    
    private suspend fun generateMavenCoverage(
        projectPath: File,
        generateReport: Boolean,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): CoverageResult = withContext(Dispatchers.IO) {
        val command = if (generateReport) {
            "mvn test jacoco:report"
        } else {
            "mvn test"
        }
        
        val output = executeCommand(command, projectPath, updateOutput, signal)
        
        // Parse Maven/JaCoCo coverage
        val coverage = parseMavenCoverage(output, projectPath)
        coverage
    }
    
    private suspend fun generatePytestCoverage(
        projectPath: File,
        generateReport: Boolean,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): CoverageResult = withContext(Dispatchers.IO) {
        val command = if (generateReport) {
            "pytest --cov=. --cov-report=term --cov-report=html"
        } else {
            "pytest --cov=. --cov-report=term"
        }
        
        val output = executeCommand(command, projectPath, updateOutput, signal)
        
        // Parse pytest coverage
        val coverage = parsePytestCoverage(output, projectPath)
        coverage
    }
    
    private suspend fun generateNpmCoverage(
        projectPath: File,
        generateReport: Boolean,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): CoverageResult = withContext(Dispatchers.IO) {
        val command = if (generateReport) {
            "npm test -- --coverage"
        } else {
            "npm test -- --coverage --coverageReporters=text"
        }
        
        val output = executeCommand(command, projectPath, updateOutput, signal)
        
        // Parse npm/Jest coverage
        val coverage = parseNpmCoverage(output, projectPath)
        coverage
    }
    
    private suspend fun executeCommand(
        command: String,
        workingDir: File,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): String = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder()
            .command("sh", "-c", command)
            .directory(workingDir)
            .redirectErrorStream(true)
        
        val process = processBuilder.start()
        
        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (signal?.isAborted() == true) {
                process.destroy()
                throw InterruptedException("Coverage analysis cancelled")
            }
            output.appendLine(line)
            updateOutput?.invoke(line ?: "")
        }
        
        process.waitFor()
        output.toString()
    }
    
    private fun parseGradleCoverage(output: String, projectPath: File): CoverageResult {
        // Try to find JaCoCo report
        val jacocoReport = File(projectPath, "build/reports/jacoco/test/html/index.html")
        val jacocoXml = File(projectPath, "build/reports/jacoco/test/jacocoTestReport.xml")
        
        var overallCoverage = 0.0
        var lineCoverage: Double? = null
        var branchCoverage: Double? = null
        var functionCoverage: Double? = null
        val fileCoverage = mutableMapOf<String, Double>()
        val uncoveredFiles = mutableListOf<String>()
        
        // Try to parse XML report if available
        if (jacocoXml.exists()) {
            try {
                val xmlContent = jacocoXml.readText()
                // Simple XML parsing for coverage
                val counterRegex = Regex("""type="(LINE|BRANCH|METHOD)".*missed="(\d+)".*covered="(\d+)"""")
                var totalMissed = 0
                var totalCovered = 0
                
                counterRegex.findAll(xmlContent).forEach { match ->
                    val missed = match.groupValues[2].toIntOrNull() ?: 0
                    val covered = match.groupValues[3].toIntOrNull() ?: 0
                    totalMissed += missed
                    totalCovered += covered
                }
                
                if (totalMissed + totalCovered > 0) {
                    overallCoverage = totalCovered.toDouble() / (totalMissed + totalCovered)
                }
            } catch (e: Exception) {
                DebugLogger.w("CoverageAnalysisTool", "Failed to parse JaCoCo XML", exception = e)
            }
        }
        
        // Extract coverage from output if available
        val coverageRegex = Regex("""(\d+(?:\.\d+)?)%""")
        coverageRegex.find(output)?.let {
            overallCoverage = it.groupValues[1].toDoubleOrNull()?.div(100.0) ?: overallCoverage
        }
        
        return CoverageResult(
            framework = "gradle",
            overallCoverage = overallCoverage,
            lineCoverage = lineCoverage,
            branchCoverage = branchCoverage,
            functionCoverage = functionCoverage,
            fileCoverage = fileCoverage,
            uncoveredFiles = uncoveredFiles,
            lowCoverageFiles = emptyList(),
            reportPath = if (jacocoReport.exists()) jacocoReport.absolutePath else null,
            output = output
        )
    }
    
    private fun parseMavenCoverage(output: String, projectPath: File): CoverageResult {
        // Similar to Gradle, parse Maven/JaCoCo reports
        val jacocoReport = File(projectPath, "target/site/jacoco/index.html")
        val jacocoXml = File(projectPath, "target/site/jacoco/jacoco.xml")
        
        var overallCoverage = 0.0
        
        if (jacocoXml.exists()) {
            try {
                val xmlContent = jacocoXml.readText()
                val counterRegex = Regex("""type="(LINE|BRANCH|METHOD)".*missed="(\d+)".*covered="(\d+)"""")
                var totalMissed = 0
                var totalCovered = 0
                
                counterRegex.findAll(xmlContent).forEach { match ->
                    val missed = match.groupValues[2].toIntOrNull() ?: 0
                    val covered = match.groupValues[3].toIntOrNull() ?: 0
                    totalMissed += missed
                    totalCovered += covered
                }
                
                if (totalMissed + totalCovered > 0) {
                    overallCoverage = totalCovered.toDouble() / (totalMissed + totalCovered)
                }
            } catch (e: Exception) {
                DebugLogger.w("CoverageAnalysisTool", "Failed to parse Maven JaCoCo XML", exception = e)
            }
        }
        
        return CoverageResult(
            framework = "maven",
            overallCoverage = overallCoverage,
            fileCoverage = emptyMap(),
            uncoveredFiles = emptyList(),
            lowCoverageFiles = emptyList(),
            reportPath = if (jacocoReport.exists()) jacocoReport.absolutePath else null,
            output = output
        )
    }
    
    private fun parsePytestCoverage(output: String, projectPath: File): CoverageResult {
        var overallCoverage = 0.0
        val fileCoverage = mutableMapOf<String, Double>()
        val uncoveredFiles = mutableListOf<String>()
        
        // Parse pytest coverage output
        // Format: "TOTAL                                   123    45    63%"
        val totalRegex = Regex("""TOTAL\s+\d+\s+\d+\s+(\d+(?:\.\d+)?)%""")
        totalRegex.find(output)?.let {
            overallCoverage = it.groupValues[1].toDoubleOrNull()?.div(100.0) ?: 0.0
        }
        
        // Parse file-by-file coverage
        val fileRegex = Regex("""([^\s]+\.py)\s+(\d+)\s+(\d+)\s+(\d+(?:\.\d+)?)%""")
        fileRegex.findAll(output).forEach { match ->
            val fileName = match.groupValues[1]
            val coverage = match.groupValues[4].toDoubleOrNull()?.div(100.0) ?: 0.0
            fileCoverage[fileName] = coverage
            
            if (coverage == 0.0) {
                uncoveredFiles.add(fileName)
            }
        }
        
        val reportPath = File(projectPath, "htmlcov/index.html")
        
        return CoverageResult(
            framework = "pytest",
            overallCoverage = overallCoverage,
            fileCoverage = fileCoverage,
            uncoveredFiles = uncoveredFiles,
            lowCoverageFiles = emptyList(),
            reportPath = if (reportPath.exists()) reportPath.absolutePath else null,
            output = output
        )
    }
    
    private fun parseNpmCoverage(output: String, projectPath: File): CoverageResult {
        var overallCoverage = 0.0
        val fileCoverage = mutableMapOf<String, Double>()
        val uncoveredFiles = mutableListOf<String>()
        
        // Parse Jest coverage output
        // Format: "All files      |    95.45 |    90.00 |    85.00 |    95.00 |"
        val allFilesRegex = Regex("""All files\s+\|\s+(\d+(?:\.\d+)?)\s+\|\s+(\d+(?:\.\d+)?)\s+\|\s+(\d+(?:\.\d+)?)\s+\|\s+(\d+(?:\.\d+)?)""")
        allFilesRegex.find(output)?.let {
            // Use statements coverage (first value)
            overallCoverage = it.groupValues[1].toDoubleOrNull()?.div(100.0) ?: 0.0
        }
        
        // Parse file-by-file coverage
        val fileRegex = Regex("""([^\s\|]+)\s+\|\s+(\d+(?:\.\d+)?)\s+\|\s+(\d+(?:\.\d+)?)\s+\|\s+(\d+(?:\.\d+)?)\s+\|\s+(\d+(?:\.\d+)?)""")
        fileRegex.findAll(output).forEach { match ->
            val fileName = match.groupValues[1].trim()
            if (fileName != "All files" && !fileName.contains("---")) {
                val coverage = match.groupValues[2].toDoubleOrNull()?.div(100.0) ?: 0.0
                fileCoverage[fileName] = coverage
                
                if (coverage == 0.0) {
                    uncoveredFiles.add(fileName)
                }
            }
        }
        
        val reportPath = File(projectPath, "coverage/lcov-report/index.html")
        
        return CoverageResult(
            framework = "npm",
            overallCoverage = overallCoverage,
            fileCoverage = fileCoverage,
            uncoveredFiles = uncoveredFiles,
            lowCoverageFiles = emptyList(),
            reportPath = if (reportPath.exists()) reportPath.absolutePath else null,
            output = output
        )
    }
    
    private fun formatCoverageResults(result: CoverageResult, minCoverage: Double): String {
        return buildString {
            appendLine("# Code Coverage Analysis")
            appendLine()
            appendLine("**Framework:** ${result.framework}")
            appendLine()
            appendLine("## Overall Coverage")
            appendLine()
            val coveragePercent = (result.overallCoverage * 100)
            appendLine("- **Overall Coverage:** ${String.format("%.2f", coveragePercent)}%")
            
            if (result.lineCoverage != null) {
                appendLine("- **Line Coverage:** ${String.format("%.2f", result.lineCoverage * 100)}%")
            }
            if (result.branchCoverage != null) {
                appendLine("- **Branch Coverage:** ${String.format("%.2f", result.branchCoverage * 100)}%")
            }
            if (result.functionCoverage != null) {
                appendLine("- **Function Coverage:** ${String.format("%.2f", result.functionCoverage * 100)}%")
            }
            
            if (result.reportPath != null) {
                appendLine("- **Report:** ${result.reportPath}")
            }
            appendLine()
            
            if (result.fileCoverage.isNotEmpty()) {
                appendLine("## File Coverage")
                appendLine()
                
                // Sort by coverage (lowest first)
                val sortedFiles = result.fileCoverage.toList().sortedBy { it.value }
                
                appendLine("| File | Coverage |")
                appendLine("|------|----------|")
                sortedFiles.forEach { (file, coverage) ->
                    val coveragePercent = (coverage * 100)
                    val status = if (coverage < minCoverage) "❌" else "✅"
                    appendLine("| $file | $status ${String.format("%.2f", coveragePercent)}% |")
                }
                appendLine()
            }
            
            if (result.uncoveredFiles.isNotEmpty()) {
                appendLine("## Uncovered Files")
                appendLine()
                result.uncoveredFiles.forEach { file ->
                    appendLine("- `$file`")
                }
                appendLine()
            }
            
            if (result.lowCoverageFiles.isNotEmpty()) {
                appendLine("## Low Coverage Files (< ${(minCoverage * 100).toInt()}%)")
                appendLine()
                result.lowCoverageFiles.forEach { (file, coverage) ->
                    val coveragePercent = (coverage * 100)
                    appendLine("- `$file`: ${String.format("%.2f", coveragePercent)}%")
                }
                appendLine()
            }
            
            // Coverage assessment
            appendLine("## Assessment")
            appendLine()
            when {
                coveragePercent >= 90 -> appendLine("✅ Excellent coverage (≥90%)")
                coveragePercent >= 80 -> appendLine("✅ Good coverage (≥80%)")
                coveragePercent >= 70 -> appendLine("⚠️ Moderate coverage (≥70%)")
                coveragePercent >= 50 -> appendLine("⚠️ Low coverage (≥50%)")
                else -> appendLine("❌ Very low coverage (<50%)")
            }
            
            if (result.uncoveredFiles.isNotEmpty() || result.lowCoverageFiles.isNotEmpty()) {
                appendLine()
                appendLine("**Recommendation:** Add tests for uncovered and low-coverage files to improve overall coverage.")
            }
        }
    }
}

/**
 * Code coverage analysis tool
 */
class CoverageAnalysisTool(
    private val workspaceRoot: String
) : DeclarativeTool<CoverageAnalysisToolParams, ToolResult>() {
    
    override val name: String = "analyze_coverage"
    override val displayName: String = "Coverage Analysis"
    override val description: String = """
        Analyze code coverage and identify untested areas. Supports multiple test frameworks:
        - Gradle (JaCoCo)
        - Maven (JaCoCo)
        - pytest (coverage.py)
        - npm/Jest (Istanbul)
        
        Auto-detects test framework if not specified.
        
        Parameters:
        - projectPath: Project root path, or null for workspace root
        - framework: Test framework - "gradle", "maven", "pytest", "npm", or null for auto-detect
        - generateReport: Whether to generate HTML coverage report (default: true)
        - minCoverage: Minimum coverage threshold (0.0-1.0) for identifying low-coverage files
        
        Examples:
        - analyze_coverage() - Analyze coverage (auto-detect framework)
        - analyze_coverage(framework="gradle", minCoverage=0.8) - Analyze with 80% threshold
        - analyze_coverage(framework="pytest", generateReport=false) - Analyze without HTML report
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path, or omit for workspace root"
            ),
            "framework" to PropertySchema(
                type = "string",
                description = "Test framework: 'gradle', 'maven', 'pytest', 'npm', or omit for auto-detect",
                enum = listOf("gradle", "maven", "pytest", "npm", "jest")
            ),
            "generateReport" to PropertySchema(
                type = "boolean",
                description = "Whether to generate HTML coverage report (default: true)"
            ),
            "minCoverage" to PropertySchema(
                type = "number",
                description = "Minimum coverage threshold (0.0-1.0) for identifying low-coverage files"
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
        params: CoverageAnalysisToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<CoverageAnalysisToolParams, ToolResult> {
        return CoverageAnalysisToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): CoverageAnalysisToolParams {
        val minCoverage = params["minCoverage"]?.let {
            when (it) {
                is Number -> it.toDouble().coerceIn(0.0, 1.0)
                is String -> it.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                else -> null
            }
        }
        
        return CoverageAnalysisToolParams(
            projectPath = params["projectPath"] as? String,
            framework = params["framework"] as? String,
            generateReport = params["generateReport"] as? Boolean ?: true,
            minCoverage = minCoverage
        )
    }
}
