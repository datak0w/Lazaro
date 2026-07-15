package io.lazaro.ui.pathguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lazaro.pathguide.ApproachPhase
import io.lazaro.pathguide.ApproachState
import io.lazaro.pathguide.HandrailSide
import io.lazaro.pathguide.DoorwayGuidePhase
import io.lazaro.pathguide.ExitBrainPhase
import io.lazaro.pathguide.JunctionType
import io.lazaro.pathguide.PathGuideDebugState
import io.lazaro.pathguide.MapsInstructionType
import io.lazaro.pathguide.OutdoorNavPhase
import io.lazaro.pathguide.PathGuideRoi
import io.lazaro.pathguide.RoadSide
import io.lazaro.voice.WakeWordStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathGuideDebugScreen(
    onBack: () -> Unit,
    viewModel: PathGuideDebugViewModel = hiltViewModel(),
) {
    val debugState by viewModel.debugState.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val wakeWordStatus by viewModel.wakeWordStatus.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.ensurePreview()
        onDispose { viewModel.onLeave() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Depuración cámara") },
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
                .padding(12.dp),
        ) {
            if (debugState == null) {
                Text(
                    "Esperando cámara… Modo: $mode",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                CameraDebugPreview(state = debugState!!)
                Spacer(modifier = Modifier.height(12.dp))
                DebugMetricsPanel(
                    state = debugState!!,
                    wakeWordStatus = wakeWordStatus,
                    commandDepth = viewModel.microphoneCommandDepth,
                )
            }
        }
    }
}

@Composable
private fun CameraDebugPreview(state: PathGuideDebugState) {
    val bitmap = state.frame
    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(Color.Black),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Vista cámara trasera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scaleX = size.width / state.frameWidth
                val scaleY = size.height / state.frameHeight
                val scale = minOf(scaleX, scaleY)
                val offsetX = (size.width - state.frameWidth * scale) / 2f
                val offsetY = (size.height - state.frameHeight * scale) / 2f

                fun mapX(x: Float) = offsetX + x * state.frameWidth * scale
                fun mapY(y: Float) = offsetY + y * state.frameHeight * scale

                val roiTop = PathGuideRoi.CORRIDOR_TOP
                val roiBottom = PathGuideRoi.CORRIDOR_BOTTOM
                val leftEnd = PathGuideRoi.LEFT_BAND
                val centerEnd = leftEnd + PathGuideRoi.CENTER_BAND

                drawRect(
                    color = Color(0x66FF5252),
                    topLeft = Offset(mapX(0f), mapY(roiTop)),
                    size = Size(
                        mapX(leftEnd) - mapX(0f),
                        mapY(roiBottom) - mapY(roiTop),
                    ),
                )
                drawRect(
                    color = Color(0x664CAF50),
                    topLeft = Offset(mapX(leftEnd), mapY(roiTop)),
                    size = Size(
                        mapX(centerEnd) - mapX(leftEnd),
                        mapY(roiBottom) - mapY(roiTop),
                    ),
                )
                drawRect(
                    color = Color(0x664FC3F7),
                    topLeft = Offset(mapX(centerEnd), mapY(roiTop)),
                    size = Size(
                        mapX(1f) - mapX(centerEnd),
                        mapY(roiBottom) - mapY(roiTop),
                    ),
                )

                drawRect(
                    color = Color(0x88FFEB3B),
                    topLeft = Offset(mapX(PathGuideRoi.STAIR_COL_START), mapY(PathGuideRoi.STAIR_TOP)),
                    size = Size(
                        mapX(PathGuideRoi.STAIR_COL_END) - mapX(PathGuideRoi.STAIR_COL_START),
                        mapY(PathGuideRoi.STAIR_BOTTOM) - mapY(PathGuideRoi.STAIR_TOP),
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )

                drawLine(
                    color = Color.Yellow,
                    start = Offset(mapX(0f), mapY(roiTop)),
                    end = Offset(mapX(1f), mapY(roiTop)),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = Color.Yellow,
                    start = Offset(mapX(0f), mapY(roiBottom)),
                    end = Offset(mapX(1f), mapY(roiBottom)),
                    strokeWidth = 2f,
                )

                if (state.corridor.doorwayActive) {
                    val door = state.corridor.doorway
                    val doorTop = PathGuideRoi.DOORWAY_TOP
                    val doorBottom = PathGuideRoi.DOORWAY_BOTTOM
                    drawLine(
                        color = Color(0xFFFF9800),
                        start = Offset(mapX(door.leftJambNorm), mapY(doorTop)),
                        end = Offset(mapX(door.leftJambNorm), mapY(doorBottom)),
                        strokeWidth = 4f,
                    )
                    drawLine(
                        color = Color(0xFFFF9800),
                        start = Offset(mapX(door.rightJambNorm), mapY(doorTop)),
                        end = Offset(mapX(door.rightJambNorm), mapY(doorBottom)),
                        strokeWidth = 4f,
                    )
                    drawLine(
                        color = Color(0xFF76FF03),
                        start = Offset(mapX(door.centerNorm), mapY(doorTop)),
                        end = Offset(mapX(door.centerNorm), mapY(doorBottom)),
                        strokeWidth = 2f,
                    )
                }

                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 28f
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "L ${(state.corridor.leftProximity * 100).toInt()}%",
                    mapX(0.02f),
                    mapY(roiTop + 0.05f),
                    paint,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "C ${(state.corridor.centerProximity * 100).toInt()}%",
                    mapX(leftEnd + 0.02f),
                    mapY(roiTop + 0.05f),
                    paint,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "R ${(state.corridor.rightProximity * 100).toInt()}%",
                    mapX(centerEnd + 0.02f),
                    mapY(roiTop + 0.05f),
                    paint,
                )
            }
        }
    }
}

@Composable
private fun DebugMetricsPanel(
    state: PathGuideDebugState,
    wakeWordStatus: WakeWordStatus,
    commandDepth: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Text("Modo: ${state.mode}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cerebro: ${brainPhaseLabel(state.brainPhase)} | bifurcación: ${junctionLabel(state.junctionType)}",
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Acercamiento: ${approachLabel(state.approachState)} | velocidad ${"%.2f".format(state.approachState.velocity)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Wake word: ${wakeWordLabel(wakeWordStatus)} | mic depth: $commandDepth",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (state.mode == io.lazaro.pathguide.PathGuideMode.NAVEGACION) {
            Text(
                "Exterior: ${outdoorPhaseLabel(state.outdoorPhase)} | calzada: ${roadSideLabel(state.roadSide)} | seguro: ${roadSideLabel(state.safeSide)} | Maps: ${mapsTypeLabel(state.mapsInstructionType)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text("Izquierda: ${pct(state.corridor.leftProximity)} | Centro: ${pct(state.corridor.centerProximity)} | Derecha: ${pct(state.corridor.rightProximity)}")
        Text("Centrado: ${if (state.corridor.isCentered) "sí" else "no"} | Bloqueo frontal: ${if (state.corridor.isFrontallyBlocked) "sí" else "no"} | severidad ${pct(state.corridor.frontalSeverity)}")
        if (state.corridor.doorwayActive) {
            val door = state.corridor.doorway
            Text(
                "PUERTA: ${doorwayPhaseLabel(state.doorwayPhase)} | confianza ${pct(door.confidence)} | acercamiento ${pct(door.approachFactor)} | marco L ${(door.leftJambNorm * 100).toInt()}% R ${(door.rightJambNorm * 100).toInt()}%",
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text("Puerta: no detectada | fase ${doorwayPhaseLabel(state.doorwayPhase)}")
        }
        Text("Barandilla: desactivada (escaleras fuera de servicio)")
        state.lastLabel?.let { Text("Último objeto ML: $it") }
        state.lastSceneDescription?.let {
            Text("Última escena: $it", style = MaterialTheme.typography.bodySmall)
        }
        if (state.turnRemainingDeg != null || state.turnTurnedDeg != null) {
            Text(
                "Giro IMU: ${state.turnTurnedDeg?.let { "${it.toInt()}° girados" } ?: "—"}" +
                    " | restante ${state.turnRemainingDeg?.let { "${it.toInt()}°" } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "Leyenda: rojo=izq, verde=centro, azul=der, naranja=marcos puerta, verde claro=centro puerta",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun doorwayPhaseLabel(phase: DoorwayGuidePhase): String = when (phase) {
    DoorwayGuidePhase.IDLE -> "inactivo"
    DoorwayGuidePhase.APPROACHING -> "detectando"
    DoorwayGuidePhase.TURN_LEFT -> "girar izquierda"
    DoorwayGuidePhase.TURN_RIGHT -> "girar derecha"
    DoorwayGuidePhase.ALIGNING -> "alineando"
    DoorwayGuidePhase.CENTERED -> "centrado"
    DoorwayGuidePhase.PASSING -> "atravesando"
}

private fun pct(value: Float): String = "${(value * 100).toInt()}%"

private fun handrailLabel(side: HandrailSide): String = when (side) {
    HandrailSide.LEFT -> "izquierda"
    HandrailSide.RIGHT -> "derecha"
    HandrailSide.NONE -> "sin barandilla"
    HandrailSide.UNKNOWN -> "desconocida"
}

private fun brainPhaseLabel(phase: ExitBrainPhase): String = when (phase) {
    ExitBrainPhase.EXPLORE -> "explorando"
    ExitBrainPhase.EXIT_FOUND -> "salida detectada"
    ExitBrainPhase.ALIGN -> "alineando giro"
    ExitBrainPhase.CENTERED -> "centrado"
    ExitBrainPhase.PASS -> "atravesando"
    ExitBrainPhase.BLOCKED -> "obstáculo bloquea salida"
}

private fun junctionLabel(junction: JunctionType): String = when (junction) {
    JunctionType.NONE -> "ninguna"
    JunctionType.T_LEFT -> "T izquierda"
    JunctionType.T_RIGHT -> "T derecha"
    JunctionType.T_BOTH -> "T ambos lados"
    JunctionType.DEAD_END -> "sin salida"
}

private fun approachLabel(approach: ApproachState): String = when (approach.phase) {
    ApproachPhase.FAR -> "lejos"
    ApproachPhase.APPROACHING -> "acercándose"
    ApproachPhase.CLOSE -> "cerca"
    ApproachPhase.VERY_CLOSE -> "muy cerca"
}

private fun wakeWordLabel(status: WakeWordStatus): String = when (status) {
    WakeWordStatus.OFF -> "apagada"
    WakeWordStatus.STARTING -> "iniciando"
    WakeWordStatus.ACTIVE -> "activa"
    WakeWordStatus.PAUSED -> "pausada (comando)"
    WakeWordStatus.ERROR -> "error modelo/micrófono"
}

private fun outdoorPhaseLabel(phase: OutdoorNavPhase?): String = when (phase) {
    OutdoorNavPhase.FOLLOW_SIDEWALK -> "acera"
    OutdoorNavPhase.APPROACH_CROSSING -> "buscando cruce"
    OutdoorNavPhase.CROSSING -> "cruzando"
    OutdoorNavPhase.TURN_AT_JUNCTION -> "giro bifurcación"
    OutdoorNavPhase.ARRIVING -> "llegada"
    OutdoorNavPhase.DRIFT_WARNING -> "deriva calzada"
    null -> "—"
}

private fun roadSideLabel(side: RoadSide): String = when (side) {
    RoadSide.LEFT -> "izquierda"
    RoadSide.RIGHT -> "derecha"
    RoadSide.UNKNOWN -> "—"
}

private fun mapsTypeLabel(type: MapsInstructionType): String = when (type) {
    MapsInstructionType.TURN -> "giro"
    MapsInstructionType.STRAIGHT -> "recto"
    MapsInstructionType.CROSS_STREET -> "cruce"
    MapsInstructionType.ROUNDABOUT -> "rotonda"
    MapsInstructionType.ARRIVE -> "llegada"
    MapsInstructionType.OTHER -> "otra"
}
