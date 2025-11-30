package com.qali.aterm.agent.client.intent

import com.qali.aterm.agent.MemoryService
import java.io.File

/**
 * Detects user intents from messages
 */
object IntentDetector {
    
    /**
     * Detect multiple user intents: can detect multiple intents in one message.
     * This is a lightweight classifier – it does not call any remote LLM.
     */
    suspend fun detectIntents(
        userMessage: String,
        workspaceRoot: String
    ): List<IntentType> {
        val intents = mutableListOf<IntentType>()
        
        // Load memory context for better intent detection
        val memoryContext = MemoryService.getSummarizedMemory()
        
        val debugKeywords = listOf(
            "debug", "fix", "repair", "error", "bug", "issue", "problem",
            "upgrade", "update", "improve", "refactor", "modify", "change",
            "enhance", "optimize", "correct", "resolve", "solve"
        )
        
        val stacktraceIndicators = listOf(
            "exception:", "traceback (most recent call last)", " at ",
            "java.lang.", "kotlin.", "org.junit.", "AssertionError",
            "Error:", "ReferenceError", "TypeError", "SyntaxError"
        )
        
        val createKeywords = listOf(
            "create", "new", "build", "generate", "make", "start", "init",
            "setup", "scaffold", "bootstrap"
        )
        
        val testKeywords = listOf(
            "test", "run test", "run tests", "test api", "test endpoint", "test endpoints",
            "api test", "api testing", "test server", "test the", "testing", "test suite",
            "unit test", "integration test", "e2e test", "end to end test", "test coverage",
            "pytest", "jest", "mocha", "npm test", "test command", "execute test"
        )
        
        val questionWords = listOf(
            "what", "how", "why", "when", "where", "which", "who", "whom", "whose",
            "can you", "could you", "would you", "should i", "is there", "are there",
            "does", "do", "did", "will", "would", "should", "may", "might"
        )
        
        val messageLower = userMessage.lowercase()
        val contextLower = (userMessage + " " + memoryContext).lowercase()
        
        var debugScore = debugKeywords.count { contextLower.contains(it) }
        val createScore = createKeywords.count { contextLower.contains(it) }
        val testScore = testKeywords.count { contextLower.contains(it) }
        val questionIndicators = questionWords.count { messageLower.contains(it) }
        val endsWithQuestionMark = userMessage.trim().endsWith("?")
        val isQuestionPattern = messageLower.matches(Regex(".*\\b(what|how|why|when|where|which|who)\\b.*"))
        
        // Strong signal for DEBUG: presence of stack traces / exceptions
        val hasStacktraceSignals = stacktraceIndicators.any { contextLower.contains(it.lowercase()) }
        if (hasStacktraceSignals) {
            debugScore += 3 // boost debug score so debug wins over create/question
        }
        
        // Check if workspace has existing files
        val workspaceDir = File(workspaceRoot)
        val hasExistingFiles = workspaceDir.exists() && 
            workspaceDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        
        // Check memory for project context
        val hasProjectContext = memoryContext.contains("project", ignoreCase = true) ||
                                memoryContext.contains("codebase", ignoreCase = true) ||
                                memoryContext.contains("repository", ignoreCase = true)
        
        // Detect QUESTION intent (can coexist with others)
        val isQuestion = (endsWithQuestionMark || questionIndicators > 0 || isQuestionPattern) &&
                        (questionIndicators > 0 || endsWithQuestionMark)
        if (isQuestion && !hasStacktraceSignals) {
            // If we clearly have a stacktrace, prefer DEBUG over QUESTION
            intents.add(IntentType.QUESTION_ONLY)
        }
        
        // Testing is now part of DEBUG flow - AI can suggest testing during debugging
        
        // Detect DEBUG intent
        val isDebugByScore = debugScore > 0
        val isDebugByContext = hasProjectContext && hasExistingFiles && debugScore >= createScore
        val isDebug = hasExistingFiles && (isDebugByScore || isDebugByContext || hasStacktraceSignals)
        if (isDebug) {
            intents.add(IntentType.DEBUG_UPGRADE)
        }
        
        // Detect CREATE intent
        if (createScore > 0 && (!hasExistingFiles || createScore > debugScore)) {
            intents.add(IntentType.CREATE_NEW)
        }
        
        // If no intents detected, use default
        if (intents.isEmpty()) {
            intents.add(if (hasExistingFiles) IntentType.DEBUG_UPGRADE else IntentType.CREATE_NEW)
        }
        
        android.util.Log.d(
            "IntentDetector",
            "detectIntents: debugScore=$debugScore, createScore=$createScore, testScore=$testScore, " +
                "questionIndicators=$questionIndicators, hasStacktraceSignals=$hasStacktraceSignals, intents=$intents"
        )
        
        return intents.distinct() // Remove duplicates
    }
    
    /**
     * Enhance user intent with clarifying guidance to help AI understand goals, do's, and don'ts
     * Adds helpful context directly to the prompt without extra API calls
     */
    suspend fun enhanceUserIntent(userMessage: String, intent: IntentType): String {
        val memoryContext = MemoryService.getSummarizedMemory()
        
        val intentDescription = when (intent) {
            IntentType.CREATE_NEW -> "creating a new project"
            IntentType.DEBUG_UPGRADE -> "debugging or upgrading an existing project"
            IntentType.QUESTION_ONLY -> "answering a question"
        }
        
        // Add helpful guidance directly to the user message
        val enhancement = """
            === User Request ===
            $userMessage
            
            === Context & Guidance ===
            Task Type: $intentDescription
            
            Please ensure you understand:
            - **Primary Goal**: What should the end result accomplish? What is the main objective?
            - **Key Requirements**: What are the must-have features, constraints, or specifications?
            - **Best Practices**: What should be included? Follow industry standards and best practices.
            - **What to Avoid**: What are common pitfalls or anti-patterns to avoid?
            - **Success Criteria**: How will we verify the project is complete and working correctly?
            
            Be thorough, consider edge cases, and ensure the implementation is production-ready and functional.
            ${if (memoryContext.isNotEmpty()) "\nPrevious context:\n$memoryContext" else ""}
        """.trimIndent()
        
        return enhancement
    }
    
    /**
     * Detect if task needs documentation search or planning phase
     * Returns true if task likely needs web search for documentation, tutorials, or examples
     */
    fun needsDocumentationSearch(userMessage: String): Boolean {
        val messageLower = userMessage.lowercase()
        val memoryContext = MemoryService.getSummarizedMemory().lowercase()
        val contextLower = (userMessage + " " + memoryContext).lowercase()
        
        // Keywords indicating need for documentation/search
        val docSearchKeywords = listOf(
            "documentation", "docs", "tutorial", "example", "guide", "how to",
            "api", "library", "framework", "package", "npm", "pip", "crate",
            "learn", "understand", "reference", "specification", "spec",
            "unknown", "unfamiliar", "new", "first time", "don't know",
            "latest", "current", "up to date", "recent", "modern"
        )
        
        // Framework/library names that might need documentation
        val frameworkKeywords = listOf(
            "react", "vue", "angular", "svelte", "next", "nuxt",
            "express", "fastapi", "django", "flask", "spring",
            "tensorflow", "pytorch", "keras", "pandas", "numpy"
        )
        
        // Check for documentation search indicators
        val hasDocKeywords = docSearchKeywords.any { contextLower.contains(it) }
        val hasFrameworkKeywords = frameworkKeywords.any { contextLower.contains(it) }
        val mentionsLibrary = contextLower.contains("library") || contextLower.contains("package") || 
                             contextLower.contains("framework") || contextLower.contains("tool")
        
        // If task mentions unfamiliar libraries/frameworks or asks for documentation
        return hasDocKeywords || (hasFrameworkKeywords && mentionsLibrary) ||
               messageLower.contains("how do i") || messageLower.contains("what is") ||
               messageLower.contains("show me") || messageLower.contains("find")
    }
    
    /**
     * Detect if task only needs commands to run (no file creation needed)
     */
    suspend fun detectCommandsOnly(userMessage: String, workspaceRoot: String): Boolean {
        val messageLower = userMessage.lowercase()
        
        // Keywords indicating command-only tasks
        val commandOnlyKeywords = listOf(
            "run", "execute", "install", "start", "launch", "test", "build", "compile",
            "deploy", "migrate", "update", "upgrade", "setup", "configure", "init",
            "install dependencies", "run tests", "start server", "build project"
        )
        
        // Check if message is primarily about running commands
        val hasCommandKeywords = commandOnlyKeywords.any { messageLower.contains(it) }
        
        // Check if workspace has existing files (suggests command-only task)
        val workspaceDir = File(workspaceRoot)
        val hasExistingFiles = workspaceDir.exists() && 
            workspaceDir.listFiles()?.any { it.isFile && !it.name.startsWith(".") } == true
        
        // If has command keywords and existing files, likely command-only
        return hasCommandKeywords && hasExistingFiles && 
               !messageLower.contains("create") && 
               !messageLower.contains("write") && 
               !messageLower.contains("generate") &&
               !messageLower.contains("make")
    }
}
