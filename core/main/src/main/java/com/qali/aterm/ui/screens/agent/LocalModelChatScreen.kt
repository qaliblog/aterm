package com.qali.aterm.ui.screens.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.llm.LocalLlamaModel
import kotlinx.coroutines.launch

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
                        scope.launch {
                            try {
                                val response = LocalLlamaModel.generate(userMessage)
                                messages = messages + ChatMessage(
                                    text = response,
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                                // Auto-scroll to bottom
                                listState.animateScrollToItem(messages.size)
                            } catch (e: Exception) {
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
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
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
