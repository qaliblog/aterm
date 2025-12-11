package com.qali.aterm.agent.client

import org.junit.Test
import org.junit.Assert.*

class AgentClientTest {

    @Test
    fun testJsonOnlyResponse() {
        val testMessage = "What is the intent of this user request?"
        val apiClient = AgentClient(toolRegistry = com.qali.aterm.agent.tools.ToolRegistry())
        val response = apiClient.enhanceUserIntent(testMessage)

        // Verify response is valid JSON
        assertTrue(isValidJson(response))

        // Verify it contains expected fields
        val json = org.json.JSONObject(response)
        assertTrue(json.has("user_message"))

        // Verify no markdown or explanations
        assertFalse(response.contains("```"))
        assertFalse(response.contains("explanation"))
    }

    private fun isValidJson(text: String): Boolean {
        return try {
            org.json.JSONObject(text)
            true
        } catch (e: Exception) {
            try {
                org.json.JSONArray(text)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
