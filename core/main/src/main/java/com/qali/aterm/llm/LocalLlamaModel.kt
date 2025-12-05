package com.qali.aterm.llm

import android.util.Log
import com.rk.settings.Preference
import java.io.File

/**
 * Local LLM model using llama.cpp
 * JNI bridge for native llama.cpp library
 */
object LocalLlamaModel {
    private const val TAG = "LocalLlamaModel"
    
    init {
        try {
            System.loadLibrary("llama")
            Log.d(TAG, "Loaded llama native library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load llama native library: ${e.message}", e)
        }
    }
    
    private var isModelLoaded = false
    private var modelPath: String? = null
    
    /**
     * Load model from file path
     * @param path Path to model file (e.g., /sdcard/models/qwen-2.5-coder-7b.q4.gguf)
     * @return true if model loaded successfully
     */
    fun loadModel(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "Model file does not exist: $path")
                return false
            }
            
            val success = loadModelNative(path)
            if (success) {
                isModelLoaded = true
                modelPath = path
                Log.d(TAG, "Model loaded successfully: $path")
            } else {
                Log.e(TAG, "Failed to load model: $path")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${e.message}", e)
            false
        }
    }
    
    /**
     * Generate response from prompt
     * @param prompt Input prompt
     * @return Generated response text
     */
    fun generate(prompt: String): String {
        if (!isModelLoaded) {
            // Try to load model from saved path
            val savedPath = getSavedModelPath()
            if (savedPath != null && !loadModel(savedPath)) {
                return "Error: Model not loaded. Please select a model file in settings."
            }
            if (!isModelLoaded) {
                return "Error: Model not loaded. Please select a model file in settings."
            }
        }
        
        return try {
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
     * Get current model path
     */
    fun getModelPath(): String? = modelPath
    
    /**
     * Unload current model
     */
    fun unloadModel() {
        if (isModelLoaded) {
            unloadModelNative()
            isModelLoaded = false
            modelPath = null
            Log.d(TAG, "Model unloaded")
        }
    }
    
    /**
     * Get saved model path from preferences
     */
    private fun getSavedModelPath(): String? {
        return try {
            val prefs = android.app.ActivityThread.currentApplication()
                ?.getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE)
            prefs?.getString("localModelPath", null)
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
