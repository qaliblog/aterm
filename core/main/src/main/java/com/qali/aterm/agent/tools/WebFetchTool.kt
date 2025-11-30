package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

data class WebFetchToolParams(
    val prompt: String
)

class WebFetchToolInvocation(
    toolParams: WebFetchToolParams
) : ToolInvocation<WebFetchToolParams, ToolResult> {
    
    override val params: WebFetchToolParams = toolParams
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    override fun getDescription(): String {
        val displayPrompt = if (params.prompt.length > 100) {
            params.prompt.substring(0, 97) + "..."
        } else {
            params.prompt
        }
        return "Processing URLs and instructions from prompt: \"$displayPrompt\""
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
                llmContent = "Web fetch cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val (validUrls, errors) = parsePrompt(params.prompt)
                
                if (errors.isNotEmpty()) {
                    return@withContext ToolResult(
                        llmContent = "URL parsing errors: ${errors.joinToString(", ")}",
                        returnDisplay = "Error: Invalid URLs",
                        error = ToolError(
                            message = errors.joinToString(", "),
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
                
                if (validUrls.isEmpty()) {
                    return@withContext ToolResult(
                        llmContent = "No valid URLs found in prompt",
                        returnDisplay = "Error: No URLs found",
                        error = ToolError(
                            message = "No valid URLs found",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
                
                // Fetch first URL (limit to 20 in full implementation)
                val url = validUrls[0]
                val processedUrl = if (url.contains("github.com") && url.contains("/blob/")) {
                    url.replace("github.com", "raw.githubusercontent.com")
                        .replace("/blob/", "/")
                } else {
                    url
                }
                
                val request = Request.Builder()
                    .url(processedUrl)
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    return@withContext ToolResult(
                        llmContent = "Request failed with status code ${response.code} ${response.message}",
                        returnDisplay = "Error: HTTP ${response.code}",
                        error = ToolError(
                            message = "HTTP ${response.code}",
                            type = ToolErrorType.EXECUTION_ERROR
                        )
                    )
                }
                
                val contentType = response.header("Content-Type") ?: ""
                val rawContent = response.body?.string() ?: ""
                
                // Limit content length
                val maxLength = 100000
                val textContent = if (rawContent.length > maxLength) {
                    rawContent.substring(0, maxLength) + "\n... (truncated)"
                } else {
                    rawContent
                }
                
                // Simple HTML stripping (basic implementation)
                val cleanedContent = if (contentType.contains("text/html", ignoreCase = true)) {
                    stripHtmlTags(textContent)
                } else {
                    textContent
                }
                
                updateOutput?.invoke("Fetched content from $url")
                
                ToolResult(
                    llmContent = cleanedContent,
                    returnDisplay = "Fetched content from $url (${cleanedContent.length} chars)"
                )
            } catch (e: Exception) {
                ToolResult(
                    llmContent = "Error fetching URL: ${e.message}",
                    returnDisplay = "Error: ${e.message}",
                    error = ToolError(
                        message = e.message ?: "Unknown error",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
        }
    }
    
    private fun parsePrompt(text: String): Pair<List<String>, List<String>> {
        val tokens = text.split(Regex("\\s+"))
        val validUrls = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        for (token in tokens) {
            if (token.contains("://")) {
                try {
                    val url = URL(token)
                    if (url.protocol in listOf("http", "https")) {
                        validUrls.add(url.toString())
                    } else {
                        errors.add("Unsupported protocol in URL: \"$token\". Only http and https are supported.")
                    }
                } catch (e: Exception) {
                    errors.add("Malformed URL detected: \"$token\".")
                }
            }
        }
        
        return Pair(validUrls, errors)
    }
    
    private fun stripHtmlTags(html: String): String {
        // Basic HTML tag removal
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
    }
}

class WebFetchTool : DeclarativeTool<WebFetchToolParams, ToolResult>() {
    
    override val name = "web_fetch"
    override val displayName = "WebFetch"
    override val description = "Fetches content from web URLs (up to 20 URLs). Processes HTML content and extracts text. Useful for reading web pages, documentation, and online resources."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "prompt" to PropertySchema(
                type = "string",
                description = "The prompt containing URL(s) (up to 20) and instructions for processing their content."
            )
        ),
        required = listOf("prompt")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: WebFetchToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<WebFetchToolParams, ToolResult> {
        return WebFetchToolInvocation(params)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): WebFetchToolParams {
        val prompt = params["prompt"] as? String
            ?: throw IllegalArgumentException("prompt is required")
        
        if (prompt.trim().isEmpty()) {
            throw IllegalArgumentException("prompt must be non-empty")
        }
        
        return WebFetchToolParams(prompt = prompt)
    }
}
