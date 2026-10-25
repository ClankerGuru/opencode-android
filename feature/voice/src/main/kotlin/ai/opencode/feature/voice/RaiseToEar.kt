package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** Proximity covered while held up. Monitoring owns a proximity-only screen-off
 * lock (not a CPU/screen-on lock), released immediately in monitor's finally, just
 * as UIDevice proximity monitoring dims the iOS screen at the ear. */
class RaiseToEarDetector(
    private val context: Context? = null,
    private val clock: Clock = dependency(Clock),
    private val heldUp: (() -> Boolean)? = null,
    private val onNote: (String) -> Unit = { dependency(VoiceCallLogClient).noteCurrent(VoiceCallLog.Note.Kind.marker, it) },
) : SensorEventListener {
    private val manager = context?.getSystemService(SensorManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var onRaise: (() -> Boolean)? = null
    private var onNear: ((Boolean) -> Unit)? = null
    var isNear = false; private set
    var isAtEar = false; private set
    private var started = false
    private var gravity: Gravity? = null
    private var filtered = doubleArrayOf(0.0, 0.0, 0.0)
    private var hasAccelerometer = false
    private var proximityScreen: PowerManager.WakeLock? = null
    data class Gravity(val x: Double, val y: Double, val z: Double)
    @SuppressLint("WakelockTimeout") // Proximity monitor lifetime, released by stop in monitor's finally.
    fun start(onRaise: () -> Boolean, onNear: (Boolean) -> Unit) {
        if (started) return
        this.onRaise = onRaise; this.onNear = onNear
        val proximity = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        started = manager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))
        if (!started) return
        context?.getSystemService(PowerManager::class.java)?.let { power ->
            if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityScreen = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "opencode:raise-to-ear").apply {
                    setReferenceCounted(false); acquire()
                }
            }
        }
        val motion = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (motion != null) manager.registerListener(this, motion, 100000, Handler(Looper.getMainLooper()))
    }
    fun stop() {
        pending?.cancel(); pending = null; manager?.unregisterListener(this); started = false; gravity = null; hasAccelerometer = false
        proximityScreen?.let { if (it.isHeld) it.release() }; proximityScreen = null
        if (isAtEar) onNear?.invoke(false)
        isAtEar = false; isNear = false; onRaise = null; onNear = null
        scope.cancel()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            proximityChanged(event.values[0] < event.sensor.maximumRange && event.values[0] < 5f)
        } else {
            for (i in 0..2) {
                val value = event.values[i].toDouble()
                filtered[i] = if (event.sensor.type == Sensor.TYPE_GRAVITY || !hasAccelerometer) value else filtered[i] * 0.8 + value * 0.2
            }
            hasAccelerometer = true
            // CoreMotion gravity points down; Android's gravity sensor reports the
            // acceleration opposing gravity. Negate and normalize to units of g.
            gravity = Gravity(-filtered[0] / SensorManager.GRAVITY_EARTH, -filtered[1] / SensorManager.GRAVITY_EARTH, -filtered[2] / SensorManager.GRAVITY_EARTH)
        }
    }
    fun proximityChanged(near: Boolean) {
        if (near == isNear) return
        isNear = near; val hadPending = pending != null; pending?.cancel(); pending = null
        if (near) {
            if (isAtEar) { if (hadPending) onNote("Cancelled lower (proximity re-covered at ear)"); return }
            if (!isHeldUp(true)) return
            pending = scope.launch { clock.sleep(dwell); completeRaise() }
        } else {
            if (!isAtEar) return
            val maxPolls = ((lowerCap - lowerDwell) / motionPoll).roundToInt().coerceAtLeast(0)
            pending = scope.launch {
                clock.sleep(lowerDwell)
                var polls = 0
                while (polls < maxPolls) {
                    if (isNear || !isAtEar) return@launch
                    if (!isHeldUp(false)) break
                    polls++; clock.sleep(motionPoll)
                }
                completeLower(polls >= maxPolls)
            }
        }
    }
    private fun completeRaise() {
        if (!isNear || !isHeldUp(true)) return
        isAtEar = true; pending = null; onNear?.invoke(true)
        if (onRaise?.invoke() != true) isAtEar = false
    }
    private fun completeLower(reachedCap: Boolean) {
        if (isNear || !isAtEar) return
        val capped = reachedCap && isHeldUp(false); isAtEar = false; pending = null
        val duration = if (capped) lowerCap else lowerDwell
        onNote("Debounced lower (${duration.inWholeMilliseconds} ms clear${if (capped) " cap reached" else ", phone lowered"})")
        onNear?.invoke(false)
    }
    private fun isHeldUp(default: Boolean) = heldUp?.invoke() ?: gravity?.let(::isHeldUp) ?: default
    companion object {
        val dwell = 350.milliseconds; val lowerDwell = 800.milliseconds; val lowerCap = 2500.milliseconds; val motionPoll = 100.milliseconds
        fun isSupported(context: Context) = context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        fun isHeldUp(gravity: Gravity) = abs(gravity.z) < 0.75 && (gravity.y < -0.35 || (abs(gravity.x) > 0.55 && gravity.y < -0.15))
    }
}
class RaiseToEarClient(val isSupported: () -> Boolean, val monitor: suspend (() -> Boolean, (Boolean) -> Unit) -> Unit) {
    companion object Key : DependencyKey<RaiseToEarClient> {
        override val liveValue by lazy {
            val context = dependency(AppContext)
            RaiseToEarClient({ RaiseToEarDetector.isSupported(context) }, { onRaise, onNear ->
                withContext(Dispatchers.Main.immediate) {
                    val detector = RaiseToEarDetector(context)
                    try { detector.start(onRaise, onNear); awaitCancellation() } finally { detector.stop() }
                }
            })
        }
        override val testValue = RaiseToEarClient({ false }, { _, _ -> })
    }
}
class VoiceAtEarBox(initial: Boolean = false) {
    @Volatile var current = initial
    override fun equals(other: Any?) = other is VoiceAtEarBox
    override fun hashCode() = 0
}
