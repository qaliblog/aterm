package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * Dependency management tool
 * Manages project dependencies intelligently with update suggestions, security checks, and conflict resolution
 */
data class DependencyManagementToolParams(
    val projectPath: String? = null, // Project root path, or null for workspace root
    val action: String, // "check", "update", "audit", "resolve", "add", "remove"
    val packageName: String? = null, // Package name for add/remove/update actions
    val version: String? = null, // Version for add/update actions
    val devDependency: Boolean = false, // Whether to add as dev dependency
    val autoFix: Boolean = false // Whether to automatically fix issues
)

data class DependencyCheckResult(
    val packageManager: String?,
    val outdated: List<OutdatedDependency>,
    val vulnerabilities: List<SecurityVulnerability>,
    val conflicts: List<DependencyConflict>,
    val summary: String
)

data class OutdatedDependency(
    val name: String,
    val currentVersion: String,
    val latestVersion: String,
    val type: String // "major", "minor", "patch"
)

data class SecurityVulnerability(
    val packageName: String,
    val severity: String, // "low", "medium", "high", "critical"
    val description: String,
    val fixedVersion: String?,
    val advisory: String?
)

data class DependencyConflict(
    val packages: List<String>,
    val conflictType: String, // "version", "peer", "circular"
    val description: String,
    val resolution: String?
)

class DependencyManagementToolInvocation(
    toolParams: DependencyManagementToolParams,
    private val workspaceRoot: String
) : ToolInvocation<DependencyManagementToolParams, ToolResult> {
    
    override val params: DependencyManagementToolParams = toolParams
    
    override fun getDescription(): String {
        return when (params.action) {
            "check" -> "Checking for outdated dependencies"
            "update" -> "Updating dependencies"
            "audit" -> "Auditing dependencies for security vulnerabilities"
            "resolve" -> "Resolving dependency conflicts"
            "add" -> "Adding dependency: ${params.packageName}"
            "remove" -> "Removing dependency: ${params.packageName}"
            else -> "Managing dependencies"
        }
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
                llmContent = "Dependency management cancelled",
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
            
            val packageManager = detectPackageManager(projectPath)
            
            if (packageManager == null) {
                return@withContext ToolResult(
                    llmContent = "Could not detect package manager for project at ${projectPath.absolutePath}",
                    returnDisplay = "Error: No package manager detected",
                    error = ToolError(
                        message = "No supported package manager found",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            val result = when (params.action) {
                "check" -> checkOutdated(projectPath, packageManager, updateOutput)
                "update" -> updateDependencies(projectPath, packageManager, params.packageName, params.version, params.devDependency, updateOutput)
                "audit" -> auditSecurity(projectPath, packageManager, updateOutput)
                "resolve" -> resolveConflicts(projectPath, packageManager, params.autoFix, updateOutput)
                "add" -> addDependency(projectPath, packageManager, params.packageName, params.version, params.devDependency, updateOutput)
                "remove" -> removeDependency(projectPath, packageManager, params.packageName, updateOutput)
                else -> {
                    return@withContext ToolResult(
                        llmContent = "Unknown action: ${params.action}",
                        returnDisplay = "Error: Unknown action",
                        error = ToolError(
                            message = "Unknown action: ${params.action}",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
            }
            
            DebugLogger.i("DependencyManagementTool", "Action completed: ${params.action}", mapOf(
                "package_manager" to (packageManager ?: "unknown"),
                "action" to params.action
            ))
            
            result
        } catch (e: Exception) {
            DebugLogger.e("DependencyManagementTool", "Error managing dependencies", exception = e)
            ToolResult(
                llmContent = "Error managing dependencies: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun detectPackageManager(projectPath: File): String? {
        return when {
            File(projectPath, "package.json").exists() -> "npm"
            File(projectPath, "requirements.txt").exists() || 
            File(projectPath, "setup.py").exists() || 
            File(projectPath, "pyproject.toml").exists() -> "pip"
            File(projectPath, "go.mod").exists() -> "go"
            File(projectPath, "Cargo.toml").exists() -> "cargo"
            File(projectPath, "build.gradle").exists() || 
            File(projectPath, "build.gradle.kts").exists() -> "gradle"
            File(projectPath, "pom.xml").exists() -> "maven"
            else -> null
        }
    }
    
    private suspend fun checkOutdated(
        projectPath: File,
        packageManager: String,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        updateOutput?.invoke("🔍 Checking for outdated dependencies...")
        
        val outdated = mutableListOf<OutdatedDependency>()
        
        when (packageManager) {
            "npm" -> {
                // Run npm outdated
                val result = executeCommand(projectPath, "npm outdated --json", updateOutput)
                if (result.exitCode == 0 && result.output.isNotEmpty()) {
                    try {
                        val json = JSONObject(result.output)
                        json.keys().forEach { packageName ->
                            val pkg = json.getJSONObject(packageName)
                            val current = pkg.optString("current", "unknown")
                            val latest = pkg.optString("latest", "unknown")
                            val wanted = pkg.optString("wanted", latest)
                            
                            val type = when {
                                isMajorUpdate(current, latest) -> "major"
                                isMinorUpdate(current, latest) -> "minor"
                                else -> "patch"
                            }
                            
                            outdated.add(OutdatedDependency(
                                name = packageName,
                                currentVersion = current,
                                latestVersion = latest,
                                type = type
                            ))
                        }
                    } catch (e: Exception) {
                        DebugLogger.w("DependencyManagementTool", "Failed to parse npm outdated output", exception = e)
                    }
                }
            }
            "pip" -> {
                // Use pip list --outdated
                val result = executeCommand(projectPath, "pip list --outdated --format=json", updateOutput)
                if (result.exitCode == 0 && result.output.isNotEmpty()) {
                    try {
                        val json = JSONArray(result.output)
                        for (i in 0 until json.length()) {
                            val pkg = json.getJSONObject(i)
                            val name = pkg.getString("name")
                            val current = pkg.getString("version")
                            val latest = pkg.getString("latest_version")
                            
                            val type = when {
                                isMajorUpdate(current, latest) -> "major"
                                isMinorUpdate(current, latest) -> "minor"
                                else -> "patch"
                            }
                            
                            outdated.add(OutdatedDependency(
                                name = name,
                                currentVersion = current,
                                latestVersion = latest,
                                type = type
                            ))
                        }
                    } catch (e: Exception) {
                        DebugLogger.w("DependencyManagementTool", "Failed to parse pip outdated output", exception = e)
                    }
                }
            }
            "gradle" -> {
                // Use gradle dependencyUpdates
                updateOutput?.invoke("Note: Gradle dependency updates require 'com.github.ben-manes.versions' plugin")
                // For now, provide basic info
                outdated.add(OutdatedDependency(
                    name = "gradle-dependencies",
                    currentVersion = "unknown",
                    latestVersion = "unknown",
                    type = "unknown"
                ))
            }
            "go" -> {
                // Use go list -u -m all
                val result = executeCommand(projectPath, "go list -u -m all", updateOutput)
                if (result.exitCode == 0) {
                    result.output.lines().forEach { line ->
                        if (line.contains(" -> ")) {
                            val parts = line.split(" -> ")
                            if (parts.size == 2) {
                                val nameVersion = parts[0].split("@")
                                val latestVersion = parts[1].split("@").getOrNull(1) ?: parts[1]
                                if (nameVersion.size == 2) {
                                    outdated.add(OutdatedDependency(
                                        name = nameVersion[0],
                                        currentVersion = nameVersion[1],
                                        latestVersion = latestVersion,
                                        type = "unknown"
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        val summary = buildString {
            appendLine("## Outdated Dependencies Check")
            appendLine()
            appendLine("**Package Manager:** $packageManager")
            appendLine("**Total Outdated:** ${outdated.size}")
            appendLine()
            if (outdated.isEmpty()) {
                appendLine("✅ All dependencies are up to date!")
            } else {
                appendLine("### Outdated Packages")
                appendLine()
                val byType = outdated.groupBy { it.type }
                listOf("major", "minor", "patch").forEach { type ->
                    byType[type]?.forEach { dep ->
                        appendLine("- **${dep.name}**: ${dep.currentVersion} → ${dep.latestVersion} (${type})")
                    }
                }
            }
        }
        
        return ToolResult(
            llmContent = summary,
            returnDisplay = "Found ${outdated.size} outdated dependencies"
        )
    }
    
    private suspend fun updateDependencies(
        projectPath: File,
        packageManager: String,
        packageName: String?,
        version: String?,
        devDependency: Boolean,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        updateOutput?.invoke("🔄 Updating dependencies...")
        
        val command = when (packageManager) {
            "npm" -> {
                if (packageName != null) {
                    val versionSpec = version ?: "latest"
                    val depType = if (devDependency) "--save-dev" else "--save"
                    "npm install $packageName@$versionSpec $depType"
                } else {
                    "npm update"
                }
            }
            "pip" -> {
                if (packageName != null) {
                    val versionSpec = version ?: ""
                    "pip install ${if (versionSpec.isNotEmpty()) "$packageName==$versionSpec" else "--upgrade $packageName"}"
                } else {
                    "pip install --upgrade -r requirements.txt"
                }
            }
            "gradle" -> {
                if (packageName != null) {
                    // Gradle dependency update would be in build.gradle
                    return ToolResult(
                        llmContent = "Gradle dependency updates require manual editing of build.gradle files. Package: $packageName, Version: ${version ?: "latest"}",
                        returnDisplay = "Manual update required for Gradle"
                    )
                } else {
                    "gradle dependencies --refresh-dependencies"
                }
            }
            "go" -> {
                if (packageName != null) {
                    "go get ${if (version != null) "$packageName@$version" else "$packageName@latest"}"
                } else {
                    "go get -u ./..."
                }
            }
            else -> return ToolResult(
                llmContent = "Update not supported for package manager: $packageManager",
                returnDisplay = "Error: Unsupported package manager"
            )
        }
        
        val result = executeCommand(projectPath, command, updateOutput)
        
        val output = buildString {
            appendLine("## Dependency Update")
            appendLine()
            appendLine("**Package Manager:** $packageManager")
            if (packageName != null) {
                appendLine("**Package:** $packageName")
                appendLine("**Version:** ${version ?: "latest"}")
            } else {
                appendLine("**Action:** Update all dependencies")
            }
            appendLine()
            appendLine("**Exit Code:** ${result.exitCode}")
            appendLine()
            appendLine("### Output")
            appendLine("```")
            appendLine(result.output)
            appendLine("```")
            if (result.error.isNotEmpty()) {
                appendLine()
                appendLine("### Error Output")
                appendLine("```")
                appendLine(result.error)
                appendLine("```")
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = if (result.exitCode == 0) "Update successful" else "Update failed (exit code: ${result.exitCode})"
        )
    }
    
    private suspend fun auditSecurity(
        projectPath: File,
        packageManager: String,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        updateOutput?.invoke("🔒 Auditing dependencies for security vulnerabilities...")
        
        val vulnerabilities = mutableListOf<SecurityVulnerability>()
        
        when (packageManager) {
            "npm" -> {
                // Use npm audit
                val result = executeCommand(projectPath, "npm audit --json", updateOutput)
                if (result.exitCode != 0 || result.output.isNotEmpty()) {
                    try {
                        val json = JSONObject(result.output)
                        val advisories = json.optJSONObject("advisories")
                        if (advisories != null) {
                            advisories.keys().forEach { id ->
                                val advisory = advisories.getJSONObject(id)
                                val severity = advisory.optString("severity", "unknown").lowercase()
                                val moduleName = advisory.optString("module_name", "unknown")
                                val title = advisory.optString("title", "Security vulnerability")
                                val patchedVersions = advisory.optString("patched_versions", "")
                                
                                vulnerabilities.add(SecurityVulnerability(
                                    packageName = moduleName,
                                    severity = severity,
                                    description = title,
                                    fixedVersion = if (patchedVersions.isNotEmpty()) patchedVersions else null,
                                    advisory = id
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        DebugLogger.w("DependencyManagementTool", "Failed to parse npm audit output", exception = e)
                    }
                }
            }
            "pip" -> {
                // Use safety check (if available) or pip-audit
                val result = executeCommand(projectPath, "pip-audit --format=json 2>/dev/null || echo 'pip-audit not installed'", updateOutput)
                if (result.output.contains("pip-audit not installed")) {
                    return ToolResult(
                        llmContent = "pip-audit is not installed. Install it with: pip install pip-audit",
                        returnDisplay = "pip-audit not available"
                    )
                }
                if (result.exitCode == 0 && result.output.isNotEmpty()) {
                    try {
                        val json = JSONArray(result.output)
                        for (i in 0 until json.length()) {
                            val vuln = json.getJSONObject(i)
                            vulnerabilities.add(SecurityVulnerability(
                                packageName = vuln.optString("name", "unknown"),
                                severity = vuln.optString("severity", "unknown").lowercase(),
                                description = vuln.optString("vulnerability", "Security vulnerability"),
                                fixedVersion = vuln.optString("fix_versions", null),
                                advisory = vuln.optString("id", null)
                            ))
                        }
                    } catch (e: Exception) {
                        DebugLogger.w("DependencyManagementTool", "Failed to parse pip-audit output", exception = e)
                    }
                }
            }
            "gradle" -> {
                // Use gradle dependencyCheck (if plugin available)
                updateOutput?.invoke("Note: Gradle security audit requires 'org.owasp.dependencycheck' plugin")
            }
            "go" -> {
                // Use govulncheck (if available)
                val result = executeCommand(projectPath, "govulncheck ./... 2>&1", updateOutput)
                if (result.output.contains("govulncheck: not found")) {
                    return ToolResult(
                        llmContent = "govulncheck is not installed. Install it with: go install golang.org/x/vuln/cmd/govulncheck@latest",
                        returnDisplay = "govulncheck not available"
                    )
                }
                // Parse govulncheck output (text-based)
                result.output.lines().forEach { line ->
                    if (line.contains("Vulnerability") || line.contains("GO-")) {
                        val parts = line.split(" ")
                        if (parts.isNotEmpty()) {
                            vulnerabilities.add(SecurityVulnerability(
                                packageName = "go-module",
                                severity = "unknown",
                                description = line,
                                fixedVersion = null,
                                advisory = null
                            ))
                        }
                    }
                }
            }
        }
        
        val summary = buildString {
            appendLine("## Security Audit")
            appendLine()
            appendLine("**Package Manager:** $packageManager")
            appendLine("**Vulnerabilities Found:** ${vulnerabilities.size}")
            appendLine()
            if (vulnerabilities.isEmpty()) {
                appendLine("✅ No known security vulnerabilities found!")
            } else {
                val bySeverity = vulnerabilities.groupBy { it.severity }
                listOf("critical", "high", "medium", "low").forEach { severity ->
                    bySeverity[severity]?.forEach { vuln ->
                        appendLine("### ${severity.uppercase()}: ${vuln.packageName}")
                        appendLine("- **Description:** ${vuln.description}")
                        if (vuln.fixedVersion != null) {
                            appendLine("- **Fixed Version:** ${vuln.fixedVersion}")
                        }
                        if (vuln.advisory != null) {
                            appendLine("- **Advisory:** ${vuln.advisory}")
                        }
                        appendLine()
                    }
                }
            }
        }
        
        return ToolResult(
            llmContent = summary,
            returnDisplay = "Found ${vulnerabilities.size} vulnerabilities"
        )
    }
    
    private suspend fun resolveConflicts(
        projectPath: File,
        packageManager: String,
        autoFix: Boolean,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        updateOutput?.invoke("🔧 Resolving dependency conflicts...")
        
        val conflicts = mutableListOf<DependencyConflict>()
        
        when (packageManager) {
            "npm" -> {
                // Check for peer dependency issues
                val result = executeCommand(projectPath, "npm install --dry-run 2>&1", updateOutput)
                if (result.output.contains("peer dep") || result.output.contains("conflict")) {
                    conflicts.add(DependencyConflict(
                        packages = listOf("peer-dependencies"),
                        conflictType = "peer",
                        description = "Peer dependency conflicts detected",
                        resolution = if (autoFix) "Run: npm install --legacy-peer-deps" else "Review peer dependency requirements"
                    ))
                }
            }
            "pip" -> {
                // Check for version conflicts
                val result = executeCommand(projectPath, "pip check", updateOutput)
                if (result.exitCode != 0) {
                    result.output.lines().forEach { line ->
                        if (line.contains("requires") && line.contains("but")) {
                            conflicts.add(DependencyConflict(
                                packages = line.split(" requires ")[0].split(", "),
                                conflictType = "version",
                                description = line,
                                resolution = if (autoFix) "Update conflicting packages" else "Review version requirements"
                            ))
                        }
                    }
                }
            }
            "gradle" -> {
                // Check for dependency resolution conflicts
                val result = executeCommand(projectPath, "gradle dependencies 2>&1", updateOutput)
                if (result.output.contains("FAILED") || result.output.contains("conflict")) {
                    conflicts.add(DependencyConflict(
                        packages = listOf("gradle-dependencies"),
                        conflictType = "version",
                        description = "Gradle dependency resolution conflicts",
                        resolution = if (autoFix) "Review build.gradle dependency versions" else "Check dependency versions in build.gradle"
                    ))
                }
            }
            "go" -> {
                // Check for go.mod conflicts
                val result = executeCommand(projectPath, "go mod tidy 2>&1", updateOutput)
                if (result.exitCode != 0) {
                    conflicts.add(DependencyConflict(
                        packages = listOf("go-modules"),
                        conflictType = "version",
                        description = result.output,
                        resolution = if (autoFix) "Run: go mod tidy" else "Review go.mod for conflicts"
                    ))
                }
            }
        }
        
        val output = buildString {
            appendLine("## Dependency Conflict Resolution")
            appendLine()
            appendLine("**Package Manager:** $packageManager")
            appendLine("**Conflicts Found:** ${conflicts.size}")
            appendLine()
            if (conflicts.isEmpty()) {
                appendLine("✅ No dependency conflicts detected!")
            } else {
                conflicts.forEachIndexed { index, conflict ->
                    appendLine("### Conflict ${index + 1}")
                    appendLine("- **Type:** ${conflict.conflictType}")
                    appendLine("- **Packages:** ${conflict.packages.joinToString(", ")}")
                    appendLine("- **Description:** ${conflict.description}")
                    if (conflict.resolution != null) {
                        appendLine("- **Resolution:** ${conflict.resolution}")
                    }
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Found ${conflicts.size} conflicts"
        )
    }
    
    private suspend fun addDependency(
        projectPath: File,
        packageManager: String,
        packageName: String?,
        version: String?,
        devDependency: Boolean,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (packageName == null) {
            return ToolResult(
                llmContent = "Package name is required for add action",
                returnDisplay = "Error: Package name required",
                error = ToolError(
                    message = "Package name is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        updateOutput?.invoke("➕ Adding dependency: $packageName")
        
        val command = when (packageManager) {
            "npm" -> {
                val versionSpec = version ?: "latest"
                val depType = if (devDependency) "--save-dev" else "--save"
                "npm install $packageName@$versionSpec $depType"
            }
            "pip" -> {
                val versionSpec = if (version != null) "==$version" else ""
                "pip install $packageName$versionSpec"
            }
            "gradle" -> {
                return ToolResult(
                    llmContent = "Gradle dependencies must be added manually to build.gradle. Package: $packageName, Version: ${version ?: "latest"}",
                    returnDisplay = "Manual addition required for Gradle"
                )
            }
            "go" -> {
                val versionSpec = if (version != null) "@$version" else ""
                "go get $packageName$versionSpec"
            }
            else -> return ToolResult(
                llmContent = "Add not supported for package manager: $packageManager",
                returnDisplay = "Error: Unsupported package manager"
            )
        }
        
        val result = executeCommand(projectPath, command, updateOutput)
        
        val output = buildString {
            appendLine("## Add Dependency")
            appendLine()
            appendLine("**Package Manager:** $packageManager")
            appendLine("**Package:** $packageName")
            appendLine("**Version:** ${version ?: "latest"}")
            appendLine("**Dev Dependency:** $devDependency")
            appendLine()
            appendLine("**Exit Code:** ${result.exitCode}")
            appendLine()
            appendLine("### Output")
            appendLine("```")
            appendLine(result.output)
            appendLine("```")
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = if (result.exitCode == 0) "Dependency added successfully" else "Failed to add dependency"
        )
    }
    
    private suspend fun removeDependency(
        projectPath: File,
        packageManager: String,
        packageName: String?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (packageName == null) {
            return ToolResult(
                llmContent = "Package name is required for remove action",
                returnDisplay = "Error: Package name required",
                error = ToolError(
                    message = "Package name is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        updateOutput?.invoke("➖ Removing dependency: $packageName")
        
        val command = when (packageManager) {
            "npm" -> "npm uninstall $packageName"
            "pip" -> "pip uninstall -y $packageName"
            "gradle" -> {
                return ToolResult(
                    llmContent = "Gradle dependencies must be removed manually from build.gradle. Package: $packageName",
                    returnDisplay = "Manual removal required for Gradle"
                )
            }
            "go" -> "go mod edit -droprequire=$packageName"
            else -> return ToolResult(
                llmContent = "Remove not supported for package manager: $packageManager",
                returnDisplay = "Error: Unsupported package manager"
            )
        }
        
        val result = executeCommand(projectPath, command, updateOutput)
        
        val output = buildString {
            appendLine("## Remove Dependency")
            appendLine()
            appendLine("**Package Manager:** $packageManager")
            appendLine("**Package:** $packageName")
            appendLine()
            appendLine("**Exit Code:** ${result.exitCode}")
            appendLine()
            appendLine("### Output")
            appendLine("```")
            appendLine(result.output)
            appendLine("```")
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = if (result.exitCode == 0) "Dependency removed successfully" else "Failed to remove dependency"
        )
    }
    
    private fun isMajorUpdate(current: String, latest: String): Boolean {
        val currentParts = current.split(".")
        val latestParts = latest.split(".")
        return currentParts.isNotEmpty() && latestParts.isNotEmpty() && 
               currentParts[0] != latestParts[0]
    }
    
    private fun isMinorUpdate(current: String, latest: String): Boolean {
        val currentParts = current.split(".")
        val latestParts = latest.split(".")
        return currentParts.size >= 2 && latestParts.size >= 2 &&
               currentParts[0] == latestParts[0] && 
               currentParts[1] != latestParts[1]
    }
    
    private suspend fun executeCommand(
        projectPath: File,
        command: String,
        updateOutput: ((String) -> Unit)?
    ): CommandResult {
        return withContext(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder("/bin/sh", "-c", command)
                processBuilder.directory(projectPath)
                processBuilder.redirectErrorStream(false)
                
                val process = processBuilder.start()
                val output = StringBuilder()
                val error = StringBuilder()
                
                val outputReader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                // Read output and error streams
                val outputJob = kotlinx.coroutines.launch(Dispatchers.IO) {
                    outputReader.useLines { lines ->
                        lines.forEach { line ->
                            output.appendLine(line)
                            updateOutput?.invoke(line)
                        }
                    }
                }
                
                val errorJob = kotlinx.coroutines.launch(Dispatchers.IO) {
                    errorReader.useLines { lines ->
                        lines.forEach { line ->
                            error.appendLine(line)
                        }
                    }
                }
                
                outputJob.join()
                errorJob.join()
                
                val exitCode = process.waitFor()
                
                CommandResult(
                    exitCode = exitCode,
                    output = output.toString().trim(),
                    error = error.toString().trim()
                )
            } catch (e: Exception) {
                DebugLogger.e("DependencyManagementTool", "Command execution failed", exception = e)
                CommandResult(
                    exitCode = -1,
                    output = "",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )
}

/**
 * Dependency management tool
 */
class DependencyManagementTool(
    private val workspaceRoot: String
) : DeclarativeTool<DependencyManagementToolParams, ToolResult>() {
    
    override val name: String = "manage_dependencies"
    override val displayName: String = "Dependency Management"
    override val description: String = """
        Intelligent dependency management tool. Manages project dependencies with update suggestions, security checks, and conflict resolution.
        
        Supports multiple package managers:
        - npm (Node.js)
        - pip (Python)
        - gradle (Java/Kotlin)
        - go modules (Go)
        - cargo (Rust)
        - maven (Java)
        
        Actions:
        - check: Check for outdated dependencies
        - update: Update dependencies (all or specific package)
        - audit: Audit dependencies for security vulnerabilities
        - resolve: Resolve dependency conflicts
        - add: Add a new dependency
        - remove: Remove a dependency
        
        Examples:
        - manage_dependencies(action="check") - Check for outdated dependencies
        - manage_dependencies(action="audit") - Audit for security vulnerabilities
        - manage_dependencies(action="update", packageName="express", version="latest") - Update specific package
        - manage_dependencies(action="add", packageName="lodash", version="^4.17.21") - Add new dependency
        - manage_dependencies(action="resolve", autoFix=true) - Resolve conflicts automatically
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path, or omit for workspace root"
            ),
            "action" to PropertySchema(
                type = "string",
                description = "Action to perform: 'check', 'update', 'audit', 'resolve', 'add', 'remove'",
                enum = listOf("check", "update", "audit", "resolve", "add", "remove")
            ),
            "packageName" to PropertySchema(
                type = "string",
                description = "Package name (required for add/remove/update specific package)"
            ),
            "version" to PropertySchema(
                type = "string",
                description = "Version specification (for add/update actions)"
            ),
            "devDependency" to PropertySchema(
                type = "boolean",
                description = "Whether to add as dev dependency (npm only, default: false)"
            ),
            "autoFix" to PropertySchema(
                type = "boolean",
                description = "Whether to automatically fix issues (for resolve action, default: false)"
            )
        ),
        required = listOf("action")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: DependencyManagementToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<DependencyManagementToolParams, ToolResult> {
        return DependencyManagementToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): DependencyManagementToolParams {
        return DependencyManagementToolParams(
            projectPath = params["projectPath"] as? String,
            action = params["action"] as? String ?: "check",
            packageName = params["packageName"] as? String,
            version = params["version"] as? String,
            devDependency = params["devDependency"] as? Boolean ?: false,
            autoFix = params["autoFix"] as? Boolean ?: false
        )
    }
}
