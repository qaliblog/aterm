package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.utils.AutoErrorDetection
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WriteFileToolParams(
    val file_path: String,
    val content: String,
    val modified_by_user: Boolean = false,
    val ai_proposed_content: String? = null
)

class WriteFileToolInvocation(
    toolParams: WriteFileToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<WriteFileToolParams, ToolResult> {
    
    override val params: WriteFileToolParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.file_path).absolutePath
    
    override fun getDescription(): String {
        return "Writing to file: ${params.file_path}"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return listOf(ToolLocation(resolvedPath))
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "File write cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val file = File(resolvedPath)
        
        return try {
            // Read old content BEFORE writing (for patch diff)
            val oldContent = if (file.exists() && file.isFile) {
                try {
                    file.readText()
                } catch (e: Exception) {
                    android.util.Log.w("WriteFileTool", "Failed to read old content: ${e.message}")
                    ""
                }
            } else {
                ""
            }
            
            // Create parent directories if needed
            file.parentFile?.mkdirs()
            
            // Write content
            file.writeText(params.content)
            
            updateOutput?.invoke("File written successfully")
            
            // Generate patch diff if file existed before and content changed
            val patchDiff = if (oldContent.isNotEmpty() && oldContent != params.content) {
                com.qali.aterm.agent.utils.PatchDiffUtils.generatePatch(
                    params.file_path,
                    oldContent,
                    params.content
                )
            } else {
                ""
            }
            
            // Analyze code dependencies and extract important metadata
            val codeMetadata = try {
                withContext(Dispatchers.IO) {
                    CodeDependencyAnalyzer.analyzeFile(
                        filePath = params.file_path,
                        content = params.content,
                        workspaceRoot = workspaceRoot
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("WriteFileTool", "Error in code dependency analysis: ${e.message}")
                null
            }
            
            // Update dependency matrix
            codeMetadata?.let { metadata ->
                try {
                    withContext(Dispatchers.IO) {
                        CodeDependencyAnalyzer.updateDependencyMatrix(workspaceRoot, metadata)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WriteFileTool", "Error updating dependency matrix: ${e.message}")
                }
            }
            
            // Automatically detect errors after file creation/modification
            val errorTasks = try {
                withContext(Dispatchers.IO) {
                    AutoErrorDetection.detectAndCreateFixTasks(
                        filePath = params.file_path,
                        workspaceRoot = workspaceRoot
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("WriteFileTool", "Error in auto error detection: ${e.message}")
                emptyList()
            }
            
            // Determine if this is a new file or modified file
            val isNewFile = oldContent.isEmpty()
            
            // Build formatted file change notification (cursor-cli style)
            val fileChangeNotification = buildString {
                if (isNewFile) {
                    appendLine("📄 New file: ${params.file_path}")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    // Show first few lines of new file
                    val previewLines = params.content.lines().take(10)
                    previewLines.forEach { line ->
                        appendLine("+ $line")
                    }
                    if (params.content.lines().size > 10) {
                        appendLine("... (${params.content.lines().size - 10} more lines)")
                    }
                } else {
                    appendLine("📝 Modified: ${params.file_path}")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    // Show patch diff with + and - lines
                    if (patchDiff.isNotEmpty()) {
                        patchDiff.lines().forEach { line ->
                            when {
                                line.startsWith("+") && !line.startsWith("+++") -> appendLine("+ ${line.substring(1).trimStart()}")
                                line.startsWith("-") && !line.startsWith("---") -> appendLine("- ${line.substring(1).trimStart()}")
                                line.startsWith("@@") -> appendLine("  ${line}")
                                else -> if (line.isNotBlank()) appendLine("  ${line}")
                            }
                        }
                    } else {
                        appendLine("  (No changes detected)")
                    }
                }
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            }
            
            // Build message with code metadata for chat history
            val baseMessage = "File written successfully: ${params.file_path}"
            val codeSummary = codeMetadata?.let { 
                "\n\n" + CodeDependencyAnalyzer.getCodeSummaryForHistory(it)
            } ?: ""
            val relativenessSummary = codeMetadata?.let {
                val rel = CodeDependencyAnalyzer.getRelativenessSummary(params.file_path, workspaceRoot)
                if (rel.isNotEmpty()) "\n\n$rel" else ""
            } ?: ""
            
            // Include patch diff in message if available
            val patchSection = if (patchDiff.isNotEmpty()) {
                "\n\n--- Patch Diff ---\n" + com.qali.aterm.agent.utils.PatchDiffUtils.formatPatchForDisplay(patchDiff)
            } else {
                ""
            }
            
            val messageWithErrors = if (errorTasks.isNotEmpty()) {
                baseMessage + AutoErrorDetection.formatErrorDetectionMessage(errorTasks) + codeSummary + relativenessSummary + patchSection
            } else {
                baseMessage + codeSummary + relativenessSummary + patchSection
            }
            
            ToolResult(
                llmContent = messageWithErrors,
                returnDisplay = fileChangeNotification
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error writing file: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
}

class WriteFileTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<WriteFileToolParams, ToolResult>() {
    
    override val name = "write_file"
    override val displayName = "WriteFile"
    override val description = "Writes content to a file. Creates the file if it doesn't exist, and overwrites it if it does."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "file_path" to PropertySchema(
                type = "string",
                description = "The path to the file to write to."
            ),
            "content" to PropertySchema(
                type = "string",
                description = "The content to write to the file."
            )
        ),
        required = listOf("file_path", "content")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: WriteFileToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<WriteFileToolParams, ToolResult> {
        return WriteFileToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): WriteFileToolParams {
        val filePath = params["file_path"] as? String
            ?: throw IllegalArgumentException("file_path is required")
        val content = params["content"] as? String
            ?: throw IllegalArgumentException("content is required")
        
        if (filePath.trim().isEmpty()) {
            throw IllegalArgumentException("file_path must be non-empty")
        }
        
        return WriteFileToolParams(
            file_path = filePath,
            content = content,
            modified_by_user = (params["modified_by_user"] as? Boolean) ?: false,
            ai_proposed_content = params["ai_proposed_content"] as? String
        )
    }
}
