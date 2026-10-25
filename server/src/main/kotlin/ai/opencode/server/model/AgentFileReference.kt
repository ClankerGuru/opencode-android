package ai.opencode.server.model

import kotlinx.serialization.Serializable

/**
 * A file the agent named in its reply, a tool row, or a sent attachment (iOS
 * `Media/AgentFiles/AgentFileReference.swift`). Moved down to `:server` because the transcript's
 * Markdown parser (`:feature:transcript`) and the media viewer (`:feature:media`) both need it.
 * Skeleton: the full port (kinds, link parsing, targets) lands with the Markdown parser.
 */
@Serializable
data class AgentFileReference(
    /** The path as the agent wrote it, without a `file://` prefix or `:line:col`. */
    val path: String,
    /** The 1-based line a `path:42` reference points at, for text files. */
    val line: Int? = null,
) {
    val id: String get() = path
}
