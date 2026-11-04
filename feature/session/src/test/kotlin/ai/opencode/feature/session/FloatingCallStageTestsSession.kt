package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `:feature:session` half of iOS `FloatingCallStageTests.swift`: `RequestDockCall` lives with
 * `SessionView+FloatingCall` here (`:feature:voice`'s `FloatingCallStageTests.kt` holds the rest).
 */
class FloatingCallStageTestsSession {
    @Test fun eachRequestDockingOnTheShowingScreenShrinksTheCall() {
        val first = State.TaskIdentity(serverID = "s", visit = 1, sessionID = "ses_1")
        val dock = RequestDockCall(identity = first, requestID = "req_1", isCovered = false, docksRequest = true)
        assertTrue(dock.shrinksCall)
        val other = dock.copy(identity = first.copy(sessionID = "ses_2"))
        assertNotEquals(dock, other)
        assertTrue(other.shrinksCall)
        val replaced = dock.copy(requestID = "req_2")
        assertNotEquals(dock, replaced)
        assertTrue(replaced.shrinksCall)
        val covered = dock.copy(isCovered = true)
        assertFalse(covered.shrinksCall)
        val answered = dock.copy(docksRequest = false)
        assertFalse(answered.shrinksCall)
    }
}
