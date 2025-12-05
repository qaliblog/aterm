package com.qali.aterm.agent.utils

import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.ppe.PpeApiClient
import com.qali.aterm.agent.debug.DebugLogger
import android.util.Log
import org.json.JSONObject

/**
 * AI-powered request classifier that determines if a user request is:
 * - An error to debug (ERROR_DEBUG)
 * - An upgrade request (UPGRADE)
 * - Both (BOTH)
 * - Unknown (UNKNOWN)
 * 
 * Uses AI to analyze user prompts and classify intent with confidence scoring.
 */
object RequestClassifier {
    
    /**
     * Classify user request using AI analysis
     * 
     * @param userMessage The user's message/prompt
     * @param apiClient The API client for AI calls (non-streaming mode)
     * @param chatHistory Optional chat history for context
     * @param systemInfo Optional system information for context
     * @return Classification result with intent and confidence
     */
    suspend fun classifyRequest(
        userMessage: String,
        apiClient: PpeApiClient?,
        chatHistory: List<Content> = emptyList(),
        systemInfo: ActionFlowPlanner.SystemInfo? = null
    ): RequestClassificationResult {
        // First try rule-based classification for quick results
        val ruleBasedResult = classifyWithRules(userMessage)
        
        // If high confidence from rules, return immediately
        if (ruleBasedResult.isHighConfidence()) {
            Log.d("RequestClassifier", "High confidence rule-based classification: ${ruleBasedResult.intent}")
            return ruleBasedResult
        }
        
        // If API client available, use AI for better classification
        if (apiClient != null) {
            return try {
                classifyWithAI(userMessage, apiClient, chatHistory, systemInfo)
            } catch (e: Exception) {
                Log.w("RequestClassifier", "AI classification failed, using rule-based: ${e.message}")
                ruleBasedResult
            }
        }
        
        // Fallback to rule-based
        return ruleBasedResult
    }
    
    /**
     * Rule-based classification (fast, no API call)
     */
    private fun classifyWithRules(userMessage: String): RequestClassificationResult {
        val lowerMessage = userMessage.lowercase()
        
        // Error indicators
        val errorKeywords = listOf(
            "error", "exception", "failed", "failure", "crash", "bug",
            "doesn't work", "not working", "broken", "undefined",
            "null pointer", "cannot", "unable", "invalid", "missing",
            "compile error", "runtime error", "syntax error", "type error"
        )
        
        // Upgrade indicators
        val upgradeKeywords = listOf(
            "upgrade", "enhance", "improve", "add feature", "new feature",
            "implement", "add", "create", "build", "develop",
            "update to", "upgrade to", "migrate", "refactor"
        )
        
        val errorMatches = errorKeywords.filter { lowerMessage.contains(it) }
        val upgradeMatches = upgradeKeywords.filter { lowerMessage.contains(it) }
        
        val hasError = errorMatches.isNotEmpty()
        val hasUpgrade = upgradeMatches.isNotEmpty()
        
        val intent = when {
            hasError && hasUpgrade -> RequestIntent.BOTH
            hasError -> RequestIntent.ERROR_DEBUG
            hasUpgrade -> RequestIntent.UPGRADE
            else -> RequestIntent.UNKNOWN
        }
        
        // Calculate confidence based on number of matches
        val confidence = when {
            hasError && hasUpgrade -> 0.85 // High confidence for both
            hasError && errorMatches.size >= 2 -> 0.8 // High confidence for multiple error indicators
            hasUpgrade && upgradeMatches.size >= 2 -> 0.8 // High confidence for multiple upgrade indicators
            hasError || hasUpgrade -> 0.6 // Medium confidence for single indicator
            else -> 0.3 // Low confidence for unknown
        }
        
        return RequestClassificationResult(
            intent = intent,
            confidence = confidence,
            errorIndicators = errorMatches,
            upgradeIndicators = upgradeMatches
        )
    }
    
    /**
     * AI-based classification (more accurate, requires API call)
     */
    private suspend fun classifyWithAI(
        userMessage: String,
        apiClient: PpeApiClient,
        chatHistory: List<Content>,
        systemInfo: ActionFlowPlanner.SystemInfo?
    ): RequestClassificationResult {
        val prompt = buildString {
            appendLine("Analyze the following user request and classify it into one of these categories:")
            appendLine()
            appendLine("1. ERROR_DEBUG - User wants to debug/fix an error or problem")
            appendLine("2. UPGRADE - User wants to upgrade/enhance/add features to the application")
            appendLine("3. BOTH - User wants to both fix an error AND upgrade/enhance")
            appendLine("4. UNKNOWN - Cannot determine intent")
            appendLine()
            
            if (systemInfo != null) {
                appendLine("## System Information")
                appendLine("- Current Directory: ${systemInfo.currentDir}")
                appendLine("- OS: ${systemInfo.os} ${systemInfo.osVersion}")
                appendLine("- Architecture: ${systemInfo.architecture}")
                appendLine("- Package Manager: ${systemInfo.packageManager}")
                appendLine("- Shell: ${systemInfo.shell}")
                appendLine()
                appendLine("## Directory Information")
                appendLine("- Files: ${systemInfo.dirInfo.fileCount}")
                appendLine("- Directories: ${systemInfo.dirInfo.directoryCount}")
                appendLine("- Has package.json: ${systemInfo.dirInfo.hasPackageJson}")
                appendLine("- Has build.gradle: ${systemInfo.dirInfo.hasBuildGradle}")
                appendLine("- Project Type: ${systemInfo.dirInfo.projectType ?: "Unknown"}")
                appendLine()
            }
            
            appendLine("## User Request")
            appendLine("\"$userMessage\"")
            appendLine()
            appendLine("Consider:")
            appendLine("- Error indicators: error messages, exceptions, crashes, \"doesn't work\", \"not working\", stack traces")
            appendLine("- Upgrade indicators: \"add feature\", \"upgrade\", \"enhance\", \"implement\", \"new functionality\", \"improve\"")
            appendLine("- If both error fixing and upgrading are mentioned, classify as BOTH")
            appendLine()
            appendLine("Return your response as a JSON object in this EXACT format (no markdown, just JSON):")
            appendLine("{")
            appendLine("  \"intent\": \"ERROR_DEBUG\" | \"UPGRADE\" | \"BOTH\" | \"UNKNOWN\",")
            appendLine("  \"confidence\": 0.0-1.0,")
            appendLine("  \"reasoning\": \"Brief explanation of why this classification was chosen\",")
            appendLine("  \"errorIndicators\": [\"list\", \"of\", \"error\", \"indicators\", \"found\"],")
            appendLine("  \"upgradeIndicators\": [\"list\", \"of\", \"upgrade\", \"indicators\", \"found\"]")
            appendLine("}")
            appendLine()
            appendLine("JSON Response:")
        }
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            )
        )
        
        val result = apiClient.callApi(
            messages = messages,
            temperature = 0.3, // Lower temperature for more consistent classification
            disableTools = true
        )
        
        val response = result.getOrNull()
        if (response == null || response.text.isEmpty()) {
            Log.w("RequestClassifier", "AI classification returned empty response")
            return classifyWithRules(userMessage)
        }
        
        // Parse JSON response
        return try {
            var jsonText = response.text.trim()
            // Remove markdown code blocks if present
            jsonText = jsonText.removePrefix("```json").removePrefix("```").trim()
            jsonText = jsonText.removeSuffix("```").trim()
            
            // Extract JSON object
            val jsonStart = jsonText.indexOf('{')
            val jsonEnd = jsonText.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonText = jsonText.substring(jsonStart, jsonEnd)
            }
            
            val json = JSONObject(jsonText)
            val intentStr = json.optString("intent", "UNKNOWN").uppercase()
            val intent = try {
                RequestIntent.valueOf(intentStr)
            } catch (e: Exception) {
                RequestIntent.UNKNOWN
            }
            
            val confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
            val reasoning = json.optString("reasoning", null)
            
            val errorIndicators = mutableListOf<String>()
            val errorArray = json.optJSONArray("errorIndicators")
            if (errorArray != null) {
                for (i in 0 until errorArray.length()) {
                    errorIndicators.add(errorArray.getString(i))
                }
            }
            
            val upgradeIndicators = mutableListOf<String>()
            val upgradeArray = json.optJSONArray("upgradeIndicators")
            if (upgradeArray != null) {
                for (i in 0 until upgradeArray.length()) {
                    upgradeIndicators.add(upgradeArray.getString(i))
                }
            }
            
            RequestClassificationResult(
                intent = intent,
                confidence = confidence,
                reasoning = reasoning,
                errorIndicators = errorIndicators,
                upgradeIndicators = upgradeIndicators
            )
        } catch (e: Exception) {
            Log.e("RequestClassifier", "Failed to parse AI classification response: ${e.message}", e)
            classifyWithRules(userMessage)
        }
    }
    
    /**
     * Get routing information based on classification result
     */
    fun getRoutingInfo(result: RequestClassificationResult): String {
        return when (result.intent) {
            RequestIntent.ERROR_DEBUG -> "Route to error analysis workflow"
            RequestIntent.UPGRADE -> "Route to upgrade planning workflow"
            RequestIntent.BOTH -> "Route to combined error + upgrade workflow"
            RequestIntent.UNKNOWN -> "Request clarification from user"
        }
    }
}
