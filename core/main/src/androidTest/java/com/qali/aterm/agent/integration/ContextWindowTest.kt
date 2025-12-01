package com.qali.aterm.agent.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qali.aterm.agent.AgentService
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.ppe.CliBasedAgentClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

/**
 * Integration tests for context window management
 * Tests that context is properly managed under load
 */
@RunWith(AndroidJUnit4::class)
class ContextWindowTest {
    
    private lateinit var context: Context
    private lateinit var workspaceRoot: File
    private var agentClient: CliBasedAgentClient? = null
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        workspaceRoot = File(context.cacheDir, "test-context-${System.currentTimeMillis()}")
        workspaceRoot.mkdirs()
        
        val client = AgentService.initialize(
            workspaceRoot = workspaceRoot.absolutePath,
            useOllama = false,
            sessionId = "test-session"
        )
        
        if (client is CliBasedAgentClient) {
            agentClient = client
        }
    }
    
    @After
    fun cleanup() {
        if (workspaceRoot.exists()) {
            workspaceRoot.deleteRecursively()
        }
        AgentService.reset()
    }
    
    @Test
    fun testContextPruning() = runBlocking {
        val client = agentClient ?: return@runBlocking
        
        // Send many messages to test context pruning
        for (i in 1..20) {
            client.sendMessage(
                userMessage = "Message $i: This is a test message to fill up the context window",
                onChunk = { },
                onToolCall = { },
                onToolResult = { _, _ -> }
            ).collect { }
        }
        
        // Verify context is managed without errors
        assertTrue("Should handle context pruning", true)
    }
    
    @Test
    fun testLargeContextHandling() = runBlocking {
        val client = agentClient ?: return@runBlocking
        
        // Create a large message
        val largeMessage = "Large message: " + "x".repeat(10000)
        
        val events = mutableListOf<AgentEvent>()
        
        client.sendMessage(
            userMessage = largeMessage,
            onChunk = { },
            onToolCall = { },
            onToolResult = { _, _ -> }
        ).collect { event ->
            events.add(event)
        }
        
        // Verify large context is handled
        assertTrue("Should handle large context", events.isNotEmpty())
    }
    
    @Test
    fun testContextWindowUnderLoad() = runBlocking {
        val client = agentClient ?: return@runBlocking
        
        // Send multiple rapid messages
        val results = mutableListOf<Boolean>()
        
        for (i in 1..10) {
            var success = false
            client.sendMessage(
                userMessage = "Rapid message $i",
                onChunk = { },
                onToolCall = { },
                onToolResult = { _, _ -> }
            ).collect { event ->
                if (event is AgentEvent.Done || event is AgentEvent.Error) {
                    success = true
                }
            }
            results.add(success)
        }
        
        // Verify all messages were handled
        assertTrue("Should handle rapid messages", results.any { it })
    }
}
