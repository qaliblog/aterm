package com.qali.aterm.agent.tools

import com.rk.settings.Settings
import com.qali.aterm.agent.client.AgentClient
import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

data class WebSearchToolParams(
    val query: String
)

class WebSearchToolInvocation(
    toolParams: WebSearchToolParams,
    private val geminiClient: AgentClient,
    private val workspaceRoot: String
) : ToolInvocation<WebSearchToolParams, ToolResult> {
    
    override val params: WebSearchToolParams = toolParams
    
    override fun getDescription(): String {
        return "Searching the web for: \"${params.query}\""
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
                llmContent = "Web search cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return withContext(Dispatchers.IO) {
            try {
                // Check if we should use API search or custom search
                val useApiSearch = Settings.use_api_search
                
                if (!useApiSearch) {
                    // Use custom search engine
                    android.util.Log.d("WebSearchTool", "Using custom search engine (API search disabled)")
                    val customTool = CustomWebSearchTool(geminiClient, workspaceRoot)
                    val customInvocation = customTool.createInvocation(
                        CustomWebSearchToolParams(query = params.query),
                        null,
                        null
                    )
                    return@withContext customInvocation.execute(signal, updateOutput)
                }
                
                // Try API search first
                try {
                    val response = geminiClient.generateContentWithWebSearch(params.query, signal)
                
                val candidate = response.candidates?.firstOrNull()
                if (candidate == null) {
                    return@withContext ToolResult(
                        llmContent = "No search results or information found for query: \"${params.query}\"",
                        returnDisplay = "No information found"
                    )
                }
                
                // Extract text from content
                val responseText = candidate.content?.parts
                    ?.filterIsInstance<Part.TextPart>()
                    ?.joinToString("") { it.text }
                    ?: ""
                
                if (responseText.trim().isEmpty()) {
                    return@withContext ToolResult(
                        llmContent = "No search results or information found for query: \"${params.query}\"",
                        returnDisplay = "No information found"
                    )
                }
                
                val groundingMetadata = candidate.groundingMetadata
                val sources = groundingMetadata?.groundingChunks
                val groundingSupports = groundingMetadata?.groundingSupports
                
                var modifiedResponseText = responseText
                val sourceListFormatted = mutableListOf<String>()
                
                // Process sources
                if (sources != null && sources.isNotEmpty()) {
                    // Format sources with 1-based indexing (matching TypeScript: [${index + 1}])
                    sources.forEachIndexed { index, source ->
                        val title = source.web?.title ?: "Untitled"
                        val uri = source.web?.uri ?: "No URI"
                        sourceListFormatted.add("[${index + 1}] $title ($uri)")
                    }
                    
                    // Insert citation markers using grounding supports
                    // CRITICAL: segment indices are UTF-8 byte positions, not character positions
                    // This is essential for multibyte characters (Japanese, emojis, etc.)
                    if (groundingSupports != null && groundingSupports.isNotEmpty()) {
                        val insertions = mutableListOf<Pair<Int, String>>()
                        
                        groundingSupports.forEach { support ->
                            if (support.segment != null && support.groundingChunkIndices != null) {
                                val citationMarker = support.groundingChunkIndices
                                    .map { "[${it + 1}]" }
                                    .joinToString("")
                                insertions.add(support.segment.endIndex to citationMarker)
                            }
                        }
                        
                        // Sort by index descending to insert from end to start
                        insertions.sortByDescending { it.first }
                        
                        // Use UTF-8 byte positions (matching TypeScript TextEncoder/TextDecoder approach)
                        val responseBytes = modifiedResponseText.toByteArray(StandardCharsets.UTF_8)
                        val parts = mutableListOf<ByteArray>()
                        var lastIndex = responseBytes.size
                        
                        for ((byteIndex, marker) in insertions) {
                            val pos = minOf(byteIndex, lastIndex)
                            // Add text after marker
                            parts.add(0, responseBytes.sliceArray(pos until lastIndex))
                            // Add marker
                            parts.add(0, marker.toByteArray(StandardCharsets.UTF_8))
                            lastIndex = pos
                        }
                        // Add remaining text from start
                        parts.add(0, responseBytes.sliceArray(0 until lastIndex))
                        
                        // Concatenate all parts
                        val totalLength = parts.sumOf { it.size }
                        val finalBytes = ByteArray(totalLength)
                        var offset = 0
                        for (part in parts) {
                            part.copyInto(finalBytes, offset)
                            offset += part.size
                        }
                        
                        modifiedResponseText = String(finalBytes, StandardCharsets.UTF_8)
                    }
                    
                    // Append sources list
                    if (sourceListFormatted.isNotEmpty()) {
                        modifiedResponseText += "\n\nSources:\n${sourceListFormatted.joinToString("\n")}"
                    }
                }
                
                updateOutput?.invoke("Web search completed")
                
                ToolResult(
                    llmContent = "Web search results for \"${params.query}\":\n\n$modifiedResponseText",
                    returnDisplay = "Search results for \"${params.query}\" returned"
                )
                } catch (apiError: Exception) {
                    // API search failed, fallback to custom search
                    android.util.Log.w("WebSearchTool", "API search failed, reverting to custom search", apiError)
                    updateOutput?.invoke("⚠️ API search failed. Reverting to Custom offapi search engine...")
                    
                    try {
                        val customTool = CustomWebSearchTool(geminiClient, workspaceRoot)
                        val customInvocation = customTool.createInvocation(
                            CustomWebSearchToolParams(query = params.query),
                            null,
                            null
                        )
                        return@withContext customInvocation.execute(signal, updateOutput)
                    } catch (customError: Exception) {
                        val errorMessage = "Both API and custom search failed. API error: ${apiError.message}, Custom error: ${customError.message}"
                        android.util.Log.e("WebSearchTool", errorMessage, customError)
                        ToolResult(
                            llmContent = "Error: $errorMessage",
                            returnDisplay = "Error: Search failed",
                            error = ToolError(
                                message = errorMessage,
                                type = ToolErrorType.WEB_SEARCH_FAILED
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMessage = "Error during web search for query \"${params.query}\": ${e.message ?: "Unknown error"}"
                android.util.Log.e("WebSearchTool", errorMessage, e)
                ToolResult(
                    llmContent = "Error: $errorMessage",
                    returnDisplay = "Error performing web search",
                    error = ToolError(
                        message = errorMessage,
                        type = ToolErrorType.WEB_SEARCH_FAILED
                    )
                )
            }
        }
    }
}

class WebSearchTool(
    private val geminiClient: AgentClient,
    private val workspaceRoot: String
) : DeclarativeTool<WebSearchToolParams, ToolResult>() {
    
    override val name = "google_web_search" // Matching TypeScript WEB_SEARCH_TOOL_NAME
    override val displayName = "GoogleSearch"
    override val description = "Performs a web search using Google Search (via the Gemini API) and returns the results. This tool is useful for finding information on the internet based on a query."
    
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
        params: WebSearchToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<WebSearchToolParams, ToolResult> {
        return WebSearchToolInvocation(params, geminiClient, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): WebSearchToolParams {
        val query = params["query"] as? String
            ?: throw IllegalArgumentException("query is required")
        
        if (query.trim().isEmpty()) {
            throw IllegalArgumentException("query must be non-empty")
        }
        
        return WebSearchToolParams(query = query)
    }
}
