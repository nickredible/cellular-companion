package com.xuanhan.cellularcompanion

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.xuanhan.cellularcompanion.models.UpdateModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination
fun About(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    val sourceIntent = remember { Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nickredible/cellular-companion")) }
    val macAppIntent = remember { Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nickredible/cellular/releases")) }
    val coroutineScope = rememberCoroutineScope()
    var isCheckingForUpdates by remember { mutableStateOf(UpdateModel.isCheckingEnabled(context)) }

    fun setCheckingForUpdates(isEnabled: Boolean) {
        isCheckingForUpdates = isEnabled
        UpdateModel.setCheckingEnabled(context, isEnabled)
        if (isEnabled) {
            coroutineScope.launch { UpdateModel.checkForUpdates(context) }
        }
    }

    Scaffold(topBar = {
        LargeTopAppBar(
            title = { Text(text = "About") },
            navigationIcon = {
                IconButton(onClick = {
                    navigator.popBackStack()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_arrow_back_24),
                        contentDescription = "Back button"
                    )
                }
            },
        )
    }) {
        Column(
            horizontalAlignment = CenterHorizontally,
            modifier = Modifier
                .padding(it)
                .padding(0.dp, 0.dp, 0.dp, 32.dp)
                .fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "v$versionName",
                    style = MaterialTheme.typography.displayLarge,
                )
                Spacer(modifier = Modifier.height(48.dp))
                Text("Cellular Companion was created by Xuan Han Tan. This fork is by nickredible.\n\n© 2023 - 2024 Xuan Han Tan\n© 2026 nickredible\n\nThis app is free software under the GNU General Public License v3, and comes with no warranty.")
                Spacer(modifier = Modifier.height(16.dp))
            }
            ListItem(
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_language_24),
                        contentDescription = "Website icon",
                    )
                },
                headlineText = {
                    Text(
                        "View source code",
                    )
                },
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(sourceIntent)
                    } catch (e: ActivityNotFoundException) {
                        println("Failed to start source code activity: $e")
                    }
                }
            )
            ListItem(
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_language_24),
                        contentDescription = "Website icon",
                    )
                },
                headlineText = {
                    Text(
                        "Get Cellular for Mac",
                    )
                },
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(macAppIntent)
                    } catch (e: ActivityNotFoundException) {
                        println("Failed to start Mac app activity: $e")
                    }
                }
            )
            ListItem(
                leadingContent = {
                    Icon(
                        painter = painterResource(id = R.drawable.outline_download_24),
                        contentDescription = "Download icon",
                    )
                },
                headlineText = { Text("Check for updates") },
                supportingText = { Text("Check GitHub once a day for a newer version") },
                trailingContent = {
                    Switch(checked = isCheckingForUpdates, onCheckedChange = { setCheckingForUpdates(it) })
                },
                modifier = Modifier.clickable { setCheckingForUpdates(!isCheckingForUpdates) }
            )
        }
    }
}