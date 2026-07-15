package io.lazaro.ui.cane

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lazaro.R
import io.lazaro.cane.ScannedCaneDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaneSetupWizardScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: CaneSetupWizardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) onComplete()
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val ok = results.values.all { it }
        if (ok) viewModel.onPermissionsGranted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.cane_wizard_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (uiState.step) {
                WizardStep.PERMISSIONS -> {
                    Text(
                        stringResource(R.string.cane_wizard_permissions),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                btPermissionLauncher.launch(viewModel.bluetoothPermissions())
                            } else {
                                viewModel.onPermissionsGranted()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cane_wizard_next))
                    }
                    OutlinedButton(
                        onClick = { viewModel.skipWizard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cane_wizard_skip))
                    }
                }
                WizardStep.SCAN -> {
                    Text(
                        stringResource(R.string.cane_wizard_scan),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.scannedDevices, key = { it.address }) { device ->
                            DeviceCard(device) { viewModel.selectDevice(device) }
                        }
                    }
                    if (uiState.scannedDevices.isEmpty()) {
                        Text(
                            "No se encontró ningún WeWALK. Acerca el bastón y espera unos segundos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                WizardStep.CONNECTED, WizardStep.LEARN -> {
                    Text(
                        stringResource(R.string.cane_wizard_connect),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    uiState.connection.deviceName?.let { name ->
                        Text("Dispositivo: $name", style = MaterialTheme.typography.bodyMedium)
                    }
                    uiState.connection.batteryPercent?.let { pct ->
                        Text(
                            stringResource(R.string.cane_battery, pct),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (uiState.step == WizardStep.LEARN) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.cane_wizard_learn),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (uiState.learnMessage.isNotBlank()) {
                            Text(uiState.learnMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (uiState.learnTimedOut) {
                            Text(
                                stringResource(R.string.cane_wizard_learn_timeout),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = { viewModel.finishWithoutButton() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.cane_wizard_finish))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: ScannedCaneDevice, onSelect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(device.name ?: "WeWALK", style = MaterialTheme.typography.titleMedium)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
            Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall)
        }
    }
}
