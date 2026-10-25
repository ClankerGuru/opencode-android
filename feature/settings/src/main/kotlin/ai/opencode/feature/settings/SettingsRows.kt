package ai.opencode.feature.settings

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Port of iOS `Settings/SettingsRows.swift` plus the inset-grouped `List`/`Section` chrome every
 * Settings page uses, as Material surfaces: a page is a `LazyColumn` of [settingsSection]s, each a
 * rounded card on the grouped canvas with an optional header and footer.
 */

/** iOS system tints for [SettingsIcon] tiles (`Color.purple`, `.blue`, …), light and dark. */
enum class SettingsTint(private val light: Long, private val dark: Long) {
    Red(0xFFFF3B30, 0xFFFF453A),
    Orange(0xFFFF9500, 0xFFFF9F0A),
    Yellow(0xFFFFCC00, 0xFFFFD60A),
    Green(0xFF34C759, 0xFF30D158),
    Mint(0xFF00C7BE, 0xFF63E6E2),
    Teal(0xFF30B0C7, 0xFF40CBE0),
    Cyan(0xFF32ADE6, 0xFF64D2FF),
    Blue(0xFF007AFF, 0xFF0A84FF),
    Indigo(0xFF5856D6, 0xFF5E5CE6),
    Purple(0xFFAF52DE, 0xFFBF5AF2),
    Pink(0xFFFF2D55, 0xFFFF375F),
    Brown(0xFFA2845E, 0xFFAC8E68),
    Gray(0xFF8E8E93, 0xFF8E8E93),
    ;

    fun color(isDark: Boolean): Color = Color(if (isDark) dark else light)

    @Composable
    fun color(): Color = color(OpenCodeTheme.colors.isDark)
}

/** The tinted rounded tile in front of a settings row, as in system Settings (29 pt, radius 7, gradient fill). */
@Composable
fun SettingsIcon(symbol: String, tint: SettingsTint, modifier: Modifier = Modifier) {
    val color = tint.color()
    Box(
        modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f).compositeOver(Color.White, 0.15f), color))),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 18.dp, tint = Color.White)
    }
}

@Composable
fun SettingsIcon(symbol: SFSymbol, tint: SettingsTint, modifier: Modifier = Modifier) =
    SettingsIcon(symbol.sfName, tint, modifier)

private fun Color.compositeOver(other: Color, amount: Float): Color = Color(
    red = red * (1 - amount) + other.red * amount,
    green = green * (1 - amount) + other.green * amount,
    blue = blue * (1 - amount) + other.blue * amount,
    alpha = alpha,
)

/** Minimum row height (iOS 44 pt list rows → the 48 dp target). */
val SettingsRowMinHeight = 52.dp

/**
 * One section of an inset-grouped Settings list: a header (iOS section header, sentence case,
 * secondary), the rows on a rounded card separated by inset dividers, and a footer.
 */
fun LazyListScope.settingsSection(
    key: Any,
    header: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    item(key = key, contentType = "settings-section") {
        SettingsSection(header = header, footer = footer, rows = rows)
    }
}

/** Plain-text footer convenience for [settingsSection]. */
fun footerText(text: String): @Composable ColumnScope.() -> Unit = { SettingsFooterText(text) }

@Composable
fun SettingsFooterText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = OpenCodeTextStyles.footnote,
        color = OpenCodeTheme.colors.secondaryText,
        modifier = modifier,
    )
}

@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(
                header,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }
        Surface(
            color = OpenCodeTheme.colors.groupedCard,
            shape = RoundedCornerShape(OpenCodeRadius.card),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = rows)
        }
        if (footer != null) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = footer,
            )
        }
    }
}

/** The inset divider between rows on a section card. */
@Composable
fun SettingsDivider(inset: Boolean = false) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 62.dp else 16.dp),
        color = OpenCodeTheme.colors.border,
        thickness = with(LocalDensity.current) { 1f.toDp() },
    )
}

/** A row's frame: full-width target, 16 dp sides, at least [SettingsRowMinHeight] tall. */
@Composable
fun SettingsRowFrame(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    content: @Composable RowScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = SettingsRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * iOS `SettingsRow`: a tinted symbol tile, the title, and a quiet value; with [onClick] it is a
 * navigation row and ends in a chevron (iOS `NavigationLink`).
 */
@Composable
fun SettingsRow(
    title: String,
    symbol: String?,
    tint: SettingsTint = SettingsTint.Gray,
    value: String? = null,
    modifier: Modifier = Modifier,
    showsChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SettingsRowFrame(modifier, onClick = onClick) {
        if (symbol != null) SettingsIcon(symbol, tint)
        Text(title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = OpenCodeTextStyles.body,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null && showsChevron) SettingsChevron()
    }
}

/** The trailing disclosure chevron (tertiary, decorative). */
@Composable
fun SettingsChevron() {
    OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.tertiaryText)
}

/** iOS `LabeledContent(title, value:)`: a non-interactive title/value row. */
@Composable
fun SettingsValueRow(title: String, value: String, modifier: Modifier = Modifier) {
    SettingsRowFrame(modifier.semantics(mergeDescendants = true) {}) {
        Text(title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
        Text(value, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * iOS `Toggle` in a Settings list, optionally with a [SettingsIcon] and a one-line [summary] (the
 * Experimental rows). The whole row toggles.
 */
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    tint: SettingsTint = SettingsTint.Gray,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingsRowFrame(
        modifier.semantics(mergeDescendants = true) {
            toggleableState = ToggleableState(checked)
        },
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        role = Role.Switch,
    ) {
        if (symbol != null) SettingsIcon(symbol, tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
            if (summary != null) {
                Text(
                    summary,
                    style = OpenCodeTextStyles.footnote,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A plain button row (iOS `Button` in a List): accent text, or red for destructive. */
@Composable
fun SettingsButtonRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: SFSymbol? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> OpenCodeTheme.colors.tertiaryText
        destructive -> OpenCodeTheme.colors.destructive
        else -> MaterialTheme.colorScheme.primary
    }
    SettingsRowFrame(modifier, onClick = onClick, enabled = enabled) {
        if (symbol != null) OpenCodeIcon(symbol, contentDescription = null, size = 22.dp, tint = color)
        Text(title, style = OpenCodeTextStyles.body, color = color, modifier = Modifier.weight(1f))
    }
}

/** One option of a [SettingsMenuRow]: its value, title and optional caption. */
data class SettingsMenuOption<T>(val value: T, val title: String, val caption: String? = null)

/**
 * iOS `SettingsMenuRow`: a choice in a Settings list, as native Settings shows one: the title
 * (with an icon and a caption when given), then the value in the secondary colour with an
 * up-and-down chevron. The whole row opens the menu (a Material dropdown with checkmarks).
 * [sections] groups options under headers (Voice › Model's provider groups); a single unnamed
 * section is a flat list.
 */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    value: String,
    selection: T,
    sections: List<Pair<String?, List<SettingsMenuOption<T>>>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    symbol: String? = null,
    tint: SettingsTint = SettingsTint.Gray,
    accessibilityLabel: String = title,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        SettingsRowFrame(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = value
            },
            onClick = { expanded = true },
        ) {
            if (symbol != null) SettingsIcon(symbol, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
                if (caption != null) {
                    Text(
                        caption,
                        style = OpenCodeTextStyles.footnote,
                        color = OpenCodeTheme.colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    style = OpenCodeTextStyles.body,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                OpenCodeIcon(SFSymbol.ChevronUpChevronDown, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            sections.forEachIndexed { index, (header, options) ->
                if (index > 0) HorizontalDivider()
                if (header != null) {
                    Text(
                        header,
                        style = OpenCodeTextStyles.footnote,
                        color = OpenCodeTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.title)
                                if (option.caption != null) {
                                    Text(option.caption, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                                }
                            }
                        },
                        leadingIcon = {
                            Box(Modifier.width(20.dp)) {
                                if (option.value == selection) {
                                    OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.value)
                        },
                    )
                }
            }
        }
    }
}

/** Flat-list overload of [SettingsMenuRow]. */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    value: String,
    selection: T,
    options: List<SettingsMenuOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    symbol: String? = null,
    tint: SettingsTint = SettingsTint.Gray,
) = SettingsMenuRow(title, value, selection, listOf(null to options), onSelect, modifier, caption, symbol, tint)

/** Leading spacer matching a row with an icon, for rows that line up under iconed rows. */
@Composable
fun RowScope.SettingsIconSpacer() = Spacer(Modifier.width(30.dp))
