package com.qali.aterm.agent.utils

import java.io.File
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * Agent memory system with summarization
 * Keeps memory under 80 lines and supports search/append
 */
object AgentMemory {
    
    private const val MAX_MEMORY_LINES = 80
    private const val MEMORY_FILE = ".agent_memory.json"
    
    /**
     * Memory entry
     */
    data class MemoryEntry(
        val id: String,
        val timestamp: Long,
        val category: String,
        val content: String,
        val tags: List<String> = emptyList()
    )
    
    /**
     * Memory summary
     */
    data class MemorySummary(
        val entries: List<MemoryEntry>,
        val summary: String,
        val totalLines: Int
    )
    
    /**
     * Load memory from file
     */
    fun loadMemory(workspaceRoot: String): MemorySummary {
        val memoryFile = File(workspaceRoot, MEMORY_FILE)
        if (!memoryFile.exists()) {
            return MemorySummary(emptyList(), "", 0)
        }
        
        return try {
            val json = JSONObject(memoryFile.readText())
            parseMemoryFromJson(json)
        } catch (e: Exception) {
            Log.e("AgentMemory", "Failed to load memory: ${e.message}", e)
            MemorySummary(emptyList(), "", 0)
        }
    }
    
    /**
     * Save memory to file
     */
    fun saveMemory(summary: MemorySummary, workspaceRoot: String) {
        val memoryFile = File(workspaceRoot, MEMORY_FILE)
        val json = memoryToJson(summary)
        memoryFile.writeText(json.toString(2))
    }
    
    /**
     * Add memory entry
     */
    fun addMemory(
        category: String,
        content: String,
        tags: List<String> = emptyList(),
        workspaceRoot: String
    ) {
        val current = loadMemory(workspaceRoot)
        val newEntry = MemoryEntry(
            id = "mem_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            category = category,
            content = content,
            tags = tags
        )
        
        val updatedEntries = (current.entries + newEntry).sortedByDescending { it.timestamp }
        val summarized = summarizeMemory(updatedEntries)
        
        saveMemory(summarized, workspaceRoot)
    }
    
    /**
     * Search memory
     */
    fun searchMemory(
        query: String,
        workspaceRoot: String
    ): List<MemoryEntry> {
        val memory = loadMemory(workspaceRoot)
        val queryLower = query.lowercase()
        
        return memory.entries.filter { entry ->
            entry.content.lowercase().contains(queryLower) ||
            entry.category.lowercase().contains(queryLower) ||
            entry.tags.any { it.lowercase().contains(queryLower) }
        }
    }
    
    /**
     * Summarize memory to keep under MAX_MEMORY_LINES
     */
    private fun summarizeMemory(entries: List<MemoryEntry>): MemorySummary {
        if (entries.isEmpty()) {
            return MemorySummary(emptyList(), "", 0)
        }
        
        // Calculate total lines
        val totalLines = entries.sumOf { entry ->
            entry.content.lines().size + 2 // +2 for category and tags
        }
        
        // If under limit, return as-is
        if (totalLines <= MAX_MEMORY_LINES) {
            val summary = buildSummary(entries)
            return MemorySummary(entries, summary, totalLines)
        }
        
        // Need to summarize - keep most recent entries and summarize older ones
        val recentCount = MAX_MEMORY_LINES / 4 // Keep 25% as recent entries
        val recentEntries = entries.take(recentCount)
        val oldEntries = entries.drop(recentCount)
        
        // Summarize old entries
        val summarizedOld = oldEntries.groupBy { it.category }.map { (category, categoryEntries) ->
            val combinedContent = categoryEntries.joinToString("\n") { it.content }
            val allTags = categoryEntries.flatMap { it.tags }.distinct()
            
            MemoryEntry(
                id = "summary_${category}_${System.currentTimeMillis()}",
                timestamp = categoryEntries.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                category = category,
                content = summarizeContent(combinedContent, MAX_MEMORY_LINES / 2),
                tags = allTags
            )
        }
        
        val finalEntries = (recentEntries + summarizedOld).sortedByDescending { it.timestamp }
        val summary = buildSummary(finalEntries)
        val finalLines = finalEntries.sumOf { it.content.lines().size + 2 }
        
        return MemorySummary(finalEntries, summary, finalLines)
    }
    
    /**
     * Summarize content to fit within line limit
     */
    private fun summarizeContent(content: String, maxLines: Int): String {
        val lines = content.lines()
        if (lines.size <= maxLines) {
            return content
        }
        
        // Keep first and last lines, summarize middle
        val keepFirst = maxLines / 3
        val keepLast = maxLines / 3
        val summarizeMiddle = maxLines - keepFirst - keepLast
        
        val first = lines.take(keepFirst)
        val last = lines.takeLast(keepLast)
        val middle = lines.drop(keepFirst).dropLast(keepLast)
        
        val middleSummary = if (middle.isNotEmpty()) {
            val middleText = middle.joinToString("\n")
            // Simple summarization: take key sentences
            val sentences = middleText.split(Regex("[.!?]\\s+"))
            sentences.take(summarizeMiddle).joinToString(". ") + "."
        } else {
            ""
        }
        
        return buildString {
            appendLine(first.joinToString("\n"))
            if (middleSummary.isNotEmpty()) {
                appendLine("... [summarized] ...")
                appendLine(middleSummary)
            }
            appendLine(last.joinToString("\n"))
        }
    }
    
    /**
     * Build summary text
     */
    private fun buildSummary(entries: List<MemoryEntry>): String {
        if (entries.isEmpty()) return ""
        
        val categories = entries.groupBy { it.category }
        return buildString {
            appendLine("Memory Summary (${entries.size} entries):")
            categories.forEach { (category, categoryEntries) ->
                appendLine("  $category: ${categoryEntries.size} entries")
            }
        }
    }
    
    /**
     * Convert memory to JSON
     */
    private fun memoryToJson(summary: MemorySummary): JSONObject {
        val json = JSONObject()
        json.put("summary", summary.summary)
        json.put("totalLines", summary.totalLines)
        
        val entriesArray = JSONArray()
        summary.entries.forEach { entry ->
            entriesArray.put(entryToJson(entry))
        }
        json.put("entries", entriesArray)
        
        return json
    }
    
    /**
     * Convert entry to JSON
     */
    private fun entryToJson(entry: MemoryEntry): JSONObject {
        val json = JSONObject()
        json.put("id", entry.id)
        json.put("timestamp", entry.timestamp)
        json.put("category", entry.category)
        json.put("content", entry.content)
        json.put("tags", JSONArray(entry.tags))
        return json
    }
    
    /**
     * Parse memory from JSON
     */
    private fun parseMemoryFromJson(json: JSONObject): MemorySummary {
        val summary = json.optString("summary", "")
        val totalLines = json.optInt("totalLines", 0)
        
        val entriesArray = json.getJSONArray("entries")
        val entries = mutableListOf<MemoryEntry>()
        
        for (i in 0 until entriesArray.length()) {
            val entryObj = entriesArray.getJSONObject(i)
            val tagsArray = entryObj.getJSONArray("tags")
            val tags = mutableListOf<String>()
            for (j in 0 until tagsArray.length()) {
                tags.add(tagsArray.getString(j))
            }
            
            entries.add(MemoryEntry(
                id = entryObj.getString("id"),
                timestamp = entryObj.getLong("timestamp"),
                category = entryObj.getString("category"),
                content = entryObj.getString("content"),
                tags = tags
            ))
        }
        
        return MemorySummary(entries, summary, totalLines)
    }
    
    /**
     * Get memory for context (formatted for AI)
     */
    fun getMemoryForContext(workspaceRoot: String, query: String? = null): String {
        val memory = if (query != null) {
            val searchResults = searchMemory(query, workspaceRoot)
            MemorySummary(searchResults, "", searchResults.sumOf { it.content.lines().size + 2 })
        } else {
            loadMemory(workspaceRoot)
        }
        
        if (memory.entries.isEmpty()) {
            return ""
        }
        
        return buildString {
            appendLine("## Agent Memory")
            appendLine(memory.summary)
            appendLine()
            memory.entries.take(10).forEach { entry ->
                appendLine("### ${entry.category}")
                appendLine(entry.content.take(200)) // Limit each entry
                if (entry.tags.isNotEmpty()) {
                    appendLine("Tags: ${entry.tags.joinToString(", ")}")
                }
                appendLine()
            }
        }
    }
}
