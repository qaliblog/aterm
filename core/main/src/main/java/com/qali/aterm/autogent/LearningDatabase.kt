package com.qali.aterm.autogent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import com.rk.libcommons.localDir
import java.io.File

/**
 * SQLite database for storing offline learned knowledge
 * Database location: /sdcard/aterm/model/model.db
 */
class LearningDatabase private constructor(private val modelName: String = "aterm-offline") {
    companion object {
        private const val DATABASE_VERSION = 1
        
        private const val TABLE_LEARNED_DATA = "learned_data"
        private const val COL_ID = "id"
        private const val COL_TYPE = "type"
        private const val COL_CONTENT = "content"
        private const val COL_POSITIVE_SCORE = "positive_score"
        private const val COL_SOURCE = "source"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_METADATA = "metadata"
        private const val COL_USER_PROMPT = "user_prompt" // For metadata generation relevance
        
        @Volatile
        private var INSTANCE: LearningDatabase? = null
        
        fun getInstance(modelName: String? = null): LearningDatabase {
            val dbName = modelName ?: "aterm-offline"
            return INSTANCE?.takeIf { it.modelName == dbName } ?: synchronized(this) {
                INSTANCE?.takeIf { it.modelName == dbName } ?: LearningDatabase(dbName).also { INSTANCE = it }
            }
        }
        
        fun getDatabasePath(modelName: String): String {
            // Store database in localDir()/aterm/model/ (same location as models and near distros)
            // This allows manual copying of database files if needed
            val modelDir = File(localDir(), "aterm/model").also {
                if (!it.exists()) {
                    it.mkdirs()
                }
            }
            
            // Use model name as database name (sanitize for filename)
            val sanitizedModelName = modelName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val dbFileName = "$sanitizedModelName.db"
            return File(modelDir, dbFileName).absolutePath
        }
    }
    
    private var database: SQLiteDatabase? = null
    
    private fun getWritableDatabase(): SQLiteDatabase {
        if (database == null || !database!!.isOpen) {
            val dbPath = getDatabasePath(modelName)
            val dbFile = File(dbPath)
            val isNewDatabase = !dbFile.exists()
            
            database = SQLiteDatabase.openDatabase(
                dbPath,
                null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
            )
            // Initialize database if it's new or if tables don't exist
            if (database != null) {
                val cursor = database!!.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='$TABLE_LEARNED_DATA'",
                    null
                )
                val tableExists = cursor.moveToFirst()
                cursor.close()
                
                if (!tableExists) {
                    onCreate(database!!)
                }
            }
        }
        return database!!
    }
    
    private fun getReadableDatabase(): SQLiteDatabase {
        return getWritableDatabase()
    }
    
    private fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_LEARNED_DATA (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_POSITIVE_SCORE INTEGER DEFAULT 1,
                $COL_SOURCE TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_METADATA TEXT,
                $COL_USER_PROMPT TEXT
            )
        """.trimIndent()
        
        db.execSQL(createTable)
        
        // Create indexes for fast retrieval
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_type ON $TABLE_LEARNED_DATA($COL_TYPE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_content ON $TABLE_LEARNED_DATA($COL_CONTENT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_score ON $TABLE_LEARNED_DATA($COL_POSITIVE_SCORE DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_prompt ON $TABLE_LEARNED_DATA($COL_USER_PROMPT)")
    }
    
    /**
     * Insert or update learned data
     */
    fun insertOrUpdateLearnedData(
        type: String,
        content: String,
        source: String,
        metadata: String? = null,
        incrementScore: Boolean = true,
        userPrompt: String? = null
    ): Long {
        val db = getWritableDatabase()
        
        // Check if similar content exists
        val cursor = db.query(
            TABLE_LEARNED_DATA,
            arrayOf(COL_ID, COL_POSITIVE_SCORE),
            "$COL_TYPE = ? AND $COL_CONTENT = ?",
            arrayOf(type, content),
            null,
            null,
            null
        )
        
        return if (cursor.moveToFirst()) {
            // Update existing entry
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
            val currentScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POSITIVE_SCORE))
            val newScore = if (incrementScore) currentScore + 1 else currentScore.coerceAtLeast(0)
            
            val values = ContentValues().apply {
                put(COL_POSITIVE_SCORE, newScore)
                put(COL_TIMESTAMP, System.currentTimeMillis())
                if (metadata != null) {
                    put(COL_METADATA, metadata)
                }
                if (userPrompt != null) {
                    put(COL_USER_PROMPT, userPrompt)
                }
            }
            
            db.update(TABLE_LEARNED_DATA, values, "$COL_ID = ?", arrayOf(id.toString()))
            cursor.close()
            id
        } else {
            // Insert new entry
            val values = ContentValues().apply {
                put(COL_TYPE, type)
                put(COL_CONTENT, content)
                put(COL_POSITIVE_SCORE, 1)
                put(COL_SOURCE, source)
                put(COL_TIMESTAMP, System.currentTimeMillis())
                if (metadata != null) {
                    put(COL_METADATA, metadata)
                }
                if (userPrompt != null) {
                    put(COL_USER_PROMPT, userPrompt)
                }
            }
            
            cursor.close()
            db.insert(TABLE_LEARNED_DATA, null, values)
        }
    }
    
    /**
     * Get learned data by type, ordered by positive score
     */
    fun getLearnedDataByType(type: String, limit: Int = 100, userPrompt: String? = null): List<LearnedDataEntry> {
        val db = getReadableDatabase()
        val entries = mutableListOf<LearnedDataEntry>()
        
        val selection = if (userPrompt != null) {
            "$COL_TYPE = ? AND ($COL_USER_PROMPT LIKE ? OR $COL_USER_PROMPT IS NULL)"
        } else {
            "$COL_TYPE = ?"
        }
        
        val selectionArgs = if (userPrompt != null) {
            arrayOf(type, "%$userPrompt%")
        } else {
            arrayOf(type)
        }
        
        val cursor = db.query(
            TABLE_LEARNED_DATA,
            arrayOf(COL_ID, COL_TYPE, COL_CONTENT, COL_POSITIVE_SCORE, COL_SOURCE, COL_TIMESTAMP, COL_METADATA, COL_USER_PROMPT),
            selection,
            selectionArgs,
            null,
            null,
            "$COL_POSITIVE_SCORE DESC",
            limit.toString()
        )
        
        while (cursor.moveToNext()) {
            entries.add(
                LearnedDataEntry(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    type = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                    content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
                    positiveScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POSITIVE_SCORE)),
                    source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    metadata = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_METADATA)),
                    userPrompt = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_USER_PROMPT))
                )
            )
        }
        
        cursor.close()
        return entries
    }
    
    /**
     * Get fixes with JSON format including reason
     */
    fun getFixesByKeywords(keywords: List<String>, limit: Int = 20): List<FixEntry> {
        val db = getReadableDatabase()
        val fixes = mutableListOf<FixEntry>()
        
        // Build search query for keywords
        val keywordConditions = keywords.joinToString(" OR ") { "$COL_CONTENT LIKE ? OR $COL_METADATA LIKE ?" }
        val selection = "$COL_TYPE = ? AND ($keywordConditions)"
        val selectionArgs = arrayOf(LearnedDataType.FIX_PATCH) + keywords.flatMap { listOf("%$it%", "%$it%") }
        
        val cursor = db.query(
            TABLE_LEARNED_DATA,
            arrayOf(COL_ID, COL_CONTENT, COL_POSITIVE_SCORE, COL_METADATA, COL_USER_PROMPT, COL_TIMESTAMP),
            selection,
            selectionArgs,
            null,
            null,
            "$COL_POSITIVE_SCORE DESC",
            limit.toString()
        )
        
        while (cursor.moveToNext()) {
            val metadataStr = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_METADATA))
            val content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT))
            
            // Parse JSON from metadata or content
            val fixData = parseFixJson(metadataStr, content)
            
            fixes.add(
                FixEntry(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    oldCode = fixData.oldCode,
                    newCode = fixData.newCode,
                    reason = fixData.reason,
                    keywords = keywords,
                    score = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POSITIVE_SCORE)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
                )
            )
        }
        
        cursor.close()
        return fixes
    }
    
    /**
     * Parse fix JSON from metadata or content
     */
    private fun parseFixJson(metadata: String?, content: String): FixData {
        // Try to parse JSON from metadata first
        if (metadata != null) {
            try {
                // Check if metadata contains JSON-like structure
                if (metadata.contains("\"old_code\"") || metadata.contains("\"new_code\"")) {
                    // Simple JSON parsing (can be enhanced with proper JSON library)
                    val oldCodeMatch = Regex("\"old_code\"\\s*:\\s*\"([^\"]+)\"").find(metadata)
                    val newCodeMatch = Regex("\"new_code\"\\s*:\\s*\"([^\"]+)\"").find(metadata)
                    val reasonMatch = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"").find(metadata)
                    
                    if (oldCodeMatch != null && newCodeMatch != null) {
                        return FixData(
                            oldCode = oldCodeMatch.groupValues[1],
                            newCode = newCodeMatch.groupValues[1],
                            reason = reasonMatch?.groupValues?.get(1) ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("LearningDatabase", "Error parsing JSON from metadata", e)
            }
        }
        
        // Fallback: parse from content format "OLD:\n...\n\nNEW:\n...\n\nREASON: ..."
        val oldMatch = Regex("OLD:\\s*\\n([\\s\\S]*?)\\n\\nNEW:").find(content)
        val newMatch = Regex("NEW:\\s*\\n([\\s\\S]*?)(?:\\n\\nREASON:|$)").find(content)
        val reasonMatch = Regex("REASON:\\s*([\\s\\S]*)").find(content)
        
        return FixData(
            oldCode = oldMatch?.groupValues?.get(1)?.trim() ?: "",
            newCode = newMatch?.groupValues?.get(1)?.trim() ?: "",
            reason = reasonMatch?.groupValues?.get(1)?.trim() ?: ""
        )
    }
    
    /**
     * Search learned data by content similarity
     */
    fun searchLearnedData(query: String, type: String? = null, limit: Int = 50): List<LearnedDataEntry> {
        val db = getReadableDatabase()
        val entries = mutableListOf<LearnedDataEntry>()
        
        val selection = if (type != null) {
            "$COL_TYPE = ? AND $COL_CONTENT LIKE ?"
        } else {
            "$COL_CONTENT LIKE ?"
        }
        
        val selectionArgs = if (type != null) {
            arrayOf(type, "%$query%")
        } else {
            arrayOf("%$query%")
        }
        
        val cursor = db.query(
            TABLE_LEARNED_DATA,
            arrayOf(COL_ID, COL_TYPE, COL_CONTENT, COL_POSITIVE_SCORE, COL_SOURCE, COL_TIMESTAMP, COL_METADATA, COL_USER_PROMPT),
            selection,
            selectionArgs,
            null,
            null,
            "$COL_POSITIVE_SCORE DESC",
            limit.toString()
        )
        
        while (cursor.moveToNext()) {
            entries.add(
                LearnedDataEntry(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    type = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
                    content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
                    positiveScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POSITIVE_SCORE)),
                    source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
                    metadata = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_METADATA)),
                    userPrompt = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_USER_PROMPT))
                )
            )
        }
        
        cursor.close()
        return entries
    }
    
    /**
     * Decrement score for negative feedback
     */
    fun decrementScore(id: Long) {
        val db = getWritableDatabase()
        val cursor = db.query(
            TABLE_LEARNED_DATA,
            arrayOf(COL_POSITIVE_SCORE),
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        
        if (cursor.moveToFirst()) {
            val currentScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POSITIVE_SCORE))
            val newScore = (currentScore - 1).coerceAtLeast(0)
            
            val values = ContentValues().apply {
                put(COL_POSITIVE_SCORE, newScore)
            }
            
            db.update(TABLE_LEARNED_DATA, values, "$COL_ID = ?", arrayOf(id.toString()))
        }
        
        cursor.close()
    }
    
    /**
     * Get all learned data for AutoAgent context
     */
    fun getAllLearnedDataForContext(limit: Int = 200): Map<String, List<LearnedDataEntry>> {
        val result = mutableMapOf<String, MutableList<LearnedDataEntry>>()
        
        val types = listOf(
            LearnedDataType.CODE_SNIPPET,
            LearnedDataType.API_USAGE,
            LearnedDataType.FIX_PATCH,
            LearnedDataType.METADATA_TRANSFORMATION
        )
        
        types.forEach { type ->
            result[type] = getLearnedDataByType(type, limit / types.size).toMutableList()
        }
        
        return result
    }
    
    fun close() {
        database?.close()
        database = null
    }
}

data class LearnedDataEntry(
    val id: Long,
    val type: String,
    val content: String,
    val positiveScore: Int,
    val source: String,
    val timestamp: Long,
    val metadata: String?,
    val userPrompt: String? = null
)

data class FixEntry(
    val id: Long,
    val oldCode: String,
    val newCode: String,
    val reason: String,
    val keywords: List<String>,
    val score: Int,
    val timestamp: Long
)

private data class FixData(
    val oldCode: String,
    val newCode: String,
    val reason: String
)

object LearnedDataType {
    const val CODE_SNIPPET = "code_snippet"
    const val API_USAGE = "api_usage"
    const val FIX_PATCH = "fix_patch"
    const val METADATA_TRANSFORMATION = "metadata_transformation"
}

object LearnedDataSource {
    const val NORMAL_FLOW = "normal_flow"
    const val REVERSE_FLOW = "reverse_flow"
    const val DEBUG_FEEDBACK = "debug_feedback"
}

// Extension function for safe string retrieval
private fun android.database.Cursor.getStringOrNull(columnIndex: Int): String? {
    return if (isNull(columnIndex)) null else getString(columnIndex)
}

