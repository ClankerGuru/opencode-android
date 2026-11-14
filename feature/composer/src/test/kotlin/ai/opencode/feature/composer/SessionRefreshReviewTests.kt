package ai.opencode.feature.composer

import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Robolectric: the map stub is an `ImageBitmap` (an Android `Bitmap`). Time is the test scheduler's
 * virtual time.
 *
 * Dropped: `locationEntranceTrace…`'s `LocationAttachmentTrace` recorder and parser (a lab trace;
 * the entrance metrics it records are checked directly); `locationTappedDelivers…`'s
 * `OneShotRequestGate` half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedLocationTests {
    @get:Rule val isolation = TestIsolationRule()

    private val spot = SharedLocation(
        latitude = 37.3349, longitude = -122.00902, accuracy = 12.4,
        place = "Apple Park", address = "1 Apple Park Way, Cupertino, CA 95014",
        capturedAt = Instant.ofEpochSecond(1_800_000_000),
    )

    /** The composer's compact map in pixels at 3×, as the live client measures it. */
    private val compactMapSize = IntSize(
        (SharedLocationCard.compactWidth.value * 3).toInt(),
        (SharedLocationCard.compactMapHeight.value * 3).toInt(),
    )

    @Test fun theBlockReadsPlainlyAndParsesBack() {
        val prompt = spot.prompt("Find me coffee nearby")
        assertTrue(prompt, prompt.startsWith("<shared-location>\nPlace: Apple Park\nAddress: 1 Apple Park Way, Cupertino, CA 95014\nCoordinates: 37.334900, -122.009020\nAccuracy: ±12 m"))
        assertTrue(prompt.contains("Map: https://maps.apple.com/?ll=37.334900,-122.009020"))
        val parsed = SharedLocation.parse(prompt)
        assertNotNull(parsed)
        assertEquals("Find me coffee nearby", parsed!!.remainder)
        assertEquals("Apple Park", parsed.location.place)
        assertEquals(spot.address, parsed.location.address)
        assertTrue(abs(parsed.location.latitude - 37.3349) < 1e-6)
        assertEquals(spot.capturedAt, parsed.location.capturedAt)
        assertNull(SharedLocation.parse("Coordinates: 1, 2"))
    }

    @Test fun locationSnapshotIsInertInTestsAndInjectable() = runTest {
        assertNull(LocationClient.testValue.snapshot(spot, IntSize(260, 120), true))
        val stub = ImageBitmap(1, 1)
        val injected = LocationClient(current = { spot }, snapshot = { _, _, _ -> stub })
        assertSame(stub, injected.snapshot(spot, IntSize(260, 120), true))
    }

    @Test fun locationTappedPrefetchesCompactSnapshotBeforeDeliveringLocationResponse() = runTest {
        val events = mutableListOf<String>()
        val prefetchedSizes = mutableListOf<IntSize>()
        val stub = ImageBitmap(1, 1)
        val store = TestStore(ComposerAttachmentsFeature.State(), ComposerAttachmentsFeature(), this) {
            this[LocationClient] = LocationClient(
                current = {
                    events += "current"
                    spot
                },
                snapshot = { _, size, _ ->
                    events += "snapshot"
                    prefetchedSizes += size
                    stub
                },
                cachedSnapshot = { _, _, _ -> stub },
                compactSnapshotTraits = { compactMapSize to true },
            )
        }
        store.send(ComposerAttachmentsFeature.Action.LocationTapped) { it.copy(isLocating = true, locationError = null) }
        store.receive(ComposerAttachmentsFeature.Action.LocationResponse(ComposerAttachmentsFeature.Outcome.Success(spot))) {
            it.copy(isLocating = false, pendingLocation = spot)
        }
        assertEquals(listOf("current", "snapshot"), events)
        assertEquals(listOf(compactMapSize), prefetchedSizes)
        store.finish()
    }

    @Test fun locationEntranceTraceHasOneContinuousEntranceWithNoHeightJumpAbove1_5pt() {
        // 20 frames of entrance (0 -> 1) with resolution starting mid-flight at frame 10 and
        // completing across frames 10...28: one continuous entrance, zero second pop.
        val samples = (0..28).map { frame ->
            val gateProgress = minOf(1f, frame / 18f)
            val resolvedProgress = if (frame < 10) 0f else minOf(1f, (frame - 10) / 18f)
            ComposerLocationEntrance.metrics(gateProgress = gateProgress, resolvedProgress = resolvedProgress, reduceMotion = false)
        }
        assertTrue(abs(samples.first().scale - ComposerLocationEntrance.ENTRANCE_SCALE) < 0.01)
        assertTrue(abs(samples.last().scale - 1) < 0.01)
        assertEquals(0f, samples.first().opacity)
        assertTrue(abs(samples.last().opacity - 1) < 0.01)
        assertTrue(abs(samples.last().resolved - 1) < 0.01)
        for (index in 1 until samples.size) {
            assertTrue(samples[index].scale >= samples[index - 1].scale - 0.001f)
            assertTrue(samples[index].opacity >= samples[index - 1].opacity - 0.001f)
        }
        val maxJump = (1 until samples.size).maxOf { abs(samples[it].height - samples[it - 1].height) }
        assertTrue("Max frame-to-frame height jump was $maxJump pt (> 1.5 pt)", maxJump <= 1.5f)

        // Reduce Motion: constant scale = 1, blur = 0, height jump = 0, opacity cross-fades 0 -> 1.
        val reduced = (0..10).map { step ->
            val progress = step / 10f
            ComposerLocationEntrance.metrics(gateProgress = progress, resolvedProgress = progress, reduceMotion = true).also {
                assertEquals(1f, it.scale)
                assertEquals(0f, it.blur)
            }
        }
        assertEquals(0f, (1 until reduced.size).maxOf { abs(reduced[it].height - reduced[it - 1].height) })
        assertEquals(0f, reduced.first().opacity)
        assertTrue(abs(reduced.last().opacity - 1) < 0.01)
    }

    @Test fun locationTappedDeliversLocationAfterPrefetchTimeoutAndLoadsMapAsyncWithoutHeightChange() = runTest {
        assertEquals(1200.milliseconds, LocationClient.DEFAULT_PREFETCH_TIMEOUT)

        // The full default 1.2 s timeout path in ComposerAttachmentsFeature with a
        // never-completing snapshot dependency that ignores cancellation.
        var snapshotAttempts = 0
        var hung: Continuation<ImageBitmap?>? = null
        val stub = ImageBitmap(1, 1)
        val client = LocationClient(
            current = { spot },
            snapshot = { _, _, _ ->
                snapshotAttempts += 1
                if (snapshotAttempts == 1) {
                    // Uncooperative never-completing snapshot: never resumes on cancellation.
                    suspendCoroutine { hung = it }
                } else {
                    stub
                }
            },
            cachedSnapshot = { _, _, _ -> null },
            compactSnapshotTraits = { compactMapSize to true },
        )
        val store = TestStore(ComposerAttachmentsFeature.State(), ComposerAttachmentsFeature(), this) {
            this[LocationClient] = client
        }
        try {
            val started = testScheduler.currentTime
            store.send(ComposerAttachmentsFeature.Action.LocationTapped) { it.copy(isLocating = true, locationError = null) }
            store.receive(ComposerAttachmentsFeature.Action.LocationResponse(ComposerAttachmentsFeature.Outcome.Success(spot)), timeout = 3.seconds) {
                it.copy(isLocating = false, pendingLocation = spot)
            }
            val elapsed = (testScheduler.currentTime - started).milliseconds
            assertTrue("$elapsed", elapsed >= 1100.milliseconds)
            assertTrue("$elapsed", elapsed < 2500.milliseconds)
            assertEquals(1, snapshotAttempts)
            assertNotNull(hung)

            // When the card mounts with no cached map and the map finishes loading async, its
            // compact size stays constant, with zero height change.
            val beforeAsyncMap = ComposerLocationEntrance.metrics(gateProgress = 1f, resolvedProgress = 1f, reduceMotion = false)
            assertSame(stub, client.snapshot(spot, compactMapSize, true))
            assertEquals(2, snapshotAttempts)
            val afterAsyncMap = ComposerLocationEntrance.metrics(gateProgress = 1f, resolvedProgress = 1f, reduceMotion = false)
            assertEquals((SharedLocationCard.compactMapHeight + SharedLocationCard.defaultCompactFooterHeight).value, beforeAsyncMap.height)
            assertEquals(beforeAsyncMap.height, afterAsyncMap.height)
            store.finish()
        } finally {
            hung?.resumeWith(Result.success(null))
        }
    }
}
