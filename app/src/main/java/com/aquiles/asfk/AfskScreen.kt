package com.aquiles.asfk

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope

@Composable
fun AfskScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receiver = remember { AfskReceiver() }
    val state by receiver.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    DisposableEffect(hasPermission) {
        if (hasPermission) receiver.start(scope)
        onDispose { receiver.stop() }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!hasPermission) {
            Text("Microphone required to listen for the AFSK signal")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Allow microphone")
            }
            return@Column
        }

        when (val s = state) {
            is ReceiverState.Listening -> Text("Listening... 🎧")
            is ReceiverState.Capturing -> Text("Signal detected, capturing (${s.elapsedMs} ms)")
            is ReceiverState.Decoded -> {
                Text("Message received:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.text, style = MaterialTheme.typography.headlineSmall)
            }
            is ReceiverState.Error -> Text("Error: ${s.reason}")
        }
    }
}
