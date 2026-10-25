package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Baked iOS callSound.* defaults; no tuning panel on Android. */
object CallSoundTunables {
    const val pitch = 0.0; const val decay = 1.0; const val brightness = 1.0
    const val cutoff = 1800.0; const val attack = 0.025; const val spacing = 1.0
    const val pingRightLevel = -14.997704; const val pingLeftLevel = -14.196395
    const val connectLevel = -6.298899; const val hangUpLevel = -6.372926
    const val muteLevel = -13.881027; const val unmuteLevel = -13.804098
}

/** Breath Ping: Double DSP, Float32 storage and mulberry32 noise match D-128. */
object VoiceCallSynthesizer {
    const val sampleRate = 24000f
    private const val rate = PCM.sampleRate
    const val g4 = 392.0; const val d5 = 587.33; const val g5 = 783.99
    data class Settings(
        val pitch: Double = 1.0, val decay: Double = 1.0, val brightness: Double = 1.0,
        val cutoff: Double = 1800.0, val attack: Double = 0.025, val spacing: Double = 1.0,
        val levels: List<Double> = listOf(-14.997704, -14.196395, -6.298899, -6.372926, -13.881027, -13.804098),
    ) { companion object { val current = Settings() } }
    val chordNotes get() = floatArrayOf((g4 * Settings.current.pitch).toFloat(), (d5 * Settings.current.pitch).toFloat(), (g5 * Settings.current.pitch).toFloat())
    val glideStart get() = (d5 * Settings.current.pitch).toFloat()
    val glideEnd get() = (g4 * Settings.current.pitch).toFloat()
    val glideDuration get() = (0.16 * Settings.current.spacing).toFloat()
    fun pitch(cue: DotScopeSimulation.Cue) = when (cue) {
        DotScopeSimulation.Cue.sweepRight -> (g4 * Settings.current.pitch).toFloat()
        DotScopeSimulation.Cue.sweepLeft -> (d5 * Settings.current.pitch).toFloat()
        DotScopeSimulation.Cue.connected -> chordNotes[0]
        DotScopeSimulation.Cue.ended -> glideStart
    }
    private val cache by lazy { DotScopeSimulation.Cue.entries.associateWith { render(it, Settings.current) } }
    fun samples(cue: DotScopeSimulation.Cue) = cache.getValue(cue)
    fun cueSamples() = cache
    fun endTailWait(samples: FloatArray) = samples.size / rate + 0.08
    fun muteSamples(muted: Boolean, settings: Settings = Settings.current): FloatArray = level(
        tone(if (muted) g4 else d5, 0.28, 0.1, settings, 0.015 * settings.attack / 0.025), settings.levels[if (muted) 4 else 5])
    fun render(cue: DotScopeSimulation.Cue, s: Settings): FloatArray = when (cue) {
        DotScopeSimulation.Cue.sweepRight -> level(tone(g4, 0.4, 0.1, s), s.levels[0])
        DotScopeSimulation.Cue.sweepLeft -> level(tone(d5, 0.4, 0.09, s), s.levels[1])
        DotScopeSimulation.Cue.connected -> {
            val chord = arpeggio(doubleArrayOf(g4, d5, g5), 0.13 * s.spacing) { tone(it, 0.75, 0.12, s) }
            level(FloatArray(chord.size) { tanh(chord[it].toDouble()).toFloat() }, s.levels[2])
        }
        DotScopeSimulation.Cue.ended -> level(arpeggio(doubleArrayOf(d5, g4), 0.16 * s.spacing) { tone(it, 0.6, 0.13, s) }, s.levels[3])
    }
    private fun level(raw: FloatArray, db: Double): FloatArray { val gain = 10.0.pow(db / 20); return FloatArray(raw.size) { (raw[it] * gain).toFloat() } }
    private fun tone(note: Double, duration: Double, amplitude: Double, s: Settings, attack: Double = s.attack): FloatArray {
        val f = note * s.pitch; val length = duration * s.decay; val air = 0.08 * s.brightness
        val count = max(1, (length * rate).roundToInt()); val hush = lowPass(noise(count, 4), 900.0)
        val out = FloatArray(count) { i ->
            val t = i / rate
            val wave = sin(2 * PI * f * t) + air * hush[i] * exp(-t / 0.06)
            val envelope = if (t < attack) amplitude * (0.5 - 0.5 * cos(PI * t / attack))
                else amplitude * 1e-3.pow(((t - attack) / max(1e-3, length - attack)).coerceIn(0.0, 1.0))
            (envelope * wave).toFloat()
        }
        return lowPass(out, s.cutoff)
    }
    private fun arpeggio(notes: DoubleArray, stagger: Double, voice: (Double) -> FloatArray): FloatArray {
        var mix = FloatArray(1)
        for ((index, note) in notes.withIndex()) {
            val part = voice(note); val offset = (index * stagger * rate).roundToInt()
            if (mix.size < offset + part.size) mix = mix.copyOf(offset + part.size)
            for (i in part.indices) mix[offset + i] += part[i]
        }
        return FloatArray(mix.size) { tanh(mix[it].toDouble()).toFloat() }
    }
    private fun noise(count: Int, seed: Int): FloatArray {
        var a = seed
        return FloatArray(count) {
            a += 0x6D2B79F5
            var t = a; t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
            (((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0 * 2 - 1).toFloat()
        }
    }
    private fun lowPass(input: FloatArray, cutoff: Double): FloatArray {
        if (input.size <= 2) return input
        val w0 = 2 * PI * min(cutoff, rate * 0.45) / rate
        val cosW0 = cos(w0); val alpha = sin(w0) / (2 * 0.70710678); val a0 = 1 + alpha
        val b0 = ((1 - cosW0) * 0.5) / a0; val b1 = (1 - cosW0) / a0; val b2 = b0
        val a1 = (-2 * cosW0) / a0; val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(input.size) { i ->
            val x0 = input[i].toDouble(); val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0; y0.toFloat()
        }
    }
    fun wavData(from: FloatArray): ByteArray = ByteBuffer.allocate(44 + from.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + from.size * 2); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(sampleRate.toInt()); putInt(sampleRate.toInt() * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(from.size * 2)
        for (s in from) putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
    }.array()
    fun buffer(from: FloatArray) = from.takeIf { it.isNotEmpty() }
}

/** One call-scoped clock shared by the sound scheduler, haptics and scanner. */
class VoiceCallSounds(private val clock: Clock = dependency(Clock), private val haptics: VoiceHapticPlaying = dependency(VoiceHapticEngine)) {
    @Serializable data class ScheduledCue(val cue: DotScopeSimulation.Cue, val time: Double)
    @Serializable data class MeterSample(val time: Double, val rms: Float, val peak: Float)
    @Serializable data class RecordedHaptic(val haptic: VoiceCallHaptic, val time: Double)
    @Serializable data class PlaybackTelemetry(val buffers: Int, val underruns: Int, val renderedGaps: Int, val renderedGapMilliseconds: Int)
    @Serializable data class TelemetrySnapshot(val scheduledCues: List<ScheduledCue>, val meterSamples: List<MeterSample>, val hapticEvents: List<RecordedHaptic> = emptyList(), val playback: PlaybackTelemetry? = null)
    data class FirstCue(val cue: DotScopeSimulation.Cue, val milliseconds: Int, val audioReadyMilliseconds: Int) {
        val logLine get() = "Call cue: first ${cue.name} heard $milliseconds ms after the tap (audio up at $audioReadyMilliseconds ms)"
    }
    data class EndingShape(val drawn: Boolean, val origin: Float, val overshoot: Float, val width: Float)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var callID: UUID? = null
    private var startedAt = clock.now()
    private var stage: DotScopeSimulation.Stage? = null
    private var stageStartedAt = clock.now()
    private var scheduler = DotScopeSimulation.SoundScheduler()
    private var playHandler: ((FloatArray) -> Unit)? = null
    private var ringTask: Job? = null
    private val scheduledCues = mutableListOf<ScheduledCue>()
    private val meterSamples = mutableListOf<MeterSample>()
    private val hapticEvents = mutableListOf<RecordedHaptic>()
    private var tappedAt: Duration? = null
    private var cueLatency = 0.0
    private var scopeWidth = 400f
    private var scheduledTickPass: Int? = null
    private var hangUpHapticAt: Duration? = null
    private var playback: PlaybackTelemetry? = null
    private var audioReadyAfter: Double? = null
    private var reportFirstCue: ((FirstCue) -> Unit)? = null
    private var lastCancel: Duration? = null
    private var fallbackHangUp: Job? = null
    private val fallbackTicks = mutableListOf<Job>()
    val isTelemetryEnabled get() = !VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_SOUND_LOG").isNullOrEmpty()
    fun snapshot() = synchronized(lock) { TelemetrySnapshot(scheduledCues.toList(), meterSamples.toList(), hapticEvents.toList(), playback) }
    fun recordPlayback(playback: PlaybackTelemetry) = synchronized(lock) { this.playback = playback }
    fun recordHaptic(haptic: VoiceCallHaptic) {
        val now = clock.now()
        synchronized(lock) {
            when (haptic) {
                VoiceCallHaptic.callStarted -> { tappedAt = now; if (callID == null) { stage = DotScopeSimulation.Stage.connecting; stageStartedAt = now; hangUpHapticAt = null } }
                VoiceCallHaptic.ended -> { hangUpHapticAt = now; tappedAt = null; if (callID == null) { stage = DotScopeSimulation.Stage.ending; stageStartedAt = now } }
                else -> Unit
            }
            hapticEvents.add(RecordedHaptic(haptic, max(0.0, seconds(now - startedAt))))
        }
        flushDebugLogAsync()
    }
    /** Reducer's endPressed uses this at the press, then voice(ended) at the
     * landing. endTapped (agent/notification) uses only voice(ended). */
    fun cancelPressed() {
        synchronized(lock) { lastCancel = clock.now() }
        dependency(HapticsClient).play(Haptic.cancel)
    }
    fun voice(haptic: VoiceCallHaptic) = synchronized(lock) {
        val ending = if (haptic == VoiceCallHaptic.ended) endingShape() else null
        recordHaptic(haptic)
        if (haptic == VoiceCallHaptic.callStarted) { fallbackHangUp?.cancel(); fallbackHangUp = null }
        when (haptic) {
            VoiceCallHaptic.ended -> ending?.let { voiceHangUp(it.drawn, it.origin, it.overshoot, it.width, clock.now()) }
            VoiceCallHaptic.muted, VoiceCallHaptic.unmuted -> {
                val pattern = VoiceHapticDesign.control(haptic)!!
                if (!haptics.isCallActive || !haptics.play(pattern, clock.now())) fallback(haptic)
            }
            else -> fallback(haptic)
        }
    }
    private fun fallback(haptic: VoiceCallHaptic) { dependency(HapticsClient).play(haptic.fallback) }
    private fun cancelVoiceTicks() {
        haptics.cancel(VoiceHapticEngine.Kind.tick)
        fallbackTicks.forEach { it.cancel() }; fallbackTicks.clear()
    }
    private fun voiceTick(cue: DotScopeSimulation.Cue, at: Duration, audioDelay: Double) {
        if (!ticksAreDue) return
        val pattern = VoiceHapticDesign.tick(cue, audioDelay)
        if (haptics.play(pattern, at, VoiceHapticEngine.Kind.tick)) return
        val due = at + (pattern.transients.firstOrNull() ?: 0.0).seconds
        fallbackTicks.removeAll { it.isCompleted }
        fallbackTicks.add(scope.launch {
            clock.sleep(maxOf(Duration.ZERO, due - clock.now()))
            if (ticksAreDue) fallback(VoiceCallHaptic.sweepPass)
        })
    }
    private fun voiceHangUp(drawn: Boolean, origin: Float, overshoot: Float, width: Float, at: Duration): Boolean {
        cancelVoiceTicks()
        val pattern = VoiceHapticDesign.hangUp(drawn, origin, overshoot, width)
        val due = at + (pattern.transients.firstOrNull() ?: 0.0).seconds
        val press = lastCancel; val pressed = press != null && at - press < 1.seconds
        if (pressed && due - press < VoiceHapticDesign.minimumGap.seconds) return false
        if (haptics.isCallActive && haptics.play(pattern, at)) return true
        if (pressed) return false // The fallback would repeat the very same cancel.
        fallbackHangUp?.cancel()
        fallbackHangUp = scope.launch { clock.sleep(maxOf(Duration.ZERO, due - clock.now())); fallback(VoiceCallHaptic.ended) }
        return false
    }
    val sinceCallStart get() = synchronized(lock) { clock.now() - startedAt }
    val ticksAreDue get() = synchronized(lock) { callID != null && stage == DotScopeSimulation.Stage.connecting && hangUpHapticAt == null }
    fun noteScopeWidth(width: Float) { if (width > 0) synchronized(lock) { scopeWidth = width } }
    fun endingShape(): EndingShape = synchronized(lock) {
        val elapsed = stageElapsed(DotScopeSimulation.Stage.connecting) ?: return@synchronized EndingShape(true, 0f, 0f, scopeWidth)
        val beam = DotScopeSimulation.Scan.connectingBeam(elapsed.toFloat())
        EndingShape(false, beam.progress, beam.overshoot, scopeWidth)
    }
    fun stageElapsed(requestedStage: DotScopeSimulation.Stage): Double? = synchronized(lock) {
        val now = clock.now()
        if (stage == requestedStage) max(0.0, seconds(now - stageStartedAt))
        else if (requestedStage == DotScopeSimulation.Stage.connecting && callID == null) recentTap(now)?.let { max(0.0, seconds(now - it)) }
        else null
    }
    fun cancelPendingStart(began: Duration = clock.now()) = synchronized(lock) {
        if (callID == null && (tappedAt == null || tappedAt!! <= began)) { tappedAt = null; if (stage == DotScopeSimulation.Stage.connecting) stage = null }
    }
    suspend fun <T> pendingStart(body: suspend () -> T): T {
        val began = clock.now()
        try { return body() } catch (error: Throwable) { cancelPendingStart(began); throw error }
    }
    private fun recentTap(now: Duration) = tappedAt?.takeIf { now - it < tapWindow }
    private fun recordCues(cues: List<DotScopeSimulation.Cue>, elapsed: Double, recordHaptics: Boolean = true) {
        for (cue in cues) { scheduledCues.add(ScheduledCue(cue, elapsed)); if (recordHaptics) hapticEvents.add(RecordedHaptic(VoiceCallHaptic.forCue(cue), elapsed)) }
        val cue = cues.firstOrNull(); val ready = audioReadyAfter; val report = reportFirstCue
        if (cue != null && ready != null && report != null) {
            audioReadyAfter = null; reportFirstCue = null
            val first = FirstCue(cue, ((elapsed + cueLatency) * 1000).roundToInt(), (ready * 1000).roundToInt())
            scope.launch { report(first) }
        }
    }
    fun startCallClock(id: UUID, latency: Double = 0.0, reportFirstCue: ((FirstCue) -> Unit)? = null, play: (FloatArray) -> Unit) {
        val now = clock.now()
        synchronized(lock) {
            ringTask?.cancel()
            val tapped = recentTap(now); val origin = tapped ?: now
            callID = id; startedAt = origin; stage = DotScopeSimulation.Stage.connecting; stageStartedAt = origin
            scheduler = DotScopeSimulation.SoundScheduler().apply { setStage(DotScopeSimulation.Stage.connecting, seconds(now - origin)) }
            playHandler = play; cueLatency = latency; scheduledTickPass = null; hangUpHapticAt = null
            scheduledCues.clear(); meterSamples.clear(); hapticEvents.clear(); playback = null
            if (tapped != null) hapticEvents.add(RecordedHaptic(VoiceCallHaptic.callStarted, 0.0))
            audioReadyAfter = tapped?.let { seconds(now - it) }; this.reportFirstCue = reportFirstCue; tappedAt = null
            ringTask = scope.launch { runConnectingLoop(id) }
        }
        haptics.prepare()
    }
    fun noteConnected(id: UUID) {
        val now = clock.now()
        synchronized(lock) {
            if (callID != id) return
            ringTask?.cancel(); ringTask = null
            val elapsed = if (stage == DotScopeSimulation.Stage.connecting) seconds(now - stageStartedAt).toFloat() else 0f
            val beam = DotScopeSimulation.Scan.connectingBeam(elapsed)
            if (stage != DotScopeSimulation.Stage.live) { stage = DotScopeSimulation.Stage.live; stageStartedAt = now }
            scheduler.setStage(DotScopeSimulation.Stage.live)
            val cues = scheduler.poll(); recordCues(cues, seconds(now - startedAt))
            cancelVoiceTicks()
            cues.forEach { playHandler?.invoke(VoiceCallSynthesizer.samples(it)) }
            if (DotScopeSimulation.Cue.connected in cues && !haptics.play(VoiceHapticDesign.connect(cueLatency, beam.progress, beam.overshoot, scopeWidth), now)) fallback(VoiceCallHaptic.connected)
        }
        flushDebugLogAsync()
    }
    fun endCallClock(id: UUID, playEndCue: Boolean): FloatArray? = synchronized(lock) {
        if (callID != id) return@synchronized null
        val now = clock.now(); ringTask?.cancel(); ringTask = null; callID = null; playHandler = null; reportFirstCue = null
        stage = DotScopeSimulation.Stage.ending; stageStartedAt = now; scheduler.setStage(DotScopeSimulation.Stage.ending)
        val cues = if (playEndCue) scheduler.poll() else emptyList()
        recordCues(cues, seconds(now - startedAt), false)
        cancelVoiceTicks()
        if (DotScopeSimulation.Cue.ended in cues && (hangUpHapticAt?.let { now - it > 2.seconds } ?: true)) {
            hangUpHapticAt = now; hapticEvents.add(RecordedHaptic(VoiceCallHaptic.ended, seconds(now - startedAt)))
            voiceHangUp(true, 0f, 0f, scopeWidth, now)
        }
        haptics.endCall(); flushDebugLogAsync()
        if (playEndCue && DotScopeSimulation.Cue.ended in cues) VoiceCallSynthesizer.samples(DotScopeSimulation.Cue.ended) else null
    }
    fun noteEndTailStarted(latency: Double) { haptics.play(VoiceHapticDesign.glide(latency), clock.now()) }
    fun cancelActiveTail() { AudioSessionGeneration.bump() }
    private suspend fun runConnectingLoop(id: UUID) {
        while (currentCoroutineContext().isActive) {
            val wait = synchronized(lock) {
                if (callID != id || stage != DotScopeSimulation.Stage.connecting) return
                val ring = scheduler.nextRing ?: return
                if (scheduledTickPass != ring.pass) {
                    scheduledTickPass = ring.pass
                    voiceTick(ring.cue, stageStartedAt + ring.elapsed.seconds, cueLatency)
                }
                scheduler.nextRingDelay ?: return
            }
            clock.sleep(max(1.0, ceil(wait * 1000)).milliseconds)
            synchronized(lock) {
                if (callID != id || stage != DotScopeSimulation.Stage.connecting) return
                val now = clock.now(); scheduler.advance(max(0.0, seconds(now - stageStartedAt) - scheduler.elapsed))
                val cues = scheduler.poll(); recordCues(cues, seconds(now - startedAt))
                cues.forEach { playHandler?.invoke(VoiceCallSynthesizer.samples(it)) }
                if (cues.isNotEmpty()) flushDebugLogAsync()
            }
        }
    }
    fun recordMixerBuffer(buffer: FloatArray, count: Int = buffer.size) {
        if (count <= 0) return
        var sum = 0f; var peak = 0f
        for (i in 0 until count) { val v = abs(buffer[i]); sum += v * v; peak = max(peak, v) }
        val rms = sqrt(sum / count); if (rms <= 5e-4f) return
        synchronized(lock) {
            meterSamples.add(MeterSample(seconds(clock.now() - startedAt), rms, peak))
            if (meterSamples.size > 512) meterSamples.subList(0, meterSamples.size - 512).clear()
        }
    }
    fun flushDebugLogAsync() {
        val path = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_SOUND_LOG")?.takeIf { it.isNotEmpty() } ?: return
        val snap = snapshot()
        scope.launch(Dispatchers.IO) { runCatching { File(path).writeText(Json.encodeToString(snap)) } }
    }
    companion object Key : DependencyKey<VoiceCallSounds> {
        val tapWindow = 30.seconds
        override val liveValue by lazy { VoiceCallSounds() }
        override val testValue get() = VoiceCallSounds(clock = dependency(Clock), haptics = VoiceHapticEngine.testValue)
        val shared get() = dependency(Key)
        private fun seconds(duration: Duration) = duration.inWholeNanoseconds / 1e9
    }
}
