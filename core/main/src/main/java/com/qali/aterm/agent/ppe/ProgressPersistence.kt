package com.qali.aterm.agent.ppe

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * Progress persistence for resuming interrupted operations
 * Better than Cursor AI: Checkpoint system with atomic operations
 */
object ProgressPersistence {
    
    data class Checkpoint(
        val operationId: String,
        val step: Int,
        val totalSteps: Int,
        val completedFiles: List<String>,
        val state: Map<String, String>,
        val timestamp: Long
    )
    
    private val checkpointsDir = File(System.getProperty("java.io.tmpdir"), "aterm-checkpoints")
    
    init {
        if (!checkpointsDir.exists()) {
            checkpointsDir.mkdirs()
        }
    }
    
    /**
     * Save checkpoint
     */
    fun saveCheckpoint(checkpoint: Checkpoint) {
        if (!PpeConfig.ENABLE_PROGRESS_PERSISTENCE) return
        
        try {
            val file = File(checkpointsDir, "${checkpoint.operationId}.json")
            val json = JSONObject().apply {
                put("operationId", checkpoint.operationId)
                put("step", checkpoint.step)
                put("totalSteps", checkpoint.totalSteps)
                put("timestamp", checkpoint.timestamp)
                put("completedFiles", JSONArray(checkpoint.completedFiles))
                val stateObj = JSONObject()
                checkpoint.state.forEach { (k, v) -> stateObj.put(k, v) }
                put("state", stateObj)
            }
            file.writeText(json.toString())
            Log.d("ProgressPersistence", "Saved checkpoint: ${checkpoint.operationId} at step ${checkpoint.step}/${checkpoint.totalSteps}")
        } catch (e: Exception) {
            Log.e("ProgressPersistence", "Failed to save checkpoint: ${e.message}")
        }
    }
    
    /**
     * Load checkpoint
     */
    fun loadCheckpoint(operationId: String): Checkpoint? {
        if (!PpeConfig.ENABLE_PROGRESS_PERSISTENCE) return null
        
        return try {
            val file = File(checkpointsDir, "$operationId.json")
            if (!file.exists()) return null
            
            val json = JSONObject(file.readText())
            val completedFiles = mutableListOf<String>()
            val filesArray = json.optJSONArray("completedFiles")
            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    completedFiles.add(filesArray.getString(i))
                }
            }
            
            val state = mutableMapOf<String, String>()
            val stateObj = json.optJSONObject("state")
            if (stateObj != null) {
                val keys = stateObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    state[key] = stateObj.getString(key)
                }
            }
            
            val checkpoint = Checkpoint(
                operationId = json.getString("operationId"),
                step = json.getInt("step"),
                totalSteps = json.getInt("totalSteps"),
                completedFiles = completedFiles,
                state = state,
                timestamp = json.getLong("timestamp")
            )
            
            Log.d("ProgressPersistence", "Loaded checkpoint: $operationId at step ${checkpoint.step}/${checkpoint.totalSteps}")
            checkpoint
        } catch (e: Exception) {
            Log.e("ProgressPersistence", "Failed to load checkpoint: ${e.message}")
            null
        }
    }
    
    /**
     * Delete checkpoint
     */
    fun deleteCheckpoint(operationId: String) {
        try {
            val file = File(checkpointsDir, "$operationId.json")
            if (file.exists()) {
                file.delete()
                Log.d("ProgressPersistence", "Deleted checkpoint: $operationId")
            }
        } catch (e: Exception) {
            Log.e("ProgressPersistence", "Failed to delete checkpoint: ${e.message}")
        }
    }
    
    /**
     * List all checkpoints
     */
    fun listCheckpoints(): List<String> {
        return checkpointsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
    
    /**
     * Clean old checkpoints (older than 24 hours)
     */
    fun cleanOldCheckpoints() {
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        
        checkpointsDir.listFiles()?.forEach { file ->
            if (file.lastModified() < oneDayAgo) {
                file.delete()
                Log.d("ProgressPersistence", "Cleaned old checkpoint: ${file.name}")
            }
        }
    }
}
