package com.qali.aterm.ui.screens.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qali.aterm.ui.screens.agent.models.DiffLine
import com.qali.aterm.ui.screens.agent.models.DiffLineType
import com.qali.aterm.ui.screens.agent.models.FileDiff
import com.qali.aterm.ui.screens.agent.models.calculateLineDiff

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
        shape = MaterialTheme.shapes.medium,
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
                            shape = MaterialTheme.shapes.small
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
