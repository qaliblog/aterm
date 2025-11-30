package com.qali.aterm.agent

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rk.libcommons.application
import com.qali.aterm.ui.screens.agent.AgentMessage
import java.lang.reflect.Type

data class SerializableFileDiff(
    val filePath: String,
    val oldContent: String,
    val newContent: String,
    val isNewFile: Boolean = false
) {
    fun toFileDiff(): com.qali.aterm.ui.screens.agent.FileDiff = 
        com.qali.aterm.ui.screens.agent.FileDiff(filePath, oldContent, newContent, isNewFile)
    
    companion object {
        fun fromFileDiff(diff: com.qali.aterm.ui.screens.agent.FileDiff?): SerializableFileDiff? {
            return diff?.let { SerializableFileDiff(it.filePath, it.oldContent, it.newContent, it.isNewFile) }
        }
    }
}

data class SerializableAgentMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val fileDiff: SerializableFileDiff? = null
) {
    fun toAgentMessage(): AgentMessage = AgentMessage(
        text = text,
        isUser = isUser,
        timestamp = timestamp,
        fileDiff = fileDiff?.toFileDiff()
    )
    
    companion object {
        fun fromAgentMessage(msg: AgentMessage): SerializableAgentMessage {
            return SerializableAgentMessage(
                text = msg.text,
                isUser = msg.isUser,
                timestamp = msg.timestamp,
                fileDiff = SerializableFileDiff.fromFileDiff(msg.fileDiff)
            )
        }
    }
}

data class SessionMetadata(
    val workspaceRoot: String,
    val isPaused: Boolean,
    val lastPrompt: String? = null,
    val currentResponseText: String? = null
)

object HistoryPersistenceService {
    private const val PREFS_NAME = "agent_history"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_METADATA = "session_metadata"
    private val gson = Gson()
    
    private val prefs: SharedPreferences
        get() = application!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val sessionsType: Type = object : TypeToken<Map<String, String>>() {}.type
    private val messagesType: Type = object : TypeToken<List<SerializableAgentMessage>>() {}.type
    private val metadataType: Type = object : TypeToken<Map<String, SessionMetadata>>() {}.type
    
    /**
     * Save chat history for a session
     */
    fun saveHistory(sessionId: String, messages: List<AgentMessage>) {
        try {
            val serializableMessages = messages.map { SerializableAgentMessage.fromAgentMessage(it) }
            val messagesJson = gson.toJson(serializableMessages)
            
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = try {
                gson.fromJson<Map<String, String>>(sessionsJson, sessionsType) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
            
            val updatedSessions = sessions.toMutableMap()
            updatedSessions[sessionId] = messagesJson
            
            prefs.edit()
                .putString(KEY_SESSIONS, gson.toJson(updatedSessions))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to save history", e)
        }
    }
    
    /**
     * Load chat history for a session
     */
    fun loadHistory(sessionId: String): List<AgentMessage> {
        return try {
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = gson.fromJson<Map<String, String>>(sessionsJson, sessionsType) ?: emptyMap()
            val messagesJson = sessions[sessionId] ?: return emptyList()
            
            val serializableMessages = gson.fromJson<List<SerializableAgentMessage>>(messagesJson, messagesType)
                ?: return emptyList()
            
            serializableMessages.map { it.toAgentMessage() }
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to load history", e)
            emptyList()
        }
    }
    
    /**
     * Get all session IDs
     */
    fun getAllSessionIds(): List<String> {
        return try {
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = gson.fromJson<Map<String, String>>(sessionsJson, sessionsType) ?: emptyMap()
            sessions.keys.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Delete history for a session
     */
    fun deleteHistory(sessionId: String) {
        try {
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = gson.fromJson<MutableMap<String, String>>(sessionsJson, sessionsType) ?: mutableMapOf()
            sessions.remove(sessionId)
            
            prefs.edit()
                .putString(KEY_SESSIONS, gson.toJson(sessions))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to delete history", e)
        }
    }
    
    /**
     * Clear all history
     */
    fun clearAllHistory() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Save session metadata (workspace, pause state, etc.)
     */
    fun saveSessionMetadata(sessionId: String, metadata: SessionMetadata) {
        try {
            val metadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
            val allMetadata = try {
                gson.fromJson<Map<String, SessionMetadata>>(metadataJson, metadataType) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
            
            val updatedMetadata = allMetadata.toMutableMap()
            updatedMetadata[sessionId] = metadata
            
            prefs.edit()
                .putString(KEY_METADATA, gson.toJson(updatedMetadata))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to save session metadata", e)
        }
    }
    
    /**
     * Load session metadata
     */
    fun loadSessionMetadata(sessionId: String): SessionMetadata? {
        return try {
            val metadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
            val allMetadata = gson.fromJson<Map<String, SessionMetadata>>(metadataJson, metadataType) ?: emptyMap()
            allMetadata[sessionId]
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to load session metadata", e)
            null
        }
    }
    
    /**
     * Delete session metadata
     */
    fun deleteSessionMetadata(sessionId: String) {
        try {
            val metadataJson = prefs.getString(KEY_METADATA, "{}") ?: "{}"
            val allMetadata = try {
                gson.fromJson<MutableMap<String, SessionMetadata>>(metadataJson, metadataType) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            allMetadata.remove(sessionId)
            
            prefs.edit()
                .putString(KEY_METADATA, gson.toJson(allMetadata))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to delete session metadata", e)
        }
    }
}
