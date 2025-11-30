package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.ppe.models.PpeScript
import java.io.File

/**
 * Loads and caches PPE scripts
 */
object PpeScriptLoader {
    private val scriptCache = mutableMapOf<String, PpeScript>()
    
    /**
     * Load a script from file path
     */
    fun loadScript(filePath: String): PpeScript {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Script file not found: $filePath")
        }
        
        // Check cache
        val cacheKey = file.canonicalPath
        scriptCache[cacheKey]?.let { return it }
        
        // Parse and cache (use enhanced parser for full CLI features)
        val script = PpeScriptParserEnhanced.parse(file)
        scriptCache[cacheKey] = script
        return script
    }
    
    /**
     * Load a script from File object
     */
    fun loadScript(file: File): PpeScript {
        if (!file.exists()) {
            throw IllegalArgumentException("Script file not found: ${file.absolutePath}")
        }
        
        // Check cache
        val cacheKey = file.canonicalPath
        scriptCache[cacheKey]?.let { return it }
        
        // Parse and cache (use enhanced parser for full CLI features)
        val script = PpeScriptParserEnhanced.parse(file)
        scriptCache[cacheKey] = script
        return script
    }
    
    /**
     * Load a script from directory (entry point script has same name as directory)
     */
    fun loadScriptFromDirectory(dirPath: String): PpeScript {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            throw IllegalArgumentException("Script directory not found: $dirPath")
        }
        
        val dirName = dir.name
        val entryPoint = File(dir, "$dirName.ai.yaml")
        if (!entryPoint.exists()) {
            throw IllegalArgumentException("Entry point script not found: ${entryPoint.absolutePath}")
        }
        
        return loadScript(entryPoint)
    }
    
    /**
     * Clear script cache
     */
    fun clearCache() {
        scriptCache.clear()
    }
    
    /**
     * Remove script from cache
     */
    fun removeFromCache(filePath: String) {
        val file = File(filePath)
        scriptCache.remove(file.canonicalPath)
    }
    
    /**
     * Get cached script if available
     */
    fun getCached(filePath: String): PpeScript? {
        val file = File(filePath)
        return scriptCache[file.canonicalPath]
    }
}
