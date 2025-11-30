package com.qali.aterm.ui.screens.agent.models

/**
 * Data models for Agent UI
 */

data class FileDiff(
    val filePath: String,
    val oldContent: String,
    val newContent: String,
    val isNewFile: Boolean = false
)

enum class DiffLineType {
    ADDED,      // Green with +
    REMOVED,    // Red with -
    UNCHANGED   // Normal text
}

data class DiffLine(
    val content: String,
    val type: DiffLineType,
    val lineNumber: Int
)

data class AgentMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val fileDiff: FileDiff? = null // Optional file diff for code changes
)

fun formatTimestamp(timestamp: Long): String {
    val time = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(time)
}
