package com.qali.aterm.agent.client.error

/**
 * Error type classification
 */
enum class ErrorType {
    COMMAND_NOT_FOUND,      // Command/tool not installed
    CODE_ERROR,             // Syntax/runtime error in code
    DEPENDENCY_MISSING,     // Missing dependencies
    PERMISSION_ERROR,       // Permission/access issues
    CONFIGURATION_ERROR,    // Configuration/wrong setup
    NETWORK_ERROR,          // Network/connection issues
    UNKNOWN                 // Unknown error type
}

/**
 * Data class for failure analysis
 */
data class FailureAnalysis(
    val reason: String,
    val fallbackPlans: List<FallbackPlan>
)

/**
 * Data class for fallback plan
 */
data class FallbackPlan(
    val command: String,
    val description: String,
    val shouldRetryOriginal: Boolean = false
)
