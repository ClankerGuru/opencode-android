package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `:feature:composer` tests of iOS `OpenCodeTests/ShareComposerTests.swift`:
 * [SharedComposerDraft] (here because it carries composer types) and the [PromptOutbox] half of
 * `newIDsHaveTheServersShape`. The rest is `:feature:system`'s (`share/ShareComposerTests.kt`)
 * and `:feature:drawer`'s (`ShareComposerAppTests`). iOS's `append` mutates in place; Android's
 * [SharedComposerDraft.appending] returns the joined value.
 */
class ShareComposerTests {
    /**
     * `newIDsHaveTheServersShape`'s `PromptOutbox.newSessionID` comparison: the outbox makes the
     * same ID as `ShareSendRequest.newSessionID` (`:feature:system` checks that one against the
     * same literal; this module can't see it).
     */
    @Test
    fun newIDsHaveTheServersShape() {
        val date = Instant.ofEpochSecond(1_800_000_000)
        assertEquals("ses_ce7a3afffffeabc00000000000", PromptOutbox.newSessionID(at = date, entropy = "abc"))
    }
}

/** iOS `ShareCompositionTests` (the sheet's decisions): the one that joins drafts. */
class ShareCompositionTests {
    private fun draft(text: String, send: SharedComposerDraft.Send? = null) = SharedComposerDraft(text = text, send = send)

    @Test
    fun sharesJoiningOneComposerSendOnlyWhenAllAskTo() {
        var two = draft("a", send = SharedComposerDraft.Send(messageID = "msg_1", sessionID = null))
        two = two.appending(draft("b", send = SharedComposerDraft.Send(messageID = "msg_2", sessionID = null)))
        assertEquals("a\n\nb", two.text)
        assertEquals("One message now: neither tried ID", SharedComposerDraft.Send(messageID = null, sessionID = null), two.send)
        var mixed = draft("typed")
        mixed = mixed.appending(draft("shared", send = SharedComposerDraft.Send(messageID = "msg_3", sessionID = null)))
        assertNull(mixed.send)
        assertTrue("A Send Later that stays a draft gets the keyboard", mixed.focusesComposer)
        var lone = SharedComposerDraft()
        lone = lone.appending(draft("alone", send = SharedComposerDraft.Send(messageID = "msg_4", sessionID = "ses_4")))
        assertEquals(SharedComposerDraft.Send(messageID = "msg_4", sessionID = "ses_4"), lone.send)
    }
}
