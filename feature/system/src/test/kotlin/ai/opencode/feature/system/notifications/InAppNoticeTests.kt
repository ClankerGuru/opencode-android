package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.testing.TestIsolationRule
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Port of iOS `InAppNoticeTests.swift`'s notification tests (the rest is `:feature:drawer`'s):
 * how a session notification shows in the foreground (iOS `DeliveredNotifications.presentation`,
 * Android [SessionNotifications.presentation]) and which delivered ones an answer clears (iOS
 * `DeliveredNotifications.isNeedsInput`/`sessionID(of:)`, Android [SessionPushNotifications.remove]
 * over the posted notifications, Robolectric).
 *
 * iOS `[.banner, .sound, .list]` is [NotificationPresentation.Banner], `[.list]`
 * [NotificationPresentation.ListOnly], `[]` [NotificationPresentation.None]. In-App Notices' mode
 * reaches Android as `inAppNoticesOn` (the drawer's reader: mode ≠ Off).
 */
class InAppNoticeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun bannersForOtherSessionsGoOnlyToNotificationCenterWhileNoticesAreOn() {
        fun presentation(sessionID: String?, visibleSessionID: String?, noticesOn: Boolean): NotificationPresentation {
            val notifications = SessionNotifications(PushTokenProvider.inert)
            notifications.visibleSessionID = visibleSessionID
            notifications.inAppNoticesOn = { noticesOn }
            return notifications.presentation(sessionID)
        }
        assertEquals(NotificationPresentation.None, presentation("ses_a", "ses_a", noticesOn = true))
        assertEquals(NotificationPresentation.Banner, presentation("ses_b", "ses_a", noticesOn = false))
        // iOS `.needsInput` and `.all` both read as on.
        assertEquals(NotificationPresentation.ListOnly, presentation("ses_b", "ses_a", noticesOn = true))
        assertEquals(NotificationPresentation.ListOnly, presentation("ses_b", null, noticesOn = true))
        assertEquals(NotificationPresentation.Banner, presentation(null, "ses_a", noticesOn = true))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InAppNoticeFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun needsInputStatusesAreTheOnlyOnesClearedOnAnswer() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        fun post(status: String) = withDependencies({ this[AppContext] = context }) {
            SessionPushNotifications.handle(context, mapOf("title" to "T", "body" to "B", "sessionID" to "ses_a", "status" to status))
        }
        fun shown() = manager.activeNotifications.count { it.tag == "ses_a" }

        post("permission")
        assertEquals(1, shown())
        SessionPushNotifications.remove(context, setOf("ses_a"), needsInputOnly = true)
        assertEquals("A needs-input status is cleared on answer", 0, shown())

        post("succeeded")
        SessionPushNotifications.remove(context, setOf("ses_a"), needsInputOnly = true)
        assertEquals("Any other status stays", 1, shown())
        // It still belongs to its session.
        SessionPushNotifications.remove(context, setOf("ses_a"), needsInputOnly = false)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
