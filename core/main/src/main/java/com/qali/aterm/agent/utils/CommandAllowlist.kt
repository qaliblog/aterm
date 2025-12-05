package com.qali.aterm.agent.utils

import android.content.SharedPreferences
import android.content.Context
import android.util.Log

/**
 * Command allowlist manager
 * Manages allowed commands and prompts for permission
 */
object CommandAllowlist {
    
    private const val PREFS_NAME = "command_allowlist"
    private const val KEY_ALLOWLIST = "allowlist"
    
    /**
     * Base startup commands that are always allowed
     */
    private val BASE_ALLOWED_COMMANDS = setOf(
        "ls", "pwd", "cd", "cat", "echo", "mkdir", "touch",
        "git status", "git log", "git diff", "git show",
        "npm list", "npm view", "node --version", "python --version",
        "which", "whereis", "type", "command -v"
    )
    
    /**
     * Command permission result
     */
    enum class PermissionResult {
        ALLOWED,        // Command is in allowlist
        DENIED,         // User denied
        SKIPPED,        // User skipped with reason
        NEEDS_APPROVAL  // Needs user approval
    }
    
    /**
     * Command permission request
     */
    data class CommandPermission(
        val command: String,
        val result: PermissionResult,
        val reason: String? = null
    )
    
    /**
     * Check if command is allowed
     */
    fun isAllowed(command: String, context: Context?): Boolean {
        if (context == null) return false
        
        val allowlist = loadAllowlist(context)
        return allowlist.contains(command) || BASE_ALLOWED_COMMANDS.contains(command)
    }
    
    /**
     * Add command to allowlist
     */
    fun addToAllowlist(command: String, context: Context?) {
        if (context == null) return
        
        val allowlist = loadAllowlist(context).toMutableSet()
        allowlist.add(command)
        saveAllowlist(allowlist, context)
        
        Log.d("CommandAllowlist", "Added command to allowlist: $command")
    }
    
    /**
     * Remove command from allowlist
     */
    fun removeFromAllowlist(command: String, context: Context?) {
        if (context == null) return
        
        val allowlist = loadAllowlist(context).toMutableSet()
        allowlist.remove(command)
        saveAllowlist(allowlist, context)
        
        Log.d("CommandAllowlist", "Removed command from allowlist: $command")
    }
    
    /**
     * Reset allowlist to base commands
     */
    fun resetToBase(context: Context?) {
        if (context == null) return
        
        saveAllowlist(BASE_ALLOWED_COMMANDS.toMutableSet(), context)
        Log.d("CommandAllowlist", "Reset allowlist to base commands")
    }
    
    /**
     * Load allowlist from preferences
     */
    private fun loadAllowlist(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allowlistJson = prefs.getString(KEY_ALLOWLIST, null)
        
        if (allowlistJson == null) {
            // Initialize with base commands
            saveAllowlist(BASE_ALLOWED_COMMANDS.toMutableSet(), context)
            return BASE_ALLOWED_COMMANDS
        }
        
        return try {
            val json = org.json.JSONArray(allowlistJson)
            val commands = mutableSetOf<String>()
            for (i in 0 until json.length()) {
                commands.add(json.getString(i))
            }
            commands
        } catch (e: Exception) {
            Log.e("CommandAllowlist", "Failed to load allowlist: ${e.message}", e)
            BASE_ALLOWED_COMMANDS
        }
    }
    
    /**
     * Save allowlist to preferences
     */
    private fun saveAllowlist(allowlist: Set<String>, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = org.json.JSONArray(allowlist.toList())
        prefs.edit().putString(KEY_ALLOWLIST, json.toString()).apply()
    }
    
    /**
     * Get all allowed commands
     */
    fun getAllowedCommands(context: Context?): Set<String> {
        if (context == null) return BASE_ALLOWED_COMMANDS
        
        val allowlist = loadAllowlist(context)
        return allowlist + BASE_ALLOWED_COMMANDS
    }
    
    /**
     * Check if command needs approval (not in allowlist and not base command)
     */
    fun needsApproval(command: String, context: Context?): Boolean {
        if (context == null) return true
        
        val allowlist = loadAllowlist(context)
        return !allowlist.contains(command) && !BASE_ALLOWED_COMMANDS.contains(command)
    }
    
    /**
     * Format command for display
     */
    fun formatCommand(command: String): String {
        // Truncate long commands
        return if (command.length > 100) {
            command.take(100) + "..."
        } else {
            command
        }
    }
}
