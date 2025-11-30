package com.qali.aterm.agent.utils

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors edit failures to identify patterns and improve edit tool reliability
 */
object EditFailureMonitor {
    
    private data class EditFailureStats(
        val filePath: String,
        var failureCount: AtomicInteger = AtomicInteger(0),
        var lastFailureTime: AtomicLong = AtomicLong(0),
        var failureReasons: MutableMap<String, AtomicInteger> = ConcurrentHashMap(),
        var fuzzyMatchSuccessCount: AtomicInteger = AtomicInteger(0),
        var totalEditAttempts: AtomicInteger = AtomicInteger(0)
    )
    
    private val failureStats = ConcurrentHashMap<String, EditFailureStats>()
    private val fuzzyMatchStats = ConcurrentHashMap<String, MutableList<Double>>()
    
    /**
     * Record an edit failure
     */
    fun recordEditFailure(
        filePath: String,
        reason: String,
        oldStringLength: Int = 0,
        fileSize: Int = 0
    ) {
        val stats = failureStats.getOrPut(filePath) {
            EditFailureStats(filePath)
        }
        
        stats.failureCount.incrementAndGet()
        stats.lastFailureTime.set(System.currentTimeMillis())
        stats.totalEditAttempts.incrementAndGet()
        
        val reasonCount = stats.failureReasons.getOrPut(reason) { AtomicInteger(0) }
        reasonCount.incrementAndGet()
        
        // Log failure for debugging
        Log.w("EditFailureMonitor", 
            "Edit failure in $filePath: $reason (failures: ${stats.failureCount.get()}, " +
            "oldStringLen: $oldStringLength, fileSize: $fileSize)")
    }
    
    /**
     * Record successful fuzzy match
     */
    fun recordFuzzyMatchSuccess(
        filePath: String,
        similarity: Double
    ) {
        val stats = failureStats.getOrPut(filePath) {
            EditFailureStats(filePath)
        }
        
        stats.fuzzyMatchSuccessCount.incrementAndGet()
        stats.totalEditAttempts.incrementAndGet()
        
        fuzzyMatchStats.getOrPut(filePath) { mutableListOf() }.add(similarity)
        
        Log.d("EditFailureMonitor", 
            "Fuzzy match success in $filePath: similarity=$similarity")
    }
    
    /**
     * Record successful edit
     */
    fun recordEditSuccess(filePath: String) {
        val stats = failureStats.getOrPut(filePath) {
            EditFailureStats(filePath)
        }
        stats.totalEditAttempts.incrementAndGet()
    }
    
    /**
     * Get failure statistics for a file
     */
    fun getFailureStats(filePath: String): Map<String, Any>? {
        val stats = failureStats[filePath] ?: return null
        
        return mapOf(
            "failureCount" to stats.failureCount.get(),
            "totalAttempts" to stats.totalEditAttempts.get(),
            "successRate" to if (stats.totalEditAttempts.get() > 0) {
                1.0 - (stats.failureCount.get().toDouble() / stats.totalEditAttempts.get())
            } else 1.0,
            "fuzzyMatchSuccessCount" to stats.fuzzyMatchSuccessCount.get(),
            "failureReasons" to stats.failureReasons.mapValues { it.value.get() },
            "lastFailureTime" to stats.lastFailureTime.get()
        )
    }
    
    /**
     * Get average fuzzy match similarity for a file
     */
    fun getAverageFuzzyMatchSimilarity(filePath: String): Double? {
        val similarities = fuzzyMatchStats[filePath] ?: return null
        if (similarities.isEmpty()) return null
        return similarities.average()
    }
    
    /**
     * Get overall statistics
     */
    fun getOverallStats(): Map<String, Any> {
        val totalFailures = failureStats.values.sumOf { it.failureCount.get() }
        val totalAttempts = failureStats.values.sumOf { it.totalEditAttempts.get() }
        val totalFuzzyMatches = failureStats.values.sumOf { it.fuzzyMatchSuccessCount.get() }
        
        val failureReasons = mutableMapOf<String, Int>()
        failureStats.values.forEach { stats ->
            stats.failureReasons.forEach { (reason, count) ->
                failureReasons[reason] = failureReasons.getOrDefault(reason, 0) + count.get()
            }
        }
        
        return mapOf(
            "totalFiles" to failureStats.size,
            "totalFailures" to totalFailures,
            "totalAttempts" to totalAttempts,
            "overallSuccessRate" to if (totalAttempts > 0) {
                1.0 - (totalFailures.toDouble() / totalAttempts)
            } else 1.0,
            "totalFuzzyMatches" to totalFuzzyMatches,
            "failureReasons" to failureReasons,
            "filesWithFailures" to failureStats.filter { it.value.failureCount.get() > 0 }.size
        )
    }
    
    /**
     * Check if a file has high failure rate
     */
    fun hasHighFailureRate(filePath: String, threshold: Double = 0.5): Boolean {
        val stats = failureStats[filePath] ?: return false
        if (stats.totalEditAttempts.get() < 3) return false // Need at least 3 attempts
        
        val failureRate = stats.failureCount.get().toDouble() / stats.totalEditAttempts.get()
        return failureRate >= threshold
    }
    
    /**
     * Get recommendations for improving edit success rate
     */
    fun getRecommendations(filePath: String? = null): List<String> {
        val recommendations = mutableListOf<String>()
        
        if (filePath != null) {
            val stats = failureStats[filePath]
            if (stats != null) {
                val failureRate = if (stats.totalEditAttempts.get() > 0) {
                    stats.failureCount.get().toDouble() / stats.totalEditAttempts.get()
                } else 0.0
                
                if (failureRate > 0.5) {
                    recommendations.add("High failure rate (${(failureRate * 100).toInt()}%) for $filePath")
                }
                
                val stringNotFoundCount = stats.failureReasons["String not found"]?.get() ?: 0
                if (stringNotFoundCount > 0) {
                    recommendations.add("Consider using more context in old_string (${stringNotFoundCount} 'String not found' errors)")
                }
                
                val fuzzySuccessRate = if (stats.totalEditAttempts.get() > 0) {
                    stats.fuzzyMatchSuccessCount.get().toDouble() / stats.totalEditAttempts.get()
                } else 0.0
                if (fuzzySuccessRate > 0.3) {
                    recommendations.add("Fuzzy matching is frequently needed (${(fuzzySuccessRate * 100).toInt()}% of edits)")
                }
            }
        } else {
            // Overall recommendations
            val overallStats = getOverallStats()
            val overallFailureRate = overallStats["overallSuccessRate"] as? Double ?: 1.0
            
            if (overallFailureRate < 0.8) {
                recommendations.add("Overall edit success rate is ${(overallFailureRate * 100).toInt()}% - consider improving string matching")
            }
            
            val failureReasons = overallStats["failureReasons"] as? Map<String, Int> ?: emptyMap<String, Int>()
            val stringNotFound = failureReasons["String not found"] as? Int ?: 0
            if (stringNotFound > 10) {
                recommendations.add("'String not found' is the main issue ($stringNotFound occurrences) - improve context in old_string")
            }
        }
        
        return recommendations
    }
    
    /**
     * Clear statistics (for testing or reset)
     */
    fun clearStats() {
        failureStats.clear()
        fuzzyMatchStats.clear()
    }
}
