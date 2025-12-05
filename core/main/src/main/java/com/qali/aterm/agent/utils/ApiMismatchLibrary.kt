package com.qali.aterm.agent.utils

/**
 * Comprehensive API mismatch detection library
 * Detects 20+ common API mismatches across different libraries and frameworks
 */
object ApiMismatchLibrary {
    
    /**
     * API mismatch pattern
     */
    data class MismatchPattern(
        val errorPattern: String,
        val errorType: String,
        val suggestedFix: String,
        val affectedFiles: List<String>,
        val libraries: List<String> // Which libraries this applies to
    )
    
    /**
     * All API mismatch patterns
     */
    val mismatchPatterns = listOf(
        // Database API Mismatches
        MismatchPattern(
            errorPattern = "execute.*not a function",
            errorType = "SQLite API Mismatch",
            suggestedFix = "SQLite uses db.all(), db.get(), db.run() instead of db.execute(). Use: db.all('SELECT * FROM table', [], callback) or db.get() for single row.",
            affectedFiles = listOf("database.js", "db.js", "routes/", "controllers/"),
            libraries = listOf("sqlite3")
        ),
        MismatchPattern(
            errorPattern = "query.*not a function",
            errorType = "Database API Mismatch",
            suggestedFix = "Check database library. SQLite uses db.all/get/run, MySQL uses connection.query(), PostgreSQL uses client.query().",
            affectedFiles = listOf("database.js", "db.js"),
            libraries = listOf("sqlite3", "mysql", "pg")
        ),
        MismatchPattern(
            errorPattern = "connection\\.execute",
            errorType = "MySQL API Mismatch",
            suggestedFix = "MySQL2 uses connection.query() not connection.execute(). Use: connection.query('SELECT * FROM table', callback).",
            affectedFiles = listOf("database.js", "db.js"),
            libraries = listOf("mysql2")
        ),
        
        // Promise vs Callback Mismatches
        MismatchPattern(
            errorPattern = "cannot read property.*then",
            errorType = "Promise/Callback Mismatch",
            suggestedFix = "Function returns callback, not Promise. Use callback pattern or wrap with util.promisify().",
            affectedFiles = emptyList(),
            libraries = listOf("nodejs")
        ),
        MismatchPattern(
            errorPattern = "await.*is not a function",
            errorType = "Async/Await Mismatch",
            suggestedFix = "Function is not async or doesn't return Promise. Add 'async' keyword or return Promise.",
            affectedFiles = emptyList(),
            libraries = listOf("nodejs", "javascript")
        ),
        
        // Express.js vs Koa.js
        MismatchPattern(
            errorPattern = "app\\.use.*ctx",
            errorType = "Express/Koa API Mismatch",
            suggestedFix = "Express uses (req, res, next), Koa uses (ctx, next). Check which framework you're using.",
            affectedFiles = listOf("app.js", "server.js", "routes/"),
            libraries = listOf("express", "koa")
        ),
        MismatchPattern(
            errorPattern = "ctx\\.body.*not defined",
            errorType = "Koa Context Mismatch",
            suggestedFix = "In Koa, use ctx.body not ctx.response.body. In Express, use res.send() or res.json().",
            affectedFiles = listOf("routes/", "controllers/"),
            libraries = listOf("koa", "express")
        ),
        
        // React Hooks vs Class Components
        MismatchPattern(
            errorPattern = "hooks.*can only be called",
            errorType = "React Hooks Mismatch",
            suggestedFix = "Hooks can only be called in functional components, not class components. Convert to functional component or use class component lifecycle methods.",
            affectedFiles = listOf("components/", "*.jsx", "*.tsx"),
            libraries = listOf("react")
        ),
        MismatchPattern(
            errorPattern = "setState.*is not a function",
            errorType = "React Component Type Mismatch",
            suggestedFix = "setState() only works in class components. In functional components, use useState() hook.",
            affectedFiles = listOf("components/", "*.jsx", "*.tsx"),
            libraries = listOf("react")
        ),
        
        // MongoDB Driver Mismatches
        MismatchPattern(
            errorPattern = "collection\\.find.*toArray.*not a function",
            errorType = "MongoDB Driver Version Mismatch",
            suggestedFix = "Older MongoDB driver uses callbacks, newer uses Promises. Use: collection.find({}).toArray() returns Promise, or use callback in older versions.",
            affectedFiles = listOf("database.js", "models/"),
            libraries = listOf("mongodb")
        ),
        MismatchPattern(
            errorPattern = "db\\.collection.*is not a function",
            errorType = "MongoDB API Mismatch",
            suggestedFix = "Check MongoDB driver version. Use: db.collection('name') or client.db('name').collection('name').",
            affectedFiles = listOf("database.js"),
            libraries = listOf("mongodb")
        ),
        
        // HTTP Client Mismatches
        MismatchPattern(
            errorPattern = "axios\\.get.*then.*not a function",
            errorType = "Axios/Fetch API Mismatch",
            suggestedFix = "Axios returns Promise, use .then() or await. If using fetch(), it returns Response object, use response.json() first.",
            affectedFiles = listOf("api/", "services/"),
            libraries = listOf("axios", "fetch")
        ),
        MismatchPattern(
            errorPattern = "fetch.*then.*not a function",
            errorType = "Fetch API Mismatch",
            suggestedFix = "fetch() returns Response object, not data. Use: fetch(url).then(res => res.json()).then(data => ...).",
            affectedFiles = listOf("api/", "services/"),
            libraries = listOf("fetch")
        ),
        
        // ORM Mismatches
        MismatchPattern(
            errorPattern = "sequelize\\.define.*not a function",
            errorType = "Sequelize API Mismatch",
            suggestedFix = "Check Sequelize version. Older: Sequelize.define(), newer: sequelize.define(). Import correctly: const { Sequelize } = require('sequelize').",
            affectedFiles = listOf("models/"),
            libraries = listOf("sequelize")
        ),
        MismatchPattern(
            errorPattern = "model\\.findOne.*then.*not a function",
            errorType = "TypeORM/Sequelize Mismatch",
            suggestedFix = "TypeORM uses repository.findOne(), Sequelize uses Model.findOne(). Check which ORM you're using.",
            affectedFiles = listOf("models/", "repositories/"),
            libraries = listOf("typeorm", "sequelize")
        ),
        MismatchPattern(
            errorPattern = "prisma\\.(findMany|create).*not a function",
            errorType = "Prisma Client Mismatch",
            suggestedFix = "Prisma uses client.model.findMany(), not prisma.findMany(). Use: prisma.user.findMany() after generating client.",
            affectedFiles = listOf("models/", "services/"),
            libraries = listOf("prisma")
        ),
        
        // Testing Framework Mismatches
        MismatchPattern(
            errorPattern = "describe.*it.*not defined",
            errorType = "Jest/Mocha API Mismatch",
            suggestedFix = "Jest and Mocha both use describe/it, but setup differs. Check if jest is configured or use mocha with proper setup.",
            affectedFiles = listOf("test/", "*.test.js", "*.spec.js"),
            libraries = listOf("jest", "mocha")
        ),
        MismatchPattern(
            errorPattern = "expect.*toBe.*not a function",
            errorType = "Jest/Chai Assertion Mismatch",
            suggestedFix = "Jest uses expect().toBe(), Chai uses expect().to.equal(). Check which testing library you're using.",
            affectedFiles = listOf("test/", "*.test.js"),
            libraries = listOf("jest", "chai")
        ),
        
        // Node.js Version Mismatches
        MismatchPattern(
            errorPattern = "require.*is not defined",
            errorType = "ES Modules vs CommonJS Mismatch",
            suggestedFix = "In ES modules, use 'import' not 'require'. Add 'type: module' to package.json or use .mjs extension, or use import syntax.",
            affectedFiles = listOf("*.js", "*.mjs"),
            libraries = listOf("nodejs")
        ),
        MismatchPattern(
            errorPattern = "import.*is not defined",
            errorType = "CommonJS vs ES Modules Mismatch",
            suggestedFix = "In CommonJS, use 'require' not 'import'. Remove 'type: module' from package.json or use .cjs extension, or use require() syntax.",
            affectedFiles = listOf("*.js"),
            libraries = listOf("nodejs")
        ),
        
        // Python 2 vs 3 Mismatches
        MismatchPattern(
            errorPattern = "print.*syntax error",
            errorType = "Python 2/3 Print Mismatch",
            suggestedFix = "Python 3 requires print() with parentheses. Change 'print x' to 'print(x)'. Ensure using Python 3.",
            affectedFiles = listOf("*.py"),
            libraries = listOf("python")
        ),
        MismatchPattern(
            errorPattern = "xrange.*not defined",
            errorType = "Python 2/3 Range Mismatch",
            suggestedFix = "Python 3 uses range() not xrange(). Replace xrange() with range().",
            affectedFiles = listOf("*.py"),
            libraries = listOf("python")
        ),
        
        // File System API Mismatches
        MismatchPattern(
            errorPattern = "fs\\.readFile.*then.*not a function",
            errorType = "Node.js fs API Mismatch",
            suggestedFix = "fs.readFile() uses callback by default. Use fs.promises.readFile() for Promise, or fs.readFileSync() for sync, or use callback.",
            affectedFiles = listOf("*.js"),
            libraries = listOf("nodejs", "fs")
        ),
        
        // Date/Time API Mismatches
        MismatchPattern(
            errorPattern = "moment.*is not a function",
            errorType = "Moment.js API Mismatch",
            suggestedFix = "Moment.js v2+ uses moment() not new moment(). Use: moment() or require('moment')(). Consider migrating to date-fns or dayjs.",
            affectedFiles = emptyList(),
            libraries = listOf("moment")
        )
    )
    
    /**
     * Detect API mismatch from error message
     * 
     * @param errorMessage The error message
     * @param filePath Optional file path for context
     * @return Detected API mismatch or null
     */
    fun detectApiMismatch(errorMessage: String, filePath: String? = null): ErrorDetectionUtils.ApiMismatch? {
        val lowerError = errorMessage.lowercase()
        
        // Try each pattern
        for (pattern in mismatchPatterns) {
            val regex = java.util.regex.Pattern.compile(pattern.errorPattern, java.util.regex.Pattern.CASE_INSENSITIVE)
            if (regex.matcher(lowerError).find()) {
                // Check if file path matches affected files pattern
                val matchesFile = if (filePath != null && pattern.affectedFiles.isNotEmpty()) {
                    pattern.affectedFiles.any { affected ->
                        filePath.contains(affected.removeSuffix("/")) || 
                        affected.endsWith("*") && filePath.endsWith(affected.removeSuffix("*"))
                    }
                } else {
                    true // If no file path, assume match
                }
                
                if (matchesFile) {
                    return ErrorDetectionUtils.ApiMismatch(
                        errorType = pattern.errorType,
                        suggestedFix = pattern.suggestedFix,
                        affectedFiles = pattern.affectedFiles
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Get all mismatches for a specific library
     */
    fun getMismatchesForLibrary(library: String): List<MismatchPattern> {
        return mismatchPatterns.filter { it.libraries.contains(library.lowercase()) }
    }
    
    /**
     * Get migration suggestions for API mismatches
     */
    fun getMigrationSuggestion(errorType: String): String? {
        return when (errorType) {
            "SQLite API Mismatch" -> "Consider using better-sqlite3 for synchronous API or sqlite3 with proper callback/Promise handling"
            "Promise/Callback Mismatch" -> "Use util.promisify() to convert callbacks to Promises, or use async/await with Promise-based APIs"
            "Express/Koa API Mismatch" -> "Choose one framework: Express (req/res) or Koa (ctx). Don't mix APIs"
            "React Hooks Mismatch" -> "Convert class components to functional components to use hooks, or use class component lifecycle methods"
            "ES Modules vs CommonJS Mismatch" -> "Standardize on one module system. Use 'type: module' in package.json for ES modules, or use .cjs/.mjs extensions"
            "Python 2/3 Mismatch" -> "Ensure using Python 3. Update code to Python 3 syntax (print(), range(), etc.)"
            else -> null
        }
    }
}
