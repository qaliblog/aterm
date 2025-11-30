package com.qali.aterm.agent.core

import com.qali.aterm.agent.tools.ToolResult

/**
 * Represents a single turn in the conversation
 */
data class Turn(
    val chatHistory: List<Content>,
    val promptId: String,
    val finishReason: String? = null,
    val usageMetadata: UsageMetadata? = null,
    val pendingToolCalls: List<FunctionCall> = emptyList()
)

/**
 * Turn execution result
 */
data class TurnResult(
    val turn: Turn,
    val events: List<TurnEvent>
)

sealed class TurnEvent {
    data class Content(val text: String) : TurnEvent()
    data class ToolCallRequest(val functionCall: FunctionCall) : TurnEvent()
    data class ToolCallResponse(val toolName: String, val result: ToolResult) : TurnEvent()
    data class Error(val message: String) : TurnEvent()
    data class Finished(val reason: String?) : TurnEvent()
    object Retry : TurnEvent()
}
