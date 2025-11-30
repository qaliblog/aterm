package com.qali.aterm.agent.utils

/**
 * Fuzzy string matching utilities for improved edit tool reliability
 */
object FuzzyStringMatcher {
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[m][n]
    }
    
    /**
     * Calculate similarity ratio (0.0 to 1.0)
     */
    fun similarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        val maxLen = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen)
    }
    
    /**
     * Find best match position using fuzzy matching
     * Returns MatchResult with position, similarity, and adjusted old_string
     */
    data class MatchResult(
        val startIndex: Int,
        val endIndex: Int,
        val similarity: Double,
        val matchedText: String,
        val adjustedOldString: String
    )
    
    /**
     * Find the best fuzzy match for old_string in content
     */
    fun findBestMatch(
        content: String,
        oldString: String,
        minSimilarity: Double = 0.85,
        contextWindow: Int = 50
    ): MatchResult? {
        if (oldString.isEmpty()) return null
        
        val normalizedContent = content.replace("\r\n", "\n")
        val normalizedOld = oldString.replace("\r\n", "\n")
        
        // First try exact match
        val exactIndex = normalizedContent.indexOf(normalizedOld)
        if (exactIndex >= 0) {
            return MatchResult(
                startIndex = exactIndex,
                endIndex = exactIndex + normalizedOld.length,
                similarity = 1.0,
                matchedText = normalizedOld,
                adjustedOldString = normalizedOld
            )
        }
        
        // Try fuzzy matching with sliding window
        val oldLines = normalizedOld.lines()
        if (oldLines.isEmpty()) return null
        
        // Strategy 1: Find by first and last line (anchor-based matching)
        val firstLine = oldLines.first().trim()
        val lastLine = oldLines.lastOrNull()?.trim() ?: firstLine
        
        // Limit search to prevent OOM on very large files
        val maxContentSize = 5_000_000 // 5MB limit
        if (normalizedContent.length > maxContentSize) {
            // For very large files, only search in first and last portions
            val searchPortion = maxContentSize / 2
            val firstPortion = normalizedContent.substring(0, minOf(searchPortion, normalizedContent.length))
            val lastPortion = if (normalizedContent.length > searchPortion) {
                normalizedContent.substring(maxOf(0, normalizedContent.length - searchPortion))
            } else {
                ""
            }
            
            // Try first portion
            val firstLineIndices = findAllIndices(firstPortion, firstLine)
            val lastLineIndices = findAllIndices(firstPortion, lastLine)
            
            var bestMatch: MatchResult? = null
            var bestSimilarity = 0.0
            
            // Limit iterations to prevent OOM
            val maxIterations = 100
            var iterations = 0
            for (firstIdx in firstLineIndices.take(10)) {
                for (lastIdx in lastLineIndices.take(10)) {
                    if (++iterations > maxIterations) break
                    if (lastIdx <= firstIdx) continue
                    
                    val regionStart = maxOf(0, firstIdx - contextWindow)
                    val regionEnd = minOf(firstPortion.length, lastIdx + lastLine.length + contextWindow)
                    val region = firstPortion.substring(regionStart, regionEnd)
                    
                    val sim = similarity(region, normalizedOld)
                    if (sim >= minSimilarity && sim > bestSimilarity) {
                        val adjustedStart = findBestStart(region, normalizedOld, firstIdx - regionStart)
                        val adjustedEnd = adjustedStart + normalizedOld.length
                        
                        if (adjustedStart >= 0 && adjustedEnd <= region.length) {
                            val matchedText = region.substring(adjustedStart, adjustedEnd)
                            bestMatch = MatchResult(
                                startIndex = regionStart + adjustedStart,
                                endIndex = regionStart + adjustedEnd,
                                similarity = sim,
                                matchedText = matchedText,
                                adjustedOldString = matchedText
                            )
                            bestSimilarity = sim
                        }
                    }
                }
                if (iterations > maxIterations) break
            }
            
            return bestMatch
        }
        
        val firstLineIndices = findAllIndices(normalizedContent, firstLine)
        val lastLineIndices = findAllIndices(normalizedContent, lastLine)
        
        var bestMatch: MatchResult? = null
        var bestSimilarity = 0.0
        
        // Limit iterations to prevent OOM
        val maxIterations = 500
        var iterations = 0
        // Check each potential match region
        for (firstIdx in firstLineIndices.take(20)) {
            for (lastIdx in lastLineIndices.take(20)) {
                if (++iterations > maxIterations) break
                if (lastIdx <= firstIdx) continue
                
                // Extract potential match region
                val regionStart = maxOf(0, firstIdx - contextWindow)
                val regionEnd = minOf(normalizedContent.length, lastIdx + lastLine.length + contextWindow)
                val region = normalizedContent.substring(regionStart, regionEnd)
                
                // Calculate similarity
                val sim = similarity(region, normalizedOld)
                
                if (sim >= minSimilarity && sim > bestSimilarity) {
                    // Try to find exact boundaries
                    val adjustedStart = findBestStart(region, normalizedOld, firstIdx - regionStart)
                    val adjustedEnd = adjustedStart + normalizedOld.length
                    
                    if (adjustedStart >= 0 && adjustedEnd <= region.length) {
                        val matchedText = region.substring(adjustedStart, adjustedEnd)
                        bestMatch = MatchResult(
                            startIndex = regionStart + adjustedStart,
                            endIndex = regionStart + adjustedEnd,
                            similarity = sim,
                            matchedText = matchedText,
                            adjustedOldString = matchedText
                        )
                        bestSimilarity = sim
                    }
                }
            }
            if (iterations > maxIterations) break
        }
        
        // Strategy 2: Line-by-line fuzzy matching
        if (bestMatch == null && oldLines.size > 1) {
            val contentLines = normalizedContent.lines()
            for (i in 0..(contentLines.size - oldLines.size)) {
                val window = contentLines.subList(i, minOf(i + oldLines.size + 2, contentLines.size))
                val windowText = window.joinToString("\n")
                
                val sim = similarity(windowText, normalizedOld)
                if (sim >= minSimilarity && sim > bestSimilarity) {
                    // Calculate actual position
                    val lineStart = contentLines.subList(0, i).joinToString("\n").length + 
                                   if (i > 0) 1 else 0
                    val matchedText = windowText.substring(0, minOf(windowText.length, normalizedOld.length))
                    
                    bestMatch = MatchResult(
                        startIndex = lineStart,
                        endIndex = lineStart + matchedText.length,
                        similarity = sim,
                        matchedText = matchedText,
                        adjustedOldString = matchedText
                    )
                    bestSimilarity = sim
                }
            }
        }
        
        return bestMatch
    }
    
    /**
     * Find all indices where substring occurs in text
     * Limits results to prevent OOM on very large files
     */
    private fun findAllIndices(text: String, substring: String): List<Int> {
        val indices = mutableListOf<Int>()
        val maxIndices = 1000 // Limit to prevent OOM
        var index = text.indexOf(substring)
        var count = 0
        while (index >= 0 && count < maxIndices) {
            indices.add(index)
            index = text.indexOf(substring, index + 1)
            count++
        }
        return indices
    }
    
    /**
     * Find best start position within region
     */
    private fun findBestStart(region: String, target: String, anchorPos: Int): Int {
        // Try positions around anchor
        val searchWindow = 20
        val start = maxOf(0, anchorPos - searchWindow)
        val end = minOf(region.length - target.length, anchorPos + searchWindow)
        
        var bestPos = anchorPos
        var bestSim = 0.0
        
        for (pos in start..end) {
            if (pos < 0 || pos + target.length > region.length) continue
            val candidate = region.substring(pos, pos + target.length)
            val sim = similarity(candidate, target)
            if (sim > bestSim) {
                bestSim = sim
                bestPos = pos
            }
        }
        
        return bestPos
    }
    
    /**
     * Context-aware offset finder: uses line numbers and surrounding context
     */
    data class ContextMatch(
        val lineNumber: Int,
        val columnOffset: Int,
        val matchedText: String,
        val contextBefore: String,
        val contextAfter: String
    )
    
    /**
     * Find match using line numbers and context
     */
    fun findContextAwareMatch(
        content: String,
        oldString: String,
        hintLineNumber: Int? = null,
        contextLines: Int = 3
    ): ContextMatch? {
        val lines = content.lines()
        val oldLines = oldString.lines()
        
        if (oldLines.isEmpty()) return null
        
        // If hint provided, check around that line
        if (hintLineNumber != null && hintLineNumber >= 0 && hintLineNumber < lines.size) {
            val startLine = maxOf(0, hintLineNumber - contextLines)
            val endLine = minOf(lines.size, hintLineNumber + oldLines.size + contextLines)
            val region = lines.subList(startLine, endLine).joinToString("\n")
            
            val match = findBestMatch(region, oldString, minSimilarity = 0.80)
            if (match != null) {
                // Calculate actual line number
                val regionLines = region.lines()
                val matchLineInRegion = region.substring(0, match.startIndex).split('\n').size - 1
                val actualLineNumber = startLine + matchLineInRegion
                
                val contextBefore = lines.subList(
                    maxOf(0, actualLineNumber - contextLines),
                    actualLineNumber
                ).joinToString("\n")
                val contextAfter = lines.subList(
                    minOf(actualLineNumber + oldLines.size, lines.size),
                    minOf(actualLineNumber + oldLines.size + contextLines, lines.size)
                ).joinToString("\n")
                
                return ContextMatch(
                    lineNumber = actualLineNumber,
                    columnOffset = 0,
                    matchedText = match.matchedText,
                    contextBefore = contextBefore,
                    contextAfter = contextAfter
                )
            }
        }
        
        // Fallback to regular fuzzy match
        val match = findBestMatch(content, oldString, minSimilarity = 0.85)
        if (match != null) {
            val matchLineNumber = content.substring(0, match.startIndex).split('\n').size - 1
            val contextBefore = lines.subList(
                maxOf(0, matchLineNumber - contextLines),
                matchLineNumber
            ).joinToString("\n")
            val contextAfter = lines.subList(
                minOf(matchLineNumber + oldLines.size, lines.size),
                minOf(matchLineNumber + oldLines.size + contextLines, lines.size)
            ).joinToString("\n")
            
            return ContextMatch(
                lineNumber = matchLineNumber,
                columnOffset = 0,
                matchedText = match.matchedText,
                contextBefore = contextBefore,
                contextAfter = contextAfter
            )
        }
        
        return null
    }
}
