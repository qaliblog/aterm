package com.qali.aterm.agent.client

import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.tools.ToolResult

/**
 * Sealed class representing different types of events from the agent
 */
sealed class AgentEvent {
    data class Chunk(val text: String) : AgentEvent()
    data class ToolCall(val functionCall: FunctionCall) : AgentEvent()
    data class ToolResult(val toolName: String, val result: ToolResult) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class KeysExhausted(val message: String) : AgentEvent()
    object Done : AgentEvent()
}
