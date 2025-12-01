package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Unit tests for ToolRegistry
 * Tests tool registration, retrieval, and function declaration generation
 */
class ToolRegistryTest {
    
    private lateinit var toolRegistry: ToolRegistry
    
    @Mock
    private lateinit var mockTool1: DeclarativeTool<*, *>
    
    @Mock
    private lateinit var mockTool2: DeclarativeTool<*, *>
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        toolRegistry = ToolRegistry()
        
        // Setup mock tools
        whenever(mockTool1.name).thenReturn("test_tool_1")
        whenever(mockTool1.displayName).thenReturn("Test Tool 1")
        whenever(mockTool1.description).thenReturn("Test tool 1 description")
        whenever(mockTool1.getFunctionDeclaration()).thenReturn(
            FunctionDeclaration(
                name = "test_tool_1",
                description = "Test tool 1 description",
                parameters = com.qali.aterm.agent.core.FunctionParameters(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
        
        whenever(mockTool2.name).thenReturn("test_tool_2")
        whenever(mockTool2.displayName).thenReturn("Test Tool 2")
        whenever(mockTool2.description).thenReturn("Test tool 2 description")
        whenever(mockTool2.getFunctionDeclaration()).thenReturn(
            FunctionDeclaration(
                name = "test_tool_2",
                description = "Test tool 2 description",
                parameters = com.qali.aterm.agent.core.FunctionParameters(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }
    
    @Test
    fun testToolRegistryInitialization() {
        assertNotNull(toolRegistry)
        assertEquals(0, toolRegistry.getAllTools().size)
    }
    
    @Test
    fun testRegisterTool() {
        toolRegistry.registerTool(mockTool1)
        
        assertEquals(1, toolRegistry.getAllTools().size)
        assertEquals(mockTool1, toolRegistry.getTool("test_tool_1"))
    }
    
    @Test
    fun testRegisterMultipleTools() {
        toolRegistry.registerTool(mockTool1)
        toolRegistry.registerTool(mockTool2)
        
        assertEquals(2, toolRegistry.getAllTools().size)
        assertEquals(mockTool1, toolRegistry.getTool("test_tool_1"))
        assertEquals(mockTool2, toolRegistry.getTool("test_tool_2"))
    }
    
    @Test
    fun testGetToolWithNonExistentName() {
        assertNull(toolRegistry.getTool("non_existent_tool"))
    }
    
    @Test
    fun testGetAllTools() {
        toolRegistry.registerTool(mockTool1)
        toolRegistry.registerTool(mockTool2)
        
        val allTools = toolRegistry.getAllTools()
        assertEquals(2, allTools.size)
        assertTrue(allTools.contains(mockTool1))
        assertTrue(allTools.contains(mockTool2))
    }
    
    @Test
    fun testGetFunctionDeclarations() {
        toolRegistry.registerTool(mockTool1)
        toolRegistry.registerTool(mockTool2)
        
        val declarations = toolRegistry.getFunctionDeclarations()
        assertEquals(2, declarations.size)
        assertEquals("test_tool_1", declarations[0].name)
        assertEquals("test_tool_2", declarations[1].name)
    }
    
    @Test
    fun testRegisterToolOverwritesExisting() {
        toolRegistry.registerTool(mockTool1)
        
        val newMockTool = mock<DeclarativeTool<*, *>>()
        whenever(newMockTool.name).thenReturn("test_tool_1")
        whenever(newMockTool.displayName).thenReturn("New Test Tool 1")
        
        toolRegistry.registerTool(newMockTool)
        
        assertEquals(1, toolRegistry.getAllTools().size)
        assertEquals(newMockTool, toolRegistry.getTool("test_tool_1"))
    }
    
    @Test
    fun testEmptyToolRegistry() {
        assertEquals(0, toolRegistry.getAllTools().size)
        assertEquals(0, toolRegistry.getFunctionDeclarations().size)
    }
}
