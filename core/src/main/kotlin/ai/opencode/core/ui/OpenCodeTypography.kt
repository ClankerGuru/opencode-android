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

/** Font families. Everything is the system sans (Roboto / the device font); iOS uses SF. */
object OpenCodeFonts {
    /** The platform sans-serif, the Android counterpart of SF Pro. */
    val system: FontFamily = FontFamily.Default

    /** Monospace for code (iOS: SF Mono via `.monospaced()`). */
    val monospace: FontFamily = FontFamily.Monospace

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
 * iOS Dynamic Type text styles at the default size (Large), in sp. iOS points and Android
 * dp are within 2 % physically (163 vs 160 per inch), so the sizes port 1:1 and keep iOS's
 * relative scale; sp also follows the system font scale like Dynamic Type.
 *
 * Use these by name when porting a view that says `.font(.footnote)`; Material components
 * pick them up through [OpenCodeTypography]'s role mapping.
 */
object OpenCodeTextStyles {
    private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = OpenCodeFonts.system,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = TextUnit.Unspecified,
    )

    /** `.largeTitle` 34/41. */
    val largeTitle = style(34, 41)
    /** `.title` (title1) 28/34. */
    val title1 = style(28, 34)
    /** `.title2` 22/28. */
    val title2 = style(22, 28)
    /** `.title3` 20/25. */
    val title3 = style(20, 25)
    /** `.headline` 17/22 semibold. */
    val headline = style(17, 22, FontWeight.SemiBold)
    /** `.body` 17/22. */
    val body = style(17, 22)
    /** `.callout` 16/21. */
    val callout = style(16, 21)
    /** `.subheadline` 15/20. */
    val subheadline = style(15, 20)
    /** `.footnote` 13/18. */
    val footnote = style(13, 18)
    /** `.caption` (caption1) 12/16. */
    val caption1 = style(12, 16)
    /** `.caption2` 11/13. */
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
        titleSmall = subheadline.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = body,
        bodyMedium = subheadline,
        bodySmall = footnote,
        labelLarge = body.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = caption1.copy(fontWeight = FontWeight.Medium),
        labelSmall = caption2.copy(fontWeight = FontWeight.Medium),
    )
}
