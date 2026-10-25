package ai.opencode.feature.voice

import ai.opencode.feature.voice.DotScopeSimulation.Cue
import ai.opencode.feature.voice.DotScopeSimulation.Stage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of iOS `DotScopeSimulationTests.swift`. Dropped: the five `MTKView`/`UIHostingController`
 * probes (`coveredScopeCannotReconfigureSharedRenderer`, `scopeResumesAfterSwiftUIOpacityHandoff`,
 * `coveredScopeDrawMeasurement`, `coveredScopeEnvironmentPausesMetalDisplayLink`,
 * `browserAndCatchUpPauseScopesAndUnhideRedraws`): they drive UIKit windows and Metal display
 * links, which have no JVM equivalent (Android draws the scope with AGSL in Compose).
 */
class DotScopeSimulationTests {
    private val grid = DotScopeGrid(pixelWidth = 1248, pixelHeight = 168, scale = 3f)

    /** The packed xyzw render field, reduced to (x, y) per dot. */
    private fun field(simulation: DotScopeSimulation): FloatArray {
        val rendered = FloatArray(grid.count * 4)
        simulation.render(grid, rendered)
        return FloatArray(grid.count * 2) { rendered[(it / 2) * 4 + it % 2] }
    }

    private class Light(var leftX: Float = 0f, var leftY: Float = 0f, var rightX: Float = 0f, var rightY: Float = 0f)

    /** Summed light per voice over the left and right thirds of the bar. */
    private fun light(field: FloatArray): Light {
        val light = Light()
        for (j in 0 until grid.rows) {
            for (i in 0 until grid.columns) {
                val index = (j * grid.columns + i) * 2
                if (i < grid.columns / 3) {
                    light.leftX += field[index]; light.leftY += field[index + 1]
                } else if (i >= grid.columns * 2 / 3) {
                    light.rightX += field[index]; light.rightY += field[index + 1]
                }
            }
        }
        return light
    }

    /** Rows away from the centre line that the brightest dot of each column sits, at most. */
    private fun swing(field: FloatArray): Float {
        val mid = (grid.rows - 1) / 2f
        var widest = 0f
        for (i in 0 until grid.columns) {
            for (k in 0 until 2) {
                var best = -1f
                var row = 0
                for (j in 0 until grid.rows) {
                    val value = field[(j * grid.columns + i) * 2 + k]
                    if (value > best) { best = value; row = j }
                }
                widest = max(widest, abs(row - mid))
            }
        }
        return widest
    }

    private fun syllable(n: Int) = abs(sin(n / 60.0 * PI * 4.4)).toFloat()

    @Test fun gridMatchesThePrototypeAtBarSize() {
        assertEquals(11f, grid.pitch)
        assertEquals(113, grid.columns)
        assertEquals("Odd, so a flat line sits on the centre row", 13, grid.rows)
        assertEquals(416f, grid.width)
    }

    @Test fun sameInputsGiveTheSamePicture() {
        val a = DotScopeSimulation()
        val b = DotScopeSimulation()
        // Different frame pacing, same total time.
        repeat(120) { a.advance(by = 1.0 / 60, you = 0.6f, agent = 0.2f, connected = true) }
        repeat(240) { b.advance(by = 1.0 / 120, you = 0.6f, agent = 0.2f, connected = true) }
        assertArrayEquals(field(a), field(b), 0f)
    }

    @Test fun quietCallIsTwoNearFlatLines() {
        val simulation = DotScopeSimulation()
        repeat(240) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = true) }
        assertTrue(swing(field(simulation)) <= 1.5f)
        val still = DotScopeSimulation.still(connected = true)
        assertTrue(swing(field(still)) <= 1.5f)
    }

    @Test fun eachVoiceIsStrongestOnItsOwnSide() {
        val you = DotScopeSimulation()
        for (n in 0 until 90) {
            you.advance(by = 1.0 / 60, you = 0.3f + 0.6f * syllable(n), agent = 0f, connected = true)
        }
        val field = field(you)
        val lit = light(field)
        assertTrue(lit.leftX > lit.rightX * 1.5f)
        assertTrue(swing(field) >= 3)
    }

    @Test fun phosphorLeavesFadingTrails() {
        val simulation = DotScopeSimulation()
        field(simulation)
        for (n in 0 until 60) {
            simulation.advance(by = 1.0 / 60, you = 0.3f + 0.6f * syllable(n), agent = 0f, connected = true)
        }
        val rendered = FloatArray(grid.count * 4)
        simulation.render(grid, rendered)
        val dots = (0 until grid.count).map { rendered[it * 4] to rendered[it * 4 + 2] }
        // Some dots are lit only by afterglow: bright, but not fresh.
        assertTrue(dots.any { (x, z) -> x > 0.3f && z < 0.5f })
        assertTrue(dots.any { (x, z) -> x > 0.3f && z > 0.95f })
    }

    @Test fun glowSwellsWithItsVoiceAndSettles() {
        val simulation = DotScopeSimulation()
        repeat(60) { simulation.advance(by = 1.0 / 60, you = 0.8f, agent = 0f, connected = true) }
        assertTrue(simulation.glow.x > 0.4f)
        assertTrue(simulation.glow.y < 0.05f)
        repeat(360) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = true) }
        assertTrue(simulation.glow.x < 0.1f)
    }

    @Test fun scannerConnectsDrawsAndSweepsOut() {
        // Connecting: one beam on a flat line; only dots near it are bright.
        val simulation = DotScopeSimulation()
        simulation.setStage(Stage.connecting)
        repeat(30) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = false) }
        val connecting = simulation.scan(grid.width)
        assertEquals(0f, connecting.helix)
        assertTrue(connecting.envelope(connecting.front).bright > 2)
        assertTrue(connecting.envelope(connecting.front + 60).bright < 0.1f)

        // Live: the draw sweep crosses the bar and leaves the whole braid lit.
        simulation.setStage(Stage.live)
        repeat(60) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = true) }
        val live = simulation.scan(grid.width)
        assertEquals(1f, live.envelope(grid.width - 1).reveal)
        assertEquals(1f, live.presence)

        // Ending: the beam heads toward End, erasing behind it, and the scope is gone by the exit's end.
        simulation.setStage(Stage.ending)
        repeat(15) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = false) }
        val ending = simulation.scan(grid.width)
        assertTrue("What the beam passed is gone", ending.envelope(ending.front - 40).reveal < 0.01f)
        assertTrue("Ahead of it the braid remains", ending.envelope(ending.front + 60).reveal > 0.99f)
        repeat((DotScopeSimulation.exitDuration * 60).toInt() + 2) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = false) }
        assertEquals(0f, simulation.scan(grid.width).presence)
    }

    @Test fun hangingUpWhileConnectingSweepsOnlyTheBeam() {
        val simulation = DotScopeSimulation()
        simulation.setStage(Stage.connecting)
        repeat(20) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = false) }
        simulation.setStage(Stage.ending)
        repeat(6) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = false) }
        val ending = simulation.scan(grid.width)
        assertEquals("No braid appears just to be erased", 0f, ending.helix)
        assertTrue(ending.envelope(ending.front + 60).reveal < 0.01f)
        assertTrue("The beam itself sweeps out", ending.envelope(ending.front).reveal > 0.9f)
    }

    @Test fun soundSchedulerPingsOnceAfterALateWake() {
        val scheduler = DotScopeSimulation.SoundScheduler()
        scheduler.setStage(Stage.connecting)
        scheduler.advance(by = 0.6)
        assertEquals(listOf(Cue.sweepRight), scheduler.poll())
        // Waking 2.2 s late crosses four passes (2–5); only the latest one pings.
        scheduler.advance(by = 2.2)
        assertEquals(listOf(Cue.sweepRight), scheduler.poll())
    }

    @Test fun soundSchedulerJoiningLateSkipsTheTurnaroundsItMissed() {
        // The engine came up 0.6 s after the tap: the beam already turned at Mute (0.57 s).
        val scheduler = DotScopeSimulation.SoundScheduler()
        scheduler.setStage(Stage.connecting, elapsed = 0.6)
        assertTrue(scheduler.poll().isEmpty())
        val ring = scheduler.nextRing ?: return fail("No next ring")
        assertTrue(ring.pass == 2 && ring.cue == Cue.sweepLeft)
        assertTrue(abs((scheduler.nextRingDelay ?: 0.0) - (DotScopeSimulation.SoundScheduler.pingTime(2) - 0.6)) < 1e-9)
        scheduler.advance(by = 0.5)
        assertTrue(scheduler.poll().isEmpty())
        scheduler.advance(by = 0.04)
        assertEquals("The first ping is the next turnaround the beam reaches", listOf(Cue.sweepLeft), scheduler.poll())
    }

    @Test fun soundSchedulerEmitsPingPerPassAndStaysSilentOnReconnect() {
        // Opening directly onto a call in progress stays silent.
        val liveStart = DotScopeSimulation.SoundScheduler()
        liveStart.setStage(Stage.live)
        liveStart.advance(by = 1.0)
        assertTrue(liveStart.poll().isEmpty())

        // Pre-connect failure / hang-up before connecting plays no .ended glide.
        val preConnectEnd = DotScopeSimulation.SoundScheduler()
        preConnectEnd.setStage(Stage.connecting)
        preConnectEnd.advance(by = 0.6)
        assertEquals(listOf(Cue.sweepRight), preConnectEnd.poll())
        preConnectEnd.setStage(Stage.ending)
        assertTrue("A call that never connected must not play .ended", preConnectEnd.poll().isEmpty())

        // Connecting schedules alternating sweepRight / sweepLeft pings at each
        // turnaround, every 0.55 s pass; the launch out of the call button has none.
        val callScheduler = DotScopeSimulation.SoundScheduler()
        callScheduler.setStage(Stage.connecting)
        callScheduler.advance(by = 0.01)
        assertTrue(callScheduler.poll().isEmpty())
        callScheduler.advance(by = 0.02) // elapsed = 0.03 s (pass 0: launching from the call button)
        assertTrue(callScheduler.poll().isEmpty())
        callScheduler.advance(by = 0.50) // elapsed = 0.53 s
        assertTrue(callScheduler.poll().isEmpty())
        callScheduler.advance(by = 0.06) // elapsed = 0.59 s (pass 1: Mute → End at 0.57 s)
        assertEquals(listOf(Cue.sweepRight), callScheduler.poll())
        callScheduler.advance(by = 0.55) // elapsed = 1.14 s (pass 2: End → Mute at 1.12 s)
        assertEquals(listOf(Cue.sweepLeft), callScheduler.poll())
        callScheduler.advance(by = 0.55) // elapsed = 1.69 s (pass 3: Mute → End at 1.67 s)
        assertEquals(listOf(Cue.sweepRight), callScheduler.poll())
        callScheduler.advance(by = 0.55) // elapsed = 2.24 s (pass 4: End → Mute at 2.22 s)
        assertEquals(listOf(Cue.sweepLeft), callScheduler.poll())

        // First connect emits .connected once, and nothing while live.
        callScheduler.setStage(Stage.live)
        assertEquals(listOf(Cue.connected), callScheduler.poll())
        callScheduler.advance(by = 2.0)
        assertTrue(callScheduler.poll().isEmpty())

        // Mid-call reconnect (live → connecting → live) is completely silent.
        callScheduler.setStage(Stage.connecting)
        callScheduler.advance(by = 2.5)
        assertTrue("Mid-call reconnect must not ping", callScheduler.poll().isEmpty())
        callScheduler.setStage(Stage.live)
        assertTrue("Mid-call reconnect must not play the connect chord", callScheduler.poll().isEmpty())

        // Hanging up a connected call plays .ended.
        callScheduler.setStage(Stage.ending)
        assertEquals(listOf(Cue.ended), callScheduler.poll())
    }

    @Test fun hapticCueMappingMatchesScannerPingsAndControls() {
        assertEquals(VoiceCallHaptic.sweepPass, VoiceCallHaptic.forCue(Cue.sweepRight))
        assertEquals(VoiceCallHaptic.sweepPass, VoiceCallHaptic.forCue(Cue.sweepLeft))
        assertEquals(VoiceCallHaptic.connected, VoiceCallHaptic.forCue(Cue.connected))
        assertEquals(VoiceCallHaptic.ended, VoiceCallHaptic.forCue(Cue.ended))
        assertEquals(VoiceCallHaptic.muted, VoiceCallHaptic.forMute(isMuted = true))
        assertEquals(VoiceCallHaptic.unmuted, VoiceCallHaptic.forMute(isMuted = false))
    }

    @Test fun synthesizerGeneratesNonSilentWaveformsForEveryCue() {
        for (cue in listOf(Cue.sweepRight, Cue.sweepLeft, Cue.connected, Cue.ended)) {
            val samples = VoiceCallSynthesizer.samples(cue)
            assertTrue(samples.size > 3_000)
            val rms = sqrt(samples.fold(0f) { sum, s -> sum + s * s } / samples.size)
            val peak = samples.maxOf { abs(it) }
            assertTrue("Cue $cue has soft non-silent RMS ($rms)", rms > 0.002f && rms < 0.25f)
            assertTrue("Cue $cue does not clip ($peak)", peak > 0.01f && peak <= 1.0f)
        }
    }

    /**
     * The hang-up tail plays the whole cue before it's stopped, at default
     * and at the longest decay and spacing the tunables allow.
     */
    @Test fun endTailOutlastsTheHangUpCue() {
        // iOS `CallSoundTunables.decay.range` (0.5…2) and `.spacing.range` (0.4…2.5) upper bounds.
        val longest = VoiceCallSynthesizer.Settings(decay = 2.0, spacing = 2.5)
        for (settings in listOf(VoiceCallSynthesizer.Settings(), longest)) {
            val samples = VoiceCallSynthesizer.render(Cue.ended, settings)
            val length = samples.size / PCM.sampleRate
            assertTrue("tail for a $length s hang-up", VoiceCallSynthesizer.endTailWait(samples) > length + 0.05)
        }
        assertTrue(VoiceCallSynthesizer.render(Cue.ended, longest).size / PCM.sampleRate > 1.5)
    }

    private data class Prototype(val count: Int, val peak: Float, val at1200: Float, val at4000: Float)

    /**
     * The breath ping set matches the D-128 web prototype it was chosen from:
     * its length, peak and two samples per cue.
     */
    @Test fun breathPingCuesMatchThePrototype() {
        val defaults = VoiceCallSynthesizer.Settings()
        val rendered = listOf(
            "sweepRight" to VoiceCallSynthesizer.render(Cue.sweepRight, defaults),
            "sweepLeft" to VoiceCallSynthesizer.render(Cue.sweepLeft, defaults),
            "connected" to VoiceCallSynthesizer.render(Cue.connected, defaults),
            "ended" to VoiceCallSynthesizer.render(Cue.ended, defaults),
            "mute" to VoiceCallSynthesizer.muteSamples(muted = true, settings = defaults),
            "unmute" to VoiceCallSynthesizer.muteSamples(muted = false, settings = defaults),
        )
        val prototype = mapOf(
            "sweepRight" to Prototype(9_600, 0.017676122f, -0.0035037478f, 0.0012820613f),
            "sweepLeft" to Prototype(9_600, 0.017382188f, 0.010709052f, -0.0011834651f),
            "connected" to Prototype(24_240, 0.073754534f, -0.014271764f, 0.027006725f),
            "ended" to Prototype(18_240, 0.069794223f, 0.044520687f, -0.014266015f),
            "mute" to Prototype(6_720, 0.020073203f, -0.0025383446f, 0.00038064722f),
            "unmute" to Prototype(6_720, 0.019921213f, 0.0079313954f, -0.00035919933f),
        )
        for ((name, samples) in rendered) {
            val expected = prototype.getValue(name)
            assertEquals("$name length", expected.count, samples.size)
            assertTrue("$name peak", abs(samples.maxOf { abs(it) } - expected.peak) < 1e-7f)
            assertTrue("$name at 50 ms", abs(samples[1_200] - expected.at1200) < 1e-7f)
            assertTrue("$name at 167 ms", abs(samples[4_000] - expected.at4000) < 1e-7f)
        }
        // The repeating pings stay well under connect and hang-up.
        fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it) } ?: 0f
        assertTrue(peak(rendered[0].second) * 3 < peak(rendered[2].second))
        assertTrue(peak(rendered[1].second) * 3 < peak(rendered[3].second))
    }

    @Test fun providedBandsReplaceTheStandIn() {
        val lows = DotScopeSimulation()
        val highs = DotScopeSimulation()
        val low = FloatArray(16) { if (it < 4) 0.9f else 0.1f }
        val high = FloatArray(16) { if (it >= 12) 0.9f else 0.1f }
        repeat(60) {
            lows.advance(by = 1.0 / 60, you = 0.7f, agent = 0f, youBands = low, connected = true)
            highs.advance(by = 1.0 / 60, you = 0.7f, agent = 0f, youBands = high, connected = true)
        }
        assertTrue(lows.you.bands[0] > 0.8f)
        assertFalse(field(lows).contentEquals(field(highs)))
    }

    @Test fun stageTransitionsAreContinuousAcrossPassPhases() {
        val beamStep = DotScopeSimulation.stepDuration * 2 // 120 Hz beam cadence
        // Leaving the call button, launching toward Mute, settling into Mute's
        // turnaround, mid-pass going right, and mid-pass going left.
        val connectTimes = listOf(0.02, 0.08, 0.26, 0.50, 0.76, 1.30)
        val buffer = FloatArray(grid.count * 4)
        val windows = FloatArray(grid.columns) { col ->
            val x = grid.columnX(col.toFloat())
            val t = min(1f, max(0f, (min(x, grid.width - x) - DotScopeSimulation.windowInset) / DotScopeSimulation.windowFade))
            t * t * (3 - 2 * t)
        }

        fun renderPeakLight(simulation: DotScopeSimulation): Float {
            simulation.render(grid, buffer)
            var best = 0f
            for (col in 0 until grid.columns) {
                val win = windows[col]
                for (row in 0 until grid.rows) {
                    val v = (row * grid.columns + col) * 4
                    best = max(best, max(buffer[v], buffer[v + 1]) * win)
                }
            }
            return best
        }

        for (connectAt in connectTimes) {
            val simulation = DotScopeSimulation()
            simulation.setStage(Stage.connecting)
            val connectSteps = max(1, (connectAt / beamStep).roundToInt())
            var previousLight = renderPeakLight(simulation)
            var previousScan = simulation.scan(grid.width)

            repeat(connectSteps) {
                simulation.advance(by = beamStep, you = 0f, agent = 0f, connected = false)
                val currentScan = simulation.scan(grid.width)
                val currentLight = renderPeakLight(simulation)
                assertTrue("Connecting beam jumped at ${connectAt}s", abs(currentScan.front - previousScan.front) <= 10)
                assertTrue("Connecting pulse head jumped at ${connectAt}s", abs(currentScan.head - previousScan.head) <= 0.15f)
                assertTrue("Connecting peak light jumped at ${connectAt}s: $previousLight → $currentLight (front ${currentScan.front})", abs(currentLight - previousLight) <= 0.25f)
                previousScan = currentScan
                previousLight = currentLight
            }

            // Instant of connecting → live: zero discontinuity in front, head, grid, or envelope.
            val beforeConnect = simulation.scan(grid.width)
            simulation.setStage(Stage.live)
            val atConnect = simulation.scan(grid.width)
            assertTrue("Connect front jumped from ${beforeConnect.front} to ${atConnect.front}", abs(atConnect.front - beforeConnect.front) < 1e-3f)
            assertTrue("Connect head jumped from ${beforeConnect.head} to ${atConnect.head}", abs(atConnect.head - beforeConnect.head) < 1e-3f)
            assertTrue("Connect grid jumped from ${beforeConnect.grid} to ${atConnect.grid}", abs(atConnect.grid - beforeConnect.grid) < 1e-3f)
            for (col in 0 until grid.columns) {
                val x = grid.columnX(col.toFloat())
                val e0 = beforeConnect.envelope(x)
                val e1 = atConnect.envelope(x)
                assertTrue(abs(e0.amp - e1.amp) < 1e-4f)
                assertTrue(abs(e0.reveal - e1.reveal) < 1e-4f)
                assertTrue(abs(e0.bright - e1.bright) < 1e-4f)
                assertTrue(abs(e0.swell - e1.swell) < 1e-4f)
            }

            // Step through the live bloom and into settled live; verify every 120 Hz step is smooth.
            repeat(80) {
                simulation.advance(by = beamStep, you = 0.25f, agent = 0.2f, connected = true)
                val currentScan = simulation.scan(grid.width)
                val currentLight = renderPeakLight(simulation)
                assertTrue("Live bloom front jumped at ${connectAt}s", abs(currentScan.front - previousScan.front) <= 12)
                assertTrue("Live bloom head jumped at ${connectAt}s", abs(currentScan.head - previousScan.head) <= 0.15f)
                assertTrue("Live bloom peak light jumped at ${connectAt}s: $previousLight → $currentLight", abs(currentLight - previousLight) <= 0.25f)
                previousScan = currentScan
                previousLight = currentLight
            }

            // Instant of live → ending: zero discontinuity in envelope across the visible window.
            val beforeEnd = simulation.scan(grid.width)
            simulation.setStage(Stage.ending)
            val atEnd = simulation.scan(grid.width)
            assertTrue(abs(atEnd.grid - beforeEnd.grid) < 1e-3f)
            assertTrue(abs(atEnd.presence - beforeEnd.presence) < 1e-3f)
            for (col in 0 until grid.columns) {
                val x = grid.columnX(col.toFloat())
                val e0 = beforeEnd.envelope(x)
                val e1 = atEnd.envelope(x)
                assertTrue(abs(e0.amp - e1.amp) < 1e-4f)
                assertTrue(abs(e0.reveal - e1.reveal) < 1e-4f)
                assertTrue(abs(e0.bright - e1.bright) < 1e-4f)
            }

            // Step through the ending sweep to exitDuration; verify no per-step jump.
            previousScan = atEnd
            repeat((DotScopeSimulation.exitDuration / beamStep).roundToInt()) {
                simulation.advance(by = beamStep, you = 0f, agent = 0f, connected = false)
                val currentScan = simulation.scan(grid.width)
                val currentLight = renderPeakLight(simulation)
                assertTrue("Ending sweep front jumped", abs(currentScan.front - previousScan.front) <= 12)
                assertTrue("Ending peak light jumped", abs(currentLight - previousLight) <= 0.35f)
                previousScan = currentScan
                previousLight = currentLight
            }

            // Also verify connecting → ending directly from this pass phase has zero jump.
            val preConnect = DotScopeSimulation()
            preConnect.setStage(Stage.connecting)
            repeat(connectSteps) { preConnect.advance(by = beamStep, you = 0f, agent = 0f, connected = false) }
            var prevPreLight = renderPeakLight(preConnect)
            val beforeAbort = preConnect.scan(grid.width)
            preConnect.setStage(Stage.ending)
            val atAbort = preConnect.scan(grid.width)
            assertTrue("Connecting→ending front jumped from ${beforeAbort.front} to ${atAbort.front}", abs(atAbort.front - beforeAbort.front) < 1e-3f)
            assertTrue("Connecting→ending head jumped from ${beforeAbort.head} to ${atAbort.head}", abs(atAbort.head - beforeAbort.head) < 1e-3f)
            var prevAbortScan = atAbort
            repeat((DotScopeSimulation.exitDuration / beamStep).roundToInt()) {
                preConnect.advance(by = beamStep, you = 0f, agent = 0f, connected = false)
                val curAbortScan = preConnect.scan(grid.width)
                val curPreLight = renderPeakLight(preConnect)
                assertTrue("Connecting→ending sweep must move monotonically toward End", curAbortScan.front >= prevAbortScan.front - 1e-3f)
                assertTrue(abs(curAbortScan.front - prevAbortScan.front) <= 12)
                assertTrue(abs(curPreLight - prevPreLight) <= 0.35f)
                prevAbortScan = curAbortScan
                prevPreLight = curPreLight
            }
        }
    }

    @Test fun connectingPassPingsLineUpWithBeamTurnarounds() {
        val simulation = DotScopeSimulation()
        val scheduler = DotScopeSimulation.SoundScheduler()
        simulation.setStage(Stage.connecting)
        scheduler.setStage(Stage.connecting)

        val leftEdge = DotScopeSimulation.Scan.edgeInset
        val rightEdge = grid.width - DotScopeSimulation.Scan.edgeInset
        // The beam starts on the call button, at End's end of the bar, and heads for Mute.
        val start = simulation.scan(grid.width)
        assertTrue(abs(start.front - (grid.width - DotScopeSimulation.Scan.launchInset)) < 0.5f)
        data class Emitted(val cue: Cue, val front: Float, val time: Double)
        val emitted = mutableListOf<Emitted>()
        val midPassHeads = mutableListOf<Float>()

        val steps = (2.3 / DotScopeSimulation.stepDuration).roundToInt()
        for (step in 1..steps) {
            simulation.advance(by = DotScopeSimulation.stepDuration, you = 0f, agent = 0f, connected = false)
            scheduler.advance(by = DotScopeSimulation.stepDuration)
            val scan = simulation.scan(grid.width)
            for (cue in scheduler.poll()) emitted += Emitted(cue, scan.front, step * DotScopeSimulation.stepDuration)
            val pass = scan.elapsed / DotScopeSimulation.Scan.sweepPass
            val along = pass - floor(pass)
            if (abs(along - 0.5f) < 0.01f) midPassHeads += scan.head
        }

        assertEquals(listOf(Cue.sweepRight, Cue.sweepLeft, Cue.sweepRight, Cue.sweepLeft), emitted.map { it.cue })
        for (event in emitted) {
            when (event.cue) {
                Cue.sweepRight -> assertTrue("sweepRight at ${event.time}s should fire at left turnaround ($leftEdge), got ${event.front}", abs(event.front - leftEdge) <= 2.5f)
                Cue.sweepLeft -> assertTrue("sweepLeft at ${event.time}s should fire at right turnaround ($rightEdge), got ${event.front}", abs(event.front - rightEdge) <= 2.5f)
                else -> fail("Unexpected cue ${event.cue}")
            }
        }
        assertTrue("Each pass should swell in brightness through the middle", midPassHeads.all { it > 3.4f })
    }

    /**
     * The scope's first frames after the call button's tap, drawn as
     * `DotScopeRenderer` does: synced to the call's connecting clock (which
     * runs from the tap), then stepped by the frame's duration.
     */
    private fun connectingFronts(firstFrameAt: Double, frames: Int): List<Float> {
        val simulation = DotScopeSimulation()
        val frame = 1.0 / 60
        return (0 until frames).map { n ->
            val step = if (n == 0) 0.0 else frame
            simulation.setStage(Stage.connecting, elapsed = firstFrameAt + n * frame - step)
            simulation.advance(by = step, you = 0f, agent = 0f, connected = false)
            simulation.scan(grid.width).front
        }
    }

    /** iOS `@Test(arguments: [0.0, 1.0 / 60, 2.0 / 60])`. */
    @Test fun theBeamLeavesTheCallButtonFromTheFirstFrame() {
        for (delay in listOf(0.0, 1.0 / 60, 2.0 / 60)) {
            val fronts = connectingFronts(firstFrameAt = delay, frames = 7)
            val button = grid.width - DotScopeSimulation.Scan.launchInset
            assertTrue("It starts at the call button: $fronts", fronts[0] <= button + 0.01f && fronts[0] > button - 35)
            for ((a, b) in fronts.zipWithNext()) {
                assertTrue("Moving toward Mute every frame, with no hold: $fronts", a - b > 5)
                assertTrue("Smoothly, with no jump: $fronts", a - b < 20)
            }
            // Out from under the button and into the scope's window within a few frames.
            val window = grid.width - DotScopeSimulation.windowInset - DotScopeSimulation.windowFade / 2
            assertTrue("$fronts", fronts[5] < window)
        }
    }

    @Test fun trayModeReachesLeadingEdgeAndHonoursVisibleBarWidth() {
        val simulation = DotScopeSimulation()
        simulation.setStage(Stage.connecting)
        repeat(33) { simulation.advance(by = 1.0 / 60, you = 0f, agent = 0f, connected = false) }

        val defaultScan = simulation.scan(grid.width)
        // iOS copies the value; the Kotlin simulation is a class, and `simulation` isn't used again.
        val traySim = simulation
        traySim.windowInsets = ScopeVector2(0f, 108f)
        traySim.reachesLeadingEdge = true
        traySim.visibleBar = ScopeVector2(0f, grid.width)
        val trayScan = traySim.scan(grid.width)
        assertTrue(abs(defaultScan.front - DotScopeSimulation.Scan.edgeInset) < 1.5f)
        assertTrue("Tray scanner reaches the left cap (14 pt) since Mute is on the right", abs(trayScan.front - 14) < 1.5f)

        // When pinned in the narrower drawer, scan operates in the visible bar's coordinate space (0…narrowedWidth).
        val narrowedWidth = 280f
        traySim.visibleBar = ScopeVector2(grid.width - narrowedWidth, narrowedWidth)
        val narrowedScan = traySim.scan(grid.width)
        assertEquals(narrowedWidth, narrowedScan.width)
        assertTrue(abs(narrowedScan.front - 14) < 1.5f)

        // Live voices light dots near the left cap when reachesLeadingEdge is enabled.
        val trayLive = DotScopeSimulation()
        trayLive.setStage(Stage.live)
        trayLive.windowInsets = ScopeVector2(0f, 108f)
        trayLive.reachesLeadingEdge = true
        trayLive.visibleBar = ScopeVector2(0f, grid.width)

        val defaultLive = DotScopeSimulation()
        defaultLive.setStage(Stage.live)
        for (n in 0 until 90) {
            trayLive.advance(by = 1.0 / 60, you = 0.35f + 0.6f * syllable(n), agent = 0f, connected = true)
            defaultLive.advance(by = 1.0 / 60, you = 0.35f + 0.6f * syllable(n), agent = 0f, connected = true)
        }
        val defaultBuffer = FloatArray(grid.count * 4)
        val trayBuffer = FloatArray(grid.count * 4)
        defaultLive.render(grid, defaultBuffer)
        trayLive.render(grid, trayBuffer)
        val leftColumn = 6 // x ≈ 22 pt, well inside the left cap where Mute used to sit
        var defaultLeftSwing = 0f
        var trayLeftSwing = 0f
        val mid = (grid.rows - 1) / 2f
        for (row in 0 until grid.rows) {
            if (defaultBuffer[(row * grid.columns + leftColumn) * 4] > 0.35f) defaultLeftSwing = max(defaultLeftSwing, abs(row - mid))
            if (trayBuffer[(row * grid.columns + leftColumn) * 4] > 0.35f) trayLeftSwing = max(trayLeftSwing, abs(row - mid))
        }
        assertTrue("Tray live waveform reaches into the left cap (x ≈ 22 pt)", trayLeftSwing > defaultLeftSwing + 1)
    }
}
