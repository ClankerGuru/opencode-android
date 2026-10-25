package ai.opencode.feature.composer.modelpicker

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

// The model picker's real blurs (iOS `ProgressiveBlur`, a `VariableBlurView` over what lies
// behind it). Compose has no backdrop blur, so the content to blur is recorded once into a
// GraphicsLayer as it draws ([blurSource]) and drawn again, blurred with a RenderEffect and
// masked by iOS's strength profile, wherever a [ProgressiveBlur] sits over it. A variable blur's
// strength ramp becomes the blurred copy's alpha ramp (the same smoothstep samples).
//
// A ProgressiveBlur must never sit inside the content of its own source (the layer would draw
// itself).

/** iOS `ProgressiveBlur.Profile`: full strength at one edge fading out, or a band peaking in the middle. */
sealed interface ProgressiveBlurProfile {
    /** Strongest at the top ([top]) or the bottom edge. */
    data class Edge(val top: Boolean) : ProgressiveBlurProfile

    /** Full strength between [lower] and [upper] (fractions of the height), easing out to both edges. */
    data class Band(val lower: Double, val upper: Double) : ProgressiveBlurProfile
}

/** iOS `ProgressiveBlur.strengths(_:samples:)`: the blur's strength top to bottom, smoothstepped. */
fun progressiveBlurStrengths(profile: ProgressiveBlurProfile, samples: Int = 32): List<Double> {
    fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
    return (0..samples).map { i ->
        val t = i.toDouble() / samples
        when (profile) {
            is ProgressiveBlurProfile.Edge -> if (profile.top) smooth(1 - t) else smooth(t)
            is ProgressiveBlurProfile.Band -> when {
                t < profile.lower -> smooth(t / max(profile.lower, 0.001))
                t > profile.upper -> smooth((1 - t) / max(1 - profile.upper, 0.001))
                else -> 1.0
            }
        }
    }
}

/**
 * Content a [ProgressiveBlur] can blur: [blurSource] records its node into [layer] every time it
 * draws, and keeps its root position so a blur elsewhere lines its copy up with it.
 */
@Stable
class BlurSource {
    internal var layer by mutableStateOf<GraphicsLayer?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
}

/** Records this node's drawing into [source] (and draws it as usual). */
fun Modifier.blurSource(source: BlurSource): Modifier = this then BlurSourceElement(source)

private data class BlurSourceElement(val source: BlurSource) : ModifierNodeElement<BlurSourceNode>() {
    override fun create() = BlurSourceNode(source)
    override fun update(node: BlurSourceNode) {
        if (node.source !== source) {
            node.detachLayer()
            node.source = source
            node.attachLayer()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "blurSource"
    }
}

private class BlurSourceNode(var source: BlurSource) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() = attachLayer()
    override fun onDetach() = detachLayer()

    fun attachLayer() {
        if (!isAttached) return
        val created = currentValueOf(LocalGraphicsContext).createGraphicsLayer()
        layer = created
        source.layer = created
    }

    fun detachLayer() {
        val current = layer ?: return
        if (source.layer === current) source.layer = null
        if (isAttached) currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(current)
        layer = null
    }

    override fun ContentDrawScope.draw() {
        val target = layer
        if (target == null) {
            drawContent()
            return
        }
        target.record { this@draw.drawContent() }
        drawLayer(target)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        source.origin = coordinates.positionInRoot()
    }
}

/**
 * iOS `ProgressiveBlur`: [source] blurred by [radius], strongest where [profile] says, over the
 * area this occupies. Draws nothing until the source has drawn, or at a radius under half a pixel.
 */
@Composable
internal fun ProgressiveBlur(
    source: BlurSource?,
    profile: ProgressiveBlurProfile,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val blurLayer = rememberGraphicsLayer()
    val holder = remember { BlurConsumerOrigin() }
    val stops = remember(profile) { strengthStops(profile) }
    Spacer(
        modifier
            .onGloballyPositioned { holder.origin = it.positionInRoot() }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                if (drawBlurredSource(source, blurLayer, radius.toPx(), holder.origin)) {
                    drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
                }
            },
    )
}

@Stable
private class BlurConsumerOrigin {
    var origin by mutableStateOf(Offset.Zero)
}

private fun strengthStops(profile: ProgressiveBlurProfile): Array<Pair<Float, Color>> {
    val strengths = progressiveBlurStrengths(profile)
    val last = strengths.size - 1
    return Array(strengths.size) { i -> (i.toFloat() / last) to Color.Black.copy(alpha = strengths[i].toFloat()) }
}

/**
 * Draws [source] blurred by [radiusPx], lined up with where it sits ([selfOrigin] is this draw
 * scope's root position). Returns false when there's nothing to draw.
 */
internal fun DrawScope.drawBlurredSource(source: BlurSource?, blurLayer: GraphicsLayer, radiusPx: Float, selfOrigin: Offset): Boolean {
    val src = source?.layer ?: return false
    if (radiusPx < 0.5f || src.size == IntSize.Zero) return false
    val r = min(radiusPx, 150f)
    blurLayer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
    blurLayer.record(size = src.size) { drawLayer(src) }
    translate(source.origin.x - selfOrigin.x, source.origin.y - selfOrigin.y) { drawLayer(blurLayer) }
    return true
}

/**
 * A real gaussian blur of this node's own drawing (SwiftUI `.blur(radius:)`), skipped at a radius
 * under half a pixel.
 */
internal fun Modifier.layerBlur(radius: () -> Float): Modifier = graphicsLayer {
    val r = radius() * density
    renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Decal) else null
}
