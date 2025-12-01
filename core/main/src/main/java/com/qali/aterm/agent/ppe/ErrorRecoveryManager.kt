package com.qali.aterm.agent.ppe

import android.util.Log
import com.qali.aterm.agent.debug.DebugLogger
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Error recovery manager for API calls
 * Provides error classification, context-aware retry strategies, and recovery suggestions
 */
object ErrorRecoveryManager {
    
    enum class ErrorCategory {
        RATE_LIMIT,          // Rate limit exceeded
        AUTHENTICATION,      // Authentication/authorization error
        NETWORK,             // Network connectivity issues
        TIMEOUT,             // Request timeout
        SERVER_ERROR,        // Server-side error (5xx)
        CLIENT_ERROR,        // Client-side error (4xx)
        INVALID_REQUEST,     // Invalid request format
        QUOTA_EXCEEDED,      // Quota exceeded
        MODEL_UNAVAILABLE,   // Model not available
        TOOL_ERROR,          // Tool-related error
        UNKNOWN              // Unknown error
    }
    
    data class ErrorClassification(
        val category: ErrorCategory,
        val severity: String, // "low", "medium", "high", "critical"
        val isRetryable: Boolean,
        val retryDelay: Long, // milliseconds
        val maxRetries: Int,
        val recoverySuggestion: String?,
        val context: Map<String, Any> = emptyMap()
    )
    
    data class RetryStrategy(
        val maxRetries: Int,
        val baseDelay: Long, // milliseconds
        val maxDelay: Long,
        val backoffMultiplier: Double,
        val retryableCategories: Set<ErrorCategory>
    )
    
    /**
     * Classify an error and determine recovery strategy
     */
    fun classifyError(
        error: Throwable,
        context: Map<String, Any> = emptyMap()
    ): ErrorClassification {
        val errorMessage = error.message?.lowercase() ?: ""
        val errorType = error.javaClass.simpleName
        
        return when {
            // Rate limit errors
            errorMessage.contains("rate limit") ||
            errorMessage.contains("rate_limit") ||
            errorMessage.contains("429") ||
            errorMessage.contains("too many requests") -> {
                ErrorClassification(
                    category = ErrorCategory.RATE_LIMIT,
                    severity = "medium",
                    isRetryable = true,
                    retryDelay = calculateRateLimitDelay(errorMessage, context),
                    maxRetries = 3,
                    recoverySuggestion = "Rate limit exceeded. Wait before retrying. Consider reducing request frequency or upgrading API tier.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Authentication errors
            errorMessage.contains("401") ||
            errorMessage.contains("unauthorized") ||
            errorMessage.contains("authentication") ||
            errorMessage.contains("invalid api key") ||
            errorMessage.contains("api key") && errorMessage.contains("invalid") -> {
                ErrorClassification(
                    category = ErrorCategory.AUTHENTICATION,
                    severity = "critical",
                    isRetryable = false,
                    retryDelay = 0,
                    maxRetries = 0,
                    recoverySuggestion = "Authentication failed. Check API key configuration and ensure it's valid and has proper permissions.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Network errors
            error is UnknownHostException ||
            errorMessage.contains("network") ||
            errorMessage.contains("connection") ||
            errorMessage.contains("dns") ||
            errorMessage.contains("unreachable") -> {
                ErrorClassification(
                    category = ErrorCategory.NETWORK,
                    severity = "high",
                    isRetryable = true,
                    retryDelay = 2000, // 2 seconds
                    maxRetries = 3,
                    recoverySuggestion = "Network connectivity issue. Check internet connection and try again. Verify API endpoint is accessible.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Timeout errors
            error is SocketTimeoutException ||
            errorMessage.contains("timeout") ||
            errorMessage.contains("timed out") -> {
                ErrorClassification(
                    category = ErrorCategory.TIMEOUT,
                    severity = "medium",
                    isRetryable = true,
                    retryDelay = 1000, // 1 second
                    maxRetries = 2,
                    recoverySuggestion = "Request timeout. The API may be slow or overloaded. Consider increasing timeout or reducing request size.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Server errors (5xx)
            errorMessage.contains("500") ||
            errorMessage.contains("502") ||
            errorMessage.contains("503") ||
            errorMessage.contains("504") ||
            errorMessage.contains("internal server error") ||
            errorMessage.contains("bad gateway") ||
            errorMessage.contains("service unavailable") -> {
                ErrorClassification(
                    category = ErrorCategory.SERVER_ERROR,
                    severity = "high",
                    isRetryable = true,
                    retryDelay = 5000, // 5 seconds
                    maxRetries = 3,
                    recoverySuggestion = "Server error. The API service may be temporarily unavailable. Wait and retry.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Client errors (4xx)
            errorMessage.contains("400") ||
            errorMessage.contains("403") ||
            errorMessage.contains("404") ||
            errorMessage.contains("bad request") ||
            errorMessage.contains("forbidden") ||
            errorMessage.contains("not found") -> {
                val isRetryable = when {
                    errorMessage.contains("400") -> false // Bad request - don't retry
                    errorMessage.contains("403") -> false // Forbidden - don't retry
                    errorMessage.contains("404") -> false // Not found - don't retry
                    else -> false
                }
                
                ErrorClassification(
                    category = ErrorCategory.CLIENT_ERROR,
                    severity = if (errorMessage.contains("403")) "critical" else "high",
                    isRetryable = isRetryable,
                    retryDelay = 0,
                    maxRetries = 0,
                    recoverySuggestion = when {
                        errorMessage.contains("400") -> "Invalid request. Check request format and parameters."
                        errorMessage.contains("403") -> "Access forbidden. Check API key permissions and resource access rights."
                        errorMessage.contains("404") -> "Resource not found. Verify endpoint URL and resource identifiers."
                        else -> "Client error. Review request parameters and API documentation."
                    },
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Quota exceeded
            errorMessage.contains("quota") ||
            errorMessage.contains("billing") ||
            errorMessage.contains("payment") ||
            errorMessage.contains("insufficient") -> {
                ErrorClassification(
                    category = ErrorCategory.QUOTA_EXCEEDED,
                    severity = "critical",
                    isRetryable = false,
                    retryDelay = 0,
                    maxRetries = 0,
                    recoverySuggestion = "Quota or billing limit exceeded. Check API usage limits and billing status. Consider upgrading plan.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Model unavailable
            errorMessage.contains("model") && (
                errorMessage.contains("not available") ||
                errorMessage.contains("unavailable") ||
                errorMessage.contains("not found")
            ) -> {
                ErrorClassification(
                    category = ErrorCategory.MODEL_UNAVAILABLE,
                    severity = "high",
                    isRetryable = true,
                    retryDelay = 2000,
                    maxRetries = 2,
                    recoverySuggestion = "Model not available. Try a different model or wait for the model to become available.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Tool errors
            errorMessage.contains("tool") ||
            errorMessage.contains("function") && errorMessage.contains("not support") -> {
                ErrorClassification(
                    category = ErrorCategory.TOOL_ERROR,
                    severity = "medium",
                    isRetryable = false,
                    retryDelay = 0,
                    maxRetries = 0,
                    recoverySuggestion = "Tool/function not supported by model. Retry without tools or use a different model.",
                    context = context + mapOf("error_type" to errorType)
                )
            }
            
            // Unknown errors
            else -> {
                ErrorClassification(
                    category = ErrorCategory.UNKNOWN,
                    severity = "medium",
                    isRetryable = true,
                    retryDelay = 1000,
                    maxRetries = 1,
                    recoverySuggestion = "Unknown error occurred. Check error message and API documentation. Consider retrying with different parameters.",
                    context = context + mapOf("error_type" to errorType, "error_message" to errorMessage)
                )
            }
        }
    }
    
    /**
     * Get retry strategy based on error classification
     */
    fun getRetryStrategy(classification: ErrorClassification): RetryStrategy {
        return when (classification.category) {
            ErrorCategory.RATE_LIMIT -> RetryStrategy(
                maxRetries = classification.maxRetries,
                baseDelay = classification.retryDelay,
                maxDelay = 60000, // 1 minute max
                backoffMultiplier = 2.0,
                retryableCategories = setOf(ErrorCategory.RATE_LIMIT)
            )
            ErrorCategory.NETWORK -> RetryStrategy(
                maxRetries = classification.maxRetries,
                baseDelay = classification.retryDelay,
                maxDelay = 10000, // 10 seconds max
                backoffMultiplier = 1.5,
                retryableCategories = setOf(ErrorCategory.NETWORK)
            )
            ErrorCategory.TIMEOUT -> RetryStrategy(
                maxRetries = classification.maxRetries,
                baseDelay = classification.retryDelay,
                maxDelay = 5000,
                backoffMultiplier = 1.5,
                retryableCategories = setOf(ErrorCategory.TIMEOUT)
            )
            ErrorCategory.SERVER_ERROR -> RetryStrategy(
                maxRetries = classification.maxRetries,
                baseDelay = classification.retryDelay,
                maxDelay = 30000, // 30 seconds max
                backoffMultiplier = 2.0,
                retryableCategories = setOf(ErrorCategory.SERVER_ERROR)
            )
            ErrorCategory.MODEL_UNAVAILABLE -> RetryStrategy(
                maxRetries = classification.maxRetries,
                baseDelay = classification.retryDelay,
                maxDelay = 10000,
                backoffMultiplier = 1.5,
                retryableCategories = setOf(ErrorCategory.MODEL_UNAVAILABLE)
            )
            else -> RetryStrategy(
                maxRetries = 0,
                baseDelay = 0,
                maxDelay = 0,
                backoffMultiplier = 1.0,
                retryableCategories = emptySet()
            )
        }
    }
    
    /**
     * Calculate delay for rate limit errors
     */
    private fun calculateRateLimitDelay(errorMessage: String, context: Map<String, Any>): Long {
        // Try to extract retry-after from error message or context
        val retryAfter = context["retry_after"] as? Number
        if (retryAfter != null) {
            return retryAfter.toLong() * 1000 // Convert to milliseconds
        }
        
        // Look for retry-after in error message
        val retryAfterMatch = Regex("retry[_-]?after[\\s:=]+(\\d+)", RegexOption.IGNORE_CASE).find(errorMessage)
        if (retryAfterMatch != null) {
            return retryAfterMatch.groupValues[1].toLongOrNull()?.times(1000) ?: 60000
        }
        
        // Default rate limit delay
        return 60000 // 1 minute
    }
    
    /**
     * Generate error report with context
     */
    fun generateErrorReport(
        error: Throwable,
        classification: ErrorClassification,
        context: Map<String, Any> = emptyMap()
    ): String {
        return buildString {
            appendLine("## Error Report")
            appendLine()
            appendLine("**Category:** ${classification.category}")
            appendLine("**Severity:** ${classification.severity}")
            appendLine("**Retryable:** ${classification.isRetryable}")
            appendLine()
            appendLine("### Error Details")
            appendLine("- **Type:** ${error.javaClass.simpleName}")
            appendLine("- **Message:** ${error.message ?: "No message"}")
            appendLine()
            
            if (classification.isRetryable) {
                appendLine("### Retry Strategy")
                appendLine("- **Max Retries:** ${classification.maxRetries}")
                appendLine("- **Retry Delay:** ${classification.retryDelay}ms")
                appendLine()
            }
            
            if (classification.recoverySuggestion != null) {
                appendLine("### Recovery Suggestion")
                appendLine(classification.recoverySuggestion)
                appendLine()
            }
            
            if (context.isNotEmpty()) {
                appendLine("### Context")
                context.forEach { (key, value) ->
                    appendLine("- **$key:** $value")
                }
            }
        }
    }
    
    /**
     * Check if error should trigger provider switch
     */
    fun shouldSwitchProvider(classification: ErrorClassification): Boolean {
        return when (classification.category) {
            ErrorCategory.AUTHENTICATION,
            ErrorCategory.QUOTA_EXCEEDED,
            ErrorCategory.MODEL_UNAVAILABLE -> true
            else -> false
        }
    }
    
    /**
     * Check if error should trigger model fallback
     */
    fun shouldFallbackModel(classification: ErrorClassification): Boolean {
        return classification.category == ErrorCategory.MODEL_UNAVAILABLE
    }
}
