package ai.opencode.feature.drawer.list

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.drawer.motion.ServerSwapMotion
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.session.ServerFlowTunables
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Port of iOS `DrawerListServerFlowCells.swift` (Server Flow, d8a311bd): the ghost rows that hold
// the list while the server picked answers, and the note left after a switch went back.

/** The ghost bars' lengths, as fractions of the row, in turn (iOS `DrawerListGhostCell.lengths`). */
internal val drawerGhostLengths = listOf(0.72f, 0.54f, 0.8f, 0.46f, 0.64f)

/**
 * iOS `DrawerListGhostCell`: a row's place while the server picked answers, a 12dp bar of a title's
 * length with a slow sweep (1.6 s, every bar in step; held under Reduce Motion). Hidden from TalkBack.
 */
@Composable
fun DrawerListGhostCell(index: Int, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val base = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.35f)
    val shine = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.2f)
    val fraction = drawerGhostLengths[index % drawerGhostLengths.size]
    val phase = if (reducedMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "drawer-ghost")
        val value by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "drawer-ghost-sweep",
        )
        value
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(DrawerMetrics.rowHeight + DrawerTunables.rowGap)
            .clearAndSetSemantics {},
    ) {
        val leading = (DrawerMetrics.rowEdge + DrawerMetrics.textInset).toPx()
        val available = (size.width - leading - (DrawerMetrics.trailingInset + DrawerMetrics.rowEdge).toPx()).coerceAtLeast(0f)
        val barHeight = 12.dp.toPx()
        val top = (size.height - DrawerTunables.rowGap.toPx() - barHeight) / 2
        val width = available * fraction
        val radius = CornerRadius(6.dp.toPx())
        drawRoundRect(base, topLeft = Offset(leading, top), size = Size(width, barHeight), cornerRadius = radius)
        if (phase != null && width > 0f) {
            // The sweep runs from the bar's right to its left, three bars wide with the shine in its middle.
            val sweepStart = leading + width - phase * 2 * width - width
            val brush = Brush.horizontalGradient(
                0.3f to Color.Transparent, 0.5f to shine, 0.7f to Color.Transparent,
                startX = sweepStart, endX = sweepStart + width * 3,
            )
            drawRoundRect(brush, topLeft = Offset(leading, top), size = Size(width, barHeight), cornerRadius = radius)
        }
    }
}

/**
 * iOS `DrawerServerNoteRow`: the note left after a switch went back on its own, with the way to try
 * again (or pair again, for a sign-in it refused).
 */
@Composable
fun DrawerServerNoteRow(note: ServerSwitchNote, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val tint = when (note.reason) {
        is ServerSwitchNote.Reason.TimedOut -> colors.secondaryText
        ServerSwitchNote.Reason.Unreachable -> colors.destructive
        ServerSwitchNote.Reason.SignInFailed -> Color(0xFFFF9F0A)
    }
    Column(
        modifier
            .padding(horizontal = DrawerMetrics.rowEdge)
            .padding(top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("drawer-server-note"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon(note.systemImage, contentDescription = null, size = 20.dp, tint = tint)
            Text(
                note.title,
                modifier = Modifier.padding(start = 8.dp),
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        }
        Text(
            note.detail,
            modifier = Modifier.padding(start = 28.dp),
            style = OpenCodeTextStyles.footnote,
            color = colors.secondaryText,
        )
        OpenCodePrimaryButton(
            text = note.action,
            onClick = onAction,
            modifier = Modifier
                .padding(start = 28.dp, top = 10.dp)
                .testTag("drawer-server-note-action"),
        )
    }
}

/**
 * Title Leads: a row of the server shown hangs off the title's name, the [index]th on screen
 * following the one above it ([ServerSwapMotion.row]). Off without a swap target. Under Reduce
 * Motion the rows only fade (iOS `swapTravel`).
 */
internal fun Modifier.serverSwapRow(motion: ServerSwapMotion?, serverID: String, reducedMotion: Boolean, index: () -> Int): Modifier {
    if (motion == null) return this
    return graphicsLayer {
        motion.frame // read so each frame redraws
        if (!motion.isMoving && motion.leaving.isEmpty()) return@graphicsLayer
        val pose = motion.row(serverID, index().coerceAtLeast(0))
        translationY = if (reducedMotion) 0f else pose.position * ServerFlowTunables.listTravel.dp.toPx()
        alpha = pose.opacity
    }
}

/**
 * iOS `DrawerListController.leavingRows`: the pictures of the rows a server switch leaves, hanging
 * off their name as it goes. iOS snapshots each visible cell's content; a `LazyColumn` drops the
 * old server's items at once, so each row records what it draws into a layer kept here
 * ([serverSwapPicture]), and a switch hands the visible rows' layers to the leaving server, drawn
 * over the list ([drawLeaving]) on that server's row chain until its name has gone.
 */
internal class ServerSwapPictures(private val graphics: GraphicsContext) {
    private class Picture(val layer: GraphicsLayer, val top: Float)

    private val live = HashMap<Any, GraphicsLayer>()
    private val leaving = HashMap<String, List<Picture>>()

    /** The layer the row with [key] records into. */
    fun layer(key: Any): GraphicsLayer = live.getOrPut(key) { graphics.createGraphicsLayer() }

    /** A row left the list: what it drew goes with it, unless a switch took it. */
    fun forget(key: Any) {
        live.remove(key)?.let(graphics::releaseGraphicsLayer)
    }

    /**
     * iOS `swapRows(from:_:)`: before the next server's rows compose, the rows on screen (as last
     * laid out) become [server]'s leaving pictures. Rows coming back before they left turn round
     * as live rows.
     */
    fun swap(server: String, next: String, info: LazyListLayoutInfo, firstVisible: Int) {
        for (id in listOf(server, next)) leaving.remove(id)?.forEach { graphics.releaseGraphicsLayer(it.layer) }
        val pictures = info.visibleItemsInfo
            .filter { it.index >= firstVisible }
            .sortedBy { it.offset }
            .mapNotNull { item ->
                live.remove(item.key)?.let { Picture(it, (item.offset - info.viewportStartOffset).toFloat()) }
            }
        if (pictures.isNotEmpty()) leaving[server] = pictures
    }

    /** iOS `placeSwapRows()` for the leaving pictures: each on its server's chain, faded with it. */
    fun DrawScope.drawLeaving(motion: ServerSwapMotion, travel: Float) {
        if (leaving.isEmpty()) return
        val still = motion.leaving.toSet()
        val gone = leaving.keys.filter { it !in still }
        for (id in gone) leaving.remove(id)?.forEach { graphics.releaseGraphicsLayer(it.layer) }
        for ((id, pictures) in leaving) {
            val poses = motion.rows(id, pictures.size)
            pictures.forEachIndexed { index, picture ->
                val pose = poses[index]
                if (pose.opacity <= 0f) return@forEachIndexed
                picture.layer.alpha = pose.opacity
                translate(top = picture.top + pose.position * travel) { drawLayer(picture.layer) }
            }
        }
    }

    /** The list left the screen. */
    fun release() {
        live.values.forEach(graphics::releaseGraphicsLayer)
        live.clear()
        leaving.values.flatten().forEach { graphics.releaseGraphicsLayer(it.layer) }
        leaving.clear()
    }
}

/** A row records what it draws for [ServerSwapPictures], so a server switch can fly it out. */
internal fun Modifier.serverSwapPicture(pictures: ServerSwapPictures?, key: Any): Modifier =
    if (pictures == null) this else this then ServerSwapPictureElement(pictures, key)

private data class ServerSwapPictureElement(val pictures: ServerSwapPictures, val key: Any) : ModifierNodeElement<ServerSwapPictureNode>() {
    override fun create() = ServerSwapPictureNode(pictures, key)
    override fun update(node: ServerSwapPictureNode) {
        if (node.key != key || node.pictures !== pictures) node.pictures.forget(node.key)
        node.pictures = pictures
        node.key = key
    }
}

private class ServerSwapPictureNode(var pictures: ServerSwapPictures, var key: Any) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        val layer = pictures.layer(key)
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
    }

    override fun onDetach() {
        pictures.forget(key)
    }
}

