package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.AttachmentMenuLinger
import ai.opencode.feature.composer.AttachmentMenuPhase
import ai.opencode.feature.session.taskIdentity
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Port of iOS `AttachmentMenuPressTests.attachmentMenuLingerResetsAcrossSessionAndDraftSwitches`:
 * the linger keys on the identity Home gives each screen. The press and menu tests are
 * `:feature:composer`'s.
 */
class AttachmentMenuPressTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun attachmentMenuLingerResetsAcrossSessionAndDraftSwitches() = DependencyValues.withValues(DependencyValues.testing()) {
        val server = OpenCodeServer(url = "https://menu.example", username = "", password = "")
        val project = OpenCodeProject(id = "p1", name = "project", directory = "/project")
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet()))
        val firstDraftIdentity = home.sessionDetail!!.taskIdentity

        var linger = AttachmentMenuLinger()
        linger = linger.phaseChanged(AttachmentMenuPhase.Menu, firstDraftIdentity)
        linger = linger.phaseChanged(AttachmentMenuPhase.Photos, firstDraftIdentity)
        linger = linger.phaseChanged(AttachmentMenuPhase.Closed, firstDraftIdentity)
        assertTrue(linger.glassLingers(firstDraftIdentity))
        assertTrue(linger.rowsLinger(firstDraftIdentity))
        assertTrue(linger.keepsPickerDrawn(firstDraftIdentity))

        // A draft becoming its own created session keeps the same taskIdentity.
        val created = OpenCodeSession(id = "ses_created", serverTitle = "Created", projectID = "p1", directory = "/project", updatedAt = Instant.EPOCH)
        val becameSession = home.sessionDetail!!.copy(draftBecameSessionID = created.id, session = created)
        assertEquals(firstDraftIdentity, becameSession.taskIdentity)
        assertTrue(linger.glassLingers(becameSession.taskIdentity))

        // Opening a fresh draft from a draft advances taskIdentity (even though session?.id is
        // null -> null) and immediately suppresses lingering layers.
        val secondDraft = home.makeDraft().second
        val secondDraftIdentity = secondDraft.taskIdentity
        assertNull(secondDraft.session?.id)
        assertNotEquals(firstDraftIdentity, secondDraftIdentity)
        assertFalse(linger.glassLingers(secondDraftIdentity))
        assertFalse(linger.rowsLinger(secondDraftIdentity))
        assertFalse(linger.keepsPickerDrawn(secondDraftIdentity))
        assertEquals(AttachmentMenuPhase.Closed, linger.shownPhase(secondDraftIdentity))

        linger = linger.syncIdentity(secondDraftIdentity, AttachmentMenuPhase.Closed)
        assertFalse(linger.glassLingers)
        assertFalse(linger.rowsLinger)
        assertFalse(linger.keepsPickerDrawn)
    }
}
