package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The `:feature:composer` tests of iOS `OpenCodeTests/ShareComposerTests.swift`:
 * [SharedComposerDraft] (here because it carries composer types). The rest is `:feature:system`'s
 * (`share/ShareComposerTests.kt`) and `:feature:drawer`'s (`ShareComposerAppTests`). iOS's
 * `append` mutates in place; Android's [SharedComposerDraft.appending] returns the joined value.
 */

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
