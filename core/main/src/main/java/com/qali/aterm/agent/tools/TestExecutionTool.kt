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

/**
 * Test execution tool that allows AI to run tests and analyze results
 * Supports multiple test frameworks (JUnit, pytest, npm test, etc.)
 */
data class TestExecutionToolParams(
    val testPath: String? = null, // Specific test file/class to run, or null for all tests
    val framework: String? = null, // "junit", "pytest", "npm", "gradle", "maven", or null for auto-detect
    val projectPath: String? = null // Project root path, or null for workspace root
)

data class TestResult(
    val framework: String,
    val totalTests: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val duration: Long, // milliseconds
    val failures: List<TestFailure>,
    val output: String
)

data class TestFailure(
    val testName: String,
    val errorMessage: String,
    val stackTrace: String? = null,
    val file: String? = null,
    val line: Int? = null
)

class TestExecutionToolInvocation(
    toolParams: TestExecutionToolParams,
    private val workspaceRoot: String
) : ToolInvocation<TestExecutionToolParams, ToolResult> {
    
    override val params: TestExecutionToolParams = toolParams
    
    override fun getDescription(): String {
        val testPath = params.testPath ?: "all tests"
        val framework = params.framework ?: "auto-detect"
        return "Running tests: $testPath (framework: $framework)"
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
                llmContent = "Test execution cancelled",
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
            
            updateOutput?.invoke("▶️ Running tests with $framework...")
            
            // Execute tests
            val testResult = when (framework) {
                "junit", "gradle" -> runGradleTests(projectPath, params.testPath, updateOutput, signal)
                "maven" -> runMavenTests(projectPath, params.testPath, updateOutput, signal)
                "pytest" -> runPytestTests(projectPath, params.testPath, updateOutput, signal)
                "npm", "jest" -> runNpmTests(projectPath, params.testPath, updateOutput, signal)
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
            
            updateOutput?.invoke("✅ Tests completed")
            
            // Format results
            val formattedResult = formatTestResults(testResult)
            
            DebugLogger.i("TestExecutionTool", "Test execution completed", mapOf(
                "framework" to framework,
                "total" to testResult.totalTests,
                "passed" to testResult.passed,
                "failed" to testResult.failed,
                "duration_ms" to testResult.duration
            ))
            
            ToolResult(
                llmContent = formattedResult,
                returnDisplay = "Tests: ${testResult.passed}/${testResult.totalTests} passed"
            )
        } catch (e: Exception) {
            DebugLogger.e("TestExecutionTool", "Error executing tests", exception = e)
            ToolResult(
                llmContent = "Error executing tests: ${e.message}",
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
                packageJson.contains("\"test\"")) {
                return "npm"
            }
        }
        
        return null
    }
    
    private suspend fun runGradleTests(
        projectPath: File,
        testPath: String?,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): TestResult {
        val command = if (testPath != null) {
            "./gradlew test --tests $testPath"
        } else {
            "./gradlew test"
        }
        
        return executeTestCommand(
            command = command,
            workingDir = projectPath,
            framework = "gradle",
            updateOutput = updateOutput,
            signal = signal
        )
    }
    
    private suspend fun runMavenTests(
        projectPath: File,
        testPath: String?,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): TestResult {
        val command = if (testPath != null) {
            "mvn test -Dtest=$testPath"
        } else {
            "mvn test"
        }
        
        return executeTestCommand(
            command = command,
            workingDir = projectPath,
            framework = "maven",
            updateOutput = updateOutput,
            signal = signal
        )
    }
    
    private suspend fun runPytestTests(
        projectPath: File,
        testPath: String?,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): TestResult {
        val command = if (testPath != null) {
            "pytest $testPath -v"
        } else {
            "pytest -v"
        }
        
        return executeTestCommand(
            command = command,
            workingDir = projectPath,
            framework = "pytest",
            updateOutput = updateOutput,
            signal = signal
        )
    }
    
    private suspend fun runNpmTests(
        projectPath: File,
        testPath: String?,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): TestResult {
        val command = if (testPath != null) {
            "npm test -- $testPath"
        } else {
            "npm test"
        }
        
        return executeTestCommand(
            command = command,
            workingDir = projectPath,
            framework = "npm",
            updateOutput = updateOutput,
            signal = signal
        )
    }
    
    private suspend fun executeTestCommand(
        command: String,
        workingDir: File,
        framework: String,
        updateOutput: ((String) -> Unit)?,
        signal: CancellationSignal?
    ): TestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
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
                throw InterruptedException("Test execution cancelled")
            }
            output.appendLine(line)
            updateOutput?.invoke(line ?: "")
        }
        
        val exitCode = process.waitFor()
        val duration = System.currentTimeMillis() - startTime
        
        val outputText = output.toString()
        
        // Parse test results based on framework
        val parsedResult = when (framework) {
            "gradle" -> parseGradleResults(outputText, duration)
            "maven" -> parseMavenResults(outputText, duration)
            "pytest" -> parsePytestResults(outputText, duration)
            "npm" -> parseNpmResults(outputText, duration)
            else -> TestResult(
                framework = framework,
                totalTests = 0,
                passed = 0,
                failed = if (exitCode != 0) 1 else 0,
                skipped = 0,
                duration = duration,
                failures = emptyList(),
                output = outputText
            )
        }
        
        parsedResult
    }
    
    private fun parseGradleResults(output: String, duration: Long): TestResult {
        // Parse Gradle test output
        val totalTests = extractNumber(output, "tests? completed") ?: 0
        val passed = extractNumber(output, "tests? passed") ?: 
                     (totalTests - extractNumber(output, "tests? failed")!!)
        val failed = extractNumber(output, "tests? failed") ?: 0
        val skipped = extractNumber(output, "tests? skipped") ?: 0
        
        val failures = extractGradleFailures(output)
        
        return TestResult(
            framework = "gradle",
            totalTests = totalTests,
            passed = passed,
            failed = failed,
            skipped = skipped,
            duration = duration,
            failures = failures,
            output = output
        )
    }
    
    private fun parseMavenResults(output: String, duration: Long): TestResult {
        // Parse Maven test output
        val totalTests = extractNumber(output, "Tests run:") ?: 0
        val failed = extractNumber(output, "Failures:") ?: 0
        val skipped = extractNumber(output, "Skipped:") ?: 0
        val passed = totalTests - failed - skipped
        
        val failures = extractMavenFailures(output)
        
        return TestResult(
            framework = "maven",
            totalTests = totalTests,
            passed = passed,
            failed = failed,
            skipped = skipped,
            duration = duration,
            failures = failures,
            output = output
        )
    }
    
    private fun parsePytestResults(output: String, duration: Long): TestResult {
        // Parse pytest output
        val totalTests = extractNumber(output, "passed|failed|skipped") ?: 0
        val passed = extractNumber(output, "passed") ?: 0
        val failed = extractNumber(output, "failed") ?: 0
        val skipped = extractNumber(output, "skipped") ?: 0
        
        val failures = extractPytestFailures(output)
        
        return TestResult(
            framework = "pytest",
            totalTests = totalTests,
            passed = passed,
            failed = failed,
            skipped = skipped,
            duration = duration,
            failures = failures,
            output = output
        )
    }
    
    private fun parseNpmResults(output: String, duration: Long): TestResult {
        // Parse npm/Jest output
        val totalTests = extractNumber(output, "Tests:") ?: extractNumber(output, "tests") ?: 0
        val passed = extractNumber(output, "passed") ?: 0
        val failed = extractNumber(output, "failed") ?: 0
        
        val failures = extractJestFailures(output)
        
        return TestResult(
            framework = "npm",
            totalTests = totalTests,
            passed = passed,
            failed = failed,
            skipped = 0,
            duration = duration,
            failures = failures,
            output = output
        )
    }
    
    private fun extractNumber(text: String, pattern: String): Int? {
        val regex = Regex("(\\d+)\\s*$pattern", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractGradleFailures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        val failureRegex = Regex("""FAILED\s+:\s+([^\n]+)""")
        
        failureRegex.findAll(output).forEach { match ->
            failures.add(
                TestFailure(
                    testName = match.groupValues[1].trim(),
                    errorMessage = "Test failed",
                    stackTrace = null
                )
            )
        }
        
        return failures
    }
    
    private fun extractMavenFailures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        val failureRegex = Regex("""Tests run:.*Failures:\s*(\d+)""")
        
        // Simple extraction - can be enhanced
        return failures
    }
    
    private fun extractPytestFailures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        val failureRegex = Regex("""FAILED\s+([^\s]+)\s*::\s*([^\n]+)""")
        
        failureRegex.findAll(output).forEach { match ->
            failures.add(
                TestFailure(
                    testName = match.groupValues[1],
                    errorMessage = match.groupValues[2],
                    stackTrace = null
                )
            )
        }
        
        return failures
    }
    
    private fun extractJestFailures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        // Jest failure extraction - can be enhanced
        return failures
    }
    
    private fun formatTestResults(result: TestResult): String {
        return buildString {
            appendLine("# Test Execution Results")
            appendLine()
            appendLine("**Framework:** ${result.framework}")
            appendLine("**Duration:** ${result.duration / 1000.0}s")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("- **Total Tests:** ${result.totalTests}")
            appendLine("- **Passed:** ${result.passed} ✅")
            appendLine("- **Failed:** ${result.failed} ❌")
            if (result.skipped > 0) {
                appendLine("- **Skipped:** ${result.skipped} ⏭️")
            }
            appendLine()
            
            if (result.failures.isNotEmpty()) {
                appendLine("## Failures")
                appendLine()
                result.failures.forEachIndexed { index, failure ->
                    appendLine("### Failure ${index + 1}: ${failure.testName}")
                    appendLine()
                    appendLine("**Error:** ${failure.errorMessage}")
                    if (failure.stackTrace != null) {
                        appendLine()
                        appendLine("```")
                        appendLine(failure.stackTrace)
                        appendLine("```")
                    }
                    if (failure.file != null) {
                        appendLine()
                        appendLine("**Location:** ${failure.file}${failure.line?.let { ":$it" } ?: ""}")
                    }
                    appendLine()
                }
            }
            
            if (result.failed == 0) {
                appendLine("✅ All tests passed!")
            } else {
                appendLine("❌ ${result.failed} test(s) failed. Review the failures above.")
            }
            
            appendLine()
            appendLine("## Full Output")
            appendLine()
            appendLine("```")
            appendLine(result.output.take(5000)) // Limit output size
            appendLine("```")
        }
    }
}

/**
 * Test execution tool for running and analyzing tests
 */
class TestExecutionTool(
    private val workspaceRoot: String
) : DeclarativeTool<TestExecutionToolParams, ToolResult>() {
    
    override val name: String = "run_tests"
    override val displayName: String = "Test Execution"
    override val description: String = """
        Run tests and analyze results. Supports multiple test frameworks:
        - Gradle (JUnit, TestNG)
        - Maven (JUnit, TestNG)
        - pytest (Python)
        - npm/Jest (JavaScript/TypeScript)
        
        Auto-detects test framework if not specified.
        
        Parameters:
        - testPath: Specific test file/class to run (e.g., "com.example.MyTest", "test_file.py"), or null for all tests
        - framework: Test framework to use - "gradle", "maven", "pytest", "npm", or null for auto-detect
        - projectPath: Project root path, or null for workspace root
        
        Examples:
        - run_tests() - Run all tests (auto-detect framework)
        - run_tests(framework="gradle", testPath="com.example.MyTest") - Run specific test class
        - run_tests(framework="pytest", testPath="test_file.py") - Run specific pytest file
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "testPath" to PropertySchema(
                type = "string",
                description = "Specific test file/class to run, or omit for all tests"
            ),
            "framework" to PropertySchema(
                type = "string",
                description = "Test framework: 'gradle', 'maven', 'pytest', 'npm', or omit for auto-detect",
                enum = listOf("gradle", "maven", "pytest", "npm", "junit", "jest")
            ),
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path, or omit for workspace root"
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
        params: TestExecutionToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<TestExecutionToolParams, ToolResult> {
        return TestExecutionToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): TestExecutionToolParams {
        return TestExecutionToolParams(
            testPath = params["testPath"] as? String,
            framework = params["framework"] as? String,
            projectPath = params["projectPath"] as? String
        )
    }
}
