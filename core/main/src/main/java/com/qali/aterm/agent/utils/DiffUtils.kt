package com.qali.aterm.agent.utils

import com.qali.aterm.agent.tools.DiffStat

/**
 * Default diff options
 */
data class DiffOptions(
    val context: Int = 3,
    val ignoreWhitespace: Boolean = true
)

val DEFAULT_DIFF_OPTIONS = DiffOptions()

/**
 * Calculate diff statistics between old, AI-proposed, and user-modified content
 */
fun getDiffStat(
    fileName: String,
    oldStr: String,
    aiStr: String,
    userStr: String
): DiffStat {
    val modelStats = calculateDiffStats(oldStr, aiStr)
    val userStats = calculateDiffStats(aiStr, userStr)
    
    return DiffStat(
        model_added_lines = modelStats.addedLines,
        model_removed_lines = modelStats.removedLines,
        model_added_chars = modelStats.addedChars,
        model_removed_chars = modelStats.removedChars,
        user_added_lines = userStats.addedLines,
        user_removed_lines = userStats.removedLines,
        user_added_chars = userStats.addedChars,
        user_removed_chars = userStats.removedChars
    )
}

private data class Stats(
    val addedLines: Int,
    val removedLines: Int,
    val addedChars: Int,
    val removedChars: Int
)

private fun calculateDiffStats(oldStr: String, newStr: String): Stats {
    val oldLines = oldStr.lines()
    val newLines = newStr.lines()
    
    var addedLines = 0
    var removedLines = 0
    var addedChars = 0
    var removedChars = 0
    
    // Simple line-by-line diff calculation
    val maxLines = maxOf(oldLines.size, newLines.size)
    var oldIndex = 0
    var newIndex = 0
    
    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        when {
            oldIndex >= oldLines.size -> {
                // Only new lines remain
                addedLines++
                addedChars += newLines[newIndex].length
                newIndex++
            }
            newIndex >= newLines.size -> {
                // Only old lines remain
                removedLines++
                removedChars += oldLines[oldIndex].length
                oldIndex++
            }
            oldLines[oldIndex] == newLines[newIndex] -> {
                // Lines match, move both forward
                oldIndex++
                newIndex++
            }
            else -> {
                // Lines differ - simplified: treat as remove + add
                removedLines++
                removedChars += oldLines[oldIndex].length
                addedLines++
                addedChars += newLines[newIndex].length
                oldIndex++
                newIndex++
            }
        }
    }
    
    return Stats(addedLines, removedLines, addedChars, removedChars)
}

/**
 * Create a unified diff patch string
 */
fun createPatch(
    fileName: String,
    oldStr: String,
    newStr: String,
    oldHeader: String = "Current",
    newHeader: String = "Proposed",
    options: DiffOptions = DEFAULT_DIFF_OPTIONS
): String {
    val oldLines = oldStr.lines()
    val newLines = newStr.lines()
    
    val patch = StringBuilder()
    patch.append("--- $oldHeader/$fileName\n")
    patch.append("+++ $newHeader/$fileName\n")
    
    // Simplified patch generation
    var oldIndex = 0
    var newIndex = 0
    
    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        when {
            oldIndex >= oldLines.size -> {
                patch.append("+${newLines[newIndex]}\n")
                newIndex++
            }
            newIndex >= newLines.size -> {
                patch.append("-${oldLines[oldIndex]}\n")
                oldIndex++
            }
            oldLines[oldIndex] == newLines[newIndex] -> {
                patch.append(" ${oldLines[oldIndex]}\n")
                oldIndex++
                newIndex++
            }
            else -> {
                patch.append("-${oldLines[oldIndex]}\n")
                patch.append("+${newLines[newIndex]}\n")
                oldIndex++
                newIndex++
            }
        }
    }
    
    return patch.toString()
}
