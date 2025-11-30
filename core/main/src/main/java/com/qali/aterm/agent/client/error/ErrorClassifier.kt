package com.qali.aterm.agent.client.error

/**
 * Classifies error types from command output
 */
object ErrorClassifier {
    
    /**
     * Detect failure keywords in command output with comprehensive patterns
     */
    fun detectFailureKeywords(output: String): Boolean {
        if (output.isEmpty()) return false
        
        val outputLower = output.lowercase()
        
        // Comprehensive failure keywords
        val failureKeywords = listOf(
            // General errors
            "error", "failed", "failure", "fatal", "exception", "crash", "abort",
            "cannot", "can't", "unable", "not found", "missing", "not available",
            "command not found", "permission denied", "access denied", "forbidden",
            "syntax error", "parse error", "type error", "reference error", "name error",
            "module not found", "package not found", "dependency", "import error",
            "exit code", "exit status", "non-zero", "returned 1", "returned 2",
            "failed to", "unexpected", "invalid", "bad", "wrong", "incorrect",
            "undefined", "null pointer", "null reference", "nullpointerexception",
            "timeout", "timed out", "connection refused", "connection reset",
            "eaddrinuse", "eacces", "enoent", "eexist", "eisdir", "enotdir",
            "segmentation fault", "segfault", "bus error", "stack overflow",
            "out of memory", "memory error", "allocation failed",
            "cannot read", "cannot write", "read-only", "readonly",
            "no such file", "no such directory", "file not found", "directory not found",
            "is a directory", "not a directory", "not a file",
            "already exists", "file exists", "directory exists",
            "broken pipe", "broken link", "symbolic link",
            "invalid argument", "invalid option", "invalid syntax",
            "uncaught exception", "unhandled exception", "uncaught error",
            "traceback", "stack trace", "call stack",
            "deprecated", "deprecation warning", "deprecation",
            "warning", "warn", "caution",
            // Exit codes
            "exit code 1", "exit code 2", "exit code 127", "exit code 128",
            "exit status 1", "exit status 2", "exit status 127",
            // Command-specific
            "npm err", "yarn error", "pip error", "python error",
            "node: command not found", "npm: command not found",
            "python: command not found", "pip: command not found",
            "gcc: command not found", "make: command not found",
            "go: command not found", "cargo: command not found",
            "java: command not found", "javac: command not found",
            "mvn: command not found", "gradle: command not found",
            // Code errors
            "syntaxerror", "syntax error", "indentationerror", "indentation error",
            "typeerror", "type error", "referenceerror", "reference error",
            "nameerror", "name error", "attributeerror", "attribute error",
            "valueerror", "value error", "keyerror", "key error",
            "indexerror", "index error", "ioerror", "io error",
            "oserror", "os error", "runtimeerror", "runtime error",
            "zerodivisionerror", "zero division", "division by zero",
            "filenotfounderror", "file not found error",
            "permissionerror", "permission error",
            "import error", "importerror", "modulenotfounderror",
            "cannot import", "failed to import", "import failed",
            "undefined variable", "undefined function", "undefined method",
            "undefined is not a function", "cannot read property",
            "cannot read properties", "cannot access",
            "is not defined", "is not a function", "is not a constructor",
            "expected", "unexpected token", "unexpected end",
            "missing", "missing required", "required parameter",
            "invalid", "invalid character", "invalid token",
            "unterminated", "unclosed", "missing closing",
            // Test failures
            "test failed", "tests failed", "test suite failed",
            "assertion failed", "assertionerror", "assert failed",
            "expected but got", "expected true but got false",
            "test error", "test exception", "test timeout"
        )
        
        return failureKeywords.any { keyword ->
            outputLower.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * Classify error type from output
     */
    fun classifyErrorType(output: String, errorMessage: String, command: String): ErrorType {
        val outputLower = output.lowercase()
        val errorLower = errorMessage.lowercase()
        val commandLower = command.lowercase()
        val combined = "$outputLower $errorLower $commandLower"
        
        // Command not found
        if (combined.contains("command not found") || 
            combined.contains("not found") && (combined.contains("node") || combined.contains("npm") || 
            combined.contains("python") || combined.contains("pip") || combined.contains("go") ||
            combined.contains("cargo") || combined.contains("java") || combined.contains("mvn") ||
            combined.contains("gradle") || combined.contains("gcc") || combined.contains("make"))) {
            return ErrorType.COMMAND_NOT_FOUND
        }
        
        // Code errors (syntax, runtime, import errors)
        if (combined.contains("syntax error") || combined.contains("syntaxerror") ||
            combined.contains("parse error") || combined.contains("parseerror") ||
            combined.contains("type error") || combined.contains("typeerror") ||
            combined.contains("reference error") || combined.contains("referenceerror") ||
            combined.contains("name error") || combined.contains("nameerror") ||
            combined.contains("attribute error") || combined.contains("attributeerror") ||
            combined.contains("import error") || combined.contains("importerror") ||
            combined.contains("module not found") || combined.contains("modulenotfound") ||
            combined.contains("cannot import") || combined.contains("failed to import") ||
            combined.contains("undefined") || combined.contains("is not defined") ||
            combined.contains("traceback") || combined.contains("stack trace") ||
            combined.contains("uncaught exception") || combined.contains("unhandled exception") ||
            combined.contains("runtime error") || combined.contains("runtimeerror") ||
            combined.contains("null pointer") || combined.contains("nullpointer") ||
            combined.contains("cannot read property") || combined.contains("cannot access")) {
            return ErrorType.CODE_ERROR
        }
        
        // Dependency missing
        if (combined.contains("module not found") || combined.contains("package not found") ||
            combined.contains("dependency") || combined.contains("missing dependency") ||
            combined.contains("cannot find module") || combined.contains("cannot resolve") ||
            combined.contains("npm err") || combined.contains("yarn error") ||
            combined.contains("pip error") || combined.contains("no module named") ||
            combined.contains("package.json") && combined.contains("not found")) {
            return ErrorType.DEPENDENCY_MISSING
        }
        
        // Permission errors
        if (combined.contains("permission denied") || combined.contains("permissionerror") ||
            combined.contains("access denied") || combined.contains("forbidden") ||
            combined.contains("eacces") || combined.contains("read-only") ||
            combined.contains("cannot write") || combined.contains("cannot read")) {
            return ErrorType.PERMISSION_ERROR
        }
        
        // Network errors
        if (combined.contains("connection refused") || combined.contains("connection reset") ||
            combined.contains("timeout") || combined.contains("timed out") ||
            combined.contains("network error") || combined.contains("dns") ||
            combined.contains("econnrefused") || combined.contains("econnreset")) {
            return ErrorType.NETWORK_ERROR
        }
        
        // Configuration errors (including package.json JSON parse errors)
        if (combined.contains("invalid") || combined.contains("wrong") ||
            combined.contains("incorrect") || combined.contains("bad") ||
            combined.contains("configuration") || combined.contains("config error") ||
            combined.contains("ejsonparse") || combined.contains("json parse") ||
            (combined.contains("npm") && combined.contains("error") && combined.contains("code")) ||
            (combined.contains("package.json") && (combined.contains("parse") || combined.contains("json") || combined.contains("syntax")))) {
            return ErrorType.CONFIGURATION_ERROR
        }
        
        return ErrorType.UNKNOWN
    }
}
