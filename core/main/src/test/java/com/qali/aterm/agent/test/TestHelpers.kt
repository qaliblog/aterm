package com.qali.aterm.agent.test

import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.ppe.models.*
import java.io.File

/**
 * Test helper utilities for agent tests
 */
object TestHelpers {
    
    /**
     * Create a mock PpeScript for testing
     */
    fun createMockScript(
        userMessage: String = "Test message",
        hasAiPlaceholder: Boolean = false
    ): PpeScript {
        return PpeScript(
            parameters = mapOf("userMessage" to userMessage),
            turns = listOf(
                PpeTurn(
                    messages = listOf(
                        PpeMessage(
                            role = "user",
                            content = "{{userMessage}}",
                            hasAiPlaceholder = hasAiPlaceholder
                        )
                    ),
                    instructions = emptyList()
                )
            ),
            sourcePath = null
        )
    }
    
    /**
     * Create a mock Content message
     */
    fun createMockContent(
        role: String = "user",
        text: String = "Test message"
    ): Content {
        return Content(
            role = role,
            parts = listOf(Part.TextPart(text = text))
        )
    }
    
    /**
     * Create a list of mock messages
     */
    fun createMockMessages(count: Int): List<Content> {
        return (1..count).map { index ->
            createMockContent(
                role = if (index % 2 == 0) "user" else "model",
                text = "Message $index"
            )
        }
    }
    
    /**
     * Create a temporary test directory
     */
    fun createTempTestDir(prefix: String = "aterm-test"): File {
        val tempDir = File.createTempFile(prefix, null)
        tempDir.delete()
        tempDir.mkdirs()
        return tempDir
    }
    
    /**
     * Clean up temporary directory
     */
    fun cleanupTempDir(dir: File) {
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
    
    /**
     * Create a mock API response
     */
    fun createMockApiResponse(
        text: String = "Test response",
        finishReason: String = "STOP",
        functionCalls: List<FunctionCall> = emptyList()
    ): com.qali.aterm.agent.ppe.PpeApiResponse {
        return com.qali.aterm.agent.ppe.PpeApiResponse(
            text = text,
            finishReason = finishReason,
            functionCalls = functionCalls
        )
    }
}
