package io.lazaro.pathguide

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRotationTracker @Inject constructor(
    @ApplicationContext context: Context,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val absoluteRotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationSensor =
        absoluteRotationSensor
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val hasAbsoluteNorth: Boolean = absoluteRotationSensor != null

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var currentYawRad = 0f
    private var baselineYawRad = 0f
    private var hasBaseline = false
    private var listening = false
    private var lastYawSampleRad = 0f
    private var lastYawSampleMs = 0L
    private var yawRateDegPerSec = 0f

    val isAvailable: Boolean
        get() = rotationSensor != null

    fun start() {
        if (listening || rotationSensor == null) return
        sensorManager.registerListener(
            this,
            rotationSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        listening = true
        lastYawSampleMs = 0L
        yawRateDegPerSec = 0f
    }

    fun stop() {
        if (!listening) return
        sensorManager.unregisterListener(this)
        listening = false
        hasBaseline = false
        lastYawSampleMs = 0L
        yawRateDegPerSec = 0f
    }

    fun markBaseline() {
        baselineYawRad = currentYawRad
        hasBaseline = true
    }

    fun clearBaseline() {
        hasBaseline = false
    }

    fun yawDeltaDeg(): Float {
        if (!hasBaseline) return 0f
        val deltaRad = normalizeRadians(currentYawRad - baselineYawRad)
        return Math.toDegrees(deltaRad.toDouble()).toFloat()
    }

    fun currentYawDeg(): Float {
        return Math.toDegrees(currentYawRad.toDouble()).toFloat()
    }

    /**
     * Rumbo absoluto 0–360° (norte magnético) si el sensor es ROTATION_VECTOR.
     * GAME_ROTATION_VECTOR no tiene referencia a norte → null.
     */
    fun compassHeadingDeg(): Float? {
        if (!hasAbsoluteNorth) return null
        if (!listening) return null
        var deg = Math.toDegrees(currentYawRad.toDouble()).toFloat()
        deg = (deg + 360f) % 360f
        return deg
    }

    /** Velocidad de giro en °/s (EMA). Positivo/negativo según yaw del sensor. */
    fun yawRateDegPerSec(): Float = yawRateDegPerSec

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        currentYawRad = orientation[0]
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastYawSampleMs > 0L) {
            val dtSec = (now - lastYawSampleMs) / 1000f
            if (dtSec in 0.008f..0.25f) {
                val deltaDeg = Math.toDegrees(
                    normalizeRadians(currentYawRad - lastYawSampleRad).toDouble(),
                ).toFloat()
                val instant = deltaDeg / dtSec
                yawRateDegPerSec = yawRateDegPerSec * 0.72f + instant * 0.28f
            }
        }
        lastYawSampleRad = currentYawRad
        lastYawSampleMs = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun normalizeRadians(angle: Float): Float {
        var a = angle
        while (a > Math.PI) a -= (2 * Math.PI).toFloat()
        while (a < -Math.PI) a += (2 * Math.PI).toFloat()
        return a
    }
}

object VisualTurnEstimator {
    private const val CAMERA_HFOV_DEG = 62f

    fun doorwayOffsetDegrees(door: DoorwayState): Float {
        val offset = door.centerNorm - 0.5f
        val perspectiveScale = 1f - door.approachFactor * 0.55f
        return offset * CAMERA_HFOV_DEG * perspectiveScale
    }

    fun corridorOffsetDegrees(corridor: CorridorState): Float {
        val balance = corridor.rightProximity - corridor.leftProximity
        return balance * 18f
    }
}
