package ai.opencode.core.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Corner radii the iOS app uses (surveyed from its `cornerRadius:` / `.rect(cornerRadius:)`
 * call sites). iOS draws `.continuous` (squircle) corners; Compose's rounded corners are
 * circular, which reads the same at these sizes.
 */
object OpenCodeRadius {
    /** Tiny marks: inline code, checkbox insets (6). */
    val xs: Dp = 6.dp
    /** Small fills and thumbnails (10). */
    val sm: Dp = 10.dp
    /** Tertiary-fill fields and rows (`tertiarySystemFill` boxes, 14). */
    val fill: Dp = 14.dp
    /** Inline controls and trays (16). */
    val control: Dp = 16.dp
    /** Cards and glass cards (server list, error report, 20). */
    val card: Dp = 20.dp
    /** Attachment chips, clipped media, settings tiles (22). */
    val tile: Dp = 22.dp
    /** Large panels: connect, server details, What's New cards (26). */
    val panel: Dp = 26.dp
    /** Sheets, scanner frame, model list, voice panel (28). */
    val sheet: Dp = 28.dp
}

/** Shapes for the iOS forms: capsules and circles stay capsules and circles. */
object OpenCodeShape {
    val capsule = RoundedCornerShape(percent = 50)
    val circle = CircleShape
    val card = RoundedCornerShape(OpenCodeRadius.card)
    val panel = RoundedCornerShape(OpenCodeRadius.panel)
    val sheet = RoundedCornerShape(OpenCodeRadius.sheet)
    val fill = RoundedCornerShape(OpenCodeRadius.fill)

    /** Top-only rounding for bottom sheets. */
    val sheetTop = RoundedCornerShape(topStart = OpenCodeRadius.sheet, topEnd = OpenCodeRadius.sheet)
}

/**
 * Material shape scale from the iOS radii: extraSmall 6, small 10, medium 14, large 20,
 * extraLarge 28. Material components read these (menus small, cards medium, dialogs and
 * sheets extraLarge).
 */
val OpenCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(OpenCodeRadius.xs),
    small = RoundedCornerShape(OpenCodeRadius.sm),
    medium = RoundedCornerShape(OpenCodeRadius.fill),
    large = RoundedCornerShape(OpenCodeRadius.card),
    extraLarge = RoundedCornerShape(OpenCodeRadius.sheet),
)

/**
 * Port of `GlassGeometry` (`Glass.swift`): a rounded rectangle's radius must be at most half its
 * height. Material shapes clamp on their own, but animated shapes computed by hand (morphs,
 * growing panels) should clamp with this so every intermediate frame stays a valid
 * capsule-or-less (docs/native-ui-guidance.md). iOS removed the unused `GlassShape.clamped`
 * wrapper in 18771799.
 */
object OpenCodeShapeGeometry {
    fun radius(radius: Float, height: Float): Float = min(max(0f, radius), max(0f, height) / 2)
}

/**
 * Spacing values the iOS app uses (its common `spacing:` and `.padding` values), in dp.
 * Prefer these to literals so layouts stay on the same grid as iOS.
 */
object OpenCodeSpacing {
    val s1: Dp = 1.dp
    val s2: Dp = 2.dp
    val s4: Dp = 4.dp
    val s6: Dp = 6.dp
    val s8: Dp = 8.dp
    val s10: Dp = 10.dp
    val s12: Dp = 12.dp
    val s14: Dp = 14.dp
    val s16: Dp = 16.dp
    val s18: Dp = 18.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    /** Screen and list-row horizontal inset (iOS `.padding(.horizontal, 16)`). */
    val screenHorizontal: Dp = s16

    /** Default stack spacing between related items (iOS `spacing: 8`). */
    val stack: Dp = s8

    /** Between groups/sections (iOS `spacing: 12`). */
    val group: Dp = s12
}
