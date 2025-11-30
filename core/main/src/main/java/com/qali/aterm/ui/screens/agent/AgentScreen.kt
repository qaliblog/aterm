package com.qali.aterm.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.api.ApiProviderManager
import com.qali.aterm.api.ApiProviderManager.KeysExhaustedException
import com.qali.aterm.api.ApiProviderType
import com.qali.aterm.agent.AgentService
import com.qali.aterm.agent.HistoryPersistenceService
import com.qali.aterm.agent.SessionMetadata
import com.qali.aterm.agent.client.AgentClient
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.client.OllamaClient
import com.qali.aterm.agent.ppe.CliBasedAgentClient
import com.qali.aterm.agent.tools.ToolResult
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.screens.terminal.changeSession
import com.rk.settings.Settings
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

data class FileDiff(
    val filePath: String,
    val oldContent: String,
    val newContent: String,
    val isNewFile: Boolean = false
)

enum class DiffLineType {
    ADDED,      // Green with +
    REMOVED,    // Red with -
    UNCHANGED   // Normal text
}

data class DiffLine(
    val content: String,
    val type: DiffLineType,
    val lineNumber: Int
)

data class AgentMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val fileDiff: FileDiff? = null // Optional file diff for code changes
)

fun formatTimestamp(timestamp: Long): String {
    val time = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(time)
}

@Composable
fun DirectoryPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onDirectorySelected: (File) -> Unit
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var directories by remember { mutableStateOf<List<File>>(emptyList()) }
    // Start at a better OS-relative directory: prefer home directory, then rootfs, then alpine
    val initialDir = remember {
        val homeDir = com.rk.libcommons.getRootfsHomeDir()
        if (homeDir.exists() && homeDir.isDirectory) {
            homeDir.absolutePath
        } else {
            val rootfsDir = com.rk.libcommons.getRootfsDir()
            if (rootfsDir.exists() && rootfsDir.isDirectory) {
                rootfsDir.absolutePath
            } else {
                com.rk.libcommons.alpineDir().absolutePath
            }
        }
    }
    
    LaunchedEffect(currentPath) {
        val dir = File(currentPath)
        directories = if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Workspace Directory") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Path bar with navigation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPath != initialDir) {
                        IconButton(onClick = {
                            File(currentPath).parentFile?.let {
                                currentPath = it.absolutePath
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Up")
                        }
                    }
                    Text(
                        text = currentPath,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Current directory option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = {
                        val dir = File(currentPath)
                        if (dir.exists() && dir.isDirectory) {
                            onDirectorySelected(dir)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use this directory",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Select",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Subdirectories list
                Text(
                    text = "Subdirectories:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(directories.sortedBy { it.name.lowercase() }) { dir ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            onClick = {
                                currentPath = dir.absolutePath
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null
                                )
                                Text(
                                    text = dir.name,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Enter",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Parse file diff from tool result
 * Extracts file path and content from edit and write_file tool results
 */
fun parseFileDiffFromToolResult(toolName: String, toolResult: ToolResult, toolArgs: Map<String, Any>? = null): FileDiff? {
    if (toolName != "edit" && toolName != "write_file") {
        return null
    }
    
    return try {
        // Extract file path from tool args
        val filePath = toolArgs?.get("file_path") as? String
            ?: run {
                // Try to extract from llmContent or returnDisplay
                val content = toolResult.llmContent
                val filePathPattern = Regex("""(?:file|File|path|Path)[:\s]+([^\s,]+)""")
                filePathPattern.find(content)?.groupValues?.get(1)
            } ?: return null
        
        // For edit, extract old_string and new_string from args
        if (toolName == "edit" && toolArgs != null) {
            val oldString = toolArgs["old_string"] as? String ?: ""
            val newString = toolArgs["new_string"] as? String ?: ""
            val isNewFile = oldString.isEmpty()
            
            FileDiff(
                filePath = filePath,
                oldContent = oldString,
                newContent = newString,
                isNewFile = isNewFile
            )
        } 
        // For write_file, we only have new content (creates new file or overwrites)
        else if (toolName == "write_file" && toolArgs != null) {
            val newContent = toolArgs["content"] as? String ?: ""
            // Check if file exists to determine if it's new
            // Use a coroutine-safe check
            val workspaceRoot = com.rk.libcommons.alpineDir()
            val file = java.io.File(workspaceRoot, filePath)
            val isNewFile = !file.exists()
            val oldContent = if (isNewFile) {
                ""
            } else {
                try {
                    file.takeIf { it.exists() }?.readText() ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
            
            FileDiff(
                filePath = filePath,
                oldContent = oldContent,
                newContent = newContent,
                isNewFile = isNewFile
            )
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("AgentScreen", "Failed to parse file diff", e)
        null
    }
}

/**
 * Calculate line-by-line diff between old and new content
 * Uses a simple line-by-line comparison algorithm
 */
fun calculateLineDiff(oldContent: String, newContent: String): List<DiffLine> {
    val oldLines = if (oldContent.isEmpty()) emptyList() else oldContent.lines()
    val newLines = if (newContent.isEmpty()) emptyList() else newContent.lines()
    val diffLines = mutableListOf<DiffLine>()
    
    // If both are empty, return empty
    if (oldLines.isEmpty() && newLines.isEmpty()) {
        return emptyList()
    }
    
    // If old is empty, all new lines are additions
    if (oldLines.isEmpty()) {
        newLines.forEachIndexed { index, line ->
            diffLines.add(DiffLine(line, DiffLineType.ADDED, index + 1))
        }
        return diffLines
    }
    
    // If new is empty, all old lines are removals
    if (newLines.isEmpty()) {
        oldLines.forEachIndexed { index, line ->
            diffLines.add(DiffLine(line, DiffLineType.REMOVED, index + 1))
        }
        return diffLines
    }
    
    // Use a simple longest common subsequence approach
    var oldIndex = 0
    var newIndex = 0
    var newLineNumber = 1
    
    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        when {
            oldIndex >= oldLines.size -> {
                // Only new lines remain - all additions
                diffLines.add(DiffLine(newLines[newIndex], DiffLineType.ADDED, newLineNumber))
                newIndex++
                newLineNumber++
            }
            newIndex >= newLines.size -> {
                // Only old lines remain - all removals
                diffLines.add(DiffLine(oldLines[oldIndex], DiffLineType.REMOVED, oldIndex + 1))
                oldIndex++
            }
            oldLines[oldIndex] == newLines[newIndex] -> {
                // Lines match - unchanged
                diffLines.add(DiffLine(oldLines[oldIndex], DiffLineType.UNCHANGED, oldIndex + 1))
                oldIndex++
                newIndex++
                newLineNumber++
            }
            else -> {
                // Lines differ - check if we can find a match ahead
                var foundMatch = false
                var lookAhead = 1
                while (oldIndex + lookAhead < oldLines.size && !foundMatch) {
                    if (oldLines[oldIndex + lookAhead] == newLines[newIndex]) {
                        // Found match ahead - mark intermediate old lines as removed
                        for (i in oldIndex until oldIndex + lookAhead) {
                            diffLines.add(DiffLine(oldLines[i], DiffLineType.REMOVED, i + 1))
                        }
                        oldIndex += lookAhead
                        foundMatch = true
                    } else {
                        lookAhead++
                    }
                }
                
                if (!foundMatch) {
                    // No match found - treat as remove + add
                    diffLines.add(DiffLine(oldLines[oldIndex], DiffLineType.REMOVED, oldIndex + 1))
                    diffLines.add(DiffLine(newLines[newIndex], DiffLineType.ADDED, newLineNumber))
                    oldIndex++
                    newIndex++
                    newLineNumber++
                }
            }
        }
    }
    
    return diffLines
}

@Composable
fun WelcomeMessage() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Welcome to Gemini AI Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This agent will be integrated with gemini-cli to provide AI-powered assistance for your terminal workflow.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Features coming soon:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Code generation and assistance\n• Terminal command suggestions\n• File operations guidance\n• Project analysis and recommendations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Summary card showing all new and changed files
 */
@Composable
fun FileChangesSummaryCard(
    messages: List<AgentMessage>,
    modifier: Modifier = Modifier
) {
    val fileDiffs = remember(messages) {
        messages.mapNotNull { it.fileDiff }
    }
    
    val newFiles = remember(fileDiffs) {
        fileDiffs.filter { it.isNewFile }.map { it.filePath }.distinct()
    }
    
    val changedFiles = remember(fileDiffs) {
        fileDiffs.filter { !it.isNewFile }.map { it.filePath }.distinct()
    }
    
    if (newFiles.isEmpty() && changedFiles.isEmpty()) {
        return
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "File Changes Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Divider()
            
            // New files section
            if (newFiles.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "New Files (${newFiles.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    newFiles.forEach { filePath ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFF4CAF50).copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = filePath,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            
            // Changed files section
            if (changedFiles.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (newFiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Changed Files (${changedFiles.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    changedFiles.forEach { filePath ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = filePath,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Beautiful code diff card component similar to Cursor AI
 */
@Composable
fun CodeDiffCard(
    fileDiff: FileDiff,
    modifier: Modifier = Modifier
) {
    val diffLines = remember(fileDiff.oldContent, fileDiff.newContent) {
        val allDiffLines = calculateLineDiff(fileDiff.oldContent, fileDiff.newContent)
        // Show context lines (unchanged) around changes, but limit total
        val changesOnly = allDiffLines.filter { it.type != DiffLineType.UNCHANGED }
        if (changesOnly.size > 200) {
            // If too many changes, show only first 200
            changesOnly.take(200)
        } else {
            // Show changes with some context
            val result = mutableListOf<DiffLine>()
            var lastWasChange = false
            for (i in allDiffLines.indices) {
                val line = allDiffLines[i]
                if (line.type != DiffLineType.UNCHANGED) {
                    // Add context before change (up to 2 lines)
                    if (!lastWasChange && i > 0) {
                        val contextStart = maxOf(0, i - 2)
                        for (j in contextStart until i) {
                            if (allDiffLines[j].type == DiffLineType.UNCHANGED && 
                                result.none { it.lineNumber == allDiffLines[j].lineNumber }) {
                                result.add(allDiffLines[j])
                            }
                        }
                    }
                    result.add(line)
                    lastWasChange = true
                } else if (lastWasChange && result.size < 300) {
                    // Add context after change (up to 2 lines)
                    result.add(line)
                    lastWasChange = false
                }
            }
            result.take(300) // Limit total
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp)
        ) {
            // File header
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (fileDiff.isNewFile) Icons.Outlined.Add else Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = if (fileDiff.isNewFile) 
                            Color(0xFF4CAF50) 
                        else 
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = fileDiff.filePath,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (fileDiff.isNewFile) {
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "NEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            // Diff content
            if (diffLines.isEmpty()) {
                Text(
                    text = "No changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    diffLines.forEach { diffLine ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when (diffLine.type) {
                                        DiffLineType.ADDED -> Color(0xFF1E4620).copy(alpha = 0.15f)
                                        DiffLineType.REMOVED -> Color(0xFF5C1F1F).copy(alpha = 0.15f)
                                        DiffLineType.UNCHANGED -> Color.Transparent
                                    }
                                ),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Line number and indicator column
                            Column(
                                modifier = Modifier
                                    .width(70.dp)
                                    .background(
                                        when (diffLine.type) {
                                            DiffLineType.ADDED -> Color(0xFF1E4620).copy(alpha = 0.3f)
                                            DiffLineType.REMOVED -> Color(0xFF5C1F1F).copy(alpha = 0.3f)
                                            DiffLineType.UNCHANGED -> Color.Transparent
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = when (diffLine.type) {
                                        DiffLineType.ADDED -> "+"
                                        DiffLineType.REMOVED -> "-"
                                        DiffLineType.UNCHANGED -> " "
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when (diffLine.type) {
                                        DiffLineType.ADDED -> Color(0xFF4CAF50)
                                        DiffLineType.REMOVED -> Color(0xFFF44336)
                                        DiffLineType.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                    fontSize = 14.sp
                                )
                                if (diffLine.type != DiffLineType.UNCHANGED) {
                                    Text(
                                        text = diffLine.lineNumber.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            
                            // Code content
                            SelectionContainer {
                                Text(
                                    text = diffLine.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (diffLine.type) {
                                        DiffLineType.ADDED -> Color(0xFF81C784)
                                        DiffLineType.REMOVED -> Color(0xFFE57373)
                                        DiffLineType.UNCHANGED -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 2.dp, horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: AgentMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isUser) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Card(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isUser) 
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.isUser) 
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 10.sp
                )
            }
        }
        
        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun KeysExhaustedDialog(
    onDismiss: () -> Unit,
    onWaitAndRetry: (Int) -> Unit
) {
    var waitSeconds by remember { mutableStateOf(60) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API Keys Exhausted") },
        text = {
            Column {
                Text("All API keys are rate limited. You can:")
                Spacer(modifier = Modifier.height(8.dp))
                Text("1. Wait and retry after a delay")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Wait for:")
                    OutlinedTextField(
                        value = waitSeconds.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { secs -> 
                                if (secs >= 0 && secs <= 3600) waitSeconds = secs 
                            }
                        },
                        modifier = Modifier.width(80.dp),
                        label = { Text("seconds") }
                    )
                    Text("seconds")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onWaitAndRetry(waitSeconds) }) {
                Text("Wait and Retry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Read recent logcat entries filtered by relevant tags
 */
suspend fun readLogcatLogs(maxLines: Int = 200): String = withContext(Dispatchers.IO) {
    try {
        // Read more lines than needed, then filter
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat",
                "-d", // dump and exit
                "-t", (maxLines * 3).toString(), // read more lines to filter from
                "-v", "time" // time format
            )
        )
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val logs = StringBuilder()
        var line: String?
        var lineCount = 0
        
        // Relevant tags to filter for (including agent and shell execution)
        val relevantTags = listOf(
            "AgentClient", "OllamaClient", "AgentScreen", "ApiProviderManager",
            "AgentService", "OkHttp", "Okio", "AndroidRuntime", "ApiProvider",
            "OkHttpClient", "OkHttp3", "Okio", "System.err", "ShellTool",
            "sendMessage", "sendMessageInternal", "makeApiCall",
            "ToolInvocation", "executeToolSync", "LanguageLinterTool"
        )
        
        while (reader.readLine().also { line = it } != null && lineCount < maxLines) {
            line?.let { logLine ->
                // Check if line contains relevant tags or is an error/warning
                val containsRelevantTag = relevantTags.any { tag ->
                    logLine.contains(tag, ignoreCase = true)
                }
                
                // Check for error/warning indicators
                val isErrorOrWarning = logLine.matches(Regex(".*\\s+[EW]\\s+.*")) ||
                        logLine.contains("Error", ignoreCase = true) ||
                        logLine.contains("Exception", ignoreCase = true) ||
                        logLine.contains("IOException", ignoreCase = true) ||
                        logLine.contains("Network", ignoreCase = true) ||
                        logLine.contains("HTTP", ignoreCase = true) ||
                        logLine.contains("Failed", ignoreCase = true) ||
                        logLine.contains("Timeout", ignoreCase = true) ||
                        logLine.contains("generateContent", ignoreCase = true) ||
                        logLine.contains("generativelanguage", ignoreCase = true) ||
                        logLine.contains("API", ignoreCase = true) ||
                        logLine.contains("api", ignoreCase = true)
                
                if (containsRelevantTag || isErrorOrWarning) {
                    logs.appendLine(logLine)
                    lineCount++
                }
            }
        }
        
        process.waitFor()
        reader.close()
        
        // Also read error stream in case of issues
        val errorReader = BufferedReader(InputStreamReader(process.errorStream))
        val errorOutput = StringBuilder()
        while (errorReader.readLine().also { line = it } != null) {
            errorOutput.appendLine(line)
        }
        errorReader.close()
        
        if (logs.isEmpty()) {
            if (errorOutput.isNotEmpty()) {
                "No relevant logcat entries found.\nLogcat error: ${errorOutput.toString().take(200)}"
            } else {
                "No relevant logcat entries found (checked last ${maxLines * 3} lines).\nTry increasing the filter or check if logcat is accessible."
            }
        } else {
            logs.toString()
        }
    } catch (e: Exception) {
        "Error reading logcat: ${e.message}\n${e.stackTraceToString().take(500)}"
    }
}

@Composable
fun DebugDialog(
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    useOllama: Boolean,
    ollamaHost: String,
    ollamaPort: Int,
    ollamaModel: String,
    ollamaUrl: String,
    workspaceRoot: String,
    messages: List<AgentMessage>,
    aiClient: Any
) {
    var logcatLogs by remember { mutableStateOf<String?>(null) }
    var isLoadingLogs by remember { mutableStateOf(false) }
    var systemInfo by remember { mutableStateOf<String?>(null) }
    var testInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    
    // Load all debug information when dialog opens
    LaunchedEffect(Unit) {
        isLoadingLogs = true
        // Load logs on IO dispatcher to avoid blocking main thread
        scope.launch(Dispatchers.IO) {
            val logs = readLogcatLogs(300)
            // Update UI state on Main dispatcher
            withContext(Dispatchers.Main) {
                logcatLogs = logs
            }
            
            // Get system information (already on IO dispatcher)
            try {
                val workspaceDir = File(workspaceRoot)
                val systemInfoBuilder = StringBuilder()
                
                // Detect project type
                val hasPackageJson = File(workspaceDir, "package.json").exists()
                val hasGoMod = File(workspaceDir, "go.mod").exists()
                val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
                val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
                val hasMaven = File(workspaceDir, "pom.xml").exists()
                val hasRequirements = File(workspaceDir, "requirements.txt").exists()
                val hasPipfile = File(workspaceDir, "Pipfile").exists()
                
                systemInfoBuilder.appendLine("Project Type: ")
                when {
                    hasPackageJson -> systemInfoBuilder.appendLine("  - Node.js (package.json found)")
                    hasGoMod -> systemInfoBuilder.appendLine("  - Go (go.mod found)")
                    hasCargoToml -> systemInfoBuilder.appendLine("  - Rust (Cargo.toml found)")
                    hasGradle -> systemInfoBuilder.appendLine("  - Java/Kotlin (Gradle)")
                    hasMaven -> systemInfoBuilder.appendLine("  - Java (Maven)")
                    hasPipfile -> systemInfoBuilder.appendLine("  - Python (Pipfile)")
                    hasRequirements -> systemInfoBuilder.appendLine("  - Python (requirements.txt)")
                    else -> systemInfoBuilder.appendLine("  - Unknown/Generic")
                }
                
                // Count files
                val fileCount = workspaceDir.walkTopDown().count { it.isFile }
                val dirCount = workspaceDir.walkTopDown().count { it.isDirectory }
                systemInfoBuilder.appendLine("Files: $fileCount files, $dirCount directories")
                
                // Get system info from SystemInfoService
                try {
                    val sysInfo = com.qali.aterm.agent.SystemInfoService.detectSystemInfo(workspaceRoot)
                    systemInfoBuilder.appendLine()
                    systemInfoBuilder.appendLine("OS: ${sysInfo.os}")
                    systemInfoBuilder.appendLine("OS Version: ${sysInfo.osVersion ?: "Unknown"}")
                    systemInfoBuilder.appendLine("Package Manager: ${sysInfo.packageManager}")
                    systemInfoBuilder.appendLine("Architecture: ${sysInfo.architecture}")
                    systemInfoBuilder.appendLine("Shell: ${sysInfo.shell}")
                    
                    if (sysInfo.packageManagerCommands.isNotEmpty()) {
                        systemInfoBuilder.appendLine("Package Manager Commands:")
                        sysInfo.packageManagerCommands.forEach { entry ->
                            systemInfoBuilder.appendLine("  - ${entry.key}: ${entry.value}")
                        }
                    }
                } catch (e: Exception) {
                    systemInfoBuilder.appendLine()
                    systemInfoBuilder.appendLine("System Info: Error - ${e.message}")
                }
                
                // Update UI state on Main dispatcher
                val finalSystemInfo = systemInfoBuilder.toString()
                withContext(Dispatchers.Main) {
                    systemInfo = finalSystemInfo
                    isLoadingLogs = false
                }
            } catch (e: Exception) {
                // Update UI state on Main dispatcher
                withContext(Dispatchers.Main) {
                    systemInfo = "Error getting system info: ${e.message}"
                    isLoadingLogs = false
                }
            }
            
            // Get testing information from messages (already on IO dispatcher)
            // Access messages safely on Main dispatcher to avoid snapshot lock issues
            val finalTestInfo = try {
                val messagesSnapshot = withContext(Dispatchers.Main) { messages }
                val testInfoBuilder = StringBuilder()
                val toolCalls = messagesSnapshot.filter { !it.isUser && it.text.contains("Tool") }
                val toolResults = messagesSnapshot.filter { !it.isUser && (it.text.contains("completed") || it.text.contains("Error")) }
                val testCommands = messagesSnapshot.filter { !it.isUser && (it.text.contains("test") || it.text.contains("npm test") || it.text.contains("pytest") || it.text.contains("cargo test") || it.text.contains("go test") || it.text.contains("mvn test") || it.text.contains("gradle test")) }
                
                testInfoBuilder.appendLine("Tool Calls: ${toolCalls.size}")
                testInfoBuilder.appendLine("Tool Results: ${toolResults.size}")
                testInfoBuilder.appendLine("Test Commands Detected: ${testCommands.size}")
                
                // Count by tool type
                val shellCalls = toolCalls.count { it.text.contains("shell") }
                val editCalls = toolCalls.count { it.text.contains("edit") }
                val readCalls = toolCalls.count { it.text.contains("read") }
                val writeCalls = toolCalls.count { it.text.contains("write") }
                val todosCalls = toolCalls.count { it.text.contains("write_todos") || it.text.contains("todo") }
                
                testInfoBuilder.appendLine()
                testInfoBuilder.appendLine("Tool Usage:")
                testInfoBuilder.appendLine("  - shell: $shellCalls")
                testInfoBuilder.appendLine("  - edit: $editCalls")
                testInfoBuilder.appendLine("  - read: $readCalls")
                testInfoBuilder.appendLine("  - write: $writeCalls")
                testInfoBuilder.appendLine("  - todos: $todosCalls")
                
                // Code debugging statistics
                val codeDebugAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Code error detected") || it.text.contains("debugging code")) }
                val codeFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("Code fix applied") || it.text.contains("Fixed __dirname")) }
                val fallbackAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Fallback") || it.text.contains("fallback")) }
                val esModuleFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("ES Module") || it.text.contains("type: \"module\"")) }
                
                testInfoBuilder.appendLine()
                testInfoBuilder.appendLine("Code Debugging:")
                testInfoBuilder.appendLine("  - Debug Attempts: $codeDebugAttempts")
                testInfoBuilder.appendLine("  - Successful Fixes: $codeFixes")
                testInfoBuilder.appendLine("  - ES Module Fixes: $esModuleFixes")
                testInfoBuilder.appendLine("  - Fallback Attempts: $fallbackAttempts")
                
                // Error analysis
                val errors = messagesSnapshot.filter { !it.isUser && (it.text.contains("Error") || it.text.contains("error") || it.text.contains("failed") || it.text.contains("Failed")) }
                val codeErrors = errors.count { it.text.contains("SyntaxError") || it.text.contains("TypeError") || it.text.contains("ReferenceError") || it.text.contains("ImportError") }
                val commandErrors = errors.count { it.text.contains("command not found") || it.text.contains("Exit code") || it.text.contains("127") }
                val dependencyErrors = errors.count { it.text.contains("module not found") || it.text.contains("package not found") || it.text.contains("Cannot find module") }
                val editErrors = errors.count { it.text.contains("String not found") || it.text.contains("edit") && it.text.contains("Error") }
                
                testInfoBuilder.appendLine()
                testInfoBuilder.appendLine("Error Analysis:")
                testInfoBuilder.appendLine("  - Total Errors: ${errors.size}")
                testInfoBuilder.appendLine("  - Code Errors: $codeErrors")
                testInfoBuilder.appendLine("  - Command Errors: $commandErrors")
                testInfoBuilder.appendLine("  - Dependency Errors: $dependencyErrors")
                testInfoBuilder.appendLine("  - Edit Tool Errors: $editErrors")
                
                // Success rate
                val successfulTools = toolResults.count { it.text.contains("✅") || (it.text.contains("completed") && !it.text.contains("Error")) }
                val totalTools = toolCalls.size
                if (totalTools > 0) {
                    val successRate = (successfulTools * 100.0 / totalTools).toInt()
                    testInfoBuilder.appendLine()
                    testInfoBuilder.appendLine("Success Rate: $successRate% ($successfulTools/$totalTools)")
                }
                
                // API call statistics (from logcat) - access logcatLogs safely
                val logsSnapshot = withContext(Dispatchers.Main) { logcatLogs }
                val apiCalls = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") } ?: 0
                val apiSuccess = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") && it.contains("Response code: 200") } ?: 0
                val apiSuccessRate = if (apiCalls > 0) (apiSuccess * 100.0 / apiCalls).toInt() else 0
                if (apiCalls > 0) {
                    testInfoBuilder.appendLine()
                    testInfoBuilder.appendLine("API Calls:")
                    testInfoBuilder.appendLine("  - Total: $apiCalls")
                    testInfoBuilder.appendLine("  - Successful: $apiSuccess ($apiSuccessRate%)")
                }
                
                // Recommendations based on errors
                val recommendations = mutableListOf<String>()
                if (commandErrors > 0 && commandErrors > codeErrors) {
                    recommendations.add("Install missing commands using package manager")
                }
                if (dependencyErrors > 0) {
                    recommendations.add("Run dependency installation (npm install, pip install, etc.)")
                }
                if (editErrors > 0) {
                    recommendations.add("Check file content matches before editing")
                }
                if (apiSuccessRate < 50 && apiCalls > 10) {
                    recommendations.add("Check API key validity and rate limits")
                }
                if (fallbackAttempts > 10) {
                    recommendations.add("Many fallback attempts - check package manager detection")
                }
                
                if (recommendations.isNotEmpty()) {
                    testInfoBuilder.appendLine()
                    testInfoBuilder.appendLine("Recommendations:")
                    recommendations.forEach { rec ->
                        testInfoBuilder.appendLine("  - $rec")
                    }
                }
                
                testInfoBuilder.toString()
            } catch (e: Exception) {
                "Error getting test info: ${e.message}"
            }
            
            // Update UI state on Main dispatcher
            withContext(Dispatchers.Main) {
                testInfo = finalTestInfo
                isLoadingLogs = false
            }
        }
    }
    
    val debugInfo = remember(useOllama, ollamaHost, ollamaPort, ollamaModel, ollamaUrl, workspaceRoot, messages, logcatLogs, systemInfo, testInfo, ApiProviderManager.selectedProvider) {
        buildString {
            appendLine("=== Agent Debug Information ===")
            appendLine()
            
            // Configuration
            appendLine("--- Configuration ---")
            val currentProvider = ApiProviderManager.selectedProvider
            val providerName = if (useOllama) "Ollama" else currentProvider.displayName
            appendLine("Provider: $providerName")
            if (useOllama) {
                appendLine("Host: $ollamaHost")
                appendLine("Port: $ollamaPort")
                appendLine("Model: $ollamaModel")
                appendLine("URL: $ollamaUrl")
            } else {
                val model = com.qali.aterm.api.ApiProviderManager.getCurrentModel()
                appendLine("Model: $model")
            }
            appendLine("Workspace Root: $workspaceRoot")
            appendLine()
            
            // System Information
            appendLine("--- System Information ---")
            appendLine(systemInfo ?: "Loading...")
            appendLine()
            
            // Testing Information
            appendLine("--- Testing & Analytics ---")
            appendLine(testInfo ?: "Loading...")
            appendLine()
            
            // Messages
            appendLine("--- Messages (${messages.size}) ---")
            messages.takeLast(20).forEachIndexed { index, msg ->
                val prefix = if (msg.isUser) "User" else "AI"
                val preview = msg.text.take(150)
                appendLine("${index + 1}. [$prefix] $preview${if (msg.text.length > 150) "..." else ""}")
            }
            appendLine()
            
            // Recent Tool Activity
            val recentToolActivity = messages.filter { !it.isUser && (it.text.contains("Tool") || it.text.contains("✅") || it.text.contains("❌")) }
            if (recentToolActivity.isNotEmpty()) {
                appendLine("--- Recent Tool Activity (last 10) ---")
                recentToolActivity.takeLast(10).forEachIndexed { index, msg ->
                    appendLine("${index + 1}. ${msg.text.take(200)}")
                }
                appendLine()
            }
            
            // Logcat
            appendLine("--- Recent Logcat (filtered) ---")
            appendLine(logcatLogs ?: "Loading...")
            appendLine()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Information") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
            ) {
                if (isLoadingLogs) {
                    CircularProgressIndicator()
                } else {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = debugInfo,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        // Launch on IO dispatcher to avoid blocking main thread
                        scope.launch(Dispatchers.IO) {
                            // Update loading state on Main dispatcher
                            withContext(Dispatchers.Main) {
                                isLoadingLogs = true
                            }
                            
                            val logs = readLogcatLogs(300)
                            
                            // Refresh system info (already on IO dispatcher)
                            val finalSystemInfo = try {
                                val workspaceDir = File(workspaceRoot)
                                val systemInfoBuilder = StringBuilder()
                                
                                val hasPackageJson = File(workspaceDir, "package.json").exists()
                                val hasGoMod = File(workspaceDir, "go.mod").exists()
                                val hasCargoToml = File(workspaceDir, "Cargo.toml").exists()
                                val hasGradle = File(workspaceDir, "build.gradle").exists() || File(workspaceDir, "build.gradle.kts").exists()
                                val hasMaven = File(workspaceDir, "pom.xml").exists()
                                val hasRequirements = File(workspaceDir, "requirements.txt").exists()
                                val hasPipfile = File(workspaceDir, "Pipfile").exists()
                                
                                systemInfoBuilder.appendLine("Project Type: ")
                                when {
                                    hasPackageJson -> systemInfoBuilder.appendLine("  - Node.js (package.json found)")
                                    hasGoMod -> systemInfoBuilder.appendLine("  - Go (go.mod found)")
                                    hasCargoToml -> systemInfoBuilder.appendLine("  - Rust (Cargo.toml found)")
                                    hasGradle -> systemInfoBuilder.appendLine("  - Java/Kotlin (Gradle)")
                                    hasMaven -> systemInfoBuilder.appendLine("  - Java (Maven)")
                                    hasPipfile -> systemInfoBuilder.appendLine("  - Python (Pipfile)")
                                    hasRequirements -> systemInfoBuilder.appendLine("  - Python (requirements.txt)")
                                    else -> systemInfoBuilder.appendLine("  - Unknown/Generic")
                                }
                                
                                val fileCount = workspaceDir.walkTopDown().count { it.isFile }
                                val dirCount = workspaceDir.walkTopDown().count { it.isDirectory }
                                systemInfoBuilder.appendLine("Files: $fileCount files, $dirCount directories")
                                
                                // Get system info
                                try {
                                    val sysInfo = com.qali.aterm.agent.SystemInfoService.detectSystemInfo(workspaceRoot)
                                    systemInfoBuilder.appendLine()
                                    systemInfoBuilder.appendLine("OS: ${sysInfo.os}")
                                    systemInfoBuilder.appendLine("OS Version: ${sysInfo.osVersion ?: "Unknown"}")
                                    systemInfoBuilder.appendLine("Package Manager: ${sysInfo.packageManager}")
                                    systemInfoBuilder.appendLine("Architecture: ${sysInfo.architecture}")
                                } catch (e: Exception) {
                                    // Ignore
                                }
                                
                                systemInfoBuilder.toString()
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                            
                            // Update all UI state on Main dispatcher
                            withContext(Dispatchers.Main) {
                                logcatLogs = logs
                                systemInfo = finalSystemInfo
                                isLoadingLogs = false
                            }
                            
                            // Refresh test info (already on IO dispatcher)
                            // Access messages safely on Main dispatcher to avoid snapshot lock issues
                            val finalTestInfoRefresh = try {
                                val messagesSnapshot = withContext(Dispatchers.Main) { messages }
                                val logsSnapshot = withContext(Dispatchers.Main) { logcatLogs }
                                val testInfoBuilder = StringBuilder()
                                val toolCalls = messagesSnapshot.filter { !it.isUser && it.text.contains("Tool") }
                                val toolResults = messagesSnapshot.filter { !it.isUser && (it.text.contains("completed") || it.text.contains("Error")) }
                                val testCommands = messagesSnapshot.filter { !it.isUser && (it.text.contains("test") || it.text.contains("npm test") || it.text.contains("pytest") || it.text.contains("cargo test") || it.text.contains("go test") || it.text.contains("mvn test") || it.text.contains("gradle test")) }
                                
                                testInfoBuilder.appendLine("Tool Calls: ${toolCalls.size}")
                                testInfoBuilder.appendLine("Tool Results: ${toolResults.size}")
                                testInfoBuilder.appendLine("Test Commands Detected: ${testCommands.size}")
                                
                                val shellCalls = toolCalls.count { it.text.contains("shell") }
                                val editCalls = toolCalls.count { it.text.contains("edit") }
                                val readCalls = toolCalls.count { it.text.contains("read") }
                                val writeCalls = toolCalls.count { it.text.contains("write") }
                                val todosCalls = toolCalls.count { it.text.contains("write_todos") || it.text.contains("todo") }
                                
                                testInfoBuilder.appendLine()
                                testInfoBuilder.appendLine("Tool Usage:")
                                testInfoBuilder.appendLine("  - shell: $shellCalls")
                                testInfoBuilder.appendLine("  - edit: $editCalls")
                                testInfoBuilder.appendLine("  - read: $readCalls")
                                testInfoBuilder.appendLine("  - write: $writeCalls")
                                testInfoBuilder.appendLine("  - todos: $todosCalls")
                                
                                val codeDebugAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Code error detected") || it.text.contains("debugging code")) }
                                val codeFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("Code fix applied") || it.text.contains("Fixed __dirname")) }
                                val fallbackAttempts = messagesSnapshot.count { !it.isUser && (it.text.contains("Fallback") || it.text.contains("fallback")) }
                                val esModuleFixes = messagesSnapshot.count { !it.isUser && (it.text.contains("ES Module") || it.text.contains("type: \"module\"")) }
                                
                                testInfoBuilder.appendLine()
                                testInfoBuilder.appendLine("Code Debugging:")
                                testInfoBuilder.appendLine("  - Debug Attempts: $codeDebugAttempts")
                                testInfoBuilder.appendLine("  - Successful Fixes: $codeFixes")
                                testInfoBuilder.appendLine("  - ES Module Fixes: $esModuleFixes")
                                testInfoBuilder.appendLine("  - Fallback Attempts: $fallbackAttempts")
                                
                                val errors = messagesSnapshot.filter { !it.isUser && (it.text.contains("Error") || it.text.contains("error") || it.text.contains("failed") || it.text.contains("Failed")) }
                                val codeErrors = errors.count { it.text.contains("SyntaxError") || it.text.contains("TypeError") || it.text.contains("ReferenceError") || it.text.contains("ImportError") }
                                val commandErrors = errors.count { it.text.contains("command not found") || it.text.contains("Exit code") || it.text.contains("127") }
                                val dependencyErrors = errors.count { it.text.contains("module not found") || it.text.contains("package not found") || it.text.contains("Cannot find module") }
                                val editErrors = errors.count { it.text.contains("String not found") || it.text.contains("edit") && it.text.contains("Error") }
                                
                                testInfoBuilder.appendLine()
                                testInfoBuilder.appendLine("Error Analysis:")
                                testInfoBuilder.appendLine("  - Total Errors: ${errors.size}")
                                testInfoBuilder.appendLine("  - Code Errors: $codeErrors")
                                testInfoBuilder.appendLine("  - Command Errors: $commandErrors")
                                testInfoBuilder.appendLine("  - Dependency Errors: $dependencyErrors")
                                testInfoBuilder.appendLine("  - Edit Tool Errors: $editErrors")
                                
                                val successfulTools = toolResults.count { it.text.contains("✅") || (it.text.contains("completed") && !it.text.contains("Error")) }
                                val totalTools = toolCalls.size
                                if (totalTools > 0) {
                                    val successRate = (successfulTools * 100.0 / totalTools).toInt()
                                    testInfoBuilder.appendLine()
                                    testInfoBuilder.appendLine("Success Rate: $successRate% ($successfulTools/$totalTools)")
                                }
                                
                                val apiCalls = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") } ?: 0
                                val apiSuccess = logsSnapshot?.split("\n")?.count { it.contains("makeApiCall") && it.contains("Response code: 200") } ?: 0
                                if (apiCalls > 0) {
                                    val apiSuccessRate = (apiSuccess * 100.0 / apiCalls).toInt()
                                    testInfoBuilder.appendLine()
                                    testInfoBuilder.appendLine("API Calls:")
                                    testInfoBuilder.appendLine("  - Total: $apiCalls")
                                    testInfoBuilder.appendLine("  - Successful: $apiSuccess ($apiSuccessRate%)")
                                }
                                
                                // Recommendations
                                val recommendations = mutableListOf<String>()
                                if (commandErrors > 0 && commandErrors > codeErrors) {
                                    recommendations.add("Install missing commands using package manager")
                                }
                                if (dependencyErrors > 0) {
                                    recommendations.add("Run dependency installation (npm install, pip install, etc.)")
                                }
                                if (editErrors > 0) {
                                    recommendations.add("Check file content matches before editing")
                                }
                                if (apiCalls > 0) {
                                    val apiSuccessRate = (apiSuccess * 100.0 / apiCalls).toInt()
                                    if (apiSuccessRate < 50 && apiCalls > 10) {
                                        recommendations.add("Check API key validity and rate limits")
                                    }
                                }
                                if (fallbackAttempts > 10) {
                                    recommendations.add("Many fallback attempts - check package manager detection")
                                }
                                
                                if (recommendations.isNotEmpty()) {
                                    testInfoBuilder.appendLine()
                                    testInfoBuilder.appendLine("Recommendations:")
                                    recommendations.forEach { rec ->
                                        testInfoBuilder.appendLine("  - $rec")
                                    }
                                }
                                
                                testInfoBuilder.toString()
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                            
                            // Update UI state on Main dispatcher
                            withContext(Dispatchers.Main) {
                                testInfo = finalTestInfoRefresh
                                isLoadingLogs = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Refresh", fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        onCopy(debugInfo)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Copy", fontSize = 11.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Close", fontSize = 12.sp)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    // Use remember with sessionId as key to preserve state per session (persistence handled by HistoryPersistenceService)
    var messages by remember(sessionId) { mutableStateOf<List<AgentMessage>>(emptyList()) }
    var messageHistory by remember(sessionId) { mutableStateOf<List<AgentMessage>>(emptyList()) } // Persistent history
    var showKeysExhaustedDialog by remember { mutableStateOf(false) }
    var lastFailedPrompt by remember { mutableStateOf<String?>(null) }
    var retryCountdown by remember { mutableStateOf(0) }
    var currentResponseText by remember { mutableStateOf("") }
    // Get workspace root based on session's working mode
    var workspaceRoot by remember(sessionId) { 
        mutableStateOf(
            if (mainActivity.sessionBinder != null) {
                val workingMode = mainActivity.sessionBinder!!.getSessionWorkingMode(sessionId)
                com.rk.libcommons.getRootfsDirForSession(sessionId, workingMode).absolutePath
            } else {
                com.rk.libcommons.getRootfsDir().absolutePath
            }
        )
    }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var showTerminateDialog by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showSessionSwitcher by remember { mutableStateOf(false) }
    // Track tool calls to extract file diffs (queue-based to handle multiple calls)
    val toolCallQueue = remember { mutableListOf<Pair<String, Map<String, Any>>>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Track current agent job for cancellation
    var currentAgentJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val clipboardManager = LocalClipboardManager.current
    
    // Read Ollama settings from Settings
    val useOllama = Settings.use_ollama
    val ollamaHost = Settings.ollama_host
    val ollamaPort = Settings.ollama_port
    val ollamaModel = Settings.ollama_model
    val ollamaUrl = "http://$ollamaHost:$ollamaPort"
    
    // Initialize client - use sessionId in key to ensure per-session clients
    val aiClient = remember(sessionId, workspaceRoot, useOllama, ollamaHost, ollamaPort, ollamaModel) {
        AgentService.initialize(workspaceRoot, useOllama, ollamaUrl, ollamaModel, sessionId, mainActivity)
    }
    
    // Ensure agent session exists when AgentScreen opens
    // This creates the hidden terminal session for the agent if it doesn't exist
    LaunchedEffect(sessionId) {
        if (mainActivity.sessionBinder != null) {
            val agentSessionId = "${sessionId}_agent"
            val agentSession = mainActivity.sessionBinder!!.getSession(agentSessionId)
            
            if (agentSession == null) {
                // Agent session doesn't exist, create it
                android.util.Log.d("AgentScreen", "Creating agent session: $agentSessionId")
                
                // Get the main session to determine working mode
                val mainSession = mainActivity.sessionBinder!!.getSession(sessionId)
                
                if (mainSession != null) {
                    // Main session exists, create just the agent session with matching working mode
                    val workingMode = mainActivity.sessionBinder!!.getSessionWorkingMode(sessionId) ?: com.rk.settings.Settings.working_Mode
                    
                    val agentClient = object : com.termux.terminal.TerminalSessionClient {
                        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {}
                        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
                        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {}
                        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
                        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
                        override fun setTerminalShellPid(session: com.termux.terminal.TerminalSession, pid: Int) {}
                        override fun onBell(session: com.termux.terminal.TerminalSession) {}
                        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
                        override fun onTerminalCursorStateChange(state: Boolean) {}
                        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
                        override fun logError(tag: String?, message: String?) {}
                        override fun logWarn(tag: String?, message: String?) {}
                        override fun logInfo(tag: String?, message: String?) {}
                        override fun logDebug(tag: String?, message: String?) {}
                        override fun logVerbose(tag: String?, message: String?) {}
                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                        override fun logStackTrace(tag: String?, e: Exception?) {}
                    }
                    
                    val agentSession = mainActivity.sessionBinder!!.createSession(agentSessionId, agentClient, mainActivity, workingMode)
                    // Mark as hidden/headless - this prevents UI operations like text selection
                    agentSession.setVisible(false)
                    android.util.Log.d("AgentScreen", "Created hidden agent session: $agentSessionId with working mode: $workingMode")
                    
                    // Ensure proper initialization of headless terminal
                    if (agentSession.emulator == null) {
                        android.util.Log.d("AgentScreen", "Initializing headless terminal emulator for $agentSessionId")
                        agentSession.updateSize(80, 24, 10, 20) // Default terminal size
                    }
                } else {
                    // Main session doesn't exist either, create both using createSessionWithHidden
                    val dummyClient = object : com.termux.terminal.TerminalSessionClient {
                        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {}
                        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
                        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) {}
                        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) {}
                        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
                        override fun setTerminalShellPid(session: com.termux.terminal.TerminalSession, pid: Int) {}
                        override fun onBell(session: com.termux.terminal.TerminalSession) {}
                        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
                        override fun onTerminalCursorStateChange(state: Boolean) {}
                        override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
                        override fun logError(tag: String?, message: String?) {}
                        override fun logWarn(tag: String?, message: String?) {}
                        override fun logInfo(tag: String?, message: String?) {}
                        override fun logDebug(tag: String?, message: String?) {}
                        override fun logVerbose(tag: String?, message: String?) {}
                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                        override fun logStackTrace(tag: String?, e: Exception?) {}
                    }
                    
                    // Create main session with hidden agent session (this creates both)
                    mainActivity.sessionBinder!!.createSessionWithHidden(
                        sessionId,
                        dummyClient,
                        mainActivity,
                        com.rk.settings.Settings.working_Mode
                    )
                    android.util.Log.d("AgentScreen", "Created main session and agent session: $sessionId -> $agentSessionId")
                }
            } else {
                android.util.Log.d("AgentScreen", "Agent session already exists: $agentSessionId")
            }
        }
    }
    
    // Load history on init for this session and restore to client
    LaunchedEffect(sessionId, aiClient) {
        val loadedHistory = HistoryPersistenceService.loadHistory(sessionId)
        val loadedMetadata = HistoryPersistenceService.loadSessionMetadata(sessionId)
        
        // Restore workspace directory
        loadedMetadata?.workspaceRoot?.let {
            workspaceRoot = it
        }
        
        if (loadedHistory.isNotEmpty()) {
            messages = loadedHistory
            messageHistory = loadedHistory
            // Restore history to client for context in API calls
            if (aiClient is AgentClient) {
                aiClient.restoreHistoryFromMessages(loadedHistory)
            }
            // CliBasedAgentClient doesn't need history restoration (uses script-based approach)
            android.util.Log.d("AgentScreen", "Loaded ${loadedHistory.size} messages for session $sessionId")
        } else {
            messages = emptyList()
            messageHistory = emptyList()
            // Clear client history if no saved history
            if (aiClient is AgentClient) {
                aiClient.resetChat()
            }
            // CliBasedAgentClient doesn't need reset (stateless)
        }
    }
    
    // Save history and metadata whenever they change (debounced to avoid too frequent saves)
    // Also save immediately when sessionId changes to preserve work when switching tabs
    LaunchedEffect(messages, sessionId, workspaceRoot, currentResponseText) {
        if (messages.isNotEmpty()) {
            // Save immediately (no debounce) when session changes to preserve work
            val shouldDebounce = true // Can be made configurable
            if (!shouldDebounce) {
                HistoryPersistenceService.saveHistory(sessionId, messages)
                messageHistory = messages
                android.util.Log.d("AgentScreen", "Saved ${messages.size} messages for session $sessionId (immediate)")
            } else {
                // Debounce saves to avoid too frequent disk writes
                kotlinx.coroutines.delay(500)
                HistoryPersistenceService.saveHistory(sessionId, messages)
                messageHistory = messages
                android.util.Log.d("AgentScreen", "Saved ${messages.size} messages for session $sessionId")
            }
        }
        
        // Save session metadata (workspace, pause state, etc.)
        val metadata = SessionMetadata(
            workspaceRoot = workspaceRoot,
            isPaused = false,
            lastPrompt = messages.lastOrNull()?.takeIf { it.isUser }?.text,
            currentResponseText = currentResponseText.takeIf { it.isNotEmpty() }
        )
        HistoryPersistenceService.saveSessionMetadata(sessionId, metadata)
    }
    
    // Save messages when component is about to be disposed (when switching away from this session)
    DisposableEffect(sessionId) {
        onDispose {
            // Save messages one final time when leaving this session
            if (messages.isNotEmpty()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    HistoryPersistenceService.saveHistory(sessionId, messages)
                    android.util.Log.d("AgentScreen", "Final save: ${messages.size} messages for session $sessionId on dispose")
                }
            }
        }
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header - Improved sizing and layout
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when {
                                useOllama -> "Ollama AI Agent"
                                AgentService.isUsingCliAgent() -> "CLI-Based AI Agent"
                                else -> "Gemini AI Agent"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = workspaceRoot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        fontSize = 11.sp
                    )
                }
                IconButton(
                    onClick = { showWorkspacePicker = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Folder, 
                        contentDescription = "Change Workspace", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // Session menu button with dropdown
                Box {
                    IconButton(
                        onClick = { showSessionMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Session Menu",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showSessionMenu,
                        onDismissRequest = { showSessionMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Create New Session") },
                            onClick = {
                                showSessionMenu = false
                                showNewSessionDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.AddCircle, contentDescription = null)
                            }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Terminate Session", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showSessionMenu = false
                                showTerminateDialog = true
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Stop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
                IconButton(
                    onClick = { showHistory = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Edit, 
                        contentDescription = "History", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // Session switcher button - to reopen saved chat sessions
                IconButton(
                    onClick = { showSessionSwitcher = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.History, 
                        contentDescription = "Switch Session", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { showDebugDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.BugReport, 
                        contentDescription = "View Debug Info: Shows agent configuration, message history, and recent logcat entries for troubleshooting", 
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    WelcomeMessage()
                }
            } else {
                // Show file changes summary at the top if there are any file changes
                val hasFileChanges = messages.any { it.fileDiff != null }
                if (hasFileChanges) {
                    item {
                        FileChangesSummaryCard(
                            messages = messages,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                
                items(messages) { message ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MessageBubble(message = message)
                        // Show diff card if message has file diff
                        message.fileDiff?.let { diff ->
                            CodeDiffCard(
                                fileDiff = diff,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        
        // Input area
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the agent...") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    val userMessage = AgentMessage(
                                        text = inputText,
                                        isUser = true,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = messages + userMessage
                                    val prompt = inputText
                                    inputText = ""
                                    
                                    // Cancel previous job if any (before starting new one)
                                    val previousJob = currentAgentJob
                                    currentAgentJob = null // Clear reference before cancelling to avoid race conditions
                                    previousJob?.cancel()
                                    
                                    // Send to Gemini API with tools
                                    // Launch on IO dispatcher to avoid blocking main thread
                                    val job = scope.launch(Dispatchers.IO) {
                                        val messageStartTime = System.currentTimeMillis()
                                        
                                        // Shared state for timeout monitoring
                                        data class ExecutionState(
                                            var lastEventTime: Long = messageStartTime,
                                            var eventCount: Int = 0,
                                            var chunkCount: Int = 0,
                                            var toolCallCount: Int = 0,
                                            var toolResultCount: Int = 0,
                                            var doneEventReceived: Boolean = false
                                        )
                                        val execState = ExecutionState()
                                        
                                        android.util.Log.d("AgentScreen", "Starting message send for: ${prompt.take(50)}...")
                                        
                                        // Start timeout monitoring coroutine
                                        val timeoutMonitorJob = scope.launch(Dispatchers.IO) {
                                            val timeoutMs = 300000L // 5 minutes
                                            val warningIntervalMs = 30000L // Warn every 30 seconds
                                            var lastWarningTime = messageStartTime
                                            
                                            while (!execState.doneEventReceived && timeoutMonitorJob.isActive) {
                                                delay(5000) // Check every 5 seconds
                                                val now = System.currentTimeMillis()
                                                val timeSinceStart = now - messageStartTime
                                                val timeSinceLastEvent = now - execState.lastEventTime
                                                
                                                if (timeSinceStart > timeoutMs) {
                                                    android.util.Log.e("AgentScreen", "TIMEOUT: Agent execution exceeded ${timeoutMs}ms (events: ${execState.eventCount}, last event: ${timeSinceLastEvent}ms ago)")
                                                    android.util.Log.e("AgentScreen", "Timeout details - Chunks: ${execState.chunkCount}, ToolCalls: ${execState.toolCallCount}, ToolResults: ${execState.toolResultCount}")
                                                    break
                                                }
                                                
                                                if (timeSinceLastEvent > warningIntervalMs && now - lastWarningTime > warningIntervalMs) {
                                                    android.util.Log.w("AgentScreen", "WARNING: No events for ${timeSinceLastEvent}ms (total time: ${timeSinceStart}ms, events: ${execState.eventCount})")
                                                    lastWarningTime = now
                                                }
                                            }
                                        }
                                        
                                        // Update UI state on main dispatcher
                                        withContext(Dispatchers.Main) {
                                            val loadingMessage = AgentMessage(
                                                text = "Thinking...",
                                                isUser = false,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            messages = messages + loadingMessage
                                            currentResponseText = ""
                                        }
                                        
                                        try {
                                            android.util.Log.d("AgentScreen", "Creating stream, useOllama: $useOllama, useCliAgent: ${AgentService.isUsingCliAgent()}")
                                            val stream = when {
                                                useOllama -> {
                                                    (aiClient as OllamaClient).sendMessage(
                                                    userMessage = prompt,
                                                    onChunk = { chunk ->
                                                        // Update UI state on main dispatcher - launch coroutine since callback is not suspend
                                                        scope.launch(Dispatchers.Main) {
                                                            currentResponseText += chunk
                                                            val currentMessages = if (messages.isNotEmpty()) messages.dropLast(1) else messages
                                                            messages = currentMessages + AgentMessage(
                                                                text = currentResponseText,
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                        }
                                                    },
                                                    onToolCall = { functionCall ->
                                                        // Update UI state on main dispatcher - launch coroutine since callback is not suspend
                                                        scope.launch(Dispatchers.Main) {
                                                            val toolMessage = AgentMessage(
                                                                text = "🔧 Calling tool: ${functionCall.name}",
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                            messages = messages + toolMessage
                                                        }
                                                    },
                                                    onToolResult = { toolName, args ->
                                                        // Update UI state on main dispatcher - launch coroutine since callback is not suspend
                                                        scope.launch(Dispatchers.Main) {
                                                            val resultMessage = AgentMessage(
                                                                text = "✅ Tool '$toolName' completed",
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                            messages = messages + resultMessage
                                                        }
                                                    }
                                                )
                                                }
                                                aiClient is CliBasedAgentClient -> {
                                                    (aiClient as CliBasedAgentClient).sendMessage(
                                                        userMessage = prompt,
                                                        onChunk = { },
                                                        onToolCall = { },
                                                        onToolResult = { _, _ -> }
                                                    )
                                                }
                                                else -> {
                                                    (aiClient as AgentClient).sendMessage(
                                                    userMessage = prompt,
                                                    onChunk = { chunk ->
                                                        // Update UI state on main dispatcher - launch coroutine since callback is not suspend
                                                        scope.launch(Dispatchers.Main) {
                                                            currentResponseText += chunk
                                                            val currentMessages = if (messages.isNotEmpty()) messages.dropLast(1) else messages
                                                            messages = currentMessages + AgentMessage(
                                                                text = currentResponseText,
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                        }
                                                    },
                                                    onToolCall = { functionCall ->
                                                        // Update UI state on main dispatcher - launch coroutine since callback is not suspend
                                                        scope.launch(Dispatchers.Main) {
                                                            val toolMessage = AgentMessage(
                                                                text = "🔧 Calling tool: ${functionCall.name}",
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                            messages = messages + toolMessage
                                                        }
                                                    },
                                                    onToolResult = { toolName, args ->
                                                        // Update UI state on main dispatcher - launch coroutine since callback is not suspend
                                                        scope.launch(Dispatchers.Main) {
                                                            val resultMessage = AgentMessage(
                                                                text = "✅ Tool '$toolName' completed",
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                            messages = messages + resultMessage
                                                        }
                                                    }
                                                )
                                                }
                                            }
                                            
                                            // Collect stream events on IO dispatcher
                                            android.util.Log.d("AgentScreen", "Starting to collect stream events")
                                            try {
                                                android.util.Log.d("AgentScreen", "About to start stream.collect")
                                                // Collect on IO dispatcher to avoid blocking main thread
                                                stream.collect { event ->
                                                    val eventTime = System.currentTimeMillis()
                                                    val timeSinceStart = eventTime - messageStartTime
                                                    val timeSinceLastEvent = eventTime - execState.lastEventTime
                                                    execState.lastEventTime = eventTime
                                                    execState.eventCount++
                                                    
                                                    android.util.Log.d("AgentScreen", "Stream collect lambda called (event #${execState.eventCount}, type: ${event.javaClass.simpleName}, time since start: ${timeSinceStart}ms, time since last: ${timeSinceLastEvent}ms)")
                                                    
                                                    try {
                                                        android.util.Log.d("AgentScreen", "Received stream event: ${event.javaClass.simpleName}")
                                                        // All state updates must happen on Main dispatcher to avoid Compose snapshot lock issues
                                                        when (event) {
                                                            is AgentEvent.Chunk -> {
                                                                execState.chunkCount++
                                                                android.util.Log.d("AgentScreen", "Processing Chunk event (count: ${execState.chunkCount}, size: ${event.text.length})")
                                                                withContext(Dispatchers.Main) {
                                                                    currentResponseText += event.text
                                                                    val currentMessages = if (messages.isNotEmpty()) messages.dropLast(1) else messages
                                                                    messages = currentMessages + AgentMessage(
                                                                        text = currentResponseText,
                                                                        isUser = false,
                                                                        timestamp = System.currentTimeMillis()
                                                                    )
                                                                }
                                                            }
                                                            is AgentEvent.ToolCall -> {
                                                                execState.toolCallCount++
                                                                android.util.Log.d("AgentScreen", "Processing ToolCall event (count: ${execState.toolCallCount}, tool: ${event.functionCall.name})")
                                                                // Store tool call args in queue for file diff extraction
                                                                if (event.functionCall.name == "edit" || event.functionCall.name == "write_file") {
                                                                    toolCallQueue.add(Pair(event.functionCall.name, event.functionCall.args))
                                                                }
                                                                withContext(Dispatchers.Main) {
                                                                    val toolMessage = AgentMessage(
                                                                        text = "🔧 Calling tool: ${event.functionCall.name}",
                                                                        isUser = false,
                                                                        timestamp = System.currentTimeMillis()
                                                                    )
                                                                    messages = messages + toolMessage
                                                                }
                                                            }
                                                            is AgentEvent.ToolResult -> {
                                                                execState.toolResultCount++
                                                                android.util.Log.d("AgentScreen", "Processing ToolResult event (count: ${execState.toolResultCount}, tool: ${event.toolName})")
                                                                // Try to extract file diff from tool result
                                                                // Find matching tool call from queue
                                                                val toolCallIndex = toolCallQueue.indexOfFirst { it.first == event.toolName }
                                                                val toolArgs = if (toolCallIndex >= 0) {
                                                                    val args = toolCallQueue[toolCallIndex].second
                                                                    toolCallQueue.removeAt(toolCallIndex) // Remove after use
                                                                    args
                                                                } else null
                                                                
                                                                val fileDiff = parseFileDiffFromToolResult(event.toolName, event.result, toolArgs)
                                                                
                                                                withContext(Dispatchers.Main) {
                                                                    val resultMessage = AgentMessage(
                                                                        text = "✅ Tool '${event.toolName}' completed: ${event.result.returnDisplay}",
                                                                        isUser = false,
                                                                        timestamp = System.currentTimeMillis(),
                                                                        fileDiff = fileDiff
                                                                    )
                                                                    messages = messages + resultMessage
                                                                }
                                                            }
                                                            is AgentEvent.Error -> {
                                                                android.util.Log.e("AgentScreen", "Processing Error event: ${event.message}")
                                                                withContext(Dispatchers.Main) {
                                                                    val errorMessage = AgentMessage(
                                                                        text = "❌ Error: ${event.message}",
                                                                        isUser = false,
                                                                        timestamp = System.currentTimeMillis()
                                                                    )
                                                                    messages = if (messages.isNotEmpty()) messages.dropLast(1) + errorMessage else messages + errorMessage
                                                                }
                                                            }
                                                            is AgentEvent.KeysExhausted -> {
                                                                android.util.Log.w("AgentScreen", "Processing KeysExhausted event")
                                                                withContext(Dispatchers.Main) {
                                                                    lastFailedPrompt = prompt
                                                                    showKeysExhaustedDialog = true
                                                                    val exhaustedMessage = AgentMessage(
                                                                        text = "⚠️ Keys are exhausted\n\nAll API keys are rate limited. Use 'Wait and Retry' to retry after a delay.",
                                                                        isUser = false,
                                                                        timestamp = System.currentTimeMillis()
                                                                    )
                                                                    messages = if (messages.isNotEmpty()) messages.dropLast(1) + exhaustedMessage else messages + exhaustedMessage
                                                                }
                                                            }
                                                            is AgentEvent.Done -> {
                                                                execState.doneEventReceived = true
                                                                timeoutMonitorJob.cancel()
                                                                val totalTime = System.currentTimeMillis() - messageStartTime
                                                                android.util.Log.d("AgentScreen", "Stream completed (Done event) - Total time: ${totalTime}ms")
                                                                android.util.Log.d("AgentScreen", "Event summary - Total: ${execState.eventCount}, Chunks: ${execState.chunkCount}, ToolCalls: ${execState.toolCallCount}, ToolResults: ${execState.toolResultCount}")
                                                                withContext(Dispatchers.Main) {
                                                                    // Final cleanup: ensure loading message is replaced with final response if there's any text
                                                                    if (currentResponseText.isNotEmpty() && messages.isNotEmpty()) {
                                                                        try {
                                                                            val lastMessage = messages.last()
                                                                            // Only replace if it's still the loading message or empty
                                                                            if (lastMessage.text == "Thinking..." || lastMessage.text.isEmpty()) {
                                                                                val currentMessages = messages.dropLast(1)
                                                                                messages = currentMessages + AgentMessage(
                                                                                    text = currentResponseText,
                                                                                    isUser = false,
                                                                                    timestamp = System.currentTimeMillis()
                                                                                )
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            android.util.Log.e("AgentScreen", "Error in Done event cleanup", e)
                                                                        }
                                                                    }
                                                                    // Reset currentResponseText for next message
                                                                    currentResponseText = ""
                                                                }
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("AgentScreen", "Error processing stream event", e)
                                                        android.util.Log.e("AgentScreen", "Event processing error - Event type: ${event.javaClass.simpleName}, Event count: ${execState.eventCount}")
                                                        e.printStackTrace()
                                                        // Emit error message but don't crash
                                                        withContext(Dispatchers.Main) {
                                                            val errorMessage = AgentMessage(
                                                                text = "❌ Error processing event: ${e.message ?: "Unknown error"}",
                                                                isUser = false,
                                                                timestamp = System.currentTimeMillis()
                                                            )
                                                            messages = if (messages.isNotEmpty()) messages.dropLast(1) + errorMessage else messages + errorMessage
                                                        }
                                                    }
                                                } // closes collect lambda
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                timeoutMonitorJob.cancel()
                                                val totalTime = System.currentTimeMillis() - messageStartTime
                                                val timeSinceLastEvent = System.currentTimeMillis() - execState.lastEventTime
                                                android.util.Log.d("AgentScreen", "Stream collection cancelled: ${e.message}")
                                                android.util.Log.d("AgentScreen", "Cancellation details - Total time: ${totalTime}ms, Time since last event: ${timeSinceLastEvent}ms")
                                                android.util.Log.d("AgentScreen", "Cancellation event summary - Total: ${execState.eventCount}, Chunks: ${execState.chunkCount}, ToolCalls: ${execState.toolCallCount}, ToolResults: ${execState.toolResultCount}, Done received: ${execState.doneEventReceived}")
                                                android.util.Log.d("AgentScreen", "Cancellation stack trace", e)
                                                // Clean up loading message for cancellations on Main dispatcher
                                                withContext(Dispatchers.Main) {
                                                    if (messages.isNotEmpty() && messages.last().text == "Thinking...") {
                                                        messages = messages.dropLast(1)
                                                    }
                                                }
                                                throw e
                                            } catch (e: Exception) {
                                                timeoutMonitorJob.cancel()
                                                val totalTime = System.currentTimeMillis() - messageStartTime
                                                val timeSinceLastEvent = System.currentTimeMillis() - execState.lastEventTime
                                                android.util.Log.e("AgentScreen", "Error in stream collection", e)
                                                android.util.Log.e("AgentScreen", "Stream collection error details - Total time: ${totalTime}ms, Time since last event: ${timeSinceLastEvent}ms")
                                                android.util.Log.e("AgentScreen", "Stream collection error event summary - Total: ${execState.eventCount}, Chunks: ${execState.chunkCount}, ToolCalls: ${execState.toolCallCount}, ToolResults: ${execState.toolResultCount}, Done received: ${execState.doneEventReceived}")
                                                android.util.Log.e("AgentScreen", "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
                                                e.printStackTrace()
                                                // Fall through to outer catch block
                                                throw e
                                            }
                                            timeoutMonitorJob.cancel()
                                            val totalTime = System.currentTimeMillis() - messageStartTime
                                            android.util.Log.d("AgentScreen", "Finished collecting stream events - Total time: ${totalTime}ms")
                                            android.util.Log.d("AgentScreen", "Final event summary - Total: ${execState.eventCount}, Chunks: ${execState.chunkCount}, ToolCalls: ${execState.toolCallCount}, ToolResults: ${execState.toolResultCount}, Done received: ${execState.doneEventReceived}")
                                            
                                            // Check if Done event was received
                                            if (!execState.doneEventReceived) {
                                                android.util.Log.w("AgentScreen", "WARNING: Stream collection finished but Done event was never received!")
                                                android.util.Log.w("AgentScreen", "This may indicate the agent stopped prematurely")
                                            }
                                            
                                            // Final safety check: ensure loading message is cleaned up on Main dispatcher
                                            try {
                                                withContext(Dispatchers.Main) {
                                                    currentAgentJob = null
                                                    try {
                                                        if (currentResponseText.isNotEmpty() && messages.isNotEmpty()) {
                                                            val lastMessage = messages.last()
                                                            if (lastMessage.text == "Thinking..." || lastMessage.text.isEmpty()) {
                                                                val currentMessages = messages.dropLast(1)
                                                                messages = currentMessages + AgentMessage(
                                                                    text = currentResponseText,
                                                                    isUser = false,
                                                                    timestamp = System.currentTimeMillis()
                                                                )
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("AgentScreen", "Error in final cleanup after stream", e)
                                                    }
                                                    currentResponseText = ""
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("AgentScreen", "Error in final cleanup after stream (outer)", e)
                                            }
                                        } catch (e: KeysExhaustedException) {
                                            android.util.Log.e("AgentScreen", "KeysExhaustedException caught", e)
                                            withContext(Dispatchers.Main) {
                                                lastFailedPrompt = prompt
                                                showKeysExhaustedDialog = true
                                                val exhaustedMessage = AgentMessage(
                                                    text = "⚠️ Keys are exhausted\n\nAll API keys are rate limited. Use 'Wait and Retry' to retry after a delay.",
                                                    isUser = false,
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                messages = if (messages.isNotEmpty()) messages.dropLast(1) + exhaustedMessage else messages + exhaustedMessage
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            val totalTime = System.currentTimeMillis() - messageStartTime
                                            android.util.Log.d("AgentScreen", "Message send cancelled: ${e.message}")
                                            android.util.Log.d("AgentScreen", "Cancellation details - Total time: ${totalTime}ms, Events: ${execState.eventCount}")
                                            android.util.Log.d("AgentScreen", "Cancellation cause", e)
                                            // Clean up loading message on Main dispatcher
                                            withContext(Dispatchers.Main) {
                                                if (messages.isNotEmpty() && messages.last().text == "Thinking...") {
                                                    messages = messages.dropLast(1)
                                                }
                                                currentAgentJob = null
                                            }
                                        } catch (e: Exception) {
                                            val totalTime = System.currentTimeMillis() - messageStartTime
                                            android.util.Log.e("AgentScreen", "Exception caught in message send", e)
                                            android.util.Log.e("AgentScreen", "Exception type: ${e.javaClass.simpleName}")
                                            android.util.Log.e("AgentScreen", "Exception message: ${e.message}")
                                            android.util.Log.e("AgentScreen", "Exception details - Total time: ${totalTime}ms, Events: ${execState.eventCount}, Chunks: ${execState.chunkCount}, ToolCalls: ${execState.toolCallCount}, ToolResults: ${execState.toolResultCount}")
                                            e.printStackTrace()
                                            // Update UI state on Main dispatcher
                                            withContext(Dispatchers.Main) {
                                                val errorMessage = AgentMessage(
                                                    text = "❌ Error: ${e.message ?: "Unknown error"}",
                                                    isUser = false,
                                                    timestamp = System.currentTimeMillis()
                                                )
                                                messages = if (messages.isNotEmpty()) messages.dropLast(1) + errorMessage else messages + errorMessage
                                                currentAgentJob = null
                                            }
                                        }
                                    }
                                    // Store job reference immediately after launching
                                    currentAgentJob = job
                                }
                            },
                            enabled = inputText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                )
            }
        }
    }
    
    // Workspace picker dialog
    if (showWorkspacePicker) {
        DirectoryPickerDialog(
            initialPath = workspaceRoot,
            onDismiss = { showWorkspacePicker = false },
            onDirectorySelected = { selectedDir: File ->
                workspaceRoot = selectedDir.absolutePath
                showWorkspacePicker = false
                // Save workspace directory immediately
                val metadata = SessionMetadata(
                    workspaceRoot = workspaceRoot,
                    isPaused = false,
                    lastPrompt = messages.lastOrNull()?.takeIf { it.isUser }?.text,
                    currentResponseText = currentResponseText.takeIf { it.isNotEmpty() }
                )
                HistoryPersistenceService.saveSessionMetadata(sessionId, metadata)
                // Client will be recreated automatically by the remember block when workspaceRoot changes
            }
        )
    }
    
    // History display dialog - shows current messages (which includes history)
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Conversation History")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    if (messages.isEmpty()) {
                        Text(
                            "No conversation history",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Total messages: ${messages.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn {
                            items(messages) { msg ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (msg.isUser) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (msg.isUser) "You" else "Agent",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (msg.isUser) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            Text(
                                                text = formatTimestamp(msg.timestamp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        SelectionContainer {
                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (msg.isUser) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) {
                    Text("Close")
                }
            }
        )
    }
    
    
    // New Session Dialog
    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("Create New Session") },
            text = {
                Column {
                    Text(
                        "Start a new agent session? This will clear the current conversation and start fresh.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (messages.isNotEmpty()) {
                        Text(
                            "⚠️ Current session has ${messages.size} message(s) that will be cleared.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        "Current session: $sessionId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNewSessionDialog = false
                        // Create new session by clearing current state
                        scope.launch {
                            // Save current session history before clearing (optional - user might want to keep it)
                            // For now, we'll clear it to start fresh
                            
                            // Clear history from persistence
                            HistoryPersistenceService.deleteHistory(sessionId)
                            
                            // Clear current session state
                            messages = emptyList()
                            messageHistory = emptyList()
                            inputText = ""
                            currentResponseText = ""
                            
                            // Clear client history
                            if (aiClient is AgentClient) {
                                aiClient.resetChat()
                            }
                            
                            android.util.Log.d("AgentScreen", "New session created - cleared session $sessionId")
                        }
                    }
                ) {
                    Text("Create New")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Terminate Session Dialog
    if (showTerminateDialog) {
        AlertDialog(
            onDismissRequest = { showTerminateDialog = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Terminate Session")
                }
            },
            text = {
                Column {
                    Text(
                        "Are you sure you want to terminate this session?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This will:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text("• Cancel any ongoing agent workflow", style = MaterialTheme.typography.bodySmall)
                    Text("• Clear all chat history for this session", style = MaterialTheme.typography.bodySmall)
                    Text("• Reset the conversation", style = MaterialTheme.typography.bodySmall)
                    Text("• Clear client chat history", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Session: $sessionId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Cancel any ongoing agent job first
                        android.util.Log.d("AgentScreen", "Terminating session $sessionId - cancelling agent job")
                        currentAgentJob?.cancel()
                        currentAgentJob = null
                        
                        // Terminate session: clear history and reset
                        scope.launch {
                            // Clear history from persistence
                            HistoryPersistenceService.deleteHistory(sessionId)
                            android.util.Log.d("AgentScreen", "Terminated session $sessionId - history cleared")
                            
                            // Clear messages
                            messages = emptyList()
                            messageHistory = emptyList()
                            
                            // Clear client history
                            if (aiClient is AgentClient) {
                                aiClient.resetChat()
                            }
                            
                            showTerminateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Terminate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTerminateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    
    // Keys Exhausted Dialog with Wait and Retry
    if (showKeysExhaustedDialog) {
        KeysExhaustedDialog(
            onDismiss = { showKeysExhaustedDialog = false },
            onWaitAndRetry = { waitSeconds ->
                showKeysExhaustedDialog = false
                retryCountdown = waitSeconds
                
                scope.launch {
                    while (retryCountdown > 0) {
                        delay(1000)
                        retryCountdown--
                    }
                    
                    // Retry by continuing from where we left off (not resending original prompt)
                    // Use special continuation message to preserve chat history
                    val continuationMessage = AgentMessage(
                        text = "Retrying after API key wait...",
                        isUser = true,
                        timestamp = System.currentTimeMillis()
                    )
                    messages = messages + continuationMessage
                    
                    val loadingMessage = AgentMessage(
                        text = "Retrying from current conversation state...",
                        isUser = false,
                        timestamp = System.currentTimeMillis()
                    )
                    messages = messages + loadingMessage
                    
                    // Get the AI client
                    val aiClient = when {
                        AgentService.isUsingOllama() -> AgentService.getOllamaClient()
                        AgentService.isUsingCliAgent() -> AgentService.getCliClient()
                        else -> AgentService.getClient()
                    }
                    
                    if (aiClient == null) {
                        val errorMessage = AgentMessage(
                            text = "❌ Error: AI client not available",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        messages = messages.dropLast(1) + errorMessage
                        return@launch
                    }
                    
                    // Use continuation message to resume from chat history
                    currentResponseText = ""
                    val stream = when {
                        aiClient is OllamaClient -> {
                            (aiClient as OllamaClient).sendMessage(
                                userMessage = "__CONTINUE__", // Special message to continue from chat history
                            onChunk = { chunk ->
                                currentResponseText += chunk
                                val currentMessages = if (messages.isNotEmpty()) messages.dropLast(1) else messages
                                messages = currentMessages + AgentMessage(
                                    text = currentResponseText,
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            },
                            onToolCall = { functionCall ->
                                val toolMessage = AgentMessage(
                                    text = "🔧 Calling tool: ${functionCall.name}",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                                messages = messages + toolMessage
                            },
                            onToolResult = { toolName, args ->
                                val resultMessage = AgentMessage(
                                    text = "✅ Tool '$toolName' completed",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                                messages = messages + resultMessage
                            }
                        )
                        }
                        aiClient is CliBasedAgentClient -> {
                            // CLI agent doesn't support continuation - just send a new message
                            (aiClient as CliBasedAgentClient).sendMessage(
                                userMessage = "Continue from previous conversation",
                                onChunk = { },
                                onToolCall = { },
                                onToolResult = { _, _ -> }
                            )
                        }
                        else -> {
                            // Fallback to AgentClient
                            (aiClient as AgentClient).sendMessage(
                                userMessage = "__CONTINUE__",
                                onChunk = { chunk ->
                                    currentResponseText += chunk
                                    val currentMessages = if (messages.isNotEmpty()) messages.dropLast(1) else messages
                                    messages = currentMessages + AgentMessage(
                                        text = currentResponseText,
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                },
                                onToolCall = { functionCall ->
                                    val toolMessage = AgentMessage(
                                        text = "🔧 Calling tool: ${functionCall.name}",
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = messages + toolMessage
                                },
                                onToolResult = { toolName, args ->
                                    val resultMessage = AgentMessage(
                                        text = "✅ Tool '$toolName' completed",
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = messages + resultMessage
                                }
                            )
                        }
                    }
                    
                    // Collect stream events
                    try {
                        stream.collect { event ->
                            when (event) {
                                is AgentEvent.Chunk -> {
                                    currentResponseText += event.text
                                    val currentMessages = if (messages.isNotEmpty()) messages.dropLast(1) else messages
                                    messages = currentMessages + AgentMessage(
                                        text = currentResponseText,
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                }
                                is AgentEvent.ToolCall -> {
                                    val toolMessage = AgentMessage(
                                        text = "🔧 Calling tool: ${event.functionCall.name}",
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = messages + toolMessage
                                }
                                is AgentEvent.ToolResult -> {
                                    val resultMessage = AgentMessage(
                                        text = "✅ Tool '${event.toolName}' completed: ${event.result.returnDisplay}",
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = messages + resultMessage
                                }
                                is AgentEvent.Error -> {
                                    val errorMessage = AgentMessage(
                                        text = "❌ Error: ${event.message}",
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = if (messages.isNotEmpty()) messages.dropLast(1) + errorMessage else messages + errorMessage
                                }
                                is AgentEvent.KeysExhausted -> {
                                    lastFailedPrompt = null // Clear since we're continuing, not restarting
                                    showKeysExhaustedDialog = true
                                    val exhaustedMessage = AgentMessage(
                                        text = "⚠️ Keys are still exhausted. Please wait longer or add more API keys.",
                                        isUser = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = if (messages.isNotEmpty()) messages.dropLast(1) + exhaustedMessage else messages + exhaustedMessage
                                }
                                is AgentEvent.Done -> {
                                    // Stream completed
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AgentScreen", "Error collecting stream", e)
                        val errorMessage = AgentMessage(
                            text = "❌ Error: ${e.message}",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        messages = if (messages.isNotEmpty()) messages.dropLast(1) + errorMessage else messages + errorMessage
                    }
                }
            }
        )
    }
    
    // Show countdown if retrying
    if (retryCountdown > 0) {
        LaunchedEffect(retryCountdown) {
            // Countdown is handled in the coroutine above
        }
    }
    
    // Session Switcher Dialog - to reopen saved chat sessions
    if (showSessionSwitcher) {
        var allSessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
        var sessionMetadataMap by remember { mutableStateOf<Map<String, SessionMetadata>>(emptyMap()) }
        
        LaunchedEffect(Unit) {
            allSessionIds = HistoryPersistenceService.getAllSessionIds()
            sessionMetadataMap = allSessionIds.associateWith { sessionId ->
                HistoryPersistenceService.loadSessionMetadata(sessionId) ?: SessionMetadata(
                    workspaceRoot = com.rk.libcommons.alpineDir().absolutePath,
                    isPaused = false
                )
            }
        }
        
        AlertDialog(
            onDismissRequest = { showSessionSwitcher = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Switch Chat Session")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    if (allSessionIds.isEmpty()) {
                        Text(
                            "No saved chat sessions found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Select a saved chat session to reopen:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn {
                            items(allSessionIds) { savedSessionId ->
                                val metadata = sessionMetadataMap[savedSessionId]
                                val sessionHistory = HistoryPersistenceService.loadHistory(savedSessionId)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    onClick = {
                                        showSessionSwitcher = false
                                        // Switch to this terminal session to load its chat history
                                        // If the session doesn't exist, it will be created when accessed
                                        scope.launch {
                                            try {
                                                // Switch to this terminal session - changeSession will create it if it doesn't exist
                                                // The chat history for this sessionId will automatically load when AgentScreen recomposes
                                                changeSession(mainActivity, savedSessionId)
                                                android.util.Log.d("AgentScreen", "Switched to terminal session: $savedSessionId (chat history will load automatically)")
                                            } catch (e: Exception) {
                                                android.util.Log.e("AgentScreen", "Error switching session", e)
                                            }
                                        }
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (savedSessionId == sessionId) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = savedSessionId,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (savedSessionId == sessionId) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "CURRENT",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Messages: ${sessionHistory.size}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        metadata?.workspaceRoot?.let {
                                            Text(
                                                text = "Workspace: $it",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        metadata?.lastPrompt?.let {
                                            Text(
                                                text = "Last: ${it.take(50)}${if (it.length > 50) "..." else ""}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionSwitcher = false }) {
                    Text("Close")
                }
            }
        )
    }
    
    // Debug Dialog
    if (showDebugDialog) {
        DebugDialog(
            onDismiss = { showDebugDialog = false },
            onCopy = { text ->
                clipboardManager.setText(AnnotatedString(text))
            },
            useOllama = useOllama,
            ollamaHost = ollamaHost,
            ollamaPort = ollamaPort,
            ollamaModel = ollamaModel,
            ollamaUrl = ollamaUrl,
            workspaceRoot = workspaceRoot,
            messages = messages,
            aiClient = aiClient
        )
    }
}
