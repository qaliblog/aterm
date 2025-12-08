package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.debug.DebugLogger
import android.util.Log
import java.io.File

/**
 * Enhanced project startup detection
 * Detects new project startup scenarios with improved heuristics
 * Supports multiple project types and template-based initialization
 */
object ProjectStartupDetector {
    
    enum class ProjectType {
        NODEJS,
        PYTHON,
        REACT,
        VUE,
        ANGULAR,
        GO,
        RUST,
        JAVA,
        KOTLIN,
        CPP,
        UNKNOWN
    }
    
    data class ProjectDetection(
        val isNewProject: Boolean,
        val projectType: ProjectType? = null,
        val confidence: Double = 0.0, // 0.0 to 1.0
        val suggestedTemplate: String? = null,
        val detectedLanguage: String? = null
    )
    
    /**
     * Detect if this is a new project startup
     * Enhanced detection with better heuristics and project type identification
     */
    fun detectNewProject(userMessage: String, workspaceRoot: String): ProjectDetection {
        val message = userMessage.lowercase()
        
        // EXCLUDE error/debug indicators first - these should NOT be treated as new projects
        val errorDebugIndicators = listOf(
            "error", "bug", "fix", "debug", "broken", "not working",
            "doesn't work", "doesn't start", "won't start", "wont start",
            "not starting", "failed", "failure", "exception", "crash",
            "issue", "problem", "solve", "troubleshoot"
        )
        val hasErrorDebugIndicator = errorDebugIndicators.any { message.contains(it) }
        
        // If message contains error/debug indicators, it's NOT a new project
        if (hasErrorDebugIndicator) {
            Log.d("ProjectStartupDetector", "Excluding from new project detection - contains error/debug indicators")
            return ProjectDetection(
                isNewProject = false,
                projectType = null,
                confidence = 0.0,
                suggestedTemplate = null,
                detectedLanguage = null
            )
        }
        
        // Check for new project keywords (enhanced list)
        // Exclude "start" alone - require context like "new project" or "create"
        val newProjectKeywords = listOf(
            "make me", "create", "new project", "build", "initialize",
            "set up", "setup", "generate", "scaffold", "scaffolding", "new app",
            "create a", "build a", "make a", "develop", "develop a",
            "write me", "write a", "make a", "build me", "create me"
        )
        // Only match "start" if it's clearly about starting a NEW project
        val hasStartKeyword = message.contains("start") && 
            (message.contains("new") || message.contains("create") || 
             message.contains("project") || message.contains("app"))
        val hasNewProjectKeyword = newProjectKeywords.any { message.contains(it) } || hasStartKeyword
        
        // Auto-create .atermignore for new projects
        com.qali.aterm.agent.utils.AtermIgnoreManager.createDefaultAtermIgnore(workspaceRoot)
        
        // Check workspace state - count code files (respecting .atermignore)
        val workspaceDir = File(workspaceRoot)
        val ignoreManager = com.qali.aterm.agent.utils.AtermIgnoreManager
        val codeFileCount = if (workspaceDir.exists() && workspaceDir.isDirectory) {
            workspaceDir.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    // Skip ignored files
                    !ignoreManager.shouldIgnoreFile(file, workspaceRoot)
                }
                .filter { file ->
                    val name = file.name.lowercase()
                    name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".py") ||
                    name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".go") ||
                    name.endsWith(".rs") || name.endsWith(".cpp") || name.endsWith(".c") ||
                    name.endsWith(".jsx") || name.endsWith(".tsx") || name.endsWith(".vue") ||
                    name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".htm")
                }
                .count()
        } else {
            0
        }
        
        // Detect project type from message
        val projectTypeDetection = detectProjectType(message, workspaceRoot)
        
        // Calculate confidence
        var confidence = 0.0
        if (hasNewProjectKeyword) confidence += 0.4
        if (codeFileCount <= 2) confidence += 0.4
        if (projectTypeDetection.projectType != null) confidence += 0.2
        
        // Consider it a new project if:
        // 1. Has new project keywords AND
        // 2. Has very few code files (0-2) OR workspace is mostly empty
        val isNewProject = hasNewProjectKeyword && codeFileCount <= 2 && confidence >= 0.6
        
        Log.d("ProjectStartupDetector", "Detection - keywords: $hasNewProjectKeyword, codeFiles: $codeFileCount, type: ${projectTypeDetection.projectType}, confidence: $confidence, isNew: $isNewProject")
        
        return ProjectDetection(
            isNewProject = isNewProject,
            projectType = projectTypeDetection.projectType,
            confidence = confidence,
            suggestedTemplate = projectTypeDetection.suggestedTemplate,
            detectedLanguage = projectTypeDetection.detectedLanguage
        )
    }
    
    /**
     * Detect project type from user message and workspace
     */
    fun detectProjectType(message: String, workspaceRoot: String): ProjectTypeDetection {
        val lowerMessage = message.lowercase()
        
        // React detection
        if (lowerMessage.contains("react") || 
            lowerMessage.contains("react app") ||
            lowerMessage.contains("reactjs") ||
            lowerMessage.contains("create-react-app")) {
            return ProjectTypeDetection(
                projectType = ProjectType.REACT,
                suggestedTemplate = "react",
                detectedLanguage = "javascript"
            )
        }
        
        // Vue detection
        if (lowerMessage.contains("vue") || 
            lowerMessage.contains("vuejs") ||
            lowerMessage.contains("nuxt")) {
            return ProjectTypeDetection(
                projectType = ProjectType.VUE,
                suggestedTemplate = "vue",
                detectedLanguage = "javascript"
            )
        }
        
        // Angular detection
        if (lowerMessage.contains("angular")) {
            return ProjectTypeDetection(
                projectType = ProjectType.ANGULAR,
                suggestedTemplate = "angular",
                detectedLanguage = "typescript"
            )
        }
        
        // HTML/CSS/JS web project detection (check before Node.js)
        if (lowerMessage.contains("html") || 
            lowerMessage.contains("css") ||
            lowerMessage.contains("javascript") ||
            lowerMessage.contains("web page") ||
            lowerMessage.contains("webpage") ||
            lowerMessage.contains("website") ||
            (lowerMessage.contains("page") && (lowerMessage.contains("html") || lowerMessage.contains("css") || lowerMessage.contains("js")))) {
            return ProjectTypeDetection(
                projectType = ProjectType.NODEJS, // Use NODEJS type for HTML/CSS/JS projects
                suggestedTemplate = "html-css-js",
                detectedLanguage = "html"
            )
        }
        
        // Node.js detection
        if (lowerMessage.contains("node") || 
            lowerMessage.contains("nodejs") ||
            lowerMessage.contains("express") ||
            lowerMessage.contains("server") ||
            lowerMessage.contains("api") ||
            lowerMessage.contains("backend")) {
            return ProjectTypeDetection(
                projectType = ProjectType.NODEJS,
                suggestedTemplate = "nodejs",
                detectedLanguage = "javascript"
            )
        }
        
        // Python detection
        if (lowerMessage.contains("python") || 
            lowerMessage.contains("django") ||
            lowerMessage.contains("flask") ||
            lowerMessage.contains("fastapi") ||
            lowerMessage.contains(".py")) {
            return ProjectTypeDetection(
                projectType = ProjectType.PYTHON,
                suggestedTemplate = "python",
                detectedLanguage = "python"
            )
        }
        
        // Go detection
        if (lowerMessage.contains("go ") || 
            lowerMessage.contains("golang") ||
            lowerMessage.contains(".go")) {
            return ProjectTypeDetection(
                projectType = ProjectType.GO,
                suggestedTemplate = "go",
                detectedLanguage = "go"
            )
        }
        
        // Rust detection
        if (lowerMessage.contains("rust") || 
            lowerMessage.contains(".rs")) {
            return ProjectTypeDetection(
                projectType = ProjectType.RUST,
                suggestedTemplate = "rust",
                detectedLanguage = "rust"
            )
        }
        
        // Java detection
        if (lowerMessage.contains("java") && !lowerMessage.contains("javascript")) {
            return ProjectTypeDetection(
                projectType = ProjectType.JAVA,
                suggestedTemplate = "java",
                detectedLanguage = "java"
            )
        }
        
        // Kotlin detection
        if (lowerMessage.contains("kotlin")) {
            return ProjectTypeDetection(
                projectType = ProjectType.KOTLIN,
                suggestedTemplate = "kotlin",
                detectedLanguage = "kotlin"
            )
        }
        
        // C++ detection
        if (lowerMessage.contains("c++") || 
            lowerMessage.contains("cpp") ||
            lowerMessage.contains(".cpp")) {
            return ProjectTypeDetection(
                projectType = ProjectType.CPP,
                suggestedTemplate = "cpp",
                detectedLanguage = "cpp"
            )
        }
        
        // Check workspace for existing project files
        val workspaceDir = File(workspaceRoot)
        if (workspaceDir.exists() && workspaceDir.isDirectory) {
            // Auto-detect Node.js projects (presence of package.json)
            val packageJsonFile = File(workspaceDir, "package.json")
            if (packageJsonFile.exists() && !packageJsonFile.isDirectory) {
                val packageJson = File(workspaceDir, "package.json").readText()
                when {
                    packageJson.contains("\"react\"") || packageJson.contains("\"react-dom\"") -> {
                        return ProjectTypeDetection(
                            projectType = ProjectType.REACT,
                            suggestedTemplate = "react",
                            detectedLanguage = "javascript"
                        )
                    }
                    packageJson.contains("\"vue\"") -> {
                        return ProjectTypeDetection(
                            projectType = ProjectType.VUE,
                            suggestedTemplate = "vue",
                            detectedLanguage = "javascript"
                        )
                    }
                    packageJson.contains("\"@angular/core\"") -> {
                        return ProjectTypeDetection(
                            projectType = ProjectType.ANGULAR,
                            suggestedTemplate = "angular",
                            detectedLanguage = "typescript"
                        )
                    }
                    else -> {
                        return ProjectTypeDetection(
                            projectType = ProjectType.NODEJS,
                            suggestedTemplate = "nodejs",
                            detectedLanguage = "javascript"
                        )
                    }
                }
            }
            
            // Check for requirements.txt or setup.py (Python)
            if (File(workspaceDir, "requirements.txt").exists() ||
                File(workspaceDir, "setup.py").exists() ||
                File(workspaceDir, "pyproject.toml").exists()) {
                return ProjectTypeDetection(
                    projectType = ProjectType.PYTHON,
                    suggestedTemplate = "python",
                    detectedLanguage = "python"
                )
            }
            
            // Check for go.mod (Go)
            if (File(workspaceDir, "go.mod").exists()) {
                return ProjectTypeDetection(
                    projectType = ProjectType.GO,
                    suggestedTemplate = "go",
                    detectedLanguage = "go"
                )
            }
            
            // Check for Cargo.toml (Rust)
            if (File(workspaceDir, "Cargo.toml").exists()) {
                return ProjectTypeDetection(
                    projectType = ProjectType.RUST,
                    suggestedTemplate = "rust",
                    detectedLanguage = "rust"
                )
            }
            
            // Check for build.gradle or pom.xml (Java/Kotlin)
            if (File(workspaceDir, "build.gradle").exists() ||
                File(workspaceDir, "build.gradle.kts").exists() ||
                File(workspaceDir, "pom.xml").exists()) {
                // Check for Kotlin files
                val hasKotlinFiles = workspaceDir.walkTopDown()
                    .any { it.isFile && it.name.endsWith(".kt") }
                
                return ProjectTypeDetection(
                    projectType = if (hasKotlinFiles) ProjectType.KOTLIN else ProjectType.JAVA,
                    suggestedTemplate = if (hasKotlinFiles) "kotlin" else "java",
                    detectedLanguage = if (hasKotlinFiles) "kotlin" else "java"
                )
            }
        }
        
        return ProjectTypeDetection(
            projectType = null,
            suggestedTemplate = null,
            detectedLanguage = null
        )
    }
    
    /**
     * Get project template suggestions based on project type
     */
    fun getTemplateSuggestions(projectType: ProjectType?): List<String> {
        return when (projectType) {
            ProjectType.REACT -> listOf(
                "react-basic", "react-typescript", "react-vite", "react-nextjs"
            )
            ProjectType.VUE -> listOf(
                "vue-basic", "vue-typescript", "vue-vite", "vue-nuxt"
            )
            ProjectType.NODEJS -> listOf(
                "nodejs-express", "nodejs-rest-api", "nodejs-cli", "nodejs-websocket"
            )
            ProjectType.PYTHON -> listOf(
                "python-flask", "python-django", "python-fastapi", "python-cli"
            )
            ProjectType.GO -> listOf(
                "go-cli", "go-rest-api", "go-grpc", "go-web"
            )
            ProjectType.RUST -> listOf(
                "rust-cli", "rust-web", "rust-api"
            )
            ProjectType.JAVA -> listOf(
                "java-spring", "java-maven", "java-gradle"
            )
            ProjectType.KOTLIN -> listOf(
                "kotlin-gradle", "kotlin-android", "kotlin-spring"
            )
            else -> emptyList()
        }
    }
    
    /**
     * Data class for project type detection
     */
    data class ProjectTypeDetection(
        val projectType: ProjectType?,
        val suggestedTemplate: String?,
        val detectedLanguage: String?
    )
}
