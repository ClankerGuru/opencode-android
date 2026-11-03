package ai.opencode.feature.settings

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.ThemeDefinition
import ai.opencode.core.ui.rememberThemeDefinition
import ai.opencode.core.ui.ThemeSharedKeys
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.EaseInOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.min

/**
 * Port of iOS `Settings/AppearanceSettingsView.swift`.
 *
 * Settings → Appearance: System / Light / Dark mode override and a `Theme` row with a three-dot
 * palette swatch that pushes [ThemesSettingsView] (`Settings › Appearance › Themes`). Choosing a
 * theme or mode re-themes Settings in place: `OpenCodeAppTheme` at the root observes the keys,
 * and Compose cross-fades the colours.
 */
@Composable
fun AppearanceSettingsView(store: SettingsStore) {
    val appTheme by remember { shared(ThemeSharedKeys.appTheme).flow }.collectAsState()
    val scheme by remember { shared(ThemeSharedKeys.appearanceColorScheme).flow }.collectAsState()
    val isDark = scheme.resolveIsDark(isSystemInDarkTheme())
    val send = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }

    SettingsPageScaffold(title = "Appearance", store = store) {
        settingsSection(key = "mode", header = "Mode") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                AppearanceColorScheme.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = scheme == mode,
                        onClick = { send(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppearanceColorScheme.entries.size),
                        icon = {},
                    ) { Text(mode.title) }
                }
            }
        }
        settingsSection(key = "theme", header = "Theme") {
            SettingsRowFrame(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Theme"
                    stateDescription = appTheme.title
                },
                onClick = { store.send(SettingsFeature.Action.PagePushed(SettingsPage.Themes)) },
            ) {
                val swatch = @Composable {
                    ThemeSwatchTile(
                        rememberThemeDefinition(appTheme),
                        isDark = isDark,
                        size = ThemeSwatchTile.Size.Compact,
                        cardBackground = OpenCodeTheme.colors.groupedCard,
                    )
                }
                if (isAccessibilitySize()) {
                    // iOS stacks the label over the swatch and value at the accessibility sizes.
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Theme", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            swatch()
                            Text(appTheme.title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                        }
                    }
                } else {
                    Text("Theme", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        swatch()
                        Text(appTheme.title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
                    }
                }
                SettingsChevron()
            }
        }
    }
}

/**
 * Settings → Appearance → Themes (D-109 Split + D-110 Palette Dots): the top is a session preview
 * edge to edge in the theme's own canvas, pinned, and the curated theme list scrolls
 * independently beneath it, dissolving across a soft edge (iOS `ThemesSoftEdgeBackdrop`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemesSettingsView(store: SettingsStore) {
    val appTheme by remember { shared(ThemeSharedKeys.appTheme).flow }.collectAsState()
    val scheme by remember { shared(ThemeSharedKeys.appearanceColorScheme).flow }.collectAsState()
    val isDark = scheme.resolveIsDark(isSystemInDarkTheme())
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val canvas = rememberThemeDefinition(appTheme).variant(isDark).canvas.color
    val density = LocalDensity.current
    /** The pinned preview header's height (px), content plus its 14 pt bottom inset. */
    var headerHeight by remember { mutableIntStateOf(0) }
    val softEdgeBlur = rememberSoftEdgeBlur(headerHeight, opaque = colors.highContrast)

    Scaffold(
        containerColor = colors.groupedCanvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Themes", style = OpenCodeTextStyles.headline) },
                navigationIcon = {
                    IconButton(onClick = { store.send(SettingsFeature.Action.BackTapped) }) {
                        OpenCodeIcon(SFSymbol.ChevronBackward, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = canvas),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = with(density) { headerHeight.toDp() } + AppearanceTunables.softEdgeHeight + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    // The soft edge's progressive blur, applied to the list's own layer.
                    .graphicsLayer { renderEffect = softEdgeBlur },
            ) {
                settingsSection(key = "themes") {
                    AppTheme.entries.forEachIndexed { index, theme ->
                        if (index > 0) SettingsDivider(inset = true)
                        ThemeOptionRow(
                            theme = theme,
                            definition = rememberThemeDefinition(theme),
                            isDark = isDark,
                            isSelected = appTheme == theme,
                            onClick = { store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.AppThemeChanged(theme))) },
                        )
                    }
                }
            }
            if (headerHeight > 0) {
                ThemesSoftEdgeBackdrop(
                    header = headerHeight,
                    topBackground = canvas,
                    bottomBackground = colors.groupedCanvas,
                    opaque = colors.highContrast,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    // iOS pulls the rows 6 pt up under its translucent bar (`.padding(.top, -6)`);
                    // Android's bar is opaque, so that would cut the bubble's top: no pull.
                    .padding(bottom = SoftEdgeBottomInset)
                    .onSizeChanged { headerHeight = it.height },
            ) {
                Crossfade(
                    appTheme to isDark,
                    animationSpec = tween(if (reduceMotion) 0 else 220, easing = EaseInOut),
                    label = "theme-preview",
                ) { (theme, dark) ->
                    AppearanceTranscriptPreview(theme, dark)
                }
            }
        }
    }
}

/** iOS `AppearanceTunables` (the soft scroll edge under the pinned preview), at their defaults. */
internal object AppearanceTunables {
    /** Soft edge feather (pt). */
    val softEdgeHeight = 22.dp
    /** Soft edge blur. */
    val softEdgeBlur = 12.dp
}

private val SoftEdgeBottomInset = 14.dp

/**
 * Soft scroll-edge backdrop behind the pinned split preview (iOS `ThemesSoftEdgeBackdrop`): full
 * [topBackground] through `fadeOrigin`, then a smoothstep gradient easing to transparent over the
 * feather below the preview, so scrolling theme rows dissolve before they reach it; the list
 * itself blurs progressively across the `bottomInset + feather` band ([rememberSoftEdgeBlur]).
 * Under high contrast it is opaque.
 */
@Composable
private fun ThemesSoftEdgeBackdrop(
    header: Int,
    topBackground: Color,
    bottomBackground: Color,
    opaque: Boolean,
) {
    val density = LocalDensity.current
    val bottomInset = with(density) { SoftEdgeBottomInset.toPx() }
    val feather = with(density) { AppearanceTunables.softEdgeHeight.toPx() }
    val headerPx = max(header.toFloat(), 1f)
    val fadeOrigin = max(headerPx - bottomInset, 1f)
    val span = if (opaque) 0f else max(feather, 1f)
    val blurBand = bottomInset + span
    val total = fadeOrigin + blurBand
    val splitStop = (fadeOrigin / total).coerceIn(0f, 1f)

    if (opaque) {
        Box(Modifier.fillMaxWidth().height(with(density) { headerPx.toDp() }).background(topBackground).clearAndSetSemantics { })
        return
    }
    // The feather: the preview's canvas, then the grouped canvas easing to transparent.
    val stops = remember(topBackground, bottomBackground, splitStop) {
        buildList {
            add(0f to topBackground)
            add(splitStop to topBackground)
            val rest = max(1 - splitStop, 0.001f)
            for (step in 1..8) {
                val t = step / 8f
                val ease = 1 - t * t * (3 - 2 * t)
                add(splitStop + rest * t to bottomBackground.copy(alpha = bottomBackground.alpha * ease))
            }
        }.toTypedArray()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(density) { total.toDp() })
            .clearAndSetSemantics { }
            .background(Brush.verticalGradient(*stops)),
    )
}

/**
 * The soft edge's progressive blur (iOS `DrawerEdgeBlur(fadeStart:height:radius:curve: .smoothstep,
 * edge: .top)` over the `bottomInset + feather` band below the preview): full [AppearanceTunables.
 * softEdgeBlur] over the bottom inset, easing to none across the feather. An AGSL effect on the
 * list's own layer, so the list is drawn once (re-drawing a recorded layer under a blur crashed
 * the emulator's renderer). Null under high contrast, where the backdrop is opaque.
 */
@Composable
private fun rememberSoftEdgeBlur(header: Int, opaque: Boolean): RenderEffect? {
    val density = LocalDensity.current
    val shader = remember { RuntimeShader(SoftEdgeBlurShader) }
    return remember(header, opaque, density) {
        if (opaque || header <= 0) return@remember null
        val bottomInset = with(density) { SoftEdgeBottomInset.toPx() }
        val feather = max(with(density) { AppearanceTunables.softEdgeHeight.toPx() }, 1f)
        val fadeOrigin = max(header - bottomInset, 1f)
        val band = bottomInset + feather
        shader.setFloatUniform("origin", fadeOrigin)
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("fadeStart", (bottomInset / band).coerceIn(0f, 1f))
        shader.setFloatUniform("radius", with(density) { AppearanceTunables.softEdgeBlur.toPx() })
        android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** A Gaussian blur whose radius follows the smoothstep strength profile down the band. */
private const val SoftEdgeBlurShader = """
uniform shader content;
uniform float origin;
uniform float band;
uniform float fadeStart;
uniform float radius;

half4 main(float2 p) {
    float y = (p.y - origin) / band;
    if (y < 0.0 || y > 1.0) return content.eval(p);
    float t = clamp((y - fadeStart) / max(1.0 - fadeStart, 0.001), 0.0, 1.0);
    float strength = y <= fadeStart ? 1.0 : 1.0 - t * t * (3.0 - 2.0 * t);
    // Blur radius is about two standard deviations, as BlurEffect's.
    float sigma = radius * strength * 0.5;
    if (sigma < 0.3) return content.eval(p);
    half4 sum = half4(0);
    float weights = 0.0;
    for (int i = -4; i <= 4; i++) {
        for (int j = -4; j <= 4; j++) {
            float2 o = float2(float(i), float(j)) * (sigma * 0.5);
            float w = exp(-0.5 * dot(o, o) / (sigma * sigma));
            sum += content.eval(p + o) * w;
            weights += w;
        }
    }
    return sum / weights;
}
"""

@Composable
private fun ThemeOptionRow(
    theme: AppTheme,
    definition: ThemeDefinition,
    isDark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val variant = definition.variant(isDark)
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = theme.title
            selected = isSelected
        },
        onClick = onClick,
        role = Role.RadioButton,
    ) {
        ThemeSwatchTile(definition, isDark, ThemeSwatchTile.Size.Regular, cardBackground = OpenCodeTheme.colors.groupedCard)
        Text(
            theme.title,
            style = OpenCodeTextStyles.body.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
            color = OpenCodeTheme.colors.text,
            maxLines = if (isAccessibilitySize()) Int.MAX_VALUE else 1,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            OpenCodeIcon(SFSymbol.CheckmarkCircleFill, contentDescription = null, size = 22.dp, tint = variant.swatchAccent.color)
        } else {
            Box(
                Modifier
                    .size(20.dp)
                    .border(1.5.dp, OpenCodeTheme.colors.tertiaryText.copy(alpha = 0.65f), CircleShape),
            )
        }
    }
}

/** The three overlapping palette dots (canvas, accent, syntax) with card knockout rings (D-110). */
object ThemeSwatchTile {
    enum class Size(val dotSize: Dp, val step: Dp, val frameHeight: Dp) {
        Regular(13.dp, 8.5.dp, 29.dp),
        Compact(10.5.dp, 7.dp, 24.dp),
    }
}

@Composable
fun ThemeSwatchTile(
    definition: ThemeDefinition,
    isDark: Boolean,
    size: ThemeSwatchTile.Size = ThemeSwatchTile.Size.Regular,
    cardBackground: Color = Color.Transparent,
) {
    val variant = definition.variant(isDark)
    val knockout = if (cardBackground == Color.Transparent) variant.swatchCanvas.color else cardBackground
    val dots = listOf(
        Triple(variant.swatchCanvas.color, variant.swatchRing.color.copy(alpha = 0.95f), 1.dp),
        Triple(variant.swatchAccent.color, variant.swatchRing.color.copy(alpha = 0.35f), 0.5.dp),
        Triple(variant.syntaxKeyword.color, variant.swatchRing.color.copy(alpha = 0.35f), 0.5.dp),
    )
    Box(
        Modifier.width(size.dotSize + size.step * 2).height(size.frameHeight).clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        dots.forEachIndexed { index, (fill, ring, ringWidth) ->
            Box(
                Modifier
                    .offset(x = size.step * index - 1.5.dp)
                    .zIndex(index.toFloat())
                    .size(size.dotSize + 3.dp)
                    .clip(CircleShape)
                    .background(knockout),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(size.dotSize)
                        .clip(CircleShape)
                        .background(fill)
                        .border(ringWidth, ring, CircleShape),
                )
            }
        }
    }
}

/**
 * The real transcript rows for the Themes stage (iOS `TranscriptMessageRow`s), provided by a
 * module that sees `:feature:transcript` (Home provides it where it hosts Settings). Without one,
 * [ImitationTranscriptPreview] draws the same slice from the theme's palette.
 */
val LocalAppearanceTranscriptPreview = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * The Themes stage: an actual slice of the session timeline edge to edge (D-109 Split), in the
 * theme's own colours, its type capped at iOS's xLarge, read as one element and inert.
 */
@Composable
private fun AppearanceTranscriptPreview(theme: AppTheme, isDark: Boolean) {
    val rows = LocalAppearanceTranscriptPreview.current
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Transcript preview"
                stateDescription = theme.title
            }
            // iOS `.allowsHitTesting(false)`: the preview takes no touches.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) {
        OpenCodeTheme(theme = theme, appearance = if (isDark) AppearanceColorScheme.Dark else AppearanceColorScheme.Light) {
            // iOS `.dynamicTypeSize(...DynamicTypeSize.xLarge)`.
            CompositionLocalProvider(LocalDensity provides Density(density.density, min(density.fontScale, PreviewMaxFontScale))) {
                if (rows != null) rows() else ImitationTranscriptPreview(theme, isDark)
            }
        }
    }
}

/** iOS xLarge body (19 pt) over Large (17 pt). */
private const val PreviewMaxFontScale = 19f / 17f

/** The palette-drawn stand-in for [LocalAppearanceTranscriptPreview] when no host provides rows. */
@Composable
private fun ImitationTranscriptPreview(theme: AppTheme, isDark: Boolean) {
    val v = rememberThemeDefinition(theme).variant(isDark)
    val mono = FontFamily.Monospace
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = v.userBubble.color,
                shape = RoundedCornerShape(OpenCodeRadius.card),
            ) {
                Text(
                    "Help me improve the session screen. Show me what you find and explain the changes.",
                    style = OpenCodeTextStyles.body,
                    color = v.text.color,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            buildAnnotatedString {
                append("I found the layout issue in ")
                withStyle(SpanStyle(fontFamily = mono, background = v.surfaceStrong.color, fontSize = 15.sp)) {
                    append("SessionView.swift")
                }
                append(". The transcript and composer share the available height, so the keyboard is a useful stress test.")
            },
            style = OpenCodeTextStyles.body,
            color = v.bodyText.color,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OpenCodeRadius.sm))
                .background(v.codeBand.color)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("struct ") }
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("SessionSummary") }
                    append(": ")
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("Equatable") }
                    append(" {\n  ")
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("var ") }
                    append("title: ")
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("String") }
                    append("\n  ")
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("var ") }
                    append("isRunning: ")
                    withStyle(SpanStyle(color = v.syntaxType.color)) { append("Bool") }
                    append("\n  ")
                    withStyle(SpanStyle(color = v.syntaxKeyword.color)) { append("var ") }
                    append("detail = ")
                    withStyle(SpanStyle(color = v.syntaxString.color)) {
                        append("\"A deliberately long line to inspect horizontal scrolling on a narrow phone display\"")
                    }
                    append("\n}")
                },
                style = OpenCodeTextStyles.footnote.copy(fontFamily = mono),
                color = v.text.color,
                softWrap = false,
            )
        }
    }
}
