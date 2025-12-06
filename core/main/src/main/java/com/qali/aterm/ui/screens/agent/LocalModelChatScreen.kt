package com.qali.aterm.ui.screens.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.llm.LocalLlamaModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.content.Context
import com.rk.settings.Preference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local model chat screen for built-in LLM
 * Supports chat history, sessions, and debug logs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelChatScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isGenerating by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    val chatLogs = remember { mutableStateListOf<LogEntry>() }
    
    // Load chat history on first composition
    LaunchedEffect(sessionId) {
        messages = loadChatHistory(context, sessionId)
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size)
            }
        }
    }
    
    // Save chat history whenever messages change
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            saveChatHistory(context, sessionId, messages)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar with session management
        TopAppBar(
            title = { Text("AI Model Chat") },
            actions = {
                IconButton(onClick = { showSessionDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Manage Sessions"
                    )
                }
            }
        )
        
        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatMessageItem(message)
            }
        }

        // Input area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = !isGenerating,
                singleLine = false,
                maxLines = 5
            )

            // Debug logs button
            IconButton(
                onClick = { showLogsDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Debug Logs",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = {
                    if (inputText.isNotBlank() && !isGenerating) {
                        val userMessage = inputText
                        inputText = ""
                        
                        // Add user message
                        messages = messages + ChatMessage(
                            text = userMessage,
                            isUser = true,
                            timestamp = System.currentTimeMillis()
                        )
                        
                        // Scroll to bottom
                        scope.launch {
                            listState.animateScrollToItem(messages.size)
                        }
                        
                        // Generate response
                        isGenerating = true
                        val startTime = System.currentTimeMillis()
                        chatLogs.add(LogEntry("INFO", "Starting generation for prompt: ${userMessage.take(50)}..."))
                        
                        scope.launch {
                            try {
                                val response = LocalLlamaModel.generate(userMessage)
                                val duration = System.currentTimeMillis() - startTime
                                chatLogs.add(LogEntry("SUCCESS", "Generation completed in ${duration}ms. Response length: ${response.length}"))
                                
                                messages = messages + ChatMessage(
                                    text = response,
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                                // Auto-scroll to bottom
                                listState.animateScrollToItem(messages.size)
                            } catch (e: Exception) {
                                val duration = System.currentTimeMillis() - startTime
                                chatLogs.add(LogEntry("ERROR", "Generation failed after ${duration}ms: ${e.message}"))
                                
                                messages = messages + ChatMessage(
                                    text = "Error: ${e.message}",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            } finally {
                                isGenerating = false
                            }
                        }
                    }
                },
                enabled = !isGenerating && inputText.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (isGenerating || inputText.isBlank()) 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else 
                        MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    
    // Logs dialog
    if (showLogsDialog) {
        LogsDialog(
            logs = chatLogs,
            onDismiss = { showLogsDialog = false },
            onClear = { chatLogs.clear() }
        )
    }
    
    // Session management dialog
    if (showSessionDialog) {
        SessionManagementDialog(
            currentSessionId = sessionId,
            onDismiss = { showSessionDialog = false },
            onNewSession = {
                // Create new session by clearing current messages
                messages = emptyList()
                chatLogs.clear()
                showSessionDialog = false
            },
            onDeleteSession = { sessionToDelete ->
                deleteChatHistory(context, sessionToDelete)
                if (sessionToDelete == sessionId) {
                    messages = emptyList()
                    chatLogs.clear()
                }
                showSessionDialog = false
            }
        )
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)

data class LogEntry(
    val level: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
private fun ChatMessageItem(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) 
                    MaterialTheme.colorScheme.primaryContainer
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LogsDialog(
    logs: List<LogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    
    // Build log text for copying
    val logText = logs.joinToString("\n") { log ->
        "${dateFormat.format(Date(log.timestamp))} [${log.level}] ${log.message}"
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Debug Logs (${logs.size})")
                Row {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(logText))
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Logs"
                            )
                        }
                    }
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        },
        text = {
            if (logs.isEmpty()) {
                Text(
                    "No logs yet. Logs will appear here when you interact with the model.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    logs.forEach { log ->
                        val color = when (log.level) {
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "SUCCESS" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = dateFormat.format(Date(log.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "[${log.level}]",
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = log.message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun SessionManagementDialog(
    currentSessionId: String,
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit
) {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<String>() }
    
    LaunchedEffect(Unit) {
        sessions.addAll(getAllSessions(context))
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat Sessions") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (sessions.isEmpty()) {
                    Text("No saved sessions")
                } else {
                    sessions.forEach { sessionId ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (sessionId == currentSessionId) "Current Session" else sessionId,
                                modifier = Modifier.weight(1f)
                            )
                            if (sessionId != currentSessionId) {
                                IconButton(onClick = { onDeleteSession(sessionId) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Session"
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onNewSession) {
                    Text("New Session")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

// Chat history persistence functions
private fun saveChatHistory(context: Context, sessionId: String, messages: List<ChatMessage>) {
    try {
        val jsonArray = JSONArray()
        messages.forEach { message ->
            val json = JSONObject().apply {
                put("text", message.text)
                put("isUser", message.isUser)
                put("timestamp", message.timestamp)
            }
            jsonArray.put(json)
        }
        
        val key = "chat_history_$sessionId"
        Preference.putString(key, jsonArray.toString())
        
        // Also save session ID to list of sessions
        val sessionsKey = "chat_sessions"
        val sessionsJson = Preference.getString(sessionsKey, "[]")
        val sessionsArray = JSONArray(sessionsJson)
        var found = false
        for (i in 0 until sessionsArray.length()) {
            if (sessionsArray.getString(i) == sessionId) {
                found = true
                break
            }
        }
        if (!found) {
            sessionsArray.put(sessionId)
            Preference.putString(sessionsKey, sessionsArray.toString())
        }
    } catch (e: Exception) {
        android.util.Log.e("LocalModelChatScreen", "Failed to save chat history: ${e.message}", e)
    }
}

private fun loadChatHistory(context: Context, sessionId: String): List<ChatMessage> {
    return try {
        val key = "chat_history_$sessionId"
        val jsonStr = Preference.getString(key, "[]")
        val jsonArray = JSONArray(jsonStr)
        val messages = mutableListOf<ChatMessage>()
        
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            messages.add(
                ChatMessage(
                    text = json.getString("text"),
                    isUser = json.getBoolean("isUser"),
                    timestamp = json.getLong("timestamp")
                )
            )
        }
        messages
    } catch (e: Exception) {
        android.util.Log.e("LocalModelChatScreen", "Failed to load chat history: ${e.message}", e)
        emptyList()
    }
}

private fun deleteChatHistory(context: Context, sessionId: String) {
    try {
        val key = "chat_history_$sessionId"
        Preference.putString(key, "")
        
        // Remove from sessions list
        val sessionsKey = "chat_sessions"
        val sessionsJson = Preference.getString(sessionsKey, "[]")
        val sessionsArray = JSONArray(sessionsJson)
        val newSessionsArray = JSONArray()
        for (i in 0 until sessionsArray.length()) {
            if (sessionsArray.getString(i) != sessionId) {
                newSessionsArray.put(sessionsArray.getString(i))
            }
        }
        Preference.putString(sessionsKey, newSessionsArray.toString())
    } catch (e: Exception) {
        android.util.Log.e("LocalModelChatScreen", "Failed to delete chat history: ${e.message}", e)
    }
}

private fun getAllSessions(context: Context): List<String> {
    return try {
        val sessionsKey = "chat_sessions"
        val sessionsJson = Preference.getString(sessionsKey, "[]")
        val sessionsArray = JSONArray(sessionsJson)
        val sessions = mutableListOf<String>()
        for (i in 0 until sessionsArray.length()) {
            sessions.add(sessionsArray.getString(i))
        }
        sessions
    } catch (e: Exception) {
        android.util.Log.e("LocalModelChatScreen", "Failed to get sessions: ${e.message}", e)
        emptyList()
    }
}
