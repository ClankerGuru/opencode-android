package ai.opencode.feature.drawer.motion

import androidx.compose.foundation.BorderStroke
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Port of `OpenCode/Drawer/DrawerSearchPill.swift`: the drawer's search. One circle at rest that
 * stretches leftward into the search field while a circular × button buds off its trailing
 * edge. Tapping × or pressing Escape clears the query, dismisses the keyboard and merges the ×
 * back into the search button. Under Reduce Motion the two states cross-fade in place.
 *
 * Liquid Glass and its morph (`GlassEffectContainer`, `glassEffectID`) become a tonal M3
 * capsule whose width springs on [DrawerSearchPillDefaults.morph].
 */
object DrawerSearchPillDefaults {
    /** The circle at rest and the field's height (`DrawerTunables.searchSize`, at least Android's 48 dp target). */
    val size: Dp get() = maxOf(DrawerTunables.searchSize, OpenCodeMinTapTarget)
    /** About 0.3 s with a small bounce (iOS `drawer.search.morph`). */
    val morph = MotionSpring(0.3, 0.15)
    /** The magnifier's box, centred in the circle and, opened, starting on the drawer's text inset. */
    val glyphWidth: Dp = 20.dp
    /** Between the open field and the × button. */
    val cancelGap: Dp = 10.dp
    /** Opened, the magnifier starts on the drawer's one leading edge (`DrawerMetrics.textInset`). */
    val textInset: Dp get() = DrawerMetrics.textInset
}

/**
 * Whether the drawer's search is open or focused (iOS `DrawerSearchState`). Read by the pill and
 * the header/footer fade modifiers in their own layers, so opening and closing animate there
 * without recomposing Home.
 */
@Stable
class DrawerSearchState {
    var isSearching: Boolean by mutableStateOf(false)
    var isFocused: Boolean by mutableStateOf(false)
}

/**
 * The drawer's search pill.
 *
 * @param keyboardHold the drawer's [DrawerKeyboardHold]: search's keyboard belongs to the
 *   drawer, never the peeking card.
 * @param onBegin search opens (Home records the drawer's search began).
 * @param onEnd search closes (Home clears the query).
 */
@Composable
fun DrawerSearchPill(
    state: DrawerSearchState,
    text: String,
    onTextChange: (String) -> Unit,
    reducedMotion: Boolean,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardHold: DrawerKeyboardHold? = null,
    size: Dp = DrawerSearchPillDefaults.size,
    textInset: Dp = DrawerSearchPillDefaults.textInset,
) {
    val haptics = remember { dependency(HapticsClient) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val fieldKey = remember { Any() }
    val isSearching = state.isSearching
    /** 0 at rest, 1 open; springs the capsule's width and the × button. */
    val open = remember { Animatable(if (isSearching) 1f else 0f) }
    var syncedSearching by remember { mutableStateOf(isSearching) }

    val begin = {
        if (state.isSearching) {
            keyboardHold?.setDrawerSearching(true)
            runCatching { focusRequester.requestFocus() }
            state.isFocused = true
        } else {
            onBegin()
            state.isSearching = true
            state.isFocused = true
        }
    }
    val end = { playHaptic: Boolean ->
        if (playHaptic) haptics.play(Haptic.cancel)
        focusManager.clearFocus()
        state.isFocused = false
        state.isSearching = false
        onEnd()
    }

    LaunchedEffect(isSearching) {
        if (syncedSearching != isSearching) {
            syncedSearching = isSearching
            keyboardHold?.setDrawerSearching(isSearching)
            if (isSearching) {
                if (!state.isFocused) state.isFocused = true
            } else if (state.isFocused) {
                state.isFocused = false
            }
        }
        if (isSearching) runCatching { focusRequester.requestFocus() }
        open.animateTo(if (isSearching) 1f else 0f, if (reducedMotion) Motion.reducedFadeSpec() else DrawerSearchPillDefaults.morph.spec())
    }

    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = OpenCodeTextStyles.body.copy(color = OpenCodeTheme.colors.text),
            cursorBrush = SolidColor(OpenCodeTheme.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = fieldModifier
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    keyboardHold?.drawerFieldFocusChanged(fieldKey, focus.isFocused) { focusManager.clearFocus(force = true) }
                    keyboardHold?.setDrawerSearching(focus.isFocused)
                    if (state.isFocused != focus.isFocused) state.isFocused = focus.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        end(false)
                        true
                    } else {
                        false
                    }
                }
                .semantics { contentDescription = "Search sessions" }
                .padding(end = 12.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text("Search", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.tertiaryText, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }

    val restPadding = (size - DrawerSearchPillDefaults.glyphWidth) / 2

    if (reducedMotion) {
        // A new identity per state, so they cross-fade rather than stretch.
        Crossfade(isSearching, modifier.fillMaxWidth(), animationSpec = Motion.reducedFadeSpec(), label = "drawer-search") { searching ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SearchCapsule(
                    isSearching = searching,
                    size = size,
                    leadingPadding = if (searching) textInset else restPadding,
                    trailingPadding = if (searching) 8.dp else restPadding,
                    onTap = begin,
                    modifier = if (searching) Modifier.weight(1f) else Modifier,
                    field = field,
                )
                if (searching) {
                    Spacer(Modifier.width(DrawerSearchPillDefaults.cancelGap))
                    CancelButton(size) { end(true) }
                }
            }
        }
    } else {
        BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            val full = maxWidth
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchCapsule(
                    isSearching = isSearching,
                    size = size,
                    leadingPadding = if (isSearching) textInset else restPadding,
                    trailingPadding = if (isSearching) 8.dp else restPadding,
                    onTap = begin,
                    modifier = Modifier.animatedWidth {
                        val p = open.value
                        val openWidth = (full - size - DrawerSearchPillDefaults.cancelGap).value
                        size.value + (openWidth - size.value) * p
                    },
                    field = field,
                )
                if (isSearching || open.value > 0.001f) {
                    // The × buds off the capsule's trailing edge.
                    Box(
                        Modifier
                            .animatedWidth { (DrawerSearchPillDefaults.cancelGap + size).value * open.value.coerceIn(0f, 1f) }
                            .clipToBounds(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            Modifier.graphicsLayer {
                                val p = open.value.coerceIn(0f, 1f)
                                alpha = p
                                scaleX = 0.6f + 0.4f * p
                                scaleY = 0.6f + 0.4f * p
                            },
                        ) {
                            CancelButton(size, enabled = isSearching) { end(true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCapsule(
    isSearching: Boolean,
    size: Dp,
    leadingPadding: Dp,
    trailingPadding: Dp,
    onTap: () -> Unit,
    modifier: Modifier,
    field: @Composable (Modifier) -> Unit,
) {
    val leading by animateFloatAsState(leadingPadding.value, DrawerSearchPillDefaults.morph.spec(), label = "search-leading")
    val trailing by animateFloatAsState(trailingPadding.value, DrawerSearchPillDefaults.morph.spec(), label = "search-trailing")
    Surface(
        // The desktop's search input: base surface, hairline ring, small radius.
        shape = OpenCodeShape.control,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, OpenCodeTheme.colors.border),
        contentColor = OpenCodeTheme.colors.text,
        modifier = modifier
            .height(size)
            // The button at rest; opened, the magnifier and the field's padding focus the field.
            .clickable(role = if (isSearching) null else Role.Button, onClick = onTap)
            .then(
                if (isSearching) {
                    Modifier.testTag("drawer-search-pill")
                } else {
                    Modifier
                        .clearAndSetSemantics { contentDescription = "Search sessions" }
                        .testTag("drawer-search")
                },
            ),
    ) {
        Row(Modifier.clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(start = leading.dp, end = trailing.dp)
                    .size(DrawerSearchPillDefaults.glyphWidth, size)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(
                    SFSymbol.Magnifyingglass,
                    contentDescription = null,
                    tint = if (isSearching) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.text,
                )
            }
            // iOS `.transition(.opacity)` inside the morph's `withAnimation`: the field fades on the morph spring.
            AnimatedVisibility(
                isSearching,
                enter = fadeIn(DrawerSearchPillDefaults.morph.spec()),
                exit = fadeOut(DrawerSearchPillDefaults.morph.spec()),
                modifier = Modifier.weight(1f),
            ) {
                field(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CancelButton(size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = OpenCodeShape.circle,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = OpenCodeTheme.colors.text,
        modifier = Modifier
            .size(size)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Cancel Search" }
            .testTag("drawer-search-cancel"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            OpenCodeIcon(SFSymbol.Xmark, contentDescription = null)
        }
    }
}

/** Lays the element out at [widthDp], read at layout so springing it never recomposes. */
private fun Modifier.animatedWidth(widthDp: () -> Float): Modifier = layout { measurable, constraints ->
    val width = (widthDp().coerceAtLeast(0f) * density).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** Swallows touches in the `Initial` pass while [block], so nothing beneath reacts (iOS `.allowsHitTesting(false)`). */
private fun Modifier.blockingTouches(block: Boolean): Modifier = if (!block) {
    this
} else {
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The drawer's title fades out beneath the opening search, quicker than the capsule grows, and
 * stops taking touches and TalkBack focus (iOS `DrawerSearchTitleFade`).
 */
fun Modifier.drawerSearchTitleFade(state: DrawerSearchState): Modifier = composed {
    val searching = state.isSearching
    val alpha by animateFloatAsState(
        if (searching) 0f else 1f,
        Motion.easeOut(if (searching) 0.12 else 0.2),
        label = "drawer-search-title",
    )
    graphicsLayer { this.alpha = alpha }
        .blockingTouches(searching)
        .then(if (searching) Modifier.clearAndSetSemantics {} else Modifier)
}

/** The drawer's floating bottom controls fade out of the way while search owns the keyboard (iOS `DrawerSearchFocusFade`). */
fun Modifier.drawerSearchFocusFade(state: DrawerSearchState, reducedMotion: Boolean): Modifier = composed {
    val focused = state.isFocused
    val alpha by animateFloatAsState(
        if (focused) 0f else 1f,
        if (reducedMotion) snap() else tween(motionMillis(0.15), easing = Motion.EaseOut),
        label = "drawer-search-focus",
    )
    graphicsLayer { this.alpha = alpha }
        .blockingTouches(focused)
        .then(if (focused) Modifier.clearAndSetSemantics {} else Modifier)
}

/** The raised bottom controls stop taking touches while search owns the keyboard (iOS `DrawerSearchFocusHitTesting`). */
fun Modifier.drawerSearchFocusHitTesting(state: DrawerSearchState): Modifier = composed {
    blockingTouches(state.isFocused)
}
