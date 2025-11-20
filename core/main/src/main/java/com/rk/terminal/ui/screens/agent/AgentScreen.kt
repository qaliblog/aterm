package com.rk.terminal.ui.screens.agent

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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.AddCircle
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
import com.rk.terminal.api.ApiProviderManager
import com.rk.terminal.api.ApiProviderManager.KeysExhaustedException
import com.rk.terminal.gemini.GeminiService
import com.rk.terminal.gemini.HistoryPersistenceService
import com.rk.terminal.gemini.client.GeminiClient
import com.rk.terminal.gemini.client.GeminiStreamEvent
import com.rk.terminal.gemini.client.OllamaClient
import com.rk.terminal.gemini.tools.ToolResult
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.settings.Settings
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

data class AgentMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
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
    val initialDir = com.rk.libcommons.alpineDir().absolutePath
    
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
    var workspaceRoot by remember(sessionId) { mutableStateOf(com.rk.libcommons.alpineDir().absolutePath) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var isPaused by remember(sessionId) { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var showTerminateDialog by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    
    // Read Ollama settings from Settings
    val useOllama = Settings.use_ollama
    val ollamaHost = Settings.ollama_host
    val ollamaPort = Settings.ollama_port
    val ollamaModel = Settings.ollama_model
    val ollamaUrl = "http://$ollamaHost:$ollamaPort"
    
    // Initialize client - use sessionId in key to ensure per-session clients
    val aiClient = remember(sessionId, workspaceRoot, useOllama, ollamaHost, ollamaPort, ollamaModel) {
        GeminiService.initialize(workspaceRoot, useOllama, ollamaUrl, ollamaModel)
    }
    
    // Load history on init for this session and restore to client
    LaunchedEffect(sessionId, aiClient) {
        val loadedHistory = HistoryPersistenceService.loadHistory(sessionId)
        if (loadedHistory.isNotEmpty()) {
            messages = loadedHistory
            messageHistory = loadedHistory
            // Restore history to client for context in API calls
            if (aiClient is GeminiClient) {
                aiClient.restoreHistoryFromMessages(loadedHistory)
            }
            android.util.Log.d("AgentScreen", "Loaded ${loadedHistory.size} messages for session $sessionId")
        } else {
            messages = emptyList()
            messageHistory = emptyList()
            // Clear client history if no saved history
            if (aiClient is GeminiClient) {
                aiClient.resetChat()
            }
        }
    }
    
    // Save history whenever messages change (debounced to avoid too frequent saves)
    // Also save immediately when sessionId changes to preserve work when switching tabs
    LaunchedEffect(messages, sessionId) {
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
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (useOllama) "Ollama AI Agent" else "Gemini AI Agent",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (isPaused) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "PAUSED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        text = workspaceRoot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                IconButton(onClick = { showWorkspacePicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Change Workspace", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                // Pause/Resume button
                IconButton(
                    onClick = { 
                        isPaused = !isPaused
                        if (isPaused) {
                            android.util.Log.d("AgentScreen", "Session $sessionId paused")
                        } else {
                            android.util.Log.d("AgentScreen", "Session $sessionId resumed")
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                // Session menu button with dropdown
                Box {
                    IconButton(
                        onClick = { showSessionMenu = true }
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Session Menu",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
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
                        DropdownMenuItem(
                            text = { Text(if (isPaused) "Resume Session" else "Pause Session") },
                            onClick = {
                                isPaused = !isPaused
                                showSessionMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                    contentDescription = null
                                )
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
                IconButton(onClick = { showHistory = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "History", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                IconButton(onClick = { showDebugDialog = true }) {
                    Icon(Icons.Default.BugReport, contentDescription = "Debug", tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                items(messages) { message ->
                    MessageBubble(message = message)
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
                    onValueChange = { if (!isPaused) inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (isPaused) "Session paused - click resume to continue" else "Ask the agent...") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isPaused,
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
                                    
                                    // Send to Gemini API with tools
                                    scope.launch {
                                        android.util.Log.d("AgentScreen", "Starting message send for: ${prompt.take(50)}...")
                                        val loadingMessage = AgentMessage(
                                            text = "Thinking...",
                                            isUser = false,
                                            timestamp = System.currentTimeMillis()
                                        )
                                        messages = messages + loadingMessage
                                        currentResponseText = ""
                                        
                                        try {
                                            android.util.Log.d("AgentScreen", "Creating stream, useOllama: $useOllama")
                                            val stream = if (useOllama) {
                                                (aiClient as OllamaClient).sendMessageStream(
                                                    userMessage = prompt,
                                                    onChunk = { chunk ->
                                                        currentResponseText += chunk
                                                        val currentMessages = messages.dropLast(1)
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
                                            } else {
                                                (aiClient as GeminiClient).sendMessageStream(
                                                    userMessage = prompt,
                                                    onChunk = { chunk ->
                                                        currentResponseText += chunk
                                                        val currentMessages = messages.dropLast(1)
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
                                            
                                            // Collect stream events
                                            android.util.Log.d("AgentScreen", "Starting to collect stream events")
                                            stream.collect { event ->
                                                android.util.Log.d("AgentScreen", "Received stream event: ${event.javaClass.simpleName}")
                                                when (event) {
                                                    is GeminiStreamEvent.Chunk -> {
                                                        currentResponseText += event.text
                                                        val currentMessages = messages.dropLast(1)
                                                        messages = currentMessages + AgentMessage(
                                                            text = currentResponseText,
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                    }
                                                    is GeminiStreamEvent.ToolCall -> {
                                                        val toolMessage = AgentMessage(
                                                            text = "🔧 Calling tool: ${event.functionCall.name}",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages + toolMessage
                                                    }
                                                    is GeminiStreamEvent.ToolResult -> {
                                                        val resultMessage = AgentMessage(
                                                            text = "✅ Tool '${event.toolName}' completed: ${(event.result as ToolResult).returnDisplay}",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages + resultMessage
                                                    }
                                                    is GeminiStreamEvent.Error -> {
                                                        val errorMessage = AgentMessage(
                                                            text = "❌ Error: ${event.message}",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages.dropLast(1) + errorMessage
                                                    }
                                                    is GeminiStreamEvent.KeysExhausted -> {
                                                        lastFailedPrompt = prompt
                                                        showKeysExhaustedDialog = true
                                                        val exhaustedMessage = AgentMessage(
                                                            text = "⚠️ Keys are exhausted\n\nAll API keys are rate limited. Use 'Wait and Retry' to retry after a delay.",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages.dropLast(1) + exhaustedMessage
                                                    }
                                                    is GeminiStreamEvent.Done -> {
                                                        android.util.Log.d("AgentScreen", "Stream completed (Done event)")
                                                    }
                                                }
                                            }
                                            android.util.Log.d("AgentScreen", "Finished collecting stream events")
                                        } catch (e: KeysExhaustedException) {
                                            android.util.Log.e("AgentScreen", "KeysExhaustedException caught", e)
                                            lastFailedPrompt = prompt
                                            showKeysExhaustedDialog = true
                                            val exhaustedMessage = AgentMessage(
                                                text = "⚠️ Keys are exhausted\n\nAll API keys are rate limited. Use 'Wait and Retry' to retry after a delay.",
                                                isUser = false,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            messages = messages.dropLast(1) + exhaustedMessage
                                        } catch (e: Exception) {
                                            android.util.Log.e("AgentScreen", "Exception caught in message send", e)
                                            android.util.Log.e("AgentScreen", "Exception type: ${e.javaClass.simpleName}")
                                            android.util.Log.e("AgentScreen", "Exception message: ${e.message}")
                                            val errorMessage = AgentMessage(
                                                text = "❌ Error: ${e.message ?: "Unknown error"}",
                                                isUser = false,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            messages = messages.dropLast(1) + errorMessage
                                        }
                                    }
                                }
                            },
                            enabled = inputText.isNotBlank() && !isPaused
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
    
    // Session Menu - using Box with DropdownMenu positioned relative to button
    Box {
        // Invisible anchor for dropdown menu
        if (showSessionMenu) {
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
            DropdownMenuItem(
                text = { Text(if (isPaused) "Resume Session" else "Pause Session") },
                onClick = {
                    isPaused = !isPaused
                    showSessionMenu = false
                },
                leadingIcon = {
                    Icon(
                        if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = null
                    )
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
                            isPaused = false
                            currentResponseText = ""
                            
                            // Clear client history
                            if (aiClient is GeminiClient) {
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
                        // Terminate session: clear history and reset
                        scope.launch {
                            // Clear history from persistence
                            HistoryPersistenceService.deleteHistory(sessionId)
                            android.util.Log.d("AgentScreen", "Terminated session $sessionId - history cleared")
                            
                            // Clear messages
                            messages = emptyList()
                            messageHistory = emptyList()
                            
                            // Clear client history
                            if (aiClient is GeminiClient) {
                                aiClient.resetChat()
                            }
                            
                            // Reset pause state
                            isPaused = false
                            
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
    
    // Paused indicator
    if (isPaused) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    "Session Paused - Click resume to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                    
                    // Retry the last prompt
                    lastFailedPrompt?.let { prompt ->
                        inputText = prompt
                        // Trigger send (simulate button click)
                        val userMessage = AgentMessage(
                            text = prompt,
                            isUser = true,
                            timestamp = System.currentTimeMillis()
                        )
                        messages = messages + userMessage
                        
                        val loadingMessage = AgentMessage(
                            text = "Retrying...",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        messages = messages + loadingMessage
                        
                        val result = ApiProviderManager.makeApiCallWithRetry { apiKey ->
                            kotlinx.coroutines.delay(1000)
                            if (kotlin.random.Random.nextDouble() < 0.3) {
                                Result.failure(Exception("Rate limit exceeded (RPM)"))
                            } else {
                                Result.success("Retry successful! Response for: \"$prompt\"")
                            }
                        }
                        
                        val currentMessages = messages.dropLast(1)
                        val responseMessage = if (result.isSuccess) {
                            AgentMessage(
                                text = result.getOrNull() ?: "No response",
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            )
                        } else {
                            val error = result.exceptionOrNull()
                            if (error is KeysExhaustedException) {
                                lastFailedPrompt = prompt
                                showKeysExhaustedDialog = true
                                AgentMessage(
                                    text = "⚠️ Keys are still exhausted. Please wait longer or add more API keys.",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            } else {
                                AgentMessage(
                                    text = "Error: ${error?.message ?: "Unknown error"}",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            }
                        }
                        
                        messages = currentMessages + responseMessage
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

@Composable
fun KeysExhaustedDialog(
    onDismiss: () -> Unit,
    onWaitAndRetry: (Int) -> Unit
) {
    var waitSeconds by remember { mutableStateOf(60) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Keys are Exhausted")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "All API keys are rate limited. You can wait and retry, or add more API keys in settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wait for:")
                    OutlinedTextField(
                        value = waitSeconds.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { seconds ->
                                if (seconds >= 0) waitSeconds = seconds
                            }
                        },
                        modifier = Modifier.width(100.dp),
                        label = { Text("Seconds") }
                    )
                    Text("seconds")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onWaitAndRetry(waitSeconds) }
            ) {
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
        
        // Relevant tags to filter for
        val relevantTags = listOf(
            "GeminiClient", "OllamaClient", "AgentScreen", "ApiProviderManager",
            "GeminiService", "OkHttp", "Okio", "AndroidRuntime", "ApiProvider",
            "OkHttpClient", "OkHttp3", "Okio", "System.err"
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
                        logLine.contains("streamGenerateContent", ignoreCase = true) ||
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
    val scope = rememberCoroutineScope()
    
    // Load logcat logs when dialog opens
    LaunchedEffect(Unit) {
        isLoadingLogs = true
        logcatLogs = readLogcatLogs(200)
        isLoadingLogs = false
    }
    
    val debugInfo = remember(useOllama, ollamaHost, ollamaPort, ollamaModel, ollamaUrl, workspaceRoot, messages, logcatLogs) {
        buildString {
            appendLine("=== Agent Debug Information ===")
            appendLine()
            
            // Configuration
            appendLine("--- Configuration ---")
            appendLine("Provider: ${if (useOllama) "Ollama" else "Gemini"}")
            if (useOllama) {
                appendLine("Ollama Host: $ollamaHost")
                appendLine("Ollama Port: $ollamaPort")
                appendLine("Ollama Model: $ollamaModel")
                appendLine("Ollama URL: $ollamaUrl")
            } else {
                try {
                    val currentModel = ApiProviderManager.getCurrentModel()
                    val providerType = ApiProviderManager.selectedProvider
                    appendLine("Gemini Provider: ${providerType.displayName}")
                    appendLine("Gemini Model: $currentModel")
                    val providers = ApiProviderManager.getProviders()
                    val provider = providers[providerType]
                    val activeKeys = provider?.getActiveKeys()?.size ?: 0
                    appendLine("Active API Keys: $activeKeys")
                } catch (e: Exception) {
                    appendLine("Error getting Gemini config: ${e.message}")
                }
            }
            appendLine("Workspace Root: $workspaceRoot")
            appendLine()
            
            // System Information
            appendLine("--- System Information ---")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine("SDK Version: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            
            // Client Status
            appendLine("--- Client Status ---")
            try {
                if (useOllama) {
                    val ollamaClient = aiClient as? OllamaClient
                    if (ollamaClient != null) {
                        val history = ollamaClient.getHistory()
                        appendLine("Ollama Client: Initialized")
                        appendLine("Chat History Messages: ${history.size}")
                    } else {
                        appendLine("Ollama Client: Not initialized")
                    }
                } else {
                    val geminiClient = aiClient as? GeminiClient
                    if (geminiClient != null) {
                        val history = geminiClient.getHistory()
                        appendLine("Gemini Client: Initialized")
                        appendLine("Chat History Messages: ${history.size}")
                    } else {
                        appendLine("Gemini Client: Not initialized")
                    }
                }
            } catch (e: Exception) {
                appendLine("Client Status Error: ${e.message}")
            }
            appendLine()
            
            // Recent Messages & Errors
            appendLine("--- Recent Messages & Errors ---")
            val errorMessages = messages.filter { 
                it.text.contains("Error", ignoreCase = true) || 
                it.text.contains("❌", ignoreCase = false) ||
                it.text.contains("⚠️", ignoreCase = false)
            }
            
            if (errorMessages.isNotEmpty()) {
                appendLine("Error Messages Found: ${errorMessages.size}")
                errorMessages.takeLast(5).forEachIndexed { index, msg ->
                    appendLine("  [${index + 1}] ${formatTimestamp(msg.timestamp)}: ${msg.text.take(200)}")
                }
            } else {
                appendLine("No error messages found")
            }
            appendLine()
            
            // Recent Messages Summary
            appendLine("--- Message Summary ---")
            appendLine("Total Messages: ${messages.size}")
            val userMessages = messages.count { it.isUser }
            val agentMessages = messages.count { !it.isUser }
            appendLine("User Messages: $userMessages")
            appendLine("Agent Messages: $agentMessages")
            
            if (messages.isNotEmpty()) {
                appendLine()
                appendLine("Last 3 Messages:")
                messages.takeLast(3).forEach { msg ->
                    val role = if (msg.isUser) "User" else "Agent"
                    val preview = msg.text.take(100).replace("\n", " ")
                    appendLine("  [$role] ${formatTimestamp(msg.timestamp)}: $preview${if (msg.text.length > 100) "..." else ""}")
                }
            }
            appendLine()
            
            // Connection Test Info
            appendLine("--- Connection Test ---")
            if (useOllama) {
                appendLine("Test URL: $ollamaUrl/api/tags")
                appendLine("Expected: HTTP 200 with model list")
            } else {
                appendLine("API Endpoint: https://generativelanguage.googleapis.com/v1beta/")
                appendLine("Note: Check API key validity in settings")
            }
            appendLine()
            
            // Logcat Logs
            appendLine("--- Logcat Logs (Recent) ---")
            if (isLoadingLogs) {
                appendLine("Loading logcat logs...")
            } else if (logcatLogs != null) {
                if (logcatLogs!!.isNotEmpty()) {
                    appendLine(logcatLogs!!)
                } else {
                    appendLine("No logcat logs available")
                }
            } else {
                appendLine("Logcat logs not loaded")
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Debug Information")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                SelectionContainer {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = debugInfo,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isLoadingLogs = true
                            logcatLogs = readLogcatLogs(200)
                            isLoadingLogs = false
                        }
                    },
                    enabled = !isLoadingLogs
                ) {
                    if (isLoadingLogs) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh Logs")
                }
                Button(
                    onClick = {
                        onCopy(debugInfo)
                        onDismiss()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy All")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
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
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
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
