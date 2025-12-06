package com.qali.aterm.llm

import android.content.Context
import android.util.Log
import com.rk.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Local LLM model using llama.cpp
 * JNI bridge for native llama.cpp library
 */
object LocalLlamaModel {
    private const val TAG = "LocalLlamaModel"
    
    // Context for accessing app directories
    private var appContext: Context? = null
    private var isInitialized = false
    
    fun init(context: Context) {
        if (!isInitialized) {
            appContext = context.applicationContext
        try {
            System.loadLibrary("llama_jni")
            Log.d(TAG, "Loaded llama_jni native library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load llama_jni native library: ${e.message}", e)
        }
            isInitialized = true
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    private var isModelLoaded = false
    private var modelPath: String? = null
    private var cachedModelPath: String? = null
    
    /**
     * Load model from file path
     * @param path Path to model file (e.g., /sdcard/models/qwen-2.5-coder-7b.q4.gguf)
     * @return true if model loaded successfully
     */
    fun loadModel(path: String): Boolean {
        return try {
            val sourceFile = File(path)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Model file does not exist: $path")
                return false
            }
            
            // Check if file is accessible from native code
            // Files in /storage/emulated/0/ may not be accessible due to scoped storage
            val accessiblePath = if (isPathAccessible(path)) {
                path
            } else {
                // Copy to cache directory where native code can access it
                copyToCache(sourceFile) ?: run {
                    Log.e(TAG, "Failed to copy model to cache directory")
                    return false
                }
            }
            
            Log.d(TAG, "Using model path: $accessiblePath")
            
            val success = loadModelNative(accessiblePath)
            if (success) {
                isModelLoaded = true
                modelPath = path
                cachedModelPath = accessiblePath
                Log.d(TAG, "Model loaded successfully: $accessiblePath")
            } else {
                Log.e(TAG, "Failed to load model: $accessiblePath")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if path is accessible from native code
     */
    private fun isPathAccessible(path: String): Boolean {
        return try {
            // Try to open the file - if it works, it's accessible
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                return false
            }
            
            // Check if it's in app's internal storage or cache
            val context = appContext
            if (context != null) {
                val internalDir = context.filesDir.absolutePath
                val cacheDir = context.cacheDir.absolutePath
                val externalCacheDir = context.externalCacheDir?.absolutePath
                
                return path.startsWith(internalDir) || 
                       path.startsWith(cacheDir) || 
                       (externalCacheDir != null && path.startsWith(externalCacheDir))
            }
            
            // If no context, assume not accessible if in /storage/emulated/
            !path.startsWith("/storage/emulated/")
        } catch (e: Exception) {
            Log.w(TAG, "Error checking path accessibility: ${e.message}")
            false
        }
    }
    
    /**
     * Copy model file to cache directory
     */
    private fun copyToCache(sourceFile: File): String? {
        val context = appContext ?: return null
        
        return try {
            val cacheDir = File(context.cacheDir, "models")
            cacheDir.mkdirs()
            
            val cachedFile = File(cacheDir, sourceFile.name)
            
            // Check if already copied and up-to-date
            if (cachedFile.exists() && cachedFile.length() == sourceFile.length() && 
                cachedFile.lastModified() >= sourceFile.lastModified()) {
                Log.d(TAG, "Using existing cached model: ${cachedFile.absolutePath}")
                return cachedFile.absolutePath
            }
            
            Log.d(TAG, "Copying model to cache: ${sourceFile.name} (${sourceFile.length()} bytes)")
            
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Set last modified to match source
            cachedFile.setLastModified(sourceFile.lastModified())
            
            Log.d(TAG, "Model copied to cache: ${cachedFile.absolutePath}")
            cachedFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model to cache: ${e.message}", e)
            null
        }
    }
    
    /**
     * Generate response from prompt
     * This function runs on a background thread to avoid blocking the UI
     * @param prompt Input prompt
     * @return Generated response text
     */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        if (!isModelLoaded) {
            // Try to load model from saved path
            val savedPath = getSavedModelPath()
            if (savedPath != null && !loadModel(savedPath)) {
                return@withContext "Error: Model not loaded. Please select a model file in settings."
            }
            if (!isModelLoaded) {
                return@withContext "Error: Model not loaded. Please select a model file in settings."
            }
        }
        
        return@withContext try {
            generateNative(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Exception generating response: ${e.message}", e)
            "Error generating response: ${e.message}"
        }
    }
    
    /**
     * Check if model is loaded
     */
    fun isLoaded(): Boolean = isModelLoaded
    
    /**
     * Get current model path (original path, not cached)
     */
    fun getModelPath(): String? = modelPath
    
    /**
     * Get cached model path (path used by native code)
     */
    fun getCachedModelPath(): String? = cachedModelPath
    
    /**
     * Unload current model
     */
    fun unloadModel() {
        if (isModelLoaded) {
            unloadModelNative()
            isModelLoaded = false
            modelPath = null
            cachedModelPath = null
            Log.d(TAG, "Model unloaded")
        }
    }
    
    /**
     * Clear cached model files
     */
    fun clearCache() {
        val context = appContext ?: return
        try {
            val cacheDir = File(context.cacheDir, "models")
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
                Log.d(TAG, "Cleared model cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache: ${e.message}", e)
        }
    }
    
    /**
     * Get saved model path from preferences
     */
    private fun getSavedModelPath(): String? {
        return try {
            val path = Preference.getString("localModelPath", "")
            if (path.isNotBlank()) path else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get saved model path: ${e.message}", e)
            null
        }
    }
    
    // Native methods
    private external fun loadModelNative(path: String): Boolean
    private external fun generateNative(prompt: String): String
    private external fun unloadModelNative()
}
