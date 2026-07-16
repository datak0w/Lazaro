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
import io.lazaro.pathguide.DepthGuidanceMode
import io.lazaro.pathguide.PerceptionSource
import io.lazaro.pathguide.RoadSide
import io.lazaro.pathguide.SidewalkAlignment
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

                // Bandas ROI base (debilitadas cuando hay overlay de acera)
                val showStreetOverlay = state.sidewalkAlignment != SidewalkAlignment.UNKNOWN ||
                    state.mode == io.lazaro.pathguide.PathGuideMode.DEBUG ||
                    state.mode == io.lazaro.pathguide.PathGuideMode.NAVEGACION ||
                    state.mode == io.lazaro.pathguide.PathGuideMode.PASEO

                if (!showStreetOverlay) {
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
                }

                if (showStreetOverlay) {
                    drawSidewalkOverlay(
                        state = state,
                        mapX = ::mapX,
                        mapY = ::mapY,
                        roiTop = roiTop,
                        roiBottom = roiBottom,
                    )
                }

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
                    textSize = 26f
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

/**
 * Overlay acera: líneas SAFE/calzada, zona central muerta, círculo de giro.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSidewalkOverlay(
    state: PathGuideDebugState,
    mapX: (Float) -> Float,
    mapY: (Float) -> Float,
    roiTop: Float,
    roiBottom: Float,
) {
    val vanishingX = 0.50f

    // Líneas de acera calculadas (base ROI → horizonte)
    val leftNear = state.sidewalkLeftNorm
    val rightNear = state.sidewalkRightNorm
    val leftFar = vanishingX - (vanishingX - leftNear) * 0.28f
    val rightFar = vanishingX + (rightNear - vanishingX) * 0.28f

    val roadColor = Color(0x55F44336)
    val safeColor = Color(0x554CAF50)
    when (state.safeSide) {
        RoadSide.LEFT -> {
            drawRect(
                color = safeColor,
                topLeft = Offset(mapX(0f), mapY(roiTop)),
                size = Size(mapX(leftNear.coerceAtLeast(0.08f)) - mapX(0f), mapY(roiBottom) - mapY(roiTop)),
            )
            drawRect(
                color = roadColor,
                topLeft = Offset(mapX(rightNear.coerceAtMost(0.92f)), mapY(roiTop)),
                size = Size(mapX(1f) - mapX(rightNear.coerceAtMost(0.92f)), mapY(roiBottom) - mapY(roiTop)),
            )
        }
        RoadSide.RIGHT -> {
            drawRect(
                color = roadColor,
                topLeft = Offset(mapX(0f), mapY(roiTop)),
                size = Size(mapX(leftNear.coerceAtLeast(0.08f)) - mapX(0f), mapY(roiBottom) - mapY(roiTop)),
            )
            drawRect(
                color = safeColor,
                topLeft = Offset(mapX(rightNear.coerceAtMost(0.92f)), mapY(roiTop)),
                size = Size(mapX(1f) - mapX(rightNear.coerceAtMost(0.92f)), mapY(roiBottom) - mapY(roiTop)),
            )
        }
        RoadSide.UNKNOWN -> Unit
    }

    // Línea central del corredor transitable estimado
    val walkCenter = (leftNear + rightNear) * 0.5f
    drawLine(
        color = Color(0xFFFFD54F),
        start = Offset(mapX(walkCenter), mapY(roiBottom)),
        end = Offset(mapX(vanishingX + (walkCenter - vanishingX) * 0.35f), mapY(roiTop)),
        strokeWidth = 4f,
    )

    // Zona SAFE / central (muerta: no pita)
    val safeLeft = (leftNear + (rightNear - leftNear) * 0.28f).coerceIn(0.25f, 0.45f)
    val safeRight = (leftNear + (rightNear - leftNear) * 0.72f).coerceIn(0.55f, 0.75f)
    val centerColor = if (state.inSafeZone) Color(0x884CAF50) else Color(0x55FFC107)
    drawRect(
        color = centerColor,
        topLeft = Offset(mapX(safeLeft), mapY(roiTop)),
        size = Size(mapX(safeRight) - mapX(safeLeft), mapY(roiBottom) - mapY(roiTop)),
    )

    // Líneas de acera (perspectiva) — calculadas
    val lineColor = Color(0xFFE8F5E9)
    val roadLine = Color(0xFFFF8A80)
    drawLine(
        color = if (state.safeSide == RoadSide.LEFT) Color(0xFF69F0AE) else lineColor,
        start = Offset(mapX(leftNear), mapY(roiBottom)),
        end = Offset(mapX(leftFar), mapY(roiTop)),
        strokeWidth = 6f,
    )
    drawLine(
        color = if (state.safeSide == RoadSide.RIGHT) Color(0xFF69F0AE) else lineColor,
        start = Offset(mapX(rightNear), mapY(roiBottom)),
        end = Offset(mapX(rightFar), mapY(roiTop)),
        strokeWidth = 6f,
    )
    if (state.roadSide == RoadSide.RIGHT) {
        drawLine(
            color = roadLine,
            start = Offset(mapX((rightNear + 0.04f).coerceAtMost(0.98f)), mapY(roiBottom)),
            end = Offset(mapX((rightFar + 0.02f).coerceAtMost(0.98f)), mapY(roiTop)),
            strokeWidth = 4f,
        )
    } else if (state.roadSide == RoadSide.LEFT) {
        drawLine(
            color = roadLine,
            start = Offset(mapX((leftNear - 0.04f).coerceAtLeast(0.02f)), mapY(roiBottom)),
            end = Offset(mapX((leftFar - 0.02f).coerceAtLeast(0.02f)), mapY(roiTop)),
            strokeWidth = 4f,
        )
    }

    // Círculo de giro (centro inferior)
    val cx = mapX(0.5f)
    val cy = mapY(roiBottom - 0.02f)
    val radius = (mapX(0.18f) - mapX(0f)).coerceAtLeast(40f)
    drawCircle(
        color = Color(0xAAFFFFFF),
        radius = radius,
        center = Offset(cx, cy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
    )
    val remaining = state.turnRemainingDeg
    if (remaining != null && kotlin.math.abs(remaining) > 3f) {
        val sweep = (-remaining).coerceIn(-120f, 120f)
        drawArc(
            color = Color(0xFFFFEB3B),
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f),
        )
    }

    // Flechas de pitido
    if (state.guideLeftBeep > 0.05f) {
        drawCircle(
            color = Color(0xFFFF5252).copy(alpha = 0.35f + state.guideLeftBeep * 0.5f),
            radius = 18f + state.guideLeftBeep * 22f,
            center = Offset(mapX(0.08f), mapY(0.92f)),
        )
    }
    if (state.guideRightBeep > 0.05f) {
        drawCircle(
            color = Color(0xFF40C4FF).copy(alpha = 0.35f + state.guideRightBeep * 0.5f),
            radius = 18f + state.guideRightBeep * 22f,
            center = Offset(mapX(0.92f), mapY(0.92f)),
        )
    }

    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
    }
    val nc = drawContext.canvas.nativeCanvas
    nc.drawText(
        if (state.inSafeZone) "SAFE ZONE" else "FUERA SAFE",
        mapX(0.40f),
        mapY(roiTop + 0.08f),
        labelPaint,
    )
    val safePaint = android.graphics.Paint(labelPaint).apply {
        color = android.graphics.Color.GREEN
    }
    val roadPaint = android.graphics.Paint(labelPaint).apply {
        color = android.graphics.Color.RED
    }
    nc.drawText(
        "SAFE",
        mapX(if (state.safeSide == RoadSide.RIGHT) 0.70f else 0.05f),
        mapY(0.72f),
        safePaint,
    )
    nc.drawText(
        "ROAD",
        mapX(if (state.roadSide == RoadSide.LEFT) 0.05f else 0.70f),
        mapY(0.72f),
        roadPaint,
    )
    if (remaining != null) {
        val turnPaint = android.graphics.Paint(labelPaint).apply {
            color = android.graphics.Color.YELLOW
        }
        nc.drawText(
            "giro ${remaining.toInt()}°",
            mapX(0.42f),
            mapY(roiBottom + 0.04f),
            turnPaint,
        )
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
        if (state.mode == io.lazaro.pathguide.PathGuideMode.NAVEGACION ||
            state.mode == io.lazaro.pathguide.PathGuideMode.PASEO ||
            state.mode == io.lazaro.pathguide.PathGuideMode.RUTA ||
            state.mode == io.lazaro.pathguide.PathGuideMode.DEBUG
        ) {
            Text(
                "Exterior: ${outdoorPhaseLabel(state.outdoorPhase)} | " +
                    "acera: ${sidewalkAlignLabel(state.sidewalkAlignment)} | " +
                    "calzada: ${roadSideLabel(state.roadSide)} | seguro: ${roadSideLabel(state.safeSide)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Centrado ${(state.centeringScore * 100).toInt()}% | " +
                    "deriva ${(state.driftScore * 100).toInt()}% | " +
                    "offset lateral ${(state.lateralOffsetNorm * 100).toInt()}% | " +
                    "conf. acera ${(state.walkableConfidence * 100).toInt()}% | " +
                    "zona ${if (state.inSafeZone) "SAFE (silencio)" else "fuera (pita)"} | " +
                    "beep L ${(state.guideLeftBeep * 100).toInt()}% R ${(state.guideRightBeep * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.inSafeZone) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (state.odmScore != null || state.onOdmCorridor) {
                Text(
                    "ODM: score ${((state.odmScore ?: 0f) * 100).toInt()}% | " +
                        "along ${state.odmAlongM?.let { "%.0f m".format(it) } ?: "—"} | " +
                        "pendiente ${state.odmGradePct?.let { "%.1f%%".format(it) } ?: "—"} | " +
                        if (state.onOdmCorridor) "en corredor" else "fuera",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                "Hardware: ${state.deviceLabel.ifBlank { "—" }} | " +
                    "modo ${depthModeLabel(state.depthGuidanceMode)} | " +
                    "percepción ${perceptionLabel(state.perceptionSource)} | " +
                    "dist. frontal ${state.frontalDistanceM?.let { "%.1f m".format(it) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Maps: ${mapsTypeLabel(state.mapsInstructionType)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.mode == io.lazaro.pathguide.PathGuideMode.RUTA ||
            state.mode == io.lazaro.pathguide.PathGuideMode.GRABANDO
        ) {
            val conf = state.routeMatchConfidence?.let { "${(it * 100).toInt()}%" } ?: "—"
            val lateral = state.routeLateralOffsetM?.let { "%.1f m".format(it) } ?: "—"
            val replay = if (state.routeInReplaySegment) "sí" else "no"
            Text(
                "Ruta: match $conf | offset lateral $lateral | replay $replay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (state.routeExpectedLeftP != null || state.routeExpectedRightP != null) {
                Text(
                    "Perfil canónico L ${pct(state.routeExpectedLeftP ?: 0f)} | R ${pct(state.routeExpectedRightP ?: 0f)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
    OutdoorNavPhase.DRIFT_WARNING -> "¡deriva!"
    OutdoorNavPhase.ARRIVING -> "llegando"
    null -> "—"
}

private fun sidewalkAlignLabel(alignment: SidewalkAlignment): String = when (alignment) {
    SidewalkAlignment.ON_SIDEWALK -> "en acera"
    SidewalkAlignment.DRIFTING_TO_ROAD -> "deriva a calzada"
    SidewalkAlignment.ON_ROAD -> "¡en calzada!"
    SidewalkAlignment.UNKNOWN -> "—"
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

private fun depthModeLabel(mode: DepthGuidanceMode): String = when (mode) {
    DepthGuidanceMode.MONOCULAR -> "monocular"
    DepthGuidanceMode.LDAF_ONLY -> "LDAF"
    DepthGuidanceMode.ARCORE_DEPTH -> "ARCore depth"
}

private fun perceptionLabel(source: PerceptionSource): String = when (source) {
    PerceptionSource.MONOCULAR -> "monocular"
    PerceptionSource.DEPTH -> "profundidad"
    PerceptionSource.FUSED -> "fusionada"
}
