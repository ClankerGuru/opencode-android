package ai.opencode.feature.transcript.links

import ai.opencode.feature.media.LinkPreview
import ai.opencode.feature.media.LinkPreviewMetadata
import ai.opencode.feature.media.LinkPreviewResult
import ai.opencode.feature.media.LinkPreviewTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of iOS `LinkPreviewMotionTests.swift`. */
class LinkPreviewMotionTests {
    // catchUpBeforeFirstLayoutCannotDisableLaterMetadataGrowth: dropped. It drives the UIKit
    // `TranscriptListViewController` harness (`establishContentLayoutIfNeeded` on a hosted collection
    // view). Android's `TranscriptListController.landArrivals` sets `catchUpLandsUntil` from now plus
    // `BottomSpring.OPENING_SETTLE` (never from `arrivalsGlideAfter`), and its first layout sets
    // `arrivalsGlideAfter` on its own, so a catch-up before the first layout can't hold it forever.

    private fun preview(title: String? = null): LinkPreview {
        val target = requireNotNull(LinkPreviewTarget.of("https://github.com/acme/widget/pull/1240"))
        return LinkPreview.of(target, title?.let { LinkPreviewResult.Loaded(LinkPreviewMetadata(kind = LinkPreviewMetadata.Kind.Page, title = it)) })
    }

    @Test fun reloadKeepsTheOriginalClockAndHeightForGrowthAndCollapse() {
        val row = LinkPreviewRowPresentation()
        val pending = preview()
        val loaded = preview("A title wrapping onto three lines")
        assertEquals("History has no entrance", 1.0, row.change(pending, now = 1.0, duration = 0.2).progress(1.0), 0.0)
        assertEquals(0f, row.measure(44f, pending))
        val arrival = row.change(loaded, now = 2.0, duration = 0.2)
        assertEquals("A cell reload cannot replay metadata", arrival.started, row.change(loaded, now = 2.1, duration = 0.2).started, 0.0)
        assertEquals(66f, row.measure(110f, loaded))
        assertEquals("A reload cannot report growth twice", 0f, row.measure(110f, loaded))
        assertEquals("A departing cell cannot overwrite the new measurement", 0f, row.measure(44f, pending))
        val failed = LinkPreview.of(pending.target, LinkPreviewResult.Unavailable)
        row.change(failed, now = 3.0, duration = 0.2)
        assertEquals("Failures use the same signed height path", -66f, row.measure(44f, failed))
    }

    @Test fun aLaterMetadataUpdateKeepsEveryVisibleLayerAtItsCurrentOpacity() {
        val row = LinkPreviewRowPresentation()
        val pending = preview()
        val first = preview("First title")
        val second = preview("Updated title")
        row.change(pending, now = 0.0, duration = 1.0)
        val firstChange = row.change(first, now = 1.0, duration = 1.0)
        val before = firstChange.layers(1.25)
        val after = row.change(second, now = 1.25, duration = 1.0).layers(1.25)
        assertEquals(before.map { it.preview }, after.map { it.preview })
        assertEquals("Retargeting must not pop a half-faded layer", before.map { it.opacity }, after.map { it.opacity })
    }

    @Test fun anArrivingRowReportsEachLinksHeightOnce() {
        val row = LinkPreviewRowPresentation()
        row.arrives = true
        val pending = preview()
        assertEquals(0.0, row.change(pending, now = 1.0, duration = 0.2).progress(1.0), 0.0)
        assertEquals("Copy rides the same block growth as the new links", 44f, row.measure(44f, pending))
        assertEquals(0f, row.measure(44f, pending))
    }
}
