package ai.opencode.feature.composer.plusmenu

import ai.opencode.feature.composer.ComposerImageImporter
import ai.opencode.feature.composer.ComposerImageSource
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * Port of iOS `PhotoFlightTests+Reservations.swift` (an extension of `PhotoFlightTests`):
 * reserved attachment slots, held until their import arrives, fails or is cancelled, and a new
 * row held for the frame it mounts. Geometry at density 1, as in [PhotoFlightTests].
 *
 * iOS's display link for `revealHeldRow` is the caller's frame clock here: a
 * [BroadcastFrameClock] stands in for the next display frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFlightTestsReservations {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private val bounds = rect(0f, 0f, 400f, 900f)

    private fun rect(x: Float, y: Float, width: Float, height: Float) = Rect(Offset(x, y), Size(width, height))

    /** The importer launches on Dispatchers.Main, which dispatches (as Android's main looper does). */
    private fun dispatchingMainTest(body: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) { body() }
    }

    @Test fun reservedAttachmentsKeepMeasuredSlotsUntilImportArrives() {
        val flights = PhotoFlights()
        try {
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            flights.reserve(listOf(first, second))
            assertEquals(listOf(first, second), flights.reservedAttachments)
            flights.composer(rect(10f, 350f, 380f, 200f))
            val firstSlot = rect(20f, 370f, 76f, 76f)
            val secondSlot = rect(104f, 370f, 76f, 76f)
            flights.slot(first, firstSlot)
            flights.slot(second, secondSlot)
            flights.source("p1", PhotoFlights.Source(frame = rect(20f, 600f, 100f, 100f), radius = 0f))
            flights.source("p2", PhotoFlights.Source(frame = rect(130f, 600f, 100f, 100f), radius = 0f))
            flights.prepare(listOf(PhotoFlights.Pick("p1", first), PhotoFlights.Pick("p2", second)), windowBounds = bounds)
            val f1 = flights.flights.first()
            assertEquals(firstSlot, flights.resolvedSlot(f1))
            flights.attachmentsChanged(listOf(first))
            assertEquals(listOf(second), flights.reservedAttachments)
            assertTrue(f1.arrived)
            flights.attachmentsChanged(listOf(first, second))
            assertTrue(flights.reservedAttachments.isEmpty())
        } finally {
            flights.reset()
        }
    }

    @Test fun aReservedRowThatJustMountedOpensOnTheNextFrame() = runTest {
        val flights = PhotoFlights()
        try {
            val id = UUID.randomUUID()
            flights.reserve(listOf(id), holdingRow = true)
            assertTrue(flights.holdsReservedRow)
            val clock = BroadcastFrameClock()
            val reveal = launch(clock) { flights.revealHeldRow() }
            runCurrent()
            assertTrue(flights.holdsReservedRow)
            clock.sendFrame(0L)
            reveal.join()
            assertFalse(flights.holdsReservedRow)
            assertEquals(listOf(id), flights.reservedAttachments)
            // A row already showing isn't held, and a reset lets a held one go.
            flights.reserve(listOf(UUID.randomUUID()))
            assertFalse(flights.holdsReservedRow)
            flights.reserve(listOf(UUID.randomUUID()), holdingRow = true)
            flights.reset()
            assertFalse(flights.holdsReservedRow)
        } finally {
            flights.reset()
        }
    }

    @Test fun fastMissingAssetFailureReleasesReservationViaExplicitCallbacks() = dispatchingMainTest {
        val flights = PhotoFlights()
        try {
            val importer = ComposerImageImporter(backgroundScope)
            val missing = UUID.randomUUID()
            flights.reserve(listOf(missing))
            flights.source("p1", PhotoFlights.Source(frame = rect(20f, 600f, 100f, 100f), radius = 0f))
            flights.prepare(listOf(PhotoFlights.Pick("p1", missing)), windowBounds = bounds)
            val flight = flights.flights.first()

            val failedIDs = mutableListOf<UUID>()
            var completed = false
            importer.start(
                listOf(ComposerImageSource.Loader(attachmentID = missing) { null }),
                onAttachment = { fail("Missing asset must not attach") },
                onFailure = { failedID ->
                    if (failedID != null) {
                        failedIDs += failedID
                        flights.dropReservation(failedID)
                    }
                },
                onCancel = { flights.importFinished() },
                onCompletion = {
                    completed = true
                    flights.importFinished()
                },
            )
            while (importer.isLoading) yield()

            assertEquals(listOf(missing), failedIDs)
            assertTrue(completed)
            assertTrue(flights.reservedAttachments.isEmpty())
            assertEquals(PhotoFlights.Flight.State.Leaving, flight.state)
        } finally {
            flights.reset()
        }
    }

    @Test fun cancelledImportReleasesReservedSlotsViaCancelCallback() = dispatchingMainTest {
        val flights = PhotoFlights()
        try {
            val importer = ComposerImageImporter(backgroundScope)
            val id = UUID.randomUUID()
            flights.reserve(listOf(id))
            val gate = CompletableDeferred<Unit>()

            importer.start(
                listOf(
                    ComposerImageSource.Loader(attachmentID = id) {
                        gate.await()
                        byteArrayOf(1)
                    },
                ),
                onAttachment = {},
                onCancel = { flights.importFinished() },
            )
            yield()
            assertEquals(listOf(id), flights.reservedAttachments)
            importer.cancel()
            gate.complete(Unit)
            assertTrue(flights.reservedAttachments.isEmpty())
        } finally {
            flights.reset()
        }
    }
}
