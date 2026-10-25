package ai.opencode.feature.composer

import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.PageShareRequest
import ai.opencode.server.model.PastedTextSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * The composer's state on disk (iOS `PersistedComposerState`): drafts, unsent outbox lanes and
 * failed sends, keyed by server persistence ID and session ID. Inline attachment bytes live
 * beside it (`ComposerAttachments/<uuid>`), referenced by ID.
 */
@Serializable
data class PersistedComposerState(
    val version: Int = CURRENT_VERSION,
    val drafts: List<DraftRecord> = emptyList(),
    val outbox: List<OutboxRecord> = emptyList(),
    val undelivered: List<UndeliveredRecord> = emptyList(),
) {
    @Serializable
    data class AttachmentRecord(
        @Serializable(with = UUIDSerializer::class) val id: UUID,
        val mime: String,
        val name: String? = null,
        val hasPastedText: Boolean,
        val pasted: PastedTextSummary? = null,
    ) {
        constructor(attachment: OpenCodeOutgoingAttachment) : this(
            id = attachmentUUID(attachment.id),
            mime = attachment.mime,
            name = attachment.name,
            hasPastedText = attachment.pastedText != null,
            pasted = attachment.pasted,
        )
    }

    @Serializable
    data class UploadedFileRecord(
        @Serializable(with = UUIDSerializer::class) val id: UUID,
        val name: String,
        val mime: String,
        val size: Long,
        val uploadedPath: String,
    ) {
        companion object {
            fun of(file: ComposerFileAttachment): UploadedFileRecord? {
                val uploaded = file.status as? ComposerFileAttachment.Status.Uploaded ?: return null
                return UploadedFileRecord(file.id, file.name, file.mime, file.size, uploaded.path)
            }
        }
    }

    @Serializable
    data class DraftRecord(
        val serverID: String,
        val sessionID: String? = null,
        val project: OpenCodeProject? = null,
        val text: String,
        val attachments: List<AttachmentRecord>,
        val files: List<UploadedFileRecord> = emptyList(),
        @Serializable(with = SharedLocationSerializer::class) val location: SharedLocation? = null,
        val chips: List<ComposerAutocompleteChip> = emptyList(),
        val undoSnapshot: UndoSnapshot? = null,
        @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    ) {
        val hasPersistedContent: Boolean
            get() = text.isNotEmpty() || attachments.isNotEmpty() || files.isNotEmpty() || location != null || undoSnapshot != null
    }

    @Serializable
    data class PendingPromptRecord(
        val messageID: String,
        val text: String,
        val attachments: List<AttachmentRecord>,
        val files: List<UploadedFileRecord>,
        val wasRunning: Boolean,
        @Serializable(with = DeliverySerializer::class) val delivery: OpenCodeDelivery,
        val requestEpoch: Int,
        val model: OpenCodeModelRef? = null,
        @Serializable(with = PageShareRequestSerializer::class) val page: PageShareRequest? = null,
        val mentions: OpenCodeAutocompleteMentions? = null,
        val chips: List<ComposerAutocompleteChip> = emptyList(),
    )

    @Serializable
    data class OutboxRecord(
        val serverID: String,
        val sessionID: String? = null,
        val draftID: String? = null,
        val directory: String? = null,
        val newSessionID: String,
        val createdSession: OpenCodeSession? = null,
        val pending: PendingPromptRecord,
        val isHeld: Boolean,
        val backgroundKey: String? = null,
        @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    )

    @Serializable
    data class UndeliveredRecord(
        val serverID: String,
        val sessionID: String? = null,
        val pending: PendingPromptRecord,
        val reason: String,
        @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    )

    val isEmpty: Boolean
        get() = drafts.none { it.hasPersistedContent } && outbox.isEmpty() && undelivered.isEmpty()

    val referencedAttachmentIDs: Set<UUID>
        get() {
            val ids = HashSet<UUID>()
            for (draft in drafts) if (draft.hasPersistedContent) draft.attachments.mapTo(ids) { it.id }
            for (item in outbox) item.pending.attachments.mapTo(ids) { it.id }
            for (item in undelivered) item.pending.attachments.mapTo(ids) { it.id }
            return ids
        }

    /** A version change starts afresh; entries untouched for 30 days go. Returns the pruned state, or null when nothing changed. */
    fun prunedStale(now: Instant): PersistedComposerState? {
        if (version != CURRENT_VERSION) return PersistedComposerState()
        val cutoff = now.minusSeconds(STALENESS_INTERVAL_SECONDS)
        val hadStale = drafts.any { it.updatedAt < cutoff } ||
            outbox.any { it.updatedAt < cutoff } ||
            undelivered.any { it.updatedAt < cutoff }
        if (!hadStale) return null
        return copy(
            drafts = drafts.filterNot { it.updatedAt < cutoff },
            outbox = outbox.filterNot { it.updatedAt < cutoff },
            undelivered = undelivered.filterNot { it.updatedAt < cutoff },
        )
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val STALENESS_INTERVAL_SECONDS: Long = 30L * 24 * 60 * 60

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /**
         * Attachment IDs are `UUID`s on iOS and strings in `:server`'s `OpenCodeOutgoingAttachment`;
         * a non-UUID ID maps to a stable name-based UUID so its blob still has one file.
         */
        internal fun attachmentUUID(id: String): UUID =
            runCatching { UUID.fromString(id) }.getOrElse { UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)) }
    }
}

/** iOS `OpenCodeDelivery: Codable` (its raw value). */
internal object DeliverySerializer : KSerializer<OpenCodeDelivery> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OpenCodeDelivery", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OpenCodeDelivery) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): OpenCodeDelivery = OpenCodeDelivery.of(decoder.decodeString()) ?: OpenCodeDelivery.Steer
}

/** iOS `PageShareRequest: Codable`: `pageID`, `url`, `title`, `includesScreenshot`. */
internal object PageShareRequestSerializer : KSerializer<PageShareRequest> {
    @Serializable
    private class Surrogate(
        @Serializable(with = UUIDSerializer::class) val pageID: UUID,
        val url: String,
        val title: String,
        val includesScreenshot: Boolean,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PageShareRequest) =
        encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(value.pageID, value.url, value.title, value.includesScreenshot))

    override fun deserialize(decoder: Decoder): PageShareRequest {
        val s = decoder.decodeSerializableValue(Surrogate.serializer())
        return PageShareRequest(s.pageID, s.url, s.title, s.includesScreenshot)
    }
}
