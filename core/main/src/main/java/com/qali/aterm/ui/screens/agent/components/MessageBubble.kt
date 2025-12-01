package com.qali.aterm.ui.screens.agent.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.ui.screens.agent.models.AgentMessage
import com.qali.aterm.ui.screens.agent.models.formatTimestamp

@Composable
fun MessageBubble(
    message: AgentMessage,
    onToolApproved: ((com.qali.aterm.agent.core.FunctionCall) -> Unit)? = null,
    onToolSkipped: ((com.qali.aterm.agent.core.FunctionCall, String) -> Unit)? = null,
    onAddToAllowList: ((com.qali.aterm.agent.core.FunctionCall) -> Unit)? = null
) {
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
            shape = MaterialTheme.shapes.medium,
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
                
                // Show pending tool call approval UI
                message.pendingToolCall?.let { pendingCall ->
                    if (pendingCall.requiresApproval) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Warning card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Action Requires Approval",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                
                                Text(
                                    text = pendingCall.reason.ifEmpty { "This operation may be dangerous" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                
                                // Tool call details
                                Text(
                                    text = "Tool: ${pendingCall.functionCall.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                
                                if (pendingCall.functionCall.name == "shell") {
                                    val command = pendingCall.functionCall.args["command"] as? String ?: ""
                                    Text(
                                        text = "Command: $command",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                }
                                
                                // Flat modern buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Allow button
                                    Button(
                                        onClick = {
                                            onToolApproved?.invoke(pendingCall.functionCall)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        elevation = ButtonDefaults.buttonElevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 2.dp
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Allow", fontSize = 14.sp)
                                    }
                                    
                                    // Skip button
                                    var showSkipDialog by remember { mutableStateOf(false) }
                                    OutlinedButton(
                                        onClick = { showSkipDialog = true },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                            width = 1.dp
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Skip", fontSize = 14.sp)
                                    }
                                    
                                    // Skip reason dialog
                                    if (showSkipDialog) {
                                        var skipReason by remember { mutableStateOf("") }
                                        AlertDialog(
                                            onDismissRequest = { showSkipDialog = false },
                                            title = { Text("Skip Action") },
                                            text = {
                                                Column {
                                                    Text("Why are you skipping this action?")
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    OutlinedTextField(
                                                        value = skipReason,
                                                        onValueChange = { skipReason = it },
                                                        placeholder = { Text("Reason (optional)") },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        maxLines = 3
                                                    )
                                                }
                                            },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        onToolSkipped?.invoke(pendingCall.functionCall, skipReason)
                                                        showSkipDialog = false
                                                    },
                                                    shape = RoundedCornerShape(8.dp),
                                                    elevation = ButtonDefaults.buttonElevation(
                                                        defaultElevation = 0.dp
                                                    )
                                                ) {
                                                    Text("Skip")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(
                                                    onClick = { showSkipDialog = false },
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text("Cancel")
                                                }
                                            }
                                        )
                                    }
                                }
                                
                                // Add to allow list button (only for shell commands)
                                if (pendingCall.functionCall.name == "shell" && onAddToAllowList != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = {
                                            onAddToAllowList(pendingCall.functionCall)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Add to Allow List",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
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
