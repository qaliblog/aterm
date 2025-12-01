package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.ppe.PpeApiClient
import com.qali.aterm.agent.tools.ToolRegistry
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * Document analysis tool parameters
 */
data class DocumentAnalysisToolParams(
    val action: String, // "analyze", "summarize", "extract", "question", "info"
    val filePath: String? = null, // File path to analyze
    val content: String? = null, // Direct content to analyze (alternative to filePath)
    val question: String? = null, // Question to ask about the document (for question action)
    val format: String? = null, // Document format hint (markdown, html, text, pdf)
    val maxLength: Int? = null // Maximum length for summary/extraction
)

/**
 * Document analysis result
 */
data class DocumentAnalysisResult(
    val filePath: String?,
    val format: String,
    val wordCount: Int,
    val characterCount: Int,
    val lineCount: Int,
    val keyInformation: List<String>,
    val summary: String?,
    val structuredData: StructuredData?,
    val answer: String?,
    val metadata: DocumentMetadata
)

data class StructuredData(
    val headings: List<Heading>,
    val tables: List<Table>,
    val lists: List<ListItem>,
    val codeBlocks: List<CodeBlock>
)

data class Heading(
    val level: Int,
    val text: String,
    val lineNumber: Int
)

data class Table(
    val headers: List<String>,
    val rows: List<List<String>>,
    val lineNumber: Int
)

data class ListItem(
    val text: String,
    val level: Int,
    val lineNumber: Int
)

data class CodeBlock(
    val language: String?,
    val code: String,
    val lineNumber: Int
)

data class DocumentMetadata(
    val title: String?,
    val author: String?,
    val date: String?,
    val tags: List<String>,
    val sections: List<String>
)

class DocumentAnalysisToolInvocation(
    toolParams: DocumentAnalysisToolParams,
    private val workspaceRoot: String,
    private val apiClient: PpeApiClient? = null
) : ToolInvocation<DocumentAnalysisToolParams, ToolResult> {
    
    override val params: DocumentAnalysisToolParams = toolParams
    
    override fun getDescription(): String {
        return when (params.action) {
            "analyze" -> "Analyzing document: ${params.filePath ?: "content"}"
            "summarize" -> "Summarizing document: ${params.filePath ?: "content"}"
            "extract" -> "Extracting structured data from: ${params.filePath ?: "content"}"
            "question" -> "Answering question about document: ${params.question}"
            "info" -> "Getting document information: ${params.filePath ?: "content"}"
            else -> "Analyzing document"
        }
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return params.filePath?.let { path ->
            val file = if (File(path).isAbsolute) File(path) else File(workspaceRoot, path)
            if (file.exists()) listOf(ToolLocation(file.absolutePath)) else emptyList()
        } ?: emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult = withContext(Dispatchers.IO) {
        if (signal?.isAborted() == true) {
            return@withContext ToolResult(
                llmContent = "Document analysis cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            // Get document content
            val content = params.content ?: params.filePath?.let { path ->
                val file = if (File(path).isAbsolute) File(path) else File(workspaceRoot, path)
                if (!file.exists()) {
                    return@withContext ToolResult(
                        llmContent = "File not found: $path",
                        returnDisplay = "Error: File not found",
                        error = ToolError(
                            message = "File not found: $path",
                            type = ToolErrorType.FILE_NOT_FOUND
                        )
                    )
                }
                if (!file.canRead()) {
                    return@withContext ToolResult(
                        llmContent = "Permission denied: $path",
                        returnDisplay = "Error: Permission denied",
                        error = ToolError(
                            message = "Permission denied",
                            type = ToolErrorType.PERMISSION_DENIED
                        )
                    )
                }
                file.readText()
            } ?: return@withContext ToolResult(
                llmContent = "Either filePath or content must be provided",
                returnDisplay = "Error: Missing input",
                error = ToolError(
                    message = "Either filePath or content must be provided",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
            
            // Detect format
            val format = params.format ?: detectFormat(params.filePath, content)
            
            // Parse content based on format
            val parsedContent = parseContent(content, format)
            
            // Perform requested action
            val result = when (params.action) {
                "analyze" -> performFullAnalysis(parsedContent, format, params.filePath)
                "summarize" -> performSummarization(parsedContent, format, params.maxLength)
                "extract" -> performExtraction(parsedContent, format)
                "question" -> performQuestionAnswering(parsedContent, format, params.question)
                "info" -> performInfoExtraction(parsedContent, format, params.filePath)
                else -> {
                    return@withContext ToolResult(
                        llmContent = "Unknown action: ${params.action}",
                        returnDisplay = "Error: Unknown action",
                        error = ToolError(
                            message = "Unknown action: ${params.action}",
                            type = ToolErrorType.INVALID_PARAMETERS
                        )
                    )
                }
            }
            
            DebugLogger.i("DocumentAnalysisTool", "Document analysis completed", mapOf(
                "action" to params.action,
                "format" to format,
                "file_path" to (params.filePath ?: "content")
            ))
            
            result
        } catch (e: Exception) {
            DebugLogger.e("DocumentAnalysisTool", "Error analyzing document", exception = e)
            ToolResult(
                llmContent = "Error analyzing document: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun detectFormat(filePath: String?, content: String): String {
        // Check file extension
        filePath?.let { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            when (ext) {
                "md", "markdown" -> return "markdown"
                "html", "htm" -> return "html"
                "txt", "text" -> return "text"
                "pdf" -> return "pdf"
            }
        }
        
        // Check content patterns
        return when {
            content.contains(Regex("<html|<body|<div", RegexOption.IGNORE_CASE)) -> "html"
            content.contains(Regex("^#+\\s|^\\*\\s|^\\-\\s|^\\d+\\.\\s", RegexOption.MULTILINE)) -> "markdown"
            content.contains(Regex("%PDF")) -> "pdf"
            else -> "text"
        }
    }
    
    private fun parseContent(content: String, format: String): ParsedContent {
        return when (format.lowercase()) {
            "markdown" -> parseMarkdown(content)
            "html" -> parseHtml(content)
            "text" -> parseText(content)
            "pdf" -> parseText(content) // PDF parsing would require a library
            else -> parseText(content)
        }
    }
    
    private fun parseMarkdown(content: String): ParsedContent {
        val lines = content.lines()
        val headings = mutableListOf<Heading>()
        val lists = mutableListOf<ListItem>()
        val codeBlocks = mutableListOf<CodeBlock>()
        val tables = mutableListOf<Table>()
        
        var inCodeBlock = false
        var codeBlockLanguage: String? = null
        var codeBlockStart = 0
        val codeBlockLines = mutableListOf<String>()
        
        lines.forEachIndexed { index, line ->
            // Headings
            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (headingMatch != null) {
                headings.add(Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim(),
                    lineNumber = index + 1
                ))
            }
            
            // Code blocks
            val codeBlockStartMatch = Regex("^```(\\w+)?$").find(line)
            if (codeBlockStartMatch != null && !inCodeBlock) {
                inCodeBlock = true
                codeBlockLanguage = codeBlockStartMatch.groupValues[1].takeIf { it.isNotEmpty() }
                codeBlockStart = index + 1
                codeBlockLines.clear()
            } else if (line.trim() == "```" && inCodeBlock) {
                inCodeBlock = false
                codeBlocks.add(CodeBlock(
                    language = codeBlockLanguage,
                    code = codeBlockLines.joinToString("\n"),
                    lineNumber = codeBlockStart
                ))
                codeBlockLines.clear()
            } else if (inCodeBlock) {
                codeBlockLines.add(line)
            }
            
            // Lists
            val listMatch = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.+)$").find(line)
            if (listMatch != null && !inCodeBlock) {
                val indent = listMatch.groupValues[1].length
                lists.add(ListItem(
                    text = listMatch.groupValues[3].trim(),
                    level = indent / 2,
                    lineNumber = index + 1
                ))
            }
            
            // Tables
            if (line.contains("|") && !inCodeBlock) {
                val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.size > 1) {
                    // Check if it's a header separator
                    if (cells.all { it.matches(Regex("^:?-+:?$")) }) {
                        // This is a separator, previous line was header
                        if (tables.isEmpty() || tables.last().rows.isNotEmpty()) {
                            // Start new table
                            val prevLine = lines.getOrNull(index - 1)
                            if (prevLine != null) {
                                val headers = prevLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                                tables.add(Table(
                                    headers = headers,
                                    rows = mutableListOf(),
                                    lineNumber = index
                                ))
                            }
                        }
                    } else if (tables.isNotEmpty() && tables.last().rows.size < 50) {
                        // Add row to last table
                        val lastTable = tables.last()
                        val newRows = lastTable.rows.toMutableList()
                        newRows.add(cells)
                        tables[tables.size - 1] = lastTable.copy(rows = newRows)
                    }
                }
            }
        }
        
        // Close any open code block
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            codeBlocks.add(CodeBlock(
                language = codeBlockLanguage,
                code = codeBlockLines.joinToString("\n"),
                lineNumber = codeBlockStart
            ))
        }
        
        return ParsedContent(
            text = content,
            headings = headings,
            lists = lists,
            codeBlocks = codeBlocks,
            tables = tables
        )
    }
    
    private fun parseHtml(content: String): ParsedContent {
        // Simple HTML parsing (strip tags and extract structure)
        val text = content.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        val headings = mutableListOf<Heading>()
        Regex("<h([1-6])[^>]*>(.*?)</h[1-6]>", RegexOption.IGNORE_CASE).findAll(content).forEach { match ->
            headings.add(Heading(
                level = match.groupValues[1].toIntOrNull() ?: 1,
                text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim(),
                lineNumber = 0 // HTML doesn't have line numbers easily
            ))
        }
        
        return ParsedContent(
            text = text,
            headings = headings,
            lists = emptyList(),
            codeBlocks = emptyList(),
            tables = emptyList()
        )
    }
    
    private fun parseText(content: String): ParsedContent {
        return ParsedContent(
            text = content,
            headings = emptyList(),
            lists = emptyList(),
            codeBlocks = emptyList(),
            tables = emptyList()
        )
    }
    
    private suspend fun performFullAnalysis(
        parsedContent: ParsedContent,
        format: String,
        filePath: String?
    ): ToolResult {
        val wordCount = parsedContent.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val charCount = parsedContent.text.length
        val lineCount = parsedContent.text.lines().size
        
        // Extract key information using AI if available
        val keyInformation = if (apiClient != null) {
            extractKeyInformationWithAI(parsedContent.text, apiClient)
        } else {
            extractKeyInformationBasic(parsedContent.text)
        }
        
        val metadata = extractMetadata(parsedContent, filePath)
        
        val output = buildString {
            appendLine("# Document Analysis")
            appendLine()
            if (filePath != null) {
                appendLine("**File:** `$filePath`")
            }
            appendLine("**Format:** $format")
            appendLine("**Word Count:** $wordCount")
            appendLine("**Character Count:** $charCount")
            appendLine("**Line Count:** $lineCount")
            appendLine()
            
            appendLine("## Key Information")
            appendLine()
            keyInformation.forEach { info ->
                appendLine("- $info")
            }
            appendLine()
            
            if (parsedContent.headings.isNotEmpty()) {
                appendLine("## Document Structure")
                appendLine()
                parsedContent.headings.take(20).forEach { heading ->
                    appendLine("${"  ".repeat(heading.level - 1)}- **${"#".repeat(heading.level)}** ${heading.text}")
                }
                appendLine()
            }
            
            if (parsedContent.tables.isNotEmpty()) {
                appendLine("## Tables Found")
                appendLine()
                appendLine("**Count:** ${parsedContent.tables.size}")
                parsedContent.tables.take(3).forEachIndexed { index, table ->
                    appendLine("### Table ${index + 1}")
                    appendLine("**Headers:** ${table.headers.joinToString(", ")}")
                    appendLine("**Rows:** ${table.rows.size}")
                    appendLine()
                }
            }
            
            if (parsedContent.codeBlocks.isNotEmpty()) {
                appendLine("## Code Blocks Found")
                appendLine()
                appendLine("**Count:** ${parsedContent.codeBlocks.size}")
                parsedContent.codeBlocks.take(3).forEachIndexed { index, block ->
                    appendLine("### Code Block ${index + 1}")
                    if (block.language != null) {
                        appendLine("**Language:** ${block.language}")
                    }
                    appendLine("**Size:** ${block.code.length} characters")
                    appendLine()
                }
            }
            
            if (metadata.title != null || metadata.author != null) {
                appendLine("## Metadata")
                appendLine()
                metadata.title?.let { appendLine("- **Title:** $it") }
                metadata.author?.let { appendLine("- **Author:** $it") }
                metadata.date?.let { appendLine("- **Date:** $it") }
                if (metadata.tags.isNotEmpty()) {
                    appendLine("- **Tags:** ${metadata.tags.joinToString(", ")}")
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Analyzed: $wordCount words, ${parsedContent.headings.size} headings"
        )
    }
    
    private suspend fun performSummarization(
        parsedContent: ParsedContent,
        format: String,
        maxLength: Int?
    ): ToolResult {
        val maxLen = maxLength ?: 500
        
        val summary = if (apiClient != null && parsedContent.text.length > 1000) {
            // Use AI for long documents
            summarizeWithAI(parsedContent.text, maxLen, apiClient)
        } else {
            // Basic summarization for short documents
            summarizeBasic(parsedContent.text, maxLen)
        }
        
        val output = buildString {
            appendLine("# Document Summary")
            appendLine()
            appendLine("**Original Length:** ${parsedContent.text.length} characters")
            appendLine("**Summary Length:** ${summary.length} characters")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine(summary)
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Summary: ${summary.length} chars"
        )
    }
    
    private fun performExtraction(
        parsedContent: ParsedContent,
        format: String
    ): ToolResult {
        val structuredData = StructuredData(
            headings = parsedContent.headings,
            tables = parsedContent.tables,
            lists = parsedContent.lists,
            codeBlocks = parsedContent.codeBlocks
        )
        
        val output = buildString {
            appendLine("# Structured Data Extraction")
            appendLine()
            appendLine("## Headings (${parsedContent.headings.size})")
            appendLine()
            parsedContent.headings.forEach { heading ->
                appendLine("${"  ".repeat(heading.level - 1)}- [Level ${heading.level}] ${heading.text}")
            }
            appendLine()
            
            if (parsedContent.tables.isNotEmpty()) {
                appendLine("## Tables (${parsedContent.tables.size})")
                appendLine()
                parsedContent.tables.forEachIndexed { index, table ->
                    appendLine("### Table ${index + 1}")
                    appendLine("**Headers:** ${table.headers.joinToString(" | ")}")
                    appendLine("**Rows:** ${table.rows.size}")
                    if (table.rows.isNotEmpty()) {
                        appendLine()
                        appendLine("| ${table.headers.joinToString(" | ")} |")
                        appendLine("| ${table.headers.map { "---" }.joinToString(" | ")} |")
                        table.rows.take(5).forEach { row ->
                            appendLine("| ${row.joinToString(" | ")} |")
                        }
                        if (table.rows.size > 5) {
                            appendLine("| ... (${table.rows.size - 5} more rows) |")
                        }
                    }
                    appendLine()
                }
            }
            
            if (parsedContent.lists.isNotEmpty()) {
                appendLine("## Lists (${parsedContent.lists.size} items)")
                appendLine()
                parsedContent.lists.take(20).forEach { item ->
                    appendLine("${"  ".repeat(item.level)}- ${item.text}")
                }
                if (parsedContent.lists.size > 20) {
                    appendLine("... (${parsedContent.lists.size - 20} more items)")
                }
                appendLine()
            }
            
            if (parsedContent.codeBlocks.isNotEmpty()) {
                appendLine("## Code Blocks (${parsedContent.codeBlocks.size})")
                appendLine()
                parsedContent.codeBlocks.take(5).forEachIndexed { index, block ->
                    appendLine("### Code Block ${index + 1}")
                    if (block.language != null) {
                        appendLine("**Language:** ${block.language}")
                    }
                    appendLine("```${block.language ?: ""}")
                    appendLine(block.code.take(200))
                    if (block.code.length > 200) {
                        appendLine("...")
                    }
                    appendLine("```")
                    appendLine()
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Extracted: ${parsedContent.headings.size} headings, ${parsedContent.tables.size} tables"
        )
    }
    
    private suspend fun performQuestionAnswering(
        parsedContent: ParsedContent,
        format: String,
        question: String?
    ): ToolResult {
        if (question == null || question.isBlank()) {
            return ToolResult(
                llmContent = "Question is required for question action",
                returnDisplay = "Error: Question required",
                error = ToolError(
                    message = "Question is required",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        if (apiClient == null) {
            // Basic keyword matching if no AI available
            val answer = answerQuestionBasic(parsedContent.text, question)
            return ToolResult(
                llmContent = buildString {
                    appendLine("# Question Answering")
                    appendLine()
                    appendLine("**Question:** $question")
                    appendLine()
                    appendLine("## Answer")
                    appendLine()
                    appendLine(answer)
                },
                returnDisplay = "Answer found (basic)"
            )
        }
        
        // Use AI for question answering
        val answer = answerQuestionWithAI(parsedContent.text, question, apiClient)
        
        val output = buildString {
            appendLine("# Question Answering")
            appendLine()
            appendLine("**Question:** $question")
            appendLine()
            appendLine("## Answer")
            appendLine()
            appendLine(answer)
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Answer generated"
        )
    }
    
    private fun performInfoExtraction(
        parsedContent: ParsedContent,
        format: String,
        filePath: String?
    ): ToolResult {
        val wordCount = parsedContent.text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val charCount = parsedContent.text.length
        val lineCount = parsedContent.text.lines().size
        val metadata = extractMetadata(parsedContent, filePath)
        
        val output = buildString {
            appendLine("# Document Information")
            appendLine()
            if (filePath != null) {
                appendLine("**File:** `$filePath`")
            }
            appendLine("**Format:** $format")
            appendLine("**Word Count:** $wordCount")
            appendLine("**Character Count:** $charCount")
            appendLine("**Line Count:** $lineCount")
            appendLine("**Headings:** ${parsedContent.headings.size}")
            appendLine("**Tables:** ${parsedContent.tables.size}")
            appendLine("**Lists:** ${parsedContent.lists.size}")
            appendLine("**Code Blocks:** ${parsedContent.codeBlocks.size}")
            appendLine()
            
            if (metadata.title != null) {
                appendLine("## Title")
                appendLine(metadata.title)
                appendLine()
            }
            
            if (parsedContent.headings.isNotEmpty()) {
                appendLine("## Document Structure")
                appendLine()
                parsedContent.headings.take(10).forEach { heading ->
                    appendLine("${"  ".repeat(heading.level - 1)}- ${heading.text}")
                }
            }
        }
        
        return ToolResult(
            llmContent = output,
            returnDisplay = "Info: $wordCount words, ${parsedContent.headings.size} headings"
        )
    }
    
    private fun extractKeyInformationBasic(text: String): List<String> {
        // Basic extraction: look for important patterns
        val info = mutableListOf<String>()
        
        // Extract email addresses
        val emails = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b").findAll(text)
        emails.take(5).forEach { info.add("Email: ${it.value}") }
        
        // Extract URLs
        val urls = Regex("https?://[^\\s]+").findAll(text)
        urls.take(5).forEach { info.add("URL: ${it.value}") }
        
        // Extract dates
        val dates = Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}").findAll(text)
        dates.take(5).forEach { info.add("Date: ${it.value}") }
        
        return info.distinct().take(10)
    }
    
    private suspend fun extractKeyInformationWithAI(text: String, apiClient: PpeApiClient): List<String> {
        return try {
            val prompt = """
                Analyze the following document and extract the 10 most important pieces of information.
                Return only a JSON array of strings, each string being one key piece of information.
                
                Document:
                ${text.take(8000)}
                
                Return format: ["info1", "info2", ...]
            """.trimIndent()
            
            val messages = listOf(Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            ))
            
            val result = apiClient.callApi(messages, temperature = 0.3)
            result.getOrNull()?.text?.let { response ->
                // Try to parse JSON array
                try {
                    val jsonStart = response.indexOf('[')
                    val jsonEnd = response.lastIndexOf(']') + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        val json = JSONArray(response.substring(jsonStart, jsonEnd))
                        (0 until json.length()).mapNotNull { json.getString(it) }
                    } else {
                        // Fallback: split by lines
                        response.lines().filter { it.isNotBlank() }.take(10)
                    }
                } catch (e: Exception) {
                    response.lines().filter { it.isNotBlank() }.take(10)
                }
            } ?: extractKeyInformationBasic(text)
        } catch (e: Exception) {
            DebugLogger.w("DocumentAnalysisTool", "AI extraction failed, using basic", exception = e)
            extractKeyInformationBasic(text)
        }
    }
    
    private fun summarizeBasic(text: String, maxLength: Int): String {
        val sentences = text.split(Regex("[.!?]+")).filter { it.trim().isNotEmpty() }
        val summary = sentences.take(5).joinToString(". ") + "."
        return if (summary.length > maxLength) {
            summary.take(maxLength - 3) + "..."
        } else {
            summary
        }
    }
    
    private suspend fun summarizeWithAI(text: String, maxLength: Int, apiClient: PpeApiClient): String {
        return try {
            val prompt = """
                Summarize the following document in approximately ${maxLength} characters or less.
                Focus on the main points and key information.
                
                Document:
                ${text.take(15000)}
            """.trimIndent()
            
            val messages = listOf(Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            ))
            
            val result = apiClient.callApi(messages, temperature = 0.5)
            result.getOrNull()?.text?.take(maxLength) ?: summarizeBasic(text, maxLength)
        } catch (e: Exception) {
            DebugLogger.w("DocumentAnalysisTool", "AI summarization failed, using basic", exception = e)
            summarizeBasic(text, maxLength)
        }
    }
    
    private fun answerQuestionBasic(text: String, question: String): String {
        // Simple keyword matching
        val questionWords = question.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
        val sentences = text.split(Regex("[.!?]+"))
        
        val relevantSentences = sentences.filter { sentence ->
            questionWords.any { word -> sentence.lowercase().contains(word) }
        }
        
        return if (relevantSentences.isNotEmpty()) {
            relevantSentences.take(3).joinToString(". ") + "."
        } else {
            "I couldn't find specific information to answer this question in the document."
        }
    }
    
    private suspend fun answerQuestionWithAI(text: String, question: String, apiClient: PpeApiClient): String {
        return try {
            val prompt = """
                Based on the following document, answer this question: "$question"
                
                Document:
                ${text.take(15000)}
                
                Provide a clear, concise answer based only on the information in the document.
            """.trimIndent()
            
            val messages = listOf(Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            ))
            
            val result = apiClient.callApi(messages, temperature = 0.3)
            result.getOrNull()?.text ?: answerQuestionBasic(text, question)
        } catch (e: Exception) {
            DebugLogger.w("DocumentAnalysisTool", "AI question answering failed, using basic", exception = e)
            answerQuestionBasic(text, question)
        }
    }
    
    private fun extractMetadata(parsedContent: ParsedContent, filePath: String?): DocumentMetadata {
        val title = parsedContent.headings.firstOrNull()?.text
        val sections = parsedContent.headings.map { it.text }
        
        // Try to extract author/date from content
        val authorMatch = Regex("(?:author|by|written by):\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(parsedContent.text)
        val author = authorMatch?.groupValues?.get(1)?.trim()
        
        val dateMatch = Regex("(?:date|created|published):\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(parsedContent.text)
        val date = dateMatch?.groupValues?.get(1)?.trim()
        
        val tags = mutableListOf<String>()
        val tagMatch = Regex("(?:tags?|keywords?):\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(parsedContent.text)
        tagMatch?.groupValues?.get(1)?.let { tagStr ->
            tags.addAll(tagStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        
        return DocumentMetadata(
            title = title,
            author = author,
            date = date,
            tags = tags,
            sections = sections
        )
    }
    
    private data class ParsedContent(
        val text: String,
        val headings: List<Heading>,
        val lists: List<ListItem>,
        val codeBlocks: List<CodeBlock>,
        val tables: List<Table>
    )
}

/**
 * Document analysis tool
 */
class DocumentAnalysisTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val apiClient: PpeApiClient? = null
) : DeclarativeTool<DocumentAnalysisToolParams, ToolResult>() {
    
    override val name: String = "document_analysis"
    override val displayName: String = "Document Analysis"
    override val description: String = """
        Analyze documents, code files, and text content. Extract information, summarize, answer questions, and extract structured data.
        
        Actions:
        - analyze: Full document analysis with key information extraction
        - summarize: Generate a summary of the document
        - extract: Extract structured data (headings, tables, lists, code blocks)
        - question: Answer a specific question about the document content
        - info: Get basic document information (word count, structure, etc.)
        
        Supported formats:
        - Markdown (.md, .markdown)
        - HTML (.html, .htm)
        - Plain text (.txt, .text)
        - PDF (.pdf) - basic support
        
        Features:
        - Automatic format detection
        - Key information extraction
        - Document summarization (AI-powered for long documents)
        - Structured data extraction (tables, lists, headings, code blocks)
        - Question answering about document content
        - Metadata extraction (title, author, date, tags)
        
        Examples:
        - document_analysis(action="analyze", filePath="README.md") - Full analysis
        - document_analysis(action="summarize", filePath="document.txt", maxLength=300) - Summarize
        - document_analysis(action="extract", filePath="report.md") - Extract structured data
        - document_analysis(action="question", filePath="doc.md", question="What is the main topic?") - Answer question
        - document_analysis(action="info", content="...") - Get info from direct content
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "action" to PropertySchema(
                type = "string",
                description = "Action to perform: 'analyze', 'summarize', 'extract', 'question', or 'info'",
                enum = listOf("analyze", "summarize", "extract", "question", "info")
            ),
            "filePath" to PropertySchema(
                type = "string",
                description = "Path to the document file (relative to workspace or absolute)"
            ),
            "content" to PropertySchema(
                type = "string",
                description = "Direct document content to analyze (alternative to filePath)"
            ),
            "question" to PropertySchema(
                type = "string",
                description = "Question to answer about the document (required for 'question' action)"
            ),
            "format" to PropertySchema(
                type = "string",
                description = "Document format hint: 'markdown', 'html', 'text', or 'pdf' (auto-detected if not provided)"
            ),
            "maxLength" to PropertySchema(
                type = "integer",
                description = "Maximum length for summary (for 'summarize' action, default: 500)"
            )
        ),
        required = listOf("action")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: DocumentAnalysisToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<DocumentAnalysisToolParams, ToolResult> {
        return DocumentAnalysisToolInvocation(params, workspaceRoot, apiClient)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): DocumentAnalysisToolParams {
        return DocumentAnalysisToolParams(
            action = params["action"] as? String ?: "analyze",
            filePath = params["filePath"] as? String,
            content = params["content"] as? String,
            question = params["question"] as? String,
            format = params["format"] as? String,
            maxLength = (params["maxLength"] as? Number)?.toInt()
        )
    }
}
