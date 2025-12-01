package com.qali.aterm.agent.ppe

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Intelligent caching system
 * Better than Cursor AI: Smart invalidation and TTL-based expiration
 */
object IntelligentCache {
    
    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val ttl: Long
    ) {
        fun isExpired(): Boolean {
            return System.currentTimeMillis() - timestamp > ttl
        }
    }
    
    private val fileStructureCache = ConcurrentHashMap<String, CacheEntry<String>>()
    private val dependencyMatrixCache = ConcurrentHashMap<String, CacheEntry<Any>>()
    private val blueprintCache = ConcurrentHashMap<String, CacheEntry<String>>()
    private val cacheMutex = Mutex()
    
    /**
     * Get or compute file structure
     */
    suspend fun getFileStructure(
        workspaceRoot: String,
        compute: suspend () -> String
    ): String {
        val cacheKey = workspaceRoot
        val entry = fileStructureCache[cacheKey]
        
        if (entry != null && !entry.isExpired()) {
            Log.d("IntelligentCache", "Cache hit: file structure")
            return entry.data
        }
        
        // Check if workspace files changed
        val lastModified = getWorkspaceLastModified(workspaceRoot)
        if (entry != null && lastModified <= entry.timestamp) {
            Log.d("IntelligentCache", "Cache valid: workspace unchanged")
            return entry.data
        }
        
        // Cache miss or expired, compute
        Log.d("IntelligentCache", "Cache miss: computing file structure")
        val data = compute()
        
        cacheMutex.withLock {
            fileStructureCache[cacheKey] = CacheEntry(
                data = data,
                timestamp = System.currentTimeMillis(),
                ttl = PpeConfig.CACHE_TTL_SECONDS * 1000
            )
        }
        
        return data
    }
    
    /**
     * Get or compute dependency matrix
     */
    suspend fun getDependencyMatrix(
        workspaceRoot: String,
        compute: suspend () -> Any
    ): Any {
        val cacheKey = workspaceRoot
        val entry = dependencyMatrixCache[cacheKey]
        
        if (entry != null && !entry.isExpired()) {
            Log.d("IntelligentCache", "Cache hit: dependency matrix")
            return entry.data
        }
        
        // Check if code files changed
        val lastModified = getCodeFilesLastModified(workspaceRoot)
        if (entry != null && lastModified <= entry.timestamp) {
            Log.d("IntelligentCache", "Cache valid: code files unchanged")
            return entry.data
        }
        
        // Cache miss or expired, compute
        Log.d("IntelligentCache", "Cache miss: computing dependency matrix")
        val data = compute()
        
        cacheMutex.withLock {
            dependencyMatrixCache[cacheKey] = CacheEntry(
                data = data,
                timestamp = System.currentTimeMillis(),
                ttl = PpeConfig.CACHE_TTL_SECONDS * 1000
            )
        }
        
        return data
    }
    
    /**
     * Get or compute blueprint
     */
    suspend fun getBlueprint(
        workspaceRoot: String,
        userMessage: String,
        compute: suspend () -> String
    ): String {
        val cacheKey = "$workspaceRoot:$userMessage"
        val entry = blueprintCache[cacheKey]
        
        if (entry != null && !entry.isExpired()) {
            Log.d("IntelligentCache", "Cache hit: blueprint")
            return entry.data
        }
        
        // Cache miss or expired, compute
        Log.d("IntelligentCache", "Cache miss: computing blueprint")
        val data = compute()
        
        cacheMutex.withLock {
            blueprintCache[cacheKey] = CacheEntry(
                data = data,
                timestamp = System.currentTimeMillis(),
                ttl = PpeConfig.CACHE_TTL_SECONDS * 1000
            )
        }
        
        return data
    }
    
    /**
     * Invalidate cache for workspace
     */
    fun invalidate(workspaceRoot: String) {
        fileStructureCache.remove(workspaceRoot)
        dependencyMatrixCache.remove(workspaceRoot)
        blueprintCache.entries.removeAll { it.key.startsWith(workspaceRoot) }
        Log.d("IntelligentCache", "Invalidated cache for: $workspaceRoot")
    }
    
    /**
     * Clear all caches
     */
    fun clear() {
        fileStructureCache.clear()
        dependencyMatrixCache.clear()
        blueprintCache.clear()
        Log.d("IntelligentCache", "Cleared all caches")
    }
    
    private fun getWorkspaceLastModified(workspaceRoot: String): Long {
        val dir = File(workspaceRoot)
        if (!dir.exists()) return 0
        
        return dir.walkTopDown()
            .filter { it.isFile }
            .maxOfOrNull { it.lastModified() } ?: 0
    }
    
    private fun getCodeFilesLastModified(workspaceRoot: String): Long {
        val dir = File(workspaceRoot)
        if (!dir.exists()) return 0
        
        val codeExtensions = setOf("js", "ts", "py", "java", "kt", "go", "rs", "cpp", "c")
        
        return dir.walkTopDown()
            .filter { it.isFile && codeExtensions.contains(it.extension.lowercase()) }
            .maxOfOrNull { it.lastModified() } ?: 0
    }
}
