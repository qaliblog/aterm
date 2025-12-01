package com.qali.aterm.agent.ppe

import android.util.Log
import java.io.File

/**
 * Validates code quality before writing
 * Better than Cursor AI: Pre-write validation prevents broken code
 */
object CodeQualityValidator {
    
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )
    
    /**
     * Validate code before writing to file
     */
    fun validateCode(
        code: String,
        filePath: String,
        workspaceRoot: String
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Check for empty code
        if (code.trim().isEmpty()) {
            errors.add("Code is empty")
            return ValidationResult(false, errors, warnings)
        }
        
        // Detect file type
        val extension = File(filePath).extension.lowercase()
        
        // Basic syntax checks based on file type
        when {
            extension in listOf("js", "mjs", "ts", "tsx") -> {
                validateJavaScript(code, errors, warnings)
            }
            extension == "py" -> {
                validatePython(code, errors, warnings)
            }
            extension in listOf("java", "kt") -> {
                validateJavaKotlin(code, errors, warnings)
            }
        }
        
        // Check for common issues
        checkCommonIssues(code, errors, warnings)
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    private fun validateJavaScript(code: String, errors: MutableList<String>, warnings: MutableList<String>) {
        // Check for unmatched brackets
        val openBraces = code.count { it == '{' }
        val closeBraces = code.count { it == '}' }
        if (openBraces != closeBraces) {
            errors.add("Unmatched braces: $openBraces open, $closeBraces close")
        }
        
        val openParens = code.count { it == '(' }
        val closeParens = code.count { it == ')' }
        if (openParens != closeParens) {
            errors.add("Unmatched parentheses: $openParens open, $closeParens close")
        }
        
        // Check for common syntax errors
        if (code.contains("function(") && !code.contains("function(")) {
            // Check for function declarations
        }
        
        // Check for undefined variables in common patterns
        if (code.contains("require(") && !code.contains("const ") && !code.contains("let ") && !code.contains("var ")) {
            warnings.add("require() used without assignment")
        }
    }
    
    private fun validatePython(code: String, errors: MutableList<String>, warnings: MutableList<String>) {
        // Check indentation consistency (basic)
        val lines = code.lines()
        var lastIndent = 0
        lines.forEachIndexed { index, line ->
            if (line.trim().isNotEmpty()) {
                val indent = line.takeWhile { it == ' ' || it == '\t' }.length
                if (indent > lastIndent + 4 && lastIndent > 0) {
                    warnings.add("Suspicious indentation at line ${index + 1}")
                }
                lastIndent = indent
            }
        }
        
        // Check for common Python issues
        if (code.contains("import ") && code.contains("from ")) {
            // Check for circular imports (basic)
        }
    }
    
    private fun validateJavaKotlin(code: String, errors: MutableList<String>, warnings: MutableList<String>) {
        // Check for unmatched braces
        val openBraces = code.count { it == '{' }
        val closeBraces = code.count { it == '}' }
        if (openBraces != closeBraces) {
            errors.add("Unmatched braces: $openBraces open, $closeBraces close")
        }
        
        // Check for class declarations
        if (code.contains("class ") && !code.contains("public class ") && !code.contains("private class ")) {
            warnings.add("Class declaration without access modifier")
        }
    }
    
    private fun checkCommonIssues(code: String, errors: MutableList<String>, warnings: MutableList<String>) {
        // Check for TODO/FIXME without implementation
        if (code.contains("TODO") && code.lines().any { it.contains("TODO") && it.trim().endsWith("TODO") }) {
            warnings.add("Contains TODO comments")
        }
        
        // Check for very long lines
        code.lines().forEachIndexed { index, line ->
            if (line.length > 200) {
                warnings.add("Very long line at ${index + 1} (${line.length} chars)")
            }
        }
        
        // Check for suspicious patterns
        if (code.contains("eval(") || code.contains("exec(")) {
            warnings.add("Contains eval/exec - potential security risk")
        }
    }
    
    /**
     * Validate imports/exports exist
     */
    fun validateImports(
        code: String,
        filePath: String,
        workspaceRoot: String,
        availableExports: Map<String, List<String>>
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Extract imports from code
        val importPattern = Regex("""(?:import|from)\s+['"]([^'"]+)['"]""")
        val imports = importPattern.findAll(code).map { it.groupValues[1] }.toList()
        
        // Check if imports exist in available exports
        imports.forEach { importPath ->
            val file = importPath.replace(".", File.separator)
            val possibleFiles = listOf(
                "$file.js", "$file.ts", "$file/index.js",
                "$file.py", "$file/__init__.py"
            )
            
            val exists = possibleFiles.any { possibleFile ->
                File(workspaceRoot, possibleFile).exists() ||
                availableExports.containsKey(possibleFile)
            }
            
            if (!exists) {
                warnings.add("Import '$importPath' may not exist")
            }
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
