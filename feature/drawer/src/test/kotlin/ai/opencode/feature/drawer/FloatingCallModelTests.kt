package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `FloatingCallModelTests.onlyTopSessionViewDrivesCallTrayController`, Home's half:
 * pushing a subagent covers the screen beneath and popping uncovers it (`isCovered`). The rest of
 * that test hosts SwiftUI views in a `UIWindow` to watch the Call Tray controller's frames (UIKit;
 * deleted). The other floating-call tests are `:feature:voice`'s.
 */
class FloatingCallModelTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun onlyTopSessionViewDrivesCallTrayController() = DependencyValues.withValues(DependencyValues.testing()) {
        val server = OpenCodeServer(url = "https://stack.example", username = "", password = "")
        val root = OpenCodeSession(id = "root", title = "root", projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10))
        val child = OpenCodeSession(id = "child", title = "child", projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(20), parentID = "root")
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(root), activeSessionIDs = emptySet()))
        home = home.openingDetail(root, cachedItems = emptyList())
        assertEquals(false, home.sessionDetail?.isCovered)

        home = home.pushingSubagent(child)
        assertEquals("Pushing a subagent covers the root SessionView", true, home.sessionDetail?.isCovered)
        assertEquals("Only the top subagent is uncovered", false, home.subagentStack.lastOrNull()?.isCovered)

        home = home.poppingSubagents(0)
        assertEquals(false, home.sessionDetail?.isCovered)
    }
}
