package ai.opencode.core.ui

import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * Moving TalkBack's focus from Compose, where VoiceOver moves by itself (a modal appearing or
 * ending, a navigation pop). Compose has no API for it: `FocusRequester` moves input focus (and
 * with it the keyboard), not accessibility focus. So the element marks its semantics node, finds
 * it in its view's semantics tree and asks the view's accessibility node provider to perform
 * `ACTION_ACCESSIBILITY_FOCUS` on it, as TalkBack does ([moveAccessibilityFocus]). The marker is
 * semantics, so it goes ahead of any `clearAndSetSemantics` on the same element.
 */

/**
 * Moves TalkBack's focus onto this element each time [request] becomes a new non-null value
 * (and when it's attached with one), once its semantics are published. If another window is on
 * top (a system picker), it waits for this window to get its focus back: a move made meanwhile
 * is lost, and TalkBack then falls back to the first content. Nothing happens while touch
 * exploration is off.
 */
fun Modifier.accessibilityFocusRequest(request: Any?): Modifier = this then AccessibilityFocusRequestElement(request)

/**
 * A handle on one element ([accessibilityFocusTarget]) that [focus] moves TalkBack's focus onto,
 * for callers that pick the moment themselves.
 */
@Stable
class AccessibilityFocusTarget {
    internal var node: AccessibilityFocusTargetNode? = null

    /** Moves TalkBack's focus onto the element now; false if it isn't composed or the move was refused. */
    fun focus(): Boolean = node?.let { it.isAttached && it.moveAccessibilityFocus() } == true
}

/** Makes this element the one [target]'s [AccessibilityFocusTarget.focus] moves TalkBack to. */
fun Modifier.accessibilityFocusTarget(target: AccessibilityFocusTarget): Modifier = this then AccessibilityFocusTargetElement(target)

private fun <N> N.moveAccessibilityFocus(): Boolean where N : Modifier.Node, N : CompositionLocalConsumerModifierNode {
    val view = currentValueOf(LocalView)
    // The only place that reads a semantics tree. The accessibility node provider's virtual view
    // ids are semantics node ids, and nothing public maps a modifier node to its id
    // (`requireSemanticsInfo()`, `LayoutNode` and the view's `Owner` are internal), so the
    // tree comes from `RootForTest`, the public interface every Compose view implements.
    val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
    // Absent while something hides it (a cleared ancestor): then there's nothing to focus.
    val id = root.find { it.config.getOrNull(AccessibilityFocusMarker) === this }?.id ?: return false
    return view.accessibilityNodeProvider?.performAction(id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) == true
}

private fun SemanticsNode.find(match: (SemanticsNode) -> Boolean): SemanticsNode? =
    if (match(this)) this else children.firstNotNullOfOrNull { it.find(match) }

private val AccessibilityFocusMarker = SemanticsPropertyKey<Modifier.Node>("AccessibilityFocusMarker")

private data class AccessibilityFocusTargetElement(val target: AccessibilityFocusTarget) :
    ModifierNodeElement<AccessibilityFocusTargetNode>() {
    override fun create() = AccessibilityFocusTargetNode(target)
    override fun update(node: AccessibilityFocusTargetNode) = node.update(target)
}

internal class AccessibilityFocusTargetNode(private var target: AccessibilityFocusTarget) :
    Modifier.Node(), SemanticsModifierNode, CompositionLocalConsumerModifierNode {
    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AccessibilityFocusMarker] = this@AccessibilityFocusTargetNode
    }

    override fun onAttach() {
        target.node = this
    }

    override fun onDetach() {
        if (target.node === this) target.node = null
    }

    fun update(next: AccessibilityFocusTarget) {
        if (next === target) return
        if (target.node === this) target.node = null
        target = next
        if (isAttached) target.node = this
    }
}

private data class AccessibilityFocusRequestElement(val request: Any?) : ModifierNodeElement<AccessibilityFocusRequestNode>() {
    override fun create() = AccessibilityFocusRequestNode(request)
    override fun update(node: AccessibilityFocusRequestNode) = node.update(request)
}

private class AccessibilityFocusRequestNode(private var request: Any?) :
    Modifier.Node(), SemanticsModifierNode, CompositionLocalConsumerModifierNode {
    private var pending: Job? = null

    override fun SemanticsPropertyReceiver.applySemantics() {
        this[AccessibilityFocusMarker] = this@AccessibilityFocusRequestNode
    }

    override fun onAttach() = focus()

    fun update(next: Any?) {
        if (next == request) return
        request = next
        if (isAttached) focus()
    }

    private fun focus() {
        pending?.cancel()
        if (request == null) return
        pending = coroutineScope.launch {
            // Its semantics are published with the frame that lays it out.
            withFrameNanos {}
            withFrameNanos {}
            val view = currentValueOf(LocalView)
            if (view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true) return@launch
            if (!view.hasWindowFocus()) {
                view.awaitWindowFocus()
                withFrameNanos {}
            }
            moveAccessibilityFocus()
        }
    }

    private suspend fun View.awaitWindowFocus() {
        if (hasWindowFocus()) return
        suspendCancellableCoroutine { continuation ->
            val observer = viewTreeObserver
            val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                    continuation.resume(Unit)
                }
            }
            observer.addOnWindowFocusChangeListener(listener)
            continuation.invokeOnCancellation { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
        }
    }
}
