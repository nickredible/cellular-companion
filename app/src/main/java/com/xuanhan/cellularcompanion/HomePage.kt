package com.xuanhan.cellularcompanion

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.xuanhan.cellularcompanion.destinations.AboutDestination
import com.xuanhan.cellularcompanion.destinations.ChangeHotspotCredentialsDestination
import com.xuanhan.cellularcompanion.models.BluetoothModel
import com.xuanhan.cellularcompanion.models.BluetoothModel.Companion.ConnectStatus
import com.xuanhan.cellularcompanion.utilities.NoRippleTheme
import com.xuanhan.cellularcompanion.viewmodels.HomePageViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Destination
fun HomePage(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val viewModel = remember { HomePageViewModel(context, navigator) }

    val isPressed by interactionSource.collectIsPressedAsState()
    val connectStatus by bluetoothModel.connectStatus.collectAsState()
    val isAwaitingUserHotspot by bluetoothModel.isAwaitingUserHotspot.collectAsState()
    val isShowingConfirmUnlinkDialog by viewModel.isShowingConfirmUnlinkDialog.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    @Composable
    fun ConfirmUnlinkDialog() {
        AlertDialog(
            onDismissRequest = {
                viewModel.hideConfirmUnlinkDialog()
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmConfirmUnlinkDialog()
                }) {
                    Text(text = "Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.hideConfirmUnlinkDialog()
                }) {
                    Text(text = "Back")
                }
            },
            title = {
                Text(text = "Confirm unlink Mac?")
            },
            text = {
                Text(text = "This will disconnect your phone from your Mac and you will need to go through setup again to reconnect.")
            }
        )
    }

    Scaffold {
        if (isShowingConfirmUnlinkDialog)
            ConfirmUnlinkDialog()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(it)
                .padding(0.dp, 24.dp, 0.dp, 32.dp)
                .fillMaxWidth()
        ) {
            if (isLoading) {
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Unlinking Mac...")
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Text(
                    when (connectStatus) {
                        ConnectStatus.Idle -> "Idle"
                        ConnectStatus.Disconnected -> "Disconnected"
                        ConnectStatus.Connecting -> "Connecting"
                        ConnectStatus.Connected -> "Connected"
                    }
                )
                if (isAwaitingUserHotspot) {
                    // Android 16+ doesn't let apps turn on the hotspot, so the user has to
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Turn on your hotspot to connect your Mac.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = {
                        context.startActivity(BluetoothModel.hotspotSettingsIntent())
                    }) {
                        Text("Open hotspot settings")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                CompositionLocalProvider(LocalRippleTheme provides NoRippleTheme) {
                    val borderColor = when (connectStatus) {
                        ConnectStatus.Idle, ConnectStatus.Connecting, ConnectStatus.Disconnected -> if (isSystemInDarkTheme()) Color(
                            0xFF505050
                        ) else Color(0xFFD6D6D6)

                        ConnectStatus.Connected -> if (isSystemInDarkTheme()) Color(0xFF5C8C54) else Color(
                            0xFF8FD684
                        )
                    }.copy(alpha = if (isPressed) 0.8f else 1f)
                    val containerColor = when (connectStatus) {
                        ConnectStatus.Idle, ConnectStatus.Connecting, ConnectStatus.Disconnected -> if (isSystemInDarkTheme()) Color(
                            0xFF313131
                        ) else Color(0xFFF0F0F0)

                        ConnectStatus.Connected -> if (isSystemInDarkTheme()) Color(0xFF292F28) else Color(
                            0xFFDCF4D6
                        )
                    }.copy(alpha = if (isPressed) 0.8f else 1f)
                    val contentColor = when (connectStatus) {
                        ConnectStatus.Idle, ConnectStatus.Connecting, ConnectStatus.Disconnected -> Color(
                            0xFF848484
                        )

                        ConnectStatus.Connected -> if (isSystemInDarkTheme()) Color(0xFF35D11B) else Color(
                            0xFF1C850B
                        )
                    }.copy(alpha = if (isPressed) 0.8f else 1f)

                    IconButton(
                        onClick = {
                            if (connectStatus == ConnectStatus.Idle) {
                                viewModel.enableHotspot()
                            } else {
                                viewModel.disableHotspot()
                            }
                        },
                        modifier = Modifier
                            .size(200.dp)
                            .border(
                                4.dp,
                                color = borderColor,
                                shape = CircleShape
                            )
                            .clip(CircleShape),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                        ),
                        interactionSource = interactionSource,
                        enabled = connectStatus != ConnectStatus.Disconnected
                    ) {
                        if (connectStatus == ConnectStatus.Connecting) {
                            CircularProgressIndicator(
                                color = contentColor
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.outline_wifi_tethering_24),
                                contentDescription = "Enable hotspot",
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.outline_settings_24),
                            contentDescription = "Settings icon"
                        )
                    },
                    headlineText = { Text("Configure hotspot credentials") },
                    modifier = Modifier.clickable {
                        navigator.navigate(ChangeHotspotCredentialsDestination)
                    }
                )
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.outline_info_24),
                            contentDescription = "Information icon"
                        )
                    },
                    headlineText = { Text("About") },
                    modifier = Modifier.clickable {
                        navigator.navigate(AboutDestination)
                    }
                )
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.outline_link_off_24),
                            contentDescription = "Unlink icon",
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    headlineText = { Text("Unlink Mac", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        viewModel.showConfirmUnlinkDialog()
                    }
                )
            }
        }
    }
}