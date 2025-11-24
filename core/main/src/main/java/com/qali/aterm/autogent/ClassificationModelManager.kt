package com.qali.aterm.autogent

import android.os.Environment
import com.google.gson.Gson
import com.rk.settings.Preference
import java.io.File

/**
 * Manages text classification models for AutoAgent
 * Supports Mediapipe-style models
 */
object ClassificationModelManager {
    private val gson = Gson()
    private const val PREF_MODELS = "classification_models"
    private const val PREF_SELECTED_MODEL = "selected_classification_model"
    
    data class ClassificationModel(
        val id: String,
        val name: String,
        val description: String,
        val modelType: ModelType,
        val filePath: String? = null,
        val downloadUrl: String? = null,
        val isDownloaded: Boolean = false,
        val isBuiltIn: Boolean = false
    )
    
    enum class ModelType {
        MEDIAPIPE_BERT,
        UNIVERSAL_SENTENCE_ENCODER,
        TENSORFLOW_HUB,
        CUSTOM
    }
    
    // Predefined Mediapipe models
    // Note: Using working Mediapipe model URLs
    val builtInModels = listOf(
        ClassificationModel(
            id = "mediapipe_bert_en",
            name = "Mediapipe BERT English",
            description = "BERT-based text classifier optimized by Mediapipe for English text classification",
            modelType = ModelType.MEDIAPIPE_BERT,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_classifier/bert_classifier/float32/1/bert_classifier.tflite",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "mediapipe_bert_en_lite",
            name = "Mediapipe BERT English Lite",
            description = "Lightweight BERT text classifier for faster inference",
            modelType = ModelType.MEDIAPIPE_BERT,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_classifier/bert_classifier/float32/lite/bert_classifier.tflite",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "mediapipe_average_word_embedding",
            name = "Mediapipe Average Word Embedding",
            description = "Lightweight text classifier using average word embeddings",
            modelType = ModelType.MEDIAPIPE_BERT,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_classifier/average_word_embedding/float32/1/average_word_embedding.tflite",
            isBuiltIn = true
        )
    )
    
    /**
     * Get all available models (built-in + custom)
     */
    fun getAvailableModels(): List<ClassificationModel> {
        val customModels = getCustomModels()
        return builtInModels + customModels
    }
    
    /**
     * Get custom models from preferences
     */
    private fun getCustomModels(): List<ClassificationModel> {
        val modelsJson = Preference.getString(PREF_MODELS, "[]")
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<ClassificationModel>>() {}.type
            gson.fromJson<List<ClassificationModel>>(modelsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Save custom models
     */
    private fun saveCustomModels(models: List<ClassificationModel>) {
        val json = gson.toJson(models)
        Preference.setString(PREF_MODELS, json)
    }
    
    /**
     * Get selected model
     */
    fun getSelectedModel(): ClassificationModel? {
        val selectedId = Preference.getString(PREF_SELECTED_MODEL, "")
        if (selectedId.isEmpty()) return null
        
        val allModels = getAvailableModels()
        return allModels.find { it.id == selectedId }
    }
    
    /**
     * Set selected model
     */
    fun setSelectedModel(modelId: String) {
        Preference.setString(PREF_SELECTED_MODEL, modelId)
    }
    
    /**
     * Add custom model
     */
    fun addCustomModel(model: ClassificationModel): Boolean {
        val customModels = getCustomModels().toMutableList()
        
        // Check if model with same ID exists
        if (customModels.any { it.id == model.id }) {
            return false
        }
        
        customModels.add(model)
        saveCustomModels(customModels)
        return true
    }
    
    /**
     * Remove custom model
     */
    fun removeCustomModel(modelId: String): Boolean {
        val customModels = getCustomModels().toMutableList()
        val removed = customModels.removeAll { it.id == modelId }
        if (removed) {
            saveCustomModels(customModels)
            // If removed model was selected, clear selection
            if (getSelectedModel()?.id == modelId) {
                Preference.setString(PREF_SELECTED_MODEL, "")
            }
        }
        return removed
    }
    
    /**
     * Update model download status
     */
    fun updateModelDownloadStatus(modelId: String, filePath: String, isDownloaded: Boolean) {
        val customModels = getCustomModels().toMutableList()
        val modelIndex = customModels.indexOfFirst { it.id == modelId }
        
        if (modelIndex >= 0) {
            val model = customModels[modelIndex]
            customModels[modelIndex] = model.copy(
                filePath = filePath,
                isDownloaded = isDownloaded
            )
            saveCustomModels(customModels)
        }
    }
    
    /**
     * Get model file path
     * Models are stored in app-specific external storage to avoid permission issues
     * Falls back to /sdcard/aterm/model/ if external files dir is not available
     */
    fun getModelFilePath(modelId: String): String? {
        val model = getAvailableModels().find { it.id == modelId }
        return model?.filePath ?: model?.let {
            // Try app-specific external storage first (no permissions needed on Android 10+)
            val modelDir = try {
                val app = com.rk.libcommons.application
                if (app != null) {
                    val externalFilesDir = app.getExternalFilesDir(null)
                    if (externalFilesDir != null) {
                        File(externalFilesDir, "aterm/model").also {
                            if (!it.exists()) {
                                it.mkdirs()
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            } ?: run {
                // Fallback to public external storage (requires permissions)
                File(Environment.getExternalStorageDirectory(), "aterm/model").also {
                    if (!it.exists()) {
                        it.mkdirs()
                    }
                }
            }
            
            File(modelDir, "${model.id}.tflite").absolutePath
        }
    }
    
    /**
     * Check if model is available and ready
     */
    fun isModelReady(): Boolean {
        val selected = getSelectedModel() ?: return false
        if (selected.isBuiltIn && !selected.isDownloaded) {
            // Check if file exists
            val filePath = getModelFilePath(selected.id)
            return filePath != null && File(filePath).exists()
        }
        return selected.isDownloaded && selected.filePath != null && File(selected.filePath!!).exists()
    }
}

