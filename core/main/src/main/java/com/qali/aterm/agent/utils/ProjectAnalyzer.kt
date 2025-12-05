package com.qali.aterm.agent.utils

import java.io.File
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * Project analyzer - generates .analysis JSON file
 * Similar to blueprint but strips project into JSON format
 */
object ProjectAnalyzer {
    
    /**
     * Project analysis result
     */
    data class ProjectAnalysis(
        val projectType: String,
        val structure: ProjectStructure,
        val dependencies: Dependencies,
        val files: List<FileInfo>,
        val metadata: ProjectMetadata
    )
    
    /**
     * Project structure
     */
    data class ProjectStructure(
        val root: String,
        val directories: List<String>,
        val entryPoints: List<String>,
        val configFiles: List<String>
    )
    
    /**
     * Dependencies
     */
    data class Dependencies(
        val runtime: List<String> = emptyList(),
        val dev: List<String> = emptyList(),
        val build: List<String> = emptyList()
    )
    
    /**
     * File information
     */
    data class FileInfo(
        val path: String,
        val type: String, // "source", "config", "test", "documentation", etc.
        val language: String? = null,
        val imports: List<String> = emptyList(),
        val exports: List<String> = emptyList(),
        val functions: List<String> = emptyList(),
        val classes: List<String> = emptyList()
    )
    
    /**
     * Project metadata
     */
    data class ProjectMetadata(
        val name: String? = null,
        val version: String? = null,
        val description: String? = null,
        val author: String? = null,
        val license: String? = null
    )
    
    /**
     * Analyze project and generate .analysis JSON file
     */
    fun analyzeProject(workspaceRoot: String): ProjectAnalysis {
        val rootDir = File(workspaceRoot)
        
        // Detect project type
        val projectType = detectProjectType(rootDir)
        
        // Analyze structure
        val structure = analyzeStructure(rootDir, workspaceRoot)
        
        // Analyze dependencies
        val dependencies = analyzeDependencies(rootDir, projectType)
        
        // Analyze files
        val files = analyzeFiles(rootDir, workspaceRoot)
        
        // Extract metadata
        val metadata = extractMetadata(rootDir, projectType)
        
        return ProjectAnalysis(
            projectType = projectType,
            structure = structure,
            dependencies = dependencies,
            files = files,
            metadata = metadata
        )
    }
    
    /**
     * Save analysis to .analysis file
     */
    fun saveAnalysis(analysis: ProjectAnalysis, workspaceRoot: String): File {
        val analysisFile = File(workspaceRoot, ".analysis")
        
        val json = JSONObject()
        json.put("projectType", analysis.projectType)
        json.put("metadata", metadataToJson(analysis.metadata))
        json.put("structure", structureToJson(analysis.structure))
        json.put("dependencies", dependenciesToJson(analysis.dependencies))
        
        val filesArray = JSONArray()
        analysis.files.forEach { fileInfo ->
            filesArray.put(fileInfoToJson(fileInfo))
        }
        json.put("files", filesArray)
        
        analysisFile.writeText(json.toString(2))
        Log.d("ProjectAnalyzer", "Saved analysis to ${analysisFile.absolutePath}")
        
        return analysisFile
    }
    
    /**
     * Load analysis from .analysis file
     */
    fun loadAnalysis(workspaceRoot: String): ProjectAnalysis? {
        val analysisFile = File(workspaceRoot, ".analysis")
        if (!analysisFile.exists()) {
            return null
        }
        
        return try {
            val json = JSONObject(analysisFile.readText())
            parseAnalysisFromJson(json)
        } catch (e: Exception) {
            Log.e("ProjectAnalyzer", "Failed to load analysis: ${e.message}", e)
            null
        }
    }
    
    /**
     * Detect project type
     */
    private fun detectProjectType(rootDir: File): String {
        return when {
            File(rootDir, "package.json").exists() -> "nodejs"
            File(rootDir, "build.gradle").exists() || File(rootDir, "build.gradle.kts").exists() -> "android/gradle"
            File(rootDir, "pom.xml").exists() -> "java/maven"
            File(rootDir, "Cargo.toml").exists() -> "rust"
            File(rootDir, "go.mod").exists() -> "go"
            File(rootDir, "requirements.txt").exists() || File(rootDir, "setup.py").exists() -> "python"
            File(rootDir, "Makefile").exists() -> "c/cpp"
            else -> "unknown"
        }
    }
    
    /**
     * Analyze project structure
     */
    private fun analyzeStructure(rootDir: File, workspaceRoot: String): ProjectStructure {
        val directories = mutableListOf<String>()
        val entryPoints = mutableListOf<String>()
        val configFiles = mutableListOf<String>()
        
        rootDir.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                val relPath = file.relativeTo(rootDir).path
                if (relPath.isNotEmpty() && !relPath.startsWith(".")) {
                    directories.add(relPath)
                }
            } else if (file.isFile) {
                val relPath = file.relativeTo(rootDir).path
                val name = file.name.lowercase()
                
                // Entry points
                if (name == "index.js" || name == "main.js" || name == "app.js" ||
                    name == "server.js" || name == "main.py" || name == "main.rs" ||
                    name == "main.go" || name == "main.cpp" || name == "main.c") {
                    entryPoints.add(relPath)
                }
                
                // Config files
                if (name.endsWith(".json") || name.endsWith(".toml") || 
                    name.endsWith(".yaml") || name.endsWith(".yml") ||
                    name == "makefile" || name == "cmakelists.txt") {
                    configFiles.add(relPath)
                }
            }
        }
        
        return ProjectStructure(
            root = workspaceRoot,
            directories = directories.sorted(),
            entryPoints = entryPoints.sorted(),
            configFiles = configFiles.sorted()
        )
    }
    
    /**
     * Analyze dependencies
     */
    private fun analyzeDependencies(rootDir: File, projectType: String): Dependencies {
        val runtime = mutableListOf<String>()
        val dev = mutableListOf<String>()
        val build = mutableListOf<String>()
        
        when (projectType) {
            "nodejs" -> {
                val packageJson = File(rootDir, "package.json")
                if (packageJson.exists()) {
                    try {
                        val json = JSONObject(packageJson.readText())
                        val deps = json.optJSONObject("dependencies") ?: JSONObject()
                        val devDeps = json.optJSONObject("devDependencies") ?: JSONObject()
                        
                        deps.keys().forEach { runtime.add(it) }
                        devDeps.keys().forEach { dev.add(it) }
                    } catch (e: Exception) {
                        Log.w("ProjectAnalyzer", "Failed to parse package.json: ${e.message}")
                    }
                }
            }
            "python" -> {
                val requirements = File(rootDir, "requirements.txt")
                if (requirements.exists()) {
                    requirements.readLines().forEach { line ->
                        val dep = line.trim().split("==", ">=", "<=", ">", "<").first().trim()
                        if (dep.isNotEmpty() && !dep.startsWith("#")) {
                            runtime.add(dep)
                        }
                    }
                }
            }
        }
        
        return Dependencies(
            runtime = runtime.sorted(),
            dev = dev.sorted(),
            build = build.sorted()
        )
    }
    
    /**
     * Analyze files
     */
    private fun analyzeFiles(rootDir: File, workspaceRoot: String): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        
        rootDir.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.startsWith(".") && 
                !file.path.contains("/node_modules/") &&
                !file.path.contains("/.git/")) {
                val relPath = file.relativeTo(rootDir).path
                val language = detectLanguage(file)
                val type = detectFileType(relPath, language)
                
                val fileInfo = FileInfo(
                    path = relPath,
                    type = type,
                    language = language,
                    imports = extractImports(file, language),
                    exports = extractExports(file, language),
                    functions = extractFunctions(file, language),
                    classes = extractClasses(file, language)
                )
                
                files.add(fileInfo)
            }
        }
        
        return files.sortedBy { it.path }
    }
    
    /**
     * Extract metadata
     */
    private fun extractMetadata(rootDir: File, projectType: String): ProjectMetadata {
        return when (projectType) {
            "nodejs" -> {
                val packageJson = File(rootDir, "package.json")
                if (packageJson.exists()) {
                    try {
                        val json = JSONObject(packageJson.readText())
                        ProjectMetadata(
                            name = json.optString("name", null),
                            version = json.optString("version", null),
                            description = json.optString("description", null),
                            author = json.optString("author", null),
                            license = json.optString("license", null)
                        )
                    } catch (e: Exception) {
                        ProjectMetadata()
                    }
                } else {
                    ProjectMetadata()
                }
            }
            else -> ProjectMetadata()
        }
    }
    
    /**
     * Detect language from file
     */
    private fun detectLanguage(file: File): String? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "java" -> "java"
            "kt" -> "kotlin"
            "rs" -> "rust"
            "go" -> "go"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "scala" -> "scala"
            "dart" -> "dart"
            else -> null
        }
    }
    
    /**
     * Detect file type
     */
    private fun detectFileType(path: String, language: String?): String {
        return when {
            path.contains("/test/") || path.contains("test.") || path.contains("spec.") -> "test"
            path.contains("/docs/") || path.endsWith(".md") -> "documentation"
            path.endsWith(".json") || path.endsWith(".toml") || path.endsWith(".yaml") -> "config"
            language != null -> "source"
            else -> "other"
        }
    }
    
    /**
     * Extract imports
     */
    private fun extractImports(file: File, language: String?): List<String> {
        if (language == null) return emptyList()
        
        return try {
            val content = file.readText()
            val imports = mutableListOf<String>()
            
            when (language) {
                "javascript", "typescript" -> {
                    val importRegex = Regex("""(?:import|require)\s*\(?['"]([^'"]+)['"]""")
                    importRegex.findAll(content).forEach { imports.add(it.groupValues[1]) }
                }
                "python" -> {
                    val importRegex = Regex("""(?:from|import)\s+['"]?([^'"]+)['"]?""")
                    importRegex.findAll(content).forEach { imports.add(it.groupValues[1]) }
                }
            }
            
            imports.distinct().sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Extract exports
     */
    private fun extractExports(file: File, language: String?): List<String> {
        if (language == null) return emptyList()
        
        return try {
            val content = file.readText()
            val exports = mutableListOf<String>()
            
            when (language) {
                "javascript", "typescript" -> {
                    val exportRegex = Regex("""export\s+(?:default\s+)?(?:function|const|class|let|var)\s+(\w+)""")
                    exportRegex.findAll(content).forEach { exports.add(it.groupValues[1]) }
                }
            }
            
            exports.distinct().sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Extract functions
     */
    private fun extractFunctions(file: File, language: String?): List<String> {
        if (language == null) return emptyList()
        
        return try {
            val content = file.readText()
            val functions = mutableListOf<String>()
            
            when (language) {
                "javascript", "typescript" -> {
                    val funcRegex = Regex("""(?:function|const|let|var)\s+(\w+)\s*[=(]""")
                    funcRegex.findAll(content).forEach { functions.add(it.groupValues[1]) }
                }
                "python" -> {
                    val funcRegex = Regex("""def\s+(\w+)\s*\(""")
                    funcRegex.findAll(content).forEach { functions.add(it.groupValues[1]) }
                }
            }
            
            functions.distinct().sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Extract classes
     */
    private fun extractClasses(file: File, language: String?): List<String> {
        if (language == null) return emptyList()
        
        return try {
            val content = file.readText()
            val classes = mutableListOf<String>()
            
            when (language) {
                "javascript", "typescript" -> {
                    val classRegex = Regex("""class\s+(\w+)""")
                    classRegex.findAll(content).forEach { classes.add(it.groupValues[1]) }
                }
                "python" -> {
                    val classRegex = Regex("""class\s+(\w+)""")
                    classRegex.findAll(content).forEach { classes.add(it.groupValues[1]) }
                }
            }
            
            classes.distinct().sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Convert metadata to JSON
     */
    private fun metadataToJson(metadata: ProjectMetadata): JSONObject {
        val json = JSONObject()
        metadata.name?.let { json.put("name", it) }
        metadata.version?.let { json.put("version", it) }
        metadata.description?.let { json.put("description", it) }
        metadata.author?.let { json.put("author", it) }
        metadata.license?.let { json.put("license", it) }
        return json
    }
    
    /**
     * Convert structure to JSON
     */
    private fun structureToJson(structure: ProjectStructure): JSONObject {
        val json = JSONObject()
        json.put("root", structure.root)
        json.put("directories", JSONArray(structure.directories))
        json.put("entryPoints", JSONArray(structure.entryPoints))
        json.put("configFiles", JSONArray(structure.configFiles))
        return json
    }
    
    /**
     * Convert dependencies to JSON
     */
    private fun dependenciesToJson(dependencies: Dependencies): JSONObject {
        val json = JSONObject()
        json.put("runtime", JSONArray(dependencies.runtime))
        json.put("dev", JSONArray(dependencies.dev))
        json.put("build", JSONArray(dependencies.build))
        return json
    }
    
    /**
     * Convert file info to JSON
     */
    private fun fileInfoToJson(fileInfo: FileInfo): JSONObject {
        val json = JSONObject()
        json.put("path", fileInfo.path)
        json.put("type", fileInfo.type)
        fileInfo.language?.let { json.put("language", it) }
        json.put("imports", JSONArray(fileInfo.imports))
        json.put("exports", JSONArray(fileInfo.exports))
        json.put("functions", JSONArray(fileInfo.functions))
        json.put("classes", JSONArray(fileInfo.classes))
        return json
    }
    
    /**
     * Parse analysis from JSON
     */
    private fun parseAnalysisFromJson(json: JSONObject): ProjectAnalysis {
        val projectType = json.getString("projectType")
        val metadata = parseMetadataFromJson(json.getJSONObject("metadata"))
        val structure = parseStructureFromJson(json.getJSONObject("structure"))
        val dependencies = parseDependenciesFromJson(json.getJSONObject("dependencies"))
        
        val filesArray = json.getJSONArray("files")
        val files = mutableListOf<FileInfo>()
        for (i in 0 until filesArray.length()) {
            files.add(parseFileInfoFromJson(filesArray.getJSONObject(i)))
        }
        
        return ProjectAnalysis(
            projectType = projectType,
            structure = structure,
            dependencies = dependencies,
            files = files,
            metadata = metadata
        )
    }
    
    private fun parseMetadataFromJson(json: JSONObject): ProjectMetadata {
        return ProjectMetadata(
            name = json.optString("name", null),
            version = json.optString("version", null),
            description = json.optString("description", null),
            author = json.optString("author", null),
            license = json.optString("license", null)
        )
    }
    
    private fun parseStructureFromJson(json: JSONObject): ProjectStructure {
        val dirsArray = json.getJSONArray("directories")
        val directories = mutableListOf<String>()
        for (i in 0 until dirsArray.length()) {
            directories.add(dirsArray.getString(i))
        }
        
        val entryPointsArray = json.getJSONArray("entryPoints")
        val entryPoints = mutableListOf<String>()
        for (i in 0 until entryPointsArray.length()) {
            entryPoints.add(entryPointsArray.getString(i))
        }
        
        val configFilesArray = json.getJSONArray("configFiles")
        val configFiles = mutableListOf<String>()
        for (i in 0 until configFilesArray.length()) {
            configFiles.add(configFilesArray.getString(i))
        }
        
        return ProjectStructure(
            root = json.getString("root"),
            directories = directories,
            entryPoints = entryPoints,
            configFiles = configFiles
        )
    }
    
    private fun parseDependenciesFromJson(json: JSONObject): Dependencies {
        val runtimeArray = json.getJSONArray("runtime")
        val runtime = mutableListOf<String>()
        for (i in 0 until runtimeArray.length()) {
            runtime.add(runtimeArray.getString(i))
        }
        
        val devArray = json.getJSONArray("dev")
        val dev = mutableListOf<String>()
        for (i in 0 until devArray.length()) {
            dev.add(devArray.getString(i))
        }
        
        val buildArray = json.getJSONArray("build")
        val build = mutableListOf<String>()
        for (i in 0 until buildArray.length()) {
            build.add(buildArray.getString(i))
        }
        
        return Dependencies(
            runtime = runtime,
            dev = dev,
            build = build
        )
    }
    
    private fun parseFileInfoFromJson(json: JSONObject): FileInfo {
        val importsArray = json.getJSONArray("imports")
        val imports = mutableListOf<String>()
        for (i in 0 until importsArray.length()) {
            imports.add(importsArray.getString(i))
        }
        
        val exportsArray = json.getJSONArray("exports")
        val exports = mutableListOf<String>()
        for (i in 0 until exportsArray.length()) {
            exports.add(exportsArray.getString(i))
        }
        
        val functionsArray = json.getJSONArray("functions")
        val functions = mutableListOf<String>()
        for (i in 0 until functionsArray.length()) {
            functions.add(functionsArray.getString(i))
        }
        
        val classesArray = json.getJSONArray("classes")
        val classes = mutableListOf<String>()
        for (i in 0 until classesArray.length()) {
            classes.add(classesArray.getString(i))
        }
        
        return FileInfo(
            path = json.getString("path"),
            type = json.getString("type"),
            language = json.optString("language", null),
            imports = imports,
            exports = exports,
            functions = functions,
            classes = classes
        )
    }
}
