package com.qali.aterm.api

/**
 * Configuration for LLM provider parameters
 * All fields have safe defaults to prevent null crashes
 * This class is designed to NEVER be null - all fields have defaults
 */
data class ProviderConfig(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val topP: Float = 1.0f,
    val userOverridden: Boolean = false // Track if user manually set these values
) {
    init {
        // Ensure all fields are valid (defensive programming)
        require(temperature >= 0.1f && temperature <= 1.2f) { "Temperature must be between 0.1 and 1.2" }
        require(maxTokens > 0) { "MaxTokens must be positive" }
        require(topP >= 0.0f && topP <= 1.0f) { "TopP must be between 0.0 and 1.0" }
    }
    companion object {
        /**
         * Get model-specific default configuration
         */
        fun getModelDefaults(model: String): ProviderConfig {
            return when {
                model.contains("gemini-2.5-flash-lite", ignoreCase = true) -> ProviderConfig(
                    temperature = 0.6f,
                    maxTokens = 2048,
                    topP = 1.0f,
                    userOverridden = false
                )
                model.contains("gemini-2.5-pro", ignoreCase = true) -> ProviderConfig(
                    temperature = 0.8f,
                    maxTokens = 4096,
                    topP = 1.0f,
                    userOverridden = false
                )
                model.contains("gemini-2.0-flash", ignoreCase = true) -> ProviderConfig(
                    temperature = 0.7f,
                    maxTokens = 2048,
                    topP = 1.0f,
                    userOverridden = false
                )
                model.contains("llama3.1", ignoreCase = true) -> ProviderConfig(
                    temperature = 0.7f,
                    maxTokens = 2048,
                    topP = 1.0f,
                    userOverridden = false
                )
                model.contains("gpt-4o-mini", ignoreCase = true) -> ProviderConfig(
                    temperature = 0.4f,
                    maxTokens = 2048,
                    topP = 1.0f,
                    userOverridden = false
                )
                else -> ProviderConfig(
                    temperature = 0.7f,
                    maxTokens = 2048,
                    topP = 1.0f,
                    userOverridden = false
                ) // Use explicit default values
            }
        }
        
        /**
         * Adjust parameters based on prompt length and type (creative vs deterministic)
         * Returns adjusted config if user hasn't overridden, otherwise returns original
         */
        fun adjustForPromptLength(
            config: ProviderConfig?,
            promptLength: Int,
            isCreative: Boolean = false
        ): ProviderConfig {
            // If config is null, create default config
            val safeConfig = config ?: ProviderConfig()
            
            // If user manually overrode, don't auto-adjust
            if (safeConfig.userOverridden) {
                return safeConfig
            }
            
            // Determine temperature based on prompt type and length
            val baseTemp = when {
                isCreative -> 0.8f // Higher for creative tasks
                promptLength < 200 -> 0.2f // Lower for short, focused prompts
                promptLength <= 1000 -> 0.5f // Medium for normal prompts
                else -> 0.7f // Higher for long, complex prompts
            }
            
            // Clamp temperature between 0.2 and 1.0
            val temp = baseTemp.coerceIn(0.2f, 1.0f)
            
            // Determine max tokens based on prompt length
            val tokens = when {
                promptLength < 200 -> 1024
                promptLength <= 1000 -> 2048
                else -> 4096
            }
            
            return safeConfig.copy(
                temperature = temp,
                maxTokens = tokens
            )
        }
        
        /**
         * Clamp temperature to valid range [0.1, 1.2]
         */
        fun clampTemperature(temp: Float): Float {
            return temp.coerceIn(0.1f, 1.2f)
        }
        
        /**
         * Clamp maxTokens based on model capabilities
         */
        fun clampMaxTokens(model: String, tokens: Int): Int {
            val maxCapability = when {
                model.contains("gemini-2.5-pro", ignoreCase = true) -> 8192
                model.contains("gemini-2.5-flash", ignoreCase = true) -> 8192
                model.contains("gpt-4", ignoreCase = true) -> 16384
                model.contains("claude", ignoreCase = true) -> 4096
                else -> 4096 // Safe default
            }
            return tokens.coerceIn(1, maxCapability)
        }
    }
}

