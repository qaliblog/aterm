package com.qali.aterm.ui.screens.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.ui.screens.agent.models.FileDiff

/**
 * Minimal file diff card for chat history - shows just file name and status
 */
@Composable
fun MinimalFileDiffCard(
    fileDiff: FileDiff,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (fileDiff.isNewFile) {
                Color(0xFF1E4620).copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ),
        border = if (fileDiff.isNewFile) {
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Icon
            Icon(
                imageVector = if (fileDiff.isNewFile) Icons.Outlined.Add else Icons.Outlined.Edit,
                contentDescription = null,
                tint = if (fileDiff.isNewFile) {
                    Color(0xFF4CAF50)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(18.dp)
            )
            
            // Status badge
            Surface(
                color = if (fileDiff.isNewFile) {
                    Color(0xFF4CAF50).copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (fileDiff.isNewFile) "ADDED" else "CHANGED",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fileDiff.isNewFile) {
                        Color(0xFF4CAF50)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp
                )
            }
            
            // File name
            Text(
                text = fileDiff.filePath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
