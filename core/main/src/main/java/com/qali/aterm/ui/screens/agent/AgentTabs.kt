package com.qali.aterm.ui.screens.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.qali.aterm.ui.activities.terminal.MainActivity

/**
 * Agent tabs container with two sub-tabs:
 * - Agent: Main agent screen
 * - AI Model: Local model chat screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTabs(
    mainActivity: MainActivity,
    sessionId: String
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Agent", "AI Model")

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, text ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(text) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> AgentMainScreen(mainActivity, sessionId)
            1 -> LocalModelChatScreen(mainActivity, sessionId)
        }
    }
}

/**
 * Main agent screen (existing AgentScreen functionality)
 */
@Composable
private fun AgentMainScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    AgentScreen(mainActivity, sessionId)
}
