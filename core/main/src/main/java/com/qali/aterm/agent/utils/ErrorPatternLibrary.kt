package com.qali.aterm.agent.utils

import java.util.regex.Pattern

/**
 * Comprehensive error pattern library for multiple programming languages
 * Supports 15+ languages with language-specific error patterns
 */
object ErrorPatternLibrary {
    
    /**
     * Error pattern for a specific language
     */
    data class LanguagePattern(
        val language: String,
        val patterns: List<Pattern>,
        val errorTypePatterns: Map<String, Pattern>
    )
    
    /**
     * Get error patterns for a specific language
     */
    fun getPatternsForLanguage(language: String): LanguagePattern {
        return when (language.lowercase()) {
            "javascript", "js", "typescript", "ts", "jsx", "tsx" -> getJavaScriptPatterns()
            "python", "py" -> getPythonPatterns()
            "java", "kotlin", "kt" -> getJavaKotlinPatterns()
            "rust", "rs" -> getRustPatterns()
            "go" -> getGoPatterns()
            "c", "cpp", "c++", "cxx" -> getCppPatterns()
            "ruby", "rb" -> getRubyPatterns()
            "php" -> getPhpPatterns()
            "swift" -> getSwiftPatterns()
            "r" -> getRPatterns()
            "scala" -> getScalaPatterns()
            "dart" -> getDartPatterns()
            "lua" -> getLuaPatterns()
            "perl", "pl" -> getPerlPatterns()
            "shell", "bash", "sh" -> getShellPatterns()
            else -> getGenericPatterns()
        }
    }
    
    /**
     * JavaScript/TypeScript patterns
     */
    private fun getJavaScriptPatterns(): LanguagePattern {
        val patterns = listOf(
            // "at functionName (/path/to/file.js:123:45)"
            Pattern.compile("""at\s+(?:[^\s]+\s+)?\(?([^:]+):(\d+):(\d+)\)?"""),
            // "at /path/to/file.js:123:45"
            Pattern.compile("""at\s+([^:]+):(\d+):(\d+)"""),
            // "Error: message\n    at file.js:123"
            Pattern.compile("""at\s+([^\s]+):(\d+)"""),
            // "ReferenceError: message at file.js:123"
            Pattern.compile("""(ReferenceError|TypeError|SyntaxError|RangeError|URIError):\s*(.+?)(?:\s+at\s+([^:]+):(\d+))?"""),
            // Module not found
            Pattern.compile("""Cannot find module\s+['"]([^'"]+)['"]"""),
            // Import error
            Pattern.compile("""Error: Cannot find module\s+['"]([^'"]+)['"]""")
        )
        
        val errorTypePatterns = mapOf(
            "ReferenceError" to Pattern.compile("""ReferenceError:\s*(.+)"""),
            "TypeError" to Pattern.compile("""TypeError:\s*(.+)"""),
            "SyntaxError" to Pattern.compile("""SyntaxError:\s*(.+)"""),
            "RangeError" to Pattern.compile("""RangeError:\s*(.+)"""),
            "URIError" to Pattern.compile("""URIError:\s*(.+)""")
        )
        
        return LanguagePattern("javascript", patterns, errorTypePatterns)
    }
    
    /**
     * Python patterns
     */
    private fun getPythonPatterns(): LanguagePattern {
        val patterns = listOf(
            // "File \"/path/to/file.py\", line 123"
            Pattern.compile("""File\s+["']([^"']+)["'],\s*line\s+(\d+)"""),
            // "  File \"/path/to/file.py\", line 123, in function"
            Pattern.compile("""File\s+["']([^"']+)["'],\s*line\s+(\d+)(?:,\s*in\s+(\w+))?"""),
            // Traceback with line numbers
            Pattern.compile("""Traceback\s+\(most\s+recent\s+call\s+last\):.*?File\s+["']([^"']+)["'],\s*line\s+(\d+)""", Pattern.DOTALL),
            // Import error
            Pattern.compile("""ModuleNotFoundError:\s+No\s+module\s+named\s+['"]([^'"]+)['"]"""),
            // Name error
            Pattern.compile("""NameError:\s+name\s+['"]([^'"]+)['"]\s+is\s+not\s+defined""")
        )
        
        val errorTypePatterns = mapOf(
            "SyntaxError" to Pattern.compile("""SyntaxError:\s*(.+)"""),
            "IndentationError" to Pattern.compile("""IndentationError:\s*(.+)"""),
            "NameError" to Pattern.compile("""NameError:\s*(.+)"""),
            "TypeError" to Pattern.compile("""TypeError:\s*(.+)"""),
            "ValueError" to Pattern.compile("""ValueError:\s*(.+)"""),
            "KeyError" to Pattern.compile("""KeyError:\s*(.+)"""),
            "AttributeError" to Pattern.compile("""AttributeError:\s*(.+)"""),
            "ImportError" to Pattern.compile("""ImportError:\s*(.+)"""),
            "ModuleNotFoundError" to Pattern.compile("""ModuleNotFoundError:\s*(.+)""")
        )
        
        return LanguagePattern("python", patterns, errorTypePatterns)
    }
    
    /**
     * Java/Kotlin patterns
     */
    private fun getJavaKotlinPatterns(): LanguagePattern {
        val patterns = listOf(
            // "at com.example.Class.method(Class.java:123)"
            Pattern.compile("""at\s+[^\s]+\s*\(([^:]+):(\d+)\)"""),
            // "Caused by: ... at Class.java:123"
            Pattern.compile("""Caused\s+by:.*?at\s+[^\s]+\s*\(([^:]+):(\d+)\)""", Pattern.DOTALL),
            // Exception with file
            Pattern.compile("""(\w+Exception):\s*(.+?)(?:\s+at\s+[^\s]+\s*\(([^:]+):(\d+)\))?""")
        )
        
        val errorTypePatterns = mapOf(
            "NullPointerException" to Pattern.compile("""NullPointerException:\s*(.+)"""),
            "ArrayIndexOutOfBoundsException" to Pattern.compile("""ArrayIndexOutOfBoundsException:\s*(.+)"""),
            "ClassNotFoundException" to Pattern.compile("""ClassNotFoundException:\s*(.+)"""),
            "IllegalArgumentException" to Pattern.compile("""IllegalArgumentException:\s*(.+)"""),
            "RuntimeException" to Pattern.compile("""RuntimeException:\s*(.+)""")
        )
        
        return LanguagePattern("java", patterns, errorTypePatterns)
    }
    
    /**
     * Rust patterns
     */
    private fun getRustPatterns(): LanguagePattern {
        val patterns = listOf(
            // "error[E0000]: message\n --> src/main.rs:5:10"
            Pattern.compile("""error\[E\d+\]:\s*(.+?)\s+-->\s+([^:]+):(\d+):(\d+)""", Pattern.DOTALL),
            // "warning: message\n --> src/main.rs:5:10"
            Pattern.compile("""warning:\s*(.+?)\s+-->\s+([^:]+):(\d+):(\d+)""", Pattern.DOTALL),
            // Panic message
            Pattern.compile("""thread\s+['"][^'"]+['"]\s+panicked\s+at\s+['"]([^'"]+)['"],\s+([^:]+):(\d+)""")
        )
        
        val errorTypePatterns = mapOf(
            "CompileError" to Pattern.compile("""error\[E\d+\]:\s*(.+)"""),
            "Warning" to Pattern.compile("""warning:\s*(.+)"""),
            "Panic" to Pattern.compile("""panicked\s+at\s+(.+)""")
        )
        
        return LanguagePattern("rust", patterns, errorTypePatterns)
    }
    
    /**
     * Go patterns
     */
    private fun getGoPatterns(): LanguagePattern {
        val patterns = listOf(
            // "main.go:10:5: error message"
            Pattern.compile("""([^:]+):(\d+):(\d+):\s*(.+)"""),
            // "panic: runtime error: message"
            Pattern.compile("""panic:\s*(.+?)(?:\s+goroutine\s+\d+.*?\[running\]:\s+([^:]+):(\d+))?""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "CompileError" to Pattern.compile("""([^:]+):(\d+):(\d+):\s*(.+)"""),
            "Panic" to Pattern.compile("""panic:\s*(.+)"""),
            "RuntimeError" to Pattern.compile("""runtime\s+error:\s*(.+)""")
        )
        
        return LanguagePattern("go", patterns, errorTypePatterns)
    }
    
    /**
     * C/C++ patterns
     */
    private fun getCppPatterns(): LanguagePattern {
        val patterns = listOf(
            // "error: message\n    file.cpp:123:45: note:"
            Pattern.compile("""error:\s*(.+?)\s+([^:]+):(\d+):(\d+):""", Pattern.DOTALL),
            // "warning: message\n    file.cpp:123:45:"
            Pattern.compile("""warning:\s*(.+?)\s+([^:]+):(\d+):(\d+):""", Pattern.DOTALL),
            // GCC/Clang format
            Pattern.compile("""([^:]+):(\d+):(\d+):\s+(error|warning):\s*(.+)""")
        )
        
        val errorTypePatterns = mapOf(
            "CompileError" to Pattern.compile("""error:\s*(.+)"""),
            "Warning" to Pattern.compile("""warning:\s*(.+)"""),
            "LinkError" to Pattern.compile("""undefined\s+reference\s+to\s+(.+)""")
        )
        
        return LanguagePattern("cpp", patterns, errorTypePatterns)
    }
    
    /**
     * Ruby patterns
     */
    private fun getRubyPatterns(): LanguagePattern {
        val patterns = listOf(
            // "/path/to/file.rb:123:in `method'"
            Pattern.compile("""([^:]+):(\d+):in\s+['"]([^'"]+)['"]"""),
            // "NameError: message\n    from file.rb:123:in `method'"
            Pattern.compile("""(\w+Error):\s*(.+?)(?:\s+from\s+([^:]+):(\d+))?""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "NameError" to Pattern.compile("""NameError:\s*(.+)"""),
            "NoMethodError" to Pattern.compile("""NoMethodError:\s*(.+)"""),
            "SyntaxError" to Pattern.compile("""SyntaxError:\s*(.+)"""),
            "ArgumentError" to Pattern.compile("""ArgumentError:\s*(.+)""")
        )
        
        return LanguagePattern("ruby", patterns, errorTypePatterns)
    }
    
    /**
     * PHP patterns
     */
    private fun getPhpPatterns(): LanguagePattern {
        val patterns = listOf(
            // "Parse error: message in /path/to/file.php on line 123"
            Pattern.compile("""Parse\s+error:\s*(.+?)\s+in\s+([^:]+)\s+on\s+line\s+(\d+)"""),
            // "Fatal error: message in /path/to/file.php on line 123"
            Pattern.compile("""Fatal\s+error:\s*(.+?)\s+in\s+([^:]+)\s+on\s+line\s+(\d+)"""),
            // "Warning: message in /path/to/file.php on line 123"
            Pattern.compile("""Warning:\s*(.+?)\s+in\s+([^:]+)\s+on\s+line\s+(\d+)""")
        )
        
        val errorTypePatterns = mapOf(
            "ParseError" to Pattern.compile("""Parse\s+error:\s*(.+)"""),
            "FatalError" to Pattern.compile("""Fatal\s+error:\s*(.+)"""),
            "Warning" to Pattern.compile("""Warning:\s*(.+)"""),
            "Notice" to Pattern.compile("""Notice:\s*(.+)""")
        )
        
        return LanguagePattern("php", patterns, errorTypePatterns)
    }
    
    /**
     * Swift patterns
     */
    private fun getSwiftPatterns(): LanguagePattern {
        val patterns = listOf(
            // "error: message\n    file.swift:123:45: error:"
            Pattern.compile("""error:\s*(.+?)\s+([^:]+):(\d+):(\d+):""", Pattern.DOTALL),
            // "warning: message\n    file.swift:123:45:"
            Pattern.compile("""warning:\s*(.+?)\s+([^:]+):(\d+):(\d+):""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "CompileError" to Pattern.compile("""error:\s*(.+)"""),
            "Warning" to Pattern.compile("""warning:\s*(.+)"""),
            "RuntimeError" to Pattern.compile("""fatal\s+error:\s*(.+)""")
        )
        
        return LanguagePattern("swift", patterns, errorTypePatterns)
    }
    
    /**
     * R patterns
     */
    private fun getRPatterns(): LanguagePattern {
        val patterns = listOf(
            // "Error in function() : message\n    at file.R:123"
            Pattern.compile("""Error\s+in\s+(\w+)\s*\(\)\s*:\s*(.+?)(?:\s+at\s+([^:]+):(\d+))?""", Pattern.DOTALL),
            // "Warning message:\n    In file.R:123"
            Pattern.compile("""Warning\s+message:\s*(.+?)(?:\s+In\s+([^:]+):(\d+))?""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "Error" to Pattern.compile("""Error\s+in\s+.*?:\s*(.+)"""),
            "Warning" to Pattern.compile("""Warning\s+message:\s*(.+)""")
        )
        
        return LanguagePattern("r", patterns, errorTypePatterns)
    }
    
    /**
     * Scala patterns
     */
    private fun getScalaPatterns(): LanguagePattern {
        val patterns = listOf(
            // "error: message\n    at file.scala:123"
            Pattern.compile("""error:\s*(.+?)\s+at\s+([^:]+):(\d+)""", Pattern.DOTALL),
            // "Exception in thread: message\n    at file.scala:123"
            Pattern.compile("""Exception\s+in\s+thread.*?:\s*(.+?)(?:\s+at\s+([^:]+):(\d+))?""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "CompileError" to Pattern.compile("""error:\s*(.+)"""),
            "RuntimeException" to Pattern.compile("""Exception\s+in\s+thread.*?:\s*(.+)""")
        )
        
        return LanguagePattern("scala", patterns, errorTypePatterns)
    }
    
    /**
     * Dart patterns
     */
    private fun getDartPatterns(): LanguagePattern {
        val patterns = listOf(
            // "Error: message\n    at file.dart:123:45"
            Pattern.compile("""Error:\s*(.+?)(?:\s+at\s+([^:]+):(\d+):(\d+))?""", Pattern.DOTALL),
            // "Exception: message\n    at file.dart:123"
            Pattern.compile("""Exception:\s*(.+?)(?:\s+at\s+([^:]+):(\d+))?""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "Error" to Pattern.compile("""Error:\s*(.+)"""),
            "Exception" to Pattern.compile("""Exception:\s*(.+)""")
        )
        
        return LanguagePattern("dart", patterns, errorTypePatterns)
    }
    
    /**
     * Lua patterns
     */
    private fun getLuaPatterns(): LanguagePattern {
        val patterns = listOf(
            // "lua: file.lua:123: message"
            Pattern.compile("""lua:\s+([^:]+):(\d+):\s*(.+)"""),
            // "error: message\n    stack traceback:\n    file.lua:123: in function"
            Pattern.compile("""error:\s*(.+?)(?:\s+stack\s+traceback:.*?([^:]+):(\d+))?""", Pattern.DOTALL)
        )
        
        val errorTypePatterns = mapOf(
            "Error" to Pattern.compile("""error:\s*(.+)"""),
            "SyntaxError" to Pattern.compile("""syntax\s+error\s+near\s+(.+)""")
        )
        
        return LanguagePattern("lua", patterns, errorTypePatterns)
    }
    
    /**
     * Perl patterns
     */
    private fun getPerlPatterns(): LanguagePattern {
        val patterns = listOf(
            // "syntax error at file.pl line 123, near \"text\""
            Pattern.compile("""syntax\s+error\s+at\s+([^:]+)\s+line\s+(\d+)"""),
            // "Can't locate file.pl in @INC"
            Pattern.compile("""Can't\s+locate\s+([^\s]+)\s+in\s+@INC"""),
            // "Died at file.pl line 123"
            Pattern.compile("""Died\s+at\s+([^:]+)\s+line\s+(\d+)""")
        )
        
        val errorTypePatterns = mapOf(
            "SyntaxError" to Pattern.compile("""syntax\s+error\s+at\s+.*?line\s+\d+"""),
            "ModuleNotFound" to Pattern.compile("""Can't\s+locate\s+(.+)""")
        )
        
        return LanguagePattern("perl", patterns, errorTypePatterns)
    }
    
    /**
     * Shell/Bash patterns
     */
    private fun getShellPatterns(): LanguagePattern {
        val patterns = listOf(
            // "file.sh: line 123: command: error message"
            Pattern.compile("""([^:]+):\s+line\s+(\d+):\s+(.+?):\s*(.+)"""),
            // "bash: file.sh: line 123: error message"
            Pattern.compile("""bash:\s+([^:]+):\s+line\s+(\d+):\s*(.+)""")
        )
        
        val errorTypePatterns = mapOf(
            "SyntaxError" to Pattern.compile("""syntax\s+error\s+near\s+(.+)"""),
            "CommandNotFound" to Pattern.compile("""command\s+not\s+found:\s+(.+)""")
        )
        
        return LanguagePattern("shell", patterns, errorTypePatterns)
    }
    
    /**
     * Generic patterns (fallback)
     */
    private fun getGenericPatterns(): LanguagePattern {
        val patterns = listOf(
            // Generic "error in file:line"
            Pattern.compile("""(?:error|Error|ERROR)\s+(?:in|at|on)\s+([^:]+):(\d+)"""),
            // Generic "file:line:column"
            Pattern.compile("""([^\\s]+):(\d+)(?::(\d+))?""")
        )
        
        val errorTypePatterns = mapOf(
            "Error" to Pattern.compile("""error:\s*(.+)""", Pattern.CASE_INSENSITIVE),
            "Warning" to Pattern.compile("""warning:\s*(.+)""", Pattern.CASE_INSENSITIVE)
        )
        
        return LanguagePattern("generic", patterns, errorTypePatterns)
    }
    
    /**
     * Detect language from file extension or content
     */
    fun detectLanguage(filePath: String, content: String? = null): String {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        
        return when (extension) {
            "js", "mjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "jsx" -> "javascript"
            "py" -> "python"
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "rs" -> "rust"
            "go" -> "go"
            "c" -> "c"
            "cpp", "cxx", "cc", "hpp" -> "cpp"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "r" -> "r"
            "scala" -> "scala"
            "dart" -> "dart"
            "lua" -> "lua"
            "pl", "pm" -> "perl"
            "sh", "bash" -> "shell"
            else -> {
                // Try to detect from content if available
                content?.let { detectLanguageFromContent(it) } ?: "generic"
            }
        }
    }
    
    /**
     * Detect language from file content
     */
    private fun detectLanguageFromContent(content: String): String {
        val lowerContent = content.lowercase()
        
        return when {
            lowerContent.contains("function") && lowerContent.contains("require") -> "javascript"
            lowerContent.contains("def ") && lowerContent.contains("import ") -> "python"
            lowerContent.contains("public class") || lowerContent.contains("public static void main") -> "java"
            lowerContent.contains("fn main") || lowerContent.contains("use ") && lowerContent.contains("::") -> "rust"
            lowerContent.contains("package main") && lowerContent.contains("func ") -> "go"
            lowerContent.contains("#include") -> "cpp"
            lowerContent.contains("def ") && lowerContent.contains("end") -> "ruby"
            lowerContent.contains("<?php") -> "php"
            lowerContent.contains("func ") && lowerContent.contains("import ") -> "go"
            else -> "generic"
        }
    }
}
