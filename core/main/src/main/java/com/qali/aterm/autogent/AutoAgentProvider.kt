package com.qali.aterm.autogent

import com.qali.aterm.gemini.client.GeminiStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AutoAgent provider that uses only offline learned knowledge
 * Does not make external API calls - generates responses from database
 */
object AutoAgentProvider {
    private var database: LearningDatabase = LearningDatabase.getInstance()
    private val codeParser = CodeParser
    private val enhancedLearning = EnhancedLearningService
    
    /**
     * Initialize database with model name
     */
    fun initialize(modelName: String) {
        database = LearningDatabase.getInstance(modelName)
        // Note: AutoAgentLearningService.initialize() uses separate model name
        // This is for when AutoAgent is active, using the model name passed here
        AutoAgentLearningService.updateDatabaseForModel(modelName)
    }
    
    /**
     * Read and learn from full code files in workspace
     */
    suspend fun learnFromWorkspaceFiles(workspaceRoot: String, userPrompt: String) = withContext(Dispatchers.IO) {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory()) return@withContext
        
        // Find code files
        val codeFiles = findCodeFiles(workspaceDir)
        
        codeFiles.forEach { file ->
            try {
                val code = file.readText()
                if (code.length > 100) { // Only process substantial files
                    // Parse code into chunks
                    val chunks = codeParser.parseCodeToChunks(code)
                    
                    // Learn each chunk with metadata
                    chunks.forEach { chunk ->
                        // Extract theme properties if any
                        val themeProps = codeParser.extractThemeProperties(listOf(chunk))
                        if (themeProps.isNotEmpty()) {
                            enhancedLearning.learnObjectPattern(
                                objectName = chunk.name,
                                objectType = chunk.type.name,
                                properties = chunk.properties + themeProps,
                                userPrompt = userPrompt,
                                context = "File: ${file.name}"
                            )
                        }
                        
                        // Extract text content
                        val texts = codeParser.extractTextContent(listOf(chunk))
                        texts.forEach { text ->
                            enhancedLearning.learnObjectPattern(
                                objectName = "text_${chunk.name}",
                                objectType = "text_content",
                                properties = mapOf("content" to text),
                                userPrompt = userPrompt,
                                context = "File: ${file.name}, Chunk: ${chunk.name}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AutoAgent", "Error reading file ${file.name}", e)
            }
        }
    }
    
    /**
     * Find code files in workspace
     */
    private fun findCodeFiles(dir: File): List<File> {
        val codeFiles = mutableListOf<File>()
        val codeExtensions = listOf(
            ".kt", ".java", ".js", ".ts", ".py", ".xml", ".html", ".css", 
            ".json", ".dart", ".swift", ".go", ".rs", ".cpp", ".c", ".h"
        )
        
        dir.walkTopDown().forEach { file ->
            if (file.isFile && codeExtensions.any { file.name.endsWith(it, ignoreCase = true) }) {
                codeFiles.add(file)
            }
        }
        
        return codeFiles.take(50) // Limit to 50 files to avoid overload
    }
    
    /**
     * Generate response using only offline learned knowledge
     */
    suspend fun generateResponse(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (com.qali.aterm.gemini.core.FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
        AutoAgentLogger.info("AutoAgentProvider", "Starting response generation", mapOf("userMessage" to userMessage.take(100)))
        
        // Disable learning while AutoAgent is active
        AutoAgentLearningService.setLearningEnabled(false)
        
        try {
            // Check if classification model is ready
            if (!ClassificationModelManager.isModelReady()) {
                AutoAgentLogger.warning("AutoAgentProvider", "Classification model not ready")
                emit(GeminiStreamEvent.Chunk("⚠️ AutoAgent requires a classification model to be selected and downloaded.\n\n"))
                emit(GeminiStreamEvent.Chunk("Please go to Settings > Classification Model Settings to:\n"))
                emit(GeminiStreamEvent.Chunk("1. Select a built-in model (Mediapipe BERT or Universal Sentence Encoder)\n"))
                emit(GeminiStreamEvent.Chunk("2. Download the selected model\n"))
                emit(GeminiStreamEvent.Chunk("3. Or add a custom model from URL or file\n\n"))
                emit(GeminiStreamEvent.Chunk("Once a model is ready, AutoAgent will be able to classify and learn from code generation.\n"))
                onChunk("⚠️ AutoAgent requires a classification model. Please configure it in Settings.\n")
                emit(GeminiStreamEvent.Done)
                return@flow
            }
            
            // Initialize with AutoAgent-specific model name (separate from other providers)
            val modelName = ClassificationModelManager.getAutoAgentModelName()
            initialize(modelName)
            AutoAgentLogger.debug("AutoAgentProvider", "Initialized with model", mapOf("modelName" to modelName))
            
            // Analyze user prompt using text classifier
            val promptAnalysis = PromptAnalyzer.analyzePrompt(userMessage)
            AutoAgentLogger.info("AutoAgentProvider", "Prompt analyzed", mapOf(
                "intent" to promptAnalysis.intent.name,
                "frameworkType" to (promptAnalysis.frameworkType ?: "none"),
                "fileTypes" to promptAnalysis.fileTypes.joinToString(", ")
            ))
            
            // Search learned data for relevant content (including prompt pattern matching)
            val relevantData = searchRelevantKnowledge(userMessage, promptAnalysis)
            AutoAgentLogger.info("AutoAgentProvider", "Searched relevant knowledge", mapOf(
                "codeSnippets" to (relevantData[LearnedDataType.CODE_SNIPPET]?.size ?: 0),
                "apiUsage" to (relevantData[LearnedDataType.API_USAGE]?.size ?: 0),
                "fixes" to (relevantData[LearnedDataType.FIX_PATCH]?.size ?: 0),
                "metadata" to (relevantData[LearnedDataType.METADATA_TRANSFORMATION]?.size ?: 0)
            ))
            
            if (relevantData.isEmpty()) {
                AutoAgentLogger.warning("AutoAgentProvider", "No relevant knowledge found")
                emit(GeminiStreamEvent.Chunk("I don't have enough learned knowledge to help with this request yet. Please use other providers to generate solutions, and I'll learn from them.\n"))
                onChunk("I don't have enough learned knowledge to help with this request yet. Please use other providers to generate solutions, and I'll learn from them.\n")
                emit(GeminiStreamEvent.Done)
                return@flow
            }
            
            // Build context from learned data
            val context = buildContextFromLearnedData(relevantData, userMessage, promptAnalysis)
            
            // Generate response based on learned patterns and metadata
            val response = generateFromLearnedPatterns(context, userMessage, relevantData, promptAnalysis)
            
            // Run a lightweight self-check pass on the generated response.
            // This is for observability only and never blocks or mutates output.
            runSelfCheck(response, promptAnalysis)
            
            // Stream the response
            AutoAgentLogger.info("AutoAgentProvider", "Generating response", mapOf("responseLength" to response.length))
            streamResponse(response, onChunk)
            
            AutoAgentLogger.info("AutoAgentProvider", "Response generation completed successfully")
            emit(GeminiStreamEvent.Done)
        } catch (e: Exception) {
            AutoAgentLogger.error("AutoAgentProvider", "Error generating response", mapOf("error" to (e.message ?: "Unknown error")))
            android.util.Log.e("AutoAgent", "Error generating response", e)
            emit(GeminiStreamEvent.Error(e.message ?: "Unknown error"))
        } finally {
            // Re-enable learning when AutoAgent is done
            AutoAgentLearningService.setLearningEnabled(true)
            AutoAgentLogger.debug("AutoAgentProvider", "Learning re-enabled")
        }
    }
    
    /**
     * Lightweight self-check pass for generated responses.
     * Verifies that required files/functions inferred from metadata are present
     * in the generated response and logs any gaps for later analysis.
     */
    private fun runSelfCheck(
        response: String,
        promptAnalysis: PromptAnalysis
    ) {
        val requiredFiles = (promptAnalysis.metadata["file_names"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        val requiredFunctions = (promptAnalysis.metadata["function_names"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        
        val missingFiles = requiredFiles.filterNot { file ->
            response.contains(file, ignoreCase = true)
        }
        
        val missingFunctions = requiredFunctions.filterNot { fn ->
            response.contains(fn, ignoreCase = true)
        }
        
        if (missingFiles.isEmpty() && missingFunctions.isEmpty()) {
            AutoAgentLogger.debug(
                "AutoAgentProvider",
                "Self-check passed",
                mapOf(
                    "hasFiles" to requiredFiles.isNotEmpty(),
                    "hasFunctions" to requiredFunctions.isNotEmpty()
                )
            )
        } else {
            AutoAgentLogger.warning(
                "AutoAgentProvider",
                "Self-check: generated response may be missing required items",
                mapOf(
                    "missingFiles" to missingFiles.joinToString(", "),
                    "missingFunctions" to missingFunctions.joinToString(", "),
                    "frameworkType" to (promptAnalysis.frameworkType ?: "none")
                )
            )
        }
    }
    
    /**
     * Search for relevant knowledge based on user message and prompt analysis
     */
    private suspend fun searchRelevantKnowledge(
        userMessage: String,
        promptAnalysis: PromptAnalysis
    ): Map<String, List<LearnedDataEntry>> = withContext(Dispatchers.IO) {
        // Extract keywords from user message
        val keywords = extractKeywords(userMessage)
        
        val relevantData = mutableMapOf<String, MutableList<LearnedDataEntry>>()
        
        // Search each type with user prompt for relevance
        val types = listOf(
            LearnedDataType.CODE_SNIPPET,
            LearnedDataType.API_USAGE,
            LearnedDataType.FIX_PATCH,
            LearnedDataType.METADATA_TRANSFORMATION,
            "framework_knowledge" // Include framework knowledge
        )
        
        types.forEach { type ->
            // Search by keywords
            keywords.forEach { keyword ->
                val results = database.searchLearnedData(keyword, type, 10)
                relevantData.getOrPut(type) { mutableListOf() }.addAll(results)
            }
            
            // Search by prompt pattern for better matching
            if (promptAnalysis.promptPattern.isNotEmpty()) {
                val patternResults = database.searchByPromptPattern(promptAnalysis.promptPattern, type, 5)
                relevantData.getOrPut(type) { mutableListOf() }.addAll(patternResults)
            }
            
            // Search by framework type if available
            if (promptAnalysis.frameworkType != null) {
                val frameworkResults = database.searchByFrameworkType(promptAnalysis.frameworkType, type, 5)
                relevantData.getOrPut(type) { mutableListOf() }.addAll(frameworkResults)
            }
            
            // Get entries filtered by user prompt relevance (especially for metadata)
            val topEntries = database.getLearnedDataByType(type, 5, userPrompt = if (type == LearnedDataType.METADATA_TRANSFORMATION) userMessage else null)
            relevantData.getOrPut(type) { mutableListOf() }.addAll(topEntries)
        }
        
        // Remove duplicates and sort by score
        relevantData.forEach { (type, entries) ->
            relevantData[type] = entries.distinctBy { it.content }.sortedByDescending { it.positiveScore }.take(20).toMutableList()
        }
        
        relevantData
    }
    
    /**
     * Extract keywords from user message
     */
    private fun extractKeywords(message: String): List<String> {
        // Simple keyword extraction - can be enhanced
        val words = message.lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length > 3 }
            .distinct()
        
        // Also extract common programming terms
        val programmingTerms = listOf(
            "function", "class", "method", "api", "fix", "error", "bug",
            "code", "implementation", "create", "generate", "write"
        )
        
        val foundTerms = programmingTerms.filter { message.contains(it, ignoreCase = true) }
        
        return (words + foundTerms).distinct().take(10)
    }
    
    /**
     * Build context from learned data including framework knowledge
     */
    private fun buildContextFromLearnedData(
        relevantData: Map<String, List<LearnedDataEntry>>,
        userMessage: String,
        promptAnalysis: PromptAnalysis
    ): String {
        val context = StringBuilder()
        
        context.append("Based on learned knowledge and framework patterns, here's relevant information:\n\n")
        
        // Add framework knowledge first (high priority, filtered by frameworkType when available)
        val frameworkType = promptAnalysis.frameworkType
        val frameworkEntries = relevantData["framework_knowledge"].orEmpty()
            .let { entries ->
                if (frameworkType != null) {
                    entries.filter { entry ->
                        entry.metadata?.contains(frameworkType, ignoreCase = true) == true ||
                            entry.content.contains(frameworkType, ignoreCase = true)
                    }
                } else {
                    entries
                }
            }
            .sortedByDescending { it.positiveScore }
            .take(3)
        
        frameworkEntries.forEach { entry ->
            context.append("// Framework knowledge${if (frameworkType != null) " ($frameworkType)" else ""}:\n")
            context.append("${entry.content}\n\n")
        }
        
        // Add code snippets, preferring ones that match framework type or come from Gemini/normal_flow
        val codeSnippets = relevantData[LearnedDataType.CODE_SNIPPET].orEmpty()
            .sortedWith(
                compareByDescending<LearnedDataEntry> { it.positiveScore }
                    .thenByDescending { entry ->
                        when {
                            frameworkType != null && entry.content.contains(frameworkType, ignoreCase = true) -> 2
                            entry.source.contains("gemini", ignoreCase = true) -> 1
                            else -> 0
                        }
                    }
            )
            .take(5)
        
        codeSnippets.forEach { entry ->
            context.append("// Learned code snippet (score: ${entry.positiveScore}, source: ${entry.source}):\n")
            if (entry.metadata?.contains("import") == true) {
                context.append("// Imports: ${entry.metadata}\n")
            }
            context.append("${entry.content}\n\n")
        }
        
        // Add API usage patterns
        relevantData[LearnedDataType.API_USAGE]?.take(3)?.forEach { entry ->
            context.append("// API usage pattern (score: ${entry.positiveScore}):\n")
            context.append("${entry.content}\n\n")
        }
        
        // Add fixes if relevant
        if (userMessage.contains("fix", ignoreCase = true) || userMessage.contains("error", ignoreCase = true)) {
            relevantData[LearnedDataType.FIX_PATCH]?.take(3)?.forEach { entry ->
                context.append("// Fix pattern (score: ${entry.positiveScore}):\n")
                context.append("${entry.content}\n\n")
            }
        }
        
        return context.toString()
    }
    
    /**
     * Generate response from learned patterns using metadata
     */
    private fun generateFromLearnedPatterns(
        context: String,
        userMessage: String,
        relevantData: Map<String, List<LearnedDataEntry>>,
        promptAnalysis: PromptAnalysis
    ): String {
        val response = StringBuilder()
        
        // Use intent from prompt analysis
        val intent = promptAnalysis.intent
        
        when (intent) {
            com.qali.aterm.autogent.Intent.ANSWER_QUESTION -> {
                // Search for learned Q&A pairs
                val qaEntries = relevantData[LearnedDataType.METADATA_TRANSFORMATION]?.filter { entry ->
                    entry.metadata?.contains("\"question\"") == true && entry.metadata?.contains("\"answer\"") == true
                } ?: emptyList()
                
                if (qaEntries.isNotEmpty()) {
                    // Find best matching Q&A
                    val bestMatch = qaEntries.firstOrNull()
                    if (bestMatch != null) {
                        // Parse Q&A from metadata
                        val qaMatch = try {
                            val metadata = bestMatch.metadata ?: ""
                            val questionMatch = Regex("\"question\"\\s*:\\s*\"([^\"]+)\"").find(metadata)
                            val answerMatch = Regex("\"answer\"\\s*:\\s*\"([^\"]+)\"").find(metadata)
                            
                            if (questionMatch != null && answerMatch != null) {
                                Pair(
                                    questionMatch.groupValues[1].replace("\\n", "\n"),
                                    answerMatch.groupValues[1].replace("\\n", "\n")
                                )
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                        
                        if (qaMatch != null) {
                            response.append("Based on learned knowledge:\n\n")
                            response.append(qaMatch.second)
                            response.append("\n\n(Learned from previous question: \"${qaMatch.first.take(100)}\")")
                        } else {
                            // Fallback to content
                            response.append("Based on learned knowledge:\n\n")
                            response.append(bestMatch.content)
                        }
                    }
                } else {
                    // Fallback: use general metadata transformations that might answer the question
                    val generalEntries = relevantData[LearnedDataType.METADATA_TRANSFORMATION]?.take(3)
                    if (generalEntries != null && generalEntries.isNotEmpty()) {
                        response.append("Based on learned knowledge:\n\n")
                        generalEntries.forEach { entry ->
                            response.append("${entry.content}\n\n")
                        }
                    } else {
                        response.append("I don't have learned answers for this question yet. Please use other providers to get answers, and I'll learn from them.")
                    }
                }
            }
            com.qali.aterm.autogent.Intent.CREATE_CODE -> {
                // Find best matching code snippet or framework knowledge
                val frameworkType = promptAnalysis.frameworkType
                val allCodeSnippets = relevantData[LearnedDataType.CODE_SNIPPET].orEmpty()
                val allFramework = relevantData["framework_knowledge"].orEmpty()
                
                // Prefer snippets that match framework type or were learned from Gemini
                val prioritizedSnippets = allCodeSnippets.sortedWith(
                    compareByDescending<LearnedDataEntry> { entry ->
                        when {
                            frameworkType != null && entry.content.contains(frameworkType, ignoreCase = true) -> 3
                            entry.source.contains("gemini", ignoreCase = true) -> 2
                            entry.source.contains("normal_flow", ignoreCase = true) -> 1
                            else -> 0
                        }
                    }.thenByDescending { it.positiveScore }
                )
                
                val prioritizedFramework = allFramework.sortedWith(
                    compareByDescending<LearnedDataEntry> { entry ->
                        when {
                            frameworkType != null && entry.content.contains(frameworkType, ignoreCase = true) -> 1
                            else -> 0
                        }
                    }.thenByDescending { it.positiveScore }
                )
                
                val bestMatch = prioritizedSnippets.firstOrNull()
                    ?: prioritizedFramework.firstOrNull()
                
                if (bestMatch != null) {
                    response.append("Based on learned patterns and framework knowledge, here's a solution:\n\n")
                    
                    // Add imports if available
                    if (promptAnalysis.importPatterns != null) {
                        response.append("// Required imports:\n")
                        promptAnalysis.importPatterns.split(", ").forEach { imp ->
                            response.append("// $imp\n")
                        }
                        response.append("\n")
                    }
                    
                    // Adapt code to request with metadata
                    response.append(adaptCodeToRequest(bestMatch.content, userMessage, promptAnalysis))
                    
                    // Add event handlers if relevant
                    if (promptAnalysis.eventHandlerPatterns != null && promptAnalysis.frameworkType in listOf("HTML", "JavaScript")) {
                        response.append("\n// Event handlers: ${promptAnalysis.eventHandlerPatterns}\n")
                    }
                } else {
                    response.append("I don't have learned code for this specific request yet. Using framework knowledge:\n\n")
                    // Fallback to framework knowledge
                    relevantData["framework_knowledge"]?.firstOrNull()?.let { framework ->
                        response.append(framework.content)
                    } ?: response.append("No framework knowledge available for this request.")
                }
            }
            com.qali.aterm.autogent.Intent.FIX_CODE -> {
                // Find relevant fixes by keywords
                val keywords = extractKeywords(userMessage)
                val fixes = database.getFixesByKeywords(keywords, 5)
                if (fixes.isNotEmpty()) {
                    response.append("Based on learned fixes (retrieved by keywords: ${keywords.joinToString(", ")}), here are potential solutions:\n\n")
                    fixes.forEach { fix ->
                        response.append("```json\n")
                        response.append("{\n")
                        response.append("  \"old_code\": \"${fix.oldCode.take(200)}\",\n")
                        response.append("  \"new_code\": \"${fix.newCode.take(200)}\",\n")
                        response.append("  \"reason\": \"${fix.reason}\",\n")
                        response.append("  \"score\": ${fix.score}\n")
                        response.append("}\n")
                        response.append("```\n\n")
                    }
                } else {
                    // Fallback to regular fixes
                    val regularFixes = relevantData[LearnedDataType.FIX_PATCH]?.take(2)
                    if (regularFixes != null && regularFixes.isNotEmpty()) {
                        response.append("Based on learned fixes:\n\n")
                        regularFixes.forEach { fix ->
                            response.append("${fix.content}\n\n")
                        }
                    } else {
                        response.append("I don't have learned fixes for this issue yet.")
                    }
                }
            }
            com.qali.aterm.autogent.Intent.USE_API -> {
                // Find API usage patterns
                val apiPatterns = relevantData[LearnedDataType.API_USAGE]?.take(3)
                if (apiPatterns != null && apiPatterns.isNotEmpty()) {
                    response.append("Based on learned API usage:\n\n")
                    apiPatterns.forEach { pattern ->
                        response.append("${pattern.content}\n\n")
                    }
                } else {
                    response.append("I don't have learned API patterns for this yet.")
                }
            }
            com.qali.aterm.autogent.Intent.RUN_TEST -> {
                // Test execution - use learned test patterns
                response.append("Based on learned test patterns:\n\n")
                val testPatterns = relevantData[LearnedDataType.CODE_SNIPPET]?.filter { 
                    it.content.contains("test", ignoreCase = true) 
                }?.take(3)
                if (testPatterns != null && testPatterns.isNotEmpty()) {
                    testPatterns.forEach { pattern ->
                        response.append("${pattern.content}\n\n")
                    }
                } else {
                    response.append("I don't have learned test patterns for this yet.")
                }
            }
            com.qali.aterm.autogent.Intent.GENERAL -> {
                // General response combining all types
                response.append(context)
                response.append("\n\nBased on the learned knowledge above, here's my response:\n")
                response.append(generateGeneralResponse(userMessage, relevantData))
            }
        }
        
        return response.toString()
    }
    
    // Note: analyzeIntent is no longer used - PromptAnalyzer handles intent extraction
    
    /**
     * Adapt learned code to user request using metadata
     */
    private fun adaptCodeToRequest(
        learnedCode: String,
        request: String,
        promptAnalysis: PromptAnalysis
    ): String {
        var adaptedCode = learnedCode
        
        // Replace placeholders with metadata if available
        promptAnalysis.metadata["file_names"]?.let { fileNames ->
            if (fileNames is List<*>) {
                fileNames.firstOrNull()?.let { fileName ->
                    adaptedCode = adaptedCode.replace("{file}", fileName.toString())
                }
            }
        }
        
        promptAnalysis.metadata["function_names"]?.let { functionNames ->
            if (functionNames is List<*>) {
                functionNames.firstOrNull()?.let { functionName ->
                    adaptedCode = adaptedCode.replace("{function}", functionName.toString())
                }
            }
        }
        
        return "$adaptedCode\n\n// Adapted from learned knowledge and framework patterns"
    }
    
    /**
     * Generate general response
     */
    private fun generateGeneralResponse(
        userMessage: String,
        relevantData: Map<String, List<LearnedDataEntry>>
    ): String {
        val response = StringBuilder()
        
        // Combine relevant learned data
        val allRelevant = relevantData.values.flatten()
            .sortedByDescending { it.positiveScore }
            .take(5)
        
        if (allRelevant.isNotEmpty()) {
            response.append("Here's relevant learned knowledge:\n\n")
            allRelevant.forEach { entry ->
                response.append("${entry.content}\n\n")
            }
        } else {
            response.append("I need more learned knowledge to help with this request.")
        }
        
        return response.toString()
    }
    
    /**
     * Stream response in chunks
     */
    private suspend fun streamResponse(
        response: String,
        onChunk: (String) -> Unit
    ) {
        val words = response.split(" ")
        var currentChunk = ""
        
        for (word in words) {
            currentChunk += if (currentChunk.isEmpty()) word else " $word"
            
            // Emit chunk every 5 words or on newline
            if (currentChunk.lines().size > 1 || currentChunk.split(" ").size >= 5) {
                onChunk(currentChunk)
                delay(50) // Small delay for streaming effect
                currentChunk = ""
            }
        }
        
        // Emit remaining chunk
        if (currentChunk.isNotEmpty()) {
            onChunk(currentChunk)
        }
    }
    
    /**
     * User intent types
     */
    private enum class Intent {
        ANSWER_QUESTION,
        CREATE_CODE,
        FIX_CODE,
        USE_API,
        GENERAL
    }
}

