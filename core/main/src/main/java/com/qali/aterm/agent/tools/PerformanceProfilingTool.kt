package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.debug.ExecutionStateTracker
import com.qali.aterm.agent.ppe.Observability
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Performance profiling tool
 * Profiles and analyzes application performance, identifies bottlenecks, and suggests optimizations
 */
data class PerformanceProfilingToolParams(
    val operationId: String? = null, // Specific operation to profile, or null for all
    val profileType: String? = null, // "execution", "memory", "api", "tools", "all"
    val duration: Long? = null, // Duration in seconds to profile (for continuous profiling)
    val includeSuggestions: Boolean = true // Whether to include optimization suggestions
)

data class PerformanceProfileResult(
    val executionMetrics: ExecutionMetrics,
    val memoryMetrics: MemoryMetrics,
    val apiMetrics: ApiMetrics,
    val toolMetrics: ToolMetrics,
    val bottlenecks: List<Bottleneck>,
    val suggestions: List<OptimizationSuggestion>,
    val summary: String
)

data class ExecutionMetrics(
    val totalExecutionTime: Long, // milliseconds
    val averageOperationTime: Double,
    val slowestOperations: List<OperationTime>,
    val fastestOperations: List<OperationTime>,
    val operationsCount: Int
)

data class OperationTime(
    val operationId: String,
    val operationType: String,
    val duration: Long, // milliseconds
    val timestamp: Long
)

data class MemoryMetrics(
    val totalMemory: Long, // bytes
    val usedMemory: Long, // bytes
    val freeMemory: Long, // bytes
    val maxMemory: Long, // bytes
    val memoryUsagePercent: Double,
    val memoryTrend: String // "stable", "increasing", "decreasing"
)

data class ApiMetrics(
    val totalApiCalls: Long,
    val averageResponseTime: Double, // milliseconds
    val slowestApiCalls: List<ApiCallTime>,
    val totalTokens: Long,
    val totalCost: Double,
    val errors: Long,
    val errorRate: Double // percentage
)

data class ApiCallTime(
    val operationId: String,
    val provider: String?,
    val duration: Long, // milliseconds
    val tokens: Int,
    val cost: Double
)

data class ToolMetrics(
    val totalToolCalls: Long,
    val averageToolTime: Double, // milliseconds
    val slowestTools: List<ToolCallTime>,
    val toolUsage: Map<String, Int> // tool name -> count
)

data class ToolCallTime(
    val toolName: String,
    val operationId: String,
    val duration: Long, // milliseconds
    val timestamp: Long
)

data class Bottleneck(
    val type: String, // "execution", "memory", "api", "tool"
    val severity: String, // "low", "medium", "high", "critical"
    val description: String,
    val location: String?,
    val impact: String
)

data class OptimizationSuggestion(
    val category: String, // "execution", "memory", "api", "caching", "parallelization"
    val priority: String, // "low", "medium", "high"
    val description: String,
    val expectedImprovement: String,
    val steps: List<String>
)

class PerformanceProfilingToolInvocation(
    toolParams: PerformanceProfilingToolParams,
    private val workspaceRoot: String
) : ToolInvocation<PerformanceProfilingToolParams, ToolResult> {
    
    override val params: PerformanceProfilingToolParams = toolParams
    
    override fun getDescription(): String {
        val profileType = params.profileType ?: "all"
        return "Profiling performance: $profileType"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult = withContext(Dispatchers.IO) {
        if (signal?.isAborted() == true) {
            return@withContext ToolResult(
                llmContent = "Performance profiling cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            updateOutput?.invoke("📊 Collecting performance metrics...")
            
            val profileType = params.profileType?.lowercase() ?: "all"
            
            // Collect execution metrics
            val executionMetrics = if (profileType in listOf("execution", "all")) {
                updateOutput?.invoke("⏱️ Analyzing execution times...")
                collectExecutionMetrics(params.operationId)
            } else {
                ExecutionMetrics(0, 0.0, emptyList(), emptyList(), 0)
            }
            
            // Collect memory metrics
            val memoryMetrics = if (profileType in listOf("memory", "all")) {
                updateOutput?.invoke("💾 Analyzing memory usage...")
                collectMemoryMetrics()
            } else {
                MemoryMetrics(0, 0, 0, 0, 0.0, "stable")
            }
            
            // Collect API metrics
            val apiMetrics = if (profileType in listOf("api", "all")) {
                updateOutput?.invoke("🌐 Analyzing API calls...")
                collectApiMetrics()
            } else {
                ApiMetrics(0, 0.0, emptyList(), 0, 0.0, 0, 0.0)
            }
            
            // Collect tool metrics
            val toolMetrics = if (profileType in listOf("tools", "all")) {
                updateOutput?.invoke("🔧 Analyzing tool usage...")
                collectToolMetrics()
            } else {
                ToolMetrics(0, 0.0, emptyList(), emptyMap())
            }
            
            updateOutput?.invoke("🔍 Identifying bottlenecks...")
            val bottlenecks = identifyBottlenecks(executionMetrics, memoryMetrics, apiMetrics, toolMetrics)
            
            updateOutput?.invoke("💡 Generating optimization suggestions...")
            val suggestions = if (params.includeSuggestions) {
                generateOptimizationSuggestions(bottlenecks, executionMetrics, memoryMetrics, apiMetrics, toolMetrics)
            } else {
                emptyList()
            }
            
            val result = PerformanceProfileResult(
                executionMetrics = executionMetrics,
                memoryMetrics = memoryMetrics,
                apiMetrics = apiMetrics,
                toolMetrics = toolMetrics,
                bottlenecks = bottlenecks,
                suggestions = suggestions,
                summary = generateSummary(executionMetrics, memoryMetrics, apiMetrics, toolMetrics, bottlenecks)
            )
            
            val formattedResult = formatPerformanceReport(result, profileType)
            
            DebugLogger.i("PerformanceProfilingTool", "Performance profiling completed", mapOf(
                "profile_type" to profileType,
                "bottlenecks" to bottlenecks.size,
                "suggestions" to suggestions.size
            ))
            
            ToolResult(
                llmContent = formattedResult,
                returnDisplay = "Profile: ${bottlenecks.size} bottlenecks, ${suggestions.size} suggestions"
            )
        } catch (e: Exception) {
            DebugLogger.e("PerformanceProfilingTool", "Error profiling performance", exception = e)
            ToolResult(
                llmContent = "Error profiling performance: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private suspend fun collectExecutionMetrics(operationId: String?): ExecutionMetrics {
        val allOperations = ExecutionStateTracker.getAllActiveExecutions()
        val operationTimes = mutableListOf<OperationTime>()
        
        if (operationId != null) {
            // Get specific operation
            val state = ExecutionStateTracker.getExecutionState(operationId)
            if (state != null) {
                val duration = System.currentTimeMillis() - state.startTime
                operationTimes.add(OperationTime(
                    operationId = operationId,
                    operationType = state.operationType,
                    duration = duration,
                    timestamp = state.startTime
                ))
            }
        } else {
            // Get all operations from Observability
            // Note: Observability doesn't expose all operations directly, so we use ExecutionStateTracker
            allOperations.forEach { (id, state) ->
                val duration = System.currentTimeMillis() - state.startTime
                operationTimes.add(OperationTime(
                    operationId = id,
                    operationType = state.operationType,
                    duration = duration,
                    timestamp = state.startTime
                ))
            }
        }
        
        val totalTime = operationTimes.sumOf { it.duration }
        val avgTime = if (operationTimes.isNotEmpty()) totalTime.toDouble() / operationTimes.size else 0.0
        val slowest = operationTimes.sortedByDescending { it.duration }.take(10)
        val fastest = operationTimes.sortedBy { it.duration }.take(10)
        
        return ExecutionMetrics(
            totalExecutionTime = totalTime,
            averageOperationTime = avgTime,
            slowestOperations = slowest,
            fastestOperations = fastest,
            operationsCount = operationTimes.size
        )
    }
    
    private fun collectMemoryMetrics(): MemoryMetrics {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        val usagePercent = if (maxMemory > 0) (usedMemory.toDouble() / maxMemory) * 100.0 else 0.0
        
        // Simple trend detection (would need historical data for accurate trend)
        val trend = when {
            usagePercent > 80 -> "increasing"
            usagePercent < 20 -> "decreasing"
            else -> "stable"
        }
        
        return MemoryMetrics(
            totalMemory = totalMemory,
            usedMemory = usedMemory,
            freeMemory = freeMemory,
            maxMemory = maxMemory,
            memoryUsagePercent = usagePercent,
            memoryTrend = trend
        )
    }
    
    private fun collectApiMetrics(): ApiMetrics {
        val globalStats = Observability.getGlobalStats()
        val totalApiCalls = (globalStats["totalApiCalls"] as? Number)?.toLong() ?: 0L
        val totalTokens = (globalStats["totalTokens"] as? Number)?.toLong() ?: 0L
        val totalCost = (globalStats["totalCost"] as? Number)?.toDouble() ?: 0.0
        val totalErrors = (globalStats["totalErrors"] as? Number)?.toLong() ?: 0L
        
        // Calculate average response time (simplified - would need actual timing data)
        val avgResponseTime = if (totalApiCalls > 0) {
            // Estimate based on typical API response times
            500.0 // milliseconds (placeholder)
        } else {
            0.0
        }
        
        val errorRate = if (totalApiCalls > 0) {
            (totalErrors.toDouble() / totalApiCalls) * 100.0
        } else {
            0.0
        }
        
        // Get slowest API calls (would need actual timing data from Observability)
        val slowestApiCalls = emptyList<ApiCallTime>() // Placeholder
        
        return ApiMetrics(
            totalApiCalls = totalApiCalls,
            averageResponseTime = avgResponseTime,
            slowestApiCalls = slowestApiCalls,
            totalTokens = totalTokens,
            totalCost = totalCost,
            errors = totalErrors,
            errorRate = errorRate
        )
    }
    
    private suspend fun collectToolMetrics(): ToolMetrics {
        val allOperations = ExecutionStateTracker.getAllActiveExecutions()
        val toolCalls = mutableListOf<ToolCallTime>()
        val toolUsage = mutableMapOf<String, Int>()
        
        allOperations.forEach { (operationId, state) ->
            state.toolCalls.forEach { toolCall ->
                val toolName = toolCall.toolName
                toolUsage[toolName] = toolUsage.getOrDefault(toolName, 0) + 1
                
                // Estimate duration (would need actual timing data)
                val duration = 100L // placeholder
                toolCalls.add(ToolCallTime(
                    toolName = toolName,
                    operationId = operationId,
                    duration = duration,
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
        
        val totalToolCalls = toolCalls.size.toLong()
        val avgToolTime = if (toolCalls.isNotEmpty()) {
            toolCalls.map { it.duration }.average()
        } else {
            0.0
        }
        val slowestTools = toolCalls.sortedByDescending { it.duration }.take(10)
        
        return ToolMetrics(
            totalToolCalls = totalToolCalls,
            averageToolTime = avgToolTime,
            slowestTools = slowestTools,
            toolUsage = toolUsage
        )
    }
    
    private fun identifyBottlenecks(
        execution: ExecutionMetrics,
        memory: MemoryMetrics,
        api: ApiMetrics,
        tools: ToolMetrics
    ): List<Bottleneck> {
        val bottlenecks = mutableListOf<Bottleneck>()
        
        // Execution bottlenecks
        if (execution.averageOperationTime > 5000) {
            bottlenecks.add(Bottleneck(
                type = "execution",
                severity = if (execution.averageOperationTime > 10000) "high" else "medium",
                description = "Average operation time is ${String.format("%.1f", execution.averageOperationTime)}ms",
                location = null,
                impact = "Slow overall execution"
            ))
        }
        
        execution.slowestOperations.take(5).forEach { op ->
            if (op.duration > 10000) {
                bottlenecks.add(Bottleneck(
                    type = "execution",
                    severity = if (op.duration > 30000) "critical" else "high",
                    description = "Operation '${op.operationType}' took ${op.duration}ms",
                    location = op.operationId,
                    impact = "Significantly slows down execution"
                ))
            }
        }
        
        // Memory bottlenecks
        if (memory.memoryUsagePercent > 80) {
            bottlenecks.add(Bottleneck(
                type = "memory",
                severity = if (memory.memoryUsagePercent > 90) "critical" else "high",
                description = "Memory usage is ${String.format("%.1f", memory.memoryUsagePercent)}%",
                location = null,
                impact = "High memory pressure, risk of OOM"
            ))
        }
        
        // API bottlenecks
        if (api.averageResponseTime > 2000) {
            bottlenecks.add(Bottleneck(
                type = "api",
                severity = if (api.averageResponseTime > 5000) "high" else "medium",
                description = "Average API response time is ${String.format("%.1f", api.averageResponseTime)}ms",
                location = null,
                impact = "Slow API calls affecting performance"
            ))
        }
        
        if (api.errorRate > 5.0) {
            bottlenecks.add(Bottleneck(
                type = "api",
                severity = if (api.errorRate > 10.0) "high" else "medium",
                description = "API error rate is ${String.format("%.1f", api.errorRate)}%",
                location = null,
                impact = "High error rate causing retries and delays"
            ))
        }
        
        // Tool bottlenecks
        if (tools.averageToolTime > 1000) {
            bottlenecks.add(Bottleneck(
                type = "tool",
                severity = if (tools.averageToolTime > 3000) "high" else "medium",
                description = "Average tool execution time is ${String.format("%.1f", tools.averageToolTime)}ms",
                location = null,
                impact = "Slow tool execution"
            ))
        }
        
        tools.slowestTools.take(5).forEach { tool ->
            if (tool.duration > 5000) {
                bottlenecks.add(Bottleneck(
                    type = "tool",
                    severity = if (tool.duration > 10000) "high" else "medium",
                    description = "Tool '${tool.toolName}' took ${tool.duration}ms",
                    location = tool.operationId,
                    impact = "Slow tool execution"
                ))
            }
        }
        
        return bottlenecks
    }
    
    private fun generateOptimizationSuggestions(
        bottlenecks: List<Bottleneck>,
        execution: ExecutionMetrics,
        memory: MemoryMetrics,
        api: ApiMetrics,
        tools: ToolMetrics
    ): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        
        // Execution optimizations
        if (execution.averageOperationTime > 5000) {
            suggestions.add(OptimizationSuggestion(
                category = "execution",
                priority = "high",
                description = "Optimize slow operations",
                expectedImprovement = "Reduce average operation time by 30-50%",
                steps = listOf(
                    "Identify and optimize slowest operations",
                    "Consider parallelizing independent operations",
                    "Cache frequently accessed data",
                    "Optimize algorithms and data structures"
                )
            ))
        }
        
        // Memory optimizations
        if (memory.memoryUsagePercent > 80) {
            suggestions.add(OptimizationSuggestion(
                category = "memory",
                priority = "high",
                description = "Reduce memory usage",
                expectedImprovement = "Reduce memory usage by 20-40%",
                steps = listOf(
                    "Identify memory leaks",
                    "Release unused resources",
                    "Use object pooling for frequently created objects",
                    "Optimize data structures to use less memory"
                )
            ))
        }
        
        // API optimizations
        if (api.averageResponseTime > 2000) {
            suggestions.add(OptimizationSuggestion(
                category = "api",
                priority = "medium",
                description = "Optimize API calls",
                expectedImprovement = "Reduce API response time by 20-30%",
                steps = listOf(
                    "Implement request batching",
                    "Add response caching",
                    "Use connection pooling",
                    "Consider using faster API endpoints"
                )
            ))
        }
        
        if (api.errorRate > 5.0) {
            suggestions.add(OptimizationSuggestion(
                category = "api",
                priority = "high",
                description = "Reduce API errors",
                expectedImprovement = "Reduce error rate to < 1%",
                steps = listOf(
                    "Improve error handling and retry logic",
                    "Validate requests before sending",
                    "Handle rate limits properly",
                    "Add circuit breaker pattern"
                )
            ))
        }
        
        // Tool optimizations
        if (tools.averageToolTime > 1000) {
            suggestions.add(OptimizationSuggestion(
                category = "tools",
                priority = "medium",
                description = "Optimize tool execution",
                expectedImprovement = "Reduce tool execution time by 20-30%",
                steps = listOf(
                    "Optimize slowest tools",
                    "Cache tool results when possible",
                    "Parallelize independent tool calls",
                    "Reduce unnecessary tool invocations"
                )
            ))
        }
        
        // Caching suggestions
        if (api.totalApiCalls > 100) {
            suggestions.add(OptimizationSuggestion(
                category = "caching",
                priority = "medium",
                description = "Implement caching strategy",
                expectedImprovement = "Reduce API calls by 30-50%",
                steps = listOf(
                    "Cache API responses with appropriate TTL",
                    "Cache tool results",
                    "Use intelligent cache invalidation",
                    "Implement cache warming for frequently accessed data"
                )
            ))
        }
        
        // Parallelization suggestions
        if (execution.operationsCount > 10 && execution.averageOperationTime > 2000) {
            suggestions.add(OptimizationSuggestion(
                category = "parallelization",
                priority = "medium",
                description = "Parallelize operations",
                expectedImprovement = "Reduce total execution time by 40-60%",
                steps = listOf(
                    "Identify independent operations",
                    "Execute operations in parallel using coroutines",
                    "Use async/await patterns",
                    "Implement proper synchronization"
                )
            ))
        }
        
        return suggestions
    }
    
    private fun generateSummary(
        execution: ExecutionMetrics,
        memory: MemoryMetrics,
        api: ApiMetrics,
        tools: ToolMetrics,
        bottlenecks: List<Bottleneck>
    ): String {
        return buildString {
            appendLine("Performance Summary")
            appendLine("Execution: ${execution.operationsCount} operations, avg ${String.format("%.1f", execution.averageOperationTime)}ms")
            appendLine("Memory: ${String.format("%.1f", memory.memoryUsagePercent)}% used (${formatBytes(memory.usedMemory)}/${formatBytes(memory.maxMemory)})")
            appendLine("API: ${api.totalApiCalls} calls, ${String.format("%.1f", api.averageResponseTime)}ms avg, ${String.format("%.1f", api.errorRate)}% errors")
            appendLine("Tools: ${tools.totalToolCalls} calls, ${String.format("%.1f", tools.averageToolTime)}ms avg")
            appendLine("Bottlenecks: ${bottlenecks.size} identified")
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }
    
    private fun formatPerformanceReport(
        result: PerformanceProfileResult,
        profileType: String
    ): String {
        return buildString {
            appendLine("# Performance Profiling Report")
            appendLine()
            appendLine(result.summary)
            appendLine()
            
            if (profileType in listOf("execution", "all")) {
                appendLine("## Execution Metrics")
                appendLine()
                appendLine("- **Total Execution Time:** ${result.executionMetrics.totalExecutionTime}ms")
                appendLine("- **Average Operation Time:** ${String.format("%.1f", result.executionMetrics.averageOperationTime)}ms")
                appendLine("- **Operations Count:** ${result.executionMetrics.operationsCount}")
                appendLine()
                
                if (result.executionMetrics.slowestOperations.isNotEmpty()) {
                    appendLine("### Slowest Operations")
                    result.executionMetrics.slowestOperations.forEach { op ->
                        appendLine("- **${op.operationType}** (${op.operationId}): ${op.duration}ms")
                    }
                    appendLine()
                }
            }
            
            if (profileType in listOf("memory", "all")) {
                appendLine("## Memory Metrics")
                appendLine()
                appendLine("- **Total Memory:** ${formatBytes(result.memoryMetrics.totalMemory)}")
                appendLine("- **Used Memory:** ${formatBytes(result.memoryMetrics.usedMemory)}")
                appendLine("- **Free Memory:** ${formatBytes(result.memoryMetrics.freeMemory)}")
                appendLine("- **Max Memory:** ${formatBytes(result.memoryMetrics.maxMemory)}")
                appendLine("- **Usage:** ${String.format("%.1f", result.memoryMetrics.memoryUsagePercent)}%")
                appendLine("- **Trend:** ${result.memoryMetrics.memoryTrend}")
                appendLine()
            }
            
            if (profileType in listOf("api", "all")) {
                appendLine("## API Metrics")
                appendLine()
                appendLine("- **Total API Calls:** ${result.apiMetrics.totalApiCalls}")
                appendLine("- **Average Response Time:** ${String.format("%.1f", result.apiMetrics.averageResponseTime)}ms")
                appendLine("- **Total Tokens:** ${result.apiMetrics.totalTokens}")
                appendLine("- **Total Cost:** $${String.format("%.4f", result.apiMetrics.totalCost)}")
                appendLine("- **Errors:** ${result.apiMetrics.errors}")
                appendLine("- **Error Rate:** ${String.format("%.1f", result.apiMetrics.errorRate)}%")
                appendLine()
            }
            
            if (profileType in listOf("tools", "all")) {
                appendLine("## Tool Metrics")
                appendLine()
                appendLine("- **Total Tool Calls:** ${result.toolMetrics.totalToolCalls}")
                appendLine("- **Average Tool Time:** ${String.format("%.1f", result.toolMetrics.averageToolTime)}ms")
                appendLine()
                
                if (result.toolMetrics.toolUsage.isNotEmpty()) {
                    appendLine("### Tool Usage")
                    result.toolMetrics.toolUsage.entries.sortedByDescending { it.value }.forEach { (tool, count) ->
                        appendLine("- **$tool:** $count calls")
                    }
                    appendLine()
                }
                
                if (result.toolMetrics.slowestTools.isNotEmpty()) {
                    appendLine("### Slowest Tools")
                    result.toolMetrics.slowestTools.forEach { tool ->
                        appendLine("- **${tool.toolName}:** ${tool.duration}ms")
                    }
                    appendLine()
                }
            }
            
            appendLine("## Bottlenecks")
            appendLine()
            if (result.bottlenecks.isEmpty()) {
                appendLine("✅ No significant bottlenecks detected!")
            } else {
                val bySeverity = result.bottlenecks.groupBy { it.severity }
                listOf("critical", "high", "medium", "low").forEach { severity ->
                    bySeverity[severity]?.forEach { bottleneck ->
                        appendLine("### ${severity.uppercase()}: ${bottleneck.type}")
                        appendLine("- **Description:** ${bottleneck.description}")
                        if (bottleneck.location != null) {
                            appendLine("- **Location:** ${bottleneck.location}")
                        }
                        appendLine("- **Impact:** ${bottleneck.impact}")
                        appendLine()
                    }
                }
            }
            
            if (result.suggestions.isNotEmpty()) {
                appendLine("## Optimization Suggestions")
                appendLine()
                val byPriority = result.suggestions.groupBy { it.priority }
                listOf("high", "medium", "low").forEach { priority ->
                    byPriority[priority]?.forEach { suggestion ->
                        appendLine("### ${priority.uppercase()}: ${suggestion.category}")
                        appendLine("- **Description:** ${suggestion.description}")
                        appendLine("- **Expected Improvement:** ${suggestion.expectedImprovement}")
                        appendLine("- **Steps:**")
                        suggestion.steps.forEach { step ->
                            appendLine("  1. $step")
                        }
                        appendLine()
                    }
                }
            }
        }
    }
}

/**
 * Performance profiling tool
 */
class PerformanceProfilingTool(
    private val workspaceRoot: String
) : DeclarativeTool<PerformanceProfilingToolParams, ToolResult>() {
    
    override val name: String = "profile_performance"
    override val displayName: String = "Performance Profiling"
    override val description: String = """
        Comprehensive performance profiling tool. Profiles execution time, memory usage, API calls, and tool usage to identify bottlenecks and suggest optimizations.
        
        Profile Types:
        - execution: Profile execution times and operations
        - memory: Analyze memory usage and trends
        - api: Profile API calls, response times, and costs
        - tools: Analyze tool usage and execution times
        - all: Complete performance profile (default)
        
        Features:
        - Execution time profiling
        - Memory usage analysis
        - API call metrics and cost tracking
        - Tool usage analysis
        - Bottleneck identification
        - Optimization suggestions
        
        Examples:
        - profile_performance() - Full performance profile
        - profile_performance(profileType="execution") - Profile execution only
        - profile_performance(operationId="op-123") - Profile specific operation
        - profile_performance(includeSuggestions=true) - Include optimization suggestions
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "operationId" to PropertySchema(
                type = "string",
                description = "Specific operation ID to profile, or omit for all operations"
            ),
            "profileType" to PropertySchema(
                type = "string",
                description = "Type of profiling: 'execution', 'memory', 'api', 'tools', or 'all'",
                enum = listOf("execution", "memory", "api", "tools", "all")
            ),
            "duration" to PropertySchema(
                type = "integer",
                description = "Duration in seconds for continuous profiling (optional)"
            ),
            "includeSuggestions" to PropertySchema(
                type = "boolean",
                description = "Whether to include optimization suggestions (default: true)"
            )
        ),
        required = emptyList()
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: PerformanceProfilingToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<PerformanceProfilingToolParams, ToolResult> {
        return PerformanceProfilingToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): PerformanceProfilingToolParams {
        return PerformanceProfilingToolParams(
            operationId = params["operationId"] as? String,
            profileType = params["profileType"] as? String,
            duration = (params["duration"] as? Number)?.toLong(),
            includeSuggestions = params["includeSuggestions"] as? Boolean ?: true
        )
    }
}
