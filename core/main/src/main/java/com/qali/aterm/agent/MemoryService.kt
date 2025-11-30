package com.qali.aterm.agent

import com.rk.libcommons.alpineHomeDir
import java.io.File

/**
 * Service for managing and summarizing memory for intent detection
 */
object MemoryService {
    private const val MEMORY_FILENAME = "GEMINI.md"
    private const val MEMORY_SECTION_HEADER = "## Gemini Added Memories"
    
    /**
     * Get memory file path
     */
    private fun getMemoryFile(): File {
        return File(alpineHomeDir(), MEMORY_FILENAME)
    }
    
    /**
     * Load all memories
     */
    fun loadMemories(): String {
        val memoryFile = getMemoryFile()
        return if (memoryFile.exists()) {
            memoryFile.readText()
        } else {
            ""
        }
    }
    
    /**
     * Get summarized memory for intent detection
     * Extracts key facts and context from memory
     */
    fun getSummarizedMemory(): String {
        val fullMemory = loadMemories()
        if (fullMemory.isEmpty()) {
            return ""
        }
        
        // Extract memory section
        val headerIndex = fullMemory.indexOf(MEMORY_SECTION_HEADER)
        if (headerIndex == -1) {
            return ""
        }
        
        val memorySection = fullMemory.substring(headerIndex)
        val lines = memorySection.lines()
        
        // Extract memory items (lines starting with -)
        val memoryItems = lines.filter { it.trim().startsWith("-") }
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
            .take(20) // Limit to most recent 20 items
        
        if (memoryItems.isEmpty()) {
            return ""
        }
        
        return buildString {
            appendLine("## User Context from Memory")
            appendLine()
            appendLine("The following information has been remembered about the user and their preferences:")
            appendLine()
            memoryItems.forEach { item ->
                appendLine("- $item")
            }
            appendLine()
            appendLine("Use this context to better understand user intent and provide personalized responses.")
        }
    }
    
    /**
     * Check if memory exists
     */
    fun hasMemory(): Boolean {
        val memoryFile = getMemoryFile()
        return memoryFile.exists() && memoryFile.readText().contains(MEMORY_SECTION_HEADER)
    }
}
