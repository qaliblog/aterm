package com.qali.aterm.agent.ppe

/**
 * Configuration for PPE execution engine
 * Centralizes all configurable values
 */
object PpeConfig {
    // ==================== Timeouts ====================
    
    /** Ollama connection timeout in seconds (for slow connections) */
    const val OLLAMA_CONNECT_TIMEOUT_SECONDS = 120L // 2 minutes
    
    /** Ollama read timeout in seconds (for slow/large models) */
    const val OLLAMA_READ_TIMEOUT_SECONDS = 600L // 10 minutes
    
    /** Ollama write timeout in seconds */
    const val OLLAMA_WRITE_TIMEOUT_SECONDS = 120L // 2 minutes
    
    /** Default connection timeout in seconds */
    const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 30L
    
    /** Default read timeout in seconds */
    const val DEFAULT_READ_TIMEOUT_SECONDS = 60L
    
    /** Default write timeout in seconds */
    const val DEFAULT_WRITE_TIMEOUT_SECONDS = 60L
    
    // ==================== File Limits ====================
    
    /** Maximum lines to read from a file without offset/limit */
    const val MAX_FILE_LINES = 500
    
    /** Maximum lines for context in prompts */
    const val MAX_CONTEXT_LINES = 1000
    
    /** Maximum file size to process (in bytes) */
    const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB
    
    // ==================== Rate Limiting ====================
    
    /** Maximum API requests per window */
    const val RATE_LIMIT_MAX_REQUESTS = 10
    
    /** Rate limit window in milliseconds */
    const val RATE_LIMIT_WINDOW_MS = 1000L // 1 second
    
    // ==================== Retry Configuration ====================
    
    /** Maximum retry attempts */
    const val MAX_RETRIES = 20
    
    /** Initial retry delay in milliseconds */
    const val RETRY_INITIAL_DELAY_MS = 1000L
    
    /** Maximum retry delay in milliseconds */
    const val RETRY_MAX_DELAY_MS = 10000L // 10 seconds
    
    // ==================== Context Window ====================
    
    /** Maximum chat history messages to keep */
    const val MAX_CHAT_HISTORY_MESSAGES = 50
    
    /** Maximum tokens per provider (approximate) */
    val MAX_TOKENS_BY_PROVIDER = mapOf(
        "gpt-4" to 8192,
        "gpt-4-turbo" to 128000,
        "gpt-3.5-turbo" to 16385,
        "gemini-pro" to 32768,
        "gemini-2.0" to 1000000,
        "claude-3-opus" to 200000,
        "claude-3-sonnet" to 200000,
        "claude-3-haiku" to 200000,
        "ollama" to 32768 // Default for Ollama models
    )
    
    /** Average characters per token (for estimation) */
    const val CHARS_PER_TOKEN = 4.0
    
    // ==================== Caching ====================
    
    /** Enable caching */
    const val CACHE_ENABLED = true
    
    /** Cache TTL in seconds */
    const val CACHE_TTL_SECONDS = 300L // 5 minutes
    
    // ==================== Validation ====================
    
    /** Validate code before writing */
    const val VALIDATE_BEFORE_WRITE = true
    
    /** Check syntax before writing */
    const val CHECK_SYNTAX = true
    
    /** Check imports/exports before writing */
    const val CHECK_IMPORTS = true
    
    // ==================== Blueprint ====================
    
    /** Maximum files in blueprint */
    const val MAX_BLUEPRINT_FILES = 100
    
    /** Maximum dependencies per file */
    const val MAX_FILE_DEPENDENCIES = 20
    
    // ==================== Progress ====================
    
    /** Enable progress persistence */
    const val ENABLE_PROGRESS_PERSISTENCE = false // TODO: Implement
    
    /** Checkpoint interval (operations) */
    const val CHECKPOINT_INTERVAL = 10
    
    // ==================== Helper Functions ====================
    
    /**
     * Estimate token count from text (rough approximation)
     */
    fun estimateTokens(text: String): Int {
        return (text.length / CHARS_PER_TOKEN).toInt()
    }
    
    /**
     * Get max tokens for a model
     */
    fun getMaxTokensForModel(model: String): Int {
        return MAX_TOKENS_BY_PROVIDER.entries.firstOrNull { 
            model.contains(it.key, ignoreCase = true) 
        }?.value ?: MAX_TOKENS_BY_PROVIDER["ollama"] ?: 32768
    }
    
    /**
     * Check if text exceeds token limit for model
     */
    fun exceedsTokenLimit(text: String, model: String): Boolean {
        val estimatedTokens = estimateTokens(text)
        val maxTokens = getMaxTokensForModel(model)
        return estimatedTokens > maxTokens
    }
}
