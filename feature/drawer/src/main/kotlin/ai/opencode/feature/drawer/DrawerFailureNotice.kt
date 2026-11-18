package ai.opencode.feature.drawer

import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An archive, unarchive or delete the server refused, shown above New Session for a few seconds. */
data class DrawerFailure(
    val action: Action,
    /** The session that failed last. */
    val sessionTitle: String,
    /** Why it failed. */
    val detail: String,
    /** How many sessions failed this way while the notice showed. */
    val count: Int = 1,
) {
    enum class Action { archive, unarchive, delete }

    val title: String
        get() = when (action) {
            Action.archive ->
                if (count == 1) localized(R.string.drawer_archive_error, sessionTitle)
                else localizedPlural(R.plurals.drawer_archive_sessions_error, count, count)
            Action.unarchive ->
                if (count == 1) localized(R.string.drawer_unarchive_error, sessionTitle)
                else localizedPlural(R.plurals.drawer_unarchive_sessions_error, count, count)
            Action.delete ->
                if (count == 1) localized(R.string.drawer_delete_session_error, sessionTitle)
                else localizedPlural(R.plurals.drawer_delete_sessions_error, count, count)
        }

    /** Counts this failure with [shown] when it failed the same way, so a selection gets one notice. */
    fun joining(shown: DrawerFailure?): DrawerFailure {
        if (shown == null || shown.action != action || shown.detail != detail) return this
        return copy(count = shown.count + 1)
    }
}

/** The connection pill's floating chip, saying what failed and why. */
@Composable
internal fun DrawerFailureNotice(failure: DrawerFailure, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    Surface(
        shape = OpenCodeShape.control,
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        shadowElevation = OpenCodeSurfaces.raisedShadow,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("drawer-failure-notice"),
    ) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon(
                SFSymbol.ExclamationmarkCircleFill,
                contentDescription = null,
                size = (18 * fontScale).dp,
                tint = OpenCodeTheme.colors.destructive,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    failure.title,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodeTheme.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    failure.detail,
                    style = OpenCodeTextStyles.caption1,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
