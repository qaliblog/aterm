package com.qali.aterm.agent.ppe

import android.util.Log
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/**
 * Manages allow list for commands/tools
 * Better than Cursor AI: Persistent allow list with pattern matching
 */
object AllowListManager {
    
    private val allowListFile = File(System.getProperty("java.io.tmpdir"), "aterm-allowlist.json")
    private val allowList = mutableSetOf<String>()
    private val allowPatterns = mutableListOf<Regex>()
    
    init {
        loadAllowList()
    }
    
    /**
     * Check if a tool call is in the allow list
     */
    fun isAllowed(functionCall: com.qali.aterm.agent.core.FunctionCall): Boolean {
        when (functionCall.name) {
            "shell" -> {
                val command = functionCall.args["command"] as? String ?: ""
                // Check exact match
                if (allowList.contains(command)) {
                    return true
                }
                // Check pattern match
                return allowPatterns.any { it.matches(command) }
            }
            else -> {
                // For other tools, check if tool name + args hash is allowed
                val key = "${functionCall.name}:${functionCall.args.hashCode()}"
                return allowList.contains(key)
            }
        }
    }
    
    /**
     * Add command to allow list
     */
    fun addToAllowList(functionCall: com.qali.aterm.agent.core.FunctionCall, pattern: Boolean = false) {
        when (functionCall.name) {
            "shell" -> {
                val command = functionCall.args["command"] as? String ?: ""
                if (pattern) {
                    // Add as pattern
                    try {
                        val regex = Regex(command.replace("*", ".*"))
                        allowPatterns.add(regex)
                        Log.d("AllowListManager", "Added pattern to allow list: $command")
                    } catch (e: Exception) {
                        Log.e("AllowListManager", "Failed to add pattern: ${e.message}")
                        // Fallback to exact match
                        allowList.add(command)
                    }
                } else {
                    // Add exact command
                    allowList.add(command)
                    Log.d("AllowListManager", "Added command to allow list: $command")
                }
            }
            else -> {
                val key = "${functionCall.name}:${functionCall.args.hashCode()}"
                allowList.add(key)
                Log.d("AllowListManager", "Added tool to allow list: $key")
            }
        }
        saveAllowList()
    }
    
    /**
     * Remove from allow list
     */
    fun removeFromAllowList(functionCall: com.qali.aterm.agent.core.FunctionCall) {
        when (functionCall.name) {
            "shell" -> {
                val command = functionCall.args["command"] as? String ?: ""
                allowList.remove(command)
                allowPatterns.removeAll { it.pattern == command.replace("*", ".*") }
            }
            else -> {
                val key = "${functionCall.name}:${functionCall.args.hashCode()}"
                allowList.remove(key)
            }
        }
        saveAllowList()
    }
    
    /**
     * Get all allowed commands
     */
    fun getAllowedCommands(): List<String> {
        return allowList.filter { !it.contains(":") }.sorted()
    }
    
    /**
     * Get all allowed patterns
     */
    fun getAllowedPatterns(): List<String> {
        return allowPatterns.map { it.pattern.replace(".*", "*") }
    }
    
    /**
     * Clear allow list
     */
    fun clearAllowList() {
        allowList.clear()
        allowPatterns.clear()
        saveAllowList()
    }
    
    private fun loadAllowList() {
        if (!allowListFile.exists()) return
        
        try {
            val json = JSONObject(allowListFile.readText())
            
            // Load exact matches
            val commandsArray = json.optJSONArray("commands")
            if (commandsArray != null) {
                for (i in 0 until commandsArray.length()) {
                    allowList.add(commandsArray.getString(i))
                }
            }
            
            // Load patterns
            val patternsArray = json.optJSONArray("patterns")
            if (patternsArray != null) {
                for (i in 0 until patternsArray.length()) {
                    try {
                        val pattern = patternsArray.getString(i)
                        allowPatterns.add(Regex(pattern.replace("*", ".*")))
                    } catch (e: Exception) {
                        Log.e("AllowListManager", "Failed to load pattern: ${e.message}")
                    }
                }
            }
            
            Log.d("AllowListManager", "Loaded ${allowList.size} commands and ${allowPatterns.size} patterns")
        } catch (e: Exception) {
            Log.e("AllowListManager", "Failed to load allow list: ${e.message}")
        }
    }
    
    private fun saveAllowList() {
        try {
            val json = JSONObject().apply {
                put("commands", JSONArray(allowList.filter { !it.contains(":") }))
                put("patterns", JSONArray(allowPatterns.map { it.pattern.replace(".*", "*") }))
            }
            allowListFile.writeText(json.toString())
            Log.d("AllowListManager", "Saved allow list: ${allowList.size} commands, ${allowPatterns.size} patterns")
        } catch (e: Exception) {
            Log.e("AllowListManager", "Failed to save allow list: ${e.message}")
        }
    }
}
