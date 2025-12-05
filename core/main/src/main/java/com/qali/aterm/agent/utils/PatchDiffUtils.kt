package com.qali.aterm.agent.utils

import android.util.Log
import java.io.File

/**
 * Utilities for generating patch-style diffs (like Git diffs)
 * Used when agent makes code changes to show what was modified
 */
object PatchDiffUtils {
    
    /**
     * Generate a unified diff patch from old and new content
     * Format matches Git's unified diff format
     */
    fun generatePatch(
        filePath: String,
        oldContent: String,
        newContent: String,
        contextLines: Int = 3
    ): String {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        
        // Simple line-by-line diff
        val diff = mutableListOf<String>()
        diff.add("--- a/$filePath")
        diff.add("+++ b/$filePath")
        
        // Calculate diff using simple algorithm
        val changes = calculateLineDiff(oldLines, newLines)
        
        if (changes.isEmpty()) {
            return "" // No changes
        }
        
        // Group consecutive changes into hunks
        val hunks = groupIntoHunks(changes, contextLines)
        
        for (hunk in hunks) {
            val oldStart = hunk.oldStart
            val oldCount = hunk.oldCount
            val newStart = hunk.newStart
            val newCount = hunk.newCount
            
            diff.add("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            
            // Add context and changes
            for (change in hunk.changes) {
                when (change.type) {
                    ChangeType.UNCHANGED -> diff.add(" ${change.content}")
                    ChangeType.REMOVED -> diff.add("-${change.content}")
                    ChangeType.ADDED -> diff.add("+${change.content}")
                }
            }
        }
        
        return diff.joinToString("\n")
    }
    
    /**
     * Generate patch from file changes
     */
    fun generatePatchFromFiles(
        filePath: String,
        oldFile: File?,
        newContent: String
    ): String {
        val oldContent = if (oldFile?.exists() == true) {
            try {
                oldFile.readText()
            } catch (e: Exception) {
                Log.w("PatchDiffUtils", "Failed to read old file: ${e.message}")
                ""
            }
        } else {
            "" // New file
        }
        
        return generatePatch(filePath, oldContent, newContent)
    }
    
    private enum class ChangeType {
        UNCHANGED, REMOVED, ADDED
    }
    
    private data class Change(
        val type: ChangeType,
        val content: String,
        val oldLine: Int?,
        val newLine: Int?
    )
    
    private data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val changes: List<Change>
    )
    
    /**
     * Calculate line-by-line diff using simple algorithm
     */
    private fun calculateLineDiff(oldLines: List<String>, newLines: List<String>): List<Change> {
        val changes = mutableListOf<Change>()
        
        // Use simple longest common subsequence approach
        val lcs = longestCommonSubsequence(oldLines, newLines)
        
        var oldIdx = 0
        var newIdx = 0
        var lcsIdx = 0
        
        while (oldIdx < oldLines.size || newIdx < newLines.size) {
            when {
                // Match found
                lcsIdx < lcs.size && 
                oldIdx < oldLines.size && 
                newIdx < newLines.size &&
                oldLines[oldIdx] == lcs[lcsIdx] &&
                newLines[newIdx] == lcs[lcsIdx] -> {
                    changes.add(Change(ChangeType.UNCHANGED, oldLines[oldIdx], oldIdx + 1, newIdx + 1))
                    oldIdx++
                    newIdx++
                    lcsIdx++
                }
                // Line removed
                oldIdx < oldLines.size && 
                (lcsIdx >= lcs.size || oldLines[oldIdx] != lcs[lcsIdx]) -> {
                    changes.add(Change(ChangeType.REMOVED, oldLines[oldIdx], oldIdx + 1, null))
                    oldIdx++
                }
                // Line added
                newIdx < newLines.size && 
                (lcsIdx >= lcs.size || newLines[newIdx] != lcs[lcsIdx]) -> {
                    changes.add(Change(ChangeType.ADDED, newLines[newIdx], null, newIdx + 1))
                    newIdx++
                }
                else -> break
            }
        }
        
        return changes
    }
    
    /**
     * Find longest common subsequence
     */
    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<String> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        // Build DP table
        for (i in 1..m) {
            for (j in 1..n) {
                if (a[i - 1] == b[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        
        // Reconstruct LCS
        val lcs = mutableListOf<String>()
        var i = m
        var j = n
        
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> {
                    lcs.add(0, a[i - 1])
                    i--
                    j--
                }
                dp[i - 1][j] > dp[i][j - 1] -> i--
                else -> j--
            }
        }
        
        return lcs
    }
    
    /**
     * Group changes into hunks with context
     */
    private fun groupIntoHunks(changes: List<Change>, contextLines: Int): List<Hunk> {
        if (changes.isEmpty()) return emptyList()
        
        val hunks = mutableListOf<Hunk>()
        var i = 0
        
        while (i < changes.size) {
            // Find next change
            while (i < changes.size && changes[i].type == ChangeType.UNCHANGED) {
                i++
            }
            
            if (i >= changes.size) break
            
            // Find start of hunk (with context)
            val hunkStart = maxOf(0, i - contextLines)
            
            // Find end of hunk (consecutive changes + context)
            var hunkEnd = i
            var lastChange = i
            
            while (hunkEnd < changes.size) {
                if (changes[hunkEnd].type != ChangeType.UNCHANGED) {
                    lastChange = hunkEnd
                }
                
                // Continue until we have contextLines of unchanged after last change
                if (hunkEnd > lastChange + contextLines) {
                    break
                }
                
                hunkEnd++
            }
            
            hunkEnd = minOf(changes.size, hunkEnd)
            
            // Calculate line numbers
            val hunkChanges = changes.subList(hunkStart, hunkEnd)
            val oldStart = hunkChanges.firstOrNull { it.oldLine != null }?.oldLine ?: 1
            val newStart = hunkChanges.firstOrNull { it.newLine != null }?.newLine ?: 1
            
            val oldCount = hunkChanges.count { it.type != ChangeType.ADDED }
            val newCount = hunkChanges.count { it.type != ChangeType.REMOVED }
            
            hunks.add(Hunk(oldStart, oldCount, newStart, newCount, hunkChanges))
            
            i = hunkEnd
        }
        
        return hunks
    }
    
    /**
     * Format patch for display in agent response
     */
    fun formatPatchForDisplay(patch: String): String {
        if (patch.isEmpty()) return ""
        
        return buildString {
            appendLine("```diff")
            appendLine(patch)
            appendLine("```")
        }
    }
}
