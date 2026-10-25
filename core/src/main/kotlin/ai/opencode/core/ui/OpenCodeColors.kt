package ai.opencode.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * The app's semantic colours for one (theme, light/dark, contrast) state: a port of iOS
 * `ThemePalette`'s dynamic colour providers (`canvas`, `groupedCard`, `text`, `codeBand`, …)
 * including their Increase Contrast and System-theme rules.
 *
 * Read with `OpenCodeTheme.colors` inside [OpenCodeTheme]. Colours with a Material role are
 * also in `MaterialTheme.colorScheme` (see [toColorScheme] for the mapping); the transcript,
 * syntax, diff and Send-button colours exist only here.
 *
 * Android's Increase Contrast equivalent is the system contrast level (Settings →
 * Accessibility → Colour and motion → Contrast, `UiModeManager.getContrast() >= 0.5`).
 */
@Immutable
class OpenCodeColors(
    val theme: AppTheme,
    val isDark: Boolean,
    val highContrast: Boolean,
    /** The raw palette, for swatches (`swatchCanvas`, `swatchRing`, `swatchAccent`) and contrast checks. */
    val variant: ThemeVariant,
    /** Plain backgrounds (iOS `systemBackground`). */
    val canvas: Color,
    /** Grouped lists' background, Settings (iOS `systemGroupedBackground`). */
    val groupedCanvas: Color,
    /** A card or row group on [groupedCanvas] (iOS `secondarySystemGroupedBackground`). */
    val groupedCard: Color,
    /** Code block band behind code. */
    val codeBand: Color,
    /** The user's message bubble (iOS `secondarySystemBackground`). */
    val userBubble: Color,
    /** Inline `code` fill (iOS `tertiarySystemFill`). */
    val inlineCodeFill: Color,
    /** Primary text (iOS `label`). */
    val text: Color,
    /** Strongest text: titles, emphasis. */
    val strongText: Color,
    /** Secondary text (iOS `secondaryLabel`). */
    val secondaryText: Color,
    /** Tertiary text (iOS `tertiaryLabel`). */
    val tertiaryText: Color,
    /** Default transcript prose ([bodyText] at tone 0.92). */
    val defaultBodyText: Color,
    /** Separators and hairlines (iOS `separator`). */
    val border: Color,
    /** Tint for controls and selection (iOS `tintColor`). */
    val accent: Color,
    val sendButtonBackground: Color,
    val sendButtonForeground: Color,
    val link: Color,
    val syntaxKeyword: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxComment: Color,
    val syntaxType: Color,
    val diffAddedFill: Color,
    val diffRemovedFill: Color,
    val diffAddedText: Color,
    val diffRemovedText: Color,
    /** Destructive roles only (iOS `systemRed`: delete, end call). */
    val destructive: Color,
    private val bodyTextResolver: (Double) -> Color,
) {
    /**
     * Prose at [tone] (`0..1`) between canvas and text, mixed in OKLab (System theme: the
     * label colour at alpha [tone]). `tone >= 0.999` is [strongText]; Increase Contrast
     * returns full text (iOS `ThemePalette.bodyText(tone:)`).
     */
    fun bodyText(tone: Double): Color = when {
        tone >= 0.999 -> strongText
        abs(tone - 0.92) < 0.0005 -> defaultBodyText
        else -> bodyTextResolver(tone)
    }

    companion object {
        /**
         * Resolves every semantic colour exactly as iOS's dynamic providers do for [theme],
         * [isDark] and [highContrast]. For [AppTheme.System], iOS returns UIKit system colours;
         * this uses their concrete values (the [ThemePalette.system] reference palette plus the
         * UIKit-only fills below).
         */
        fun resolve(theme: AppTheme, isDark: Boolean, highContrast: Boolean = false): OpenCodeColors {
            val v = ThemePalette.definition(theme).variant(isDark)
            val system = theme == AppTheme.System
            val label = v.text
            val text = if (!system && highContrast) v.textStrong else v.text
            val bodyResolver: (Double) -> Color = { tone ->
                when {
                    system -> if (highContrast || tone >= 0.999) label.color else label.withAlpha(tone).color
                    highContrast || tone >= 0.999 -> (if (highContrast) v.textStrong else v.text).color
                    abs(tone - 0.92) < 0.0005 -> v.bodyText.color
                    else -> v.canvas.mixedInOklab(v.text, tone).color
                }
            }
            return OpenCodeColors(
                theme = theme,
                isDark = isDark,
                highContrast = highContrast,
                variant = v,
                canvas = v.canvas.color,
                groupedCanvas = (if (isDark) v.canvas else v.canvasWeak).color,
                groupedCard = when {
                    !isDark -> v.canvas
                    !system && highContrast -> v.surfaceStrong
                    else -> v.userBubble
                }.color,
                codeBand = if (system) label.withAlpha(if (isDark) 0.035 else 0.025).color else v.codeBand.color,
                userBubble = (if (!system && highContrast) v.surfaceStrong else v.userBubble).color,
                inlineCodeFill = if (system) SystemColors.tertiarySystemFill(isDark).color else v.surfaceStrong.color,
                text = text.color,
                strongText = (if (system) label else v.textStrong).color,
                secondaryText = (if (!system && highContrast) v.text else v.textWeak).color,
                tertiaryText = (if (!system && highContrast) v.textWeak else v.textTertiary).color,
                defaultBodyText = bodyResolver(0.92),
                border = (if (!system && highContrast) v.borderStrong else v.border).color,
                accent = v.accent.color,
                sendButtonBackground = (if (system) label else v.accent).color,
                sendButtonForeground = (if (system) v.canvas else v.accent.highContrastForeground).color,
                link = v.link.color,
                syntaxKeyword = v.syntaxKeyword.color,
                syntaxString = v.syntaxString.color,
                syntaxNumber = v.syntaxNumber.color,
                syntaxComment = v.syntaxComment.color,
                syntaxType = v.syntaxType.color,
                diffAddedFill = (if (system) SystemColors.green(isDark).withAlpha(0.16) else v.glassDiffAddedFill).color,
                diffRemovedFill = (if (system) SystemColors.red(isDark).withAlpha(0.14) else v.glassDiffRemovedFill).color,
                diffAddedText = (if (system) label else v.diffAddedText).color,
                diffRemovedText = (if (system) label else v.diffRemovedText).color,
                destructive = SystemColors.red(isDark).color,
                bodyTextResolver = bodyResolver,
            )
        }
    }
}

/** UIKit system colours the iOS System theme reads that have no field in [ThemeVariant]. */
internal object SystemColors {
    fun red(isDark: Boolean) = if (isDark) ThemeRGBA(1.0, 0.2706, 0.2275) else ThemeRGBA(1.0, 0.2314, 0.1882)
    fun green(isDark: Boolean) = if (isDark) ThemeRGBA(0.1882, 0.8196, 0.3451) else ThemeRGBA(0.2039, 0.7804, 0.349)

    /** `UIColor.tertiarySystemFill`: rgba(118,118,128) at 0.12 light / 0.24 dark. */
    fun tertiarySystemFill(isDark: Boolean) = ThemeRGBA(0.4627, 0.4627, 0.502, if (isDark) 0.24 else 0.12)
}

/**
 * Maps the iOS semantic colours onto Material 3 roles. Translucent iOS colours (secondary
 * text, separators) are composited over the canvas, because Material derives its own
 * alpha states (disabled, pressed) from these roles.
 *
 * | M3 role | iOS colour |
 * | --- | --- |
 * | primary / onPrimary | accent / white (System: UIKit tint) or the accent's high-contrast foreground (themes) |
 * | primaryContainer / onPrimaryContainer | canvas → accent 22 % (OKLab) / strong text |
 * | secondary / onSecondary | secondary text / canvas |
 * | secondaryContainer / onSecondaryContainer | surfaceStrong (secondary buttons) / text |
 * | tertiary / onTertiary | link / white (System) or the link's high-contrast foreground (themes) |
 * | background, surface, surfaceContainerLowest | canvas |
 * | surfaceContainerLow | canvasWeak (grouped canvas, light) |
 * | surfaceContainer | canvasWeak ↔ userBubble midpoint |
 * | surfaceContainerHigh, surfaceVariant | userBubble |
 * | surfaceContainerHighest | surfaceStrong |
 * | onSurface / onSurfaceVariant | text / secondary text |
 * | outline / outlineVariant | borderStrong / border |
 * | error / onError | destructive red (System) or diffRemovedText (themes) / white (System) or its high-contrast foreground |
 * | errorContainer / onErrorContainer | removed-diff fill over canvas / text |
 */
fun OpenCodeColors.toColorScheme(): ColorScheme {
    val v = variant
    val canvas = v.canvas
    val system = theme == AppTheme.System
    val accent = v.accent
    val strong = if (system) v.text else v.textStrong
    val textRgba = if (!system && highContrast) v.textStrong else v.text
    val secondary = (if (!system && highContrast) v.text else v.textWeak).composited(canvas)
    val outline = v.borderStrong.composited(canvas)
    val outlineVariant = (if (!system && highContrast) v.borderStrong else v.border).composited(canvas)
    val error = if (system) SystemColors.red(isDark) else v.diffRemovedText
    val container = v.canvasWeak.mixedInOklab(v.userBubble, 0.5)
    val primaryContainer = canvas.mixedInOklab(accent, 0.22)
    val errorContainer = (if (system) SystemColors.red(isDark).withAlpha(0.14) else v.glassDiffRemovedFill).composited(canvas)
    val surfaceBright = if (isDark) v.surfaceStrong else canvas
    val surfaceDim = if (isDark) canvas else v.canvasWeak
    // UIKit's system tints (systemBlue, systemRed) are designed for white labels: iOS draws its
    // tint-filled and destructive controls with white text in light and dark. The luminance pick
    // ([highContrastForeground]) chooses near-black over systemBlue/systemRed, so it only
    // applies to theme palettes (iOS `sendButtonForeground` over a theme accent).
    val white = ThemeRGBA(1.0, 1.0, 1.0)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent.color,
        onPrimary = (if (system) white else accent.highContrastForeground).color,
        primaryContainer = primaryContainer.color,
        onPrimaryContainer = strong.color,
        inversePrimary = accent.mixedInOklab(canvas.highContrastForeground, 0.35).color,
        secondary = secondary.color,
        onSecondary = canvas.color,
        secondaryContainer = v.surfaceStrong.color,
        onSecondaryContainer = textRgba.color,
        tertiary = v.link.color,
        onTertiary = (if (system) white else v.link.highContrastForeground).color,
        tertiaryContainer = canvas.mixedInOklab(v.link, 0.22).color,
        onTertiaryContainer = strong.color,
        background = canvas.color,
        onBackground = textRgba.color,
        surface = canvas.color,
        onSurface = textRgba.color,
        surfaceVariant = v.userBubble.color,
        onSurfaceVariant = secondary.color,
        surfaceTint = accent.color,
        inverseSurface = v.text.color,
        inverseOnSurface = canvas.color,
        error = error.color,
        onError = (if (system) white else error.highContrastForeground).color,
        errorContainer = errorContainer.color,
        onErrorContainer = textRgba.color,
        outline = outline.color,
        outlineVariant = outlineVariant.color,
        surfaceBright = surfaceBright.color,
        surfaceDim = surfaceDim.color,
        surfaceContainer = container.color,
        surfaceContainerHigh = v.userBubble.color,
        surfaceContainerHighest = v.surfaceStrong.color,
        surfaceContainerLow = v.canvasWeak.color,
        surfaceContainerLowest = canvas.color,
        scrim = Color.Black,
    )
}

/** The current [OpenCodeColors]; provided by [OpenCodeTheme]. Read via `OpenCodeTheme.colors`. */
val LocalOpenCodeColors = staticCompositionLocalOf { OpenCodeColors.resolve(AppTheme.System, isDark = false) }

/** Crisp dark or white foreground over this colour (iOS `ThemeRGBA.highContrastForeground`). */
fun Color.highContrastForeground(): Color =
    ThemeRGBA(red.toDouble(), green.toDouble(), blue.toDouble()).highContrastForeground.color
