package com.qali.aterm.ui.screens.agent.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.ui.screens.agent.models.AgentMessage
import com.qali.aterm.ui.screens.agent.utils.readLogcatLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DebugDialog(
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    useOllama: Boolean,
    ollamaHost: String,
    ollamaPort: Int,
    ollamaModel: String,
    ollamaUrl: String,
    workspaceRoot: String,
    messages: List<AgentMessage>,
    aiClient: Any
) {
    var logcatLogs by remember { mutableStateOf<String?>(null) }
    var isLoadingLogs by remember { mutableStateOf(false) }
    var systemInfo by remember { mutableStateOf<String?>(null) }
    var testInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    
    // Load all debug information when dialog opens
    LaunchedEffect(Unit) {
        isLoadingLogs = true
        // Load logs on IO dispatcher to avoid blocking main thread
        scope.launch(Dispatchers.IO) {
            val logs = readLogcatLogs(300)
            // Update UI state on Main dispatcher
            withContext(Dispatchers.Main) {
                logcatLogs = logs
            }
            
            // Get system information (already on IO dispatcher)
            try {
                val workspaceDir = File(workspaceRoot)
                val systemInfoBuilder = StringBuilder()
                
                // Detect project type
                val hasPackageJson = File(workspaceDir, "package.json").exists()
                val hasGoMod = File(workspaceDir, "go.mod").exists()
                val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
                val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
                val hasMaven = File(workspaceDir, "pom.xml").exists()
                val hasRequirements = File(workspaceDir, "requirements.txt").exists()
                val hasPipfile = File(workspaceDir, "Pipfile").exists()
                
                systemInfoBuilder.appendLine("Project Type: ")
                when {
                    hasPackageJson -> systemInfoBuilder.appendLine("  - Node.js (package.json found)")
                    hasGoMod -> systemInfoBuilder.appendLine("  - Go (go.mod found)")
                    hasCargoToml -> systemInfoBuilder.appendLine("  - Rust (Cargo.toml found)")
                    hasGradle -> systemInfoBuilder.appendLine("  - Java/Kotlin (Gradle)")
                    hasMaven -> systemInfoBuilder.appendLine("  - Java (Maven)")
                    hasPipfile -> systemInfoBuilder.appendLine("  - Python (Pipfile)")
                    hasRequirements -> systemInfoBuilder.appendLine("  - Python (requirements.txt)")
                    else -> systemInfoBuilder.appendLine("  - Unknown/Generic")
                }
                
                // Count files
                val fileCount = workspaceDir.walkTopDown().count { it.isFile }
                val dirCount = workspaceDir.walkTopDown().count { it.isDirectory }
                systemInfoBuilder.appendLine("Files: $fileCount files, $dirCount directories")
                
                // Get system info from SystemInfoService
                try {
                    val sysInfo = com.qali.aterm.agent.SystemInfoService.detectSystemInfo(workspaceRoot)
                    systemInfoBuilder.appendLine()
                    systemInfoBuilder.appendLine("OS: ${sysInfo.os}")
                    systemInfoBuilder.appendLine("OS Version: ${sysInfo.osVersion ?: "Unknown"}")
                    systemInfoBuilder.appendLine("Package Manager: ${sysInfo.packageManager}")
                    systemInfoBuilder.appendLine("Architecture: ${sysInfo.architecture}")
                    systemInfoBuilder.appendLine("Shell: ${sysInfo.shell}")
                    
                    if (sysInfo.packageManagerCommands.isNotEmpty()) {
                        systemInfoBuilder.appendLine("Package Manager Commands:")
                        sysInfo.packageManagerCommands.forEach { entry ->
                            systemInfoBuilder.appendLine("  - ${entry.key}: ${entry.value}")
                        }
                    }
                } catch (e: Exception) {
                    systemInfoBuilder.appendLine()
                    systemInfoBuilder.appendLine("System Info: Error - ${e.message}")
                }
                
                // Update UI state on Main dispatcher
                val finalSystemInfo = systemInfoBuilder.toString()
                withContext(Dispatchers.Main) {
                    systemInfo = finalSystemInfo
                    isLoadingLogs = false
                }
            } catch (e: Exception) {
                // Update UI state on Main dispatcher
                withContext(Dispatchers.Main) {
                    systemInfo = "Error getting system info: ${e.message}"
                    isLoadingLogs = false
                }
            }
            
            // Get testing information from messages (already on IO dispatcher)
            // Access messages safely on Main dispatcher to avoid snapshot lock issues
            val finalTestInfo = try {
                val messagesSnapshot = withContext(Dispatchers.Main) { messages }
                val testInfoBuilder = StringBuilder()
                val toolCalls = messagesSnapshot.filter { !it.isUser && it.text.contains("Tool") }
                val toolResults = messagesSnapshot.filter { !it.isUser && (it.text.contains("completed") || it.text.contains("Error")) }
                val testCommands = messagesSnapshot.filter { !it.isUser && (it.text.contains("test") || it.text.contains("npm test") || it.text.contains("pytest") || it.text.contains("cargo test") || it.text.contains("go test") || it.text.contains("mvn test") || it.text.contains("gradle test")) }
                
                testInfoBuilder.appendLine("Tool Calls: ${toolCalls.size}")
                testInfoBuilder.appendLine("Tool Results: ${toolResults.size}")
                testInfoBuilder.appendLine("Test Commands Detected: ${testCommands.size}")
                
                // Count by tool type
                val shellCalls = toolCalls.count { it.text.contains("shell") }
                val editCalls = toolCalls.count { it.text.contains("edit") }
                val readCalls = toolCalls.count { it.text.contains("read") }
                val writeCalls = toolCalls.count { it.text.contains("write") }
                val todosCalls = toolCalls.count { it.text.contains("write_todos") || it.text.contains("todo") }
                
                testInfoBuilder.appendLine()
                testInfoBuilder.appendLine("Tool Usage:")
                testInfoBuilder.appendLine("  - shell: $shellCalls")
                testInfoBuilder.appendLine("  - edit: $editCalls")
                testInfoBuilder.appendLine("  - read: $readCalls")
                testInfoBuilder.appendLine("  - write: $writeCalls")
                testInfoBuilder.appendLine("  - todos: $todosCalls")
                
                // Code debugging statistics
                val codeDebugAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Code error detected") || it.text.contains("debugging code")) }
                val codeFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("Code fix applied") || it.text.contains("Fixed __dirname")) }
                val fallbackAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Fallback") || it.text.contains("fallback")) }
                val esModuleFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("ES Module") || it.text.contains("type: \"module\"")) }
                
                testInfoBuilder.appendLine()
                testInfoBuilder.appendLine("Code Debugging:")
                testInfoBuilder.appendLine("  - Debug Attempts: $codeDebugAttempts")
                testInfoBuilder.appendLine("  - Successful Fixes: $codeFixes")
                testInfoBuilder.appendLine("  - ES Module Fixes: $esModuleFixes")
                testInfoBuilder.appendLine("  - Fallback Attempts: $fallbackAttempts")
                
                // Error analysis
                val errors = messagesSnapshot.filter { !it.isUser && (it.text.contains("Error") || it.text.contains("error") || it.text.contains("failed") || it.text.contains("Failed")) }
                val codeErrors = errors.count { it.text.contains("SyntaxError") || it.text.contains("TypeError") || it.text.contains("ReferenceError") || it.text.contains("ImportError") }
                val commandErrors = errors.count { it.text.contains("command not found") || it.text.contains("Exit code") || it.text.contains("127") }
                val dependencyErrors = errors.count { it.text.contains("module not found") || it.text.contains("package not found") || it.text.contains("Cannot find module") }
                val editErrors = errors.count { it.text.contains("String not found") || it.text.contains("edit") && it.text.contains("Error") }
                
                testInfoBuilder.appendLine()
                testInfoBuilder.appendLine("Error Analysis:")
                testInfoBuilder.appendLine("  - Total Errors: ${errors.size}")
                testInfoBuilder.appendLine("  - Code Errors: $codeErrors")
                testInfoBuilder.appendLine("  - Command Errors: $commandErrors")
                testInfoBuilder.appendLine("  - Dependency Errors: $dependencyErrors")
                testInfoBuilder.appendLine("  - Edit Tool Errors: $editErrors")
                
                // Success rate
                val successfulTools = toolResults.count { it.text.contains("✅") || (it.text.contains("completed") && !it.text.contains("Error")) }
                val totalTools = toolCalls.size
                if (totalTools > 0) {
                    val successRate = (successfulTools * 100.0 / totalTools).toInt()
                    testInfoBuilder.appendLine()
                    testInfoBuilder.appendLine("Success Rate: $successRate% ($successfulTools/$totalTools)")
                }
                
                // API call statistics (from logcat) - access logcatLogs safely
                val logsSnapshot = withContext(Dispatchers.Main) { logcatLogs }
                val apiCalls = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") } ?: 0
                val apiSuccess = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") && it.contains("Response code: 200") } ?: 0
                val apiSuccessRate = if (apiCalls > 0) (apiSuccess * 100.0 / apiCalls).toInt() else 0
                if (apiCalls > 0) {
                    testInfoBuilder.appendLine()
                    testInfoBuilder.appendLine("API Calls:")
                    testInfoBuilder.appendLine("  - Total: $apiCalls")
                    testInfoBuilder.appendLine("  - Successful: $apiSuccess ($apiSuccessRate%)")
                }
                
                // Recommendations based on errors
                val recommendations = mutableListOf<String>()
                if (commandErrors > 0 && commandErrors > codeErrors) {
                    recommendations.add("Install missing commands using package manager")
                }
                if (dependencyErrors > 0) {
                    recommendations.add("Run dependency installation (npm install, pip install, etc.)")
                }
                if (editErrors > 0) {
                    recommendations.add("Check file content matches before editing")
                }
                if (apiSuccessRate < 50 && apiCalls > 10) {
                    recommendations.add("Check API key validity and rate limits")
                }
                if (fallbackAttempts > 10) {
                    recommendations.add("Many fallback attempts - check package manager detection")
                }
                
                if (recommendations.isNotEmpty()) {
                    testInfoBuilder.appendLine()
                    testInfoBuilder.appendLine("Recommendations:")
                    recommendations.forEach { rec ->
                        testInfoBuilder.appendLine("  - $rec")
                    }
                }
                
                testInfoBuilder.toString()
            } catch (e: Exception) {
                "Error getting test info: ${e.message}"
            }
            
            // Update UI state on Main dispatcher
            withContext(Dispatchers.Main) {
                testInfo = finalTestInfo
                isLoadingLogs = false
            }
        }
    }
    
    val debugInfo = remember(useOllama, ollamaHost, ollamaPort, ollamaModel, ollamaUrl, workspaceRoot, messages, logcatLogs, systemInfo, testInfo, ApiProviderManager.selectedProvider) {
        buildString {
            appendLine("=== Agent Debug Information ===")
            appendLine()
            
            // Configuration
            appendLine("--- Configuration ---")
            val currentProvider = ApiProviderManager.selectedProvider
            val useCliAgent = AgentService.isUsingCliAgent()
            val providerName = when {
                useOllama -> "Ollama"
                useCliAgent -> "CLI-Based Agent (PPE)"
                else -> currentProvider.displayName
            }
            appendLine("Agent Type: $providerName")
            if (useCliAgent) {
                appendLine("Agent Engine: Programmable Prompt Engine (PPE)")
                appendLine("Script-Based: Yes")
                val cliClient = AgentService.getCliClient()
                if (cliClient != null) {
                    cliClient.defaultScriptPath?.let {
                        appendLine("Default Script: $it")
                    } ?: appendLine("Default Script: Using inline default")
                }
            }
            if (useOllama) {
                appendLine("Host: $ollamaHost")
                appendLine("Port: $ollamaPort")
                appendLine("Model: $ollamaModel")
                appendLine("URL: $ollamaUrl")
            } else {
                val model = ApiProviderManager.getCurrentModel()
                appendLine("Model: $model")
            }
            appendLine("Workspace Root: $workspaceRoot")
            appendLine()
            
            // System Information
            appendLine("--- System Information ---")
            appendLine(systemInfo ?: "Loading...")
            appendLine()
            
            // Testing Information
            appendLine("--- Testing & Analytics ---")
            appendLine(testInfo ?: "Loading...")
            appendLine()
            
            // Messages
            appendLine("--- Messages (${messages.size}) ---")
            messages.takeLast(20).forEachIndexed { index, msg ->
                val prefix = if (msg.isUser) "User" else "AI"
                val preview = msg.text.take(150)
                appendLine("${index + 1}. [$prefix] $preview${if (msg.text.length > 150) "..." else ""}")
            }
            appendLine()
            
            // Recent Tool Activity
            val recentToolActivity = messages.filter { !it.isUser && (it.text.contains("Tool") || it.text.contains("✅") || it.text.contains("❌")) }
            if (recentToolActivity.isNotEmpty()) {
                appendLine("--- Recent Tool Activity (last 10) ---")
                recentToolActivity.takeLast(10).forEachIndexed { index, msg ->
                    appendLine("${index + 1}. ${msg.text.take(200)}")
                }
                appendLine()
            }
            
            // Logcat
            appendLine("--- Recent Logcat (filtered) ---")
            appendLine(logcatLogs ?: "Loading...")
            appendLine()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Information") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
            ) {
                if (isLoadingLogs) {
                    CircularProgressIndicator()
                } else {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = debugInfo,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        // Launch on IO dispatcher to avoid blocking main thread
                        scope.launch(Dispatchers.IO) {
                            // Update loading state on Main dispatcher
                            withContext(Dispatchers.Main) {
                                isLoadingLogs = true
                            }
                            
                            val logs = readLogcatLogs(300)
                            
                            // Refresh system info (already on IO dispatcher)
                            val finalSystemInfo = try {
                                val workspaceDir = File(workspaceRoot)
                                val systemInfoBuilder = StringBuilder()
                                
                                val hasPackageJson = File(workspaceDir, "package.json").exists()
                                val hasGoMod = File(workspaceDir, "go.mod").exists()
                                val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
                                val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
                                val hasMaven = File(workspaceDir, "pom.xml").exists()
                                val hasRequirements = File(workspaceDir, "requirements.txt").exists()
                                val hasPipfile = File(workspaceDir, "Pipfile").exists()
                                
                                systemInfoBuilder.appendLine("Project Type: ")
                                when {
                                    hasPackageJson -> systemInfoBuilder.appendLine("  - Node.js (package.json found)")
                                    hasGoMod -> systemInfoBuilder.appendLine("  - Go (go.mod found)")
                                    hasCargoToml -> systemInfoBuilder.appendLine("  - Rust (Cargo.toml found)")
                                    hasGradle -> systemInfoBuilder.appendLine("  - Java/Kotlin (Gradle)")
                                    hasMaven -> systemInfoBuilder.appendLine("  - Java (Maven)")
                                    hasPipfile -> systemInfoBuilder.appendLine("  - Python (Pipfile)")
                                    hasRequirements -> systemInfoBuilder.appendLine("  - Python (requirements.txt)")
                                    else -> systemInfoBuilder.appendLine("  - Unknown/Generic")
                                }
                                
                                val fileCount = workspaceDir.walkTopDown().count { it.isFile }
                                val dirCount = workspaceDir.walkTopDown().count { it.isDirectory }
                                systemInfoBuilder.appendLine("Files: $fileCount files, $dirCount directories")
                                
                                // Get system info
                                try {
                                    val sysInfo = com.qali.aterm.agent.SystemInfoService.detectSystemInfo(workspaceRoot)
                                    systemInfoBuilder.appendLine()
                                    systemInfoBuilder.appendLine("OS: ${sysInfo.os}")
                                    systemInfoBuilder.appendLine("OS Version: ${sysInfo.osVersion ?: "Unknown"}")
                                    systemInfoBuilder.appendLine("Package Manager: ${sysInfo.packageManager}")
                                    systemInfoBuilder.appendLine("Architecture: ${sysInfo.architecture}")
                                } catch (e: Exception) {
                                    // Ignore
                                }
                                
                                systemInfoBuilder.toString()
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                            
                            // Update all UI state on Main dispatcher
                            withContext(Dispatchers.Main) {
                                logcatLogs = logs
                                systemInfo = finalSystemInfo
                                isLoadingLogs = false
                            }
                            
                            // Refresh test info (already on IO dispatcher)
                            // Access messages safely on Main dispatcher to avoid snapshot lock issues
                            val finalTestInfoRefresh = try {
                                val messagesSnapshot = withContext(Dispatchers.Main) { messages }
                                val logsSnapshot = withContext(Dispatchers.Main) { logcatLogs }
                                val testInfoBuilder = StringBuilder()
                                val toolCalls = messagesSnapshot.filter { !it.isUser && it.text.contains("Tool") }
                                val toolResults = messagesSnapshot.filter { !it.isUser && (it.text.contains("completed") || it.text.contains("Error")) }
                                val testCommands = messagesSnapshot.filter { !it.isUser && (it.text.contains("test") || it.text.contains("npm test") || it.text.contains("pytest") || it.text.contains("cargo test") || it.text.contains("go test") || it.text.contains("mvn test") || it.text.contains("gradle test")) }
                                
                                testInfoBuilder.appendLine("Tool Calls: ${toolCalls.size}")
                                testInfoBuilder.appendLine("Tool Results: ${toolResults.size}")
                                testInfoBuilder.appendLine("Test Commands Detected: ${testCommands.size}")
                                
                                val shellCalls = toolCalls.count { it.text.contains("shell") }
                                val editCalls = toolCalls.count { it.text.contains("edit") }
                                val readCalls = toolCalls.count { it.text.contains("read") }
                                val writeCalls = toolCalls.count { it.text.contains("write") }
                                val todosCalls = toolCalls.count { it.text.contains("write_todos") || it.text.contains("todo") }
                                
                                testInfoBuilder.appendLine()
                                testInfoBuilder.appendLine("Tool Usage:")
                                testInfoBuilder.appendLine("  - shell: $shellCalls")
                                testInfoBuilder.appendLine("  - edit: $editCalls")
                                testInfoBuilder.appendLine("  - read: $readCalls")
                                testInfoBuilder.appendLine("  - write: $writeCalls")
                                testInfoBuilder.appendLine("  - todos: $todosCalls")
                                
                                val codeDebugAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Code error detected") || it.text.contains("debugging code")) }
                                val codeFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("Code fix applied") || it.text.contains("Fixed __dirname")) }
                                val fallbackAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Fallback") || it.text.contains("fallback")) }
                                val esModuleFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("ES Module") || it.text.contains("type: \"module\"")) }
                                
                                testInfoBuilder.appendLine()
                                testInfoBuilder.appendLine("Code Debugging:")
                                testInfoBuilder.appendLine("  - Debug Attempts: $codeDebugAttempts")
                                testInfoBuilder.appendLine("  - Successful Fixes: $codeFixes")
                                testInfoBuilder.appendLine("  - ES Module Fixes: $esModuleFixes")
                                testInfoBuilder.appendLine("  - Fallback Attempts: $fallbackAttempts")
                                
                                val errors = messagesSnapshot.filter { !it.isUser && (it.text.contains("Error") || it.text.contains("error") || it.text.contains("failed") || it.text.contains("Failed")) }
                                val codeErrors = errors.count { it.text.contains("SyntaxError") || it.text.contains("TypeError") || it.text.contains("ReferenceError") || it.text.contains("ImportError") }
                                val commandErrors = errors.count { it.text.contains("command not found") || it.text.contains("Exit code") || it.text.contains("127") }
                                val dependencyErrors = errors.count { it.text.contains("module not found") || it.text.contains("package not found") || it.text.contains("Cannot find module") }
                                val editErrors = errors.count { it.text.contains("String not found") || it.text.contains("edit") && it.text.contains("Error") }
                                
                                testInfoBuilder.appendLine()
                                testInfoBuilder.appendLine("Error Analysis:")
                                testInfoBuilder.appendLine("  - Total Errors: ${errors.size}")
                                testInfoBuilder.appendLine("  - Code Errors: $codeErrors")
                                testInfoBuilder.appendLine("  - Command Errors: $commandErrors")
                                testInfoBuilder.appendLine("  - Dependency Errors: $dependencyErrors")
                                testInfoBuilder.appendLine("  - Edit Tool Errors: $editErrors")
                                
                                val successfulTools = toolResults.count { it.text.contains("✅") || (it.text.contains("completed") && !it.text.contains("Error")) }
                                val totalTools = toolCalls.size
                                if (totalTools > 0) {
                                    val successRate = (successfulTools * 100.0 / totalTools).toInt()
                                    testInfoBuilder.appendLine()
                                    testInfoBuilder.appendLine("Success Rate: $successRate% ($successfulTools/$totalTools)")
                                }
                                
                                val apiCalls = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") } ?: 0
                                val apiSuccess = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") && it.contains("Response code: 200") } ?: 0
                                if (apiCalls > 0) {
                                    val apiSuccessRate = (apiSuccess * 100.0 / apiCalls).toInt()
                                    testInfoBuilder.appendLine()
                                    testInfoBuilder.appendLine("API Calls:")
                                    testInfoBuilder.appendLine("  - Total: $apiCalls")
                                    testInfoBuilder.appendLine("  - Successful: $apiSuccess ($apiSuccessRate%)")
                                }
                                
                                // Recommendations
                                val recommendations = mutableListOf<String>()
                                if (commandErrors > 0 && commandErrors > codeErrors) {
                                    recommendations.add("Install missing commands using package manager")
                                }
                                if (dependencyErrors > 0) {
                                    recommendations.add("Run dependency installation (npm install, pip install, etc.)")
                                }
                                if (editErrors > 0) {
                                    recommendations.add("Check file content matches before editing")
                                }
                                if (apiCalls > 0) {
                                    val apiSuccessRate = (apiSuccess * 100.0 / apiCalls).toInt()
                                    if (apiSuccessRate < 50 && apiCalls > 10) {
                                        recommendations.add("Check API key validity and rate limits")
                                    }
                                }
                                if (fallbackAttempts > 10) {
                                    recommendations.add("Many fallback attempts - check package manager detection")
                                }
                                
                                if (recommendations.isNotEmpty()) {
                                    testInfoBuilder.appendLine()
                                    testInfoBuilder.appendLine("Recommendations:")
                                    recommendations.forEach { rec ->
                                        testInfoBuilder.appendLine("  - $rec")
                                    }
                                }
                                
                                testInfoBuilder.toString()
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                            
                            // Update UI state on Main dispatcher
                            withContext(Dispatchers.Main) {
                                testInfo = finalTestInfoRefresh
                                isLoadingLogs = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Refresh", fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        onCopy(debugInfo)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Copy", fontSize = 11.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Close", fontSize = 12.sp)
                }
            }
        }
    )
}
