package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.Content
import android.util.Log

/**
 * Manages context window to prevent exceeding token limits
 */
object ContextWindowManager {
    
    /**
     * Prune chat history to fit within token limits
     * Keeps most recent messages and important messages (system, tool results)
     */
    fun pruneChatHistory(
        messages: List<Content>,
        model: String,
        maxTokens: Int? = null
    ): List<Content> {
        val limit = maxTokens ?: PpeConfig.getMaxTokensForModel(model)
        val estimatedTokens = estimateTokens(messages)
        
        if (estimatedTokens <= limit) {
            return messages
        }
        
        Log.d("ContextWindowManager", "Pruning chat history: $estimatedTokens tokens > $limit limit")
        
        // Always keep first message (usually system message)
        val pruned = mutableListOf<Content>()
        if (messages.isNotEmpty()) {
            pruned.add(messages.first())
        }
        
        // Keep most recent messages, prioritizing important ones
        val recentMessages = messages.drop(1).takeLast(PpeConfig.MAX_CHAT_HISTORY_MESSAGES)
        
        // Add recent messages until we approach limit
        var currentTokens = estimateTokens(pruned)
        for (message in recentMessages.reversed()) {
            val messageTokens = estimateTokens(listOf(message))
            if (currentTokens + messageTokens > limit * 0.9) { // Leave 10% buffer
                break
            }
            pruned.add(1, message) // Insert after first message
            currentTokens += messageTokens
        }
        
        Log.d("ContextWindowManager", "Pruned to ${pruned.size} messages, ~$currentTokens tokens")
        return pruned
    }
    
    /**
     * Estimate token count for messages (rough approximation)
     */
    fun estimateTokens(messages: List<Content>): Int {
        val totalChars = messages.sumOf { message ->
            message.parts.sumOf { part ->
                when (part) {
                    is com.qali.aterm.agent.core.Part.TextPart -> part.text.length
                    is com.qali.aterm.agent.core.Part.FunctionCallPart -> part.functionCall.toString().length
                    is com.qali.aterm.agent.core.Part.FunctionResponsePart -> part.functionResponse.toString().length
                    else -> 0
                }
            }
        }
        return PpeConfig.estimateTokens(totalChars.toString())
    }
    
    /**
     * Check if messages fit within token limit
     */
    fun fitsWithinLimit(messages: List<Content>, model: String): Boolean {
        val estimatedTokens = estimateTokens(messages)
        val maxTokens = PpeConfig.getMaxTokensForModel(model)
        return estimatedTokens <= maxTokens
    }
    
    /**
     * Get summary of pruned messages for context
     */
    fun createSummary(prunedMessages: List<Content>): String {
        if (prunedMessages.isEmpty()) return ""
        
        return buildString {
            appendLine("=== Previous Conversation Summary ===")
            appendLine("(${prunedMessages.size} messages were pruned to fit context window)")
            appendLine()
            
            // Extract key information from pruned messages
            prunedMessages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        message.parts.filterIsInstance<com.qali.aterm.agent.core.Part.TextPart>()
                            .take(1)
                            .forEach { part ->
                                val preview = part.text.take(100)
                                if (preview.isNotEmpty()) {
                                    appendLine("User: $preview...")
                                }
                            }
                    }
                    "model" -> {
                        message.parts.filterIsInstance<com.qali.aterm.agent.core.Part.TextPart>()
                            .take(1)
                            .forEach { part ->
                                val preview = part.text.take(100)
                                if (preview.isNotEmpty()) {
                                    appendLine("Assistant: $preview...")
                                }
                            }
                    }
                }
            }
        }
    }
}
