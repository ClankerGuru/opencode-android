package ai.opencode.devtools.tuning

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.tuning.TuneKind
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TunableDescriptor
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.tuning.TuningTitle
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The orange iOS uses for a changed value. */
private val ChangedColor = Color(0xFFFF9500)

/**
 * The floating Tuning Panel (iOS `TuningPanelView` + `TuningPlacement`): a small M3 surface over
 * the app that takes touches only inside itself. Drag its header to move it (it settles against
 * the nearer side, at the height you leave it); Collapse folds it into a pill naming the screen
 * and how many values are changed. Rows: label, slider/switch/menu, and value (orange once
 * changed; tap it to reset). Presets show as numbered chips at the top of their group.
 */
@Composable
fun TuningPanel(store: StoreOf<TuningFeature.State, TuningFeature.Action>, modifier: Modifier = Modifier) {
    val state by store.collectState()
    LaunchedEffect(store) { store.send(TuningFeature.Action.Task) }
    if (!state.isPanelShown) return
    val tuning = TuningStore.shared
    // Observed: registration and value changes redraw the panel.
    tuning.catalogRevision
    val overrides = tuning.observedOverrides
    val groups = TuningPanelGroups.shown(
        registered = tuning.descriptors.map { it.group }.distinct(),
        context = tuning.context,
        showsAll = state.showsAll,
        hidden = tuning.hiddenGroups,
    )
    val changed = overrides.keys.count { key -> key.substringBefore('.') in groups }
    val title = if (state.showsAll) "All" else tuning.context.displayTitle

    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        var size by remember { mutableStateOf(IntSize.Zero) }
        var dragX by remember { mutableFloatStateOf(Float.NaN) }
        var dragY by remember { mutableFloatStateOf(Float.NaN) }
        val freeW = (maxW - size.width).coerceAtLeast(0f)
        val freeH = (maxH - size.height).coerceAtLeast(0f)
        val x = if (dragX.isNaN()) state.placementX * freeW else dragX
        val y = if (dragY.isNaN()) state.placementY * freeH else dragY
        val drag = Modifier.pointerInputDrag(
            onDrag = { dx, dy ->
                dragX = ((if (dragX.isNaN()) x else dragX) + dx).coerceIn(0f, freeW)
                dragY = ((if (dragY.isNaN()) y else dragY) + dy).coerceIn(0f, freeH)
            },
            onEnd = {
                val endX = if (dragX.isNaN()) x else dragX
                val endY = if (dragY.isNaN()) y else dragY
                // Settles against the nearer side, at the height it was left.
                store.send(TuningFeature.Action.PlacementChanged(if (endX + size.width / 2f > maxW / 2f) 1f else 0f, if (freeH > 0) endY / freeH else 0f))
                dragX = Float.NaN
                dragY = Float.NaN
            },
        )
        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { size = it }
                .semantics { contentDescription = "Tuning Panel" },
            shape = RoundedCornerShape(if (state.isCollapsed) 24.dp else 20.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (state.isCollapsed) {
                Row(
                    drag.clickable(onClickLabel = "Expand") { store.send(TuningFeature.Action.ExpandTapped) }
                        .openCodeTapTarget()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenCodeIcon(SFSymbol.SliderHorizontal3, null, size = OpenCodeIconSize.small)
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    if (changed > 0) Text("$changed", style = MaterialTheme.typography.labelLarge, color = ChangedColor)
                }
            } else {
                Column(Modifier.widthIn(max = 360.dp).width(340.dp).heightIn(max = with(density) { (maxH * 0.6f).toDp() })) {
                    PanelHeader(store, state, title, changed, groups, drag)
                    HorizontalDivider()
                    if (groups.isEmpty()) {
                        Text(
                            if (state.showsAll) "Nothing to tune yet" else "Nothing to tune here",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            for (group in groups) {
                                item(key = "header-$group") {
                                    GroupHeader(store, group, isFolded = group in state.foldedGroups, overrides = overrides)
                                }
                                if (group !in state.foldedGroups) {
                                    val presets = tuning.presets.filter { it.group == group }
                                    for (set in presets) {
                                        item(key = "presets-${set.key}") { PresetChips(store, set, overrides) }
                                    }
                                    for ((sectionTitle, rows) in sections(tuning.descriptors.filter { it.group == group })) {
                                        if (sectionTitle.isNotEmpty()) {
                                            item(key = "section-$group-$sectionTitle") {
                                                Text(
                                                    sectionTitle,
                                                    Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        items(rows, key = { it.key }) { descriptor ->
                                            TunableRow(descriptor, overrides[descriptor.key]) { value ->
                                                store.send(TuningFeature.Action.ValueChanged(descriptor.key, value))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.didCopy) {
                        Text("Copied", Modifier.padding(12.dp).align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun Modifier.pointerInputDrag(onDrag: (Float, Float) -> Unit, onEnd: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
                change.consume()
                onDrag(amount.x, amount.y)
            }
        },
    )

@Composable
private fun PanelHeader(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    state: TuningFeature.State,
    title: String,
    changed: Int,
    groups: List<String>,
    drag: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(drag.weight(1f).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (changed > 0) Text("$changed", style = MaterialTheme.typography.labelMedium, color = ChangedColor)
        }
        FilterChip(
            selected = state.showsAll,
            onClick = { store.send(TuningFeature.Action.ShowsAllToggled) },
            label = { Text("All") },
            modifier = Modifier.semantics { contentDescription = "Show All Groups" },
        )
        Box {
            IconButton(onClick = { menu = true }) { OpenCodeIcon(SFSymbol.Ellipsis, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy All Values") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyValuesTapped(null)) }, leadingIcon = { OpenCodeIcon(SFSymbol.DocOnDoc, null) })
                DropdownMenuItem(text = { Text("Copy All as Kotlin") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyKotlinTapped(null)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ChevronLeftForwardslashChevronRight, null) })
                DropdownMenuItem(
                    text = { Text("Reset All", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; store.send(TuningFeature.Action.ResetTapped(null)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.ArrowCounterclockwise, null, tint = MaterialTheme.colorScheme.error) },
                )
                val hidden = TuningStore.shared.hiddenGroups.sorted()
                if (hidden.isNotEmpty()) HorizontalDivider()
                for (group in hidden) {
                    DropdownMenuItem(text = { Text("Show ${TuningTitle.format(group)}") }, onClick = { menu = false; store.send(TuningFeature.Action.GroupHidden(group, false)) }, leadingIcon = { OpenCodeIcon(SFSymbol.Eye, null) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Developer Tools") }, onClick = { menu = false; store.send(TuningFeature.Action.DeveloperToolsTapped) }, leadingIcon = { OpenCodeIcon(SFSymbol.HammerFill, null) })
                DropdownMenuItem(text = { Text("Turn Off Tuning") }, onClick = { menu = false; store.send(TuningFeature.Action.TurnOffTapped) }, leadingIcon = { OpenCodeIcon(SFSymbol.Power, null) })
            }
        }
        IconButton(onClick = { store.send(TuningFeature.Action.CollapseTapped) }) { OpenCodeIcon(SFSymbol.ArrowDownRightAndArrowUpLeft, "Collapse") }
        IconButton(onClick = { store.send(TuningFeature.Action.CloseTapped) }) { OpenCodeIcon(SFSymbol.Xmark, "Close") }
    }
    if (groups.isEmpty()) Spacer(Modifier.size(0.dp))
}

@Composable
private fun GroupHeader(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    group: String,
    isFolded: Boolean,
    overrides: Map<String, TuneValue>,
) {
    val title = TuningTitle.format(group)
    val changed = overrides.keys.count { it.substringBefore('.') == group }
    val canUndo = TuningStore.shared.canUndoRoll(group)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .clickable(onClickLabel = if (isFolded) "Expand" else "Collapse") { store.send(TuningFeature.Action.GroupFolded(group, !isFolded)) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = title },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpenCodeIcon(if (isFolded) SFSymbol.ChevronRight else SFSymbol.ChevronDown, null, size = OpenCodeIconSize.small)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (changed > 0) Text("$changed", style = MaterialTheme.typography.labelMedium, color = ChangedColor)
        }
        if (canUndo) {
            IconButton(onClick = { store.send(TuningFeature.Action.UndoRollTapped(group)) }) { OpenCodeIcon(SFSymbol.ArrowUturnBackward, "Undo $title Roll") }
        }
        IconButton(onClick = { store.send(TuningFeature.Action.RandomizeTapped(group)) }) { OpenCodeIcon(SFSymbol.DieFace5, "Randomize $title") }
        Box {
            IconButton(onClick = { menu = true }) { OpenCodeIcon(SFSymbol.Ellipsis, "$title Actions") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (canUndo) DropdownMenuItem(text = { Text("Undo Roll") }, onClick = { menu = false; store.send(TuningFeature.Action.UndoRollTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ArrowUturnBackward, null) })
                DropdownMenuItem(text = { Text("Randomize $title") }, onClick = { menu = false; store.send(TuningFeature.Action.RandomizeTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.DieFace5, null) })
                DropdownMenuItem(text = { Text("Copy Values") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyValuesTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.DocOnDoc, null) })
                DropdownMenuItem(text = { Text("Copy as Kotlin") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyKotlinTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ChevronLeftForwardslashChevronRight, null) })
                DropdownMenuItem(text = { Text("Reset $title") }, onClick = { menu = false; store.send(TuningFeature.Action.ResetTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ArrowCounterclockwise, null) })
                DropdownMenuItem(text = { Text("Hide Group") }, onClick = { menu = false; store.send(TuningFeature.Action.GroupHidden(group, true)) }, leadingIcon = { OpenCodeIcon(SFSymbol.EyeSlash, null) })
            }
        }
    }
}

@Composable
private fun PresetChips(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    presets: ai.opencode.core.tuning.TuningPresets,
    overrides: Map<String, TuneValue>,
) {
    val matching = presets.matching(overrides)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.presets.forEachIndexed { index, preset ->
            FilterChip(
                selected = matching == index,
                onClick = { store.send(TuningFeature.Action.PresetSelected(presets.key, index)) },
                label = { Text("${index + 1} ${preset.name}") },
                modifier = Modifier.semantics { contentDescription = "${presets.label} ${index + 1}: ${preset.name}" },
            )
        }
    }
}

@Composable
private fun TunableRow(descriptor: TunableDescriptor, override: TuneValue?, set: (TuneValue?) -> Unit) {
    val current = override ?: descriptor.defaultValue
    val isChanged = override != null
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            descriptor.label,
            Modifier.width(118.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (descriptor.kind) {
            TuneKind.Number -> {
                val number = (current as? TuneValue.Number)?.value ?: 0.0
                val range = descriptor.effectiveRange
                // Continuous track (no stop marks for 100+ positions), snapped to the step grid.
                val step = descriptor.step ?: if (descriptor.valueType == "Int") 1.0 else null
                Slider(
                    value = number.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                    onValueChange = { raw ->
                        val snapped = if (step != null && step > 0) range.start + Math.round((raw - range.start) / step) * step else raw.toDouble()
                        set(TuneValue.Number(snapped))
                    },
                    valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                    modifier = Modifier.weight(1f).semantics { contentDescription = descriptor.label },
                )
                ValueText(descriptor.valueText(current), isChanged) { set(null) }
            }
            TuneKind.Bool -> {
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = (current as? TuneValue.Bool)?.value ?: false,
                    onCheckedChange = { set(TuneValue.Bool(it)) },
                    modifier = Modifier.semantics { contentDescription = descriptor.label },
                )
                if (isChanged) ValueText("Reset", true) { set(null) }
            }
            TuneKind.Choice -> {
                var open by remember { mutableStateOf(false) }
                val choice = (current as? TuneValue.Choice)?.value.orEmpty()
                Spacer(Modifier.weight(1f))
                Box {
                    Text(
                        TuningTitle.format(choice),
                        Modifier.clickable(onClickLabel = descriptor.label) { open = true }.openCodeTapTarget().padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isChanged) ChangedColor else MaterialTheme.colorScheme.primary,
                    )
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        for (option in descriptor.choices.orEmpty()) {
                            DropdownMenuItem(text = { Text(TuningTitle.format(option)) }, onClick = { open = false; set(TuneValue.Choice(option)) })
                        }
                    }
                }
                if (isChanged) ValueText("Reset", true) { set(null) }
            }
        }
    }
}

@Composable
private fun ValueText(text: String, isChanged: Boolean, reset: () -> Unit) {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    val color = if (isChanged) ChangedColor else OpenCodeTheme.colors.secondaryText
    if (isChanged) {
        Text(text, Modifier.clickable(onClickLabel = "Reset") { reset() }.openCodeTapTarget().padding(start = 6.dp), style = style, color = color)
    } else {
        Text(text, Modifier.widthIn(min = 44.dp).padding(start = 6.dp), style = style, color = color)
    }
}

/** iOS `TuningPanel.sections`: rows grouped by the key's second segment ("drawer.edge.…" is Edge). */
internal fun sections(rows: List<TunableDescriptor>): List<Pair<String, List<TunableDescriptor>>> {
    val result = LinkedHashMap<String, MutableList<TunableDescriptor>>()
    for (row in rows) {
        val title = row.section?.let(TuningTitle::format).orEmpty()
        result.getOrPut(title) { mutableListOf() } += row
    }
    return result.map { it.key to it.value }
}
