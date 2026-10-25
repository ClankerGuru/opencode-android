package ai.opencode.server.model

import ai.opencode.core.sharing.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.ceil

/**
 * What a background shell's or subagent's notice reports (“Shell finished”,
 * “Explore failed”); several of one kind are counted (“2 subagents finished”).
 */
@Serializable
data class TranscriptNoticeKind(val source: Source, val outcome: Outcome) {
    @Serializable
    enum class Source(val one: String, val many: String) {
        @SerialName("shell") Shell("shell", "shells"),
        @SerialName("subagent") Subagent("subagent", "subagents");

        /** “Shell”, “Subagent”. */
        val title: String get() = AgentDisplayName.titleCased(one)
    }

    @Serializable
    enum class Outcome(val rawValue: String) {
        @SerialName("finished") Finished("finished"),
        @SerialName("failed") Failed("failed"),
        @SerialName("cancelled") Cancelled("cancelled");

        /** What the notice says: a cancelled one was interrupted. */
        val word: String get() = if (this == Cancelled) "interrupted" else rawValue
    }

    /** The notice's own status line, naming a subagent by its agent: “Explore finished”, else “Subagent finished”. */
    fun heading(agent: String? = null): String {
        val actor = if (source == Source.Subagent) AgentDisplayName.of(agent) ?: source.title else source.title
        return "$actor ${outcome.word}"
    }

    /** “1 shell failed”, “2 subagents finished”. */
    fun count(count: Int): String = "$count ${if (count == 1) source.one else source.many} ${outcome.word}"

    companion object {
        /** A notice reads “<heading> · <description>”. */
        fun headingOfNotice(text: String): String = text.split(" · ").first()
    }
}

object AgentDisplayName {
    /** An agent's ID as people read it: “explore” → “Explore”, “code-reviewer” → “Code-Reviewer”. */
    fun of(id: String?): String? {
        val trimmed = id?.trimmedWhitespace()?.ifEmpty { null } ?: return null
        return titleCased(trimmed)
    }

    fun titleCased(text: String): String {
        val result = StringBuilder()
        var atWordStart = true
        var index = 0
        while (index < text.length) {
            val end = text.offsetByCodePoints(index, 1)
            val piece = text.substring(index, end)
            val codePoint = piece.codePointAt(0)
            val isWord = Character.isLetter(codePoint) || Character.isDigit(codePoint) || piece == "_"
            result.append(if (atWordStart && isWord) piece.uppercase() else piece)
            atWordStart = !isWord
            index = end
        }
        return result.toString()
    }
}

/**
 * A run that ended early: an assistant message's `error`, or an `idle` message's when
 * the run failed before any reply (V2 `Session.StructuredError`).
 */
@Serializable
data class OpenCodeRunError(
    val type: String,
    val message: String,
    val status: Int? = null,
    /** Recorded on the run's `idle` message: nothing replied before it failed. */
    val beforeReply: Boolean = false,
) {
    enum class Action(val title: String) {
        /** Sends “Continue”, so the agent picks up where the run stopped. */
        TryAgain("Try Again"),
        /** Undoes the last message and puts its words back in the composer. */
        Edit("Edit"),
        Compact("Compact"),
        SwitchModel("Switch Model"),
    }

    /** A stop the reader asked for (Stop, or a denied request) is no failure. */
    val isStop: Boolean get() = type == "aborted" || type == "permission.rejected"

    /** The line the transcript shows. */
    val line: String
        get() = when (type) {
            "aborted" -> "Interrupted"
            "permission.rejected" -> "Interrupted after a denied request"
            "provider.content-filter" -> "Safety filter blocked the reply"
            "provider.rate-limit" -> "Rate limit reached"
            "provider.quota" -> "Usage limit reached"
            "provider.auth" -> "The provider rejected its key"
            "provider.invalid-request" -> if (isContextOverflow) "Session too long for this model" else "The provider rejected the request"
            "provider.internal" -> if (isOverloaded) "The model is overloaded" else "The provider had a problem"
            "provider.transport" -> "Connection to the model dropped"
            "provider.timeout" -> "The model took too long to reply"
            "provider.invalid-output" -> "The model’s reply couldn’t be read"
            "provider.no-route", "provider.unknown" -> "This model isn’t available"
            "provider.unsupported-operation" -> "This model can’t do that"
            "tool.execution" -> "A tool failed and the run stopped"
            else -> "The run stopped with an error"
        }

    /** The one action the line offers; the hold menu has the rest. */
    val action: Action?
        get() = when (type) {
            "aborted", "permission.rejected" -> null
            "provider.content-filter" -> Action.Edit
            "provider.invalid-request" -> if (isContextOverflow) Action.Compact else Action.Edit
            "provider.quota", "provider.auth", "provider.no-route", "provider.unknown", "provider.unsupported-operation" -> Action.SwitchModel
            else -> Action.TryAgain
        }

    /** Every action that could help, the line's own first. */
    val actions: List<Action>
        get() {
            if (isStop) return emptyList()
            val all = listOfNotNull(action).toMutableList()
            for (other in listOf(Action.TryAgain, Action.Edit, Action.SwitchModel)) if (other !in all) all += other
            if (isContextOverflow && Action.Compact !in all) all += Action.Compact
            return all
        }

    /** The raw type and the provider's message, for Details and Copy. */
    val details: String
        get() {
            val code = status?.let { "$type · $it" } ?: type
            return if (message.isEmpty()) code else "$code\n$message"
        }

    /** V2 classifies an overflowing prompt as an invalid request; its message carries the provider's wording. */
    val isContextOverflow: Boolean
        get() {
            val text = message.lowercase()
            return listOf(
                "prompt is too long", "context length", "context_length_exceeded", "context window", "maximum context",
                "too many tokens", "input is too long", "exceeds the context",
            ).any { text.contains(it) }
        }

    private val isOverloaded: Boolean get() = status == 529 || message.lowercase().contains("overloaded")
}

/** The server's automatic retry after a failed step (`Assistant.retry`). */
@Serializable
data class OpenCodeRunRetry(
    val attempt: Int,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val error: OpenCodeRunError,
) {
    /** “Rate limit reached · retrying in 4 s”, then “… · retrying”. */
    fun title(now: Instant): String {
        val seconds = ceil((at.toEpochMilli() - now.toEpochMilli()) / 1000.0).toInt()
        return if (seconds > 0) "${error.line} · retrying in $seconds s" else "${error.line} · retrying"
    }
}
