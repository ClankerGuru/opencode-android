package ai.opencode.feature.voice

/**
 * A short reply in which the voice agent says it's acting now ("Opening.",
 * "Checking.", "Let me look into that."), which only a delegation makes
 * true. In Kit's call GPT-Live said "Opening." to "Open code iOS" and
 * "Checking." to "It didn't work" without delegating either, and nothing
 * happened for 40 s.
 */
object VoiceActionClaim {
    /** Acknowledgements are a few words; anything longer is an answer. */
    const val maxWords = 10

    private val gerunds = listOf(
        "opening", "checking", "switching", "sending", "pulling up", "pulling that up", "pulling it up",
        "starting", "creating", "searching", "asking", "moving", "pinning", "unpinning", "stopping",
        "looking into", "looking at", "looking for", "looking now", "looking it up", "taking a look",
        "having a look", "on it", "doing it now", "doing that now", "working on it", "digging into", "digging in",
    )
    private val verbs = listOf(
        "open", "check", "switch", "send", "pull", "start", "create", "search", "ask", "move", "pin",
        "unpin", "stop", "look", "take a look", "have a look", "find", "get on", "dig",
    )
    private val leads = listOf("i'll ", "i will ", "let me ", "i'm going to ", "i am going to ", "gonna ", "going to ")

    /**
     * "Let's" also suggests ("Let's start with the tests", "Let's stop there"),
     * so only the verbs that say the voice agent is looking now.
     */
    private val lets = listOf("let's ")
    private val letsVerbs = listOf("check", "look", "take a look", "have a look", "dig", "find", "pull", "open", "search")
    private val progressive = listOf("i'm ", "i am ")

    fun isClaim(text: String): Boolean {
        val lowered = text.lowercase().replace("’", "'")
        val words = lowered.splitWhere(Char::isWhitespace)
        if (words.isEmpty() || words.size > maxWords) return false
        val clauses = lowered
            .splitWhere { it in ".,!?;:—–…" }
            .map { it.trim { char -> char == ' ' || char == '\t' } + " " }
        return clauses.any { clause ->
            if (startsWithGerund(clause)) return@any true
            val afterProgressive = rest(clause, progressive)
            if (afterProgressive != null && startsWithGerund(afterProgressive)) return@any true
            rest(clause, lets)?.let { rest -> return@any letsVerbs.any { rest.startsWith("$it ") } }
            rest(clause, leads)?.let { rest -> return@any verbs.any { rest.startsWith("$it ") } }
            false
        }
    }

    private fun startsWithGerund(clause: String): Boolean = gerunds.any { clause.startsWith("$it ") }

    private fun rest(clause: String, prefixes: List<String>): String? =
        prefixes.firstOrNull(clause::startsWith)?.let { clause.substring(it.length) }
}
