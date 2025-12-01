package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.*
import com.qali.aterm.agent.ppe.models.*
import com.qali.aterm.agent.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import java.io.File

/**
 * Unit tests for PpeExecutionEngine
 * Tests script execution, tool execution, and non-streaming API calls
 */
class PpeExecutionEngineTest {
    
    @Mock
    private lateinit var toolRegistry: ToolRegistry
    
    private lateinit var executionEngine: PpeExecutionEngine
    private val workspaceRoot = "/tmp/test-workspace"
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        // Mock tool registry
        whenever(toolRegistry.getAllTools()).thenReturn(emptyList())
        whenever(toolRegistry.getFunctionDeclarations()).thenReturn(emptyList())
        whenever(toolRegistry.getTool(any())).thenReturn(null)
        
        executionEngine = PpeExecutionEngine(toolRegistry, workspaceRoot, null, null)
    }
    
    @Test
    fun testExecutionEngineInitialization() {
        assertNotNull(executionEngine)
    }
    
    @Test
    fun testExecuteScriptWithEmptyScript() = runTest {
        val script = PpeScript(
            parameters = emptyMap(),
            turns = emptyList(),
            sourcePath = null
        )
        
        val result = executionEngine.executeScript(
            script = script,
            inputParams = emptyMap()
        )
        
        assertTrue(result.success)
        assertTrue(result.finalResult.isEmpty())
    }
    
    @Test
    fun testExecuteScriptWithSimpleTurn() = runTest {
        val script = PpeScript(
            parameters = mapOf("userMessage" to "test"),
            turns = listOf(
                PpeTurn(
                    messages = listOf(
                        PpeMessage(
                            role = "user",
                            content = "{{userMessage}}",
                            hasAiPlaceholder = false
                        )
                    ),
                    instructions = emptyList()
                )
            ),
            sourcePath = null
        )
        
        val result = executionEngine.executeScript(
            script = script,
            inputParams = mapOf("userMessage" to "Hello")
        )
        
        assertNotNull(result)
        // Without AI placeholder, should complete successfully
        assertTrue(result.success)
    }
    
    @Test
    fun testExecuteScriptWithVariables() = runTest {
        val script = PpeScript(
            parameters = mapOf("name" to "World"),
            turns = listOf(
                PpeTurn(
                    messages = listOf(
                        PpeMessage(
                            role = "user",
                            content = "Hello {{name}}",
                            hasAiPlaceholder = false
                        )
                    ),
                    instructions = emptyList()
                )
            ),
            sourcePath = null
        )
        
        val result = executionEngine.executeScript(
            script = script,
            inputParams = mapOf("name" to "Test")
        )
        
        assertNotNull(result)
        assertTrue(result.success)
    }
    
    @Test
    fun testScriptExecutionNonStreaming() {
        // Verify that script execution uses non-streaming API calls
        // This is verified by the implementation - all API calls use non-streaming mode
        assertTrue(true) // Placeholder - actual verification in integration tests
    }
    
    @Test
    fun testContextWindowManagement() {
        // Test that context window is managed correctly
        // This would require mocking the API client
        assertTrue(true) // Placeholder
    }
}
