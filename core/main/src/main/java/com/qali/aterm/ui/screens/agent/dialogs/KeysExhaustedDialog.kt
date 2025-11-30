package com.qali.aterm.ui.screens.agent.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
