package com.qali.aterm.agent.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages file coherence to prevent conflicts during concurrent operations
 * Especially important when multiple tool calls might modify files concurrently
 */
object FileCoherenceManager {
    // Per-file locks to prevent concurrent modifications
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    
    // Track file versions to detect conflicts
    private val fileVersions = ConcurrentHashMap<String, Long>()
    
    /**
     * Execute a file operation with coherence guarantees
     * Ensures only one operation per file at a time
     */
    suspend fun <T> withFileLock(filePath: String, operation: suspend () -> T): T {
        val normalizedPath = File(filePath).canonicalPath
        val mutex = fileLocks.getOrPut(normalizedPath) { Mutex() }
        
        return mutex.withLock {
            operation()
        }
    }
    
    /**
     * Read file content with version tracking
     */
    suspend fun readFileWithVersion(file: File): Pair<String, Long> {
        return withFileLock(file.absolutePath) {
            val content = file.readText()
            val version = file.lastModified()
            fileVersions[file.absolutePath] = version
            Pair(content, version)
        }
    }
    
    /**
     * Write file content with conflict detection
     * Returns true if write succeeded, false if conflict detected
     */
    suspend fun writeFileWithCoherence(
        file: File,
        content: String,
        expectedVersion: Long? = null
    ): Boolean {
        return withFileLock(file.absolutePath) {
            // Check for conflicts if expected version is provided
            if (expectedVersion != null) {
                val currentVersion = fileVersions[file.absolutePath]
                if (currentVersion != null && currentVersion != expectedVersion) {
                    // File was modified by another operation
                    android.util.Log.w("FileCoherenceManager", "Write conflict detected for ${file.absolutePath}")
                    return@withFileLock false
                }
            }
            
            // Perform atomic write using temporary file
            val tempFile = File(file.parent, "${file.name}.tmp.${System.currentTimeMillis()}")
            try {
                tempFile.writeText(content)
                
                // Atomic move
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                
                // Update version
                val newVersion = file.lastModified()
                fileVersions[file.absolutePath] = newVersion
                
                android.util.Log.d("FileCoherenceManager", "Successfully wrote ${file.absolutePath} (version: $newVersion)")
                true
            } catch (e: Exception) {
                android.util.Log.e("FileCoherenceManager", "Error writing file ${file.absolutePath}", e)
                // Clean up temp file if it exists
                tempFile.delete()
                throw e
            }
        }
    }
    
    /**
     * Check if file has been modified since last read
     */
    fun isFileModified(file: File, lastKnownVersion: Long): Boolean {
        val currentVersion = file.lastModified()
        return currentVersion != lastKnownVersion
    }
    
    /**
     * Clear locks for a file (useful for cleanup)
     */
    fun clearFileLock(filePath: String) {
        val normalizedPath = File(filePath).canonicalPath
        fileLocks.remove(normalizedPath)
        fileVersions.remove(normalizedPath)
    }
    
    /**
     * Clear all locks (useful for cleanup)
     */
    fun clearAllLocks() {
        fileLocks.clear()
        fileVersions.clear()
    }
}
