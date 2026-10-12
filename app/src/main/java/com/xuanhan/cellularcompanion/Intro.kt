package com.xuanhan.cellularcompanion

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.xuanhan.cellularcompanion.destinations.PermissionsDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Destination
fun Intro(navigator: DestinationsNavigator) {
    Scaffold {
        Column(
            horizontalAlignment = CenterHorizontally,
            modifier = Modifier.padding(it).padding(24.dp, 24.dp, 24.dp, 32.dp).fillMaxWidth()
        ) {
            Spacer(modifier = Modifier.weight(1.0f))
            Image(
                painter = painterResource(id = if (isSystemInDarkTheme()) R.drawable.start_vector_night else R.drawable.start_vector_day),
                contentDescription = "Graphic showing device connected to the Internet."
            )
            Spacer(modifier = Modifier.weight(1.0f))
            Text(
                text = "Welcome to Cellular Companion",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Before you start, install Cellular on your Mac. You can download it from github.com/nickredible/cellular.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = {
                navigator.navigate(PermissionsDestination())
            }) {
                Text("Get started")
            }
        }
    }
}