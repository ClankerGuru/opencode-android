package ai.opencode.feature.drawer.switcher

import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName

// Port of iOS `OpenCode/Drawer/ServerSwitcher.swift`, the rules half. The composable
// (`DrawerTitle(...)`, its pull-down menu and the Title Is the Switch button) lives in
// `switcher/ServerSwitcherView.kt` and reads everything it shows from [DrawerTitle.Presentation]
// and the texts below. `OpenCodeServer.friendlyName` was moved down to `:server`
// (`OpenCodeServerFriendlyName.kt`): the title shows a server only by it, never by an address.

/**
 * Saved servers, most recent first, with the one in use even if it was never saved, and its live
 * copy (a rename shows at once).
 */
fun switchableServers(current: OpenCodeServer, saved: List<OpenCodeServer>): List<OpenCodeServer> {
    if (saved.any { it.persistenceID == current.persistenceID }) {
        return saved.map { if (it.persistenceID == current.persistenceID) current else it }
    }
    return listOf(current) + saved
}

/**
 * The drawer's title: always the server in use by its friendly name, never an address. With one
 * saved server it's just the name; with more, a chevron opens a menu of the servers (the current
 * one checked) and Manage Servers…. No status shows while all is well. A confirmed outage dims it
 * (a reconnect usually settles in a second, so it earns nothing), and its menu, which one server
 * gains only then, offers Try Again. Dimming and the one-server menu coming and going are both
 * cross-fades (0.25 s ease-in-out, [CROSS_FADE_MILLIS]), with or without reduced motion.
 */
object DrawerTitle {
    /** iOS `.animation(.easeInOut(duration: 0.25), value: presentation)`. */
    const val CROSS_FADE_MILLIS = 250

    /** iOS `@ScaledMetric(relativeTo: .title)` chevron disc and glyph of Title Is the Switch, in points (dp). */
    const val CHEVRON_DISC = 22
    const val CHEVRON_GLYPH = 10

    /** The title's minimum height (iOS 44 pt; Android's touch target floor is 48 dp). */
    const val MIN_HEIGHT = 44

    /** The test tag / iOS accessibility identifier. */
    const val TAG = "drawer-title"

    const val TRY_AGAIN = "Try Again"
    const val MANAGE_SERVERS = "Manage Servers…"
    const val OFFLINE = "Offline"
    const val TITLE_SWITCH_HINT = "Shows your servers"

    /** "Connecting to <name>…": the title while another server is being connected to. */
    fun connecting(name: String): String = "Connecting to $name…"

    /** What the title (or the menu's label) reads: the server switched to while connecting, else the current one. */
    fun titleText(current: OpenCodeServer, switchingTo: OpenCodeServer?): String =
        switchingTo?.let { connecting(it.friendlyName) } ?: current.friendlyName

    /**
     * Title Is the Switch's line under the name: connecting to another server, else Offline, else
     * nothing.
     */
    fun titleSwitchCaption(status: HomeConnectionFeature.State.ConnectionStatus, switchingTo: OpenCodeServer?): String? = when {
        switchingTo != null -> connecting(switchingTo.friendlyName)
        status == HomeConnectionFeature.State.ConnectionStatus.disconnected -> OFFLINE
        else -> null
    }

    /** What the title shows for a server count and connection status. */
    data class Presentation(
        /** A confirmed outage of the server in use; not a reconnect or a switch. */
        val isDimmed: Boolean,
        /** A pull-down: with several servers always, with one only while offline. */
        val hasMenu: Boolean,
        val listsServers: Boolean,
        val offersTryAgain: Boolean,
        /** The connection's status, never an address. */
        val issue: String?,
        val accessibilityValue: String,
        val accessibilityHint: String?,
    ) {
        companion object {
            operator fun invoke(
                serverCount: Int,
                status: HomeConnectionFeature.State.ConnectionStatus,
                switchingTo: String? = null,
            ): Presentation {
                val issue = when (status) {
                    HomeConnectionFeature.State.ConnectionStatus.connected -> null
                    HomeConnectionFeature.State.ConnectionStatus.reconnecting -> "Reconnecting"
                    HomeConnectionFeature.State.ConnectionStatus.disconnected -> OFFLINE
                }
                val listsServers = serverCount > 1
                val offersTryAgain = status == HomeConnectionFeature.State.ConnectionStatus.disconnected
                val hasMenu = listsServers || offersTryAgain
                return Presentation(
                    isDimmed = status == HomeConnectionFeature.State.ConnectionStatus.disconnected && switchingTo == null,
                    hasMenu = hasMenu,
                    listsServers = listsServers,
                    offersTryAgain = offersTryAgain,
                    issue = issue,
                    accessibilityValue = switchingTo?.let { "Connecting to $it" } ?: issue ?: "",
                    accessibilityHint = if (listsServers) "Shows your servers" else if (hasMenu) "Offers to try again" else null,
                )
            }

            /** iOS `DrawerTitle.presentation`, from the view's inputs. */
            fun of(
                servers: List<OpenCodeServer>,
                status: HomeConnectionFeature.State.ConnectionStatus,
                switchingTo: OpenCodeServer?,
            ): Presentation = Presentation(servers.size, status, switchingTo?.friendlyName)
        }
    }
}
