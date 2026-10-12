package com.xuanhan.cellularcompanion

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ComponentActivity
import androidx.core.content.ContextCompat
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.popUpTo
import com.xuanhan.cellularcompanion.destinations.IntroDestination
import com.xuanhan.cellularcompanion.destinations.SettingUpDestination
import com.xuanhan.cellularcompanion.utilities.QRAnalyzer
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Destination
fun QRCode(navigator: DestinationsNavigator) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text = "Scan the QR code shown") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            navigator.popBackStack()
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.outline_arrow_back_24),
                            contentDescription = "Back button"
                        )
                    }
                },
            )
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(it)
                .padding(0.dp, 0.dp, 0.dp, 32.dp)
        ) {
            Text(
                "Scan the QR code displayed on the Cellular app on your Mac to securely pair with and receive information from this device over Bluetooth.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            PreviewViewComposable(navigator = navigator)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@androidx.camera.core.ExperimentalGetImage
@Composable
fun PreviewViewComposable(navigator: DestinationsNavigator) {
    AndroidView(
        { context ->
            val cameraExecutor = Executors.newSingleThreadExecutor()
            val previewView = PreviewView(context).also {
                it.scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageCapture = ImageCapture.Builder().build()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, QRAnalyzer { text ->
                            // Ignore QR codes that aren't Cellular's, like the download link the Mac shows during setup
                            val data = try {
                                JSONObject(text)
                            } catch (e: JSONException) {
                                println("QR code is not a Cellular pairing code.")
                                return@QRAnalyzer
                            }
                            if (!data.has("serviceUUID") || !data.has("sharedKey")) {
                                println("No usable data found in QR Code.")
                                return@QRAnalyzer
                            }

                            val serviceUUID = data.get("serviceUUID").toString()
                            val sharedKey = data.get("sharedKey").toString()

                            it.clearAnalyzer()
                            cameraProvider.unbindAll()

                            navigator.navigate(SettingUpDestination(serviceUUID, sharedKey)) {
                                popUpTo(IntroDestination) { inclusive = true }
                            }
                        })
                    }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // Unbind use cases before rebinding
                    cameraProvider.unbindAll()

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(
                        context as ComponentActivity,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalyzer
                    )

                } catch (exc: Exception) {
                    println("Use case binding failed: $exc")
                }
            }, ContextCompat.getMainExecutor(context))
            previewView
        },
        modifier = Modifier
            .size(width = 256.dp, height = 455.dp)
    )
}