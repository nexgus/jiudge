package io.github.nexgus.jiudge.core.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compass-derived facing direction, read from the fused [Sensor.TYPE_ROTATION_VECTOR] sensor while
 * the map is visible. Exposes the azimuth (degrees clockwise from north) the top of a flat-held
 * phone points at, which the overlay draws as the OruxMaps-style view cone.
 *
 * [hasCompass] is false on the (rare) devices without a rotation-vector sensor; the caller then
 * falls back to the GPS movement bearing. Heading is relative to magnetic north - the small Taiwan
 * declination (~4 deg W) is not corrected in v1.
 */
class HeadingProvider(
    context: Context,
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    /** Whether this device can report a compass heading at all. */
    val hasCompass: Boolean get() = rotationSensor != null

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        _heading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthDeg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
        _heading.value = smooth(_heading.value, azimuthDeg)
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    /**
     * Low-pass filter to calm raw-sensor jitter, taking the shortest angular path so the cone does
     * not spin the long way around when the heading crosses 0/360.
     */
    private fun smooth(
        previous: Float?,
        next: Float,
    ): Float {
        if (previous == null) return next
        var delta = next - previous
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return (previous + SMOOTHING * delta + 360f) % 360f
    }

    private companion object {
        const val SMOOTHING = 0.15f
    }
}
