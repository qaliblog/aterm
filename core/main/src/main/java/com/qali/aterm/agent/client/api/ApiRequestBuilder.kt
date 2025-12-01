package com.qali.aterm.agent.client.api

import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.MemoryService
import com.qali.aterm.agent.SystemInfoService
import com.qali.aterm.agent.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds API requests for different providers
 */
class ApiRequestBuilder(
    private val toolRegistry: ToolRegistry
) {
    
    /**
     * Build request for API call
     */
    fun buildRequest(
        chatHistory: List<Content>,
        model: String,
        includeTools: Boolean = true // When false, tools are not added to the request
    ): JSONObject {
        val request = JSONObject()
        request.put("contents", JSONArray().apply {
            // Add chat history (which already includes the user message if it's the first turn)
            chatHistory.forEach { content ->
                val contentObj = JSONObject()
                // Map roles for Gemini API compatibility
                // Gemini only accepts "user" and "model" roles
                val role = when (content.role.lowercase()) {
                    "assistant", "model" -> "model"
                    "system" -> "user" // System messages should be in systemInstruction, but if present, map to user
                    "user" -> "user"
                    else -> {
                        // Default to user for unknown roles
                        android.util.Log.w("ApiRequestBuilder", "Unknown role '${content.role}', mapping to 'user'")
                        "user"
                    }
                }
                contentObj.put("role", role)
                contentObj.put("parts", JSONArray().apply {
                    content.parts.forEach { part ->
                        when (part) {
                            is Part.TextPart -> {
                                val partObj = JSONObject()
                                partObj.put("text", part.text)
                                put(partObj)
                            }
                            is Part.FunctionCallPart -> {
                                val partObj = JSONObject()
                                val functionCallObj = JSONObject()
                                functionCallObj.put("name", part.functionCall.name)
                                functionCallObj.put("args", JSONObject(part.functionCall.args))
                                part.functionCall.id?.let { functionCallObj.put("id", it) }
                                partObj.put("functionCall", functionCallObj)
                                put(partObj)
                            }
                            is Part.FunctionResponsePart -> {
                                val partObj = JSONObject()
                                val functionResponseObj = JSONObject()
                                functionResponseObj.put("name", part.functionResponse.name)
                                functionResponseObj.put("response", JSONObject(part.functionResponse.response))
                                part.functionResponse.id?.let { functionResponseObj.put("id", it) }
                                partObj.put("functionResponse", functionResponseObj)
                                put(partObj)
                            }
                        }
                    }
                })
                put(contentObj)
            }
        })
        
        // Add tools only if includeTools is true
        if (includeTools) {
            val tools = JSONArray()
            val toolObj = JSONObject()
            val functionDeclarations = JSONArray()
            toolRegistry.getFunctionDeclarations().forEach { decl ->
                val declObj = JSONObject()
                declObj.put("name", decl.name)
                declObj.put("description", decl.description)
                declObj.put("parameters", functionParametersToJson(decl.parameters))
                functionDeclarations.put(declObj)
            }
            toolObj.put("functionDeclarations", functionDeclarations)
            tools.put(toolObj)
            request.put("tools", tools)
        } else {
            // Explicitly set toolChoice to "none" for Gemini API to prevent tool calls
            request.put("toolChoice", JSONObject().apply {
                put("none", JSONObject())
            })
        }
        
        // Add system instruction to guide planning behavior
        // This matches the comprehensive system prompt from the original gemini-cli TypeScript implementation
        val hasWriteTodosTool = toolRegistry.getFunctionDeclarations().any { it.name == "write_todos" }
        
        val systemInstruction = buildString {
            append("You are an interactive CLI agent specializing in software engineering tasks. Your primary goal is to help users safely and efficiently, adhering strictly to the following instructions and utilizing your available tools.\n\n")
            
            // Add system information
            append(SystemInfoService.generateSystemContext())
            append("\n")
            
            // Add memory context if available
            val memoryContext = MemoryService.getSummarizedMemory()
            if (memoryContext.isNotEmpty()) {
                append(memoryContext)
                append("\n")
            }
            
            append("# Core Mandates\n\n")
            append("- **Conventions:** Rigorously adhere to existing project conventions when reading or modifying code. Analyze surrounding code, tests, and configuration first.\n")
            append("- **Libraries/Frameworks:** NEVER assume a library/framework is available or appropriate. Verify its established usage within the project before employing it.\n")
            append("- **Style & Structure:** Mimic the style, structure, framework choices, typing, and architectural patterns of existing code in the project.\n")
            append("- **Proactiveness:** Fulfill the user's request thoroughly. When adding features or fixing bugs, this includes adding tests to ensure quality.\n")
            append("- **Explaining Changes:** After completing a code modification or file operation, do not provide summaries unless asked.\n\n")
            
            append("# Primary Workflows\n\n")
            append("## Software Engineering Tasks\n")
            append("When requested to perform tasks like fixing bugs, adding features, refactoring, or explaining code, follow this sequence:\n")
            append("1. **Understand:** Think about the user's request and the relevant codebase context. Use search tools extensively to understand file structures, existing code patterns, and conventions.\n")
            append("2. **Plan:** Build a coherent and grounded plan for how you intend to resolve the user's task.")
            
            if (hasWriteTodosTool) {
                append(" For complex tasks, break them down into smaller, manageable subtasks and use the `write_todos` tool to track your progress.")
            }
            
            append(" Share an extremely concise yet clear plan with the user if it would help the user understand your thought process. As part of the plan, you should use an iterative development process that includes writing unit tests to verify your changes.\n")
            append("3. **Implement:** Use the available tools to act on the plan, strictly adhering to the project's established conventions.\n")
            append("4. **Verify (Tests):** If applicable and feasible, verify the changes using the project's testing procedures.\n")
            append("5. **Verify (Standards):** VERY IMPORTANT: After making code changes, execute the project-specific build, linting and type-checking commands that you have identified for this project.\n")
            append("6. **Finalize:** After all verification passes, consider the task complete.\n\n")
            
            if (hasWriteTodosTool) {
                append("## Planning with write_todos Tool\n\n")
                append("For complex queries that require multiple steps, planning and generally is higher complexity than a simple Q&A, use the `write_todos` tool.\n\n")
                append("DO NOT use this tool for simple tasks that can be completed in less than 2 steps. If the user query is simple and straightforward, do not use the tool.\n\n")
                append("**IMPORTANT - Documentation Search Planning:**\n")
                append("If the task involves unfamiliar libraries, frameworks, APIs, or requires up-to-date documentation/examples, you MUST include a todo item for web search/documentation lookup in your todo list. Examples:\n")
                append("- \"Search for [library/framework] documentation and best practices\"\n")
                append("- \"Find examples and tutorials for [technology]\"\n")
                append("- \"Look up current API documentation for [service/API]\"\n")
                append("This ensures you have the latest information before implementing.\n\n")
                append("When using `write_todos`:\n")
                append("1. Use this todo list as soon as you receive a user request based on the complexity of the task.\n")
                append("2. **If task needs documentation search, add it as the FIRST or early todo item** before implementation.\n")
                append("3. Keep track of every subtask that you update the list with.\n")
                append("4. Mark a subtask as in_progress before you begin working on it. You should only have one subtask as in_progress at a time.\n")
                append("5. Update the subtask list as you proceed in executing the task. The subtask list is not static and should reflect your progress and current plans.\n")
                append("6. Mark a subtask as completed when you have completed it.\n")
                append("7. Mark a subtask as cancelled if the subtask is no longer needed.\n")
                append("8. **CRITICAL:** After creating a todo list, you MUST continue working on the todos. Creating the plan is NOT completing the task. You must execute each todo item until all are completed or cancelled. Do NOT stop after creating the todo list - continue implementing the tasks.\n\n")
            }
            
            append("# Operational Guidelines\n\n")
            append("## Tone and Style (CLI Interaction)\n")
            append("- **Concise & Direct:** Adopt a professional, direct, and concise tone suitable for a CLI environment.\n")
            append("- **Minimal Output:** Aim for fewer than 3 lines of text output (excluding tool use/code generation) per response whenever practical.\n")
            append("- **No Chitchat:** Avoid conversational filler, preambles, or postambles. Get straight to the action or answer.\n\n")
            
            append("## Tool Usage\n")
            append("- **Parallelism:** Execute multiple independent tool calls in parallel when feasible.\n")
            append("- **Command Execution:** Use shell tools for running shell commands.\n")
            append("- **Web Search:** ALWAYS use web search tools (google_web_search or custom_web_search) when:\n")
            append("  - The user asks about current information, recent events, or real-world data\n")
            append("  - You need to find documentation, tutorials, or examples for libraries/frameworks\n")
            append("  - The user asks questions that require up-to-date information from the internet\n")
            append("  - You need to verify facts, find solutions to problems, or gather information\n")
            append("  - The task involves external APIs, services, or online resources\n")
            append("  **IMPORTANT:** If you're unsure whether information is current or need to verify something, use web search. Don't rely on potentially outdated training data.\n\n")
            
            append("# Final Reminder\n")
            append("Your core function is efficient and safe assistance. Balance extreme conciseness with the crucial need for clarity. Always prioritize user control and project conventions. Never make assumptions about the contents of files; instead use read tools to ensure you aren't making broad assumptions.\n\n")
            append("**CRITICAL: Task Completion Rules**\n")
            append("- You are an agent - you MUST keep going until the user's query is completely resolved.\n")
            append("- Creating a todo list with `write_todos` is PLANNING, not completion. You MUST continue executing the todos.\n")
            append("- Do NOT return STOP after creating todos. You must continue working until all todos are completed.\n")
            append("- Only return STOP when ALL tasks are actually finished and the user's request is fully implemented.\n")
            append("- If you create a todo list, immediately start working on the first todo item. Do not stop after planning.")
        }
        
        request.put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", systemInstruction)
                })
            })
        })
        
        return request
    }
    
    /**
     * Convert FunctionParameters to JSON
     */
    fun functionParametersToJson(params: FunctionParameters): JSONObject {
        val json = JSONObject()
        json.put("type", params.type)
        json.put("properties", JSONObject().apply {
            params.properties.forEach { (key, schema) ->
                put(key, propertySchemaToJson(schema))
            }
        })
        json.put("required", JSONArray(params.required))
        return json
    }
    
    /**
     * Convert PropertySchema to JSON
     */
    private fun propertySchemaToJson(schema: PropertySchema): JSONObject {
        val json = JSONObject()
        json.put("type", schema.type)
        json.put("description", schema.description)
        schema.enum?.let { json.put("enum", JSONArray(it)) }
        schema.items?.let { json.put("items", propertySchemaToJson(it)) }
        return json
    }
}
