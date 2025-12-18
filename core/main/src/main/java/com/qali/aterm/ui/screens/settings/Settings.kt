package com.qali.aterm.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
            val installedRootfs = remember { com.qali.aterm.ui.screens.terminal.Rootfs.getInstalledRootfsList() }
            val hasAlpine = installedRootfs.contains("alpine.tar.gz")
            val hasUbuntu = installedRootfs.contains("ubuntu.tar.gz")

            // Android is always available
            SettingsCard(
                title = { Text("Android") },
                description = {Text("aTerm Android shell")},
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
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

            // Alpine - only show if installed
            if (hasAlpine) {
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
            }
            
            // Ubuntu - only show if installed
            if (hasUbuntu) {
                SettingsCard(
                    title = { Text("Ubuntu") },
                    description = {Text("Ubuntu Linux")},
                    startWidget = {
                        RadioButton(
                            modifier = Modifier.padding(start = 8.dp),
                            selected = selectedOption == WorkingMode.UBUNTU,
                            onClick = {
                                selectedOption = WorkingMode.UBUNTU
                                Settings.working_Mode = selectedOption
                            })
                    },
                    onClick = {
                        selectedOption = WorkingMode.UBUNTU
                        Settings.working_Mode = selectedOption
                    })
            }
            
            // Show message if no rootfs is installed
            if (!hasAlpine && !hasUbuntu) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "No rootfs installed. Install one from Rootfs Management below.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
            
            SettingsCard(
                title = { Text("Dark Theme") },
                description = { 
                    Text(
                        when (com.rk.settings.Settings.default_night_mode) {
                            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> "Dark theme enabled"
                            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> "Light theme enabled"
                            else -> "Follow system theme"
                        }
                    )
                },
                startWidget = {
                    Switch(
                        checked = com.rk.settings.Settings.default_night_mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
                        onCheckedChange = {
                            com.rk.settings.Settings.default_night_mode = if (it) {
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                            } else {
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                            }
                            // Restart activity to apply theme
                            mainActivity.recreate()
                        }
                    )
                },
                onClick = {
                    val newMode = if (com.rk.settings.Settings.default_night_mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    } else {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    }
                    com.rk.settings.Settings.default_night_mode = newMode
                    mainActivity.recreate()
                }
            )
        }
        
        // API Provider Settings
        ApiProviderSettings()
        
        // Ollama Settings
        OllamaSettings()
        
        // Rootfs Settings
        RootfsSettings(mainActivity = mainActivity, navController = navController)
    }
}