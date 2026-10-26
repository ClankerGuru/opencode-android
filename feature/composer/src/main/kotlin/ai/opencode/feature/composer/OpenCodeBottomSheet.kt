package ai.opencode.feature.composer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.BottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scrim
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private class SheetPresentation(val content: @Composable () -> Unit)
private val LocalSheetPresentations = staticCompositionLocalOf<MutableList<SheetPresentation>?> { null }

/** One full-window overlay above Home's drawer, Catch Up chrome and floating call surfaces. */
@Composable
fun OpenCodeSheetHost(content: @Composable () -> Unit) {
    val presentations = remember { mutableStateListOf<SheetPresentation>() }
    val covered = presentations.isNotEmpty()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    DisposableEffect(covered) {
        val saved = covered && focus.saveFocusedChild()
        if (covered) focusManager.clearFocus()
        onDispose { if (saved) focus.restoreFocusedChild() }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().focusRequester(focus)
                .focusProperties { onEnter = { if (covered) cancelFocusChange() } }.focusGroup()
                .then(if (covered) Modifier.clearAndSetSemantics { }.consumeWindowInsets(WindowInsets.ime) else Modifier),
        ) {
            CompositionLocalProvider(LocalSheetPresentations provides presentations, content = content)
        }
        presentations.forEach { presentation ->
            androidx.compose.runtime.key(presentation) { presentation.content() }
        }
    }
}

/**
 * The same M3 modal surface without a new Dialog/ViewRoot on every show. Home hosts it above
 * all its chrome; embedded composers without a full-window host keep the native dialog.
 * M3 owns anchors, nested scroll, predictive back, drag dismissal, semantics and IME insets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val presentations = LocalSheetPresentations.current
    if (presentations == null) {
        ModalBottomSheet(onDismissRequest, modifier, sheetState, dragHandle = dragHandle, content = content)
        return
    }
    // Preserve the presenting session's dependencies, lifecycle and typography at the host.
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val currentContent by rememberUpdatedState<@Composable () -> Unit> {
        InWindowSheet(onDismissRequest, sheetState, modifier, dragHandle, content)
    }
    val presentation = remember { SheetPresentation { CompositionLocalProvider(locals) { currentContent() } } }
    DisposableEffect(presentations, presentation) {
        presentations.add(presentation)
        onDispose { presentations.remove(presentation) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InWindowSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier,
    dragHandle: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    // M3 enables predictive Back only once currentValue leaves Hidden. A Dialog used to
    // consume Back during this entrance gap; keep it from reaching the Activity instead.
    BackHandler(enabled = !sheetState.isVisible) {
        scope.launch { sheetState.hide(); if (!sheetState.isVisible) dismiss() }
    }
    // Match ModalBottomSheet's top-inset consumption without reducing the anchor space:
    // medium stays at half the window even when the IME appears; only its content is inset.
    val offsetInsets = remember(sheetState) {
        object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getTop(density: Density) =
                runCatching { sheetState.requireOffset().toInt().coerceAtLeast(0) }.getOrDefault(0)
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getBottom(density: Density) = 0
        }
    }
    val scrimAlpha by animateFloatAsState(
        if (sheetState.targetValue == SheetValue.Hidden) 0f else 1f,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sheetScrim",
    )
    Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        Scrim(
            contentDescription = stringResource(androidx.compose.ui.R.string.close_sheet),
            onClick = { scope.launch { sheetState.hide(); if (!sheetState.isVisible) dismiss() } },
            alpha = { scrimAlpha },
            color = BottomSheetDefaults.ScrimColor,
        )
        BottomSheet(
            modifier = modifier.align(Alignment.TopCenter).consumeWindowInsets(offsetInsets),
            state = sheetState,
            onDismissRequest = onDismissRequest,
            dragHandle = dragHandle,
            contentWindowInsets = { BottomSheetDefaults.modalWindowInsets },
            tonalElevation = 0.dp,
            content = content,
        )
    }
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.hasExpandedState }.first { it }
        sheetState.show()
    }
}
