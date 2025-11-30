package com.qali.aterm.ui.screens.agent.models

import com.qali.aterm.agent.tools.ToolResult
import java.io.File

/**
 * Utility functions for parsing and calculating file diffs
 */

/**
 * Parse file diff from tool result
 * Extracts file path and content from edit and write_file tool results
 */
fun parseFileDiffFromToolResult(toolName: String, toolResult: ToolResult, toolArgs: Map<String, Any>? = null): FileDiff? {
    if (toolName != "edit" && toolName != "write_file") {
        return null
    }
    
    return try {
        // Extract file path from tool args
        val filePath = toolArgs?.get("file_path") as? String
            ?: run {
                // Try to extract from llmContent or returnDisplay
                val content = toolResult.llmContent
                val filePathPattern = Regex("""(?:file|File|path|Path)[:\s]+([^\s,]+)""")
                filePathPattern.find(content)?.groupValues?.get(1)
            } ?: return null
        
        // For edit, extract old_string and new_string from args
        if (toolName == "edit" && toolArgs != null) {
            val oldString = toolArgs["old_string"] as? String ?: ""
            val newString = toolArgs["new_string"] as? String ?: ""
            val isNewFile = oldString.isEmpty()
            
            FileDiff(
                filePath = filePath,
                oldContent = oldString,
                newContent = newString,
                isNewFile = isNewFile
            )
        } 
        // For write_file, we only have new content (creates new file or overwrites)
        else if (toolName == "write_file" && toolArgs != null) {
            val newContent = toolArgs["content"] as? String ?: ""
            // Check if file exists to determine if it's new
            val workspaceRoot = com.rk.libcommons.alpineDir()
            val file = File(workspaceRoot, filePath)
            val isNewFile = !file.exists()
            val oldContent = if (isNewFile) {
                ""
            } else {
                try {
                    file.takeIf { it.exists() }?.readText() ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
            
            FileDiff(
                filePath = filePath,
                oldContent = oldContent,
                newContent = newContent,
                isNewFile = isNewFile
            )
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("DiffUtils", "Failed to parse file diff", e)
        null
    }
}

/**
 * Calculate line-by-line diff between old and new content
 * Uses a simple line-by-line comparison algorithm
 */
fun calculateLineDiff(oldContent: String, newContent: String): List<DiffLine> {
    val oldLines = if (oldContent.isEmpty()) emptyList() else oldContent.lines()
    val newLines = if (newContent.isEmpty()) emptyList() else newContent.lines()
    val diffLines = mutableListOf<DiffLine>()
    
    // If both are empty, return empty
    if (oldLines.isEmpty() && newLines.isEmpty()) {
        return emptyList()
    }
    
    // If old is empty, all new lines are additions
    if (oldLines.isEmpty()) {
        newLines.forEachIndexed { index, line ->
            diffLines.add(DiffLine(line, DiffLineType.ADDED, index + 1))
        }
        return diffLines
    }
    
    // If new is empty, all old lines are removals
    if (newLines.isEmpty()) {
        oldLines.forEachIndexed { index, line ->
            diffLines.add(DiffLine(line, DiffLineType.REMOVED, index + 1))
        }
        return diffLines
    }
    
    // Use a simple longest common subsequence approach
    var oldIndex = 0
    var newIndex = 0
    var newLineNumber = 1
    
    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        when {
            oldIndex >= oldLines.size -> {
                // Only new lines remain - all additions
                diffLines.add(DiffLine(newLines[newIndex], DiffLineType.ADDED, newLineNumber))
                newIndex++
                newLineNumber++
            }
            newIndex >= newLines.size -> {
                // Only old lines remain - all removals
                diffLines.add(DiffLine(oldLines[oldIndex], DiffLineType.REMOVED, oldIndex + 1))
                oldIndex++
            }
            oldLines[oldIndex] == newLines[newIndex] -> {
                // Lines match - unchanged
                diffLines.add(DiffLine(oldLines[oldIndex], DiffLineType.UNCHANGED, oldIndex + 1))
                oldIndex++
                newIndex++
                newLineNumber++
            }
            else -> {
                // Lines differ - check if we can find a match ahead
                var foundMatch = false
                var lookAhead = 1
                while (oldIndex + lookAhead < oldLines.size && !foundMatch) {
                    if (oldLines[oldIndex + lookAhead] == newLines[newIndex]) {
                        // Found match ahead - mark intermediate old lines as removed
                        for (i in oldIndex until oldIndex + lookAhead) {
                            diffLines.add(DiffLine(oldLines[i], DiffLineType.REMOVED, i + 1))
                        }
                        oldIndex += lookAhead
                        foundMatch = true
                    } else {
                        lookAhead++
                    }
                }
                
                if (!foundMatch) {
                    // No match found - treat as remove + add
                    diffLines.add(DiffLine(oldLines[oldIndex], DiffLineType.REMOVED, oldIndex + 1))
                    diffLines.add(DiffLine(newLines[newIndex], DiffLineType.ADDED, newLineNumber))
                    oldIndex++
                    newIndex++
                    newLineNumber++
                }
            }
        }
    }
    
    return diffLines
}
