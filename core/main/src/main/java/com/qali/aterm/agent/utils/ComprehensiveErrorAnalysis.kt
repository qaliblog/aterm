package com.qali.aterm.agent.utils

import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.ppe.PpeApiClient
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * Comprehensive error analysis result from a single API call
 * Optimized for non-streaming API mode
 */
data class ComprehensiveErrorAnalysisResult(
    /**
     * Detected error type
     */
    val errorType: String,
    
    /**
     * Error severity
     */
    val severity: ErrorSeverity,
    
    /**
     * Files involved in the error
     */
    val affectedFiles: List<ErrorFileInfo>,
    
    /**
     * Root cause analysis
     */
    val rootCause: String? = null,
    
    /**
     * Suggested fixes
     */
    val suggestedFixes: List<FixSuggestion>,
    
    /**
     * Related files that should be checked
     */
    val relatedFiles: List<String> = emptyList(),
    
    /**
     * API mismatches detected
     */
    val apiMismatches: List<ApiMismatchInfo> = emptyList(),
    
    /**
     * Execution plan for fixing
     */
    val fixPlan: List<FixStep> = emptyList()
)

/**
 * Error file information
 */
data class ErrorFileInfo(
    val filePath: String,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val functionName: String? = null,
    val errorDescription: String? = null
)

/**
 * Fix suggestion
 */
data class FixSuggestion(
    val description: String,
    val filePath: String,
    val oldCode: String? = null,
    val newCode: String? = null,
    val confidence: Double = 0.8
)

/**
 * Fix step in execution plan
 */
data class FixStep(
    val stepNumber: Int,
    val description: String,
    val action: String, // "modify", "create", "delete", "update"
    val target: String, // File path or dependency
    val details: String? = null
)

/**
 * API mismatch information
 */
data class ApiMismatchInfo(
    val errorType: String,
    val suggestedFix: String,
    val affectedFiles: List<String>
)

/**
 * Comprehensive error analysis in a single API call
 * Optimized for non-streaming API mode
 */
object ComprehensiveErrorAnalysis {
    
    /**
     * Perform comprehensive error analysis in a single API call
     * Combines: error detection, classification, analysis, root cause, and fix suggestions
     * 
     * @param errorMessage User's error message or description
     * @param workspaceRoot Root directory of workspace
     * @param fileContents Map of file paths to their contents (already read files)
     * @param apiClient API client for non-streaming calls
     * @param chatHistory Optional chat history
     * @return Comprehensive analysis result
     */
    suspend fun analyzeComprehensively(
        errorMessage: String,
        workspaceRoot: String,
        fileContents: Map<String, String> = emptyMap(),
        apiClient: PpeApiClient?,
        chatHistory: List<Content> = emptyList()
    ): ComprehensiveErrorAnalysisResult? {
        if (apiClient == null) {
            Log.w("ComprehensiveErrorAnalysis", "API client not available")
            return null
        }
        
        return try {
            performComprehensiveAnalysis(
                errorMessage = errorMessage,
                workspaceRoot = workspaceRoot,
                fileContents = fileContents,
                apiClient = apiClient,
                chatHistory = chatHistory
            )
        } catch (e: Exception) {
            Log.e("ComprehensiveErrorAnalysis", "Comprehensive analysis failed: ${e.message}", e)
            null
        }
    }
    
    /**
     * Perform comprehensive analysis with AI
     */
    private suspend fun performComprehensiveAnalysis(
        errorMessage: String,
        workspaceRoot: String,
        fileContents: Map<String, String>,
        apiClient: PpeApiClient,
        chatHistory: List<Content>
    ): ComprehensiveErrorAnalysisResult {
        // Build comprehensive prompt that gets everything in one call
        val fileContext = if (fileContents.isNotEmpty()) {
            buildString {
                appendLine("\n=== Relevant Files ===")
                fileContents.forEach { (path, content) ->
                    appendLine("\n--- File: $path ---")
                    // Limit content to first 200 lines to avoid token limits
                    val lines = content.lines()
                    val preview = lines.take(200).joinToString("\n")
                    appendLine(preview)
                    if (lines.size > 200) {
                        appendLine("... (truncated, file has ${lines.size} lines)")
                    }
                }
            }
        } else {
            "\nNo files have been read yet. Please analyze based on the error message alone."
        }
        
        val prompt = """
You are an expert software debugging assistant. Perform a COMPREHENSIVE error analysis in a single response.

Error Message/Description: "$errorMessage"
$fileContext

Analyze this error and provide ALL of the following in ONE response:

1. **Error Type**: What type of error is this? (SyntaxError, TypeError, ReferenceError, ImportError, etc.)
2. **Severity**: How critical is this? (CRITICAL, HIGH, MEDIUM, LOW, INFO)
3. **Affected Files**: Which files are involved? Include line numbers, function names if available
4. **Root Cause**: What is the underlying cause of this error?
5. **Suggested Fixes**: Specific code fixes with old code and new code
6. **Related Files**: Other files that might be related or need checking
7. **API Mismatches**: Any API/library mismatches detected
8. **Fix Plan**: Step-by-step plan to fix the error

Return your response as a JSON object in this EXACT format (no markdown, just JSON):

{
  "errorType": "TypeError",
  "severity": "HIGH",
  "affectedFiles": [
    {
      "filePath": "path/to/file.js",
      "lineNumber": 45,
      "columnNumber": 12,
      "functionName": "getData",
      "errorDescription": "Cannot read property 'x' of undefined"
    }
  ],
  "rootCause": "The function is being called with undefined parameter",
  "suggestedFixes": [
    {
      "description": "Add null check before accessing property",
      "filePath": "path/to/file.js",
      "oldCode": "const value = data.x;",
      "newCode": "const value = data?.x ?? defaultValue;",
      "confidence": 0.9
    }
  ],
  "relatedFiles": ["path/to/related.js"],
  "apiMismatches": [
    {
      "errorType": "SQLite API Mismatch",
      "suggestedFix": "Use db.all() instead of db.execute()",
      "affectedFiles": ["database.js"]
    }
  ],
  "fixPlan": [
    {
      "stepNumber": 1,
      "description": "Add null check in getData function",
      "action": "modify",
      "target": "path/to/file.js",
      "details": "Add optional chaining and nullish coalescing"
    }
  ]
}

IMPORTANT:
- Return ONLY valid JSON, no markdown, no code blocks, no explanations
- Be specific about file paths, line numbers, and code changes
- Provide actionable fix suggestions with exact code
- Order fix plan steps logically

JSON Response:
""".trimIndent()
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            )
        )
        
        val result = apiClient.callApi(
            messages = messages,
            temperature = 0.3, // Lower temperature for more structured analysis
            disableTools = true
        )
        
        val response = result.getOrNull()
        if (response == null || response.text.isEmpty()) {
            throw Exception("AI returned empty response for comprehensive analysis")
        }
        
        return parseComprehensiveResult(response.text)
    }
    
    /**
     * Parse comprehensive analysis result from JSON
     */
    private fun parseComprehensiveResult(jsonText: String): ComprehensiveErrorAnalysisResult {
        var jsonStr = jsonText.trim()
        // Remove markdown code blocks if present
        jsonStr = jsonStr.removePrefix("```json").removePrefix("```").trim()
        jsonStr = jsonStr.removeSuffix("```").trim()
        
        // Extract JSON object
        val jsonStart = jsonStr.indexOf('{')
        val jsonEnd = jsonStr.lastIndexOf('}') + 1
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            jsonStr = jsonStr.substring(jsonStart, jsonEnd)
        }
        
        val json = JSONObject(jsonStr)
        
        val errorType = json.optString("errorType", "Unknown")
        
        val severityStr = json.optString("severity", "MEDIUM")
        val severity = try {
            ErrorSeverity.valueOf(severityStr)
        } catch (e: Exception) {
            ErrorSeverity.MEDIUM
        }
        
        // Parse affected files
        val affectedFiles = mutableListOf<ErrorFileInfo>()
        val filesArray = json.optJSONArray("affectedFiles")
        if (filesArray != null) {
            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                affectedFiles.add(
                    ErrorFileInfo(
                        filePath = fileObj.optString("filePath", ""),
                        lineNumber = if (fileObj.has("lineNumber")) fileObj.optInt("lineNumber") else null,
                        columnNumber = if (fileObj.has("columnNumber")) fileObj.optInt("columnNumber") else null,
                        functionName = fileObj.optString("functionName", null),
                        errorDescription = fileObj.optString("errorDescription", null)
                    )
                )
            }
        }
        
        val rootCause = json.optString("rootCause", null)
        
        // Parse suggested fixes
        val suggestedFixes = mutableListOf<FixSuggestion>()
        val fixesArray = json.optJSONArray("suggestedFixes")
        if (fixesArray != null) {
            for (i in 0 until fixesArray.length()) {
                val fixObj = fixesArray.getJSONObject(i)
                suggestedFixes.add(
                    FixSuggestion(
                        description = fixObj.optString("description", ""),
                        filePath = fixObj.optString("filePath", ""),
                        oldCode = fixObj.optString("oldCode", null),
                        newCode = fixObj.optString("newCode", null),
                        confidence = fixObj.optDouble("confidence", 0.8).coerceIn(0.0, 1.0)
                    )
                )
            }
        }
        
        // Parse related files
        val relatedFiles = mutableListOf<String>()
        val relatedArray = json.optJSONArray("relatedFiles")
        if (relatedArray != null) {
            for (i in 0 until relatedArray.length()) {
                relatedFiles.add(relatedArray.getString(i))
            }
        }
        
        // Parse API mismatches
        val apiMismatches = mutableListOf<ApiMismatchInfo>()
        val mismatchArray = json.optJSONArray("apiMismatches")
        if (mismatchArray != null) {
            for (i in 0 until mismatchArray.length()) {
                val mismatchObj = mismatchArray.getJSONObject(i)
                val affectedFilesList = mutableListOf<String>()
                val affectedArray = mismatchObj.optJSONArray("affectedFiles")
                if (affectedArray != null) {
                    for (j in 0 until affectedArray.length()) {
                        affectedFilesList.add(affectedArray.getString(j))
                    }
                }
                apiMismatches.add(
                    ApiMismatchInfo(
                        errorType = mismatchObj.optString("errorType", ""),
                        suggestedFix = mismatchObj.optString("suggestedFix", ""),
                        affectedFiles = affectedFilesList
                    )
                )
            }
        }
        
        // Parse fix plan
        val fixPlan = mutableListOf<FixStep>()
        val planArray = json.optJSONArray("fixPlan")
        if (planArray != null) {
            for (i in 0 until planArray.length()) {
                val stepObj = planArray.getJSONObject(i)
                fixPlan.add(
                    FixStep(
                        stepNumber = stepObj.optInt("stepNumber", i + 1),
                        description = stepObj.optString("description", ""),
                        action = stepObj.optString("action", "modify"),
                        target = stepObj.optString("target", ""),
                        details = stepObj.optString("details", null)
                    )
                )
            }
        }
        
        return ComprehensiveErrorAnalysisResult(
            errorType = errorType,
            severity = severity,
            affectedFiles = affectedFiles,
            rootCause = rootCause,
            suggestedFixes = suggestedFixes,
            relatedFiles = relatedFiles,
            apiMismatches = apiMismatches,
            fixPlan = fixPlan
        )
    }
}
