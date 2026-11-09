@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.opencode.feature.composer.modelpicker

import androidx.compose.ui.focus.onFocusChanged
import ai.opencode.core.ui.openCodeFieldContainer
import ai.opencode.core.ui.OpenCodeTextFieldDefaults
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.ProviderIcon as CoreProviderIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.OpenCodeBottomSheet
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModel
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Today model picker and its leaves, from iOS `Session/SessionView+ModelPicker.swift`
 * (`plainModelPicker`, `ModelPickerDetents`) and `SessionView+ComposerControls.swift` (the chip):
 * [ModelPickerDetentsSheet], [PlainModelPickerSheet] with its **Thinking** menu, and
 * [ModelPickerChip]. The session area owns those iOS views; they live here because the composer's
 * standalone [ai.opencode.feature.composer.ComposerView] and One Picker's sheet host compose them
 * too. iOS's Today picker searches with `.searchable`; here [ModelSearchCapsuleField] stands in.
 * They used to sit in `ModelPickerSheet.kt` beside the Sheet picker, deleted with iOS 0fbb99f8.
 */

/** The sheet's inline title row: [title] centred, optional leading and trailing buttons. */
@Composable
internal fun ModelSheetTitleBar(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Text(
            title,
            style = OpenCodeTextStyles.headline,
            color = OpenCodeTheme.colors.text,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

/**
 * iOS `ModelPickerDetents` around a stock sheet: the Today picker (and One Picker's sheet while
 * the composer is covered) open at the partially expanded rest and drag up to full. Dragged
 * down from full, a flick at [ModelPickerTunables.flickCloses] (800 dp/s) or faster closes them
 * and a slower drag rests partially expanded (a probe beside the sheet's own drag reads the
 * release velocity, which the settled value doesn't carry). Under TalkBack the grabber still
 * steps between the two rests: collapsing there isn't a request to close.
 *
 * [onClose] asks the owner to close it (`activePicker = nil`); [onClosed] runs once it leaves
 * however it closed (iOS `onDisappear`: `.modelListClosed`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerDetentsSheet(
    onClose: () -> Unit,
    onClosed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnClosed by rememberUpdatedState(onClosed)
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    // How fast the latest drag on the sheet was moving down (dp/s), kept as it moves.
    var downwardVelocity by remember { mutableFloatStateOf(0f) }
    val holder = remember { arrayOfNulls<androidx.compose.material3.SheetState>(1) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { target ->
            val current = holder[0]?.currentValue
            val talkBack = accessibility?.isTouchExplorationEnabled == true
            if (current == SheetValue.Expanded && target == SheetValue.PartiallyExpanded && !talkBack &&
                downwardVelocity >= ModelPickerTunables.flickCloses
            ) {
                scope.launch { holder[0]?.hide() }.invokeOnCompletion { currentOnClose() }
                false
            } else {
                true
            }
        },
    )
    holder[0] = sheetState
    DisposableEffect(Unit) { onDispose { currentOnClosed() } }

    val probe = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                // Local positions move with the sheet and under-report a flick. Track the
                // original pointer samples, as UIKit's pan.velocity(in: nil) does.
                tracker.addPointerInputChange(change)
                val vy = tracker.calculateVelocity().y
                downwardVelocity = with(density) { maxOf(vy, 0f).toDp().value }
            }
        }
    }

    // A pick, Cancel or Done closes the sheet by removing it (`activePicker = nil`), which on
    // its own would drop it from the screen in one frame: it slides down first, then the event
    // goes (iOS dismisses the sheet with its own animation).
    var hiding by remember { mutableStateOf(false) }
    val hideThen: (() -> Unit) -> Unit = { action ->
        if (!hiding) {
            hiding = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
        }
    }
    OpenCodeBottomSheet(
        onDismissRequest = { currentOnClose() },
        sheetState = sheetState,
        dragHandle = { Box(probe) { OpenCodeSheetHandle() } },
    ) {
        // UIKit lifts a medium sheet when Search brings up the keyboard. Keep that expanded
        // rest after the keyboard goes, but don't fight a subsequent drag back down.
        val imeVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        var liftedForIme by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible, sheetState.currentValue) {
            if (!imeVisible) {
                liftedForIme = false
            } else if (sheetState.currentValue == SheetValue.PartiallyExpanded && sheetState.targetValue != SheetValue.Hidden) {
                if (liftedForIme) {
                    // A slow drag back down is the reader's choice, not another keyboard rise.
                    // UIKit dismisses input when panning the sheet; don't bounce back to full.
                    focusManager.clearFocus()
                } else {
                    liftedForIme = true
                    sheetState.expand()
                }
            }
        }
        CompositionLocalProvider(LocalModelSheetHideThen provides hideThen) {
            Box(probe.fillMaxWidth()) { content() }
        }
    }
}

/** The enclosing [ModelPickerDetentsSheet]'s slide-down, run before an event that closes it. */
internal val LocalModelSheetHideThen = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/**
 * [onEvent] with the events that close the sheet (a pick, Cancel, Done) sent once it has slid
 * down. Call inside a [ModelPickerDetentsSheet]'s content.
 */
@Composable
internal fun closingAfterHide(onEvent: (ModelPickerEvent) -> Unit): (ModelPickerEvent) -> Unit {
    val hideThen = LocalModelSheetHideThen.current ?: return onEvent
    return { event ->
        when (event) {
            ModelPickerEvent.Dismiss, is ModelPickerEvent.ModelPicked, is ModelPickerEvent.ModelPickedWithLevel -> hideThen { onEvent(event) }
            else -> onEvent(event)
        }
    }
}

/**
 * The Today picker (`plainModelPicker`, the default style): a plain native **Model** sheet,
 * searchable, favorites first with a star, provider icon and name, a checkmark on the current
 * model, swipe to favorite, and a **Thinking** menu for the current model's variants.
 */
@Composable
fun PlainModelPickerSheet(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
) {
    ModelPickerDetentsSheet(onClose = { onEvent(ModelPickerEvent.Dismiss) }, onClosed = { onEvent(ModelPickerEvent.ModelListClosed) }) {
        @Suppress("NAME_SHADOWING") val onEvent = closingAfterHide(onEvent)
        Column(Modifier.fillMaxSize().testTag("screen-Model Picker")) {
            val variants = state.currentModelInfo?.let { model ->
                ModelVariant.sorted(model.variants.filter { it != "default" }).takeIf { it.isNotEmpty() }
            }
            ModelSheetTitleBar(
                title = "Model",
                leading = { TextButton(onClick = { onEvent(ModelPickerEvent.Dismiss) }) { Text("Cancel") } },
                trailing = variants?.let { list -> { ThinkingMenu(state, list, onEvent) } },
            )
            ModelSearchCapsuleField(
                text = state.modelFilter,
                onTextChange = { onEvent(ModelPickerEvent.FilterChanged(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                minHeight = 44.0,
                placeholder = "Search",
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.isLoadingModels && state.models.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val models = if (state.isFilteringModels) state.filteredModels else state.favoriteModels + state.unfavoriteModels
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(models, key = { it.id }) { model -> PlainModelRow(model, state, onEvent) }
                        item(key = "show-all") {
                            ShowAllModelsRow(state.hiddenModelCount, state.showsAllModels, onToggle = { onEvent(ModelPickerEvent.ShowAllModelsToggled) })
                        }
                    }
                }
                if (!state.isLoadingModels && state.filteredModels.isEmpty() && state.hiddenModelCount == 0) {
                    ModelListEmptyState(state, includesLoadError = true, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
                }
            }
        }
    }
}

/** The Today picker's **Thinking** menu: Default, then the current model's variants. */
@Composable
private fun ThinkingMenu(state: ModelPickerState, variants: List<String>, onEvent: (ModelPickerEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.currentModel?.variant?.takeIf { it != "default" }
    Box {
        Row(
            Modifier
                .clickable(role = Role.Button) { open = true }
                .openCodeTapTarget()
                .clearAndSetSemantics {
                    contentDescription = "Thinking"
                    stateDescription = current?.let(ModelVariant::label) ?: "Default"
                    role = Role.DropdownList
                }
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current?.let(ModelVariant::label) ?: "Default", fontWeight = FontWeight.SemiBold, color = OpenCodeTheme.colors.accent)
            OpenCodeIcon(SFSymbol.ChevronUpChevronDown, null, size = 12.dp, tint = OpenCodeTheme.colors.accent)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = listOf<String?>(null) + variants
            for (option in options) {
                val selected = option == current
                DropdownMenuItem(
                    text = { Text(option?.let(ModelVariant::label) ?: "Default") },
                    leadingIcon = { Box(Modifier.width(24.dp)) { if (selected) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small) } },
                    modifier = Modifier.semantics { this.selected = selected },
                    onClick = {
                        open = false
                        onEvent(ModelPickerEvent.VariantPicked(option))
                    },
                )
            }
        }
    }
}

/** [icon] Model  Provider (dimmed). */
@Composable
private fun PlainModelRow(model: OpenCodeModel, state: ModelPickerState, onEvent: (ModelPickerEvent) -> Unit) {
    val isCurrent = state.currentModelInfo?.id == model.id
    val isFavorite = model.id in state.favoriteModelIDs
    FavoriteSwipeBox(isFavorite = isFavorite, favoriteSymbol = SFSymbol.Star, onToggle = { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onEvent(ModelPickerEvent.ModelPicked(model)) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { selected = isCurrent }
                .testTag("model-row-${model.id}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(model.providerID, model.canonicalProviderID, size = 20.dp, tint = OpenCodeTheme.colors.secondaryText)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    ModelProviderGroup.title(model.providerID),
                    style = OpenCodeTextStyles.subheadline,
                    color = OpenCodeTheme.colors.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (isFavorite) OpenCodeIcon(SFSymbol.StarFill, null, size = 14.dp, tint = FavoriteYellow)
            if (isCurrent) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.accent)
        }
    }
}

/**
 * The composer's model chip with One Picker off (`SessionView+ComposerControls.swift`): reads
 * `"<Model> ⌄"` or `"<Model> · <Level> ⌄"`. TalkBack: **Choose model**, its value the label.
 * Secondary text, subheadline medium, ≥ 48 dp tall. [targetDrop]: how far its target sits below
 * its ink (the composer's `pickerTargetDrop`).
 */
@Composable
fun ModelPickerChip(
    state: ModelPickerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    targetDrop: Dp = 0.dp,
) {
    val secondary = OpenCodeTheme.colors.secondaryText
    Box(modifier) {
        // Ahead of the picker's first opening, its provider marks decode while the composer stands.
        ProviderIconPrewarm(state)
        Row(
            Modifier
                // The target sits [targetDrop] below the ink (iOS `openCodeTapTarget(lowered:)`):
                // the chip's touch and TalkBack bounds move down and its ink moves back up.
                .offset(y = targetDrop)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = "Choose model"
                    stateDescription = state.currentModelLabel
                    role = Role.Button
                }
                .testTag("composer-model")
                .offset(y = -targetDrop),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)
            Text(state.currentModelLabel, style = style, color = secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
            OpenCodeIcon(SFSymbol.ChevronDown, null, size = 12.dp, tint = secondary)
        }
    }
}

/**
 * A magnifier, the placeholder, and a Clear button while there's text, in the text field's container:
 * the Today picker's search (iOS `ModelSearchCapsuleField`, deleted with Grow from Composer in
 * 0fbb99f8; iOS's Today picker uses `.searchable`).
 */
@Composable
internal fun ModelSearchCapsuleField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    spacing: Dp = 6.dp,
    leadingPadding: Dp = 12.dp,
    emptyTrailingPadding: Dp = 12.dp,
    clearTrailingPadding: Dp = 0.dp,
    minHeight: Double = 44.0,
    imeAction: ImeAction = ImeAction.Search,
    placeholder: String = "Search Models",
    onSubmit: () -> Unit = {},
) {
    // Feed edits back to BasicTextField immediately, before the store's Flow collector redraws
    // its parent. An old value in between resets the caret and reorders the next characters.
    // A delayed echo of an earlier edit must not roll back newer keystrokes. External changes
    // still replace the buffer; an echo acknowledges all edits up to that value.
    var input by remember { mutableStateOf(text) }
    val pendingEdits = remember { mutableListOf<String>() }
    LaunchedEffect(text) {
        val acknowledged = pendingEdits.indexOf(text)
        if (acknowledged >= 0) {
            pendingEdits.subList(0, acknowledged + 1).clear()
        } else if (input != text) {
            pendingEdits.clear()
            input = text
        }
    }
    val changeText: (String) -> Unit = {
        input = it
        pendingEdits.add(it)
        onTextChange(it)
    }
    val textColor = OpenCodeTheme.colors.text
    val fieldColors = OpenCodeTextFieldDefaults.colors()
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .openCodeFieldContainer(isFocused = isFocused)
            .padding(start = leadingPadding, end = if (input.isEmpty()) emptyTrailingPadding else clearTrailingPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(SFSymbol.Magnifyingglass, null, size = OpenCodeTextFieldDefaults.iconSize, tint = fieldColors.icon)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (input.isEmpty()) Text(placeholder, style = OpenCodeTextFieldDefaults.textStyle, color = fieldColors.placeholder, maxLines = 1)
            BasicTextField(
                value = input,
                onValueChange = changeText,
                singleLine = true,
                textStyle = OpenCodeTextFieldDefaults.textStyle.copy(color = textColor),
                cursorBrush = SolidColor(fieldColors.cursor),
                // Autocorrection stays on: its suggestions keep the keyboard the message field's height.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .onFocusChanged { isFocused = it.isFocused }
                    .semantics { contentDescription = placeholder }
                    .testTag("model-search"),
            )
        }
        if (input.isNotEmpty()) {
            Box(
                Modifier
                    .clickable(role = Role.Button) { changeText("") }
                    .openCodeTapTarget()
                    .semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.XmarkCircleFill, null, size = 20.dp, tint = OpenCodeTheme.colors.tertiaryText)
            }
        }
    }
}

/** iOS's `.yellow` swipe tint (the Today picker and One Picker). */
internal val FavoriteYellow = Color(0xFFFFCC00)

/**
 * A trailing swipe that favorites or unfavorites the row (iOS `swipeActions(edge: .trailing,
 * allowsFullSwipe: true)`): swiped past the threshold it toggles and springs back.
 */
@Composable
internal fun FavoriteSwipeBox(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    tint: Color = FavoriteYellow,
    /** The Favorite action's glyph (the Sheet and composer lists `star.fill`, Today's `star`). */
    favoriteSymbol: SFSymbol = SFSymbol.StarFill,
    /** The row's own fill, drawn over the action while it rests. */
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    /** One Picker's rows also swipe from the leading edge (iOS's leading and trailing actions). */
    swipesFromStart: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = swipesFromStart,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(tint).padding(horizontal = 20.dp),
                horizontalArrangement = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(if (isFavorite) SFSymbol.StarSlash else favoriteSymbol, null, size = 20.dp, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(if (isFavorite) "Unfavorite" else "Favorite", color = Color.White, style = OpenCodeTextStyles.subheadline)
            }
        },
        onDismiss = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onToggle()
            scope.launch { swipe.reset() }
        },
    ) {
        Box(Modifier.background(containerColor)) { content() }
    }
}

/**
 * The provider's mark: iOS `ProviderIcon` (`Session/SessionView.swift`), the `provider-<id>`
 * template image (its own, else its canonical provider's, else a Console connection's upstream,
 * else a neutral placeholder), drawn in [tint]. Decorative (TalkBack hidden).
 */
@Composable
internal fun ProviderIcon(providerID: String, canonical: String?, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    CoreProviderIcon(providerID, modifier.size(size), canonical, tint)
}

/**
 * Decodes the picker's provider marks into the image cache before the picker first opens, so
 * its rows draw them on their first frame instead of popping in as each SVG decodes (and the
 * decoder's classes load off the opening). Starts a beat after it appears; each distinct mark
 * (current, favourites, then the rest, at most [PROVIDER_PREWARM_LIMIT]) is composed once at the
 * largest size the picker draws, transparent, taking no room and outside semantics.
 */
@Composable
internal fun ProviderIconPrewarm(state: ModelPickerState) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROVIDER_PREWARM_DELAY_MILLIS)
        started = true
    }
    if (!started) return
    val marks = remember(state.models, state.modelVisibility, state.currentModel, state.favoriteModelIDs) {
        val current = state.currentModelInfo
        val visible = state.pickerModels
        (listOfNotNull(current) + visible.filter { it.id in state.favoriteModelIDs } + visible)
            .distinctBy { CoreProviderIcon.assetName(it.providerID, it.canonicalProviderID) }
            .take(PROVIDER_PREWARM_LIMIT)
    }
    Layout(
        content = {
            for (model in marks) {
                key(model.providerID, model.canonicalProviderID) {
                    CoreProviderIcon(model.providerID, Modifier, model.canonicalProviderID)
                }
            }
        },
        modifier = Modifier
            .graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {},
    ) { measurables, _ ->
        val side = PROVIDER_PREWARM_SIDE.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(side, side)) }
        layout(0, 0) { placeables.forEach { it.place(0, 0) } }
    }
}

/** The largest provider mark the pickers draw (the sheets' rows). */
private val PROVIDER_PREWARM_SIDE = 20.dp
private const val PROVIDER_PREWARM_LIMIT = 24
private const val PROVIDER_PREWARM_DELAY_MILLIS = 900L
