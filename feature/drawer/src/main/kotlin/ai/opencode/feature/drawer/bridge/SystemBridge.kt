package ai.opencode.feature.drawer.bridge

/*
 * Drawer-local stand-ins for iOS Settings/System/Voice types that Home's state and actions name
 * but whose areas (`:feature:system`, `:feature:voice`) haven't ported yet. (What's New is
 * `:feature:settings`' `WhatsNewFeature` now.)
 */

/** iOS `NotificationPermission` (`System/Notifications`). TODO(merge): replace with `:feature:system`'s type. */
enum class NotificationPermission { NotDetermined, Denied, Authorized, Provisional }

/** A command from the voice call's Live Activity / ongoing notification. TODO(merge): `:feature:voice`'s `VoiceCallCommand`. */
enum class VoiceCallCommand { Mute, Unmute, End }

/** A command from the recording's Live Activity / ongoing notification. TODO(merge): `:feature:voice`'s `DictationCommand`. */
enum class DictationCommand { Stop, Cancel }

/** An item from the share sheet, for this server. TODO(merge): `:feature:system`'s `ReceivedShare`. */
data class ReceivedShare(
    val id: String,
    val target: SharedDraftTarget,
    val text: String = "",
    val projectID: String? = null,
)

/** Where a system action (shortcut, launcher action, link) goes. TODO(merge): `SystemAction.Destination`. */
sealed interface SystemActionDestination {
    data object NewSession : SystemActionDestination
    data class Session(val sessionID: String) : SystemActionDestination
    data object CatchUp : SystemActionDestination
}
