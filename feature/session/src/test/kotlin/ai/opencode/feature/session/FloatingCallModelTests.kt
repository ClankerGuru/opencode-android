package ai.opencode.feature.session

import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port of the session halves of iOS `FloatingCallModelTests.swift` (deferred here in docs/parity/voice.md); the rest
 * of the file is :feature:voice's (`FloatingCallModelTests.kt` there).
 *
 * Here: `ComposerTunables.bottomGap` / `keyboardGap` of `callTrayExperimentAndTunablesRegisterOption2Defaults`, and the
 * `SessionFeature.State.canStartCallFromTap` half of `tapIntoProjectPickerOrImmediateFailureLeavesNoTray`. Their
 * call-tray halves, and the rest of the file, are :feature:voice's.
 */
class FloatingCallModelTests {
    /** The composer half: `ComposerTunables` lives with the session's composer. */
    @Test fun callTrayExperimentAndTunablesRegisterOption2Defaults() {
        assertEquals(0f, ComposerTunables.bottomGap)
        assertEquals(8f, ComposerTunables.keyboardGap)
    }

    /** The session half: a draft without a project opens the project picker instead of starting a call. */
    @Test fun tapIntoProjectPickerOrImmediateFailureLeavesNoTray() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val project = OpenCodeProject(id = "proj_1", name = "app", directory = "/tmp/app")
        val draftWithoutProject = h.state { SessionFeature.State.draft(server = server, draftProject = null, projects = listOf(project)) }
        assertEquals("A draft without a project opens the project picker instead of starting a call", false, draftWithoutProject.canStartCallFromTap)

        val draftWithProject = h.state { SessionFeature.State.draft(server = server, draftProject = project, projects = listOf(project)) }
        assertEquals(true, draftWithProject.canStartCallFromTap)
    }
}
