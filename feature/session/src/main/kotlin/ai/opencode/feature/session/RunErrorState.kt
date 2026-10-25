package ai.opencode.feature.session

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.seams.ComposerFileAttachment
import ai.opencode.feature.session.seams.ExperimentalFeature
import ai.opencode.feature.session.seams.ExperimentalFeatures
import ai.opencode.feature.session.seams.TranscriptEntry
import ai.opencode.feature.session.seams.VoiceHandoff
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText

// Port of the `SessionFeature.State` members iOS declares in `Transcript/RunErrorState.swift`
// (and `noteOwnSend()` from `Transcript/NewMessagesMark.swift`), which the send reducer uses.
// They extend `SessionFeature.State`, so they land in this module. `hidingUndone` stays in
// `seams/FoundationSeams.kt` until the transcript port lands.

/**
 * The run error whose line offers its action: the newest turn's, while nothing runs or waits to
 * send. Prompting again (or anything newer in the turn) leaves the line as a quiet record.
 */
val State.actionableRunErrorID: String?
    get() {
        if (session == null || isRunning || failedSends.isNotEmpty() || queuedInputs.isNotEmpty() || session?.revertMessageID != null) return null
        val start = items.indexOfLast(::isUserItem).let { if (it >= 0) it + 1 else 0 }
        var found: String? = null
        for (item in items.subList(start, items.size)) {
            when (item) {
                // The idle message repeats its step's error: the step's is the row.
                is OpenCodeTranscriptItem.RunError -> if (found == null && !item.error.isStop) found = item.id
                // The turn went on after the error.
                is OpenCodeTranscriptItem.AssistantMarkdown, is OpenCodeTranscriptItem.Tool,
                is OpenCodeTranscriptItem.Compaction, is OpenCodeTranscriptItem.ModelSwitch,
                -> if (found != null) return null
                else -> Unit
            }
        }
        return found
    }

/** The actions the actionable run error's line and hold menu offer, its own first. */
val State.actionableRunErrorActions: List<OpenCodeRunError.Action>
    get() {
        val id = actionableRunErrorID ?: return emptyList()
        val item = items.lastOrNull { it.id == id } as? OpenCodeTranscriptItem.RunError ?: return emptyList()
        return runErrorActions(item.error)
    }

/** The actions a run error's line and its hold menu offer now, its own first. */
fun State.runErrorActions(error: OpenCodeRunError): List<OpenCodeRunError.Action> =
    error.actions.filter { it != OpenCodeRunError.Action.Edit || editableLastMessage != null }

/** iOS `editableLastMessage`'s `(id, text)`. */
data class EditableMessage(val id: String, val text: String)

/**
 * The last message the reader sent, when Edit can put it back in the composer whole: its words
 * only (an image or file couldn't come back).
 */
val State.editableLastMessage: EditableMessage?
    get() {
        val index = items.indexOfLast(::isUserItem)
        if (index < 0) return null
        // An agent's message (a call's delegation, another session's) isn't the reader's to edit.
        val item = items[index] as? OpenCodeTranscriptItem.UserText ?: return null
        if (item.source != null || VoiceHandoff.parse(item.text) != null || item.id in optimisticPromptIDs) return null
        val attached = items.any {
            (it is OpenCodeTranscriptItem.UserImages && it.id == "${item.id}-images") ||
                (it is OpenCodeTranscriptItem.UserFiles && it.id == "${item.id}-files")
        }
        return if (attached) null else EditableMessage(item.id, item.text)
    }

/**
 * Rebuilds one of the reader's delivered prompts ([messageID]) with its text, images, pasted text
 * cards, and uploaded file references so Revert to Here can put the whole message back in the
 * composer. Null when the message includes an inline binary file whose bytes aren't retained.
 */
fun State.restoredPrompt(messageID: String, uuid: UuidClient = dependency(UuidClient)): PendingPrompt? {
    if (messageID in optimisticPromptIDs) return null
    val boundary = session?.revertMessageID
    val boundaryIndex = boundary?.let { id -> items.indexOfFirst { startsMessage(it, id) } } ?: -1
    val visible = if (boundaryIndex >= 0) items.subList(0, boundaryIndex) else items
    var matched = false
    var text = ""
    var images: List<OpenCodeImageAttachment> = emptyList()
    var files: List<OpenCodeTranscriptFile> = emptyList()
    var source: OpenCodePromptSource? = null
    for (item in visible) {
        if (TranscriptEntry.promptID(item) != messageID) continue
        matched = true
        when (item) {
            is OpenCodeTranscriptItem.UserText -> {
                text = item.text
                source = item.source
            }
            is OpenCodeTranscriptItem.UserImages -> images = item.images
            is OpenCodeTranscriptItem.UserFiles -> files = item.files
            else -> return null
        }
    }
    if (!matched || source != null || VoiceHandoff.parse(text) != null) return null
    val attachments = images.map { image ->
        OpenCodeOutgoingAttachment(id = uuid.next().toString(), data = image.data, mime = imageMIME(image.data))
    }.toMutableList()
    val composerFiles = ArrayList<ComposerFileAttachment>()
    for (file in files) {
        val pasted = file.text
        val path = file.path
        when {
            pasted != null -> attachments += PastedText.attachment(pasted, id = uuid.next().toString())
            path != null -> composerFiles += ComposerFileAttachment(
                id = uuid.next(), name = file.name, mime = file.mime, size = 0, localURL = path,
                status = ComposerFileAttachment.Status.Uploaded(path),
            )
            else -> return null
        }
    }
    return PendingPrompt(messageID = messageID, text = text, attachments = attachments, files = composerFiles, requestEpoch = 0)
}

/** A new prompt, plain or command, ends any staged revert still in flight. Returns the state and the revert's cancellation. */
fun State.settleRevertForNewPrompt(): Pair<State, Effect<SessionFeature.Action>> =
    copy(isUndoingLastMessage = false).dropUndone() to Effect.cancel(SessionFeature.CancelID.Revert)

/** A new prompt makes an undo final: the server drops what it hid. */
fun State.dropUndone(): State {
    var next = withUndoSnapshot(null)
    val boundary = next.session?.revertMessageID ?: return next
    val index = next.items.indexOfFirst { startsMessage(it, boundary) }
    if (index >= 0) next = next.copy(items = next.items.subList(0, index)).syncShellToolStatuses()
    return next.setRevertMessageID(null)
}

/**
 * The reader sent a message: everything above it is read, so the divider goes and nothing waiting
 * to be settled counts as new (iOS `Transcript/NewMessagesMark.swift` `noteOwnSend()`).
 */
fun State.noteOwnSend(): State {
    val showsSinceYouLeft = dependency(ExperimentalFeatures)(ExperimentalFeature.sinceYouLeft)
    return copy(
        newMessages = null,
        newMessagesFitThrough = null,
        newMessagesBaseline = null,
        sinceYouLeft = null,
        sinceYouLeftSeen = null,
        sinceYouLeftAt = null,
        sinceYouLeftDismissed = if (showsSinceYouLeft) true else sinceYouLeftDismissed,
    )
}

private fun startsMessage(item: OpenCodeTranscriptItem, messageID: String): Boolean = when (item) {
    is OpenCodeTranscriptItem.UserText -> item.id == messageID
    is OpenCodeTranscriptItem.UserImages -> item.id == "$messageID-images"
    is OpenCodeTranscriptItem.UserFiles -> item.id == "$messageID-files"
    else -> false
}

private fun isUserItem(item: OpenCodeTranscriptItem): Boolean =
    item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages || item is OpenCodeTranscriptItem.UserFiles

private fun imageMIME(data: ByteArray): String {
    fun startsWith(vararg bytes: Int) = data.size >= bytes.size && bytes.indices.all { (data[it].toInt() and 0xff) == bytes[it] }
    if (startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png"
    if (startsWith(0xFF, 0xD8, 0xFF)) return "image/jpeg"
    if (startsWith(0x47, 0x49, 0x46, 0x38)) return "image/gif"
    if (data.size >= 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
        (data[8].toInt() == 0x57 && data[9].toInt() == 0x45 && data[10].toInt() == 0x42 && data[11].toInt() == 0x50)
    ) {
        return "image/webp"
    }
    return "image/jpeg"
}
