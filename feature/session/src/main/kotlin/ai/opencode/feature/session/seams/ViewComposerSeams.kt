package ai.opencode.feature.session.seams

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

// Stand-ins the session composer (VIEW-COMPOSER: SessionViewComposer*.kt, ComposerViews.kt,
// DictationViews.kt) needs from Composer/Voice files not ported yet. Each carries only what the
// composer uses.

// region Swipe rules (Composer/ComposerTextInput.swift)

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/ComposerTextInput.swift`
 * `ComposerSwipeDown`); delete when that area merges. A downward swipe on the focused composer
 * dismisses the keyboard; an upward swipe on the unfocused composer pulls it up. Distances in dp.
 */
object ComposerSwipeDown {
    const val minimumDistance = 18f
    const val predictedDistance = 60f
    const val minimumFlingDistance = 10f
    const val verticalRatio = 1.3f

    /** (dx, dy) in dp; [predictedEnd] is the projected end translation (iOS `predictedEndTranslation`). */
    fun shouldDismissKeyboard(
        dx: Float,
        dy: Float,
        predictedEndY: Float = 0f,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingUp: Boolean = false,
    ): Boolean {
        if (!isFocused || isHoldingMic || isScrollingUp) return false
        if (dy <= 0 || abs(dy) <= abs(dx) * verticalRatio) return false
        return dy >= minimumDistance || (dy >= minimumFlingDistance && predictedEndY >= predictedDistance)
    }

    fun shouldFocusKeyboard(
        dx: Float,
        dy: Float,
        predictedEndY: Float = 0f,
        velocityY: Float? = null,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingDown: Boolean = false,
        minimumDistance: Float = 18f,
        minimumFlickVelocity: Float = 260f,
    ): Boolean {
        if (isFocused || isHoldingMic || isScrollingDown) return false
        if (dy >= 0 || abs(dy) <= abs(dx) * verticalRatio) return false
        val distance = -dy
        if (distance >= minimumDistance) return true
        if (distance < minimumFlingDistance) return false
        if (velocityY != null) return -velocityY >= minimumFlickVelocity
        val predictedExtra = max(0f, -(predictedEndY - dy))
        return predictedExtra >= minimumFlickVelocity * 0.15f || -predictedEndY >= max(predictedDistance, minimumDistance)
    }
}

/**
 * SEAM: real type belongs to :feature:composer (iOS `ComposerSwipeOwnership`); delete when that
 * area merges. Whether a drag over the composer belongs to a control inside it, decided once as
 * the drag first reports and kept through its end.
 */
class ComposerSwipeOwnership {
    private var started = false
    private var yields = false
    private var focusedAtStart = false

    fun yields(isFocused: Boolean, yieldsNow: () -> Boolean): Boolean {
        if (!started) {
            started = true
            focusedAtStart = isFocused
            yields = yieldsNow()
        }
        return yields
    }

    fun beganFocused(isFocused: Boolean): Boolean {
        if (!started) {
            started = true
            focusedAtStart = isFocused
        }
        return focusedAtStart
    }

    /** The drag ended: the next one decides afresh. */
    fun ended() {
        started = false
        yields = false
        focusedAtStart = false
    }
}

// endregion

// region Dictation levels and latency (Composer/DictationClient.swift)

/**
 * SEAM: real type belongs to :feature:composer (iOS `DictationLevels`); delete when that area
 * merges. The microphone's live level (0 silence … 1 loud speech), kept outside the store.
 */
object DictationLevels {
    @Volatile
    var level: Float = 0f
        private set

    fun set(value: Float) {
        level = value.coerceIn(0f, 1f)
    }
}

/**
 * SEAM: real type belongs to :feature:composer (iOS `DictationLatency`); delete when that area
 * merges. Press-to-step timings for diagnostics; the steps marked from the composer are not
 * logged on iOS either (only `session_active` and `mic_live` are), so this is a no-op.
 */
object DictationLatency {
    @Suppress("UNUSED_PARAMETER")
    fun touchDown(uptimeMillis: Long) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun mark(step: String) = Unit
}

// endregion

// region Dot scope (Voice/DotScopeView.swift)

/**
 * SEAM: real view belongs to :feature:voice (iOS `Voice/DotScopeView.swift`, `.dictation` mode);
 * delete when that area merges. Stand-in: a row of dots between the insets whose height follows
 * [DictationLevels]; still under Reduce Motion.
 */
@Composable
fun DictationDotScope(leadingInset: Float, trailingInset: Float, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    var time by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            withFrameNanos { now ->
                time = now
                level += (DictationLevels.level - level) * 0.2f
            }
        }
    }
    val color = OpenCodeTheme.colors.text
    Canvas(modifier) {
        val start = leadingInset.dp.toPx()
        val end = size.width - trailingInset.dp.toPx()
        if (end <= start) return@Canvas
        val pitch = 6.dp.toPx()
        val radius = 1.5.dp.toPx()
        val mid = size.height / 2
        val seconds = time / 1_000_000_000.0
        var x = start
        var index = 0
        while (x <= end) {
            val wave = if (reduceMotion) 0.0 else sin(index * 0.55 + seconds * 6 * PI / 3)
            val amplitude = (2.dp.toPx() + level * 10.dp.toPx()) * wave.toFloat()
            drawCircle(color.copy(alpha = 0.55f), radius, Offset(x, mid + amplitude))
            x += pitch
            index++
        }
    }
}

// endregion

// region Queued messages and selection mark (Composer/QueuedMessagesSheet.swift, RequestDockView.swift)

/**
 * SEAM: real view belongs to :feature:composer (iOS `Composer/QueuedMessagesSheet.swift`); delete
 * when that area merges. Stand-in: a fitted sheet of numbered two-line rows with Send Now and
 * Cancel Message, and Copy (iOS's long-press menu).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuedMessagesSheet(
    messages: List<OpenCodeQueuedInput>,
    onSendNow: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                "Queued Messages",
                style = OpenCodeTextStyles.headline,
                color = OpenCodeTheme.colors.text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (messages.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OpenCodeIcon(SFSymbol.Tray, contentDescription = null, tint = OpenCodeTheme.colors.secondaryText)
                    Text("No Queued Messages", style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
                }
            }
            LazyColumn(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                itemsIndexed(messages, key = { _, it -> it.id }) { index, input ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 20.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                            color = OpenCodeTheme.colors.secondaryText,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            input.text,
                            style = OpenCodeTextStyles.body,
                            color = OpenCodeTheme.colors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            OpenCodeIconButton(SFSymbol.DocOnDoc, "Copy", { onCopy(input.text) }, style = OpenCodeIconButtonStyle.Standard, iconSize = 18.dp)
                        }
                        OpenCodeIconButton(SFSymbol.Trash, "Cancel Message", { onCancel(input.id) }, style = OpenCodeIconButtonStyle.Standard, iconSize = 18.dp)
                        OpenCodeIconButton(SFSymbol.ArrowUp, "Send Now", { onSendNow(input.id) }, style = OpenCodeIconButtonStyle.Standard, iconSize = 18.dp)
                    }
                }
            }
        }
    }
}

/**
 * SEAM: real view belongs to :feature:composer (iOS `Composer/RequestDockView.swift`
 * `SelectionMark`); delete when that area merges. The empty or filled mark beside a choice.
 */
@Composable
fun SelectionMark(selected: Boolean, multiple: Boolean, modifier: Modifier = Modifier) {
    val symbol = when {
        selected && multiple -> SFSymbol.CheckmarkSquareFill
        selected -> SFSymbol.CheckmarkCircleFill
        multiple -> SFSymbol.Square
        else -> SFSymbol.Circle
    }
    OpenCodeIcon(
        symbol,
        contentDescription = null,
        size = 22.dp,
        tint = if (selected) OpenCodeTheme.colors.text else OpenCodeTheme.colors.tertiaryText,
        modifier = modifier,
    )
}

/** SEAM: iOS `SelectionMark.width`. */
const val SELECTION_MARK_WIDTH = 22f

/** SEAM: iOS `RequestDockView.edge` (the request panel's content inset). */
const val REQUEST_DOCK_EDGE = 20f

// endregion
