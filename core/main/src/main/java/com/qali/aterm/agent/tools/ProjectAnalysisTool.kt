package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
import com.qali.aterm.agent.ppe.ProjectStartupDetector
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * Comprehensive project analysis tool
 * Analyzes project structure, dependencies, architecture, and identifies issues
 */
data class ProjectAnalysisToolParams(
    val projectPath: String? = null, // Project root path, or null for workspace root
    val analysisType: String? = null, // "structure", "dependencies", "architecture", "issues", "all"
    val includeSuggestions: Boolean = true // Whether to include improvement suggestions
)

data class ProjectAnalysisResult(
    val projectType: String?,
    val projectStructure: ProjectStructure,
    val dependencies: DependencyInfo,
    val architecture: ArchitectureInfo,
    val issues: List<ProjectIssue>,
    val suggestions: List<String>,
    val summary: String
)

data class ProjectStructure(
    val totalFiles: Int,
    val codeFiles: Int,
    val configFiles: Int,
    val testFiles: Int,
    val languages: Map<String, Int>, // language -> file count
    val directories: List<String>,
    val entryPoints: List<String>
)

data class DependencyInfo(
    val packageManager: String?,
    val dependencies: Map<String, String>, // name -> version
    val devDependencies: Map<String, String>,
    val totalDependencies: Int,
    val outdatedCount: Int = 0
)

data class ArchitectureInfo(
    val patterns: List<String>, // e.g., "MVC", "Layered", "Microservices"
    val entryPoints: List<String>,
    val moduleStructure: Map<String, List<String>>, // module -> files
    val circularDependencies: List<List<String>>,
    val couplingLevel: String // "low", "medium", "high"
)

data class ProjectIssue(
    val type: String, // "security", "performance", "maintainability", "best_practice"
    val severity: String, // "low", "medium", "high", "critical"
    val file: String?,
    val message: String,
    val suggestion: String?
)

class ProjectAnalysisToolInvocation(
    toolParams: ProjectAnalysisToolParams,
    private val workspaceRoot: String
) : ToolInvocation<ProjectAnalysisToolParams, ToolResult> {
    
    override val params: ProjectAnalysisToolParams = toolParams
    
    override fun getDescription(): String {
        val analysisType = params.analysisType ?: "all"
        return "Analyzing project: $analysisType"
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
                llmContent = "Project analysis cancelled",
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
            
            updateOutput?.invoke("🔍 Analyzing project structure...")
            
            // Detect project type
            val projectType = detectProjectType(projectPath)
            
            updateOutput?.invoke("📊 Analyzing dependencies...")
            val dependencies = analyzeDependencies(projectPath, projectType)
            
            updateOutput?.invoke("🏗️ Analyzing architecture...")
            val architecture = analyzeArchitecture(projectPath, projectType)
            
            updateOutput?.invoke("⚠️ Identifying issues...")
            val issues = identifyIssues(projectPath, projectType, dependencies, architecture)
            
            updateOutput?.invoke("💡 Generating suggestions...")
            val suggestions = if (params.includeSuggestions) {
                generateSuggestions(projectType, dependencies, architecture, issues)
            } else {
                emptyList()
            }
            
            updateOutput?.invoke("✅ Analysis completed")
            
            val analysisType = params.analysisType?.lowercase() ?: "all"
            val structure = analyzeProjectStructure(projectPath)
            
            val result = ProjectAnalysisResult(
                projectType = projectType,
                projectStructure = structure,
                dependencies = dependencies,
                architecture = architecture,
                issues = issues,
                suggestions = suggestions,
                summary = generateSummary(projectType, structure, dependencies, architecture, issues)
            )
            
            // Format results based on analysis type
            val formattedResult = when (analysisType) {
                "structure" -> formatStructureAnalysis(result)
                "dependencies" -> formatDependenciesAnalysis(result)
                "architecture" -> formatArchitectureAnalysis(result)
                "issues" -> formatIssuesAnalysis(result)
                "all" -> formatFullAnalysis(result)
                else -> formatFullAnalysis(result)
            }
            
            DebugLogger.i("ProjectAnalysisTool", "Project analysis completed", mapOf(
                "project_type" to (projectType ?: "unknown"),
                "total_files" to structure.totalFiles,
                "dependencies" to dependencies.totalDependencies,
                "issues" to issues.size,
                "suggestions" to suggestions.size
            ))
            
            ToolResult(
                llmContent = formattedResult,
                returnDisplay = "Analysis: ${structure.totalFiles} files, ${dependencies.totalDependencies} deps, ${issues.size} issues"
            )
        } catch (e: Exception) {
            DebugLogger.e("ProjectAnalysisTool", "Error analyzing project", exception = e)
            ToolResult(
                llmContent = "Error analyzing project: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun detectProjectType(projectPath: File): String? {
        // Check for package.json (Node.js)
        if (File(projectPath, "package.json").exists()) {
            val packageJson = File(projectPath, "package.json").readText()
            return when {
                packageJson.contains("\"react\"") || packageJson.contains("\"react-dom\"") -> "react"
                packageJson.contains("\"vue\"") -> "vue"
                packageJson.contains("\"@angular/core\"") -> "angular"
                packageJson.contains("\"express\"") -> "nodejs-express"
                else -> "nodejs"
            }
        }
        
        // Check for requirements.txt or setup.py (Python)
        if (File(projectPath, "requirements.txt").exists() ||
            File(projectPath, "setup.py").exists() ||
            File(projectPath, "pyproject.toml").exists()) {
            return "python"
        }
        
        // Check for go.mod (Go)
        if (File(projectPath, "go.mod").exists()) {
            return "go"
        }
        
        // Check for Cargo.toml (Rust)
        if (File(projectPath, "Cargo.toml").exists()) {
            return "rust"
        }
        
        // Check for build.gradle or pom.xml (Java/Kotlin)
        if (File(projectPath, "build.gradle").exists() ||
            File(projectPath, "build.gradle.kts").exists() ||
            File(projectPath, "pom.xml").exists()) {
            val hasKotlinFiles = projectPath.walkTopDown()
                .any { it.isFile && it.name.endsWith(".kt") }
            return if (hasKotlinFiles) "kotlin" else "java"
        }
        
        return null
    }
    
    private fun analyzeProjectStructure(projectPath: File): ProjectStructure {
        val codeFiles = mutableListOf<File>()
        val configFiles = mutableListOf<File>()
        val testFiles = mutableListOf<File>()
        val languages = mutableMapOf<String, Int>()
        val directories = mutableSetOf<String>()
        val entryPoints = mutableListOf<String>()
        
        projectPath.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(projectPath).path
                val name = file.name.lowercase()
                val extension = file.extension.lowercase()
                
                // Skip common ignored directories
                if (relativePath.contains("/node_modules/") ||
                    relativePath.contains("/.git/") ||
                    relativePath.contains("/build/") ||
                    relativePath.contains("/dist/") ||
                    relativePath.contains("/.gradle/")) {
                    return@forEach
                }
                
                // Track directories
                file.parentFile?.relativeTo(projectPath)?.path?.let { directories.add(it) }
                
                when {
                    // Code files
                    extension in listOf("js", "ts", "jsx", "tsx", "py", "java", "kt", "go", "rs", "cpp", "c") -> {
                        codeFiles.add(file)
                        val lang = when (extension) {
                            "js", "jsx" -> "javascript"
                            "ts", "tsx" -> "typescript"
                            "py" -> "python"
                            "java" -> "java"
                            "kt" -> "kotlin"
                            "go" -> "go"
                            "rs" -> "rust"
                            "cpp", "c" -> "cpp"
                            else -> extension
                        }
                        languages[lang] = languages.getOrDefault(lang, 0) + 1
                        
                        // Detect entry points
                        if (name == "main.${extension}" ||
                            name == "index.${extension}" ||
                            name == "app.${extension}" ||
                            name == "server.${extension}") {
                            entryPoints.add(relativePath)
                        }
                    }
                    // Config files
                    name in listOf("package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                        "requirements.txt", "setup.py", "pyproject.toml", "go.mod", "cargo.toml",
                        "dockerfile", ".gitignore", "readme.md") -> {
                        configFiles.add(file)
                    }
                    // Test files
                    name.contains("test") || name.contains("spec") || extension == "test.${extension}" -> {
                        testFiles.add(file)
                    }
                }
            }
        }
        
        return ProjectStructure(
            totalFiles = codeFiles.size + configFiles.size + testFiles.size,
            codeFiles = codeFiles.size,
            configFiles = configFiles.size,
            testFiles = testFiles.size,
            languages = languages,
            directories = directories.toList().sorted(),
            entryPoints = entryPoints
        )
    }
    
    private fun analyzeDependencies(projectPath: File, projectType: String?): DependencyInfo {
        val dependencies = mutableMapOf<String, String>()
        val devDependencies = mutableMapOf<String, String>()
        var packageManager: String? = null
        
        when (projectType) {
            "nodejs", "react", "vue", "angular", "nodejs-express" -> {
                packageManager = "npm"
                val packageJson = File(projectPath, "package.json")
                if (packageJson.exists()) {
                    try {
                        val json = JSONObject(packageJson.readText())
                        json.optJSONObject("dependencies")?.let { deps ->
                            deps.keys().forEach { key ->
                                dependencies[key] = deps.getString(key)
                            }
                        }
                        json.optJSONObject("devDependencies")?.let { devDeps ->
                            devDeps.keys().forEach { key ->
                                devDependencies[key] = devDeps.getString(key)
                            }
                        }
                    } catch (e: Exception) {
                        DebugLogger.w("ProjectAnalysisTool", "Failed to parse package.json", exception = e)
                    }
                }
            }
            "python" -> {
                packageManager = "pip"
                val requirements = File(projectPath, "requirements.txt")
                if (requirements.exists()) {
                    requirements.readLines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val parts = trimmed.split("==", ">=", "<=", ">", "<")
                            if (parts.isNotEmpty()) {
                                val name = parts[0].trim()
                                val version = if (parts.size > 1) parts[1].trim() else "unknown"
                                dependencies[name] = version
                            }
                        }
                    }
                }
            }
            "go" -> {
                packageManager = "go modules"
                val goMod = File(projectPath, "go.mod")
                if (goMod.exists()) {
                    goMod.readLines().forEach { line ->
                        if (line.startsWith("\t") && !line.contains("//")) {
                            val parts = line.trim().split(" ")
                            if (parts.size >= 2) {
                                dependencies[parts[0]] = parts[1]
                            }
                        }
                    }
                }
            }
            "java", "kotlin" -> {
                packageManager = "gradle/maven"
                // Try Gradle first
                val buildGradle = File(projectPath, "build.gradle")
                val buildGradleKts = File(projectPath, "build.gradle.kts")
                if (buildGradle.exists() || buildGradleKts.exists()) {
                    // Simple dependency extraction (can be enhanced)
                    val gradleFile = if (buildGradle.exists()) buildGradle else buildGradleKts
                    gradleFile.readLines().forEach { line ->
                        if (line.contains("implementation") || line.contains("api")) {
                            val match = Regex("""['"]([^'"]+)['"]""").find(line)
                            match?.groupValues?.get(1)?.let { dep ->
                                val parts = dep.split(":")
                                if (parts.size >= 3) {
                                    dependencies["${parts[0]}:${parts[1]}"] = parts[2]
                                }
                            }
                        }
                    }
                } else {
                    // Try Maven
                    val pomXml = File(projectPath, "pom.xml")
                    if (pomXml.exists()) {
                        // Simple XML parsing (can be enhanced)
                        val content = pomXml.readText()
                        val depRegex = Regex("""<dependency>.*?<groupId>([^<]+)</groupId>.*?<artifactId>([^<]+)</artifactId>.*?<version>([^<]+)</version>.*?</dependency>""", RegexOption.DOT_MATCHES_ALL)
                        depRegex.findAll(content).forEach { match ->
                            val groupId = match.groupValues[1].trim()
                            val artifactId = match.groupValues[2].trim()
                            val version = match.groupValues[3].trim()
                            dependencies["$groupId:$artifactId"] = version
                        }
                    }
                }
            }
        }
        
        return DependencyInfo(
            packageManager = packageManager,
            dependencies = dependencies,
            devDependencies = devDependencies,
            totalDependencies = dependencies.size + devDependencies.size
        )
    }
    
    private fun analyzeArchitecture(projectPath: File, projectType: String?): ArchitectureInfo {
        val patterns = mutableListOf<String>()
        val entryPoints = mutableListOf<String>()
        val moduleStructure = mutableMapOf<String, MutableList<String>>()
        val circularDependencies = mutableListOf<List<String>>()
        
        // Get dependency matrix
        val matrix = CodeDependencyAnalyzer.getDependencyMatrix(projectPath.absolutePath)
        
        // Detect architectural patterns
        when (projectType) {
            "react", "vue", "angular" -> {
                patterns.add("Component-Based")
                // Check for state management
                if (projectPath.walkTopDown().any { it.name.contains("store") || it.name.contains("redux") }) {
                    patterns.add("State Management (Redux/Vuex)")
                }
            }
            "nodejs-express" -> {
                patterns.add("REST API")
                // Check for MVC pattern
                val hasModels = projectPath.walkTopDown().any { it.path.contains("/models/") }
                val hasViews = projectPath.walkTopDown().any { it.path.contains("/views/") }
                val hasControllers = projectPath.walkTopDown().any { it.path.contains("/controllers/") }
                if (hasModels && hasViews && hasControllers) {
                    patterns.add("MVC")
                }
            }
            "python" -> {
                // Check for Django/Flask patterns
                if (File(projectPath, "manage.py").exists()) {
                    patterns.add("Django Framework")
                } else if (projectPath.walkTopDown().any { it.name == "app.py" || it.name == "flask_app.py" }) {
                    patterns.add("Flask Framework")
                }
            }
        }
        
        // Analyze module structure
        matrix.files.keys.forEach { filePath ->
            val parts = filePath.split("/")
            if (parts.size > 1) {
                val module = parts[0]
                moduleStructure.getOrPut(module) { mutableListOf() }.add(filePath)
            }
        }
        
        // Detect circular dependencies
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        
        fun detectCycle(node: String, path: List<String>): List<String>? {
            if (node in recursionStack) {
                val cycleStart = path.indexOf(node)
                return path.subList(cycleStart, path.size) + node
            }
            if (node in visited) return null
            
            visited.add(node)
            recursionStack.add(node)
            
            matrix.dependencies[node]?.forEach { dep ->
                detectCycle(dep, path + node)?.let { cycle ->
                    recursionStack.remove(node)
                    return cycle
                }
            }
            
            recursionStack.remove(node)
            return null
        }
        
        matrix.files.keys.forEach { file ->
            if (file !in visited) {
                detectCycle(file, emptyList())?.let { cycle ->
                    circularDependencies.add(cycle)
                }
            }
        }
        
        // Determine coupling level
        val totalFiles = matrix.files.size
        val totalDependencies = matrix.dependencies.values.sumOf { it.size }
        val avgDependencies = if (totalFiles > 0) totalDependencies.toDouble() / totalFiles else 0.0
        val couplingLevel = when {
            avgDependencies < 2 -> "low"
            avgDependencies < 5 -> "medium"
            else -> "high"
        }
        
        return ArchitectureInfo(
            patterns = patterns,
            entryPoints = entryPoints,
            moduleStructure = moduleStructure,
            circularDependencies = circularDependencies,
            couplingLevel = couplingLevel
        )
    }
    
    private fun identifyIssues(
        projectPath: File,
        projectType: String?,
        dependencies: DependencyInfo,
        architecture: ArchitectureInfo
    ): List<ProjectIssue> {
        val issues = mutableListOf<ProjectIssue>()
        
        // Get dependency matrix for calculations
        val matrix = CodeDependencyAnalyzer.getDependencyMatrix(projectPath.absolutePath)
        
        // Security issues
        if (dependencies.dependencies.containsKey("express") && 
            dependencies.dependencies["express"]?.startsWith("3.") == true) {
            issues.add(ProjectIssue(
                type = "security",
                severity = "high",
                file = "package.json",
                message = "Express 3.x has known security vulnerabilities. Consider upgrading to 4.x or later.",
                suggestion = "Update express to latest version: npm install express@latest"
            ))
        }
        
        // Architecture issues
        if (architecture.circularDependencies.isNotEmpty()) {
            issues.add(ProjectIssue(
                type = "maintainability",
                severity = "medium",
                file = null,
                message = "Circular dependencies detected: ${architecture.circularDependencies.size} cycles",
                suggestion = "Refactor to break circular dependencies and improve maintainability"
            ))
        }
        
        // Calculate average dependencies for issue message
        val totalFiles = matrix.files.size
        val totalDeps = matrix.dependencies.values.sumOf { it.size }
        val avgDeps = if (totalFiles > 0) totalDeps.toDouble() / totalFiles else 0.0
        
        if (architecture.couplingLevel == "high") {
            issues.add(ProjectIssue(
                type = "maintainability",
                severity = "medium",
                file = null,
                message = "High coupling detected (avg ${String.format("%.1f", avgDeps)} dependencies per file)",
                suggestion = "Consider refactoring to reduce coupling between modules"
            ))
        }
        
        // Best practice issues
        if (!File(projectPath, ".gitignore").exists()) {
            issues.add(ProjectIssue(
                type = "best_practice",
                severity = "low",
                file = null,
                message = "No .gitignore file found",
                suggestion = "Create a .gitignore file to exclude build artifacts and dependencies"
            ))
        }
        
        if (!File(projectPath, "README.md").exists() && !File(projectPath, "README.txt").exists()) {
            issues.add(ProjectIssue(
                type = "best_practice",
                severity = "low",
                file = null,
                message = "No README file found",
                suggestion = "Create a README.md file with project documentation"
            ))
        }
        
        // Performance issues
        val largeFiles = projectPath.walkTopDown()
            .filter { it.isFile && it.extension in listOf("js", "ts", "py", "java", "kt") }
            .filter { it.length() > 100 * 1024 } // > 100KB
            .map { it.relativeTo(projectPath).path }
        
        if (largeFiles.isNotEmpty()) {
            issues.add(ProjectIssue(
                type = "performance",
                severity = "low",
                file = largeFiles.first(),
                message = "Large code files detected (>100KB). Consider splitting into smaller modules.",
                suggestion = "Refactor large files into smaller, focused modules"
            ))
        }
        
        return issues
    }
    
    private fun generateSuggestions(
        projectType: String?,
        dependencies: DependencyInfo,
        architecture: ArchitectureInfo,
        issues: List<ProjectIssue>
    ): List<String> {
        val suggestions = mutableListOf<String>()
        
        // Project type specific suggestions
        when (projectType) {
            "react", "vue", "angular" -> {
                suggestions.add("Consider adding TypeScript for better type safety")
                suggestions.add("Add ESLint/Prettier for code quality")
                suggestions.add("Set up unit testing with Jest/Vitest")
            }
            "nodejs-express" -> {
                suggestions.add("Add input validation middleware (express-validator)")
                suggestions.add("Implement error handling middleware")
                suggestions.add("Add API documentation (Swagger/OpenAPI)")
            }
            "python" -> {
                suggestions.add("Use virtual environment (venv or poetry)")
                suggestions.add("Add type hints for better code clarity")
                suggestions.add("Set up pytest for testing")
            }
        }
        
        // Dependency suggestions
        if (dependencies.totalDependencies > 50) {
            suggestions.add("Consider reviewing dependencies - high number may indicate bloat")
        }
        
        // Architecture suggestions
        if (architecture.circularDependencies.isNotEmpty()) {
            suggestions.add("Refactor to eliminate circular dependencies")
        }
        
        if (architecture.couplingLevel == "high") {
            suggestions.add("Apply dependency inversion principle to reduce coupling")
        }
        
        // Add suggestions from issues
        issues.filter { it.severity in listOf("high", "critical") }
            .forEach { issue ->
                issue.suggestion?.let { suggestions.add(it) }
            }
        
        return suggestions.distinct()
    }
    
    private fun generateSummary(
        projectType: String?,
        structure: ProjectStructure,
        dependencies: DependencyInfo,
        architecture: ArchitectureInfo,
        issues: List<ProjectIssue>
    ): String {
        return buildString {
            appendLine("Project Type: ${projectType ?: "Unknown"}")
            appendLine("Total Files: ${structure.totalFiles} (${structure.codeFiles} code, ${structure.configFiles} config, ${structure.testFiles} test)")
            appendLine("Languages: ${structure.languages.keys.joinToString(", ")}")
            appendLine("Dependencies: ${dependencies.totalDependencies} (${dependencies.packageManager ?: "unknown"})")
            appendLine("Architecture Patterns: ${architecture.patterns.joinToString(", ")}")
            appendLine("Coupling Level: ${architecture.couplingLevel}")
            appendLine("Issues Found: ${issues.size}")
        }
    }
    
    private fun formatFullAnalysis(result: ProjectAnalysisResult): String {
        return buildString {
            appendLine("# Project Analysis Report")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine(result.summary)
            appendLine()
            
            appendLine("## Project Structure")
            appendLine()
            appendLine("- **Total Files:** ${result.projectStructure.totalFiles}")
            appendLine("- **Code Files:** ${result.projectStructure.codeFiles}")
            appendLine("- **Config Files:** ${result.projectStructure.configFiles}")
            appendLine("- **Test Files:** ${result.projectStructure.testFiles}")
            appendLine()
            appendLine("### Languages")
            result.projectStructure.languages.forEach { (lang, count) ->
                appendLine("- $lang: $count files")
            }
            appendLine()
            if (result.projectStructure.entryPoints.isNotEmpty()) {
                appendLine("### Entry Points")
                result.projectStructure.entryPoints.forEach { entry ->
                    appendLine("- `$entry`")
                }
                appendLine()
            }
            
            appendLine("## Dependencies")
            appendLine()
            appendLine("- **Package Manager:** ${result.dependencies.packageManager ?: "Unknown"}")
            appendLine("- **Total Dependencies:** ${result.dependencies.totalDependencies}")
            appendLine("- **Runtime:** ${result.dependencies.dependencies.size}")
            appendLine("- **Development:** ${result.dependencies.devDependencies.size}")
            appendLine()
            if (result.dependencies.dependencies.isNotEmpty()) {
                appendLine("### Runtime Dependencies")
                result.dependencies.dependencies.entries.take(20).forEach { (name, version) ->
                    appendLine("- `$name`: $version")
                }
                if (result.dependencies.dependencies.size > 20) {
                    appendLine("- ... and ${result.dependencies.dependencies.size - 20} more")
                }
                appendLine()
            }
            
            appendLine("## Architecture")
            appendLine()
            appendLine("- **Patterns:** ${result.architecture.patterns.joinToString(", ")}")
            appendLine("- **Coupling Level:** ${result.architecture.couplingLevel}")
            appendLine("- **Circular Dependencies:** ${result.architecture.circularDependencies.size}")
            appendLine()
            if (result.architecture.moduleStructure.isNotEmpty()) {
                appendLine("### Module Structure")
                result.architecture.moduleStructure.forEach { (module, files) ->
                    appendLine("- **$module:** ${files.size} files")
                }
                appendLine()
            }
            if (result.architecture.circularDependencies.isNotEmpty()) {
                appendLine("### Circular Dependencies")
                result.architecture.circularDependencies.take(5).forEach { cycle ->
                    appendLine("- ${cycle.joinToString(" -> ")}")
                }
                appendLine()
            }
            
            appendLine("## Issues")
            appendLine()
            if (result.issues.isEmpty()) {
                appendLine("✅ No issues found!")
            } else {
                val bySeverity = result.issues.groupBy { it.severity }
                listOf("critical", "high", "medium", "low").forEach { severity ->
                    bySeverity[severity]?.forEach { issue ->
                        appendLine("### ${severity.uppercase()}: ${issue.type}")
                        appendLine("- **File:** ${issue.file ?: "N/A"}")
                        appendLine("- **Message:** ${issue.message}")
                        if (issue.suggestion != null) {
                            appendLine("- **Suggestion:** ${issue.suggestion}")
                        }
                        appendLine()
                    }
                }
            }
            
            if (result.suggestions.isNotEmpty()) {
                appendLine("## Suggestions")
                appendLine()
                result.suggestions.forEachIndexed { index, suggestion ->
                    appendLine("${index + 1}. $suggestion")
                }
                appendLine()
            }
        }
    }
    
    private fun formatStructureAnalysis(result: ProjectAnalysisResult): String {
        return buildString {
            appendLine("# Project Structure Analysis")
            appendLine()
            appendLine(formatFullAnalysis(result).substringAfter("## Project Structure"))
        }
    }
    
    private fun formatDependenciesAnalysis(result: ProjectAnalysisResult): String {
        return buildString {
            appendLine("# Dependencies Analysis")
            appendLine()
            appendLine(formatFullAnalysis(result).substringAfter("## Dependencies"))
        }
    }
    
    private fun formatArchitectureAnalysis(result: ProjectAnalysisResult): String {
        return buildString {
            appendLine("# Architecture Analysis")
            appendLine()
            appendLine(formatFullAnalysis(result).substringAfter("## Architecture"))
        }
    }
    
    private fun formatIssuesAnalysis(result: ProjectAnalysisResult): String {
        return buildString {
            appendLine("# Issues Analysis")
            appendLine()
            appendLine(formatFullAnalysis(result).substringAfter("## Issues"))
        }
    }
}

/**
 * Project analysis tool
 */
class ProjectAnalysisTool(
    private val workspaceRoot: String
) : DeclarativeTool<ProjectAnalysisToolParams, ToolResult>() {
    
    override val name: String = "analyze_project"
    override val displayName: String = "Project Analysis"
    override val description: String = """
        Comprehensive project analysis tool. Analyzes project structure, dependencies, architecture, and identifies issues.
        
        Provides:
        - Project structure overview (files, languages, entry points)
        - Dependency analysis (package manager, versions, counts)
        - Architecture patterns and module structure
        - Issue identification (security, performance, maintainability)
        - Improvement suggestions
        
        Parameters:
        - projectPath: Project root path, or null for workspace root
        - analysisType: Type of analysis - "structure", "dependencies", "architecture", "issues", or "all" (default: "all")
        - includeSuggestions: Whether to include improvement suggestions (default: true)
        
        Examples:
        - analyze_project() - Full project analysis
        - analyze_project(analysisType="dependencies") - Analyze dependencies only
        - analyze_project(analysisType="issues", includeSuggestions=true) - Find issues with suggestions
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path, or omit for workspace root"
            ),
            "analysisType" to PropertySchema(
                type = "string",
                description = "Type of analysis: 'structure', 'dependencies', 'architecture', 'issues', or 'all'",
                enum = listOf("structure", "dependencies", "architecture", "issues", "all")
            ),
            "includeSuggestions" to PropertySchema(
                type = "boolean",
                description = "Whether to include improvement suggestions (default: true)"
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
        params: ProjectAnalysisToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ProjectAnalysisToolParams, ToolResult> {
        return ProjectAnalysisToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ProjectAnalysisToolParams {
        return ProjectAnalysisToolParams(
            projectPath = params["projectPath"] as? String,
            analysisType = params["analysisType"] as? String,
            includeSuggestions = params["includeSuggestions"] as? Boolean ?: true
        )
    }
}
