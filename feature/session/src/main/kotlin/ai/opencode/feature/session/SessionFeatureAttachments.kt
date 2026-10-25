package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.map
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.feature.session.seams.ComposerAttachmentsFeature
import ai.opencode.feature.session.seams.ComposerFileAttachment
import ai.opencode.feature.session.seams.cancelUpload
import ai.opencode.feature.session.seams.readInlineFile
import ai.opencode.server.OpenCodeClientError

// Port of iOS `SessionFeature+Attachments.swift`: attachments, uploads, and Location as the session
// sees them. `ComposerAttachmentsFeature` (a composer seam until :feature:composer merges) reduces
// the rest of each action first.

/** Attachments, uploads, and Location. Null: the action passes on. */
internal fun SessionFeature.reduceAttachments(state: State, action: Action): Reduced<State, Action>? {
    val attachments = (action as? Action.ComposerAttachments)?.action ?: return null
    return when (attachments) {
        is ComposerAttachmentsFeature.Action.AttachmentAdded -> {
            val parked = state.parkedComposer
            when {
                // An import that finishes while the bar answers belongs to the waiting message.
                parked != null -> Reduced(state.withParkedComposer(parked.copy(attachments = parked.attachments + attachments.attachment)))
                !state.takesPasteAttachments -> Reduced(state)
                else -> {
                    haptics.play(Haptic.selection)
                    Reduced(state.withPendingAttachments(state.pendingAttachments + attachments.attachment))
                }
            }
        }

        is ComposerAttachmentsFeature.Action.AttachmentRemoved,
        ComposerAttachmentsFeature.Action.LocationTapped,
        ComposerAttachmentsFeature.Action.LocationRemoved,
        ComposerAttachmentsFeature.Action.LocationErrorDismissed,
        ComposerAttachmentsFeature.Action.LocationSettingsTapped,
        -> Reduced(state)

        is ComposerAttachmentsFeature.Action.FilesStaged -> {
            var next = state
            val effects = mutableListOf<Effect<Action>>()
            var staged = false
            for (file in attachments.files) {
                // Like desktop, media the model reads natively travels inline.
                if (file.mime == AttachmentFileType.pdfMime && file.size <= AttachmentFileType.maxInlineBytes && next.currentModelReads("pdf")) {
                    effects += ComposerAttachmentsFeature.readInlineFile(file).map(Action::ComposerAttachments)
                    continue
                }
                val parked = next.parkedComposer
                next = if (parked != null) {
                    next.withParkedComposer(parked.copy(files = parked.files + ComposerFileAttachment(file)))
                } else {
                    next.withPendingFiles(next.pendingFiles + ComposerFileAttachment(file))
                }
                val uploaded = upload(file.id, next)
                next = uploaded.state
                effects += uploaded.effect
                staged = true
            }
            // An inline file ticks as `attachmentAdded` when its bytes are read.
            if (staged) haptics.play(Haptic.selection)
            Reduced(next, Effect.merge(effects))
        }

        ComposerAttachmentsFeature.Action.QueuedUploadsStarted -> {
            val ids = state.unstartedUploadIDs
            var next = state.withUnstartedUploadIDs(emptyList())
            val effects = mutableListOf<Effect<Action>>()
            for (id in ids) {
                val uploaded = upload(id, next)
                next = uploaded.state
                effects += uploaded.effect
            }
            Reduced(next, Effect.merge(effects))
        }

        is ComposerAttachmentsFeature.Action.FileUploadProgress ->
            Reduced(
                updateFile(attachments.id, state) { file ->
                    if (!file.isUploading) file else file.copy(status = ComposerFileAttachment.Status.Uploading(attachments.sent))
                },
            )

        is ComposerAttachmentsFeature.Action.FileUploadFinished -> {
            val id = attachments.id
            val path = attachments.result.getOrNull()
            if (path != null) {
                sendIfUploadsFinished(updateFile(id, state) { it.copy(status = ComposerFileAttachment.Status.Uploaded(path)) })
            } else {
                val isUnsupported = (attachments.result.exceptionOrNull() as? OpenCodeClientError)?.isUnsupported == true
                if (isUnsupported) {
                    val serverID = state.server.persistenceID
                    state.uploadUnsupportedServerIDs.update { it + serverID }
                }
                var next = updateFile(id, state) {
                    it.copy(status = ComposerFileAttachment.Status.Failed(if (isUnsupported) "Server doesn’t support files" else "Couldn’t upload"))
                }
                if (next.pendingFiles.any { it.id == id } || next.parkedComposer?.files?.any { it.id == id } == true) {
                    next = forgetSharedSend(next.withSendAfterUploads(null))
                }
                haptics.play(Haptic.error)
                Reduced(next)
            }
        }

        is ComposerAttachmentsFeature.Action.FileRetryTapped -> {
            val id = attachments.id
            val file = (state.pendingFiles + (state.parkedComposer?.files ?: emptyList())).firstOrNull { it.id == id }
            if (!state.supportsFileUploads || file == null || !file.isFailed) {
                Reduced(state)
            } else {
                haptics.play(Haptic.tap)
                upload(id, state)
            }
        }

        is ComposerAttachmentsFeature.Action.LocationResponse -> {
            val found = state.pendingLocation
            val parked = state.parkedComposer
            // Asked for the message: it waits there while the bar answers.
            if (attachments.result.isSuccess && parked != null && found != null) {
                Reduced(state.withParkedComposer(parked.copy(location = found)).withPendingLocation(null))
            } else {
                Reduced(state)
            }
        }

        is ComposerAttachmentsFeature.Action.FileRemoved -> {
            val index = state.pendingFiles.indexOfFirst { it.id == attachments.id }
            if (index < 0) {
                Reduced(state)
            } else {
                val file = state.pendingFiles[index]
                val next = state.withPendingFiles(state.pendingFiles.filterIndexed { i, _ -> i != index })
                val send = sendIfUploadsFinished(next)
                Reduced(
                    send.state,
                    Effect.merge(
                        ComposerAttachmentsFeature.cancelUpload(file.id, discardURL = file.localURL).map(Action::ComposerAttachments),
                        send.effect,
                    ),
                )
            }
        }
    }
}
