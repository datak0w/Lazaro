package io.lazaro.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lazaro.R
import io.lazaro.cane.CaneConnectionState
import io.lazaro.cane.CaneHandshakeState
import io.lazaro.cane.ble.WeWalkDevice
import io.lazaro.sensor.PiHubConnectionState
import io.lazaro.ui.theme.LazaroBrandStyle
import io.lazaro.voice.VoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onOpenMemory: () -> Unit = {},
    onOpenCaneWizard: () -> Unit = {},
    onOpenPathGuideDebug: () -> Unit = {},
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val caneState by viewModel.caneState.collectAsStateWithLifecycle()
    val piHubState by viewModel.piHubState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.startAssistant()
        }
    }

    fun requestPermissionsAndStart() {
        if (viewModel.hasCorePermissions()) {
            viewModel.startAssistant()
            return
        }
        permissionLauncher.launch(viewModel.requiredPermissions())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        CaneTopStatusBar(
                            caneState = caneState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (piHubState.deviceAddress != null || piHubState.isConnected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            PiHubTopStatusBar(
                                hubState = piHubState,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onOpenMemory,
                        modifier = Modifier.semantics {
                            contentDescription = "Ajustes y memoria"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = uiState.isServiceRunning) {
                        viewModel.interruptAndListen()
                    }
                    .semantics {
                        contentDescription = "Pantalla principal de Lázaro. Toca para hablar."
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Lázaro",
                    style = LazaroBrandStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        heading()
                        contentDescription = "Lázaro asistente de voz"
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when {
                        uiState.voiceState == VoiceState.Listening ||
                            uiState.voiceState == VoiceState.Processing ||
                            uiState.voiceState == VoiceState.Speaking ->
                            stringResource(R.string.tap_to_speak)
                        uiState.awaitingTrigger && uiState.isServiceRunning ->
                            stringResource(R.string.trigger_hint)
                        else -> stringResource(R.string.tap_to_speak)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(40.dp))

                VoiceOrb(
                    voiceState = uiState.voiceState,
                    isRunning = uiState.isServiceRunning,
                    audioLevel = uiState.audioLevel,
                    standby = uiState.awaitingTrigger && uiState.voiceState == VoiceState.Idle,
                )

                Spacer(modifier = Modifier.height(32.dp))

                StatusBlock(
                    voiceState = uiState.voiceState,
                    statusMessage = uiState.statusMessage,
                    partialTranscript = uiState.partialTranscript,
                    standby = uiState.awaitingTrigger &&
                        uiState.isServiceRunning &&
                        uiState.voiceState == VoiceState.Idle,
                )

                if (!caneState.isConnected && uiState.isServiceRunning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onOpenCaneWizard,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.connect_cane))
                    }
                }

                if (piHubState.isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.requestVisionScan() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        enabled = piHubState.wifiOk && !piHubState.busy,
                    ) {
                        Text(
                            if (piHubState.busy) {
                                stringResource(R.string.hub_scanning)
                            } else {
                                stringResource(R.string.hub_scan_scene)
                            },
                        )
                    }
                    if (!piHubState.wifiOk) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.hub_wifi_hint),
                            color = MaterialTheme.colorScheme.tertiary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (uiState.isServiceRunning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenPathGuideDebug,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Abrir depuración de cámara" },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Depuración cámara (vídeo y bandas)")
                    }
                }

                if (!uiState.hasApiKey) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.api_key_missing),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (uiState.lastResponse.isNotBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    ResponseCard(text = uiState.lastResponse)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (uiState.isServiceRunning) {
                OutlinedButton(
                    onClick = { viewModel.stopAssistant() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "Parar asistente y cerrar escucha" },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.stop_app),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    if (uiState.isServiceRunning) {
                        viewModel.stopAssistant()
                    } else {
                        requestPermissionsAndStart()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics {
                        contentDescription = if (uiState.isServiceRunning) {
                            "Parar asistente"
                        } else {
                            "Iniciar asistente"
                        }
                    },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = if (uiState.isServiceRunning) {
                        stringResource(R.string.stop_assistant)
                    } else {
                        stringResource(R.string.start_assistant)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun CaneTopStatusBar(
    caneState: CaneConnectionState,
    modifier: Modifier = Modifier,
) {
    val deviceLabel = caneState.deviceName ?: stringResource(R.string.cane_notification_title)
    val connected = caneState.isConnected

    val primaryLabel = when {
        connected && caneState.handshakeState == CaneHandshakeState.IN_PROGRESS ->
            stringResource(R.string.cane_status_handshake, deviceLabel)
        connected && caneState.handshakeState == CaneHandshakeState.FAILED ->
            stringResource(R.string.cane_status_handshake_failed, deviceLabel)
        connected && caneState.batteryPercent != null ->
            stringResource(R.string.cane_status_connected, deviceLabel, caneState.batteryPercent!!)
        connected ->
            stringResource(R.string.cane_status_connected_no_battery, deviceLabel)
        caneState.connectionLabel == "Conectando…" ->
            stringResource(R.string.cane_status_connecting, deviceLabel)
        caneState.deviceAddress != null ->
            stringResource(R.string.cane_status_disconnected_top, deviceLabel)
        else ->
            stringResource(R.string.cane_status_no_device)
    }

    val subtitle = when {
        connected && caneState.handshakeDetail != null ->
            caneState.handshakeDetail
        connected && caneState.rssi != null ->
            stringResource(R.string.cane_status_subtitle, WeWalkDevice.MODEL, caneState.rssi!!)
        connected ->
            stringResource(R.string.cane_status_subtitle_no_rssi, WeWalkDevice.MODEL)
        else -> null
    }

    val lastEventLine = when {
        !connected -> null
        caneState.lastEventHex.isNullOrBlank() -> null
        caneState.lastEventLabel == "Batería" -> null
        else -> {
            val channel = caneState.lastEventLabel ?: "BLE"
            val hex = caneState.lastEventHex!!.let { payload ->
                if (payload.length > 20) payload.take(17) + "…" else payload
            }
            stringResource(R.string.cane_status_last_event, channel, hex)
        }
    }

    val batteryColor = when (val pct = caneState.batteryPercent) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        in 0..20 -> MaterialTheme.colorScheme.error
        in 21..40 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics {
                contentDescription = buildString {
                    append("Estado del bastón: $primaryLabel")
                    subtitle?.let { append(". $it") }
                    lastEventLine?.let { append(". Último evento: $it") }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (connected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
            contentDescription = null,
            tint = if (connected) batteryColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = primaryLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (lastEventLine != null) {
                Text(
                    text = lastEventLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
        if (connected && caneState.batteryPercent != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(batteryColor),
            )
        }
    }
}

@Composable
private fun PiHubTopStatusBar(
    hubState: PiHubConnectionState,
    modifier: Modifier = Modifier,
) {
    val distanceLabel = if (hubState.isConnected && hubState.distOk) {
        stringResource(R.string.hub_distance_status, hubState.distanceCm)
    } else if (hubState.isConnected) {
        stringResource(R.string.hub_distance_unavailable)
    } else {
        stringResource(R.string.hub_disconnected_short)
    }

    val visionLabel = when {
        hubState.busy -> stringResource(R.string.hub_scanning)
        hubState.visionSummary.isNotBlank() -> {
            val truncated = if (hubState.visionSummary.length > 40) {
                hubState.visionSummary.take(37) + "…"
            } else {
                hubState.visionSummary
            }
            stringResource(R.string.hub_vision_status, truncated)
        }
        !hubState.wifiOk -> stringResource(R.string.hub_no_wifi)
        hubState.isConnected -> stringResource(R.string.hub_vision_idle)
        else -> ""
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics {
                contentDescription = buildString {
                    append("Sensor LazaroHub: $distanceLabel")
                    if (visionLabel.isNotBlank()) append(". $visionLabel")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = distanceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            if (visionLabel.isNotBlank()) {
                Text(
                    text = visionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(
    voiceState: VoiceState,
    statusMessage: String,
    partialTranscript: String,
    standby: Boolean = false,
) {
    val stateLabel = when (voiceState) {
        VoiceState.Idle -> if (standby) {
            stringResource(R.string.assistant_passive)
        } else {
            stringResource(R.string.assistant_idle)
        }
        VoiceState.Listening -> stringResource(R.string.assistant_listening)
        VoiceState.Processing -> stringResource(R.string.assistant_thinking)
        VoiceState.Speaking -> stringResource(R.string.assistant_speaking)
        VoiceState.Error -> stringResource(R.string.assistant_error)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics {
            contentDescription = "Estado: $stateLabel. $statusMessage"
        },
    ) {
        Text(
            text = stateLabel,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (statusMessage.isNotBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (partialTranscript.isNotBlank()) {
            Text(
                text = stringResource(R.string.you_said, partialTranscript),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ResponseCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp)
            .semantics { contentDescription = "Última respuesta: $text" },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.last_response_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
