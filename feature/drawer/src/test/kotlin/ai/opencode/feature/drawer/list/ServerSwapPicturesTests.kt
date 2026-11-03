package ai.opencode.feature.drawer.list

import ai.opencode.feature.drawer.ServerSwapTarget
import ai.opencode.feature.drawer.motion.ServerSwapMotion
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Server Flow's leaving pictures ([ServerSwapPictures]) against a real `LazyColumn`: a layer a
 * switch takes is the leaving server's alone, so a row the next server shows under the same key
 * (the Recents header, or the same session on both servers) records itself again instead of
 * drawing the picture as it fades out and is released (the header that stayed blank after a Pin).
 * Not an iOS port: iOS's snapshot views are copies, so it has nothing to pin here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerSwapPicturesTests {
    private class Harness {
        val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        val state = LazyListState()
        val keys = listOf("header-recents", "session-1", "session-2")
        val draws = HashMap<String, Int>()
        lateinit var pictures: ServerSwapPictures
        val motion = ServerSwapMotion().apply { sync(serverA, animated = false) }

        init {
            activity.setContentView(view)
            view.setContent {
                val graphics = LocalGraphicsContext.current
                val swap = remember { ServerSwapPictures(graphics) }.also { pictures = it }
                LazyColumn(state = state, modifier = Modifier.size(120.dp, 300.dp)) {
                    items(keys, key = { it }) { key ->
                        Box(
                            Modifier
                                // A layer per row, as Server Flow's `serverSwapRow` gives it.
                                .graphicsLayer { }
                                .serverSwapPicture(swap, key)
                                .size(120.dp, 24.dp)
                                .drawBehind { draws[key] = (draws[key] ?: 0) + 1 },
                        )
                    }
                }
            }
            frame()
        }

        /** Lets composition and layout run, then draws the window's content once. */
        fun frame() {
            shadowOf(Looper.getMainLooper()).idle()
            val bitmap = Bitmap.createBitmap(maxOf(view.width, 1), maxOf(view.height, 1), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }

        /** The Compose host view under the `ComposeView`, which layers invalidate. */
        val content: View get() = view.getChildAt(0)

        fun swap() = pictures.swap(server = "server-a", next = "server-b", info = state.layoutInfo, firstVisible = state.firstVisibleItemIndex, motion = motion)

        /** The list's overlay drawing the leaving pictures, onto a bitmap of the list's size. */
        fun drawLeaving() {
            val bitmap = ImageBitmap(maxOf(view.width, 1), maxOf(view.height, 1))
            CanvasDrawScope().draw(Density(activity), LayoutDirection.Ltr, ComposeCanvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                with(pictures) { drawLeaving(motion, travel = 28f) }
            }
        }
    }

    private companion object {
        val serverA = ServerSwapTarget(id = "server-a", name = "Laptop", order = 0, isConnecting = false)
        val serverB = ServerSwapTarget(id = "server-b", name = "Studio", order = 1, isConnecting = true)
    }

    @Test
    fun aRowTheSwapTookRecordsItselfAgain() {
        val harness = Harness()
        assertEquals(setOf("header-recents", "session-1", "session-2"), harness.draws.keys)
        // Drawn: nothing asks for another frame.
        assertFalse(harness.content.isDirty)

        // The rows keep the same keys (a header; the same sessions on both servers): no
        // composition change asks them to draw again, so the switch itself must.
        harness.swap()
        assertTrue("the rows whose pictures the switch took draw again", harness.content.isDirty)
        val drawn = harness.draws.toMap()
        harness.frame()
        for ((key, count) in drawn) assertTrue("$key recorded itself again", (harness.draws[key] ?: 0) > count)
        assertFalse(harness.content.isDirty)
    }

    @Test
    fun theOldRowsLeaveFromWhereTheyWereOnTheSwitchsOwnFrame() {
        val harness = Harness()
        // What the overlay reads while nothing is leaving: the switch must write one of them, or
        // the overlay waits for the motion's first step and the old rows skip their first frame.
        val reads = HashSet<Any>()
        Snapshot.observe(readObserver = { reads += it }) { harness.drawLeaving() }
        var overlayInvalidated = false
        val observer = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) overlayInvalidated = true }
        try {
            harness.swap()
            Snapshot.sendApplyNotifications()
        } finally {
            observer.dispose()
        }
        assertTrue("the switch redraws the overlay", overlayInvalidated)

        // The list's SideEffect, then the first frame of the exit.
        harness.motion.sync(serverB, animated = true)
        harness.motion.step(1.0 / 30)
        val name = harness.motion.names.first { it.id == "server-a" }.position
        val rows = harness.motion.rows("server-a", harness.keys.size)
        assertTrue("the name is on its way out ($name)", name < -0.01f)
        // iOS adds the snapshots at rest and the chain follows the name from there: the first row
        // lags the name and each row the one above it.
        assertTrue("the first row follows the name (${rows[0].position} vs $name)", rows[0].position > name)
        for (i in 1 until rows.size) assertTrue("row $i lags the row above", rows[i].position >= rows[i - 1].position)
        assertTrue("still showing", rows[0].opacity > 0.5f)
        harness.drawLeaving()
    }
}
