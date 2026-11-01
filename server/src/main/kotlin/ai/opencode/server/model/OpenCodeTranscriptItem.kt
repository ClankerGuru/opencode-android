package ai.opencode.server.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/** Bytes as a base64 string, as Swift's `Data` encodes. */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}

@Serializable
class OpenCodeImageAttachment(
    val id: String,
    @Serializable(with = Base64ByteArraySerializer::class) val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is OpenCodeImageAttachment && other.id == id && other.data.contentEquals(data)
    override fun hashCode(): Int = 31 * id.hashCode() + data.contentHashCode()
    override fun toString(): String = "OpenCodeImageAttachment(id=$id, ${data.size} bytes)"
}

/** Inline prompt media: re-encoded images, or a PDF the selected model reads natively. */
class OpenCodeOutgoingAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val data: ByteArray,
    val mime: String,
    /** Sent for inline non-image files so the transcript can label their chip. */
    val name: String? = null,
    /** A long paste's text and its card's summary, kept so nothing decodes the bytes again. */
    val pastedText: String? = null,
    val pasted: PastedTextSummary? = null,
    /** Content-addressed blob hash when re-sending a `media:<hash>` reference (its bytes may be empty). */
    val mediaHash: String? = null,
    val byteCount: Long? = null,
    /** A frame of a library video, sent as an image: its duration in seconds, for the composer thumbnail's “▶ 0:50” badge. */
    val videoDuration: Double? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")

    val dataURL: String get() = "data:$mime;base64,${Base64.getEncoder().encodeToString(data)}"

    override fun equals(other: Any?): Boolean = other is OpenCodeOutgoingAttachment && other.id == id &&
        other.data.contentEquals(data) && other.mime == mime && other.name == name &&
        other.pastedText == pastedText && other.pasted == pasted && other.mediaHash == mediaHash &&
        other.byteCount == byteCount && other.videoDuration == videoDuration

    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "OpenCodeOutgoingAttachment(id=$id, mime=$mime, ${data.size} bytes)"
}

/** Who sent a user-role message when an agent did rather than the person at a keyboard. */
@Serializable
sealed interface OpenCodePromptSource {
    @Serializable @SerialName("session")
    data class Session(val id: String) : OpenCodePromptSource

    @Serializable @SerialName("parentAgent")
    data object ParentAgent : OpenCodePromptSource

    companion object {
        /** How the server opens a new subagent's first prompt. */
        const val SUBAGENT_PREAMBLE = "You are a subagent spawned by another session."

        fun of(text: String?, metadata: Map<String, OpenCodeJSONValue>?): OpenCodePromptSource? {
            val origin = metadata?.get("anomaly.session")?.obj?.get("originSessionID")?.string
            if (origin != null) return Session(origin)
            if (text?.startsWith(SUBAGENT_PREAMBLE) == true) return ParentAgent
            return null
        }
    }
}

/**
 * One transcript row's content, as decoded from REST and kept in the transcript cache.
 *
 * Deviation from iOS: [AssistantMarkdown] and [OpenCodeCompaction.summary] carry the raw
 * Markdown text; iOS parses `[OpenCodeMarkdownBlock]` at decode. The Markdown model lives
 * with the transcript renderer (`:feature:transcript`), which parses (and caches) it.
 */
@Serializable
sealed interface OpenCodeTranscriptItem {
    val id: String

    /** [source] is set when an agent sent it. */
    @Serializable @SerialName("userText")
    data class UserText(override val id: String, val text: String, val source: OpenCodePromptSource? = null) : OpenCodeTranscriptItem

    @Serializable @SerialName("userImages")
    data class UserImages(override val id: String, val images: List<OpenCodeImageAttachment>) : OpenCodeTranscriptItem

    /** Path references and inline non-image files; IDs end in `-files`. */
    @Serializable @SerialName("userFiles")
    data class UserFiles(override val id: String, val files: List<OpenCodeTranscriptFile>) : OpenCodeTranscriptItem

    /** Trimmed, non-empty Markdown of one assistant text part. */
    @Serializable @SerialName("assistantMarkdown")
    data class AssistantMarkdown(override val id: String, val text: String) : OpenCodeTranscriptItem

    @Serializable @SerialName("tool")
    data class Tool(override val id: String, val call: OpenCodeToolCall) : OpenCodeTranscriptItem

    @Serializable @SerialName("compaction")
    data class Compaction(override val id: String, val info: OpenCodeCompaction? = null) : OpenCodeTranscriptItem

    @Serializable @SerialName("info")
    data class Info(override val id: String, val text: String) : OpenCodeTranscriptItem

    /** The session switched model or thinking level; [name] is set as the list is built. */
    @Serializable @SerialName("modelSwitch")
    data class ModelSwitch(
        override val id: String,
        val model: OpenCodeModelRef,
        val previous: OpenCodeModelRef? = null,
        val name: String? = null,
    ) : OpenCodeTranscriptItem

    /** [kind] is set for a shell's or subagent's notice. */
    @Serializable @SerialName("notice")
    data class Notice(
        override val id: String,
        val text: String,
        val isError: Boolean = false,
        val kind: TranscriptNoticeKind? = null,
    ) : OpenCodeTranscriptItem

    /** Legacy on iOS (older builds' failed sends); kept so the item set matches. Nothing makes it. */
    @Serializable @SerialName("sendFailure")
    data class SendFailure(override val id: String, val message: String) : OpenCodeTranscriptItem

    /** A run that ended early, in the Working line's place. */
    @Serializable @SerialName("runError")
    data class RunError(override val id: String, val error: OpenCodeRunError) : OpenCodeTranscriptItem

    /** The server will retry the run's failed step: the Working line counts down to it. */
    @Serializable @SerialName("runRetry")
    data class RunRetry(override val id: String, val retry: OpenCodeRunRetry) : OpenCodeTranscriptItem

    /** Where a staged undo hid the last [messages] prompts, offering Redo. Only the screen makes it. */
    @Serializable @SerialName("undone")
    data class Undone(override val id: String, val messages: Int) : OpenCodeTranscriptItem
}

/** A durable context compaction boundary, mirroring the TUI's states. */
@Serializable
data class OpenCodeCompaction(
    val status: Status,
    /** The provider compacted its own context; there is no local summary. */
    val isProvider: Boolean = false,
    /** The local summary's Markdown (iOS keeps it parsed; see [OpenCodeTranscriptItem]). */
    val summary: String = "",
    val errorMessage: String? = null,
    /** Usage of the compaction request itself, not the resulting context size. */
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("running") Running,
        @SerialName("completed") Completed,
        @SerialName("failed") Failed,
        @SerialName("cancelled") Cancelled,
    }
}

data class OpenCodeTranscriptPage(val items: List<OpenCodeTranscriptItem>, val next: String?)

/** The server half of notifications (`packages/notify-plugin`). */
enum class OpenCodeNotifyPluginStatus {
    Active,
    /** Listed but not running, e.g. still installing or failed to load. */
    Inactive,
    Missing;

    companion object {
        const val PLUGIN_ID = "opencode-notify-callback"
        /** What `opencode.json` lists to install it. */
        const val PACKAGE_ENTRY = "github:anomalyco/opencode-ios#main::path:packages/notify-plugin"
    }
}
