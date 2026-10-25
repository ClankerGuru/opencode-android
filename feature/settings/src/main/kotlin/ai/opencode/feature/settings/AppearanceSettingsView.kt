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
import ai.opencode.core.ui.ThemePalette
import ai.opencode.core.ui.ThemeSharedKeys
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
                Text("Theme", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                ThemeSwatchTile(
                    ThemePalette.definition(appTheme),
                    isDark = isDark,
                    size = ThemeSwatchTile.Size.Compact,
                    cardBackground = OpenCodeTheme.colors.groupedCard,
                )
                Text(appTheme.title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
                SettingsChevron()
            }
        }
    }
}

/**
 * Settings → Appearance → Themes (D-109 Split + D-110 Palette Dots): the top is a session preview
 * edge to edge in the theme's own canvas, and the curated theme list scrolls independently below,
 * dissolving under a soft edge (iOS `ThemesSoftEdgeBackdrop`: feather 22 pt; Android draws the
 * smoothstep gradient without the progressive blur, opaque under high contrast).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemesSettingsView(store: SettingsStore) {
    val appTheme by remember { shared(ThemeSharedKeys.appTheme).flow }.collectAsState()
    val scheme by remember { shared(ThemeSharedKeys.appearanceColorScheme).flow }.collectAsState()
    val isDark = scheme.resolveIsDark(isSystemInDarkTheme())
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val canvas = ThemePalette.definition(appTheme).variant(isDark).canvas.color

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
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Box(Modifier.fillMaxWidth().background(canvas)) {
                Crossfade(appTheme to isDark, animationSpec = tween(if (reduceMotion) 0 else 220), label = "theme-preview") { (theme, dark) ->
                    AppearanceTranscriptPreview(theme, dark)
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    contentPadding = PaddingValues(top = SoftEdgeHeight + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    settingsSection(key = "themes") {
                        AppTheme.entries.forEachIndexed { index, theme ->
                            if (index > 0) SettingsDivider(inset = true)
                            ThemeOptionRow(
                                theme = theme,
                                definition = ThemePalette.definition(theme),
                                isDark = isDark,
                                isSelected = appTheme == theme,
                                onClick = { store.send(SettingsFeature.Action.Settings(HomeSettingsFeature.Action.AppThemeChanged(theme))) },
                            )
                        }
                    }
                }
                // Soft scroll edge: the preview's canvas eases into the grouped canvas (smoothstep).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SoftEdgeHeight)
                        .background(
                            Brush.verticalGradient(
                                (0..8).map { step ->
                                    val t = step / 8f
                                    val ease = 1 - t * t * (3 - 2 * t)
                                    lerpColor(colors.groupedCanvas, canvas, ease)
                                },
                            ),
                        ),
                )
            }
        }
    }
}

private val SoftEdgeHeight = 22.dp

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

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
            maxLines = 1,
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
 * The Themes stage: a slice of a session in the theme's own colours (D-109 Split). iOS renders
 * real `TranscriptMessageRow`s; `:feature:settings` sits beside `:feature:transcript`, not above
 * it, so this draws the same user bubble, Markdown reply with inline code and a scrolling code
 * band from the theme's palette.
 */
@Composable
private fun AppearanceTranscriptPreview(theme: AppTheme, isDark: Boolean) {
    val v = ThemePalette.definition(theme).variant(isDark)
    val mono = FontFamily.Monospace
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 2.dp, bottom = 14.dp)
            .clearAndSetSemantics {
                contentDescription = "Transcript preview"
                stateDescription = theme.title
            },
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f, fill = false).width(48.dp))
            Surface(
                color = v.userBubble.color,
                shape = RoundedCornerShape(OpenCodeRadius.card),
                modifier = Modifier.weight(1f, fill = false),
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
