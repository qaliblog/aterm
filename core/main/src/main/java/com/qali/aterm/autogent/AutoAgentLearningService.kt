package com.qali.aterm.autogent

import com.qali.aterm.api.ApiProviderManager
import kotlin.text.RegexOption
import com.qali.aterm.api.ApiProviderType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Service that learns from other providers when AutoAgent is inactive
 * Observes outputs in both streaming and non-streaming modes
 */
object AutoAgentLearningService {
    private var database: LearningDatabase = LearningDatabase.getInstance()
    private val classifier = TextClassifier
    
    /**
     * Initialize database with AutoAgent-specific model name
     * This is separate from other providers' model names
     */
    fun initialize() {
        val modelName = ClassificationModelManager.getAutoAgentModelName()
        database = LearningDatabase.getInstance(modelName)
    }
    
    /**
     * Update database instance based on model name
     * @deprecated Use initialize() instead to use AutoAgent-specific model name
     */
    fun updateDatabaseForModel(modelName: String) {
        database = LearningDatabase.getInstance(modelName)
    }
    
    private val learningScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val learningQueue = ConcurrentLinkedQueue<LearningTask>()
    
    private val _learningEvents = MutableSharedFlow<LearningEvent>()
    val learningEvents: Flow<LearningEvent> = _learningEvents.asSharedFlow()
    
    private var isLearningEnabled = true
    private var isProcessing = false
    
    /**
     * Check if learning should be active (AutoAgent is not selected)
     */
    fun shouldLearn(): Boolean {
        return isLearningEnabled && ApiProviderManager.selectedProvider != ApiProviderType.AUTOAGENT
    }
    
    /**
     * Enable or disable learning
     */
    fun setLearningEnabled(enabled: Boolean) {
        isLearningEnabled = enabled
    }
    
    /**
     * Learn from successful code generation
     */
    fun learnFromCodeGeneration(
        code: String,
        context: String? = null,
        source: String = LearnedDataSource.NORMAL_FLOW,
        metadata: Map<String, Any>? = null,
        userPrompt: String? = null
    ) {
        if (!shouldLearn()) return
        
        // Initialize with AutoAgent-specific model name (separate from other providers)
        initialize()
        
        AutoAgentLogger.debug("AutoAgentLearning", "Learning from code generation", mapOf(
            "codeLength" to code.length,
            "source" to source,
            "hasUserPrompt" to (userPrompt != null)
        ))
        
        learningQueue.offer(
            LearningTask.CodeGeneration(
                code = code,
                context = context,
                source = source,
                metadata = metadata,
                userPrompt = userPrompt
            )
        )
        
        processLearningQueue()
    }
    
    /**
     * Learn from streaming chunks
     */
    fun learnFromStreamingChunk(
        chunk: String,
        accumulatedContent: String,
        source: String = LearnedDataSource.NORMAL_FLOW
    ) {
        if (!shouldLearn()) return
        
        // Initialize with AutoAgent-specific model name (separate from other providers)
        initialize()
        
        learningQueue.offer(
            LearningTask.StreamingChunk(
                chunk = chunk,
                accumulatedContent = accumulatedContent,
                source = source
            )
        )
        
        processLearningQueue()
    }
    
    /**
     * Learn from successful fix/application
     */
    fun learnFromFix(
        oldCode: String,
        newCode: String,
        reason: String? = null,
        source: String = LearnedDataSource.DEBUG_FEEDBACK,
        userPrompt: String? = null,
        keywords: List<String>? = null
    ) {
        if (!shouldLearn()) return
        
        // Initialize with AutoAgent-specific model name (separate from other providers)
        initialize()
        
        learningQueue.offer(
            LearningTask.Fix(
                oldCode = oldCode,
                newCode = newCode,
                reason = reason,
                source = source,
                userPrompt = userPrompt,
                keywords = keywords
            )
        )
        
        processLearningQueue()
    }
    
    /**
     * Learn from question-answer pairs
     */
    fun learnFromQuestionAnswer(
        question: String,
        answer: String,
        filesRead: List<String>? = null,
        source: String = LearnedDataSource.NORMAL_FLOW
    ) {
        if (!shouldLearn()) return
        
        // Initialize with AutoAgent-specific model name (separate from other providers)
        initialize()
        
        AutoAgentLogger.debug("AutoAgentLearning", "Learning from question-answer", mapOf(
            "questionLength" to question.length,
            "answerLength" to answer.length,
            "filesRead" to (filesRead?.size ?: 0)
        ))
        
        learningQueue.offer(
            LearningTask.QuestionAnswer(
                question = question,
                answer = answer,
                filesRead = filesRead,
                source = source
            )
        )
        
        processLearningQueue()
    }
    
    /**
     * Learn from tool execution results
     */
    fun learnFromToolResult(
        toolName: String,
        result: String,
        success: Boolean,
        source: String = LearnedDataSource.NORMAL_FLOW
    ) {
        if (!shouldLearn() || !success) return
        
        // Initialize with AutoAgent-specific model name (separate from other providers)
        initialize()
        
        learningQueue.offer(
            LearningTask.ToolResult(
                toolName = toolName,
                result = result,
                source = source
            )
        )
        
        processLearningQueue()
    }
    
    /**
     * Process learning queue asynchronously
     */
    private fun processLearningQueue() {
        if (isProcessing) return
        
        learningScope.launch {
            isProcessing = true
            try {
                while (learningQueue.isNotEmpty()) {
                    val task = learningQueue.poll() ?: break
                    processLearningTask(task)
                }
            } finally {
                isProcessing = false
            }
        }
    }
    
    /**
     * Process a single learning task
     */
    private suspend fun processLearningTask(task: LearningTask) = withContext(Dispatchers.IO) {
        try {
            when (task) {
                is LearningTask.CodeGeneration -> {
                    processCodeGeneration(task)
                }
                is LearningTask.StreamingChunk -> {
                    processStreamingChunk(task)
                }
                is LearningTask.Fix -> {
                    processFix(task)
                }
                is LearningTask.ToolResult -> {
                    processToolResult(task)
                }
                is LearningTask.QuestionAnswer -> {
                    processQuestionAnswer(task)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoAgentLearning", "Error processing learning task", e)
            _learningEvents.emit(LearningEvent.Error(e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Process code generation task
     */
    private suspend fun processCodeGeneration(task: LearningTask.CodeGeneration) {
        AutoAgentLogger.info("AutoAgentLearning", "Processing code generation task", mapOf("codeLength" to task.code.length))
        
        val (classification, confidence) = classifier.classifyWithConfidence(
            task.code,
            task.context
        )
        
        AutoAgentLogger.debug("AutoAgentLearning", "Code classified", mapOf(
            "classification" to classification,
            "confidence" to confidence
        ))
        
        // Analyze user prompt if available
        val promptAnalysis = if (task.userPrompt != null) {
            PromptAnalyzer.analyzePrompt(task.userPrompt)
        } else {
            null
        }
        
        // Extract import patterns and event handlers from code
        val importPatterns = extractImportPatterns(task.code)
        val eventHandlerPatterns = extractEventHandlerPatterns(task.code)
        
        // For metadata transformation, learn based on user prompt relevance
        val metadataJson = if (classification == LearnedDataType.METADATA_TRANSFORMATION && task.userPrompt != null) {
            // Store metadata with user prompt for relevance learning
            val metadataMap = (task.metadata ?: emptyMap()).toMutableMap()
            metadataMap["user_prompt"] = task.userPrompt
            metadataMap["prompt_relevance"] = calculatePromptRelevance(task.code, task.userPrompt)
            if (promptAnalysis != null) {
                metadataMap["intent"] = promptAnalysis.intent.name
                metadataMap["file_types"] = promptAnalysis.fileTypes.joinToString(",")
            }
            metadataMap.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else {
            task.metadata?.let { map ->
                map.entries.joinToString(", ") { "${it.key}=${it.value}" }
            }
        }
        
        database.insertOrUpdateLearnedData(
            type = classification,
            content = task.code,
            source = task.source,
            metadata = metadataJson,
            incrementScore = true,
            userPrompt = task.userPrompt,
            promptPattern = promptAnalysis?.promptPattern,
            frameworkType = promptAnalysis?.frameworkType,
            importPatterns = importPatterns,
            eventHandlerPatterns = eventHandlerPatterns,
            codeTemplate = generateCodeTemplate(task.code, promptAnalysis)
        )
        
        _learningEvents.emit(
            LearningEvent.Learned(
                type = classification,
                content = task.code.take(100),
                confidence = confidence
            )
        )
    }
    
    /**
     * Calculate prompt relevance score for metadata learning
     */
    private fun calculatePromptRelevance(code: String, userPrompt: String): Float {
        val promptWords = userPrompt.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 2 }
        val codeWords = code.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 2 }
        
        val matchingWords = promptWords.intersect(codeWords.toSet())
        return if (promptWords.isNotEmpty()) {
            (matchingWords.size.toFloat() / promptWords.size).coerceIn(0f, 1f)
        } else {
            0.5f
        }
    }
    
    /**
     * Extract import patterns from code
     */
    private fun extractImportPatterns(code: String): String? {
        val imports = mutableListOf<String>()
        
        // Extract import statements
        val importRegex = Regex("""(?:import|require|from)\s+["']?([^"'\s]+)["']?""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val matches = importRegex.findAll(code)
        matches.forEach { match ->
            imports.add(match.value.trim())
        }
        
        return if (imports.isNotEmpty()) {
            imports.distinct().joinToString(", ")
        } else {
            null
        }
    }
    
    /**
     * Extract event handler patterns from code
     */
    private fun extractEventHandlerPatterns(code: String): String? {
        val handlers = mutableListOf<String>()
        
        // Extract event handlers
        val handlerRegex = Regex("""(?:on|handle)(?:click|change|submit|load|focus|blur|mouse|key|input|change)\w*""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val matches = handlerRegex.findAll(code)
        matches.forEach { match ->
            handlers.add(match.value.lowercase())
        }
        
        // Also check for addEventListener
        val listenerRegex = Regex("""addEventListener\s*\(\s*["']([^"']+)["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val listenerMatches = listenerRegex.findAll(code)
        listenerMatches.forEach { match ->
            handlers.add("addEventListener:${match.groupValues[1]}")
        }
        
        return if (handlers.isNotEmpty()) {
            handlers.distinct().joinToString(", ")
        } else {
            null
        }
    }
    
    /**
     * Generate code template from code and prompt analysis
     */
    private fun generateCodeTemplate(code: String, promptAnalysis: PromptAnalysis?): String? {
        if (promptAnalysis == null) return null
        
        // Create a template by replacing specific values with placeholders
        var template = code
        
        // Replace file names with {file}
        promptAnalysis.metadata["file_names"]?.let { fileNames ->
            if (fileNames is List<*>) {
                fileNames.forEach { fileName ->
                    template = template.replace(fileName.toString(), "{file}", ignoreCase = true)
                }
            }
        }
        
        // Replace function names with {function}
        promptAnalysis.metadata["function_names"]?.let { functionNames ->
            if (functionNames is List<*>) {
                functionNames.forEach { functionName ->
                    template = template.replace(functionName.toString(), "{function}", ignoreCase = true)
                }
            }
        }
        
        return if (template != code) {
            template
        } else {
            null
        }
    }
    
    /**
     * Process streaming chunk task
     */
    private suspend fun processStreamingChunk(task: LearningTask.StreamingChunk) {
        // Only process if accumulated content is substantial
        if (task.accumulatedContent.length < 50) return
        
        // Process accumulated content periodically
        if (task.accumulatedContent.length % 200 == 0 || task.chunk.endsWith("\n")) {
            val (classification, confidence) = classifier.classifyWithConfidence(
                task.accumulatedContent,
                null
            )
            
            database.insertOrUpdateLearnedData(
                type = classification,
                content = task.accumulatedContent,
                source = task.source,
                metadata = null,
                incrementScore = true
            )
        }
    }
    
    /**
     * Process fix task - store in JSON format with reason
     */
    private suspend fun processFix(task: LearningTask.Fix) {
        AutoAgentLogger.info("AutoAgentLearning", "Processing fix task", mapOf(
            "oldCodeLength" to task.oldCode.length,
            "newCodeLength" to task.newCode.length,
            "hasReason" to (task.reason != null)
        ))
        
        // Store fix in JSON format for better parsing
        val fixJson = buildString {
            append("{")
            append("\"old_code\":\"${task.oldCode.replace("\"", "\\\"").replace("\n", "\\n")}\",")
            append("\"new_code\":\"${task.newCode.replace("\"", "\\\"").replace("\n", "\\n")}\",")
            append("\"reason\":\"${(task.reason ?: "").replace("\"", "\\\"").replace("\n", "\\n")}\"")
            if (task.keywords != null && task.keywords.isNotEmpty()) {
                append(",\"keywords\":[${task.keywords.joinToString(",") { "\"$it\"" }}]")
            }
            append("}")
        }
        
        // Also create human-readable format for content
        val fixContent = buildString {
            append("OLD:\n${task.oldCode}\n\n")
            append("NEW:\n${task.newCode}\n")
            if (task.reason != null) {
                append("\nREASON: ${task.reason}")
            }
        }
        
        val (classification, confidence) = classifier.classifyWithConfidence(
            fixContent,
            task.reason
        )
        
        // Store JSON in metadata for structured retrieval
        database.insertOrUpdateLearnedData(
            type = LearnedDataType.FIX_PATCH,
            content = fixContent,
            source = task.source,
            metadata = fixJson,
            incrementScore = true,
            userPrompt = task.userPrompt
        )
        
        _learningEvents.emit(
            LearningEvent.Learned(
                type = classification,
                content = fixContent.take(100),
                confidence = confidence
            )
        )
    }
    
    /**
     * Process tool result task
     */
    private suspend fun processToolResult(task: LearningTask.ToolResult) {
        val (classification, confidence) = classifier.classifyWithConfidence(
            task.result,
            task.toolName
        )
        
        val metadata = mapOf(
            "tool_name" to task.toolName
        )
        
        database.insertOrUpdateLearnedData(
            type = classification,
            content = task.result,
            source = task.source,
            metadata = metadata.entries.joinToString(", ") { "${it.key}=${it.value}" },
            incrementScore = true
        )
    }
    
    /**
     * Process question-answer task - store Q&A pairs for AutoAgent to learn from
     */
    private suspend fun processQuestionAnswer(task: LearningTask.QuestionAnswer) {
        AutoAgentLogger.info("AutoAgentLearning", "Processing question-answer task", mapOf(
            "questionLength" to task.question.length,
            "answerLength" to task.answer.length,
            "filesRead" to (task.filesRead?.size ?: 0)
        ))
        
        // Store Q&A as metadata transformation with question as user prompt
        val qaContent = buildString {
            append("Q: ${task.question}\n\n")
            append("A: ${task.answer}")
        }
        
        val metadata = buildString {
            append("{")
            append("\"question\":\"${task.question.replace("\"", "\\\"").replace("\n", "\\n")}\",")
            append("\"answer\":\"${task.answer.replace("\"", "\\\"").replace("\n", "\\n")}\"")
            if (task.filesRead != null && task.filesRead.isNotEmpty()) {
                append(",\"files_read\":[${task.filesRead.joinToString(",") { "\"$it\"" }}]")
            }
            append("}")
        }
        
        database.insertOrUpdateLearnedData(
            type = LearnedDataType.METADATA_TRANSFORMATION,
            content = qaContent,
            source = task.source,
            metadata = metadata,
            incrementScore = true,
            userPrompt = task.question
        )
        
        _learningEvents.emit(
            LearningEvent.Learned(
                type = "question_answer",
                content = qaContent.take(100),
                confidence = 1.0f
            )
        )
    }
    
    /**
     * Record negative feedback
     */
    fun recordNegativeFeedback(entryId: Long) {
        if (!shouldLearn()) return
        
        learningScope.launch(Dispatchers.IO) {
            database.decrementScore(entryId)
            _learningEvents.emit(LearningEvent.NegativeFeedback(entryId))
        }
    }
    
    /**
     * Get learning statistics
     */
    suspend fun getLearningStats(): LearningStats = withContext(Dispatchers.IO) {
        val db = database
        val stats = LearningStats()
        
        val types = listOf(
            LearnedDataType.CODE_SNIPPET,
            LearnedDataType.API_USAGE,
            LearnedDataType.FIX_PATCH,
            LearnedDataType.METADATA_TRANSFORMATION
        )
        
        types.forEach { type ->
            val entries = db.getLearnedDataByType(type, 1)
            if (entries.isNotEmpty()) {
                stats.typeCounts[type] = entries.size
                stats.totalScore += entries.sumOf { it.positiveScore.toLong() }
            }
        }
        
        stats
    }
}

/**
 * Learning task types
 */
sealed class LearningTask {
    data class CodeGeneration(
        val code: String,
        val context: String?,
        val source: String,
        val metadata: Map<String, Any>?,
        val userPrompt: String? = null
    ) : LearningTask()
    
    data class StreamingChunk(
        val chunk: String,
        val accumulatedContent: String,
        val source: String
    ) : LearningTask()
    
    data class Fix(
        val oldCode: String,
        val newCode: String,
        val reason: String?,
        val source: String,
        val userPrompt: String? = null,
        val keywords: List<String>? = null
    ) : LearningTask()
    
    data class ToolResult(
        val toolName: String,
        val result: String,
        val source: String
    ) : LearningTask()
    
    data class QuestionAnswer(
        val question: String,
        val answer: String,
        val filesRead: List<String>? = null,
        val source: String
    ) : LearningTask()
}

/**
 * Learning events
 */
sealed class LearningEvent {
    data class Learned(
        val type: String,
        val content: String,
        val confidence: Float
    ) : LearningEvent()
    
    data class NegativeFeedback(val entryId: Long) : LearningEvent()
    data class Error(val message: String) : LearningEvent()
}

/**
 * Learning statistics
 */
data class LearningStats(
    val typeCounts: MutableMap<String, Int> = mutableMapOf(),
    var totalScore: Long = 0
)

