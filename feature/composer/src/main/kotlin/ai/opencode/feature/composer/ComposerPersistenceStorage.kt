package ai.opencode.feature.composer

import ai.opencode.core.log.AppLog
import java.io.File
import java.util.UUID

/**
 * The bytes behind [ComposerPersistence] (iOS `ComposerPersistenceStorage`): the state file
 * and one blob per inline attachment.
 *
 * Live storage sits in `filesDir/support/` (iOS Application Support/OpenCode), like
 * `TranscriptCachePersistence`: `composer-state.json` and `ComposerAttachments/<uuid>`. Android
 * app-private files are already encrypted at rest (file-based encryption, credential-encrypted
 * storage = iOS `completeUntilFirstUserAuthentication`), so there is no per-file protection
 * attribute; iOS's `isExcludedFromBackup` is the app's backup rules excluding
 * `support/composer-state.json` and `support/ComposerAttachments/` (owned by `:app`).
 */
class ComposerPersistenceStorage(
    val identity: String,
    val readStateData: () -> ByteArray?,
    val writeStateData: (ByteArray?) -> Unit,
    val readAttachment: (UUID) -> ByteArray?,
    /** Writes [writes], then removes every blob not in `keeping`. */
    val writeAttachments: (writes: Map<UUID, ByteArray>, keeping: Set<UUID>) -> Unit,
    val removeAll: () -> Unit,
) {
    companion object {
        const val STATE_FILE_NAME = "composer-state.json"
        const val ATTACHMENTS_DIRECTORY_NAME = "ComposerAttachments"

        fun live(baseDirectory: File): ComposerPersistenceStorage {
            val stateFile = File(baseDirectory, STATE_FILE_NAME)
            val attachmentsDirectory = File(baseDirectory, ATTACHMENTS_DIRECTORY_NAME)
            return ComposerPersistenceStorage(
                identity = baseDirectory.path,
                readStateData = { runCatching { stateFile.takeIf(File::exists)?.readBytes() }.getOrNull() },
                writeStateData = { data ->
                    if (data == null) {
                        stateFile.delete()
                    } else {
                        try {
                            writeAtomically(stateFile, data)
                        } catch (error: Exception) {
                            AppLog.app.error { "Couldn’t write composer state: ${error::class.java.simpleName}" }
                        }
                    }
                },
                readAttachment = { id -> runCatching { File(attachmentsDirectory, id.toString()).takeIf(File::exists)?.readBytes() }.getOrNull() },
                writeAttachments = { writes, keeping ->
                    if (writes.isNotEmpty()) {
                        attachmentsDirectory.mkdirs()
                        for ((id, data) in writes) {
                            try {
                                writeAtomically(File(attachmentsDirectory, id.toString()), data)
                            } catch (error: Exception) {
                                AppLog.app.error { "Couldn’t write composer attachment (${data.size} bytes): ${error::class.java.simpleName}" }
                            }
                        }
                    }
                    attachmentsDirectory.listFiles()?.let { files ->
                        for (file in files) {
                            val id = runCatching { UUID.fromString(file.name) }.getOrNull()
                            if (id != null && id in keeping) continue
                            file.delete()
                        }
                        if (keeping.isEmpty()) attachmentsDirectory.delete()
                    }
                },
                removeAll = {
                    stateFile.delete()
                    attachmentsDirectory.deleteRecursively()
                },
            )
        }

        fun inMemory(): ComposerPersistenceStorage {
            val lock = Any()
            var stateData: ByteArray? = null
            val attachments = HashMap<UUID, ByteArray>()
            return ComposerPersistenceStorage(
                identity = "in-memory-${UUID.randomUUID()}",
                readStateData = { synchronized(lock) { stateData } },
                writeStateData = { data -> synchronized(lock) { stateData = data } },
                readAttachment = { id -> synchronized(lock) { attachments[id] } },
                writeAttachments = { writes, keeping ->
                    synchronized(lock) {
                        attachments.putAll(writes)
                        attachments.keys.retainAll(keeping)
                    }
                },
                removeAll = {
                    synchronized(lock) {
                        stateData = null
                        attachments.clear()
                    }
                },
            )
        }

        private fun writeAtomically(target: File, data: ByteArray) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeBytes(data)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw java.io.IOException("rename failed")
            }
        }
    }
}
