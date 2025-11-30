package com.qali.aterm.agent.utils

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Caches dependency detection results to reduce unnecessary fallback attempts
 */
object DependencyCache {
    
    private data class DependencyInfo(
        val available: Boolean,
        val version: String?,
        val checkedAt: Long,
        val checkCommand: String
    )
    
    private val cache = ConcurrentHashMap<String, DependencyInfo>()
    private val cacheTimeoutMs = 5 * 60 * 1000L // 5 minutes cache
    
    /**
     * Check if a dependency is available (cached)
     */
    fun isAvailable(
        dependency: String,
        checkCommand: String,
        workspaceRoot: String? = null
    ): Boolean? {
        val cacheKey = "$dependency:$workspaceRoot"
        val cached = cache[cacheKey]
        
        if (cached != null && (System.currentTimeMillis() - cached.checkedAt) < cacheTimeoutMs) {
            Log.d("DependencyCache", "Cache hit for $dependency: ${cached.available}")
            return cached.available
        }
        
        // Cache miss - return null to indicate check needed
        return null
    }
    
    /**
     * Get cached version
     */
    fun getVersion(dependency: String, workspaceRoot: String? = null): String? {
        val cacheKey = "$dependency:$workspaceRoot"
        return cache[cacheKey]?.version
    }
    
    /**
     * Cache dependency availability result
     */
    fun cacheResult(
        dependency: String,
        available: Boolean,
        version: String? = null,
        checkCommand: String,
        workspaceRoot: String? = null
    ) {
        val cacheKey = "$dependency:$workspaceRoot"
        cache[cacheKey] = DependencyInfo(
            available = available,
            version = version,
            checkedAt = System.currentTimeMillis(),
            checkCommand = checkCommand
        )
        Log.d("DependencyCache", "Cached $dependency: available=$available, version=$version")
    }
    
    /**
     * Pre-validate Node.js dependencies upfront
     */
    fun preValidateNodeDependencies(workspaceRoot: String? = null): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        // Check for package.json to determine if Node.js is needed
        val workspaceDir = if (workspaceRoot != null) File(workspaceRoot) else null
        val hasPackageJson = workspaceDir?.let { 
            it.walkTopDown().any { file -> 
                file.name == "package.json" && file.isFile
            }
        } ?: false
        
        if (hasPackageJson) {
            // Pre-check Node.js and npm
            val nodeAvailable = isAvailable("node", "node --version", workspaceRoot)
            val npmAvailable = isAvailable("npm", "npm --version", workspaceRoot)
            
            results["node"] = nodeAvailable ?: false
            results["npm"] = npmAvailable ?: false
            results["needsNode"] = true
        } else {
            results["needsNode"] = false
        }
        
        return results
    }
    
    /**
     * Check if Node.js project exists in workspace
     */
    fun hasNodeProject(workspaceRoot: String?): Boolean {
        val workspaceDir = if (workspaceRoot != null) File(workspaceRoot) else return false
        
        val hasPackageJson = workspaceDir.walkTopDown().any { 
            it.name == "package.json" && it.isFile 
        }
        val hasNodeLockFiles = File(workspaceDir, "package-lock.json").exists() || 
                              File(workspaceDir, "yarn.lock").exists() ||
                              File(workspaceDir, "pnpm-lock.yaml").exists()
        val hasNodeSourceFiles = workspaceDir.walkTopDown()
            .any { file -> 
                file.extension in listOf("js", "jsx", "ts", "tsx", "mjs", "cjs") && file.isFile
            }
        
        return hasPackageJson || (hasNodeLockFiles && hasNodeSourceFiles)
    }
    
    /**
     * Clear cache (useful for testing or when environment changes)
     */
    fun clearCache() {
        cache.clear()
        Log.d("DependencyCache", "Cache cleared")
    }
    
    /**
     * Clear expired entries
     */
    fun clearExpired() {
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { (now - it.value.checkedAt) >= cacheTimeoutMs }
        expired.forEach { cache.remove(it.key) }
        if (expired.isNotEmpty()) {
            Log.d("DependencyCache", "Cleared ${expired.size} expired cache entries")
        }
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cachedDependencies" to cache.size,
            "cacheTimeoutMs" to cacheTimeoutMs,
            "entries" to cache.map { (key, value) ->
                mapOf(
                    "dependency" to key,
                    "available" to value.available,
                    "version" to value.version,
                    "ageMs" to (System.currentTimeMillis() - value.checkedAt)
                )
            }
        )
    }
}
