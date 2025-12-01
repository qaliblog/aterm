package com.qali.aterm.agent.tools

import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.debug.DebugLogger
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Architecture analysis tool
 * Analyzes and visualizes project architecture with dependency graphs, module boundaries, and refactoring suggestions
 */
data class ArchitectureAnalysisToolParams(
    val projectPath: String? = null, // Project root path, or null for workspace root
    val analysisType: String? = null, // "graph", "modules", "circular", "refactor", "diagram", "all"
    val outputFormat: String? = null, // "text", "mermaid", "dot", "json"
    val maxDepth: Int? = null, // Maximum depth for graph traversal
    val includeExternal: Boolean = false // Include external dependencies in analysis
)

data class ArchitectureAnalysisResult(
    val dependencyGraph: DependencyGraph,
    val modules: List<ModuleInfo>,
    val circularDependencies: List<CircularDependency>,
    val moduleBoundaries: ModuleBoundaryAnalysis,
    val refactoringSuggestions: List<RefactoringSuggestion>,
    val diagram: String? // Generated diagram (Mermaid, DOT, or text)
)

data class DependencyGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val clusters: List<GraphCluster>
)

data class GraphNode(
    val id: String,
    val label: String,
    val type: String, // "file", "module", "class", "function"
    val module: String?
)

data class GraphEdge(
    val from: String,
    val to: String,
    val type: String, // "import", "extends", "implements", "calls"
    val weight: Int = 1
)

data class GraphCluster(
    val id: String,
    val label: String,
    val nodes: List<String>
)

data class ModuleInfo(
    val name: String,
    val path: String,
    val files: List<String>,
    val dependencies: List<String>, // Modules this module depends on
    val dependents: List<String>, // Modules that depend on this module
    val cohesion: Double, // 0.0 to 1.0
    val coupling: Double // 0.0 to 1.0
)

data class CircularDependency(
    val cycle: List<String>, // Files/modules in the cycle
    val severity: String, // "low", "medium", "high"
    val description: String,
    val suggestion: String?
)

data class ModuleBoundaryAnalysis(
    val violations: List<BoundaryViolation>,
    val wellDefinedModules: List<String>,
    val ambiguousModules: List<String>
)

data class BoundaryViolation(
    val module: String,
    val violation: String,
    val files: List<String>,
    val description: String
)

data class RefactoringSuggestion(
    val type: String, // "extract", "merge", "split", "move", "invert"
    val target: String,
    val reason: String,
    val impact: String, // "low", "medium", "high"
    val steps: List<String>
)

class ArchitectureAnalysisToolInvocation(
    toolParams: ArchitectureAnalysisToolParams,
    private val workspaceRoot: String
) : ToolInvocation<ArchitectureAnalysisToolParams, ToolResult> {
    
    override val params: ArchitectureAnalysisToolParams = toolParams
    
    override fun getDescription(): String {
        val analysisType = params.analysisType ?: "all"
        return "Analyzing architecture: $analysisType"
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
                llmContent = "Architecture analysis cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        try {
            val projectPath = params.projectPath?.let { File(it) } 
                ?: File(workspaceRoot)
            
            if (!projectPath.exists()) {
                return@withContext ToolResult(
                    llmContent = "Project path does not exist: ${projectPath.absolutePath}",
                    returnDisplay = "Error: Path not found",
                    error = ToolError(
                        message = "Project path does not exist",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            }
            
            updateOutput?.invoke("🔍 Building dependency graph...")
            val dependencyGraph = buildDependencyGraph(projectPath)
            
            updateOutput?.invoke("📦 Analyzing modules...")
            val modules = analyzeModules(projectPath, dependencyGraph)
            
            updateOutput?.invoke("🔄 Detecting circular dependencies...")
            val circularDependencies = detectCircularDependencies(dependencyGraph)
            
            updateOutput?.invoke("🏗️ Analyzing module boundaries...")
            val moduleBoundaries = analyzeModuleBoundaries(modules, dependencyGraph)
            
            updateOutput?.invoke("💡 Generating refactoring suggestions...")
            val refactoringSuggestions = generateRefactoringSuggestions(
                modules, 
                circularDependencies, 
                moduleBoundaries
            )
            
            updateOutput?.invoke("📊 Generating diagram...")
            val diagram = generateDiagram(
                dependencyGraph, 
                modules, 
                params.outputFormat ?: "mermaid"
            )
            
            val analysisType = params.analysisType?.lowercase() ?: "all"
            val formattedResult = when (analysisType) {
                "graph" -> formatDependencyGraph(dependencyGraph, diagram)
                "modules" -> formatModules(modules)
                "circular" -> formatCircularDependencies(circularDependencies)
                "refactor" -> formatRefactoringSuggestions(refactoringSuggestions)
                "diagram" -> formatDiagram(diagram, params.outputFormat ?: "mermaid")
                "all" -> formatFullAnalysis(
                    dependencyGraph, 
                    modules, 
                    circularDependencies, 
                    moduleBoundaries, 
                    refactoringSuggestions, 
                    diagram
                )
                else -> formatFullAnalysis(
                    dependencyGraph, 
                    modules, 
                    circularDependencies, 
                    moduleBoundaries, 
                    refactoringSuggestions, 
                    diagram
                )
            }
            
            DebugLogger.i("ArchitectureAnalysisTool", "Architecture analysis completed", mapOf(
                "nodes" to dependencyGraph.nodes.size,
                "edges" to dependencyGraph.edges.size,
                "modules" to modules.size,
                "circular_deps" to circularDependencies.size,
                "suggestions" to refactoringSuggestions.size
            ))
            
            ToolResult(
                llmContent = formattedResult,
                returnDisplay = "Architecture: ${dependencyGraph.nodes.size} nodes, ${modules.size} modules, ${circularDependencies.size} cycles"
            )
        } catch (e: Exception) {
            DebugLogger.e("ArchitectureAnalysisTool", "Error analyzing architecture", exception = e)
            ToolResult(
                llmContent = "Error analyzing architecture: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun buildDependencyGraph(projectPath: File): DependencyGraph {
        val matrix = CodeDependencyAnalyzer.getDependencyMatrix(projectPath.absolutePath)
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val clusters = mutableMapOf<String, MutableList<String>>()
        
        // Create nodes from files
        matrix.files.forEach { (filePath, metadata) ->
            val module = extractModule(filePath)
            val nodeId = normalizeNodeId(filePath)
            
            nodes.add(GraphNode(
                id = nodeId,
                label = File(filePath).name,
                type = "file",
                module = module
            ))
            
            // Add to cluster
            clusters.getOrPut(module) { mutableListOf() }.add(nodeId)
            
            // Create edges from dependencies
            matrix.dependencies[filePath]?.forEach { depPath ->
                val depNodeId = normalizeNodeId(depPath)
                edges.add(GraphEdge(
                    from = nodeId,
                    to = depNodeId,
                    type = "import",
                    weight = 1
                ))
            }
        }
        
        // Create clusters
        val graphClusters = clusters.map { (moduleName, nodeIds) ->
            GraphCluster(
                id = "cluster_$moduleName",
                label = moduleName,
                nodes = nodeIds
            )
        }
        
        return DependencyGraph(
            nodes = nodes,
            edges = edges,
            clusters = graphClusters
        )
    }
    
    private fun analyzeModules(
        projectPath: File,
        graph: DependencyGraph
    ): List<ModuleInfo> {
        val modules = mutableMapOf<String, MutableList<String>>()
        val moduleDependencies = mutableMapOf<String, MutableSet<String>>()
        val moduleDependents = mutableMapOf<String, MutableSet<String>>()
        
        // Group files by module
        graph.nodes.forEach { node ->
            val module = node.module ?: "root"
            modules.getOrPut(module) { mutableListOf() }.add(node.id)
        }
        
        // Analyze inter-module dependencies
        graph.edges.forEach { edge ->
            val fromNode = graph.nodes.find { it.id == edge.from }
            val toNode = graph.nodes.find { it.id == edge.to }
            
            if (fromNode != null && toNode != null) {
                val fromModule = fromNode.module ?: "root"
                val toModule = toNode.module ?: "root"
                
                if (fromModule != toModule) {
                    moduleDependencies.getOrPut(fromModule) { mutableSetOf() }.add(toModule)
                    moduleDependents.getOrPut(toModule) { mutableSetOf() }.add(fromModule)
                }
            }
        }
        
        // Calculate cohesion and coupling for each module
        return modules.map { (moduleName, files) ->
            val internalEdges = graph.edges.count { edge ->
                val fromNode = graph.nodes.find { it.id == edge.from }
                val toNode = graph.nodes.find { it.id == edge.to }
                fromNode?.module == moduleName && toNode?.module == moduleName
            }
            
            val totalPossibleEdges = files.size * (files.size - 1) / 2
            val cohesion = if (totalPossibleEdges > 0) {
                internalEdges.toDouble() / totalPossibleEdges
            } else {
                1.0
            }
            
            val externalDeps = moduleDependencies[moduleName]?.size ?: 0
            val externalDependents = moduleDependents[moduleName]?.size ?: 0
            val coupling = (externalDeps + externalDependents).toDouble() / maxOf(modules.size - 1, 1)
            
            ModuleInfo(
                name = moduleName,
                path = moduleName,
                files = files,
                dependencies = moduleDependencies[moduleName]?.toList() ?: emptyList(),
                dependents = moduleDependents[moduleName]?.toList() ?: emptyList(),
                cohesion = cohesion.coerceIn(0.0, 1.0),
                coupling = coupling.coerceIn(0.0, 1.0)
            )
        }
    }
    
    private fun detectCircularDependencies(graph: DependencyGraph): List<CircularDependency> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        
        fun detectCycle(nodeId: String, path: List<String>): List<String>? {
            if (nodeId in recursionStack) {
                val cycleStart = path.indexOf(nodeId)
                return path.subList(cycleStart, path.size) + nodeId
            }
            if (nodeId in visited) return null
            
            visited.add(nodeId)
            recursionStack.add(nodeId)
            
            graph.edges.filter { it.from == nodeId }.forEach { edge ->
                detectCycle(edge.to, path + nodeId)?.let { cycle ->
                    recursionStack.remove(nodeId)
                    return cycle
                }
            }
            
            recursionStack.remove(nodeId)
            return null
        }
        
        graph.nodes.forEach { node ->
            if (node.id !in visited) {
                detectCycle(node.id, emptyList())?.let { cycle ->
                    cycles.add(cycle)
                }
            }
        }
        
        return cycles.map { cycle ->
            val severity = when {
                cycle.size <= 3 -> "low"
                cycle.size <= 5 -> "medium"
                else -> "high"
            }
            
            CircularDependency(
                cycle = cycle,
                severity = severity,
                description = "Circular dependency involving ${cycle.size} files",
                suggestion = "Break the cycle by introducing an interface or extracting shared code"
            )
        }
    }
    
    private fun analyzeModuleBoundaries(
        modules: List<ModuleInfo>,
        graph: DependencyGraph
    ): ModuleBoundaryAnalysis {
        val violations = mutableListOf<BoundaryViolation>()
        val wellDefinedModules = mutableListOf<String>()
        val ambiguousModules = mutableListOf<String>()
        
        modules.forEach { module ->
            // Check for high coupling (violates encapsulation)
            if (module.coupling > 0.7) {
                violations.add(BoundaryViolation(
                    module = module.name,
                    violation = "high_coupling",
                    files = module.files,
                    description = "Module has high coupling (${String.format("%.2f", module.coupling)}). Too many external dependencies."
                ))
            }
            
            // Check for low cohesion
            if (module.cohesion < 0.3) {
                violations.add(BoundaryViolation(
                    module = module.name,
                    violation = "low_cohesion",
                    files = module.files,
                    description = "Module has low cohesion (${String.format("%.2f", module.cohesion)}). Files may not belong together."
                ))
            }
            
            // Check for bidirectional dependencies (circular module dependencies)
            if (module.dependencies.any { dep ->
                modules.find { it.name == dep }?.dependencies?.contains(module.name) == true
            }) {
                violations.add(BoundaryViolation(
                    module = module.name,
                    violation = "circular_module_dependency",
                    files = module.files,
                    description = "Module has circular dependencies with other modules"
                ))
            }
            
            // Classify modules
            when {
                module.cohesion > 0.6 && module.coupling < 0.5 -> {
                    wellDefinedModules.add(module.name)
                }
                module.cohesion < 0.4 || module.coupling > 0.6 -> {
                    ambiguousModules.add(module.name)
                }
            }
        }
        
        return ModuleBoundaryAnalysis(
            violations = violations,
            wellDefinedModules = wellDefinedModules,
            ambiguousModules = ambiguousModules
        )
    }
    
    private fun generateRefactoringSuggestions(
        modules: List<ModuleInfo>,
        circularDependencies: List<CircularDependency>,
        boundaries: ModuleBoundaryAnalysis
    ): List<RefactoringSuggestion> {
        val suggestions = mutableListOf<RefactoringSuggestion>()
        
        // Suggestions for circular dependencies
        circularDependencies.forEach { cycle ->
            if (cycle.severity in listOf("medium", "high")) {
                suggestions.add(RefactoringSuggestion(
                    type = "extract",
                    target = cycle.cycle.first(),
                    reason = "Circular dependency detected",
                    impact = cycle.severity,
                    steps = listOf(
                        "Identify shared functionality in the cycle",
                        "Extract shared code to a new module",
                        "Update dependencies to use the new module"
                    )
                ))
            }
        }
        
        // Suggestions for boundary violations
        boundaries.violations.forEach { violation ->
            when (violation.violation) {
                "high_coupling" -> {
                    suggestions.add(RefactoringSuggestion(
                        type = "split",
                        target = violation.module,
                        reason = "High coupling detected",
                        impact = "medium",
                        steps = listOf(
                            "Identify tightly coupled components",
                            "Split module into smaller, focused modules",
                            "Use dependency inversion to reduce coupling"
                        )
                    ))
                }
                "low_cohesion" -> {
                    suggestions.add(RefactoringSuggestion(
                        type = "merge",
                        target = violation.module,
                        reason = "Low cohesion detected",
                        impact = "medium",
                        steps = listOf(
                            "Identify related functionality",
                            "Merge related files into cohesive modules",
                            "Remove unrelated files from module"
                        )
                    ))
                }
                "circular_module_dependency" -> {
                    suggestions.add(RefactoringSuggestion(
                        type = "invert",
                        target = violation.module,
                        reason = "Circular module dependency",
                        impact = "high",
                        steps = listOf(
                            "Introduce an interface or abstraction",
                            "Apply dependency inversion principle",
                            "Break the circular dependency"
                        )
                    ))
                }
            }
        }
        
        // Suggestions for ambiguous modules
        boundaries.ambiguousModules.forEach { moduleName ->
            suggestions.add(RefactoringSuggestion(
                type = "refactor",
                target = moduleName,
                reason = "Module structure is ambiguous",
                impact = "low",
                steps = listOf(
                    "Review module responsibilities",
                    "Improve cohesion or reduce coupling",
                    "Consider splitting or merging with other modules"
                )
            ))
        }
        
        return suggestions
    }
    
    private fun generateDiagram(
        graph: DependencyGraph,
        modules: List<ModuleInfo>,
        format: String
    ): String? {
        return when (format.lowercase()) {
            "mermaid" -> generateMermaidDiagram(graph, modules)
            "dot" -> generateDotDiagram(graph, modules)
            "text" -> generateTextDiagram(graph, modules)
            else -> generateMermaidDiagram(graph, modules)
        }
    }
    
    private fun generateMermaidDiagram(graph: DependencyGraph, modules: List<ModuleInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("```mermaid")
        sb.appendLine("graph TD")
        
        // Add nodes grouped by modules
        modules.forEach { module ->
            sb.appendLine("    subgraph ${module.name}")
            module.files.forEach { fileId ->
                val node = graph.nodes.find { it.id == fileId }
                if (node != null) {
                    sb.appendLine("        ${node.id}[\"${node.label}\"]")
                }
            }
            sb.appendLine("    end")
        }
        
        // Add edges (limit to avoid clutter)
        graph.edges.take(100).forEach { edge ->
            sb.appendLine("    ${edge.from} --> ${edge.to}")
        }
        
        sb.appendLine("```")
        return sb.toString()
    }
    
    private fun generateDotDiagram(graph: DependencyGraph, modules: List<ModuleInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("digraph Architecture {")
        sb.appendLine("    rankdir=LR;")
        sb.appendLine("    node [shape=box];")
        
        // Add nodes
        graph.nodes.forEach { node ->
            sb.appendLine("    \"${node.id}\" [label=\"${node.label}\"];")
        }
        
        // Add edges (limit to avoid clutter)
        graph.edges.take(100).forEach { edge ->
            sb.appendLine("    \"${edge.from}\" -> \"${edge.to}\";")
        }
        
        sb.appendLine("}")
        return sb.toString()
    }
    
    private fun generateTextDiagram(graph: DependencyGraph, modules: List<ModuleInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("Architecture Diagram (Text)")
        sb.appendLine("=".repeat(50))
        sb.appendLine()
        
        modules.forEach { module ->
            sb.appendLine("Module: ${module.name}")
            sb.appendLine("-".repeat(30))
            module.files.forEach { fileId ->
                val node = graph.nodes.find { it.id == fileId }
                if (node != null) {
                    sb.appendLine("  - ${node.label}")
                }
            }
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    private fun extractModule(filePath: String): String {
        val parts = filePath.split("/")
        return when {
            parts.size > 1 -> parts[0]
            else -> "root"
        }
    }
    
    private fun normalizeNodeId(path: String): String {
        return path.replace("/", "_").replace(".", "_").replace("-", "_")
    }
    
    private fun formatFullAnalysis(
        graph: DependencyGraph,
        modules: List<ModuleInfo>,
        circularDependencies: List<CircularDependency>,
        boundaries: ModuleBoundaryAnalysis,
        suggestions: List<RefactoringSuggestion>,
        diagram: String?
    ): String {
        return buildString {
            appendLine("# Architecture Analysis")
            appendLine()
            appendLine("## Dependency Graph")
            appendLine()
            appendLine("- **Nodes:** ${graph.nodes.size}")
            appendLine("- **Edges:** ${graph.edges.size}")
            appendLine("- **Clusters:** ${graph.clusters.size}")
            appendLine()
            
            if (diagram != null) {
                appendLine("### Diagram")
                appendLine()
                appendLine(diagram)
                appendLine()
            }
            
            appendLine("## Modules")
            appendLine()
            modules.forEach { module ->
                appendLine("### ${module.name}")
                appendLine("- **Files:** ${module.files.size}")
                appendLine("- **Dependencies:** ${module.dependencies.joinToString(", ")}")
                appendLine("- **Dependents:** ${module.dependents.joinToString(", ")}")
                appendLine("- **Cohesion:** ${String.format("%.2f", module.cohesion)}")
                appendLine("- **Coupling:** ${String.format("%.2f", module.coupling)}")
                appendLine()
            }
            
            appendLine("## Circular Dependencies")
            appendLine()
            if (circularDependencies.isEmpty()) {
                appendLine("✅ No circular dependencies found!")
            } else {
                circularDependencies.forEach { cycle ->
                    appendLine("### ${cycle.severity.uppercase()} Severity")
                    appendLine("- **Cycle:** ${cycle.cycle.joinToString(" → ")}")
                    appendLine("- **Description:** ${cycle.description}")
                    if (cycle.suggestion != null) {
                        appendLine("- **Suggestion:** ${cycle.suggestion}")
                    }
                    appendLine()
                }
            }
            
            appendLine("## Module Boundaries")
            appendLine()
            appendLine("### Well-Defined Modules")
            appendLine(boundaries.wellDefinedModules.joinToString(", ") { "`$it`" })
            appendLine()
            appendLine("### Ambiguous Modules")
            appendLine(boundaries.ambiguousModules.joinToString(", ") { "`$it`" })
            appendLine()
            if (boundaries.violations.isNotEmpty()) {
                appendLine("### Boundary Violations")
                boundaries.violations.forEach { violation ->
                    appendLine("- **${violation.module}:** ${violation.description}")
                }
                appendLine()
            }
            
            appendLine("## Refactoring Suggestions")
            appendLine()
            if (suggestions.isEmpty()) {
                appendLine("✅ No refactoring suggestions at this time.")
            } else {
                suggestions.forEach { suggestion ->
                    appendLine("### ${suggestion.type.uppercase()}: ${suggestion.target}")
                    appendLine("- **Reason:** ${suggestion.reason}")
                    appendLine("- **Impact:** ${suggestion.impact}")
                    appendLine("- **Steps:**")
                    suggestion.steps.forEach { step ->
                        appendLine("  1. $step")
                    }
                    appendLine()
                }
            }
        }
    }
    
    private fun formatDependencyGraph(graph: DependencyGraph, diagram: String?): String {
        return buildString {
            appendLine("# Dependency Graph")
            appendLine()
            appendLine("- **Nodes:** ${graph.nodes.size}")
            appendLine("- **Edges:** ${graph.edges.size}")
            appendLine()
            if (diagram != null) {
                appendLine(diagram)
            }
        }
    }
    
    private fun formatModules(modules: List<ModuleInfo>): String {
        return buildString {
            appendLine("# Module Analysis")
            appendLine()
            modules.forEach { module ->
                appendLine("## ${module.name}")
                appendLine("- Files: ${module.files.size}")
                appendLine("- Cohesion: ${String.format("%.2f", module.cohesion)}")
                appendLine("- Coupling: ${String.format("%.2f", module.coupling)}")
                appendLine()
            }
        }
    }
    
    private fun formatCircularDependencies(circularDependencies: List<CircularDependency>): String {
        return buildString {
            appendLine("# Circular Dependencies")
            appendLine()
            if (circularDependencies.isEmpty()) {
                appendLine("✅ No circular dependencies found!")
            } else {
                circularDependencies.forEach { cycle ->
                    appendLine("## ${cycle.severity.uppercase()}: ${cycle.cycle.size} files")
                    appendLine(cycle.cycle.joinToString(" → "))
                    appendLine()
                }
            }
        }
    }
    
    private fun formatRefactoringSuggestions(suggestions: List<RefactoringSuggestion>): String {
        return buildString {
            appendLine("# Refactoring Suggestions")
            appendLine()
            suggestions.forEach { suggestion ->
                appendLine("## ${suggestion.type.uppercase()}: ${suggestion.target}")
                appendLine("- **Reason:** ${suggestion.reason}")
                appendLine("- **Impact:** ${suggestion.impact}")
                appendLine("- **Steps:**")
                suggestion.steps.forEach { step ->
                    appendLine("  1. $step")
                }
                appendLine()
            }
        }
    }
    
    private fun formatDiagram(diagram: String?, format: String): String {
        return diagram ?: "No diagram generated"
    }
}

/**
 * Architecture analysis tool
 */
class ArchitectureAnalysisTool(
    private val workspaceRoot: String
) : DeclarativeTool<ArchitectureAnalysisToolParams, ToolResult>() {
    
    override val name: String = "analyze_architecture"
    override val displayName: String = "Architecture Analysis"
    override val description: String = """
        Comprehensive architecture analysis tool. Analyzes and visualizes project architecture with dependency graphs, module boundaries, and refactoring suggestions.
        
        Features:
        - Generate dependency graphs (nodes, edges, clusters)
        - Analyze module structure and boundaries
        - Detect circular dependencies
        - Identify refactoring opportunities
        - Generate architecture diagrams (Mermaid, DOT, text)
        
        Analysis Types:
        - graph: Generate dependency graph
        - modules: Analyze module structure
        - circular: Detect circular dependencies
        - refactor: Generate refactoring suggestions
        - diagram: Generate architecture diagram
        - all: Complete analysis (default)
        
        Output Formats:
        - mermaid: Mermaid diagram syntax
        - dot: Graphviz DOT format
        - text: Text-based diagram
        - json: JSON format (future)
        
        Examples:
        - analyze_architecture() - Full architecture analysis
        - analyze_architecture(analysisType="circular") - Find circular dependencies
        - analyze_architecture(analysisType="diagram", outputFormat="mermaid") - Generate Mermaid diagram
    """.trimIndent()
    
    override val parameterSchema: FunctionParameters = FunctionParameters(
        type = "object",
        properties = mapOf(
            "projectPath" to PropertySchema(
                type = "string",
                description = "Project root path, or omit for workspace root"
            ),
            "analysisType" to PropertySchema(
                type = "string",
                description = "Type of analysis: 'graph', 'modules', 'circular', 'refactor', 'diagram', or 'all'",
                enum = listOf("graph", "modules", "circular", "refactor", "diagram", "all")
            ),
            "outputFormat" to PropertySchema(
                type = "string",
                description = "Diagram output format: 'mermaid', 'dot', 'text', or 'json'",
                enum = listOf("mermaid", "dot", "text", "json")
            ),
            "maxDepth" to PropertySchema(
                type = "integer",
                description = "Maximum depth for graph traversal (optional)"
            ),
            "includeExternal" to PropertySchema(
                type = "boolean",
                description = "Include external dependencies in analysis (default: false)"
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
        params: ArchitectureAnalysisToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ArchitectureAnalysisToolParams, ToolResult> {
        return ArchitectureAnalysisToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ArchitectureAnalysisToolParams {
        return ArchitectureAnalysisToolParams(
            projectPath = params["projectPath"] as? String,
            analysisType = params["analysisType"] as? String,
            outputFormat = params["outputFormat"] as? String,
            maxDepth = params["maxDepth"] as? Int,
            includeExternal = params["includeExternal"] as? Boolean ?: false
        )
    }
}
