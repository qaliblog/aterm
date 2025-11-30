package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema

data class WriteTodosToolParams(
    val todos: List<Todo>
)

class WriteTodosToolInvocation(
    toolParams: WriteTodosToolParams
) : ToolInvocation<WriteTodosToolParams, ToolResult> {
    
    override val params: WriteTodosToolParams = toolParams
    
    override fun getDescription(): String {
        val count = params.todos.size
        return if (count == 0) {
            "Cleared todo list"
        } else {
            "Set $count todo(s)"
        }
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Todo update cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val todos = params.todos
        val todoListString = todos.mapIndexed { index, todo ->
            "${index + 1}. [${todo.status}] ${todo.description}"
        }.joinToString("\n")
        
        val llmContent = if (todos.isNotEmpty()) {
            "Successfully updated the todo list. The current list is now:\n$todoListString"
        } else {
            "Successfully cleared the todo list."
        }
        
        updateOutput?.invoke("Updated ${todos.size} todo(s)")
        
        return ToolResult(
            llmContent = llmContent,
            returnDisplay = "Updated ${todos.size} todo(s)"
        )
    }
}

class WriteTodosTool : DeclarativeTool<WriteTodosToolParams, ToolResult>() {
    
    override val name = "write_todos"
    override val displayName = "WriteTodos"
    override val description = """
        This tool can help you list out the current subtasks that are required to be completed for a given user request. The list of subtasks helps you keep track of the current task, organize complex queries and help ensure that you don't miss any steps. With this list, the user can also see the current progress you are making in executing a given task.
        
        Use this tool for complex queries that require multiple steps. DO NOT use this tool for simple tasks that can be completed in less than 2 steps.
        
        Task state definitions:
        - pending: Work has not begun on a given subtask.
        - in_progress: Marked just prior to beginning work on a given subtask. You should only have one subtask as in_progress at a time.
        - completed: Subtask was successfully completed with no errors or issues.
        - cancelled: Some tasks are not required anymore due to the dynamic nature of the task. In this case, mark the subtasks as cancelled.
    """.trimIndent()
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "todos" to PropertySchema(
                type = "array",
                description = "The full list of todos. This will overwrite any existing list. Each todo has 'description' (string) and 'status' (string: 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED').",
                items = PropertySchema(
                    type = "object",
                    description = "A todo item"
                )
            )
        ),
        required = listOf("todos")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: WriteTodosToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<WriteTodosToolParams, ToolResult> {
        return WriteTodosToolInvocation(params)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): WriteTodosToolParams {
        val todosList = (params["todos"] as? List<*>) ?: throw IllegalArgumentException("todos is required")
        
        val todos = todosList.mapNotNull { todoObj ->
            val todoMap = todoObj as? Map<*, *> ?: return@mapNotNull null
            val description = todoMap["description"] as? String ?: return@mapNotNull null
            val statusStr = (todoMap["status"] as? String)?.uppercase() ?: "PENDING"
            
            val status = when (statusStr) {
                "PENDING" -> TodoStatus.PENDING
                "IN_PROGRESS" -> TodoStatus.IN_PROGRESS
                "COMPLETED" -> TodoStatus.COMPLETED
                "CANCELLED" -> TodoStatus.CANCELLED
                else -> TodoStatus.PENDING
            }
            
            Todo(description, status)
        }
        
        return WriteTodosToolParams(todos = todos)
    }
}
