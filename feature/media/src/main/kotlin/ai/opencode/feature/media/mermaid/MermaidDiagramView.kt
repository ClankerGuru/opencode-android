package ai.opencode.feature.media

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Port of iOS `MermaidDiagramCard`'s constants and sizing. */
object MermaidDiagramMetrics {
    val maxHeight: Dp = 360.dp
    val placeholderHeight: Dp = 180.dp

    /** Fits the card's width and the height cap, never larger than drawn (all in dp). */
    fun displaySize(natural: Size, width: Float): Size {
        if (natural.width <= 0 || natural.height <= 0 || width <= 0) return Size.Zero
        val scale = min(1f, min(width / natural.width, maxHeight.value / natural.height))
        return Size((natural.width * scale).roundToInt().toFloat(), (natural.height * scale).roundToInt().toFloat())
    }
}

/**
 * A finished Mermaid block as a card showing the diagram at its natural aspect, at most
 * [MermaidDiagramMetrics.maxHeight] tall. It holds a steady height while the diagram renders,
 * opens full screen (zoomable, sharp) on a tap, and shows its source from the hold menu. A
 * diagram Mermaid rejects shows as the code it is; one whose render was interrupted is tried once
 * more, then shows the code until it appears again or is tapped. Port of iOS `MermaidDiagramCard`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MermaidDiagramCard(
    source: String,
    modifier: Modifier = Modifier,
    onPreview: (TranscriptImagePreview) -> Unit = {},
) {
    val renderer = MermaidRenderer.shared
    val theme = if (OpenCodeTheme.colors.isDark) MermaidTheme.Dark else MermaidTheme.Light
    val density = LocalDensity.current.density
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val presenter = LocalMediaPresenter.current
    val focusManager = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()

    val diagramKey = remember(source, theme) { MermaidCacheKey(source, theme) }
    val mediaID = "mermaid|${diagramKey.string}"
    val diagramType = remember(source) { MermaidSyntax.diagramType(source) ?: "Mermaid" }
    // The diagram's natural size, once drawn (also cached in the renderer, so a reused row
    // lays out at its final height at once).
    var natural by remember { mutableStateOf<Pair<MermaidCacheKey, Size>?>(null) }
    var image by remember { mutableStateOf<Pair<MermaidCacheKey, Bitmap>?>(null) }
    var failed by remember { mutableStateOf<MermaidCacheKey?>(null) }
    // Rendered twice without the page holding: shown as code, a tap retries.
    var interrupted by remember { mutableStateOf<MermaidCacheKey?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    val naturalSize: Size? = natural?.takeIf { it.first == diagramKey }?.second
        ?: renderer.cachedDiagram(source, theme)?.let { Size(it.width, it.height) }
    val hasFailed = failed == diagramKey || renderer.cachedFailure(source, theme)

    fun retry() {
        interrupted = null
        retries += 1
    }

    /** Full screen at a resolution that stays sharp when zoomed. */
    fun open() {
        scope.launch {
            val full = runCatching {
                renderer.viewerImage(source, theme, density, configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
            }.getOrNull() ?: return@launch
            onPreview(TranscriptImagePreview(mediaID, full, "Diagram, $diagramType"))
        }
    }

    fun showSource() {
        focusManager.clearFocus()
        presenter?.present(sourceID = mediaID, isMedia = false, reducesMotion = reduceMotion, screenName = "Diagram Viewer") { viewer ->
            MermaidSourceView(source, viewer)
        }
    }

    when {
        hasFailed -> MermaidCode(source, modifier)
        interrupted == diagramKey -> MermaidCode(
            source,
            modifier
                .clickable(onClick = ::retry)
                .semantics { customActions = listOf(CustomAccessibilityAction("Retry") { retry(); true }) },
        )
        else -> BoxWithConstraints(modifier.fillMaxWidth()) {
            val width = maxWidth.value
            val display = naturalSize?.let { MermaidDiagramMetrics.displaySize(it, width) } ?: Size.Zero
            // The card's width is what it's offered; its height follows the diagram's aspect
            // (a steady placeholder height until that's known).
            val height = if (naturalSize == null) MermaidDiagramMetrics.placeholderHeight else max(display.height, 44f).dp
            val key = MermaidCacheKey(source, theme, display.width)
            val shown = image?.takeIf { it.first == key }?.second ?: renderer.cachedImage(key)
            val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(shape)
                    .background(MermaidRenderer.background(theme), shape)
                    .combinedClickable(onClick = { if (shown != null) open() }, onLongClick = { menuOpen = true })
                    .clearAndSetSemantics {
                        contentDescription = "Diagram"
                        stateDescription = if (naturalSize == null) "$diagramType, loading" else diagramType
                        role = Role.Button
                        onClick { open(); true }
                        customActions = listOf(CustomAccessibilityAction("View Source") { showSource(); true })
                    }
                    .testTag("mermaid-diagram"),
                contentAlignment = Alignment.Center,
            ) {
                if (shown != null) {
                    MediaThumbnail(
                        image = shown,
                        ids = listOf(mediaID),
                        fits = true,
                        maxHeight = display.height.dp,
                        cornerRadius = if (display.width >= width - 1) TranscriptMetrics.cardCornerRadius else 0.dp,
                        modifier = Modifier.size(display.width.dp, display.height.dp),
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("View Source") },
                        leadingIcon = { OpenCodeIcon("chevron.left.forwardslash.chevron.right", contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            showSource()
                        },
                    )
                }
            }
            LaunchedEffect(key) {
                if (shown != null || display.width <= 0) return@LaunchedEffect
                val drawn = try {
                    renderer.image(source, theme, display.width, display.height, density)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return@LaunchedEffect
                }
                image = key to drawn
            }
        }
    }

    LaunchedEffect(diagramKey, retries) {
        renderer.useHost(view)
        val key = diagramKey
        if (naturalSize != null || hasFailed) return@LaunchedEffect
        for (attempt in 0 until 2) {
            try {
                val diagram = renderer.diagram(source, theme)
                natural = key to Size(diagram.width, diagram.height)
                return@LaunchedEffect
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: MermaidRenderError.InvalidDiagram) {
                failed = key
                return@LaunchedEffect
            } catch (_: Throwable) {
                // Interrupted (or the page couldn't load): once more, then code.
                if (attempt == 0) delay(1_000)
            }
        }
        interrupted = key
    }
}

/** The diagram as the code it is: a highlighted, horizontally scrolling band (iOS `MarkdownBand`). */
@Composable
private fun MermaidCode(source: String, modifier: Modifier = Modifier) {
    val palette = SyntaxHighlighter.Palette.current
    val highlighted = remember(source, palette) { SyntaxHighlighter.highlight(source, "mermaid", palette) }
    Column(modifier.fillMaxWidth().background(OpenCodeTheme.colors.codeBand)) {
        HorizontalDivider(thickness = 0.5.dp, color = OpenCodeTheme.colors.border)
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = TranscriptMetrics.contentGutter, vertical = 12.dp)) {
            SelectionContainer {
                Text(highlighted, style = codeStyle(), color = OpenCodeTheme.colors.text)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = OpenCodeTheme.colors.border)
    }
}

/** iOS `TranscriptMetrics.codeFont`: subheadline, monospaced. */
@Composable
private fun codeStyle() = OpenCodeTextStyles.subheadline.copy(fontFamily = FontFamily.Monospace)

/** A diagram's Mermaid source, highlighted and selectable (iOS `MermaidSourceView`). */
@Composable
private fun MermaidSourceView(source: String, viewer: MediaViewerState) {
    val scope = rememberCoroutineScope()
    val palette = SyntaxHighlighter.Palette.current
    val highlighted = remember(source, palette) { SyntaxHighlighter.highlight(source, "mermaid", palette) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    dependency(HapticsClient).play(Haptic.tap)
                    val pasteboard = dependency(PasteboardClient)
                    scope.launch { pasteboard.setString(source) }
                }) {
                    OpenCodeIcon("doc.on.doc", contentDescription = null, size = 18.dp)
                    Text("Copy", Modifier.padding(start = 6.dp))
                }
                Text(
                    "Diagram Source",
                    style = OpenCodeTextStyles.headline,
                    modifier = Modifier.weight(1f).semantics { },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { viewer.dismiss() }) { Text("Done") }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .testTag("mermaid-source"),
            ) {
                SelectionContainer {
                    Text(
                        highlighted,
                        style = codeStyle(),
                        color = OpenCodeTheme.colors.text,
                        softWrap = false,
                        modifier = Modifier.padding(TranscriptMetrics.contentGutter),
                    )
                }
            }
        }
    }
}
