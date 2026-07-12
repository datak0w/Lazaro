package io.lazaro.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lazaro.R
import io.lazaro.memory.entity.CustomSkill
import io.lazaro.memory.entity.LocationRecord
import io.lazaro.memory.entity.MemoryEntry
import io.lazaro.messaging.entity.IncomingMessage
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    onBack: () -> Unit,
    viewModel: MemoryManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.memory_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Volver al asistente"
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics { contentDescription = "Gestión de memoria de Lazaro" },
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.statusMessage,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics {
                            contentDescription = "Estado: ${uiState.statusMessage}"
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.permissions_section_title),
                    description = "Permisos necesarios para WhatsApp",
                )
                Spacer(modifier = Modifier.height(8.dp))
                PermissionCard(
                    title = stringResource(R.string.notifications_permission_title),
                    description = if (uiState.notificationAccessEnabled) {
                        stringResource(R.string.notifications_enabled)
                    } else {
                        stringResource(R.string.notifications_disabled)
                    },
                    enabled = uiState.notificationAccessEnabled,
                    buttonLabel = stringResource(R.string.enable_notifications),
                    buttonContentDescription = "Activar acceso a notificaciones de WhatsApp",
                    onOpenSettings = { viewModel.openNotificationSettings() },
                )
                Spacer(modifier = Modifier.height(8.dp))
                PermissionCard(
                    title = stringResource(R.string.accessibility_permission_title),
                    description = if (uiState.accessibilityEnabled) {
                        stringResource(R.string.accessibility_enabled)
                    } else {
                        stringResource(R.string.accessibility_disabled)
                    },
                    enabled = uiState.accessibilityEnabled,
                    buttonLabel = stringResource(R.string.enable_accessibility),
                    buttonContentDescription = "Activar servicio de accesibilidad de Lazaro",
                    onOpenSettings = { viewModel.openAccessibilitySettings() },
                )
            }

            item { SectionHeader("Datos guardados", "${uiState.memories.size} elementos") }
            if (uiState.memories.isEmpty()) {
                item { EmptyText("No hay datos guardados todavía.") }
            } else {
                items(uiState.memories, key = { it.key }) { memory ->
                    MemoryItemCard(
                        title = memory.key,
                        subtitle = memory.value,
                        detail = "Categoría: ${memory.category}",
                        deleteLabel = "Eliminar dato ${memory.key}",
                        onDelete = { viewModel.deleteMemory(memory.key) },
                    )
                }
            }

            item { SectionHeader("Skills personalizados", "${uiState.skills.size} skills") }
            if (uiState.skills.isEmpty()) {
                item { EmptyText("No hay skills personalizados.") }
            } else {
                items(uiState.skills, key = { it.id }) { skill ->
                    SkillItemCard(skill = skill, onDelete = { viewModel.deleteSkill(skill.id) })
                }
            }

            item { SectionHeader("Ubicaciones recientes", "${uiState.locations.size} registros") }
            if (uiState.locations.isEmpty()) {
                item { EmptyText("No hay ubicaciones registradas.") }
            } else {
                items(uiState.locations, key = { it.id }) { location ->
                    LocationItemCard(location = location, onDelete = { viewModel.deleteLocation(location.id) })
                }
            }

            item { SectionHeader("Mensajes recientes", "${uiState.messages.size} mensajes") }
            if (uiState.messages.isEmpty()) {
                item { EmptyText("No hay mensajes capturados.") }
            } else {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageItemCard(message = message, onDelete = { viewModel.deleteMessage(message.id) })
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    enabled: Boolean,
    buttonLabel: String,
    buttonContentDescription: String,
    onOpenSettings: () -> Unit,
) {
    val statusLabel = if (enabled) "Activado" else "Desactivado"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title. $statusLabel. $description" },
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = description)
            if (!enabled) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = buttonContentDescription },
                ) {
                    Text(buttonLabel)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String = title) {
    Text(
        text = title,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics {
            heading()
            contentDescription = "Sección $description"
        },
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { contentDescription = text },
    )
}

@Composable
private fun MemoryItemCard(
    title: String,
    subtitle: String,
    detail: String,
    deleteLabel: String,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title: $subtitle. $detail" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = subtitle)
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteLabel },
            ) {
                Text(stringResource(R.string.delete_item))
            }
        }
    }
}

@Composable
private fun SkillItemCard(skill: CustomSkill, onDelete: () -> Unit) {
    val triggers = skill.triggerPhrases.replace("[", "").replace("]", "").replace("\"", "")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Skill ${skill.name}. Frases: $triggers. Acción: ${skill.actionType}"
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = skill.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = "Frases: $triggers")
            Text(text = "Acción: ${skill.actionType}")
            Text(text = if (skill.confirmed) "Confirmado" else "Pendiente")
            Button(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Eliminar skill ${skill.name}" },
            ) {
                Text(stringResource(R.string.delete_item))
            }
        }
    }
}

@Composable
private fun LocationItemCard(location: LocationRecord, onDelete: () -> Unit) {
    val label = location.label ?: location.address ?: "${location.latitude}, ${location.longitude}"
    val time = DateFormat.getDateTimeInstance().format(Date(location.visitedAt))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Ubicación $label. Fecha $time" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = time)
            Button(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Eliminar ubicación $label" },
            ) {
                Text(stringResource(R.string.delete_item))
            }
        }
    }
}

@Composable
private fun MessageItemCard(message: IncomingMessage, onDelete: () -> Unit) {
    val time = DateFormat.getDateTimeInstance().format(Date(message.timestamp))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${message.appLabel} de ${message.sender}: ${message.text}. $time"
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "${message.appLabel} — ${message.sender}", fontWeight = FontWeight.Bold)
            Text(text = message.text)
            Text(text = time, style = MaterialTheme.typography.bodySmall)
            Text(text = if (message.read) "Leído" else "Sin leer")
            Button(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Eliminar mensaje de ${message.sender}" },
            ) {
                Text(stringResource(R.string.delete_item))
            }
        }
    }
}
