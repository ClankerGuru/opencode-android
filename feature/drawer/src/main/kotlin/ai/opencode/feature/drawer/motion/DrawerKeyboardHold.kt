package ai.opencode.feature.drawer.motion

import ai.opencode.core.ui.DrawerKeyboardSignals
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Port of `OpenCode/Drawer/DrawerKeyboardHold.swift`: the composer keeps its focus while the
 * drawer moves (OC-244).
 *
 * iOS slides the system keyboard's own window with the card through a private UIKit lookup. The
 * Android IME is another process's window that apps can't move, so this is iOS's documented
 * fallback, which it uses whenever that window can't be found: the keys stay put while the
 * card moves and step down once the drawer rests open (the field keeps its focus; the IME is
 * hidden), and moving the card again brings them back. Closing onto the same session gives the
 * field back ([giveBack]); anything else (another session, a new one, another field taking the
 * keyboard) lets it go ([letGo]).
 *
 * A card the keyboard is leaving (another session or New Session chosen in the drawer,
 * [cardWillChange]) lays out without it from that moment ([excludesKeyboard]): the incoming
 * session's composer rests at the bottom from its first frame while the IME goes down.
 *
 * Drawer search's keyboard belongs to the drawer, never the peeking card: while search is open
 * or its keyboard is still leaving, [excludesKeyboard] is true too.
 *
 * Wiring (the drawer container): create with [rememberDrawerKeyboardHold], provide it as
 * [LocalDrawerKeyboardSignals] around the card (the composer marks its field with
 * `Modifier.drawerKeyboardField(key)`), report drawer fields through [drawerFieldFocusChanged],
 * and set [DrawerMotionState.keyboardHold] so the drag and settle drive it at iOS's points.
 *
 * Not ported: moving and veiling the keys beside the card (`follow`, `veil`; no Android API),
 * `DrawerKeyboardTrace` (iOS lab trace), `PrivateAPI` (no private API lookups on Android).
 */
@Stable
class DrawerKeyboardHold(
    private val scope: CoroutineScope,
    private val monitor: DrawerKeyboardMonitor? = null,
) : DrawerKeyboardSignals {
    /** Shows or hides the soft keyboard without moving focus. */
    interface Ime {
        fun show()
        fun hide()
    }

    var ime: Ime? = null

    /** The field let go of its focus before the card came back. */
    var onLetGo: () -> Unit = {}

    /** Lays the card out without the keyboard (`true`) while it leaves, and with it again (`false`) once it's gone. */
    var excludeKeyboard: (Boolean) -> Unit = {}

    private class Field(val key: Any, val inDrawer: Boolean, val clearFocus: () -> Unit)

    /** The focused field (iOS `UIResponder.current`). */
    private var focused: Field? = null

    /** The field that keeps its focus while the card is away. */
    private var heldField: Field? = null
        set(value) {
            field = value
            heldKey = value?.key
        }
    private var heldKey: Any? by mutableStateOf(null)

    /** The card is laying out without the keyboard; the transcript leaves it out of its inset too. */
    override var excludesKeyboard: Boolean by mutableStateOf(false)
        private set

    /** The keys stepped down under the open drawer. */
    var keysAway: Boolean by mutableStateOf(false)
        private set

    /** The keyboard leaving a card, until it's gone. */
    var isLeaving: Boolean by mutableStateOf(false)
        private set

    val isHolding: Boolean get() = heldField != null

    private var isDrawerSearching = false
    private var drawerOwnsKeyboard = false
    private var isExcludingKeyboard = false
    private var isKeyboardVisible = false
    private var searchRestoreGeneration = 0
    private var searchRestoreJob: Job? = null
    private var searchRestoreOnHide: (() -> Unit)? = null
    private var leaveGeneration = 0
    private var leaveJob: Job? = null
    private var leaveRestore: (() -> Unit)? = null

    override fun isHeld(key: Any): Boolean = heldKey == key

    override fun cardFieldFocusChanged(key: Any, focused: Boolean, clearFocus: () -> Unit) =
        fieldFocusChanged(Field(key, inDrawer = false, clearFocus), focused)

    /** A field in the drawer (its search) gained or lost focus. */
    fun drawerFieldFocusChanged(key: Any, focused: Boolean, clearFocus: () -> Unit) =
        fieldFocusChanged(Field(key, inDrawer = true, clearFocus), focused)

    private fun fieldFocusChanged(changed: Field, isFocused: Boolean) {
        if (isFocused) {
            focused = changed
            checkActiveResponder(changed)
            return
        }
        if (focused?.key == changed.key) focused = null
        // Another field took the keyboard, or the held field left: the hold is over.
        if (heldField?.key == changed.key) letGo()
        if (changed.inDrawer && !isDrawerSearching) releaseDrawerKeyboardWhenHidden()
    }

    /** The IME started showing (`true`, iOS `keyboardWillShow`) or finished hiding (`false`, `keyboardDidHide`). */
    fun imeVisibilityChanged(visible: Boolean) {
        if (visible) {
            isKeyboardVisible = true
            focused?.let(::checkActiveResponder)
        } else {
            isKeyboardVisible = false
            if (!isDrawerSearching && !isDrawerFieldFocused) {
                cancelSearchRestore()
                drawerOwnsKeyboard = false
                syncExclusion()
            }
            searchRestoreOnHide?.invoke()
        }
        leaveRestore?.invoke()
    }

    /**
     * Drawer search opened or closed (iOS `setDrawerSearching`): while it's open or its keyboard
     * is still leaving, the card and transcript ignore the keyboard.
     */
    fun setDrawerSearching(searching: Boolean) {
        isDrawerSearching = searching
        if (searching) {
            cancelSearchRestore()
            drawerOwnsKeyboard = true
            syncExclusion()
        } else {
            releaseDrawerKeyboardWhenHidden()
        }
    }

    /** Ends drawer-owned keyboard exclusion once the session card takes over. */
    fun endDrawerSearch() {
        cancelSearchRestore()
        isDrawerSearching = false
        drawerOwnsKeyboard = false
        syncExclusion()
    }

    /**
     * Keeps the focus of the card's focused field while the card moves. Returns whether it
     * holds; if not, the caller puts the keyboard away as before.
     */
    fun begin(): Boolean {
        if (isHolding) return true
        val current = focused ?: return false
        if (current.inDrawer) return false
        heldField = current
        monitor?.isHoldingKeyboard = true
        return true
    }

    /** The drawer rests open: the keys step down out of its way, the field keeps its focus. */
    fun sendKeysAway() {
        val held = heldField ?: return
        if (keysAway || focused?.key != held.key) return
        keysAway = true
        ime?.hide()
    }

    /** The card moves again, or the drawer commits to closing: the keys come back up. */
    fun bringKeysBack() {
        val held = heldField ?: return
        if (!keysAway) return
        keysAway = false
        if (focused?.key == held.key) ime?.show()
    }

    /** Closed back onto the same session: the keys are (or are coming) back, and the field is the card's own again. */
    fun giveBack() {
        val held = heldField ?: return
        if (focused?.key != held.key) return letGo()
        bringKeysBack()
        end()
    }

    /** A session or New Session was chosen in the drawer: the held card lets its keyboard go now. */
    fun cardWillChange() = letGo(leavingCard = true, now = true)

    /**
     * The card leaves the keyboard behind (another session, a new one, a field elsewhere). The
     * field lets go just after (clearing focus mid-composition would write the composer's focus
     * state mid-update); [now] clears it at once. A card the keyboard leaves ([leavingCard])
     * stops laying out around it at once.
     */
    fun letGo(leavingCard: Boolean = false, now: Boolean = false) {
        val held = heldField ?: return
        val resigning = focused?.key == held.key
        end()
        if (resigning && leavingCard) leave()
        val resign = {
            if (focused?.key == held.key) held.clearFocus()
            onLetGo()
        }
        if (now) resign() else scope.launch { resign() }
    }

    /** The drawer is going away: whatever the hold did is undone, so nothing outlives it. */
    fun release() {
        leaveGeneration += 1
        leaveJob?.cancel()
        leaveRestore = null
        cancelSearchRestore()
        drawerOwnsKeyboard = false
        isLeaving = false
        isDrawerSearching = false
        excludesKeyboard = false
        if (isExcludingKeyboard) {
            isExcludingKeyboard = false
            excludeKeyboard(false)
        }
        end()
    }

    private val isDrawerFieldFocused: Boolean get() = focused?.inDrawer == true

    private val shouldExcludeKeyboard: Boolean
        get() = isLeaving || drawerOwnsKeyboard || isDrawerSearching

    private fun syncExclusion() {
        val excluded = shouldExcludeKeyboard
        if (isExcludingKeyboard != excluded) {
            isExcludingKeyboard = excluded
            excludeKeyboard(excluded)
        }
        if (excludesKeyboard != excluded) excludesKeyboard = excluded
    }

    private fun checkActiveResponder(responder: Field) {
        cancelSearchRestore()
        if (responder.inDrawer) {
            drawerOwnsKeyboard = true
        } else {
            isDrawerSearching = false
            drawerOwnsKeyboard = false
        }
        syncExclusion()
    }

    private fun cancelSearchRestore() {
        searchRestoreGeneration += 1
        searchRestoreJob?.cancel()
        searchRestoreJob = null
        searchRestoreOnHide = null
    }

    private fun releaseDrawerKeyboardWhenHidden() {
        if (!drawerOwnsKeyboard) return syncExclusion()
        if (!isKeyboardVisible && !isDrawerFieldFocused) {
            cancelSearchRestore()
            drawerOwnsKeyboard = false
            syncExclusion()
            return
        }
        cancelSearchRestore()
        val generation = searchRestoreGeneration
        val restore = restore@{
            if (searchRestoreGeneration != generation) return@restore
            searchRestoreJob?.cancel()
            searchRestoreJob = null
            searchRestoreOnHide = null
            if (isDrawerSearching || isDrawerFieldFocused) return@restore
            drawerOwnsKeyboard = false
            syncExclusion()
        }
        searchRestoreOnHide = restore
        searchRestoreJob = scope.launch {
            delay(SEARCH_RESTORE_MILLIS)
            restore()
        }
    }

    /** The keyboard leaves the card: it lays out without it until the IME is gone (or shows again). */
    private fun leave() {
        isLeaving = true
        syncExclusion()
        leaveGeneration += 1
        val generation = leaveGeneration
        leaveJob?.cancel()
        val restore = restore@{
            if (leaveGeneration != generation) return@restore
            leaveRestore = null
            leaveJob?.cancel()
            leaveJob = null
            isLeaving = false
            syncExclusion()
        }
        leaveRestore = restore
        leaveJob = scope.launch {
            delay(LEAVE_RESTORE_MILLIS)
            restore()
        }
    }

    private fun end() {
        heldField = null
        keysAway = false
        monitor?.isHoldingKeyboard = false
    }

    private companion object {
        /** iOS `asyncAfter(.now() + 0.6)` for drawer search's keyboard. */
        const val SEARCH_RESTORE_MILLIS = 600L
        /** iOS `asyncAfter(.now() + 1.5)` for a card the keyboard leaves. */
        const val LEAVE_RESTORE_MILLIS = 1_500L
    }
}

/**
 * A [DrawerKeyboardHold] wired to this window's IME: the soft keyboard controller shows and
 * hides the keys, and `WindowInsets.ime` reports when they start showing or finish hiding.
 * Releases the hold when it leaves composition (iOS `viewDidDisappear` → `release()`).
 */
@Composable
fun rememberDrawerKeyboardHold(monitor: DrawerKeyboardMonitor? = null): DrawerKeyboardHold {
    val scope = rememberCoroutineScope()
    val hold = remember(scope, monitor) { DrawerKeyboardHold(scope, monitor) }
    val keyboard = LocalSoftwareKeyboardController.current
    hold.ime = remember(keyboard) {
        keyboard?.let {
            object : DrawerKeyboardHold.Ime {
                override fun show() = it.show()
                override fun hide() = it.hide()
            }
        }
    }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    LaunchedEffect(hold, imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .collect { visible ->
                hold.imeVisibilityChanged(visible)
                monitor?.isImeVisible = visible
            }
    }
    DisposableEffect(hold) { onDispose { hold.release() } }
    return hold
}
