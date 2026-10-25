package ai.opencode.feature.composer

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS `accessibilityReduceTransparency`. Android has no such switch; the system's own "no blurs"
 * signal is cross-window blur being off ([WindowManager.isCrossWindowBlurEnabled]: the
 * developer "Allow window-level blurs" setting, battery saver, or a device that can't), so the
 * morph and the recent-photo peek treat that as Reduce Transparency: no backdrop blur, and the
 * dim ×1.6, as iOS does.
 */
@Composable
fun rememberReduceTransparency(): Boolean {
    val context = LocalContext.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var reduce by remember(windowManager) { mutableStateOf(windowManager?.isCrossWindowBlurEnabled == false) }
    DisposableEffect(windowManager) {
        if (windowManager == null) return@DisposableEffect onDispose {}
        val listener = java.util.function.Consumer<Boolean> { enabled -> reduce = !enabled }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return reduce
}

/**
 * A real Gaussian blur of [radiusPx] on this layer (SwiftUI `.blur(radius:)`), off when it
 * would show nothing. Decal edges fade out like SwiftUI's non-opaque blur.
 */
internal fun Modifier.morphBlur(radiusPx: () -> Float): Modifier = graphicsLayer {
    val r = radiusPx()
    renderEffect = if (r > 0.25f) BlurEffect(r, r, TileMode.Decal) else null
}

/**
 * iOS `ProgressiveBlur(band:radius:)` as a [RenderEffect] on the content it blurs: the content
 * blurred by [radiusPx], laid over itself through a smoothstep mask that is 0 above [startY],
 * rises to 1 at [fullY] and stays whole to the bottom (the band profile with an upper bound of 1).
 * minSdk is 35, so [RuntimeShader] (API 33) needs no fallback.
 */
internal fun progressiveBlurEffect(startY: Float, fullY: Float, radiusPx: Float): RenderEffect {
    val shader = RuntimeShader(PROGRESSIVE_MASK)
    shader.setFloatUniform("startY", startY)
    shader.setFloatUniform("fullY", max(fullY, startY + 1f))
    val blurred = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val masked = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blurred)
    // The sharp content underneath, the masked blur over it.
    return RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), masked, BlendMode.SRC_OVER)
}

private const val PROGRESSIVE_MASK = """
uniform shader content;
uniform float startY;
uniform float fullY;
half4 main(float2 p) {
  float t = clamp((p.y - startY) / (fullY - startY), 0.0, 1.0);
  float s = t * t * (3.0 - 2.0 * t);
  return content.eval(p) * s;
}
"""

/**
 * The morph's backdrop blur on the content beneath it (iOS `ComposerMorphBackdrop`): put it on
 * the host's screen content under the [ComposerMorphSurface] (the transcript and composer).
 * Everything blurs, gone at the top and whole from a little (80 dp) above the sheet down, its
 * radius growing with the morph in half-pixel steps (a blur's opacity can't fade its effect).
 * The surface draws the dim itself; this only reads values in the draw phase, so the content
 * isn't recomposed as the morph moves.
 */
fun Modifier.composerMorphBackdrop(morph: ComposerMorph): Modifier = composed {
    val density = LocalDensity.current.density
    var top by remember { mutableFloatStateOf(0f) }
    this
        .onGloballyPositioned { top = it.positionInRoot().y }
        .graphicsLayer {
            val shown = morph.isShown
            val strength = morph.progress.value.coerceIn(0f, 1f)
            val r = (morph.backdrop.blurPx * strength * 2).roundToInt() / 2f
            renderEffect = if (!shown && strength <= 0.001f || r < 0.5f || size.height <= 0f) {
                null
            } else {
                val height = size.height
                val band = max((morph.backdrop.sheetTop - top - 80 * density) / height, 0.05f)
                progressiveBlurEffect(startY = 0f, fullY = band * height, radiusPx = r).asComposeRenderEffect()
            }
        }
}
