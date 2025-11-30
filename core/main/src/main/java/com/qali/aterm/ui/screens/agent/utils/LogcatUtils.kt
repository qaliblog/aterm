package com.qali.aterm.ui.screens.agent.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Read recent logcat entries filtered by relevant tags
 */
suspend fun readLogcatLogs(maxLines: Int = 200): String = withContext(Dispatchers.IO) {
    try {
        // Read more lines than needed, then filter
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat",
                "-d", // dump and exit
                "-t", (maxLines * 3).toString(), // read more lines to filter from
                "-v", "time" // time format
            )
        )
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val logs = StringBuilder()
        var line: String?
        var lineCount = 0
        
        // Relevant tags to filter for (including agent and shell execution)
        val relevantTags = listOf(
            "AgentClient", "OllamaClient", "AgentScreen", "ApiProviderManager",
            "AgentService", "OkHttp", "Okio", "AndroidRuntime", "ApiProvider",
            "OkHttpClient", "OkHttp3", "Okio", "System.err", "ShellTool",
            "sendMessage", "sendMessageInternal", "makeApiCall",
            "ToolInvocation", "executeToolSync", "LanguageLinterTool"
        )
        
        while (reader.readLine().also { line = it } != null && lineCount < maxLines) {
            line?.let { logLine ->
                // Check if line contains relevant tags or is an error/warning
                val containsRelevantTag = relevantTags.any { tag ->
                    logLine.contains(tag, ignoreCase = true)
                }
                
                // Check for error/warning indicators
                val isErrorOrWarning = logLine.matches(Regex(".*\\s+[EW]\\s+.*")) ||
                        logLine.contains("Error", ignoreCase = true) ||
                        logLine.contains("Exception", ignoreCase = true) ||
                        logLine.contains("IOException", ignoreCase = true) ||
                        logLine.contains("Network", ignoreCase = true) ||
                        logLine.contains("HTTP", ignoreCase = true) ||
                        logLine.contains("Failed", ignoreCase = true) ||
                        logLine.contains("Timeout", ignoreCase = true) ||
                        logLine.contains("generateContent", ignoreCase = true) ||
                        logLine.contains("generativelanguage", ignoreCase = true) ||
                        logLine.contains("API", ignoreCase = true) ||
                        logLine.contains("api", ignoreCase = true)
                
                if (containsRelevantTag || isErrorOrWarning) {
                    logs.appendLine(logLine)
                    lineCount++
                }
            }
        }
        
        process.waitFor()
        reader.close()
        
        // Also read error stream in case of issues
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
        val errorOutput = StringBuilder()
        while (errorReader.readLine().also { line = it } != null) {
            errorOutput.appendLine(line)
        }
        errorReader.close()
        
        if (logs.isEmpty()) {
            if (errorOutput.isNotEmpty()) {
                "No relevant logcat entries found.\nLogcat error: ${errorOutput.toString().take(200)}"
            } else {
                "No relevant logcat entries found (checked last ${maxLines * 3} lines).\nTry increasing the filter or check if logcat is accessible."
            }
        } else {
            logs.toString()
        }
    } catch (e: Exception) {
        "Error reading logcat: ${e.message}\n${e.stackTraceToString().take(500)}"
    }
}
