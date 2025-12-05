package com.qali.aterm.agent.utils

import com.qali.aterm.agent.ppe.PpeApiClient
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * Action flow planner - detects user intent and creates task plans
 * Supports: UPGRADE, STARTUP, DEBUG, CUSTOM flows
 */
object ActionFlowPlanner {
    
    /**
     * Detected action flow type
     */
    enum class ActionFlowType {
        UPGRADE,    // Upgrade/enhance existing project
        STARTUP,    // New project startup
        DEBUG,      // Debug/fix errors
        CUSTOM,     // Custom action (analysis, file generation, etc.)
        MULTIPLE    // Multiple flows detected
    }
    
    /**
     * Task in a flow plan
     */
    data class FlowTask(
        val id: String,
        val name: String,
        val description: String,
        val type: TaskType,
        val dependencies: List<String> = emptyList(), // Task IDs this depends on
        val estimatedTime: String? = null,
        val requiresConfirmation: Boolean = false
    )
    
    /**
     * Task type
     */
    enum class TaskType {
        ANALYZE,        // Analyze project/files
        GENERATE,       // Generate files
        MODIFY,         // Modify existing files
        EXECUTE,        // Execute commands
        READ,           // Read files
        WRITE,          // Write files
        MEMORY,         // Memory operation
        CUSTOM          // Custom task
    }
    
    /**
     * Action flow plan
     */
    data class ActionFlowPlan(
        val flowType: ActionFlowType,
        val tasks: List<FlowTask>,
        val summary: String,
        val estimatedTotalTime: String? = null,
        val requiresUserConfirmation: Boolean = false
    )
    
    /**
     * System information for intention detection
     */
    data class SystemInfo(
        val currentDir: String,
        val os: String,
        val osVersion: String,
        val architecture: String,
        val packageManager: String,
        val shell: String,
        val dirInfo: DirInfo
    )
    
    /**
     * Directory information
     */
    data class DirInfo(
        val fileCount: Int,
        val directoryCount: Int,
        val hasPackageJson: Boolean,
        val hasBuildGradle: Boolean,
        val hasPomXml: Boolean,
        val hasCargoToml: Boolean,
        val projectType: String? = null
    )
    
    /**
     * Plan action flow based on user message and system info
     */
    fun planActionFlow(
        userMessage: String,
        systemInfo: SystemInfo,
        apiClient: PpeApiClient?,
        chatHistory: List<com.qali.aterm.agent.core.Content> = emptyList()
    ): ActionFlowPlan {
        // First, detect the flow type
        val flowType = detectFlowType(userMessage, systemInfo)
        
        // If AI client available, use AI for detailed planning
        if (apiClient != null) {
            return planWithAI(userMessage, systemInfo, flowType, apiClient, chatHistory)
        }
        
        // Fallback to rule-based planning
        return planWithRules(userMessage, systemInfo, flowType)
    }
    
    /**
     * Detect flow type from user message
     */
    private fun detectFlowType(
        userMessage: String,
        systemInfo: SystemInfo
    ): ActionFlowType {
        val message = userMessage.lowercase()
        
        // Check for multiple flows
        val flowCount = mutableSetOf<ActionFlowType>()
        
        // Startup indicators
        if (message.contains("create") || message.contains("new project") || 
            message.contains("initialize") || message.contains("setup") ||
            message.contains("start") && (message.contains("project") || message.contains("app"))) {
            flowCount.add(ActionFlowType.STARTUP)
        }
        
        // Upgrade indicators
        if (message.contains("upgrade") || message.contains("enhance") || 
            message.contains("add feature") || message.contains("improve") ||
            message.contains("update") && !message.contains("update file")) {
            flowCount.add(ActionFlowType.UPGRADE)
        }
        
        // Debug indicators
        if (message.contains("error") || message.contains("bug") || 
            message.contains("fix") || message.contains("debug") ||
            message.contains("broken") || message.contains("not working")) {
            flowCount.add(ActionFlowType.DEBUG)
        }
        
        // Custom indicators
        if (message.contains("analyze") || message.contains("generate") ||
            message.contains("regenerate") || message.contains("blueprint") ||
            message.contains("analysis file") || message.contains(".analysis")) {
            flowCount.add(ActionFlowType.CUSTOM)
        }
        
        return when {
            flowCount.size > 1 -> ActionFlowType.MULTIPLE
            flowCount.contains(ActionFlowType.STARTUP) -> ActionFlowType.STARTUP
            flowCount.contains(ActionFlowType.UPGRADE) -> ActionFlowType.UPGRADE
            flowCount.contains(ActionFlowType.DEBUG) -> ActionFlowType.DEBUG
            flowCount.contains(ActionFlowType.CUSTOM) -> ActionFlowType.CUSTOM
            else -> ActionFlowType.CUSTOM // Default to custom
        }
    }
    
    /**
     * Plan with AI
     */
    private fun planWithAI(
        userMessage: String,
        systemInfo: SystemInfo,
        flowType: ActionFlowType,
        apiClient: PpeApiClient,
        chatHistory: List<com.qali.aterm.agent.core.Content>
    ): ActionFlowPlan {
        val prompt = buildString {
            appendLine("You are an AI assistant that plans software development tasks.")
            appendLine()
            appendLine("## System Information")
            appendLine("- Current Directory: ${systemInfo.currentDir}")
            appendLine("- OS: ${systemInfo.os} ${systemInfo.osVersion}")
            appendLine("- Architecture: ${systemInfo.architecture}")
            appendLine("- Package Manager: ${systemInfo.packageManager}")
            appendLine("- Shell: ${systemInfo.shell}")
            appendLine()
            appendLine("## Directory Information")
            appendLine("- Files: ${systemInfo.dirInfo.fileCount}")
            appendLine("- Directories: ${systemInfo.dirInfo.directoryCount}")
            appendLine("- Has package.json: ${systemInfo.dirInfo.hasPackageJson}")
            appendLine("- Has build.gradle: ${systemInfo.dirInfo.hasBuildGradle}")
            appendLine("- Project Type: ${systemInfo.dirInfo.projectType ?: "Unknown"}")
            appendLine()
            appendLine("## User Request")
            appendLine(userMessage)
            appendLine()
            appendLine("## Detected Flow Type")
            appendLine(flowType.name)
            appendLine()
            appendLine("Create a detailed action plan with tasks. Return JSON in this format:")
            appendLine("{")
            appendLine("  \"flowType\": \"${flowType.name}\",")
            appendLine("  \"summary\": \"Brief summary of the plan\",")
            appendLine("  \"estimatedTotalTime\": \"X minutes\",")
            appendLine("  \"requiresUserConfirmation\": true/false,")
            appendLine("  \"tasks\": [")
            appendLine("    {")
            appendLine("      \"id\": \"task_1\",")
            appendLine("      \"name\": \"Task name\",")
            appendLine("      \"description\": \"Detailed description\",")
            appendLine("      \"type\": \"ANALYZE|GENERATE|MODIFY|EXECUTE|READ|WRITE|MEMORY|CUSTOM\",")
            appendLine("      \"dependencies\": [\"task_0\"],")
            appendLine("      \"estimatedTime\": \"X minutes\",")
            appendLine("      \"requiresConfirmation\": true/false")
            appendLine("    }")
            appendLine("  ]")
            appendLine("}")
        }
        
        return try {
            val response = apiClient.callApi(
                messages = chatHistory + listOf(
                    com.qali.aterm.agent.core.Content(
                        role = "user",
                        parts = listOf(com.qali.aterm.agent.core.Part.TextPart(text = prompt))
                    )
                ),
                tools = emptyList(),
                streaming = false
            )
            
            val jsonText = extractJsonFromResponse(response.text)
            parsePlanFromJson(jsonText)
        } catch (e: Exception) {
            Log.w("ActionFlowPlanner", "AI planning failed: ${e.message}", e)
            planWithRules(userMessage, systemInfo, flowType)
        }
    }
    
    /**
     * Plan with rules (fallback)
     */
    private fun planWithRules(
        userMessage: String,
        systemInfo: SystemInfo,
        flowType: ActionFlowType
    ): ActionFlowPlan {
        val tasks = mutableListOf<FlowTask>()
        
        when (flowType) {
            ActionFlowType.STARTUP -> {
                tasks.add(FlowTask(
                    id = "analyze_project",
                    name = "Analyze Project Structure",
                    description = "Analyze current directory and detect project type",
                    type = TaskType.ANALYZE
                ))
                tasks.add(FlowTask(
                    id = "generate_blueprint",
                    name = "Generate Project Blueprint",
                    description = "Create project blueprint for file generation",
                    type = TaskType.GENERATE,
                    dependencies = listOf("analyze_project")
                ))
            }
            ActionFlowType.UPGRADE -> {
                tasks.add(FlowTask(
                    id = "analyze_current",
                    name = "Analyze Current State",
                    description = "Analyze current project state",
                    type = TaskType.ANALYZE
                ))
                tasks.add(FlowTask(
                    id = "plan_upgrade",
                    name = "Plan Upgrade",
                    description = "Create upgrade plan based on user request",
                    type = TaskType.CUSTOM,
                    dependencies = listOf("analyze_current")
                ))
            }
            ActionFlowType.DEBUG -> {
                tasks.add(FlowTask(
                    id = "analyze_error",
                    name = "Analyze Error",
                    description = "Extract and analyze error information",
                    type = TaskType.ANALYZE
                ))
                tasks.add(FlowTask(
                    id = "read_error_context",
                    name = "Read Error Context",
                    description = "Read files around error location",
                    type = TaskType.READ,
                    dependencies = listOf("analyze_error")
                ))
            }
            ActionFlowType.CUSTOM -> {
                tasks.add(FlowTask(
                    id = "analyze_request",
                    name = "Analyze Request",
                    description = "Analyze user request and determine actions",
                    type = TaskType.ANALYZE
                ))
            }
            ActionFlowType.MULTIPLE -> {
                // Plan for multiple flows
                tasks.add(FlowTask(
                    id = "detect_flows",
                    name = "Detect Multiple Flows",
                    description = "Detect and separate multiple action flows",
                    type = TaskType.ANALYZE
                ))
            }
        }
        
        return ActionFlowPlan(
            flowType = flowType,
            tasks = tasks,
            summary = "Action plan for ${flowType.name.lowercase()} flow",
            requiresUserConfirmation = false
        )
    }
    
    /**
     * Extract JSON from AI response
     */
    private fun extractJsonFromResponse(response: String): String {
        // Try to find JSON block
        val jsonBlockRegex = Regex("""```json\s*(.*?)\s*```""", RegexOption.DOT_MATCHES_ALL)
        val match = jsonBlockRegex.find(response)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        // Try to find JSON object directly
        val jsonObjectRegex = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL)
        val objectMatch = jsonObjectRegex.find(response)
        if (objectMatch != null) {
            return objectMatch.value
        }
        
        return response.trim()
    }
    
    /**
     * Parse plan from JSON
     */
    private fun parsePlanFromJson(jsonText: String): ActionFlowPlan {
        val json = JSONObject(jsonText)
        
        val flowTypeStr = json.optString("flowType", "CUSTOM")
        val flowType = try {
            ActionFlowType.valueOf(flowTypeStr)
        } catch (e: Exception) {
            ActionFlowType.CUSTOM
        }
        
        val summary = json.optString("summary", "Action plan")
        val estimatedTotalTime = json.optString("estimatedTotalTime", null)
        val requiresUserConfirmation = json.optBoolean("requiresUserConfirmation", false)
        
        val tasksArray = json.optJSONArray("tasks") ?: JSONArray()
        val tasks = mutableListOf<FlowTask>()
        
        for (i in 0 until tasksArray.length()) {
            val taskObj = tasksArray.getJSONObject(i)
            val taskId = taskObj.optString("id", "task_$i")
            val taskName = taskObj.optString("name", "Task")
            val taskDesc = taskObj.optString("description", "")
            val taskTypeStr = taskObj.optString("type", "CUSTOM")
            val taskType = try {
                TaskType.valueOf(taskTypeStr)
            } catch (e: Exception) {
                TaskType.CUSTOM
            }
            
            val depsArray = taskObj.optJSONArray("dependencies") ?: JSONArray()
            val dependencies = mutableListOf<String>()
            for (j in 0 until depsArray.length()) {
                dependencies.add(depsArray.getString(j))
            }
            
            val estimatedTime = taskObj.optString("estimatedTime", null)
            val requiresConfirmation = taskObj.optBoolean("requiresConfirmation", false)
            
            tasks.add(FlowTask(
                id = taskId,
                name = taskName,
                description = taskDesc,
                type = taskType,
                dependencies = dependencies,
                estimatedTime = estimatedTime,
                requiresConfirmation = requiresConfirmation
            ))
        }
        
        return ActionFlowPlan(
            flowType = flowType,
            tasks = tasks,
            summary = summary,
            estimatedTotalTime = estimatedTotalTime,
            requiresUserConfirmation = requiresUserConfirmation
        )
    }
    
    /**
     * Get system info from workspace
     */
    fun getSystemInfo(workspaceRoot: String): SystemInfo {
        val currentDir = java.io.File(workspaceRoot)
        val files = currentDir.listFiles() ?: emptyArray()
        
        val fileCount = files.count { it.isFile }
        val dirCount = files.count { it.isDirectory }
        
        val hasPackageJson = java.io.File(currentDir, "package.json").exists()
        val hasBuildGradle = java.io.File(currentDir, "build.gradle").exists() ||
                            java.io.File(currentDir, "build.gradle.kts").exists()
        val hasPomXml = java.io.File(currentDir, "pom.xml").exists()
        val hasCargoToml = java.io.File(currentDir, "Cargo.toml").exists()
        
        val projectType = when {
            hasPackageJson -> "nodejs"
            hasBuildGradle -> "android/gradle"
            hasPomXml -> "java/maven"
            hasCargoToml -> "rust"
            else -> null
        }
        
        return SystemInfo(
            currentDir = workspaceRoot,
            os = System.getProperty("os.name") ?: "Unknown",
            osVersion = System.getProperty("os.version") ?: "Unknown",
            architecture = System.getProperty("os.arch") ?: "Unknown",
            packageManager = detectPackageManager(),
            shell = System.getenv("SHELL") ?: "/bin/sh",
            dirInfo = DirInfo(
                fileCount = fileCount,
                directoryCount = dirCount,
                hasPackageJson = hasPackageJson,
                hasBuildGradle = hasBuildGradle,
                hasPomXml = hasPomXml,
                hasCargoToml = hasCargoToml,
                projectType = projectType
            )
        )
    }
    
    /**
     * Detect package manager
     */
    private fun detectPackageManager(): String {
        return when {
            java.io.File("/usr/bin/apk").exists() -> "apk"
            java.io.File("/usr/bin/apt").exists() -> "apt"
            java.io.File("/usr/bin/yum").exists() -> "yum"
            java.io.File("/usr/bin/pacman").exists() -> "pacman"
            else -> "unknown"
        }
    }
}
