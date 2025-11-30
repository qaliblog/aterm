package com.qali.aterm.agent.utils

/**
 * Get error message from exception
 */
fun getErrorMessage(error: Throwable?): String {
    return error?.message ?: error?.toString() ?: "Unknown error"
}

/**
 * Check if error is a rate limit error
 */
fun isRateLimitError(error: Throwable?): Boolean {
    val message = getErrorMessage(error).lowercase()
    return message.contains("rate limit") ||
            message.contains("429") ||
            message.contains("quota") ||
            message.contains("rpm") ||
            message.contains("rpd")
}

/**
 * Check if error is a network error
 */
fun isNetworkError(error: Throwable?): Boolean {
    val message = getErrorMessage(error).lowercase()
    return message.contains("network") ||
            message.contains("timeout") ||
            message.contains("connection") ||
            message.contains("unreachable")
}

/**
 * Check if error is an authentication error
 */
fun isAuthError(error: Throwable?): Boolean {
    val message = getErrorMessage(error).lowercase()
    return message.contains("unauthorized") ||
            message.contains("401") ||
            message.contains("authentication") ||
            message.contains("api key")
}
