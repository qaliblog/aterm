package com.qali.aterm.agent.ppe

import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.tools.ToolResult
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Executes tools in parallel when possible
 * Better than Cursor AI: Dependency-aware parallelization
 */
object ParallelToolExecutor {
    
    /**
     * Check if two tool calls are independent (can run in parallel)
     */
    fun areIndependent(call1: FunctionCall, call2: FunctionCall): Boolean {
        // Tools that modify files are not independent if they touch the same file
        if (call1.name == "write_file" && call2.name == "write_file") {
            val file1 = call1.args["file_path"] as? String
            val file2 = call2.args["file_path"] as? String
            return file1 != file2
        }
        
        if (call1.name == "edit" && call2.name == "edit") {
            val file1 = call1.args["file_path"] as? String
            val file2 = call2.args["file_path"] as? String
            return file1 != file2
        }
        
        // Read operations are always independent
        if (call1.name == "read_file" || call2.name == "read_file") {
            return true
        }
        
        // Shell commands are independent if they don't modify the same files
        if (call1.name == "shell" && call2.name == "shell") {
            return true // Assume independent (could be improved)
        }
        
        // Different tool types are generally independent
        if (call1.name != call2.name) {
            return true
        }
        
        // Same tool, same file = not independent
        return false
    }
    
    /**
     * Group tool calls into batches that can run in parallel
     */
    fun groupForParallelExecution(calls: List<FunctionCall>): List<List<FunctionCall>> {
        val groups = mutableListOf<MutableList<FunctionCall>>()
        val processed = mutableSetOf<Int>()
        
        for (i in calls.indices) {
            if (i in processed) continue
            
            val group = mutableListOf<FunctionCall>()
            group.add(calls[i])
            processed.add(i)
            
            // Find other calls that can run in parallel with this one
            for (j in (i + 1) until calls.size) {
                if (j in processed) continue
                
                // Check if this call is independent of all calls in current group
                val canAdd = group.all { areIndependent(it, calls[j]) }
                if (canAdd) {
                    group.add(calls[j])
                    processed.add(j)
                }
            }
            
            groups.add(group)
        }
        
        return groups
    }
    
    /**
     * Execute tool calls in parallel when possible
     */
    suspend fun executeInParallel(
        calls: List<FunctionCall>,
        executeTool: suspend (FunctionCall) -> ToolResult
    ): List<Pair<FunctionCall, ToolResult>> {
        if (calls.isEmpty()) return emptyList()
        if (calls.size == 1) {
            return listOf(calls[0] to executeTool(calls[0]))
        }
        
        // Group calls for parallel execution
        val groups = groupForParallelExecution(calls)
        
        Log.d("ParallelToolExecutor", "Grouped ${calls.size} calls into ${groups.size} parallel batches")
        
        val results = mutableListOf<Pair<FunctionCall, ToolResult>>()
        
        // Execute groups sequentially, but calls within group in parallel
        for (group in groups) {
            if (group.size == 1) {
                // Single call, execute directly
                results.add(group[0] to executeTool(group[0]))
            } else {
                // Multiple calls, execute in parallel
                coroutineScope {
                    val deferredResults = group.map { call ->
                        async {
                            call to executeTool(call)
                        }
                    }
                    results.addAll(deferredResults.awaitAll())
                }
            }
        }
        
        // Return results in original order
        return calls.map { call ->
            results.find { it.first == call } ?: (call to ToolResult(
                llmContent = "Execution failed",
                error = com.qali.aterm.agent.tools.ToolError(
                    message = "Tool execution failed",
                    type = com.qali.aterm.agent.tools.ToolErrorType.EXECUTION_ERROR
                )
            ))
        }
    }
}
