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
            
            // Initialize with current model name
            val modelName = com.qali.aterm.api.ApiProviderManager.getCurrentModel()
            initialize(modelName)
            AutoAgentLogger.debug("AutoAgentProvider", "Initialized with model", mapOf("modelName" to modelName))
            
            // Search learned data for relevant content
            val relevantData = searchRelevantKnowledge(userMessage)
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
            val context = buildContextFromLearnedData(relevantData, userMessage)
            
            // Generate response based on learned patterns
            val response = generateFromLearnedPatterns(context, userMessage, relevantData)
            
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
     * Search for relevant knowledge based on user message
     */
    private suspend fun searchRelevantKnowledge(userMessage: String): Map<String, List<LearnedDataEntry>> = withContext(Dispatchers.IO) {
        // Extract keywords from user message
        val keywords = extractKeywords(userMessage)
        
        val relevantData = mutableMapOf<String, MutableList<LearnedDataEntry>>()
        
        // Search each type with user prompt for relevance
        val types = listOf(
            LearnedDataType.CODE_SNIPPET,
            LearnedDataType.API_USAGE,
            LearnedDataType.FIX_PATCH,
            LearnedDataType.METADATA_TRANSFORMATION
        )
        
        types.forEach { type ->
            // Search by keywords
            keywords.forEach { keyword ->
                val results = database.searchLearnedData(keyword, type, 10)
                relevantData.getOrPut(type) { mutableListOf() }.addAll(results)
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
     * Build context from learned data
     */
    private fun buildContextFromLearnedData(
        relevantData: Map<String, List<LearnedDataEntry>>,
        userMessage: String
    ): String {
        val context = StringBuilder()
        
        context.append("Based on learned knowledge, here's relevant information:\n\n")
        
        // Add code snippets
        relevantData[LearnedDataType.CODE_SNIPPET]?.take(5)?.forEach { entry ->
            context.append("// Learned code snippet (score: ${entry.positiveScore}):\n")
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
     * Generate response from learned patterns
     */
    private fun generateFromLearnedPatterns(
        context: String,
        userMessage: String,
        relevantData: Map<String, List<LearnedDataEntry>>
    ): String {
        val response = StringBuilder()
        
        // Analyze user intent
        val intent = analyzeIntent(userMessage)
        
        when (intent) {
            Intent.ANSWER_QUESTION -> {
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
            Intent.CREATE_CODE -> {
                // Find best matching code snippet
                val bestMatch = relevantData[LearnedDataType.CODE_SNIPPET]?.firstOrNull()
                if (bestMatch != null) {
                    response.append("Based on learned patterns, here's a solution:\n\n")
                    response.append(adaptCodeToRequest(bestMatch.content, userMessage))
                } else {
                    response.append("I don't have learned code for this specific request yet.")
                }
            }
            Intent.FIX_CODE -> {
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
            Intent.USE_API -> {
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
            Intent.GENERAL -> {
                // General response combining all types
                response.append(context)
                response.append("\n\nBased on the learned knowledge above, here's my response:\n")
                response.append(generateGeneralResponse(userMessage, relevantData))
            }
        }
        
        return response.toString()
    }
    
    /**
     * Analyze user intent
     */
    private fun analyzeIntent(message: String): Intent {
        val lower = message.lowercase()
        val questionWords = listOf("what", "how", "why", "when", "where", "which", "who", "does", "do", "did", "will", "would", "should", "can", "could")
        val isQuestion = message.trim().endsWith("?") || questionWords.any { lower.contains(it) }
        
        return when {
            isQuestion -> Intent.ANSWER_QUESTION
            lower.contains("create") || lower.contains("write") || lower.contains("generate") || lower.contains("implement") -> Intent.CREATE_CODE
            lower.contains("fix") || lower.contains("error") || lower.contains("bug") || lower.contains("issue") -> Intent.FIX_CODE
            lower.contains("api") || lower.contains("call") || lower.contains("request") -> Intent.USE_API
            else -> Intent.GENERAL
        }
    }
    
    /**
     * Adapt learned code to user request
     */
    private fun adaptCodeToRequest(learnedCode: String, request: String): String {
        // Simple adaptation - can be enhanced with more sophisticated pattern matching
        // For now, return the learned code with a note
        return "$learnedCode\n\n// Adapted from learned knowledge"
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

