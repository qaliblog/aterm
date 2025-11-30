package com.qali.aterm.agent.utils

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * Aggregates chunks before processing to reduce overhead
 */
object ChunkAggregator {
    
    private const val DEFAULT_BUFFER_SIZE = 100
    private const val DEFAULT_TIMEOUT_MS = 100L // 100ms timeout for aggregation
    
    /**
     * Aggregate chunks with configurable buffer size and timeout
     */
    suspend fun <T> aggregateChunks(
        chunks: Flow<T>,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        transform: (List<T>) -> List<T> = { it }
    ): Flow<List<T>> = flow {
        val buffer = mutableListOf<T>()
        var lastEmitTime = System.currentTimeMillis()
        
        chunks.collect { chunk ->
            buffer.add(chunk)
            
            val now = System.currentTimeMillis()
            val timeSinceLastEmit = now - lastEmitTime
            
            // Emit if buffer is full or timeout reached
            if (buffer.size >= bufferSize || timeSinceLastEmit >= timeoutMs) {
                if (buffer.isNotEmpty()) {
                    val aggregated = transform(buffer.toList())
                    emit(aggregated)
                    buffer.clear()
                    lastEmitTime = now
                }
            }
        }
        
        // Emit remaining chunks
        if (buffer.isNotEmpty()) {
            val aggregated = transform(buffer.toList())
            emit(aggregated)
        }
    }
    
    /**
     * Aggregate string chunks into larger strings
     */
    suspend fun aggregateStringChunks(
        chunks: Flow<String>,
        minChunkSize: Int = 50,
        maxWaitMs: Long = 200L
    ): Flow<String> = flow {
        val buffer = StringBuilder()
        var lastEmitTime = System.currentTimeMillis()
        
        chunks.collect { chunk ->
            buffer.append(chunk)
            
            val now = System.currentTimeMillis()
            val timeSinceLastEmit = now - lastEmitTime
            
            // Emit if buffer is large enough or timeout reached
            if (buffer.length >= minChunkSize || timeSinceLastEmit >= maxWaitMs) {
                if (buffer.isNotEmpty()) {
                    emit(buffer.toString())
                    buffer.clear()
                    lastEmitTime = now
                }
            }
        }
        
        // Emit remaining content
        if (buffer.isNotEmpty()) {
            emit(buffer.toString())
        }
    }
    
    /**
     * Smart aggregation that detects edit operations and batches them
     */
    data class EditOperation(
        val filePath: String,
        val oldString: String,
        val newString: String,
        val chunks: List<String>
    )
    
    /**
     * Detect if chunks contain edit operations and batch them
     */
    fun detectEditOperations(chunks: List<String>): List<EditOperation> {
        val operations = mutableListOf<EditOperation>()
        val editPattern = Regex("""edit.*file_path.*old_string""", RegexOption.IGNORE_CASE)
        
        // Simple heuristic: look for edit-related patterns
        chunks.forEachIndexed { index, chunk ->
            if (editPattern.containsMatchIn(chunk)) {
                // Try to extract file path and edit info
                // This is a simplified version - actual implementation would parse JSON/structured data
                val filePathMatch = Regex("""file_path["\s:=]+([^\s"']+)""").find(chunk)
                if (filePathMatch != null) {
                    val filePath = filePathMatch.groupValues[1]
                    operations.add(
                        EditOperation(
                            filePath = filePath,
                            oldString = "",
                            newString = "",
                            chunks = listOf(chunk)
                        )
                    )
                }
            }
        }
        
        return operations
    }
}
