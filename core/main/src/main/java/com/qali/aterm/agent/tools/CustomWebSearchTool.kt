package com.qali.aterm.agent.tools

import com.rk.settings.Settings
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.agent.client.AgentClient
import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.tools.ToolError
import com.qali.aterm.agent.tools.ToolErrorType
import com.qali.aterm.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CustomWebSearchToolParams(
    val query: String
)

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

class CustomWebSearchToolInvocation(
    toolParams: CustomWebSearchToolParams,
    private val geminiClient: AgentClient,
    private val workspaceRoot: String
) : ToolInvocation<CustomWebSearchToolParams, ToolResult> {
    
    override val params: CustomWebSearchToolParams = toolParams
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    
    override fun getDescription(): String {
        return "Custom web search for: \"${params.query}\""
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Custom web search cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val maxRecursiveCurls = Settings.custom_search_recursive_curls
                val searchGoal = params.query
                
                updateOutput?.invoke("🔍 Starting custom web search (max $maxRecursiveCurls recursive searches)...")
                
                // Step 1: Initial Google Search (using DuckDuckGo as it doesn't require API key)
                updateOutput?.invoke("📡 Performing initial search...")
                val initialResults = performDuckDuckGoSearch(searchGoal)
                
                if (initialResults.isEmpty()) {
                    return@withContext ToolResult(
                        llmContent = "No search results found for query: \"$searchGoal\"",
                        returnDisplay = "No results found"
                    )
                }
                
                // Step 2: Use AI to analyze initial results and suggest related searches/links
                updateOutput?.invoke("🤖 AI analyzing initial results...")
                val analysisPrompt = buildAnalysisPrompt(searchGoal, initialResults)
                var aiAnalysis = askAIForAnalysis(analysisPrompt, signal)
                
                // Step 3: Perform recursive searches based on AI suggestions
                val allCollectedInfo = mutableListOf<SearchResult>()
                allCollectedInfo.addAll(initialResults)
                
                var currentLevel = 1
                val visitedUrls = mutableSetOf<String>()
                initialResults.forEach { visitedUrls.add(it.url) }
                
                while (currentLevel <= maxRecursiveCurls && signal?.isAborted() != true) {
                    updateOutput?.invoke("🔄 Recursive search level $currentLevel/$maxRecursiveCurls...")
                    
                    // Extract suggested queries and URLs from AI analysis
                    val suggestedQueries = extractSuggestedQueries(aiAnalysis)
                    val suggestedUrls = extractSuggestedUrls(aiAnalysis)
                    
                    // Perform searches for suggested queries
                    for (suggestedQuery in suggestedQueries.take(2)) { // Limit to 2 queries per level
                        if (signal?.isAborted() == true) break
                        val additionalResults = performDuckDuckGoSearch(suggestedQuery)
                        additionalResults.forEach { result ->
                            if (!visitedUrls.contains(result.url)) {
                                allCollectedInfo.add(result)
                                visitedUrls.add(result.url)
                            }
                        }
                    }
                    
                    // Fetch content from suggested URLs
                    for (url in suggestedUrls.take(3)) { // Limit to 3 URLs per level
                        if (signal?.isAborted() == true) break
                        if (!visitedUrls.contains(url)) {
                            try {
                                val content = fetchUrlContent(url)
                                if (content.isNotEmpty()) {
                                    allCollectedInfo.add(SearchResult(
                                        title = extractTitleFromUrl(url),
                                        url = url,
                                        snippet = content.take(500)
                                    ))
                                    visitedUrls.add(url)
                                }
                            } catch (e: Exception) {
                                android.util.Log.d("CustomWebSearch", "Failed to fetch $url: ${e.message}")
                            }
                        }
                    }
                    
                    // Re-analyze with new information
                    if (allCollectedInfo.size > initialResults.size) {
                        val newAnalysisPrompt = buildAnalysisPrompt(searchGoal, allCollectedInfo)
                        aiAnalysis = askAIForAnalysis(newAnalysisPrompt, signal)
                    }
                    
                    currentLevel++
                }
                
                // Step 4: Final AI summarization
                updateOutput?.invoke("📝 AI summarizing search results...")
                val summaryPrompt = buildSummaryPrompt(searchGoal, allCollectedInfo)
                val finalSummary = askAIForSummary(summaryPrompt, signal)
                
                updateOutput?.invoke("✅ Custom web search completed")
                
                ToolResult(
                    llmContent = "Custom web search results for \"$searchGoal\":\n\n$finalSummary\n\nSources (${allCollectedInfo.size} total):\n${allCollectedInfo.take(10).joinToString("\n") { "[${it.title}](${it.url})" }}",
                    returnDisplay = "Custom search completed (${allCollectedInfo.size} sources, $currentLevel levels)"
                )
            } catch (e: Exception) {
                val errorMessage = "Error during custom web search: ${e.message ?: "Unknown error"}"
                android.util.Log.e("CustomWebSearchTool", errorMessage, e)
                ToolResult(
                    llmContent = "Error: $errorMessage",
                    returnDisplay = "Error performing custom search",
                    error = ToolError(
                        message = errorMessage,
                        type = ToolErrorType.WEB_SEARCH_FAILED
                    )
                )
            }
        }
    }
    
    private fun performDuckDuckGoSearch(query: String): List<SearchResult> {
        return try {
            // DuckDuckGo Instant Answer API (no API key required)
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                android.util.Log.w("CustomWebSearch", "DuckDuckGo API returned ${response.code}")
                return emptyList()
            }
            
            val json = JSONObject(response.body?.string() ?: "{}")
            val results = mutableListOf<SearchResult>()
            
            // Extract Abstract (instant answer)
            val abstract = json.optString("Abstract", "")
            val abstractUrl = json.optString("AbstractURL", "")
            val abstractText = json.optString("AbstractText", "")
            
            if (abstractText.isNotEmpty() && abstractUrl.isNotEmpty()) {
                results.add(SearchResult(
                    title = abstract.ifEmpty { "DuckDuckGo Result" },
                    url = abstractUrl,
                    snippet = abstractText
                ))
            }
            
            // Extract Related Topics
            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until minOf(relatedTopics.length(), 5)) {
                    val topic = relatedTopics.getJSONObject(i)
                    val text = topic.optString("Text", "")
                    val firstUrl = topic.optString("FirstURL", "")
                    
                    if (text.isNotEmpty() && firstUrl.isNotEmpty()) {
                        results.add(SearchResult(
                            title = text.take(100),
                            url = firstUrl,
                            snippet = text
                        ))
                    }
                }
            }
            
            // If no results from API, try HTML scraping (fallback)
            if (results.isEmpty()) {
                results.addAll(performDuckDuckGoHtmlSearch(query))
            }
            
            results
        } catch (e: Exception) {
            android.util.Log.e("CustomWebSearch", "DuckDuckGo search failed", e)
            // Fallback to HTML search
            performDuckDuckGoHtmlSearch(query)
        }
    }
    
    private fun performDuckDuckGoHtmlSearch(query: String): List<SearchResult> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return emptyList()
            }
            
            val html = response.body?.string() ?: ""
            val results = mutableListOf<SearchResult>()
            
            // Simple HTML parsing for DuckDuckGo results
            val linkPattern = Regex("<a class=\"result__a\" href=\"([^\"]+)\"[^>]*>([^<]+)</a>")
            val snippetPattern = Regex("<a class=\"result__snippet\"[^>]*>([^<]+)</a>")
            
            val links = linkPattern.findAll(html).take(5)
            val snippets = snippetPattern.findAll(html).take(5).map { it.groupValues[1] }.toList()
            
            links.forEachIndexed { index, match ->
                val url = match.groupValues[1]
                val title = match.groupValues[2]
                val snippet = if (index < snippets.size) snippets[index] else ""
                
                results.add(SearchResult(
                    title = title,
                    url = url,
                    snippet = snippet
                ))
            }
            
            results
        } catch (e: Exception) {
            android.util.Log.e("CustomWebSearch", "DuckDuckGo HTML search failed", e)
            emptyList()
        }
    }
    
    private fun fetchUrlContent(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return ""
            }
            
            val contentType = response.header("Content-Type") ?: ""
            val content = response.body?.string() ?: ""
            
            // Limit content length
            val maxLength = 5000
            val limitedContent = if (content.length > maxLength) {
                content.substring(0, maxLength) + "..."
            } else {
                content
            }
            
            // Strip HTML if needed
            if (contentType.contains("text/html", ignoreCase = true)) {
                stripHtmlTags(limitedContent)
            } else {
                limitedContent
            }
        } catch (e: Exception) {
            android.util.Log.e("CustomWebSearch", "Failed to fetch $url", e)
            ""
        }
    }
    
    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun extractTitleFromUrl(url: String): String {
        return try {
            val domain = url.replace(Regex("^https?://"), "")
                .replace(Regex("/.*"), "")
            domain
        } catch (e: Exception) {
            url
        }
    }
    
    private fun buildAnalysisPrompt(goal: String, results: List<SearchResult>): String {
        val resultsText = results.take(10).mapIndexed { index, result ->
            "${index + 1}. **${result.title}**\n   URL: ${result.url}\n   Snippet: ${result.snippet.take(200)}"
        }.joinToString("\n\n")
        
        return """
            I am performing a web search with the goal: "$goal"
            
            Here are the initial search results I found:
            
            $resultsText
            
            Please analyze these results and provide:
            1. A list of 2-3 additional search queries that would help gather more relevant information
            2. A list of 2-3 specific URLs from the results that seem most relevant and should be explored further
            3. Any related topics or keywords that should be searched
            
            Format your response as JSON:
            {
              "queries": ["query1", "query2"],
              "urls": ["url1", "url2"],
              "topics": ["topic1", "topic2"]
            }
        """.trimIndent()
    }
    
    private fun buildSummaryPrompt(goal: String, allResults: List<SearchResult>): String {
        val resultsText = allResults.take(20).mapIndexed { index, result ->
            "${index + 1}. **${result.title}**\n   URL: ${result.url}\n   ${result.snippet.take(300)}"
        }.joinToString("\n\n")
        
        return """
            I performed a comprehensive web search with the goal: "$goal"
            
            I gathered information from ${allResults.size} sources. Here are the key results:
            
            $resultsText
            
            Please provide a comprehensive summary that:
            1. Directly addresses the search goal: "$goal"
            2. Synthesizes information from multiple sources
            3. Highlights the most relevant and useful information
            4. Includes key facts, insights, or answers found
            5. Cites sources where relevant
            
            Make the summary clear, well-organized, and directly useful for the user's goal.
        """.trimIndent()
    }
    
    private suspend fun askAIForAnalysis(prompt: String, signal: CancellationSignal?): String {
        return try {
            val apiKey = ApiProviderManager.getNextApiKey()
                ?: throw Exception("No API keys configured")
            
            val model = ApiProviderManager.getCurrentModel()
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }
            
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("API call failed: ${response.code}")
            }
            
            val bodyString = response.body?.string() ?: ""
            val json = JSONObject(bodyString)
            val candidates = json.optJSONArray("candidates")
            
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                if (content != null) {
                    val parts = content.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val textParts = (0 until parts.length())
                            .mapNotNull { i ->
                                val part = parts.getJSONObject(i)
                                if (part.has("text")) part.getString("text") else null
                            }
                        return textParts.joinToString("")
                    }
                }
            }
            
            ""
        } catch (e: Exception) {
            android.util.Log.e("CustomWebSearch", "AI analysis failed", e)
            "{}"
        }
    }
    
    private suspend fun askAIForSummary(prompt: String, signal: CancellationSignal?): String {
        return askAIForAnalysis(prompt, signal) // Same implementation
    }
    
    private fun extractSuggestedQueries(aiResponse: String): List<String> {
        return try {
            val jsonStart = aiResponse.indexOf('{')
            val jsonEnd = aiResponse.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = JSONObject(aiResponse.substring(jsonStart, jsonEnd))
                val queries = json.optJSONArray("queries")
                if (queries != null) {
                    (0 until queries.length()).mapNotNull { queries.getString(it) }
                } else {
                    emptyList()
                }
            } else {
                // Fallback: extract queries from text
                extractQueriesFromText(aiResponse)
            }
        } catch (e: Exception) {
            android.util.Log.d("CustomWebSearch", "Failed to parse queries from AI response", e)
            extractQueriesFromText(aiResponse)
        }
    }
    
    private fun extractSuggestedUrls(aiResponse: String): List<String> {
        return try {
            val jsonStart = aiResponse.indexOf('{')
            val jsonEnd = aiResponse.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = JSONObject(aiResponse.substring(jsonStart, jsonEnd))
                val urls = json.optJSONArray("urls")
                if (urls != null) {
                    (0 until urls.length()).mapNotNull { urls.getString(it) }
                } else {
                    emptyList()
                }
            } else {
                // Fallback: extract URLs from text
                extractUrlsFromText(aiResponse)
            }
        } catch (e: Exception) {
            android.util.Log.d("CustomWebSearch", "Failed to parse URLs from AI response", e)
            extractUrlsFromText(aiResponse)
        }
    }
    
    private fun extractQueriesFromText(text: String): List<String> {
        // Simple extraction: look for quoted strings or numbered lists
        val queries = mutableListOf<String>()
        val quotedPattern = Regex("\"([^\"]+)\"")
        quotedPattern.findAll(text).forEach { match ->
            val query = match.groupValues[1]
            if (query.length > 5 && query.length < 100) {
                queries.add(query)
            }
        }
        return queries.take(3)
    }
    
    private fun extractUrlsFromText(text: String): List<String> {
        val urls = mutableListOf<String>()
        val urlPattern = Regex("https?://[^\\s]+")
        urlPattern.findAll(text).forEach { match ->
            val url = match.value
            if (url.length < 500) {
                urls.add(url)
            }
        }
        return urls.take(3)
    }
}

class CustomWebSearchTool(
    private val geminiClient: AgentClient,
    private val workspaceRoot: String
) : DeclarativeTool<CustomWebSearchToolParams, ToolResult>() {
    
    override val name = "custom_web_search"
    override val displayName = "CustomWebSearch"
    override val description = "Performs a custom web search using direct API calls and AI analysis. Supports recursive searches to gather comprehensive information. Use this when API-based search is not available or when you need more control over the search process."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "query" to PropertySchema(
                type = "string",
                description = "The search query."
            )
        ),
        required = listOf("query")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: CustomWebSearchToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<CustomWebSearchToolParams, ToolResult> {
        return CustomWebSearchToolInvocation(params, geminiClient, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): CustomWebSearchToolParams {
        val query = params["query"] as? String
            ?: throw IllegalArgumentException("query is required")
        
        if (query.trim().isEmpty()) {
            throw IllegalArgumentException("query must be non-empty")
        }
        
        return CustomWebSearchToolParams(query = query)
    }
}
