package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.session.seams.OpenCodeMarkdownBlock
import ai.opencode.feature.session.seams.TranscriptEntry
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.TranscriptNoticeKind
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant

/**
 * Port of iOS `SinceYouLeft`: one quiet 1–2 line summary row at the end of the transcript when
 * returning to a session that had unseen activity while the reader was away (`OC-420`, `D-94`).
 */
data class SinceYouLeft(
    val sessionID: String,
    val lastItemID: String,
    val text: String,
    val phase: Phase,
    val isFallback: Boolean = false,
) {
    enum class Phase(val rawValue: String) { Generating("generating"), Done("done") }

    /** Cached summary for a session's last unseen item. Stored once per `sessionID`. */
    data class CacheEntry(val lastItemID: String, val text: String)

    companion object {
        const val minimumTurnCount = 2
        /** Seconds. */
        const val minimumAbsenceDuration = 60.0
        /** ~1,200 tokens at ~4 characters per token. */
        const val maxInputCharacters = 4_800
        const val maxTokenEstimate = 1_200
        const val maxCachedSessions = 16

        const val latestUpdateCharacters = 1_200
        const val pendingRequestCharacters = 600
        const val errorCharacters = 600
        const val shortUpdateCharacters = 80

        private val repeatedSpaces = Regex("  +")

        /**
         * Cleans model output into a single quiet 1–2 line summary with no surrounding quotes,
         * markdown prefixes, or trailing period.
         */
        fun sanitize(raw: String, trimTrailingPeriod: Boolean): String {
            var cleaned = raw
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace(repeatedSpaces, " ")
                .trim()
            if (cleaned.startsWith("\"")) cleaned = cleaned.drop(1)
            if (trimTrailingPeriod && cleaned.endsWith("\"")) cleaned = cleaned.dropLast(1)
            cleaned = cleaned.trim()
            if (trimTrailingPeriod && cleaned.endsWith(".") && !cleaned.endsWith("...")) cleaned = cleaned.dropLast(1)
            return cleaned
        }

        /**
         * The model's first non-empty line: it sometimes adds a second sentence on its own line,
         * which reads as a run-on once newlines become spaces.
         */
        fun firstLine(text: String): String =
            text.split('\n', '\r').firstOrNull { it.isNotEmpty() && it.trim(' ', '\t').isNotEmpty() } ?: ""

        /** Cumulative word-by-word prefixes of [text] so summaries stream one word at a time. */
        fun wordPrefixes(text: String): List<String> {
            val trimmed = sanitize(text, trimTrailingPeriod = false)
            if (trimmed.isEmpty()) return emptyList()
            val words = trimmed.split(' ').filter { it.isNotEmpty() }
            if (words.size <= 1) return listOf(trimmed)
            return (1..words.size).map { count -> words.take(count).joinToString(" ") }
        }

        /**
         * Emits cumulative word-by-word steps between [previous] and [current] when a model
         * snapshot jumps by multiple words at once.
         */
        fun wordSteps(previous: String, current: String): List<String> {
            val all = wordPrefixes(current)
            if (previous.isEmpty()) return all
            val filtered = all.filter { it.length > previous.length }
            return filtered.ifEmpty { listOf(current) }
        }

        /** One unseen turn: the prompt that started it and the agent's messages with prose. */
        private class Turn(var ask: String? = null, val replies: MutableList<List<OpenCodeMarkdownBlock>> = mutableListOf())

        /**
         * Builds bounded input from unseen items after the contiguous [seen] prefix. Returns null
         * when there are no unseen items, when the absence had fewer than 2 turns, or when
         * [leftAt] is less than 1 minute before [now]. [pendingRequests] are short descriptions
         * from [pendingRequestLines].
         */
        fun buildInput(
            sessionID: String,
            items: List<OpenCodeTranscriptItem>,
            seen: Set<String>,
            ownSentPromptIDs: Set<String> = emptySet(),
            leftAt: Instant? = null,
            now: Instant = Instant.now(),
            hasPendingRequest: Boolean = false,
            pendingRequests: List<String> = emptyList(),
            ignoreMinimumAbsenceDuration: Boolean = false,
        ): SinceYouLeftInput? {
            if (seen.isEmpty() || items.isEmpty()) return null
            val lastSeen = items.indexOfLast { it.id in seen }
            val afterSeen = if (lastSeen >= 0) lastSeen + 1 else 0
            if (afterSeen >= items.size) return null

            if (!ignoreMinimumAbsenceDuration && leftAt != null) {
                val absence = Duration.between(leftAt, now).toMillis() / 1000.0
                if (absence < minimumAbsenceDuration) return null
            }

            val agentPrompts = items.mapNotNull { item ->
                if (item is OpenCodeTranscriptItem.UserText && item.source != null) item.id else null
            }.toSet()
            val isOwn = { item: OpenCodeTranscriptItem ->
                val promptID = TranscriptEntry.promptID(item)
                promptID != null && promptID in ownSentPromptIDs && promptID !in agentPrompts
            }

            val unseen = items.subList(afterSeen, items.size).filter { item ->
                if (item.id in seen || item.id.startsWith("info-") || isOwn(item)) return@filter false
                when (item) {
                    is OpenCodeTranscriptItem.SendFailure, is OpenCodeTranscriptItem.RunRetry, is OpenCodeTranscriptItem.Undone -> false
                    else -> true
                }
            }
            val lastItem = unseen.lastOrNull() ?: return null

            var assistantCount = 0
            var userCount = 0
            val toolMessageIDs = HashSet<String>()
            val editedFiles = mutableListOf<String>()
            val editedFilesSeen = HashSet<String>()
            val turns = mutableListOf(Turn())
            val errors = mutableListOf<String>()
            var failuresSinceLastReply = 0

            for (item in unseen) {
                when (item) {
                    is OpenCodeTranscriptItem.UserText -> {
                        userCount += 1
                        var ask = item.text.trim()
                        if (ask.startsWith(OpenCodePromptSource.SUBAGENT_PREAMBLE)) {
                            ask = ask.drop(OpenCodePromptSource.SUBAGENT_PREAMBLE.length)
                        }
                        val compact = compactSnippet(ask, maxLength = 160)
                        if (turns.last().ask != null || turns.last().replies.isNotEmpty()) turns += Turn()
                        turns.last().ask = compact.ifEmpty { null }
                    }
                    is OpenCodeTranscriptItem.AssistantMarkdown -> {
                        assistantCount += 1
                        val blocks = OpenCodeMarkdownBlock.blocks(item.text)
                        if (blocks.any { proseText(it) != null }) {
                            turns.last().replies += blocks
                            failuresSinceLastReply = 0
                        }
                    }
                    is OpenCodeTranscriptItem.Tool -> {
                        val call = item.call
                        call.messageID?.takeIf { it.isNotEmpty() }?.let(toolMessageIDs::add)
                        if (call.status == OpenCodeToolCall.Status.Error && call.errorType != "permission.rejected") {
                            failuresSinceLastReply += 1
                        }
                        editedFileName(call)?.let { if (editedFilesSeen.add(it)) editedFiles += it }
                    }
                    is OpenCodeTranscriptItem.Notice -> {
                        // Shell notices name commands; only their failure counts.
                        if (item.kind?.source == TranscriptNoticeKind.Source.Shell) {
                            if (item.isError) failuresSinceLastReply += 1
                        } else if (item.isError) {
                            val compact = compactSnippet(item.text, maxLength = 160)
                            if (compact.isNotEmpty()) errors += compact
                        }
                    }
                    is OpenCodeTranscriptItem.RunError -> {
                        val compact = compactSnippet(item.error.message, maxLength = 200)
                        if (compact.isNotEmpty()) errors += compact
                    }
                    is OpenCodeTranscriptItem.UserImages, is OpenCodeTranscriptItem.UserFiles,
                    is OpenCodeTranscriptItem.Compaction, is OpenCodeTranscriptItem.Info,
                    is OpenCodeTranscriptItem.ModelSwitch, is OpenCodeTranscriptItem.SendFailure,
                    is OpenCodeTranscriptItem.RunRetry, is OpenCodeTranscriptItem.Undone -> Unit
                }
            }

            val messageTurns = maxOf(assistantCount, userCount)
            val turnCount = if (messageTurns > 0) messageTurns else toolMessageIDs.size
            if (turnCount < minimumTurnCount) return null

            val sections = mutableListOf<String>()

            // What waits on the reader comes first: the on-device model drops asks that follow
            // the update (docs/since-you-left-eval.md).
            if (pendingRequests.isNotEmpty()) {
                val lines = bounded(pendingRequests.map { "- $it" }, pendingRequestCharacters)
                sections += "Needs the developer:\n${lines.joinToString("\n")}"
            } else if (hasPendingRequest) {
                sections += "Needs the developer: a pending request"
            }

            val latestIndex = turns.indexOfLast { it.replies.isNotEmpty() }.takeIf { it >= 0 }
            // A thin last message ("Tests pass.") leans on the one before it.
            var latestReplyCount = 1
            if (latestIndex != null) {
                val replies = turns[latestIndex].replies
                var update = proseHighlights(replies.last(), maxBlocks = 4, maxLength = latestUpdateCharacters)
                if (update.length < shortUpdateCharacters && replies.size > 1) {
                    val previous = proseHighlights(replies[replies.size - 2], maxBlocks = 2, maxLength = latestUpdateCharacters - update.length - 1)
                    update = "$previous\n$update"
                    latestReplyCount = 2
                }
                if (failuresSinceLastReply > 0) update += "\nThen it failed."
                sections += "The agent's latest update:\n$update"
            }

            val problems = errors.takeLast(3).map { "- $it" }.toMutableList()
            if (latestIndex == null && failuresSinceLastReply > 0) problems += "- The last step failed"
            if (problems.isNotEmpty()) {
                sections += "Problems:\n${bounded(problems, errorCharacters).joinToString("\n")}"
            }

            if (latestIndex == null && editedFiles.isNotEmpty()) {
                sections += "Files edited: ${editedFiles.take(6).joinToString(", ")}"
            }

            // Earlier context, newest kept first when the budget runs out: each turn's ask and
            // final words, and everything the agent said in the latest turn.
            val context = mutableListOf<String>()
            for ((index, turn) in turns.withIndex()) {
                turn.ask?.let { context += "- Asked: $it" }
                val earlier = if (index == latestIndex) turn.replies.dropLast(latestReplyCount) else turn.replies.takeLast(1)
                for (blocks in earlier) {
                    context += "- Agent: ${proseHighlights(blocks, maxBlocks = 1, maxLength = 240)}"
                }
            }
            val head = sections.joinToString("\n\n")
            val contextTitle = "Earlier, oldest first:"
            val contextBudget = maxInputCharacters - head.length - contextTitle.length - 3
            val keptContext = mutableListOf<String>()
            var usedChars = 0
            for (line in context.asReversed()) {
                val cost = line.length + 1
                if (usedChars + cost > contextBudget) break
                keptContext += line
                usedChars += cost
            }
            keptContext.reverse()
            if (keptContext.isNotEmpty()) sections += "$contextTitle\n${keptContext.joinToString("\n")}"

            if (sections.isEmpty()) sections += "The agent worked without writing an update"
            val fullPrompt = sections.joinToString("\n\n")
            val boundedPrompt = if (fullPrompt.length <= maxInputCharacters) fullPrompt else fullPrompt.take(maxInputCharacters)

            return SinceYouLeftInput(
                sessionID = sessionID,
                lastItemID = lastItem.id,
                turnCount = turnCount,
                editedFiles = editedFiles.toList(),
                hasPendingRequest = hasPendingRequest || pendingRequests.isNotEmpty(),
                prompt = boundedPrompt,
            )
        }

        /**
         * Short, content-free descriptions of the session's pending requests: what a permission
         * wants to do (never its commands or paths) and a form's questions.
         */
        fun pendingRequestLines(
            permissions: List<OpenCodePermissionRequest>,
            forms: List<OpenCodeFormRequest>,
            sessionID: String,
        ): List<String> {
            val permissionLines = permissions.filter { it.sessionID == sessionID }.map { "Permission to ${permissionPhrase(it.action)}" }
            val formLines = forms.filter { it.sessionID == sessionID }.mapNotNull { form ->
                val questions = form.fields.take(2).mapNotNull { field ->
                    (field.title ?: field.description)?.let { compactSnippet(it, maxLength = 160) }?.ifEmpty { null }
                }
                val title = compactSnippet(form.title, maxLength = 80)
                if (questions.isEmpty()) {
                    if (title.isEmpty()) null else "Answer: $title"
                } else {
                    (if (form.isQuestion) "Question: " else "Form: ") + questions.joinToString(" / ")
                }
            }
            return formLines + permissionLines
        }

        private fun permissionPhrase(action: String): String = when (action.lowercase()) {
            "bash" -> "run a command"
            "edit", "write", "patch", "multiedit", "apply_patch" -> "edit files"
            "read" -> "read files"
            "webfetch", "websearch" -> "use the web"
            "external_directory" -> "use a folder outside the project"
            "task" -> "start a subagent"
            else -> action.replace("_", " ")
        }

        private fun bounded(lines: List<String>, limit: Int): List<String> {
            val kept = mutableListOf<String>()
            var used = 0
            for (line in lines) {
                val cost = line.length + 1
                if (used + cost > limit) break
                kept += line
                used += cost
            }
            return kept
        }

        private fun compactSnippet(text: String, maxLength: Int): String {
            val singleLine = text
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace(repeatedSpaces, " ")
                .trim()
            if (singleLine.length <= maxLength) return singleLine
            return singleLine.take(maxLength - 1).trim(' ', '\t') + "…"
        }

        /**
         * A prose block's plain text: paragraphs, list items, and quotes. Headings, fenced code,
         * diffs, tables, math, and file cards are never prose.
         */
        private fun proseText(block: OpenCodeMarkdownBlock): String? {
            val text = when (val kind = block.kind) {
                is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text
                is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text
                is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text
                is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text
                else -> return null
            }
            return compactSnippet(text, maxLength = 400).ifEmpty { null }
        }

        /**
         * The message's opening prose block (usually its conclusion) and its last ones (usually
         * the ask or next step), in reading order, within [maxLength].
         */
        private fun proseHighlights(blocks: List<OpenCodeMarkdownBlock>, maxBlocks: Int, maxLength: Int): String {
            // Lead-ins to code ("Here is the change:") say nothing on their own.
            val allProse = blocks.mapNotNull(::proseText)
            val leadless = allProse.filter { !it.endsWith(":") }
            val prose = leadless.ifEmpty { allProse }
            val first = prose.firstOrNull() ?: return ""
            if (maxBlocks <= 1 || prose.size == 1) {
                return compactSnippet(if (maxBlocks <= 1) prose.last() else first, maxLength)
            }
            val tail = prose.drop(1).takeLast(maxBlocks - 1)
            var used = first.length
            val tailKept = ArrayDeque<String>()
            for (line in tail.asReversed()) {
                if (used + line.length + 1 > maxLength) break
                tailKept.addFirst(line)
                used += line.length + 1
            }
            val joined = (listOf(first) + tailKept).joinToString("\n")
            if (joined.length <= maxLength) return joined
            return joined.take(maxLength - 1).trim(' ', '\t') + "…"
        }

        private val editTools = setOf("edit", "write", "patch", "multiedit", "apply_patch")

        /** Extracts only the file's basename for file-mutating tools, never file contents. */
        private fun editedFileName(call: OpenCodeToolCall): String? {
            if (call.name.lowercase() !in editTools) return null
            val values = call.input.obj ?: return null
            for (key in listOf("path", "filePath", "file_path")) {
                val raw = values[key]?.string?.trim()
                if (!raw.isNullOrEmpty()) {
                    val trimmed = raw.trimEnd('/')
                    return if (trimmed.isEmpty()) "/" else trimmed.substringAfterLast('/')
                }
            }
            return null
        }
    }
}

/**
 * Port of iOS `SinceYouLeftInput`: bounded, compact summary input built from unseen transcript
 * items (OC-488). Leads with what the agent said, newest first, then pending requests and
 * errors. Shells and tool calls are never listed: at most one line says a command failed after
 * the agent's last words. Never includes file contents, diffs, code blocks, commands, or raw
 * tool outputs.
 */
data class SinceYouLeftInput(
    val sessionID: String,
    val lastItemID: String,
    val turnCount: Int,
    val editedFiles: List<String>,
    val hasPendingRequest: Boolean,
    val prompt: String,
) {
    val estimatedTokens: Int get() = minOf(SinceYouLeft.maxTokenEstimate, (prompt.length + 3) / 4)

    /**
     * Plain structural fallback line when no on-device model is available, e.g.
     * `"3 turns · 4 files edited"`.
     */
    val structuralFallback: String
        get() {
            val parts = mutableListOf("$turnCount ${if (turnCount == 1) "turn" else "turns"}")
            if (editedFiles.isNotEmpty()) {
                val count = editedFiles.size
                parts += "$count ${if (count == 1) "file" else "files"} edited"
            }
            if (hasPendingRequest) parts += "waiting on you"
            return parts.joinToString(" · ")
        }
}

/**
 * Port of iOS `SinceYouLeftClient` (`@Dependency(\.sinceYouLeft)`).
 *
 * Android: there is no system on-device language model equivalent to Apple's Foundation Models
 * in this app's dependency set, so the live client is [unavailable] and callers show
 * [SinceYouLeftInput.structuralFallback], as iOS does on devices without Apple Intelligence.
 * [systemInstructions] is kept so a future on-device model uses the same tuned prompt.
 */
class SinceYouLeftClient(
    val isAvailable: () -> Boolean = { false },
    val summarize: (input: SinceYouLeftInput) -> Flow<String> = { emptyFlow() },
) {
    companion object Key : DependencyKey<SinceYouLeftClient> {
        /**
         * Tuned against the on-device model in docs/since-you-left-eval.md: no style examples (the
         * model copies them), asks gated on the input's own section.
         */
        val systemInstructions = """
            You write the one-line note a developer sees when they come back to a coding session.
            Say in plain words what the agent got done, found, or is doing now, based mostly on the agent's latest update.
            If the input has a "Needs the developer" section, or the latest update asks the developer something, start with "Needs your", like "Needs your OK to deploy the board fix". Otherwise never say the developer is needed.
            Never mention commands, shells, terminals, tools, or reading files.
            One sentence, at most 20 words, sentence case, no trailing period. Don't start with "The agent". No quotes or markdown.
        """.trimIndent()

        fun canned(chunks: List<String>, delayMilliseconds: Long = 0): SinceYouLeftClient = SinceYouLeftClient(
            isAvailable = { true },
            summarize = {
                flow {
                    for ((index, chunk) in chunks.withIndex()) {
                        if (delayMilliseconds > 0) delay(if (index == 0) minOf(delayMilliseconds, 16) else delayMilliseconds)
                        emit(sanitize(chunk))
                    }
                }
            },
        )

        fun canned(summary: String): SinceYouLeftClient = canned(listOf(summary))

        val unavailable = SinceYouLeftClient(isAvailable = { false }, summarize = { emptyFlow() })

        override val liveValue: SinceYouLeftClient get() = unavailable
        override val testValue: SinceYouLeftClient get() = unavailable

        private fun sanitize(chunk: String) = SinceYouLeft.sanitize(chunk, trimTrailingPeriod = false)
    }
}

/**
 * Port of iOS `SinceYouLeftRow`: the quiet 1–2 line summary row at the end of the transcript
 * (`OC-420`, `D-94`). Matches the transcript's type and spacing, grows as words stream in, and
 * dismisses on send or ✕.
 *
 * Deviation: iOS reveals streamed words with the transcript's `ArrivingText`; until
 * :feature:transcript exposes it, the text grows in with an animated size change.
 *
 * @param entryID the transcript entry's ID (iOS keys the text reveal by it).
 */
@Composable
fun SinceYouLeftRow(entryID: String, summary: SinceYouLeft, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val isAccessibilitySize = LocalConfiguration.current.fontScale >= 1.8f
    val lineCap = if (isAccessibilitySize) Int.MAX_VALUE else if (summary.isFallback) 1 else 2
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Since you left",
                style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
            )
            HorizontalDivider(Modifier.weight(1f).clearAndSetSemantics {}, thickness = 0.5.dp, color = OpenCodeTheme.colors.border)
            Box(
                Modifier
                    .size(48.dp)
                    .padding(start = 0.dp)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics {
                        contentDescription = "Dismiss summary"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText)
            }
        }
        Text(
            summary.text,
            style = OpenCodeTextStyles.subheadline.copy(lineHeight = OpenCodeTextStyles.subheadline.lineHeight),
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = lineCap,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize(Motion.settle.spec()))
                .semantics {
                    stateDescription = summary.text
                    customActions = listOf(CustomAccessibilityAction("Dismiss") { onDismiss(); true })
                },
        )
    }
}
