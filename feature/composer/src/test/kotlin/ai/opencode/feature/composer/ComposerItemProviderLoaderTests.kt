package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeOutgoingAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `ComposerItemProviderLoaderTests.swift` ([ComposerImageImporter]).
 *
 * Dropped: `textViewRoutesImagePasteWithoutChangingText` (UIKit `ImagePasteTextView.paste`;
 * Android routes pastes through the field's `contentReceiver`) and
 * `loadsFileBackedAndUIImageProviders` (decodes `NSItemProvider`s with UIKit; Android's
 * [ComposerItemProviderLoader] reads a `ContentResolver`, which JVM tests don't have).
 * `imageImportPreservesMixedSourceOrder` keeps its mixed order with a loader in place of the
 * item provider for the same reason.
 * Skipped → `:feature:session`: `composerOffersACallWhenVoiceIsSetUp`,
 * `composerControlPresentation` (`ComposerControls` lives in `SessionViewComposerControls.kt`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerItemProviderLoaderTests {
    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun attachment(data: ByteArray, id: UUID) = OpenCodeOutgoingAttachment(id = id.toString(), data = data, mime = "image/test")

    private val process: suspend (ByteArray, UUID) -> OpenCodeOutgoingAttachment? = { data, id -> attachment(data, id) }

    /** The importer's callbacks run on Main; it shares the test's scheduler. */
    private fun TestScope.importer(): ComposerImageImporter {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return ComposerImageImporter(this)
    }

    private suspend fun waitForImport(importer: ComposerImageImporter) {
        while (importer.isLoading) yield()
    }

    @Test fun imageImportPreservesMixedSourceOrder() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            listOf(
                ComposerImageSource.Data(byteArrayOf(1)),
                ComposerImageSource.Loader { yield(); byteArrayOf(2) },
                ComposerImageSource.Loader { byteArrayOf(3) },
            ),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(1, 2, 3), imported)
    }

    @Test fun imageImportEnforcesSelectionLimitOnce() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            (1..6).map { ComposerImageSource.Data(byteArrayOf(it.toByte())) },
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(1, 2, 3, 4), imported)
    }

    @Test fun secondImageImportCancelsFirst() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            listOf(
                ComposerImageSource.Loader {
                    delay(10.seconds)
                    byteArrayOf(1)
                },
            ),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        yield()
        importer.start(
            listOf(ComposerImageSource.Data(byteArrayOf(2))),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(2), imported)
    }

    @Test fun partialImageImportFailureKeepsSuccessfulImages() = runTest {
        val importer = importer()
        val imported = mutableListOf<Byte>()

        importer.start(
            listOf(ComposerImageSource.Data(byteArrayOf(1)), ComposerImageSource.Loader { null }, ComposerImageSource.Data(byteArrayOf(3))),
            process = process,
            onAttachment = { imported += it.data[0] },
        )
        waitForImport(importer)

        assertEquals(listOf<Byte>(1, 3), imported)
    }

    @Test fun failedImportSettlesSoTheNextImportCanStart() = runTest {
        val importer = importer()
        val results = mutableListOf<ComposerImageImporter.Result>()
        val imported = mutableListOf<Byte>()

        importer.start(listOf(ComposerImageSource.Loader { null }), onAttachment = {}, onCompletion = { results += it })
        assertTrue(importer.isLoading)
        waitForImport(importer)
        assertEquals(listOf(0), results.map { it.addedCount })

        importer.start(
            listOf(ComposerImageSource.Data(byteArrayOf(7))),
            process = process,
            onAttachment = { imported += it.data[0] },
            onCompletion = { results += it },
        )
        waitForImport(importer)
        assertEquals(listOf<Byte>(7), imported)
        assertEquals(listOf(0, 1), results.map { it.addedCount })
    }

    @Test fun cancelledImportStopsLoadingAndNeverCompletes() = runTest {
        val importer = importer()
        val gate = CompletableDeferred<Unit>()
        var completions = 0

        importer.start(
            listOf(
                ComposerImageSource.Loader {
                    gate.await()
                    byteArrayOf(1)
                },
            ),
            process = process,
            onAttachment = { fail("A cancelled import must not attach") },
            onCompletion = { completions += 1 },
        )
        yield()
        importer.cancel()
        assertFalse(importer.isLoading)
        gate.complete(Unit)
        repeat(10) { yield() }
        assertEquals(0, completions)
    }

    @Test fun attachmentCallbackCanEndTheImport() = runTest {
        for (supersede in listOf(false, true)) {
            val importer = importer()
            var loads = 0
            var completions = 0
            val imported = mutableListOf<Byte>()

            importer.start(
                listOf(
                    ComposerImageSource.Data(byteArrayOf(1)),
                    ComposerImageSource.Loader {
                        loads += 1
                        byteArrayOf(2)
                    },
                ),
                process = process,
                onAttachment = { attachment ->
                    imported += attachment.data[0]
                    if (attachment.data[0] == 1.toByte()) {
                        if (supersede) {
                            importer.start(listOf(ComposerImageSource.Data(byteArrayOf(9))), process = process, onAttachment = { imported += it.data[0] })
                        } else {
                            importer.cancel()
                        }
                    }
                },
                onCompletion = { completions += 1 },
            )
            waitForImport(importer)
            repeat(10) { yield() }

            assertEquals("supersede=$supersede", 0, loads)
            assertEquals("supersede=$supersede", 0, completions)
            assertEquals("supersede=$supersede", if (supersede) listOf<Byte>(1, 9) else listOf<Byte>(1), imported)
        }
    }
}
