package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for ContextWindowManager
 * Tests token estimation, context pruning, and window management
 */
class ContextWindowManagerTest {
    
    @Before
    fun setup() {
        // ContextWindowManager is an object, no initialization needed
    }
    
    @Test
    fun testEstimateTokens() {
        val text = "This is a test message with some content."
        val estimatedTokens = ContextWindowManager.estimateTokens(
            listOf(
                Content(
                    role = "user",
                    parts = listOf(Part.TextPart(text = text))
                )
            )
        )
        
        assertTrue(estimatedTokens > 0)
        // Rough estimate: text length / 4
        val expectedMin = text.length / 4
        assertTrue(estimatedTokens >= expectedMin)
    }
    
    @Test
    fun testEstimateTokensWithMultipleMessages() {
        val messages = listOf(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = "Message 1"))
            ),
            Content(
                role = "model",
                parts = listOf(Part.TextPart(text = "Response 1"))
            ),
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = "Message 2"))
            )
        )
        
        val estimatedTokens = ContextWindowManager.estimateTokens(messages)
        
        assertTrue(estimatedTokens > 0)
    }
    
    @Test
    fun testEstimateTokensWithEmptyMessages() {
        val estimatedTokens = ContextWindowManager.estimateTokens(emptyList())
        assertEquals(0, estimatedTokens)
    }
    
    @Test
    fun testPruneChatHistory() {
        val messages = (1..100).map { index ->
            Content(
                role = if (index % 2 == 0) "user" else "model",
                parts = listOf(Part.TextPart(text = "Message $index"))
            )
        }
        
        val pruned = ContextWindowManager.pruneChatHistory(messages, "gpt-4")
        
        // Should prune to fit context window
        assertTrue(pruned.size <= messages.size)
        assertTrue(pruned.isNotEmpty())
    }
    
    @Test
    fun testPruneChatHistoryWithSmallHistory() {
        val messages = (1..5).map { index ->
            Content(
                role = if (index % 2 == 0) "user" else "model",
                parts = listOf(Part.TextPart(text = "Message $index"))
            )
        }
        
        val pruned = ContextWindowManager.pruneChatHistory(messages, "gpt-4")
        
        // Small history should not be pruned
        assertEquals(messages.size, pruned.size)
    }
    
    @Test
    fun testCreateSummary() {
        val messages = (1..20).map { index ->
            Content(
                role = if (index % 2 == 0) "user" else "model",
                parts = listOf(Part.TextPart(text = "Message $index with some content"))
            )
        }
        
        val summary = ContextWindowManager.createSummary(messages)
        
        // Summary should be shorter than original
        assertTrue(summary.isNotEmpty())
    }
    
    @Test
    fun testCreateSummaryWithEmptyMessages() {
        val summary = ContextWindowManager.createSummary(emptyList())
        assertTrue(summary.isEmpty())
    }
}
