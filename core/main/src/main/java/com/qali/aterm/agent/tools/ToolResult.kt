package com.qali.aterm.agent.tools

/**
 * Result of a tool execution
 */
data class ToolResult(
    val llmContent: String,
    val returnDisplay: String = llmContent,
    val error: ToolError? = null
)

data class ToolError(
    val message: String,
    val type: ToolErrorType
)

enum class ToolErrorType {
    FILE_NOT_FOUND,
    PERMISSION_DENIED,
    INVALID_PARAMETERS,
    EXECUTION_ERROR,
    DISCOVERED_TOOL_EXECUTION_ERROR,
    ATTEMPT_TO_CREATE_EXISTING_FILE,
    EDIT_NO_OCCURRENCE_FOUND,
    EDIT_EXPECTED_OCCURRENCE_MISMATCH,
    EDIT_NO_CHANGE,
    READ_CONTENT_FAILURE,
    EDIT_PREPARATION_FAILURE,
    FILE_WRITE_FAILURE,
    WEB_SEARCH_FAILED
}

/**
 * Diff statistics for file operations
 */
data class DiffStat(
    val model_added_lines: Int = 0,
    val model_removed_lines: Int = 0,
    val model_added_chars: Int = 0,
    val model_removed_chars: Int = 0,
    val user_added_lines: Int = 0,
    val user_removed_lines: Int = 0,
    val user_added_chars: Int = 0,
    val user_removed_chars: Int = 0
)

/**
 * Tool location for tracking file operations
 */
data class ToolLocation(
    val path: String,
    val line: Int? = null
)

/**
 * Todo item for task tracking
 */
data class Todo(
    val description: String,
    val status: TodoStatus
)

enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

/**
 * Base interface for tool invocations
 */
interface ToolInvocation<TParams, TResult : ToolResult> {
    val params: TParams
    fun getDescription(): String
    fun toolLocations(): List<ToolLocation>
    suspend fun execute(
        signal: CancellationSignal? = null,
        updateOutput: ((String) -> Unit)? = null
    ): TResult
}

/**
 * Cancellation signal (Android equivalent of AbortSignal)
 */
class CancellationSignal {
    private var aborted = false
    
    fun abort() {
        aborted = true
    }
    
    fun isAborted(): Boolean = aborted
}
