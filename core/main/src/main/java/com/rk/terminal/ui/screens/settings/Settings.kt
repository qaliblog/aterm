package com.rk.terminal.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.resources.strings
import com.rk.settings.Settings
import com.qali.aterm.ui.activities.terminal.MainActivity
import com.qali.aterm.ui.components.SettingsToggle
import com.qali.aterm.ui.routes.MainActivityRoutes


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    title: @Composable () -> Unit,
    description: @Composable () -> Unit = {},
    startWidget: (@Composable () -> Unit)? = null,
    endWidget: (@Composable () -> Unit)? = null,
    isEnabled: Boolean = true,
    onClick: () -> Unit
) {
    PreferenceTemplate(
        modifier = modifier
            .combinedClickable(
                enabled = isEnabled,
                indication = ripple(),
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentModifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(start = 16.dp),
        title = title,
        description = description,
        startWidget = startWidget,
        endWidget = endWidget,
        applyPaddings = false
    )

}


object WorkingMode{
    const val ALPINE = 0
    const val ANDROID = 1
    const val UBUNTU = 2
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(modifier: Modifier = Modifier,navController: NavController,mainActivity: MainActivity) {
    val context = LocalContext.current
    var selectedOption by remember { mutableIntStateOf(Settings.working_Mode) }

    PreferenceLayout(label = stringResource(strings.settings)) {
        PreferenceGroup(heading = "Default Working mode") {

            SettingsCard(
                title = { Text("Alpine") },
                description = {Text("Alpine Linux")},
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedOption == WorkingMode.ALPINE,
                        onClick = {
                            selectedOption = WorkingMode.ALPINE
                            Settings.working_Mode = selectedOption
                        })
                },
                onClick = {
                    selectedOption = WorkingMode.ALPINE
                    Settings.working_Mode = selectedOption
                })


            SettingsCard(
                title = { Text("Android") },
                description = {Text("aTerm Android shell")},
                startWidget = {
                    RadioButton(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            ,
                        selected = selectedOption == WorkingMode.ANDROID,
                        onClick = {
                            selectedOption = WorkingMode.ANDROID
                            Settings.working_Mode = selectedOption
                        })
                },
                onClick = {
                    selectedOption = WorkingMode.ANDROID
                    Settings.working_Mode = selectedOption
                })
        }


        PreferenceGroup {
            SettingsToggle(
                label = "Customizations",
                showSwitch = false,
                default = false,
                sideEffect = {
                   navController.navigate(MainActivityRoutes.Customization.route)
            }, endWidget = {
                Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,modifier = Modifier.padding(16.dp))
            })
        }
        
        // API Provider Settings
        com.qali.aterm.ui.screens.settings.ApiProviderSettings()
        
        // Ollama Settings
        com.qali.aterm.ui.screens.settings.OllamaSettings()
        
        // Agent Settings
        com.qali.aterm.ui.screens.settings.AgentSettings()
    }
}