package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `:feature:session` halves of iOS `FloatingCallModelTests.swift`, deferred from
 * `:feature:voice` (its `FloatingCallModelTests.kt` holds the tray halves).
 */
class FloatingCallModelTestsSession {
    /** `callTrayExperimentAndTunablesRegisterOption2Defaults`'s `ComposerTunables` defaults (baked constants here, so there's no `TuningStore` registry half). */
    @Test fun callTrayExperimentAndTunablesRegisterOption2Defaults() {
        assertEquals(0f, ComposerTunables.bottomGap)
        assertEquals(8f, ComposerTunables.keyboardGap)
    }

    /** `tapIntoProjectPickerOrImmediateFailureLeavesNoTray`'s `SessionFeature.State.canStartCallFromTap` half. */
    @Test fun tapIntoProjectPickerOrImmediateFailureLeavesNoTray() = sessionTest { h ->
        val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
        val project = OpenCodeProject(id = "proj_1", name = "app", directory = "/tmp/app")
        val draftWithoutProject = h.state { State.draft(server = server, draftProject = null, projects = listOf(project)) }
        assertEquals(
            "A draft without a project opens the project picker instead of starting a call",
            false, h.state { draftWithoutProject.canStartCallFromTap },
        )

        val draftWithProject = h.state { State.draft(server = server, draftProject = project, projects = listOf(project)) }
        assertEquals(true, h.state { draftWithProject.canStartCallFromTap })
    }
}
