package com.qali.aterm.autogent

import kotlin.text.RegexOption

/**
 * Analyzes user prompts to extract intent, file types, metadata, and framework information
 * Uses text classification to understand what the user wants to do
 */
object PromptAnalyzer {
    
    /**
     * Analyze user prompt and extract structured information
     */
    fun analyzePrompt(userPrompt: String): PromptAnalysis {
        val normalized = userPrompt.lowercase().trim()
        
        // Extract intent
        val intent = extractIntent(normalized)
        
        // Extract file types
        val fileTypes = extractFileTypes(normalized)
        
        // Extract framework type
        val frameworkType = extractFrameworkType(normalized)
        
        // Extract metadata (file names, function names, etc.)
        val metadata = extractMetadata(userPrompt, normalized)
        
        // Extract prompt pattern (normalized pattern for similarity matching)
        val promptPattern = extractPromptPattern(normalized)
        
        // Extract import patterns
        val importPatterns = extractImportPatterns(normalized, frameworkType)
        
        // Extract event handler patterns
        val eventHandlerPatterns = extractEventHandlerPatterns(normalized, frameworkType)
        
        return PromptAnalysis(
            intent = intent,
            fileTypes = fileTypes,
            frameworkType = frameworkType,
            metadata = metadata,
            promptPattern = promptPattern,
            importPatterns = importPatterns,
            eventHandlerPatterns = eventHandlerPatterns
        )
    }
    
    private fun extractIntent(prompt: String): Intent {
        return when {
            prompt.contains("create") || prompt.contains("write") || prompt.contains("generate") || 
            prompt.contains("implement") || prompt.contains("make") -> Intent.CREATE_CODE
            prompt.contains("fix") || prompt.contains("error") || prompt.contains("bug") || 
            prompt.contains("issue") || prompt.contains("debug") -> Intent.FIX_CODE
            prompt.contains("test") || prompt.contains("run test") -> Intent.RUN_TEST
            prompt.contains("api") || prompt.contains("call") || prompt.contains("request") -> Intent.USE_API
            prompt.contains("what") || prompt.contains("how") || prompt.contains("why") || 
            prompt.contains("explain") || prompt.endsWith("?") -> Intent.ANSWER_QUESTION
            else -> Intent.GENERAL
        }
    }
    
    private fun extractFileTypes(prompt: String): List<String> {
        val fileTypes = mutableListOf<String>()
        
        val typePatterns = mapOf(
            "html" to listOf("html", ".html", "html file"),
            "css" to listOf("css", ".css", "stylesheet", "style"),
            "javascript" to listOf("javascript", "js", ".js", "script"),
            "typescript" to listOf("typescript", "ts", ".ts"),
            "python" to listOf("python", "py", ".py", "python file"),
            "java" to listOf("java", ".java", "java file"),
            "kotlin" to listOf("kotlin", "kt", ".kt", "kotlin file"),
            "json" to listOf("json", ".json"),
            "xml" to listOf("xml", ".xml"),
            "yaml" to listOf("yaml", "yml", ".yaml", ".yml")
        )
        
        typePatterns.forEach { (type, patterns) ->
            if (patterns.any { prompt.contains(it) }) {
                fileTypes.add(type)
            }
        }
        
        return fileTypes.distinct()
    }
    
    private fun extractFrameworkType(prompt: String): String? {
        val frameworks = mapOf(
            "HTML" to listOf("html", "html5"),
            "CSS" to listOf("css", "stylesheet", "flexbox", "grid"),
            "JavaScript" to listOf("javascript", "js", "es6", "es2015", "dom", "vanilla js"),
            "Node.js" to listOf("node", "nodejs", "express", "npm", "server"),
            "Python" to listOf("python", "django", "flask", "fastapi"),
            "Java" to listOf("java", "spring", "spring boot", "jvm"),
            "Kotlin" to listOf("kotlin", "android", "coroutines"),
            "MVC" to listOf("mvc", "model view controller", "route", "router", "view", "controller")
        )
        
        frameworks.forEach { (framework, patterns) ->
            if (patterns.any { prompt.contains(it) }) {
                return framework
            }
        }
        
        return null
    }
    
    private fun extractMetadata(prompt: String, normalized: String): Map<String, Any> {
        val metadata = mutableMapOf<String, Any>()
        
        // Extract file names (quoted strings or after "file" keyword)
        val filePattern = Regex("""["']([^"']+\.(html|css|js|ts|py|java|kt|json|xml))["']""", RegexOption.IGNORE_CASE)
        val fileMatches = filePattern.findAll(prompt)
        val fileNames = fileMatches.map { match -> match.groupValues[1] }.toList()
        if (fileNames.isNotEmpty()) {
            metadata["file_names"] = fileNames
        }
        
        // Extract function/class names (capitalized words or after "function"/"class")
        val functionPattern = Regex("""(?:function|def|fun|class|interface)\s+([a-zA-Z_][a-zA-Z0-9_]*)""", RegexOption.IGNORE_CASE)
        val functionMatches = functionPattern.findAll(prompt)
        val functionNames = functionMatches.map { match -> match.groupValues[1] }.toList()
        if (functionNames.isNotEmpty()) {
            metadata["function_names"] = functionNames
        }
        
        // Extract keywords
        val keywords = normalized.split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length > 3 }
            .distinct()
            .take(10)
        if (keywords.isNotEmpty()) {
            metadata["keywords"] = keywords
        }
        
        return metadata
    }
    
    private fun extractPromptPattern(normalized: String): String {
        // Normalize prompt to a pattern by removing specific values
        var pattern = normalized
        
        // Replace specific values with placeholders
        pattern = Regex("""["'][^"']+["']""").replace(pattern, "{value}")
        pattern = Regex("""\d+""").replace(pattern, "{number}")
        pattern = Regex("""[a-zA-Z_][a-zA-Z0-9_]*\.[a-zA-Z]+""").replace(pattern, "{file}")
        
        // Remove extra whitespace
        pattern = pattern.replace(Regex("\\s+"), " ").trim()
        
        return pattern
    }
    
    private fun extractImportPatterns(prompt: String, frameworkType: String?): String? {
        val importPatterns = mutableListOf<String>()
        
        // Extract explicit imports mentioned in prompt
        val importRegex = Regex("""(?:import|require|from)\s+["']?([^"'\s]+)["']?""", RegexOption.IGNORE_CASE)
        val imports = importRegex.findAll(prompt).map { match -> match.groupValues[1] }.toList()
        importPatterns.addAll(imports)
        
        // Add framework-specific common imports
        when (frameworkType) {
            "Node.js" -> importPatterns.addAll(listOf("require('express')", "require('fs')", "require('path')"))
            "Python" -> importPatterns.addAll(listOf("import json", "import os", "import sys", "from typing import"))
            "Java" -> importPatterns.addAll(listOf("import org.springframework", "import java.util"))
            "Kotlin" -> importPatterns.addAll(listOf("import kotlinx.coroutines", "import kotlinx.coroutines.Dispatchers"))
            "JavaScript" -> importPatterns.addAll(listOf("import", "export", "from"))
        }
        
        return if (importPatterns.isNotEmpty()) {
            importPatterns.distinct().joinToString(", ")
        } else {
            null
        }
    }
    
    private fun extractEventHandlerPatterns(prompt: String, frameworkType: String?): String? {
        val eventPatterns = mutableListOf<String>()
        
        // Extract explicit event handlers mentioned
        val eventRegex = Regex("""(?:on|handle)(?:click|change|submit|load|focus|blur|mouse|key|input|change)""", RegexOption.IGNORE_CASE)
        val events = eventRegex.findAll(prompt).map { match -> match.value.lowercase() }.toList()
        eventPatterns.addAll(events)
        
        // Add framework-specific common event handlers
        when (frameworkType) {
            "HTML" -> eventPatterns.addAll(listOf("onclick", "onchange", "onsubmit", "onload", "onfocus", "onblur"))
            "JavaScript" -> eventPatterns.addAll(listOf("addEventListener", "onclick", "onchange", "onsubmit"))
        }
        
        return if (eventPatterns.isNotEmpty()) {
            eventPatterns.distinct().joinToString(", ")
        } else {
            null
        }
    }
}

/**
 * Result of prompt analysis
 */
data class PromptAnalysis(
    val intent: Intent,
    val fileTypes: List<String>,
    val frameworkType: String?,
    val metadata: Map<String, Any>,
    val promptPattern: String,
    val importPatterns: String?,
    val eventHandlerPatterns: String?
)

/**
 * User intent types
 */
enum class Intent {
    CREATE_CODE,
    FIX_CODE,
    RUN_TEST,
    USE_API,
    ANSWER_QUESTION,
    GENERAL
}
