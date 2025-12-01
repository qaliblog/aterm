package com.qali.aterm.agent.ppe

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Observability and metrics tracking
 * Better than Cursor AI: Comprehensive metrics and cost tracking
 */
object Observability {
    
    data class OperationMetrics(
        val operationId: String,
        val operationType: String,
        val startTime: Long,
        var endTime: Long? = null,
        var tokensUsed: Int = 0,
        var apiCalls: Int = 0,
        var toolCalls: Int = 0,
        var errors: Int = 0,
        var cost: Double = 0.0
    )
    
    private val metrics = ConcurrentHashMap<String, OperationMetrics>()
    private val metricsMutex = Mutex()
    
    private val totalApiCalls = AtomicLong(0)
    private val totalToolCalls = AtomicLong(0)
    private val totalTokens = AtomicLong(0)
    private val totalCost = AtomicDouble(0.0)
    private val totalErrors = AtomicLong(0)
    
    /**
     * Start tracking an operation
     */
    fun startOperation(operationId: String, operationType: String): OperationMetrics {
        val metric = OperationMetrics(
            operationId = operationId,
            operationType = operationType,
            startTime = System.currentTimeMillis()
        )
        metrics[operationId] = metric
        Log.d("Observability", "Started tracking: $operationType ($operationId)")
        return metric
    }
    
    /**
     * End tracking an operation
     */
    suspend fun endOperation(operationId: String) {
        metricsMutex.withLock {
            metrics[operationId]?.endTime = System.currentTimeMillis()
        }
    }
    
    /**
     * Record API call
     */
    fun recordApiCall(operationId: String, tokens: Int, cost: Double) {
        totalApiCalls.incrementAndGet()
        totalTokens.addAndGet(tokens.toLong())
        totalCost.add(cost)
        
        metricsMutex.withLock {
            metrics[operationId]?.let {
                it.apiCalls++
                it.tokensUsed += tokens
                it.cost += cost
            }
        }
    }
    
    /**
     * Record tool call
     */
    fun recordToolCall(operationId: String) {
        totalToolCalls.incrementAndGet()
        metricsMutex.withLock {
            metrics[operationId]?.toolCalls++
        }
    }
    
    /**
     * Record error
     */
    fun recordError(operationId: String) {
        totalErrors.incrementAndGet()
        metricsMutex.withLock {
            metrics[operationId]?.errors++
        }
    }
    
    /**
     * Get operation metrics
     */
    suspend fun getOperationMetrics(operationId: String): OperationMetrics? {
        return metricsMutex.withLock {
            metrics[operationId]
        }
    }
    
    /**
     * Get global statistics
     */
    fun getGlobalStats(): Map<String, Any> {
        return mapOf(
            "totalApiCalls" to totalApiCalls.get(),
            "totalToolCalls" to totalToolCalls.get(),
            "totalTokens" to totalTokens.get(),
            "totalCost" to totalCost.get(),
            "totalErrors" to totalErrors.get(),
            "activeOperations" to metrics.size
        )
    }
    
    /**
     * Get cost per provider (approximate)
     */
    fun estimateCost(provider: String, tokens: Int): Double {
        // Approximate costs per 1K tokens (as of 2024)
        val costsPer1K = mapOf(
            "gpt-4" to 0.03,
            "gpt-4-turbo" to 0.01,
            "gpt-3.5-turbo" to 0.0015,
            "gemini-pro" to 0.0005,
            "claude-3-opus" to 0.015,
            "claude-3-sonnet" to 0.003,
            "claude-3-haiku" to 0.00025,
            "ollama" to 0.0 // Local, no cost
        )
        
        val costPer1K = costsPer1K.entries.firstOrNull { 
            provider.contains(it.key, ignoreCase = true) 
        }?.value ?: 0.001
        
        return (tokens / 1000.0) * costPer1K
    }
    
    /**
     * Clear metrics
     */
    fun clear() {
        metrics.clear()
        totalApiCalls.set(0)
        totalToolCalls.set(0)
        totalTokens.set(0)
        totalCost.set(0.0)
        totalErrors.set(0)
    }
}

/**
 * Thread-safe atomic double
 */
private class AtomicDouble(initialValue: Double) {
    @Volatile
    private var value = initialValue
    
    fun get(): Double = value
    
    fun add(delta: Double) {
        synchronized(this) {
            value += delta
        }
    }
    
    fun set(newValue: Double) {
        synchronized(this) {
            value = newValue
        }
    }
}
