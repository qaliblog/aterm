package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.ppe.PpeApiClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Documentation generation tool parameters
 */
data class DocumentationGenerationToolParams(
    val projectPath: String? = null, // Project root path
    val filePath: String? = null,    // Specific file to document
    val action: String = "generate", // "generate", "extract", "update_readme"
    val language: String? = null,   // Language hint
    val outputPath: String? = null, // Where to write generated docs
    val format: String? = null      // "markdown", "html", "text"
)

class DocumentationGenerationToolInvocation(
    toolParams: DocumentationGenerationToolParams,
    private val workspaceRoot: String,
    private val apiClient: PpeApiClient? = null
) : ToolInvocation<DocumentationGenerationToolParams, ToolResult> {

    override val params: DocumentationGenerationToolParams = toolParams

    override fun getDescription(): String {
        val target = params.filePath ?: params.projectPath ?: "project"
        return "Generating documentation for: $target (action=${params.action})"
    }

    override fun toolLocations(): List<ToolLocation> {
        val base = params.projectPath ?: params.filePath ?: return emptyList()
        val file = if (File(base).isAbsolute) File(base) else File(workspaceRoot, base)
        return listOf(ToolLocation(file.absolutePath))
    }

    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult = withContext(Dispatchers.IO) {
        if (signal?.isAborted() == true) {
            return@withContext ToolResult(
                llmContent = "Documentation generation cancelled",
                returnDisplay = "Cancelled"
            )
        }

        try {
            val projectDir = params.projectPath?.let { path ->
                if (File(path).isAbsolute) File(path) else File(workspaceRoot, path)
            } ?: File(workspaceRoot)

            if (!projectDir.exists() || !projectDir.isDirectory) {
                return@withContext ToolResult(
                    llmContent = "Project path does not exist or is not a directory: ${projectDir.absolutePath}",
                    returnDisplay = "Error: Invalid project path",
                    error = ToolError(
                        message = "Invalid project path",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }

            val files = collectSourceFiles(projectDir, params.filePath, params.language)
            if (files.isEmpty()) {
                return@withContext ToolResult(
                    llmContent = "No source files found to document in ${projectDir.absolutePath}",
                    returnDisplay = "No files found"
                )
            }

            updateOutput?.invoke("📝 Generating documentation for ${files.size} file(s)...")

            val docs = when (params.action) {
                "extract" -> extractDocstrings(files)
                "update_readme" -> generateProjectOverview(projectDir, files)
                else -> generateApiDocs(projectDir, files)
            }

            val formatted = formatOutput(docs, params.format ?: "markdown")

            val outPath = params.outputPath?.let { path ->
                val file = if (File(path).isAbsolute) File(path) else File(projectDir, path)
                file.parentFile?.mkdirs()
                file.writeText(formatted)
                file.absolutePath
            }

            val display = if (outPath != null) "Docs written to $outPath" else "Documentation generated"

            DebugLogger.i("DocumentationGenerationTool", "Docs generated", mapOf(
                "files" to files.size,
                "action" to params.action,
                "outputPath" to (outPath ?: "inline")
            ))

            ToolResult(
                llmContent = formatted,
                returnDisplay = display
            )
        } catch (e: Exception) {
            DebugLogger.e("DocumentationGenerationTool", "Error generating documentation", exception = e)
            ToolResult(
                llmContent = "Error generating documentation: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }

    private fun collectSourceFiles(root: File, singleFilePath: String?, language: String?): List<File> {
        if (singleFilePath != null) {
            val file = if (File(singleFilePath).isAbsolute) File(singleFilePath) else File(root, singleFilePath)
            return if (file.exists() && file.isFile) listOf(file) else emptyList()
        }

        val exts = when (language?.lowercase()) {
            "kotlin" -> listOf(".kt", ".kts")
            "java" -> listOf(".java")
            "python" -> listOf(".py")
            "javascript", "js" -> listOf(".js", ".jsx")
            "typescript", "ts" -> listOf(".ts", ".tsx")
            else -> listOf(".kt", ".kts", ".java", ".py", ".js", ".jsx", ".ts", ".tsx")
        }

        return root.walkTopDown()
            .filter { it.isFile && exts.any(it.name::endsWith) }
            .take(200)
            .toList()
    }

    private fun extractDocstrings(files: List<File>): String {
        val sb = StringBuilder()
        sb.appendLine("# Extracted Documentation")
        sb.appendLine()
        files.forEach { file ->
            val rel = file.name
            val content = file.readText()
            val docs = extractFileDocstrings(content, file.extension.lowercase())
            if (docs.isNotEmpty()) {
                sb.appendLine("## $rel")
                sb.appendLine()
                docs.forEach { (name, doc) ->
                    sb.appendLine("### $name")
                    sb.appendLine()
                    sb.appendLine(doc.trim())
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    private fun extractFileDocstrings(content: String, ext: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = content.lines()
        when (ext) {
            "kt", "kts", "java" -> {
                var pendingComment = StringBuilder()
                var inKdoc = false
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("/**")) {
                        inKdoc = true
                        pendingComment = StringBuilder()
                    }
                    if (inKdoc) {
                        pendingComment.appendLine(trimmed.removePrefix("/**").removePrefix("*").removeSuffix("*/").trim())
                        if (trimmed.endsWith("*/")) {
                            inKdoc = false
                        }
                    } else if (pendingComment.isNotEmpty() && ("fun " in trimmed || "class " in trimmed || "interface " in trimmed)) {
                        val name = trimmed
                            .replace(Regex("<(.*)?>"), "")
                            .take(120)
                        result[name] = pendingComment.toString()
                        pendingComment = StringBuilder()
                    }
                }
            }
            "py" -> {
                var inDoc = false
                var pendingName: String? = null
                val docBuilder = StringBuilder()
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("def ") || trimmed.startsWith("class ")) {
                        pendingName = trimmed.take(120)
                    } else if (pendingName != null && (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''"))) {
                        inDoc = true
                        docBuilder.clear()
                    } else if (inDoc && (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''"))) {
                        inDoc = false
                        result[pendingName!!] = docBuilder.toString()
                        pendingName = null
                        docBuilder.clear()
                    } else if (inDoc) {
                        docBuilder.appendLine(trimmed)
                    }
                }
            }
        }
        return result
    }

    private fun generateProjectOverview(projectDir: File, files: List<File>): String {
        val sb = StringBuilder()
        sb.appendLine("# Project Overview")
        sb.appendLine()
        sb.appendLine("**Root:** `${projectDir.absolutePath}`")
        sb.appendLine()
        sb.appendLine("## Modules / Packages")
        sb.appendLine()
        val byDir = files.groupBy { it.parentFile?.relativeTo(projectDir)?.path ?: "." }
        byDir.forEach { (dir, dirFiles) ->
            sb.appendLine("- `$dir` (${dirFiles.size} file(s))")
        }
        sb.appendLine()
        sb.appendLine("## Public API (top-level)")
        sb.appendLine()
        files.take(50).forEach { file ->
            val rel = file.relativeTo(projectDir).path
            val api = extractPublicApi(file.readText(), file.extension.lowercase())
            if (api.isNotEmpty()) {
                sb.appendLine("### `$rel`")
                sb.appendLine()
                api.forEach { line -> sb.appendLine("- $line") }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun extractPublicApi(content: String, ext: String): List<String> {
        val out = mutableListOf<String>()
        val lines = content.lines()
        when (ext) {
            "kt", "kts" -> {
                lines.filter { it.trim().startsWith("public ") || it.trim().startsWith("fun ") || it.trim().startsWith("class ") }
                    .take(100)
                    .forEach { out.add(it.trim()) }
            }
            "java" -> {
                lines.filter { it.trim().startsWith("public ") }
                    .take(100)
                    .forEach { out.add(it.trim()) }
            }
            "py" -> {
                lines.filter { it.trim().startsWith("def ") || it.trim().startsWith("class ") }
                    .take(100)
                    .forEach { out.add(it.trim()) }
            }
        }
        return out
    }

    private fun generateApiDocs(projectDir: File, files: List<File>): String {
        val sb = StringBuilder()
        sb.appendLine("# API Documentation")
        sb.appendLine()
        files.take(100).forEach { file ->
            val rel = file.relativeTo(projectDir).path
            sb.appendLine("## `$rel`")
            sb.appendLine()
            val ext = file.extension.lowercase()
            val content = file.readText()
            val api = extractPublicApi(content, ext)
            if (api.isNotEmpty()) {
                api.forEach { line -> sb.appendLine("- `$line`") }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun formatOutput(content: String, format: String): String {
        return when (format.lowercase()) {
            "markdown", "md" -> content
            "text", "txt" -> content.replace(Regex("[`*_#>"]"), "")
            "html" -> {
                """<html><body><pre>${escapeHtml(content)}</pre></body></html>""".trimIndent()
            }
            else -> content
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}

class DocumentationGenerationTool(
    private val workspaceRoot: String = alpineDir().absolutePath,
    private val apiClient: PpeApiClient? = null
) : DeclarativeTool<DocumentationGenerationToolParams, ToolResult>() {

    override val name: String = "documentation_generation"
    override val displayName: String = "Documentation Generation"
    override val description: String = """
        Auto-generate documentation from code.

        Actions:
        - generate: Generate API docs for project or file
        - extract: Extract existing docstrings / KDoc / Javadoc into a doc file
        - update_readme: Generate a high-level project overview suitable for README

        Supports multiple languages (Kotlin, Java, Python, JS/TS) and outputs Markdown by default.
    """.trimIndent()

    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path (relative to workspace or absolute)"
            ),
            "filePath" to PropertySchema(
                type = "string",
                description = "Specific file to document (relative to workspace or absolute)"
            ),
            "action" to PropertySchema(
                type = "string",
                description = "Action to perform: generate, extract, update_readme",
                enum = listOf("generate", "extract", "update_readme")
            ),
            "language" to PropertySchema(
                type = "string",
                description = "Language hint: kotlin, java, python, javascript, typescript"
            ),
            "outputPath" to PropertySchema(
                type = "string",
                description = "Where to write generated docs (relative to project or absolute). If omitted, returns content only."
            ),
            "format" to PropertySchema(
                type = "string",
                description = "Output format: markdown (default), html, text"
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
        params: DocumentationGenerationToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<DocumentationGenerationToolParams, ToolResult> {
        return DocumentationGenerationToolInvocation(params, workspaceRoot, apiClient)
    }

    override fun validateAndConvertParams(params: Map<String, Any>): DocumentationGenerationToolParams {
        return DocumentationGenerationToolParams(
            projectPath = params["projectPath"] as? String,
            filePath = params["filePath"] as? String,
            action = params["action"] as? String ?: "generate",
            language = params["language"] as? String,
            outputPath = params["outputPath"] as? String,
            format = params["format"] as? String
        )
    }
}
