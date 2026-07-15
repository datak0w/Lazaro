package io.lazaro.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.lazaro.voice.VoiceState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceOrb(
    voiceState: VoiceState,
    isRunning: Boolean,
    audioLevel: Float,
    standby: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val orbColor = MaterialTheme.colorScheme.onBackground
    val isListening = isRunning && voiceState == VoiceState.Listening

    val targetLevel = when {
        !isRunning -> 0f
        isListening -> audioLevel
        voiceState == VoiceState.Speaking -> 0.45f
        voiceState == VoiceState.Processing -> 0.25f
        standby -> 0.05f
        else -> 0.08f
    }

    val smoothedLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(durationMillis = 90),
        label = "audioLevelSmooth",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orbMotion")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (voiceState) {
                    VoiceState.Speaking -> 700
                    VoiceState.Processing -> 1100
                    VoiceState.Listening -> 1400
                    else -> 2000
                },
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "corePulse",
    )

    Box(
        modifier = modifier
            .size(220.dp)
            .semantics {
                contentDescription = when (voiceState) {
                    VoiceState.Listening -> "Escuchando"
                    VoiceState.Processing -> "Pensando"
                    VoiceState.Speaking -> "Hablando"
                    VoiceState.Error -> "Error"
                    VoiceState.Idle -> if (isRunning) {
                        if (standby) "Listo" else "Activo"
                    } else {
                        "Detenido"
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.22f * pulse
            val activeLevel = smoothedLevel.coerceIn(0f, 1f)

            for (layer in 3 downTo 1) {
                val expansion = (18f + layer * 14f) * (0.35f + activeLevel * 0.65f)
                drawCircle(
                    color = orbColor.copy(alpha = (0.03f + activeLevel * 0.09f) / layer),
                    radius = baseRadius + expansion,
                    center = center,
                )
            }

            val barCount = 48
            val phaseRad = phase * 2f * PI.toFloat()
            for (index in 0 until barCount) {
                val angle = (index.toFloat() / barCount) * 2f * PI.toFloat()
                val wave = sin(angle * 5f + phaseRad) * 0.12f
                val barLevel = (activeLevel * 0.85f + wave + 0.08f).coerceIn(0.05f, 1f)
                val innerRadius = baseRadius + 10f
                val outerRadius = innerRadius + 6f + barLevel * 42f

                val start = Offset(
                    center.x + cos(angle) * innerRadius,
                    center.y + sin(angle) * innerRadius,
                )
                val end = Offset(
                    center.x + cos(angle) * outerRadius,
                    center.y + sin(angle) * outerRadius,
                )

                drawLine(
                    color = orbColor.copy(alpha = 0.12f + barLevel * 0.55f),
                    start = start,
                    end = end,
                    strokeWidth = 2.8f,
                    cap = StrokeCap.Round,
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.22f + activeLevel * 0.18f),
                        orbColor.copy(alpha = 0.06f + activeLevel * 0.08f),
                    ),
                    center = center,
                    radius = baseRadius,
                ),
                radius = baseRadius,
                center = center,
            )

            drawCircle(
                color = orbColor.copy(alpha = 0.35f + activeLevel * 0.25f),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 1.2f),
            )

            drawCircle(
                color = orbColor.copy(alpha = 0.85f),
                radius = baseRadius * 0.12f,
                center = center,
            )
        }
    }
}
