package ai.opencode.feature.voice

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** iOS `CGRect(x:y:width:height:)`. */
private fun cgRect(x: Float, y: Float, width: Float, height: Float) = Rect(x, y, x + width, y + height)

/**
 * The session's tray as iOS tests note it without a `sessionAnchor`: the frame is recorded,
 * but no anchor is mounted (Android's anchor reports through [CallTrayDock.noteSessionTray]).
 */
private fun CallTrayDock.noteSessionTrayWithoutAnchor(frame: Rect) {
    noteSessionTray(frame)
    noteSessionAnchorRemoved()
}

class FloatingCallModelTests {
    // `ComposerTunables` (bottomGap, keyboardGap) lives in :feature:session; the tray's copy of
    // keyboardGap is `CallTrayComposerMetrics`. The `TuningStore` registry half (`register(in:)`/
    // `unregister(in:)` and its keys) can't exist here: `CallTrayTunables` are baked constants, and
    // the Call Tray tuning panel is iOS `Tuning/` (devtools is Android-native).
    @Test
    fun callTrayExperimentAndTunablesRegisterOption2Defaults() {
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.callTray))
        assertEquals("Call Tray", ExperimentalFeature.callTray.title)
        assertEquals(CallTrayBelowMode.lift, CallTrayTunables.below)
        assertEquals(3f, CallTrayTunables.composerDip)
        assertEquals(0f, CallTrayTunables.tuck)
        assertEquals(8f, CallTrayComposerMetrics.keyboardGap)
        assertEquals(CallTrayMorph.staged, CallTrayTunables.morph)
        assertEquals(CallTraySeedHandoff.end, CallTrayTunables.seedHandoff)
        assertEquals(CallTrayCallButtonStyle.disc, CallTrayTunables.callButton)
        assertEquals(CallTrayEndButtonStyle.red, CallTrayTunables.endButton)
        assertEquals(CallTrayMuteStyle.icon, CallTrayTunables.muteStyle)
        assertEquals(CallTrayWidenCurve.shoot, CallTrayTunables.widenCurve)
        assertEquals(495.0, CallTrayTunables.spinDegrees, 0.0)
        assertEquals(0.24, CallTrayTunables.openStagger, 0.0)
        assertEquals(0.10, CallTrayTunables.closeStagger, 0.0)
        assertEquals(5f, CallTrayTunables.windup)
        assertEquals(8f, CallTrayTunables.stretch)
        assertEquals(0.5f, CallTrayTunables.squash)
        assertEquals(14f, CallTrayTunables.contentTravel)
        assertEquals(4f, CallTrayTunables.contentBlur)
        assertEquals(1f, CallTrayTunables.scanReach)
        assertEquals(12f, CallTrayTunables.scopeLeadingInset)
        assertEquals(24f, CallTrayTunables.muteGap)
        assertEquals(24f, CallTrayTunables.scopeFade)
        assertEquals(CallTrayGlassEmerge.droplet, CallTrayTunables.glassEmerge)
        assertEquals(8f, CallTrayTunables.glassSpacing)
        assertEquals(0.5f, CallTrayTunables.glassEnergize)
        assertEquals(0.0, CallTrayTunables.lift.bounce, 0.0)
        assertEquals(0.0, CallTrayTunables.open.bounce, 0.0)
        assertEquals(0.0, CallTrayTunables.close.bounce, 0.0)
        assertEquals(0.0, CallTrayTunables.transcriptExpand.bounce, 0.0)
        assertEquals(0.0, CallTrayTunables.transcriptCollapse.bounce, 0.0)
    }

    @Test
    fun callTrayCallLookIsTheComposersCallButton() {
        for (isDark in listOf(false, true)) {
            fun rgba(r: Float, g: Float, b: Float, a: Float) = listOf(r, g, b, a).map { Math.round(it * 255f).toFloat() }
            // `.label` and `.systemBackground`: white and black in dark, black and white in light.
            val label = if (isDark) rgba(1f, 1f, 1f, 1f) else rgba(0f, 0f, 0f, 1f)
            val background = if (isDark) rgba(0f, 0f, 0f, 1f) else rgba(1f, 1f, 1f, 1f)
            val look = CallTrayController.buttonLook(
                callStyle = CallTrayCallButtonStyle.disc, endStyle = CallTrayEndButtonStyle.red,
                dissolve = 0f, callness = 0f, isDark = isDark,
            )
            // `ComposerTrailingControl.call`: a `.label` disc with a `.systemBackground` glyph.
            val disc = look.disc.copy(alpha = look.disc.alpha * look.discAlpha)
            assertEquals("isDark=$isDark", label, rgba(disc.red, disc.green, disc.blue, disc.alpha))
            assertEquals("isDark=$isDark", background, rgba(look.glyph.red, look.glyph.green, look.glyph.blue, look.glyph.alpha))
        }
    }

    @Test
    fun callTrayMuteSitsEquidistantBetweenScopeAndEnd() {
        for (muteGap in listOf(18f, 24f, 32f)) for (glyphWidth in listOf(12f, 15.6f, 20f)) {
            val layout = CallTrayBarLayout(muteGap = muteGap, glyphWidth = glyphWidth, trailingPadding = 6f, scopeFade = 24f)
            val endDiscLeading = 6 + (CallTrayBarLayout.slot + CallTrayBarLayout.disc) / 2
            val glyphTrailing = layout.muteCenterFromTrailing - glyphWidth / 2
            val glyphLeading = layout.muteCenterFromTrailing + glyphWidth / 2
            val scopeHalfFade = layout.scopeTrailingInset + 24f / 2
            val args = "muteGap=$muteGap glyphWidth=$glyphWidth"

            assertTrue("Mute and End keep separate 44 pt slots ($args)", layout.buttonSpacing >= 0)
            assertTrue(args, abs((glyphTrailing - endDiscLeading) - layout.endSideGap) < 0.001f)
            assertTrue("The scope ends as far from Mute as End does ($args)", abs((scopeHalfFade - glyphLeading) - layout.endSideGap) < 0.001f)
            if (layout.buttonSpacing > 0) {
                assertTrue(args, abs(layout.endSideGap - muteGap) < 0.001f)
            } else {
                assertTrue(args, layout.endSideGap >= muteGap)
            }
        }
    }

    @Test
    fun callTrayRestsOpenOnceUnfoldedAndNotWhileEnding() {
        for (reducedMotion in listOf(false, true)) {
            val controller = CallTrayController()
            controller.noteComposer(frame = cgRect(20f, 754f, 350f, 56f), isFocused = false)
            controller.start(fromTap = true, alreadyConnected = false, reducedMotion = reducedMotion)
            assertFalse(controller.isResting)
            repeat(600) { controller.step(dt = 1.0 / 60) }
            val l = controller.l; val a = controller.a; val w = controller.w; val k = controller.k; val e = controller.e
            assertTrue(
                "Unfolded: l=${l.value}/${l.target} a=${a.value}/${a.target} w=${w.value}/${w.target} k=${k.value}/${k.target} e=${e.value}/${e.target}",
                controller.isResting,
            )
            controller.end(reducedMotion = reducedMotion)
            assertFalse("Hanging up stops it resting at once, before the next step", controller.isResting)
            controller.step(dt = 1.0 / 60)
            assertFalse(controller.isResting)
        }
    }

    @Test
    fun callTrayDocksOnlyOnATripFromAShutDrawer() {
        val dock = CallTrayDock()
        dock.setDrawer(
            CallTrayDockGeometry(
                slot = cgRect(12f, 816f, 278f, 56f),
                bounds = cgRect(0f, 0f, 302f, 932f),
                recedes = true,
            ),
        )
        dock.noteSessionTrayWithoutAnchor(cgRect(16f, 822f, 398f, 56f))
        // Not armed: the tray rides the card as before, and the drawer's controls stay down.
        dock.drawerMoved(0.3f)
        assertTrue(!dock.isTravelling && !dock.liftsDrawerControls)
        // Armed mid-trip: nothing jumps out of the card until the drawer shuts again.
        dock.setArmed(true)
        dock.drawerMoved(0.6f)
        assertTrue(!dock.isTravelling && !dock.liftsDrawerControls)
        dock.drawerMoved(0f)
        assertTrue("Shut and armed: New Session already stands above the slot", !dock.isTravelling && dock.liftsDrawerControls)
        dock.drawerMoved(0.01f)
        assertTrue(dock.isTravelling && dock.liftsDrawerControls)
        dock.drawerMoved(1f)
        assertTrue(dock.isTravelling)
        // Hanging up in the drawer hands back, and New Session comes down.
        dock.setArmed(false)
        assertTrue(!dock.isTravelling && !dock.liftsDrawerControls)
        dock.setArmed(true)
        assertFalse("Re-armed while open still waits for the next trip", dock.isTravelling)
    }

    private fun dockedDock(): CallTrayDock {
        val dock = CallTrayDock()
        dock.setDrawer(
            CallTrayDockGeometry(
                slot = cgRect(12f, 816f, 278f, 56f),
                bounds = cgRect(0f, 0f, 302f, 932f),
                recedes = true,
                screen = cgRect(0f, 0f, 430f, 932f),
            ),
        )
        dock.noteSessionTrayWithoutAnchor(cgRect(16f, 822f, 398f, 56f))
        dock.setArmed(true)
        return dock
    }

    @Test
    fun hangingUpInTheDrawerKeepsTheSessionsTrayAwayUntilTheDrawerShuts() {
        val dock = dockedDock()
        dock.drawerMoved(0.5f)
        assertTrue(dock.isTravelling && dock.hidesSessionTray)
        dock.setArmed(false)
        assertFalse("The docked tray shrinks away", dock.isTravelling)
        assertTrue("The session's tray doesn't come back behind it", dock.hidesSessionTray)
        dock.drawerMoved(1f)
        assertTrue(dock.hidesSessionTray)
        dock.drawerMoved(0f)
        assertFalse(dock.hidesSessionTray)
    }

    @Test
    fun catchUpTakingTheScreenLeavesNothingDocked() {
        assertFalse(CallTrayDock.mayDock(usesTray = true, usesSidebar = false, isCatchingUp = true, isResting = true))
        val dock = dockedDock()
        dock.drawerMoved(1f)
        assertTrue(dock.isTravelling)
        // Catch Up opens from the open drawer: progress goes to 0 without the card moving.
        dock.drawerMoved(0f, travels = false)
        dock.setArmed(false)
        assertTrue(!dock.isTravelling && !dock.hidesSessionTray && !dock.liftsDrawerControls)
        // Done returns to the open drawer by a fade: no trip starts.
        dock.setArmed(true)
        dock.drawerMoved(1f, travels = false)
        assertTrue(!dock.isTravelling && !dock.hidesSessionTray)
    }

    @Test
    fun aSessionsTrayOffScreenNeverBecomesTheTripsStart() {
        val dock = dockedDock()
        // A subagent sliding away on Back reports its tray past the screen's edge.
        dock.noteSessionTrayWithoutAnchor(cgRect(230f, 822f, 398f, 56f))
        assertEquals(cgRect(16f, 822f, 398f, 56f), dock.sessionTrayFrame)
        dock.noteSessionTrayWithoutAnchor(cgRect(16f, 700f, 398f, 56f))
        assertEquals("On screen it still follows", 700f, dock.sessionTrayFrame.top)
    }

    @Test
    fun theTraysStartKeepsEasingWhileTheDrawerHolds() {
        val dock = dockedDock()
        dock.drawerMoved(0.4f)
        val start = CallTrayDock.nowSeconds()
        // The keyboard goes down while the finger rests: the drawer doesn't move.
        dock.noteSessionTrayWithoutAnchor(cgRect(16f, 870f, 398f, 56f))
        assertTrue(dock.isEasingOrigin(start + 0.01))
        val early = dock.frame(start + 0.05).top
        val later = dock.frame(start + 0.2).top
        val settled = dock.frame(start + 5).top
        assertTrue("Time alone moves it: $early $later $settled", early < later && later < settled)
        assertFalse(dock.isEasingOrigin(start + 5))
    }

    @Test
    fun callTrayDockTravelsStraightFromTheSessionToTheSlot() {
        val session = cgRect(16f, 822f, 398f, 56f)
        val slot = cgRect(12f, 816f, 278f, 56f)
        assertEquals(session, CallTrayDock.travelFrame(origin = session, slot = slot, progress = 0f, widthLead = 1.3f))
        assertEquals(slot, CallTrayDock.travelFrame(origin = session, slot = slot, progress = 1f, widthLead = 1.3f))
        var last = session
        for (step in 1..60) {
            val frame = CallTrayDock.travelFrame(origin = session, slot = slot, progress = step.toFloat() / 60, widthLead = 1.3f)
            assertTrue("Each edge travels one way", frame.left <= last.left && frame.right <= last.right && frame.top <= last.top)
            last = frame
        }
    }

    @Test
    fun callTrayDockKeepsTheSessionTraysSidewaysPlaceWhileTheCardMoves() {
        val dock = CallTrayDock()
        dock.noteSessionTrayWithoutAnchor(cgRect(16f, 500f, 398f, 56f))
        dock.drawerMoved(0.5f)
        dock.noteSessionTrayWithoutAnchor(cgRect(216f, 822f, 398f, 56f))
        assertEquals(cgRect(16f, 822f, 398f, 56f), dock.sessionTrayFrame)
    }

    @Test
    fun callTrayControllerLiftsComposerSplitsBeneathAndReversesOnHangup() {
        val controller = CallTrayController()
        val composer = cgRect(20f, 754f, 350f, 56f)
        controller.noteComposer(frame = composer, isFocused = false)
        assertEquals(false, controller.holdsCallButton)
        assertEquals(0, controller.tapGeneration)

        fun geometry(reducedMotion: Boolean = false) = controller.geometry(width = 350f, isFocused = false, reducedMotion = reducedMotion)
        val idleGeometry = geometry()
        val restButtonX = idleGeometry.seed.x
        val restButtonY = idleGeometry.seed.y
        assertTrue(abs(restButtonX - (350 - 28)) < 0.5f)
        assertTrue(abs(restButtonY - (120 - 28)) < 0.5f)

        controller.beginFromTap()
        assertEquals(true, controller.holdsCallButton)
        assertEquals(1, controller.tapGeneration)
        assertEquals("Record button starts at full opacity on the tap frame", 1f, controller.composerMicLook().opacity)
        assertEquals("Discrete reserve stays 0 during the initial button lift", 0f, controller.discreteSessionReserve)

        val fullReserve = CallTrayTunables.height + CallTrayTunables.gap
        val observedReserves = mutableListOf(controller.discreteSessionReserve)
        fun recordReserve() {
            val current = controller.discreteSessionReserve
            if (observedReserves.last() != current) observedReserves += current
        }

        // 1. Lift hold phase (0 < t < 0.15 s): call button lifts in place, composer dips down,
        // mic button fades/scales out, and the tray glass has not split yet.
        repeat(5) {
            controller.step(dt = 1.0 / 60)
            recordReserve()
        }
        val liftGeometry = geometry()
        assertTrue("Composer dips in anticipation as the call button lifts", liftGeometry.composerDip > 0.5f)
        assertTrue("Call button lifts upward during lift hold", liftGeometry.seed.y < restButtonY - 1)
        assertTrue("Call button stays horizontally under the thumb", abs(liftGeometry.seed.x - restButtonX) < 0.5f)
        assertTrue("Call button scales up on lift", liftGeometry.seed.diameter > 35)
        assertEquals("No 56 pt glass halo around the button while inside the resting composer", false, liftGeometry.showsGlassSurface)
        val midLiftMic = controller.composerMicLook()
        assertTrue("Record button fades out continuously", midLiftMic.opacity > 0.1f && midLiftMic.opacity < 0.9f)
        assertTrue("Record button scales and blurs out continuously", midLiftMic.scale < 1.0f && midLiftMic.blur > 0)

        // 2. Unfold phase (0.15 s <= t < 0.39 s): discrete session reserve flips once to 64 pt,
        // composer lifts off the button, and the tray glass droplet-splits from the composer's bottom edge.
        repeat(10) {
            controller.step(dt = 1.0 / 60)
            recordReserve()
        }
        val unfoldGeometry = geometry()
        assertEquals("Discrete reserve flips to height + gap once unfold begins", fullReserve, controller.discreteSessionReserve)
        assertEquals("Tray glass mounts inside GlassEffectContainer for the droplet split", true, unfoldGeometry.showsGlassSurface)
        assertEquals("Droplet merge spacing matches CallTrayTunables.glassSpacing", 8f, unfoldGeometry.dropletMergeSpacing)
        assertTrue("Composer lifts upward off the call button", unfoldGeometry.composerOffsetY < -10)
        assertTrue("Call button stays horizontally under the thumb", abs(unfoldGeometry.seed.x - restButtonX) < 0.5f)

        // 3. Widen phase (t >= 0.39 s): wind-up squeezes toward End, shoots out past full width, and settles.
        var minWidthDuringWiden = Float.MAX_VALUE
        var maxWidthDuringWiden = 0f
        repeat(105) {
            controller.step(dt = 1.0 / 60)
            recordReserve()
            val g = geometry()
            minWidthDuringWiden = min(minWidthDuringWiden, g.surf.width)
            maxWidthDuringWiden = max(maxWidthDuringWiden, g.surf.width)
        }
        assertTrue("Wind-up squeezes the ball before shooting left", minWidthDuringWiden < 56)
        assertTrue("Shoot overshoots full width before settling", maxWidthDuringWiden > 352)

        val openGeometry = geometry()
        assertTrue("Composer settles height + gap above its resting spot", abs(openGeometry.composerOffsetY - (-fullReserve)) < 0.5f)
        assertEquals("Tray settles in the composer's resting spot beneath the lifted composer", cgRect(0f, 64f, 350f, 56f), openGeometry.trayRect)
        assertTrue("End lands at the exact X of the call button", abs(openGeometry.seed.x - restButtonX) < 0.5f)
        assertTrue("End lands at the exact Y of the call button", abs(openGeometry.seed.y - restButtonY) < 0.5f)
        assertTrue("Phone glyph spins 495 degrees into End", abs(openGeometry.seed.angle - 495) < 1)
        assertTrue("Call button morphs into the red End button", openGeometry.seed.callness > 0.99f)
        assertTrue("Record button is fully hidden while the tray is open", controller.composerMicLook().opacity < 0.005f)

        // 4. Hang up: narrows toward End first (closeStagger), then lowers the composer back over the button.
        controller.end(reducedMotion = false)
        repeat(4) {
            controller.step(dt = 1.0 / 60)
            recordReserve()
        }
        val narrowGeometry = geometry()
        assertTrue("Tray narrows from the left toward End before the composer descends", narrowGeometry.surf.left > 10)
        assertTrue("Trailing edge stays anchored at End while narrowing", abs(narrowGeometry.surf.right - 350) < 1)
        assertEquals("Composer waits for closeStagger before releasing reserve", fullReserve, controller.discreteSessionReserve)

        repeat(8) {
            controller.step(dt = 1.0 / 60)
            recordReserve()
        }
        val midCloseMic = controller.composerMicLook()
        assertTrue("Record button fades back in continuously on hang-up", midCloseMic.opacity > 0.05f && midCloseMic.opacity < 0.95f)

        repeat(168) {
            controller.step(dt = 1.0 / 60)
            recordReserve()
        }
        assertEquals(CallTrayController.Phase.idle, controller.phase)
        assertEquals(false, controller.holdsCallButton)
        assertEquals(1f, controller.composerMicLook().opacity)
        assertEquals(
            "Discrete session reserve flips once on open and once on close, never per tick",
            listOf(0f, fullReserve, 0f),
            observedReserves,
        )

        // 5. Reduce Motion: cross-fades without button lift, dip, or spin.
        controller.start(fromTap = true, alreadyConnected = false, reducedMotion = true)
        assertEquals(fullReserve, controller.discreteSessionReserve)
        repeat(20) { controller.step(dt = 1.0 / 60) }
        val reducedOpen = geometry(reducedMotion = true)
        assertEquals(0f, reducedOpen.composerDip)
        assertEquals(0f, reducedOpen.dropletMergeSpacing)
        assertTrue(reducedOpen.trayOpacity > 0.99f)
        controller.end(reducedMotion = true)
        assertEquals(0f, controller.discreteSessionReserve)
        repeat(30) { controller.step(dt = 1.0 / 60) }
        assertEquals(CallTrayController.Phase.idle, controller.phase)
    }

    private data class Sample(
        val luminance: Float,
        val rgb: Triple<Float, Float, Float>,
        val alpha: Double,
        val opacity: Float,
        val callness: Float,
        val phoneGlyph: Float,
    )

    /**
     * OC-473: the call button turning into End is one disc blending its colour, never a white
     * disc that dissolves to the backdrop and comes back red (Kit's "flash when the colour
     * changes"), on the way in and on hang-up, with and without Reduce Motion.
     */
    @Test
    fun callTrayButtonBlendsIntoEndWithoutDippingThroughTheBackdrop() {
        for ((reducedMotion, isDark) in listOf(false to true, false to false, true to true, true to false)) {
            val args = "reducedMotion=$reducedMotion isDark=$isDark"
            val controller = CallTrayController()
            controller.noteComposer(frame = cgRect(20f, 754f, 350f, 56f), isFocused = false)

            val backdrop = if (isDark) 0f else 1f
            fun sample(): Sample {
                val g = controller.geometry(width = 350f, isFocused = false, reducedMotion = reducedMotion)
                val look = CallTrayController.buttonLook(
                    callStyle = CallTrayCallButtonStyle.disc, endStyle = CallTrayEndButtonStyle.red,
                    dissolve = g.seed.dissolve, callness = g.seed.callness, isDark = isDark,
                )
                val r = look.disc.red; val gr = look.disc.green; val b = look.disc.blue
                val alpha = look.discAlpha
                // What the eye sees: the disc composited over the backdrop behind it.
                fun over(c: Float) = c * alpha + backdrop * (1 - alpha)
                val luminance = 0.2126f * over(r) + 0.7152f * over(gr) + 0.0722f * over(b)
                return Sample(luminance, Triple(r, gr, b), look.discAlpha.toDouble(), g.seed.opacity, g.seed.callness, g.seed.phoneGlyph)
            }

            if (reducedMotion) {
                controller.start(fromTap = true, alreadyConnected = false, reducedMotion = true)
            } else {
                controller.beginFromTap()
            }
            val opening = mutableListOf(sample())
            repeat(200) {
                controller.step(dt = 1.0 / 120)
                opening += sample()
            }
            controller.end(reducedMotion = reducedMotion)
            val closing = mutableListOf(sample())
            for (i in 0 until 360) {
                if (controller.phase == CallTrayController.Phase.idle) break
                controller.step(dt = 1.0 / 120)
                closing += sample()
            }
            assertEquals(args, CallTrayController.Phase.idle, controller.phase)

            // The seed takes over from, and lands back as, the composer's own handset glyph.
            assertEquals("Opening starts as the composer's phone.fill ($args)", 0f, opening.first().phoneGlyph)
            val lastShown = closing.lastOrNull { it.opacity > 0.001f }
            assertNotNull(args, lastShown)
            assertTrue("Closing lands as the composer's phone.fill ($args)", lastShown!!.phoneGlyph < 0.001f)
            if (!reducedMotion) {
                assertTrue("Opening ends as End's phone.down.fill ($args)", opening.last().phoneGlyph > 0.999f)
            }

            for ((name, samples) in listOf("opening" to opening, "closing" to closing)) {
                val first = samples.first(); val last = samples.last()
                val lo = min(first.luminance, last.luminance) - 0.01f
                val hi = max(first.luminance, last.luminance) + 0.01f
                samples.forEachIndexed { index, s ->
                    if (s.opacity <= 0.001f) return@forEachIndexed
                    assertTrue("$name frame $index: the disc stays opaque (alpha ${s.alpha}) ($args)", s.alpha > 0.999)
                    assertTrue(
                        "$name frame $index: luminance ${s.luminance} leaves $lo…$hi, so the disc flashes through the backdrop ($args)",
                        s.luminance >= lo && s.luminance <= hi,
                    )
                    if (s.opacity < 0.99f) {
                        assertTrue("$name frame $index: the disc only hands over to End once it already looks like End ($args)", s.callness > 0.84f)
                    }
                }
                for ((prev, next) in samples.zipWithNext()) {
                    if (!(prev.opacity > 0.001f && next.opacity > 0.001f)) continue
                    val step = maxOf(abs(next.rgb.first - prev.rgb.first), abs(next.rgb.second - prev.rgb.second), abs(next.rgb.third - prev.rgb.third))
                    assertTrue("$name: the colour moves continuously (one-frame step $step) ($args)", step < 0.12f)
                }
            }
            val open = opening.last()
            assertTrue("The call button arrives as End ($args)", open.callness > 0.99f || open.opacity < 0.001f)
        }
    }

    // The `SessionFeature.State.canStartCallFromTap` half belongs to :feature:session.
    @Test
    fun tapIntoProjectPickerOrImmediateFailureLeavesNoTray() {
        val controller = CallTrayController()
        // Even if beginFromTap ran when no call started (voicePhase == nil), reconcile resets at once.
        controller.beginFromTap()
        assertEquals(CallTrayController.Phase.live, controller.phase)
        controller.reconcile(voicePhase = null)
        assertEquals(CallTrayController.Phase.idle, controller.phase)
        assertEquals(false, controller.showsSurface)
        assertEquals(false, controller.holdsCallButton)

        // A call that fails at once into .failed resets the tray.
        controller.beginFromTap()
        controller.reconcile(voicePhase = VoiceFeature.State.Phase.Connecting)
        assertEquals(CallTrayController.Phase.live, controller.phase)
        assertEquals(true, controller.holdsCallButton)
        controller.reconcile(voicePhase = VoiceFeature.State.Phase.Failed("Add your OpenAI API key in Settings to use Voice."))
        assertEquals(CallTrayController.Phase.idle, controller.phase)
        assertEquals(false, controller.showsSurface)
        assertEquals(false, controller.holdsCallButton)

        // A live unfolded tray that fails or drops into a card also resets.
        controller.start(fromTap = false, alreadyConnected = true, reducedMotion = false)
        repeat(60) { controller.step(dt = 1.0 / 60) }
        controller.reconcile(voicePhase = VoiceFeature.State.Phase.Dropped)
        assertEquals(CallTrayController.Phase.idle, controller.phase)
        assertEquals(false, controller.showsSurface)
        assertEquals(false, controller.holdsCallButton)
    }
}
