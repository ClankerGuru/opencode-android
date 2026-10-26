package ai.opencode.app

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of the `:app` half of iOS `MultipleWindowsTests.swift` (window roster, app-wide work,
 * call ownership). `SharedEventStreams`/`SharedReads` tests are `:server`'s
 * `live/MultipleWindowsTests`; `aFurtherWindowKeepsItsOwnSidebar` is `HomeSettingsFeature`
 * (`:feature:drawer`); `windowTargetRoundTripsThroughItsActivity` is `:feature:system`'s.
 * Dropped: `oneWindowIsAlwaysTheFrontWindow` (UIKit `ActiveScene` touch tracking),
 * `activityTypeIsDeclaredInInfoPlist` (Info.plist).
 */
class MultipleWindowsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun onlyTheFirstWindowDoesAppWideWork() {
        assertTrue(AppFeature.Action.SystemActionsTask.isAppWide)
        assertTrue(AppFeature.Action.PendingSharesRequested.isAppWide)
        assertTrue(AppFeature.Action.Home(HomeFeature.Action.SystemPublishRequested(voiceAvailable = true)).isAppWide)
        assertFalse(AppFeature.Action.Home(HomeFeature.Action.SessionTapped("a")).isAppWide)
    }

    @Test
    fun aCallBelongsToOneWindow() {
        val first = RootStore()
        val second = RootStore()
        SceneWindows.noteCall(first, active = true)
        try {
            assertTrue(SceneWindows.callIsElsewhere(second))
            assertFalse(SceneWindows.callIsElsewhere(first))
            SceneWindows.noteCall(second, active = true)
            assertTrue("The first call keeps the line", SceneWindows.callIsElsewhere(second))
            SceneWindows.noteCall(first, active = false)
            assertFalse(SceneWindows.callIsElsewhere(second))
        } finally {
            SceneWindows.noteCall(first, active = false)
        }
    }
}

/**
 * `WindowRoster`: roles follow windows (tasks) connecting and disconnecting, never a window's
 * state going away, so no window is left blank or without the app-wide work.
 */
class WindowRosterTests {
    @get:Rule val isolation = TestIsolationRule()

    class Member : WindowRosterMember {
        var role: WindowRoster.Role? = null
        var ownsAppWide = false

        override fun rosterAssigned(role: WindowRoster.Role) {
            this.role = role
        }

        override fun rosterOwnsAppWideWork(owns: Boolean) {
            ownsAppWide = owns
        }

        fun join(roster: WindowRoster) {
            role = roster.join(this)
            roster.attached(this, replacing = emptyList())
        }
    }

    @Test
    fun anIPhoneSceneDisconnectedAndReconnectedShowsTheApp() {
        val roster = WindowRoster(multipleWindows = false)
        val old = Member()
        old.join(roster)
        assertEquals(WindowRoster.Role.Primary, old.role)
        assertTrue(old.ownsAppWide)
        // Reclaimed in the background; the old window's state is kept.
        roster.detached(old)
        assertFalse(old.ownsAppWide)
        // The app comes back in a new task with new window state.
        val new = Member()
        new.join(roster)
        assertEquals("The new window shows the app, not a blank", WindowRoster.Role.Primary, new.role)
        assertTrue(new.ownsAppWide)
        assertSame(new, roster.primary)
    }

    @Test
    fun anIPhoneSceneReconnectingWithItsOldStateKeepsItsRole() {
        val roster = WindowRoster(multipleWindows = false)
        val window = Member()
        window.join(roster)
        roster.detached(window)
        roster.attached(window, replacing = emptyList())
        assertEquals(WindowRoster.Role.Primary, window.role)
        assertTrue(window.ownsAppWide)
    }

    @Test
    fun anIPhoneReloadNeverMakesASecondWindow() {
        val roster = WindowRoster(multipleWindows = false)
        val old = Member()
        old.join(roster)
        // New state for the one window, while the old is still connected.
        val new = Member()
        assertEquals("One window: never a secondary with a store of its own", WindowRoster.Role.Primary, roster.join(new))
        assertEquals(1, roster.members.size)
        assertTrue(new.ownsAppWide)
    }

    @Test
    fun aStaleWindowReattachingAfterANewOneDoesNotTakeTheAppBack() {
        val roster = WindowRoster(multipleWindows = true)
        val old = Member()
        old.join(roster)
        roster.detached(old)
        val new = Member()
        new.join(roster)
        assertEquals(WindowRoster.Role.Primary, new.role)
        roster.attached(old, replacing = emptyList())
        assertEquals(WindowRoster.Role.Secondary, old.role)
        assertEquals(WindowRoster.Role.Primary, new.role)
        assertTrue(new.ownsAppWide && !old.ownsAppWide)
    }

    @Test
    fun closingTheFirstIPadWindowHandsTheAppWideWorkOver() {
        val roster = WindowRoster(multipleWindows = true)
        val first = Member()
        val second = Member()
        first.join(roster)
        second.join(roster)
        assertEquals(WindowRoster.Role.Secondary, second.role)
        assertTrue(first.ownsAppWide && !second.ownsAppWide)
        roster.detached(first)
        assertTrue("The survivor takes the inbox, shares and publishing", second.ownsAppWide)
        // Reopened: the new window shows the app's store again.
        val reopened = Member()
        reopened.join(roster)
        assertEquals(WindowRoster.Role.Primary, reopened.role)
        assertTrue(reopened.ownsAppWide && !second.ownsAppWide)
    }

    @Test
    fun aReloadReplacesTheWindowStateInTheSameScene() {
        val roster = WindowRoster(multipleWindows = true)
        val old = Member()
        old.join(roster)
        val new = Member()
        new.role = roster.join(new)
        assertEquals(WindowRoster.Role.Secondary, new.role)
        roster.attached(new, replacing = listOf(old))
        assertEquals(WindowRoster.Role.Primary, new.role)
        assertTrue(new.ownsAppWide)
        assertEquals(1, roster.members.size)
    }

    @Test
    fun aWindowWhoseStateWentAwayLeaves() {
        val roster = WindowRoster(multipleWindows = true)
        val first = Member()
        val second = Member()
        first.join(roster)
        second.join(roster)
        // iOS purges the released member on `left()`; Android removes it explicitly (a ViewModel
        // isn't released at `onCleared`).
        roster.left(first)
        assertEquals(WindowRoster.Role.Secondary, second.role)
        assertTrue(second.ownsAppWide)
    }
}
