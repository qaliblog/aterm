package com.qali.aterm.agent.utils

import com.qali.aterm.ui.screens.agent.AgentMessage
import android.util.Log

/**
 * Agent memory system that parses events from chat history to extract important context
 * This helps the agent remember what was done in previous interactions
 */
object AgentMemory {
    
    data class Memory(
        val projectType: String? = null,
        val framework: String? = null,
        val os: String? = null,
        val packageManager: String? = null,
        val filesCreated: List<String> = emptyList(),
        val filesModified: List<String> = emptyList(),
        val toolsUsed: List<String> = emptyList(),
        val keyEvents: List<String> = emptyList(),
        val dependenciesInstalled: List<String> = emptyList(),
        val projectStructure: String? = null,
        val lastAction: String? = null
    )
    
    /**
     * Parse memory from chat history messages
     * Extracts important information from the first prompt and subsequent events
     */
    fun parseMemoryFromHistory(messages: List<AgentMessage>, workspaceRoot: String? = null): Memory {
        if (messages.isEmpty()) {
            return Memory()
        }
        
        val firstUserMessage = messages.firstOrNull { it.isUser }?.text ?: ""
        val allMessages = messages.joinToString("\n") { it.text }
        
        // Detect project type from first message and file diffs
        val projectType = detectProjectType(firstUserMessage, messages, workspaceRoot)
        val framework = detectFramework(firstUserMessage, allMessages)
        val os = detectOSFromMessages(allMessages, workspaceRoot)
        val packageManager = detectPackageManagerFromMessages(allMessages, os)
        
        // Extract files created/modified from file diffs
        val filesCreated = messages.mapNotNull { it.fileDiff?.takeIf { it.isNewFile }?.filePath }.distinct()
        val filesModified = messages.mapNotNull { it.fileDiff?.takeIf { !it.isNewFile }?.filePath }.distinct()
        
        // Extract tools used
        val toolsUsed = messages
            .filter { !it.isUser && it.text.contains("🔧 Calling tool:") }
            .mapNotNull { 
                val match = Regex("🔧 Calling tool: (\\w+)").find(it.text)
                match?.groupValues?.get(1)
            }
            .distinct()
        
        // Extract key events (dependencies installed, commands run, etc.)
        val keyEvents = mutableListOf<String>()
        messages.forEach { msg ->
            when {
                msg.text.contains("npm install") || msg.text.contains("pip install") || 
                msg.text.contains("apk add") || msg.text.contains("apt install") -> {
                    val match = Regex("(npm install|pip install|apk add|apt install)\\s+([\\w-]+)").find(msg.text)
                    match?.groupValues?.get(2)?.let { keyEvents.add("Installed dependency: $it") }
                }
                msg.text.contains("✅ Tool") && msg.text.contains("completed") -> {
                    val match = Regex("✅ Tool '([^']+)' completed").find(msg.text)
                    match?.groupValues?.get(1)?.let { keyEvents.add("Completed: $it") }
                }
            }
        }
        
        // Extract dependencies installed
        val dependenciesInstalled = messages
            .filter { it.text.contains("install") && (it.text.contains("npm") || it.text.contains("pip") || it.text.contains("apk") || it.text.contains("apt")) }
            .mapNotNull {
                val match = Regex("(npm install|pip install|apk add|apt install)\\s+([\\w-@.]+)").find(it.text)
                match?.groupValues?.get(2)
            }
            .distinct()
        
        // Determine project structure
        val projectStructure = when {
            filesCreated.any { it.contains("package.json") } -> "Node.js project"
            filesCreated.any { it.contains("go.mod") } -> "Go project"
            filesCreated.any { it.contains("Cargo.toml") } -> "Rust project"
            filesCreated.any { it.contains("build.gradle") } -> "Java/Kotlin project"
            filesCreated.any { it.contains("requirements.txt") || filesCreated.any { it.contains("Pipfile") } } -> "Python project"
            else -> null
        }
        
        // Get last action
        val lastAction = messages.lastOrNull { !it.isUser }?.text?.take(100)
        
        return Memory(
            projectType = projectType,
            framework = framework,
            os = os,
            packageManager = packageManager,
            filesCreated = filesCreated,
            filesModified = filesModified,
            toolsUsed = toolsUsed,
            keyEvents = keyEvents,
            dependenciesInstalled = dependenciesInstalled,
            projectStructure = projectStructure,
            lastAction = lastAction
        )
    }
    
    /**
     * Format memory as a context string for AI prompts
     */
    fun formatMemoryForPrompt(memory: Memory): String {
        if (memory.projectType == null && memory.filesCreated.isEmpty() && memory.keyEvents.isEmpty()) {
            return ""
        }
        
        return buildString {
            appendLine("## Agent Memory (Previous Session Context)")
            if (memory.projectType != null) {
                appendLine("- **Project Type:** ${memory.projectType}")
            }
            if (memory.framework != null) {
                appendLine("- **Framework:** ${memory.framework}")
            }
            if (memory.os != null) {
                appendLine("- **OS:** ${memory.os}")
            }
            if (memory.packageManager != null) {
                appendLine("- **Package Manager:** ${memory.packageManager}")
            }
            if (memory.projectStructure != null) {
                appendLine("- **Project Structure:** ${memory.projectStructure}")
            }
            if (memory.filesCreated.isNotEmpty()) {
                appendLine("- **Files Created:** ${memory.filesCreated.joinToString(", ")}")
            }
            if (memory.filesModified.isNotEmpty()) {
                appendLine("- **Files Modified:** ${memory.filesModified.joinToString(", ")}")
            }
            if (memory.dependenciesInstalled.isNotEmpty()) {
                appendLine("- **Dependencies Installed:** ${memory.dependenciesInstalled.joinToString(", ")}")
            }
            if (memory.keyEvents.isNotEmpty()) {
                appendLine("- **Key Events:**")
                memory.keyEvents.takeLast(5).forEach { appendLine("  - $it") }
            }
            if (memory.lastAction != null) {
                appendLine("- **Last Action:** ${memory.lastAction}")
            }
            appendLine()
            appendLine("**IMPORTANT:** Use this context to maintain consistency with previous work. Continue from where you left off.")
        }
    }
    
    private fun detectProjectType(firstMessage: String, messages: List<AgentMessage>, workspaceRoot: String?): String? {
        // Check first message
        when {
            firstMessage.contains("nodejs", ignoreCase = true) || 
            firstMessage.contains("node.js", ignoreCase = true) || 
            firstMessage.contains("node", ignoreCase = true) && firstMessage.contains("webapp", ignoreCase = true) -> {
                return "Node.js"
            }
            firstMessage.contains("python", ignoreCase = true) -> return "Python"
            firstMessage.contains("go", ignoreCase = true) && !firstMessage.contains("golang", ignoreCase = true) -> return "Go"
            firstMessage.contains("rust", ignoreCase = true) -> return "Rust"
            firstMessage.contains("java", ignoreCase = true) -> return "Java"
            firstMessage.contains("kotlin", ignoreCase = true) -> return "Kotlin"
        }
        
        // Check file diffs
        messages.forEach { msg ->
            msg.fileDiff?.filePath?.let { path ->
                when {
                    path.contains("package.json") -> return "Node.js"
                    path.contains("go.mod") -> return "Go"
                    path.contains("Cargo.toml") -> return "Rust"
                    path.contains("build.gradle") -> return "Java/Kotlin"
                    path.contains("requirements.txt") || path.contains("Pipfile") -> return "Python"
                }
            }
        }
        
        // Check workspace
        workspaceRoot?.let { root ->
            val rootFile = java.io.File(root)
            if (java.io.File(rootFile, "package.json").exists()) return "Node.js"
            if (java.io.File(rootFile, "go.mod").exists()) return "Go"
            if (java.io.File(rootFile, "Cargo.toml").exists()) return "Rust"
            if (java.io.File(rootFile, "build.gradle").exists() || java.io.File(rootFile, "build.gradle.kts").exists()) return "Java/Kotlin"
            if (java.io.File(rootFile, "requirements.txt").exists() || java.io.File(rootFile, "Pipfile").exists()) return "Python"
        }
        
        return null
    }
    
    private fun detectFramework(firstMessage: String, allMessages: String): String? {
        val combined = (firstMessage + " " + allMessages).lowercase()
        when {
            combined.contains("express") -> return "Express.js"
            combined.contains("react") -> return "React"
            combined.contains("vue") -> return "Vue.js"
            combined.contains("angular") -> return "Angular"
            combined.contains("next.js") || combined.contains("nextjs") -> return "Next.js"
            combined.contains("django") -> return "Django"
            combined.contains("flask") -> return "Flask"
            combined.contains("fastapi") -> return "FastAPI"
            combined.contains("spring") -> return "Spring"
            combined.contains("gin") -> return "Gin"
            combined.contains("actix") -> return "Actix"
        }
        return null
    }
    
    private fun detectOSFromMessages(allMessages: String, workspaceRoot: String?): String? {
        val combined = allMessages.lowercase()
        when {
            combined.contains("alpine") -> return "Alpine Linux"
            combined.contains("ubuntu") -> return "Ubuntu"
            combined.contains("debian") -> return "Debian"
            combined.contains("apk") -> return "Alpine Linux"
            combined.contains("apt") -> return "Debian/Ubuntu"
        }
        
        // Check workspace root path
        workspaceRoot?.let { root ->
            when {
                root.contains("/alpine", ignoreCase = true) -> return "Alpine Linux"
                root.contains("/ubuntu", ignoreCase = true) -> return "Ubuntu"
            }
        }
        
        return null
    }
    
    private fun detectPackageManagerFromMessages(allMessages: String, os: String?): String? {
        val combined = allMessages.lowercase()
        when {
            combined.contains("apk") -> return "apk"
            combined.contains("apt") -> return "apt"
            combined.contains("yum") -> return "yum"
            combined.contains("dnf") -> return "dnf"
            combined.contains("pacman") -> return "pacman"
            combined.contains("brew") -> return "brew"
        }
        
        // Infer from OS
        return when {
            os?.contains("Alpine", ignoreCase = true) == true -> "apk"
            os?.contains("Debian", ignoreCase = true) == true || os?.contains("Ubuntu", ignoreCase = true) == true -> "apt"
            else -> null
        }
    }
}
