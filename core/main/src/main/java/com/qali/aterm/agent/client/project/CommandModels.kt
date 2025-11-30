package com.qali.aterm.agent.client.project

/**
 * Data class for commands with fallbacks
 */
data class CommandWithFallbacks(
    val primaryCommand: String,
    val description: String,
    val fallbacks: List<String>,
    val checkCommand: String? = null, // Command to check if tool is installed
    val installCheck: String? = null // Command to check if installer is available
)
