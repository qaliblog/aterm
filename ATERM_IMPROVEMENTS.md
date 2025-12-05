# aTerm Agent Improvements

This document describes the improvements made to the aTerm agent for better project scanning, error detection, and file management.

## Overview

The improvements focus on:
1. **Smart file scanning** - Ignore heavy directories and irrelevant files
2. **Error detection** - Parse stack traces and auto-locate relevant files
3. **File prioritization** - Read important files first (routes, controllers, db, etc.)
4. **Patch diffs** - Show code changes in Git-style patch format
5. **Auto-configuration** - Auto-create `.atermignore` for new projects

## Components

### 1. AtermIgnoreManager (`agent/utils/AtermIgnoreManager.kt`)

Manages `.atermignore` files to exclude files and directories from agent scanning.

**Features:**
- Default ignore patterns (node_modules, .git, build, dist, etc.)
- Supports `.atermignore` file in workspace root
- Pattern matching (wildcards, directory patterns)
- Auto-creates default `.atermignore` for new projects

**Default Ignore Patterns:**
```
node_modules/
.npm/
.cache/
build/
dist/
out/
.git/
.vscode/
.idea/
*.log
```

**Usage:**
```kotlin
// Check if file should be ignored
if (AtermIgnoreManager.shouldIgnoreFile(file, workspaceRoot)) {
    // Skip this file
}

// Get priority files for a project
val priorityFiles = AtermIgnoreManager.getPriorityFiles(workspaceRoot, "nodejs")
```

### 2. ErrorDetectionUtils (`agent/utils/ErrorDetectionUtils.kt`)

Parses error messages and stack traces to locate relevant files and lines.

**Features:**
- Parses JavaScript, Python, Java/Kotlin stack traces
- Extracts file paths and line numbers
- Detects API mismatches (e.g., SQLite vs MySQL)
- Finds related files (routes, controllers, models, database.js)

**Usage:**
```kotlin
// Parse error locations from error message
val locations = ErrorDetectionUtils.parseErrorLocations(errorMessage, workspaceRoot)
// Returns: List<ErrorLocation(filePath, lineNumber, columnNumber, functionName)>

// Detect API mismatches
val mismatch = ErrorDetectionUtils.detectApiMismatch("db.execute is not a function")
// Returns: ApiMismatch with suggested fix

// Get related files for debugging
val relatedFiles = ErrorDetectionUtils.getRelatedFilesForError(location, workspaceRoot)
```

### 3. PatchDiffUtils (`agent/utils/PatchDiffUtils.kt`)

Generates Git-style unified diff patches for code changes.

**Features:**
- Unified diff format (compatible with Git)
- Context lines around changes
- Handles new files, modified files, and deletions

**Usage:**
```kotlin
// Generate patch from old and new content
val patch = PatchDiffUtils.generatePatch(
    filePath = "routes/main.js",
    oldContent = oldCode,
    newContent = newCode,
    contextLines = 3
)

// Format for display
val formatted = PatchDiffUtils.formatPatchForDisplay(patch)
```

**Example Output:**
```diff
--- a/routes/main.js
+++ b/routes/main.js
@@ -1,5 +1,5 @@
- const [rows] = await db.execute(...)
+ db.all("SELECT * FROM posts", [], callback)
```

## Integration Points

### File Scanning

**Updated:** `PpeExecutionEngine.getFileStructureRespectingGitignore()`
- Now uses `AtermIgnoreManager` instead of custom `.gitignore` parsing
- Automatically excludes files matching `.atermignore` patterns

### File Reading

**Updated:** `ReadFileTool.execute()`
- Checks `.atermignore` before reading files
- Returns error if file is ignored

**Updated:** `PpeExecutionEngine.readFilesFromPlan()`
- Skips ignored files when reading from plan
- Logs warnings for skipped files

### Error Detection

**Updated:** `PpeExecutionEngine.determineFilesToRead()`
- Parses error messages for file locations
- Detects API mismatches
- Prioritizes files mentioned in errors
- Includes error context in file selection prompt

### File Writing

**Updated:** `WriteFileTool.execute()`
- Reads old content before writing (for patch generation)
- Generates patch diff when file is modified
- Includes patch in tool result message

### Project Detection

**Updated:** `ProjectStartupDetector.detectNewProject()`
- Auto-creates `.atermignore` for new projects
- Uses `AtermIgnoreManager` for file counting
- Excludes ignored files from project file count

**Updated:** `PpeExecutionEngine.executeTwoPhaseProjectStartup()`
- Auto-creates `.atermignore` at project startup

## Configuration

### .atermignore File

The `.atermignore` file is automatically created in the workspace root for new projects. Users can customize it:

```
# Custom ignore patterns
custom-build/
test-coverage/
*.test.js
```

### Priority Files

Priority files are automatically detected based on project type:

**Node.js:**
- package.json
- server.js, app.js, index.js
- routes/, controllers/, models/
- database.js, db.js, config.js

**Python:**
- requirements.txt
- app.py, main.py
- config.py, settings.py

## Error Detection Examples

### Stack Trace Parsing

**Input:**
```
Error: db.execute is not a function
    at /path/to/routes/main.js:45:12
    at Object.getPosts (/path/to/controllers/posts.js:23:5)
```

**Output:**
```kotlin
ErrorLocation(filePath = "routes/main.js", lineNumber = 45, columnNumber = 12)
ErrorLocation(filePath = "controllers/posts.js", lineNumber = 23, functionName = "getPosts")
```

### API Mismatch Detection

**Input:**
```
TypeError: db.execute is not a function
```

**Output:**
```kotlin
ApiMismatch(
    errorType = "SQLite API Mismatch",
    suggestedFix = "SQLite uses db.all(), db.get(), db.run() instead of db.execute()...",
    affectedFiles = ["database.js", "db.js", "routes/", "controllers/"]
)
```

## Patch Diff Examples

When `write_file` modifies an existing file, the tool result includes a patch:

```
File written successfully: routes/main.js

--- Patch Diff ---
```diff
--- a/routes/main.js
+++ b/routes/main.js
@@ -10,7 +10,7 @@
 router.get('/posts', async (req, res) => {
-  const [rows] = await db.execute('SELECT * FROM posts');
+  db.all('SELECT * FROM posts', [], (err, rows) => {
+    if (err) return res.status(500).json({ error: err.message });
     res.json(rows);
+  });
 });
```
```

## Performance Improvements

1. **Faster file scanning** - Ignores `node_modules/` and other heavy directories
2. **Smarter file selection** - Only reads relevant files based on error context
3. **Priority-based reading** - Reads important files (routes, controllers, db) first
4. **Reduced API calls** - Fewer files to analyze means faster responses

## Testing

To test the improvements:

1. **Create a new project:**
   ```
   "make me a nodejs app with sqlite3"
   ```
   - Should auto-create `.atermignore`
   - Should ignore `node_modules/` if it exists

2. **Test error detection:**
   ```
   "I'm getting error: db.execute is not a function at routes/main.js:45"
   ```
   - Should parse error location
   - Should detect API mismatch
   - Should prioritize reading `routes/main.js` and `database.js`

3. **Test file writing:**
   - Modify an existing file
   - Check tool result for patch diff

## Future Enhancements

- [ ] Support for `.atermignore` in subdirectories
- [ ] More sophisticated diff algorithms (Myers, patience)
- [ ] Error pattern learning from user corrections
- [ ] Integration with language servers for better error detection
- [ ] Support for more project types (Rust, Go, etc.)
