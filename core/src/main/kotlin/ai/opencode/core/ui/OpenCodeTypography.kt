package ai.opencode.core.ui

import ai.opencode.core.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Font families. This branch uses the OpenCode desktop's fonts: Inter (variable) for UI text and
 * IBM Plex Mono for code and tool output (`packages/ui` `--font-family-sans` / `-mono`). Both are
 * OFL-1.1 (`docs/licenses/`).
 */
object OpenCodeFonts {
    /**
     * Inter at the desktop's weights: Normal → 440 (`--font-weight-regular`), Medium → 530
     * (`--font-weight-medium`), SemiBold → 600, Bold → 670 (`--font-weight-bold`).
     */
    @OptIn(ExperimentalTextApi::class)
    val system: FontFamily = FontFamily(
        listOf(
            FontWeight.Light to 340,
            FontWeight.Normal to 440,
            FontWeight.Medium to 530,
            FontWeight.SemiBold to 600,
            FontWeight.Bold to 670,
            FontWeight.ExtraBold to 760,
        ).map { (weight, axis) ->
            Font(
                R.font.inter,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
            )
        },
    )

    /** IBM Plex Mono for code, tool output and paths (desktop `--font-family-mono`). */
    val monospace: FontFamily = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
        Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
        Font(R.font.plex_mono_semibold, FontWeight.Bold),
    )

    /**
     * Berkeley Mono (variable), only for onboarding's brand marks, exactly as iOS restricts
     * `BerkeleyMonoVariable` (the `opencode` wordmark, `opencode pair --remote`).
     */
    @OptIn(ExperimentalTextApi::class)
    val berkeleyMono: FontFamily = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(
                R.font.berkeley_mono,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )
}

/**
 * The text styles under iOS's Dynamic Type names (so ported views still say `.footnote`), sized
 * to the OpenCode desktop scale. sp follows the system font scale like Dynamic Type.
 *
 * Use these by name when porting a view that says `.font(.footnote)`; Material components
 * pick them up through [OpenCodeTypography]'s role mapping.
 */
object OpenCodeTextStyles {
    private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
        fontFamily = OpenCodeFonts.system,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = if (tracking == 0.0) TextUnit.Unspecified else tracking.sp,
    )

    // The desktop scale (13/14/16/20 px at weights 440/530/670) stretched for a phone held
    // closer than a monitor: body text one step up (15 sp), titles medium-weight and tightly
    // tracked like the desktop's `text-16/20-medium`. Everything stays in sp, so font scaling works.

    /** `.largeTitle`: 28/34 medium. */
    val largeTitle = style(28, 34, FontWeight.Medium, -0.4)
    /** `.title` (title1): 24/30 medium. */
    val title1 = style(24, 30, FontWeight.Medium, -0.32)
    /** `.title2`: 20/26 medium (desktop `text-20-medium`). */
    val title2 = style(20, 26, FontWeight.Medium, -0.32)
    /** `.title3`: 18/24 medium. */
    val title3 = style(18, 24, FontWeight.Medium, -0.16)
    /** `.headline`: 16/22 medium (desktop `text-16-medium`). */
    val headline = style(16, 22, FontWeight.Medium, -0.16)
    /** `.body`: 15/23 (desktop markdown 14 px / 160 %). */
    val body = style(15, 23)
    /** `.callout`: 15/21. */
    val callout = style(15, 21)
    /** `.subheadline`: 14/20 (desktop `text-14`). */
    val subheadline = style(14, 20)
    /** `.footnote`: 13/18 (desktop `text-12`, 13 px). */
    val footnote = style(13, 18)
    /** `.caption` (caption1): 12/16. */
    val caption1 = style(12, 16)
    /** `.caption2`: 11/13. */
    val caption2 = style(11, 13)
}

/**
 * Material 3 type roles mapped onto iOS text styles:
 *
 * | M3 role | iOS style | Typical use |
 * | --- | --- | --- |
 * | displayLarge, displayMedium | largeTitle 34 | large screen titles |
 * | displaySmall, headlineLarge | title1 28 | |
 * | headlineMedium, titleLarge* | title2 22 / title3 20 | *titleLarge = title3: top app bar titles |
 * | headlineSmall | title3 20 | section and sheet titles |
 * | titleMedium | headline 17 semibold | inline nav titles, list row titles that are emphasised |
 * | titleSmall | subheadline 15 semibold | small group headers |
 * | bodyLarge | body 17 | primary text, transcript prose, list row titles |
 * | bodyMedium | subheadline 15 | secondary text, list supporting text, RollingNumber default |
 * | bodySmall | footnote 13 | footers, timestamps |
 * | labelLarge | body 17 semibold | buttons (iOS `.controlSize(.large)` label) |
 * | labelMedium | caption1 12 medium | chips, badges |
 * | labelSmall | caption2 11 medium | tiny badges |
 */
val OpenCodeTypography: Typography = with(OpenCodeTextStyles) {
    Typography(
        displayLarge = largeTitle,
        displayMedium = largeTitle,
        displaySmall = title1,
        headlineLarge = title1,
        headlineMedium = title2,
        headlineSmall = title3,
        titleLarge = title3,
        titleMedium = headline,
        titleSmall = subheadline.copy(fontWeight = FontWeight.Medium),
        bodyLarge = body,
        bodyMedium = subheadline,
        bodySmall = footnote,
        labelLarge = subheadline.copy(fontWeight = FontWeight.Medium),
        labelMedium = caption1.copy(fontWeight = FontWeight.Medium),
        labelSmall = caption2.copy(fontWeight = FontWeight.Medium),
    )
}
