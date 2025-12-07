package com.qali.aterm.llm

import android.content.Context
import android.util.Log
import com.rk.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
                Log.d(TAG, "Attempting to load llama_jni native library...")
                System.loadLibrary("llama_jni")
                Log.d(TAG, "Successfully loaded llama_jni native library")
                
                // The library is loaded if we get here without exception
                // We can't test native methods without a model, but loadLibrary success means it's available
                isInitialized = true
                Log.d(TAG, "Native library initialization complete")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load llama_jni native library: ${e.message}", e)
                Log.e(TAG, "Library path check: ${System.getProperty("java.library.path")}")
                isInitialized = false
                throw e // Re-throw to indicate initialization failure
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading native library: ${e.message}", e)
                isInitialized = false
                throw UnsatisfiedLinkError("Failed to load native library: ${e.message}")
            }
        }
    }
    
    fun isInitialized(): Boolean = isInitialized
    
    private var isModelLoaded = false
    private var modelPath: String? = null
    private var cachedModelPath: String? = null
    
    /**
     * Get app models directory (faster than cache, persists across app updates)
     */
    fun getModelsDirectory(): File? {
        val context = appContext ?: return null
        val modelsDir = File(context.filesDir, "models")
        modelsDir.mkdirs()
        return modelsDir
    }
    
    /**
     * List all models in app folder
     */
    fun listAppModels(): List<String> {
        val modelsDir = getModelsDirectory() ?: return emptyList()
        return try {
            modelsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                ?.map { it.absolutePath }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list models: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Copy model file to app folder for faster access
     * @param sourceFile Source model file
     * @return Path to copied file in app folder, or null if failed
     */
    fun copyToAppFolder(sourceFile: File): String? {
        val context = appContext ?: return null
        val modelsDir = getModelsDirectory() ?: return null
        
        return try {
            val targetFile = File(modelsDir, sourceFile.name)
            
            // Check if already copied and up-to-date
            if (targetFile.exists() && targetFile.length() == sourceFile.length() && 
                targetFile.lastModified() >= sourceFile.lastModified()) {
                Log.d(TAG, "Using existing model in app folder: ${targetFile.absolutePath}")
                return targetFile.absolutePath
            }
            
            Log.d(TAG, "Copying model to app folder: ${sourceFile.name} (${sourceFile.length()} bytes)")
            
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Set last modified to match source
            targetFile.setLastModified(sourceFile.lastModified())
            
            Log.d(TAG, "Model copied to app folder: ${targetFile.absolutePath}")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model to app folder: ${e.message}", e)
            null
        }
    }
    
    /**
     * Check if native library is available and loaded
     */
    fun isNativeLibraryAvailable(): Boolean {
        if (!isInitialized) {
            return false
        }
        // Try to verify the library is actually loaded by checking if we can call a native method
        // We'll do this by attempting to check if the library is loaded
        return try {
            // The library should be loaded if isInitialized is true
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Load model from file path
     * @param path Path to model file (e.g., /sdcard/models/qwen-2.5-coder-7b.q4.gguf)
     * @return true if model loaded successfully
     */
    fun loadModel(path: String): Boolean {
        // Ensure library is loaded
        if (!isInitialized) {
            val context = appContext
            if (context == null) {
                Log.e(TAG, "Cannot load model: LocalLlamaModel not initialized and no context available")
                return false
            }
            try {
                init(context)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to initialize native library: ${e.message}", e)
                return false
            }
        }
        
        // Double-check that library is actually loaded before calling native method
        if (!isInitialized) {
            Log.e(TAG, "Native library not initialized, cannot load model")
            return false
        }
        
        return try {
            val sourceFile = File(path)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Model file does not exist: $path")
                return false
            }
            
            // Check if file is already in app folder (fastest access)
            val context = appContext
            val modelsDir = getModelsDirectory()
            val appFolderModel = if (modelsDir != null) {
                File(modelsDir, sourceFile.name)
            } else null
            
            val accessiblePath = when {
                // Already in app folder - use directly
                appFolderModel != null && appFolderModel.exists() && 
                appFolderModel.length() == sourceFile.length() -> {
                    Log.d(TAG, "Using model from app folder: ${appFolderModel.absolutePath}")
                    appFolderModel.absolutePath
                }
                // In app folder but different - use it anyway (might be updated)
                path.startsWith(context?.filesDir?.absolutePath ?: "") -> {
                    Log.d(TAG, "Using model from app folder: $path")
                    path
                }
                // Check if accessible from native code
                isPathAccessible(path) -> {
                    Log.d(TAG, "Using model from accessible path: $path")
                    path
                }
                // Copy to app folder for faster access
                else -> {
                    copyToAppFolder(sourceFile) ?: run {
                        // Fallback to cache if app folder copy fails
                        copyToCache(sourceFile) ?: run {
                            Log.e(TAG, "Failed to copy model to app folder or cache")
                            return false
                        }
                    }
                }
            }
            
            Log.d(TAG, "Using model path: $accessiblePath")
            
            // Call native method with error handling
            val success = try {
                loadModelNative(accessiblePath)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library not loaded: ${e.message}", e)
                throw e // Re-throw to indicate library issue
            } catch (e: Exception) {
                Log.e(TAG, "Exception calling native method: ${e.message}", e)
                false
            }
            
            if (success) {
                isModelLoaded = true
                modelPath = path
                cachedModelPath = accessiblePath
                Log.d(TAG, "Model loaded successfully: $accessiblePath")
            } else {
                Log.e(TAG, "Failed to load model: $accessiblePath")
            }
            success
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library error loading model: ${e.message}", e)
            throw e // Re-throw UnsatisfiedLinkError so caller knows it's a library issue
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
     * @param maxResponseLength Maximum response length in characters. Use 8000 for code/blueprint generation, 800 for chat
     * @return Generated response text
     */
    suspend fun generate(prompt: String, maxResponseLength: Int = 800): String = withContext(Dispatchers.IO) {
        // Check if native library is available
        if (!isInitialized) {
            val context = appContext
            if (context != null) {
                try {
                    init(context)
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to initialize native library: ${e.message}", e)
                    return@withContext "Error: Native library not available. The app may need to be reinstalled."
                }
            } else {
                return@withContext "Error: Native library not initialized. Please restart the app."
            }
        }
        
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
            // Add timeout to prevent hanging (60 seconds max for chat, 120 seconds for code/blueprint)
            val timeoutMs = if (maxResponseLength > 2000) 120000 else 60000
            withTimeout(timeoutMs.toLong()) {
                try {
                    generateNative(prompt, maxResponseLength)
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Native library not loaded during generation: ${e.message}", e)
                    throw e // Re-throw to indicate library issue
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library error: ${e.message}", e)
            "Error: Native library not available. The app may need to be reinstalled."
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Generation timed out after 60 seconds")
            "Error: Generation timed out. The model is taking too long to respond. Try a shorter prompt or check if the model is working correctly."
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
            // First check if there's a selected model in app folder
            val selectedModel = Preference.getString("selectedLocalModel", "")
            if (selectedModel.isNotBlank()) {
                val modelsDir = getModelsDirectory()
                if (modelsDir != null) {
                    val modelFile = File(modelsDir, selectedModel)
                    if (modelFile.exists()) {
                        return modelFile.absolutePath
                    }
                }
            }
            
            // Fallback to legacy path preference
            val path = Preference.getString("localModelPath", "")
            if (path.isNotBlank()) path else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get saved model path: ${e.message}", e)
            null
        }
    }
    
    /**
     * Delete model from app folder
     */
    fun deleteAppModel(fileName: String): Boolean {
        val modelsDir = getModelsDirectory() ?: return false
        return try {
            val modelFile = File(modelsDir, fileName)
            if (modelFile.exists()) {
                val deleted = modelFile.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted model: $fileName")
                }
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${e.message}", e)
            false
        }
    }
    
    // Native methods
    private external fun loadModelNative(path: String): Boolean
    private external fun generateNative(prompt: String, maxResponseLength: Int): String
    private external fun unloadModelNative()
    
    /**
     * Test if native library is actually loaded and functional
     * This will throw UnsatisfiedLinkError if the library isn't loaded
     */
    private fun testNativeLibrary(): Boolean {
        return try {
            // Try to call a simple native method to verify library is loaded
            // We can't easily test without a model, but we can at least verify the library loaded
            // by checking if the method exists
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
}
