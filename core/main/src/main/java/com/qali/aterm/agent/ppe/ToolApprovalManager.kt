package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.FunctionCall
import android.util.Log

/**
 * Manages tool call approval - determines which tools require user approval
 * Better than Cursor AI: Smart detection of dangerous operations
 */
object ToolApprovalManager {
    
    /**
     * Dangerous shell commands that require approval
     */
    private val dangerousShellPatterns = listOf(
        Regex("rm\\s+-rf", RegexOption.IGNORE_CASE),
        Regex("rm\\s+-r", RegexOption.IGNORE_CASE),
        Regex("rm\\s+.*\\*", RegexOption.IGNORE_CASE),
        Regex("format", RegexOption.IGNORE_CASE),
        Regex("mkfs", RegexOption.IGNORE_CASE),
        Regex("dd\\s+if=", RegexOption.IGNORE_CASE),
        Regex("chmod\\s+[0-7]{3,4}", RegexOption.IGNORE_CASE),
        Regex("chown\\s+.*root", RegexOption.IGNORE_CASE),
        Regex("sudo", RegexOption.IGNORE_CASE),
        Regex("kill\\s+-9", RegexOption.IGNORE_CASE),
        Regex("pkill", RegexOption.IGNORE_CASE),
        Regex("uninstall", RegexOption.IGNORE_CASE),
        Regex("delete.*all", RegexOption.IGNORE_CASE),
        Regex("drop\\s+database", RegexOption.IGNORE_CASE),
        Regex("truncate", RegexOption.IGNORE_CASE)
    )
    
    /**
     * Dangerous file operations
     */
    private val dangerousFileOperations = setOf(
        "delete_file",
        "move_file",
        "rename_file"
    )
    
    /**
     * Check if a tool call requires approval
     */
    fun requiresApproval(functionCall: FunctionCall): Boolean {
        return when (functionCall.name) {
            "shell" -> {
                val command = functionCall.args["command"] as? String ?: ""
                dangerousShellPatterns.any { it.containsMatchIn(command) }
            }
            in dangerousFileOperations -> true
            "write_file" -> {
                // Check if writing to sensitive locations
                val filePath = functionCall.args["file_path"] as? String ?: ""
                filePath.contains("/etc/") || 
                filePath.contains("/sys/") || 
                filePath.contains("/proc/") ||
                filePath.contains("/root/") ||
                filePath.startsWith("/") && !filePath.startsWith("/data/")
            }
            "edit" -> {
                // Check if editing system files
                val filePath = functionCall.args["file_path"] as? String ?: ""
                filePath.contains("/etc/") || 
                filePath.contains("/sys/") || 
                filePath.contains("/proc/")
            }
            else -> false
        }
    }
    
    /**
     * Get reason why approval is required
     */
    fun getApprovalReason(functionCall: FunctionCall): String {
        return when (functionCall.name) {
            "shell" -> {
                val command = functionCall.args["command"] as? String ?: ""
                when {
                    command.contains("rm -rf", ignoreCase = true) -> "This command will permanently delete files recursively"
                    command.contains("rm -r", ignoreCase = true) -> "This command will delete files recursively"
                    command.contains("sudo", ignoreCase = true) -> "This command requires elevated privileges"
                    command.contains("format", ignoreCase = true) -> "This command may format storage"
                    command.contains("kill", ignoreCase = true) -> "This command will terminate processes"
                    command.contains("uninstall", ignoreCase = true) -> "This command will uninstall software"
                    else -> "This shell command may be dangerous"
                }
            }
            "delete_file" -> "This will permanently delete a file"
            "move_file" -> "This will move a file to a new location"
            "rename_file" -> "This will rename a file"
            "write_file" -> {
                val filePath = functionCall.args["file_path"] as? String ?: ""
                when {
                    filePath.contains("/etc/") -> "Writing to system configuration directory"
                    filePath.contains("/sys/") -> "Writing to system directory"
                    filePath.contains("/proc/") -> "Writing to proc filesystem"
                    filePath.startsWith("/") && !filePath.startsWith("/data/") -> "Writing outside workspace"
                    else -> "Writing to sensitive location"
                }
            }
            "edit" -> {
                val filePath = functionCall.args["file_path"] as? String ?: ""
                when {
                    filePath.contains("/etc/") -> "Editing system configuration file"
                    filePath.contains("/sys/") -> "Editing system file"
                    filePath.contains("/proc/") -> "Editing proc filesystem"
                    else -> "Editing system file"
                }
            }
            else -> "This operation requires approval"
        }
    }
    
    /**
     * Check if command should be added to allow list
     */
    fun shouldAddToAllowList(functionCall: FunctionCall): Boolean {
        // Add to allow list if it's a shell command (user might want to allow similar commands)
        return functionCall.name == "shell"
    }
}
