package com.qali.aterm.ui.screens.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.llm.LocalLlamaModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Local model chat screen for built-in LLM
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelChatScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isGenerating by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    val chatLogs = remember { mutableStateListOf<LogEntry>() }

    Column(modifier = Modifier.fillMaxSize()) {
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Debug Logs (${logs.size})")
                TextButton(onClick = onClear) {
                    Text("Clear")
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
