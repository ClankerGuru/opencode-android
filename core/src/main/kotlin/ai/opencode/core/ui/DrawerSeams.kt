package ai.opencode.core.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Seams between the session drawer (`:feature:drawer`) and the screens on its card
 * (`:feature:session`, `:feature:composer`, `:feature:transcript`), which sit below the drawer
 * in the module graph. iOS reads `DrawerKeyboardHold`'s statics and `DrawerPanExclusionSpace`
 * directly from those files; here the drawer provides these locals and the card's screens read
 * them. Both default to `null` (no drawer: nothing to do).
 */
interface DrawerKeyboardSignals {
    /**
     * The card is laying out without the keyboard (iOS `DrawerKeyboardHold.excludesKeyboard`):
     * a card the keyboard is leaving, or drawer search owning the keyboard. The session and its
     * transcript leave `WindowInsets.ime` out of their insets while this is true.
     */
    val excludesKeyboard: Boolean

    /**
     * Whether the field named [key] is held while the drawer moves or rests open
     * (iOS `DrawerKeyboardHold.isHeld`): typing, hardware Return and paste must not reach a
     * composer the drawer is taking away.
     */
    fun isHeld(key: Any): Boolean

    /**
     * A text field on the card gained or lost focus (iOS `textDidBegin/EndEditing` on the card).
     * [clearFocus] lets the drawer take the focus away when the card changes.
     */
    fun cardFieldFocusChanged(key: Any, focused: Boolean, clearFocus: () -> Unit)
}

/** The drawer's keyboard hold, provided by the drawer container around the card. */
val LocalDrawerKeyboardSignals = staticCompositionLocalOf<DrawerKeyboardSignals?> { null }

/**
 * Reports this text field's focus to the drawer's keyboard hold (see [DrawerKeyboardSignals]).
 * Put it on the composer's field; [key] identifies it for [DrawerKeyboardSignals.isHeld].
 */
fun Modifier.drawerKeyboardField(key: Any): Modifier = composed {
    val signals = LocalDrawerKeyboardSignals.current
    val focusManager = LocalFocusManager.current
    if (signals == null) {
        Modifier
    } else {
        DisposableEffect(signals, key) {
            onDispose { signals.cardFieldFocusChanged(key, focused = false, clearFocus = {}) }
        }
        Modifier.onFocusChanged { state ->
            signals.cardFieldFocusChanged(key, state.isFocused) { focusManager.clearFocus(force = true) }
        }
    }
}

/**
 * Where the session drawer's pan never starts (iOS `DrawerPanExclusionSpace`): controls on the
 * card that own a sideways drag (the + menu, the held mic). Regions are layout nodes, so hit
 * tests follow the card's translation.
 */
interface DrawerPanExclusion {
    /** Records ([coordinates] non-null) or clears the excluded region named [id]. */
    fun updateExclusion(id: Any, coordinates: LayoutCoordinates?)
}

/** The drawer's pan regions, provided by the drawer container around the card. */
val LocalDrawerPanExclusion = staticCompositionLocalOf<DrawerPanExclusion?> { null }

/**
 * The session drawer's pan never starts on this element while [isEnabled]
 * (iOS `excludedFromDrawerPan(isEnabled:)`). Pass `false` while the control is hidden or
 * doesn't take the touch.
 */
fun Modifier.excludedFromDrawerPan(isEnabled: Boolean = true): Modifier = composed {
    val exclusion = LocalDrawerPanExclusion.current
    if (exclusion == null) {
        Modifier
    } else {
        val id = remember { Any() }
        DisposableEffect(exclusion, isEnabled) {
            if (!isEnabled) exclusion.updateExclusion(id, null)
            onDispose { exclusion.updateExclusion(id, null) }
        }
        Modifier.onGloballyPositioned { if (isEnabled) exclusion.updateExclusion(id, it) }
    }
}
