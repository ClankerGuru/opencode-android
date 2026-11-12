package ai.opencode.feature.drawer.list

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.InboxSidebarFeature
import ai.opencode.feature.drawer.inboxSidebarArchivedRows
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.style.drawerCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * Archived and its count, under Catch Up while the server has archived sessions; a tap opens the
 * Archived page. Lined up with Catch Up: the regular list's shortcut row, or the inbox's.
 */
@Composable
internal fun ArchivedEntryRow(count: Int, regularList: Boolean, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    val start = if (regularList) DrawerMetrics.textInset else InboxSidebarMetrics.edge
    val end = if (regularList) DrawerMetrics.trailingInset else InboxSidebarMetrics.edge
    val font = if (regularList) DrawerTunables.rowFont.font else OpenCodeTextStyles.body
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (regularList) Modifier.padding(horizontal = DrawerMetrics.rowEdge) else Modifier)
            .clip(RoundedCornerShape(if (regularList) DrawerMetrics.rowCornerRadius else 0.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = if (regularList) DrawerMetrics.rowHeight else 48.dp)
            .padding(start = start, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (regularList) 8.dp else 12.dp),
    ) {
        Box(if (regularList) Modifier.width(22.dp) else Modifier, contentAlignment = Alignment.CenterStart) {
            OpenCodeIcon(SFSymbol.Archivebox, contentDescription = null, tint = colors.secondaryText)
        }
        Text("Archived", style = font, color = colors.text, modifier = Modifier.weight(1f))
        Text("$count", style = font.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
    }
}

/**
 * The Archived page over the drawer's list: back, the title and Search Archived (the server
 * searches, as on desktop), then the archived sessions, muted. A tap opens one; a hold, or a tap
 * while selecting, chooses it, and the selection bar ([header]) takes the title's place.
 */
@Composable
internal fun ArchivedPage(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    checkedIDs: Set<String>,
    select: (sessionID: String) -> Unit,
    held: (sessionID: String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val colors = OpenCodeTheme.colors
    val rows by store.observe { it.inboxSidebarArchivedRows() }
    val search by store.observe { it.inboxSidebarStatus.archivedSearch }
    val now = Instant.now()
    Column(modifier.fillMaxSize().background(colors.drawerCanvas).padding(top = contentPadding.calculateTopPadding())) {
        Box(Modifier.fillMaxWidth().padding(horizontal = DrawerMetrics.rowEdge, vertical = 4.dp)) {
            if (header != null) {
                header()
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    OpenCodeIconButton(SFSymbol.ChevronBackward, contentDescription = "Back", onClick = onBack, style = OpenCodeIconButtonStyle.Standard)
                    Text(
                        "Archived",
                        style = DrawerTunables.titleFont.font.copy(fontWeight = FontWeight.Bold),
                        color = colors.text,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            item(key = "archived-search") {
                OpenCodeTextField(
                    value = search,
                    onValueChange = { store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedSearchChanged(it))) },
                    placeholder = "Search Archived",
                    leadingIcon = { OpenCodeIcon(SFSymbol.Magnifyingglass, contentDescription = null, size = 18.dp, tint = colors.secondaryText) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InboxSidebarMetrics.edge, vertical = 8.dp),
                )
            }
            items(rows, key = { it.id }) { row ->
                InboxSidebarSessionRow(
                    row = row,
                    now = now,
                    selected = false,
                    archived = true,
                    checked = if (checkedIDs.isEmpty()) null else row.id in checkedIDs,
                    select = { select(row.id) },
                    held = { held(row.id) },
                )
            }
            if (rows.isEmpty()) {
                item(key = "archived-empty") {
                    Text(
                        if (search.isBlank()) "No Archived Sessions" else "No Results",
                        style = OpenCodeTextStyles.body,
                        color = colors.secondaryText,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InboxSidebarMetrics.edge, vertical = 24.dp),
                    )
                }
            }
        }
    }
}
