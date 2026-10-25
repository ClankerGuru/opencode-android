package ai.opencode.server.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A session ID in a reply's Markdown, as the desktop app links them
 * (`@opencode/util/session-mention`, `markdown-session-links.ts`): a complete generated ID
 * (`ses_` and 26 letters or digits), optionally written `@session:ses_…`, as a whole inline code
 * span or inside plain text. Parsing marks it with a private `opencode-session://` link.
 *
 * The URL/ID half of iOS `Transcript/SessionLinks.swift` `SessionLink`, moved down to `:server`
 * because the link menu (`:feature:media`) reads it. The rich-text marking (`mark`, `markRuns`,
 * `marked(plain:)`, `ids(in:)`) stays with the transcript's renderer.
 */
object SessionLink {
    const val SCHEME = "opencode-session"

    private val pattern = Regex("""(?<![\w/\\:@-])(?:@session:)?(ses_[A-Za-z0-9]{26})(?![\w/\\-])""")

    /** [range] covers the whole mention, `@session:` included. */
    data class Mention(val id: String, val range: IntRange)

    fun mentions(text: String): List<Mention> {
        if (!text.contains("ses_")) return emptyList()
        return pattern.findAll(text).mapNotNull { match ->
            val id = match.groups[1]?.value ?: return@mapNotNull null
            Mention(id, match.range)
        }.toList()
    }

    /** The ID when [text] is exactly one mention (an inline code span). */
    fun exactID(text: String): String? {
        val mention = mentions(text).firstOrNull() ?: return null
        return if (mention.range.first == 0 && mention.range.last == text.lastIndex) mention.id else null
    }

    /** The private link the rendered text carries. [title] rides along for the hold menu's preview. */
    fun url(id: String, title: String? = null): String = buildString {
        append(SCHEME).append("://open?id=").append(encode(id))
        title?.let { append("&title=").append(encode(it)) }
    }

    fun id(url: String): String? {
        if (scheme(url) != SCHEME) return null
        return query(url, "id")?.let(::exactID)
    }

    fun title(url: String): String? {
        if (scheme(url) != SCHEME) return null
        return query(url, "title")
    }

    /** What a link shows until its title is known, or when it never is: `ses_ea0c…KB2l`. */
    fun shortID(id: String): String = if (id.length <= 16) id else "${id.take(8)}…${id.takeLast(4)}"

    /**
     * Whether Markdown [source] could render an ID, which always shows `s_`. Only entities (`&`)
     * and escapes (`\`) render a character the source doesn't hold, so otherwise that `s` and `_`
     * are literal, or `s` then markup Markdown drops, which starts with one of `joinsAfterS`.
     */
    fun mayRenderID(source: String): Boolean {
        var afterS = false
        for (c in source) {
            if (c == '&' || c == '\\' || (afterS && c in joinsAfterS)) return true
            afterS = c == 's'
        }
        return false
    }

    private val joinsAfterS = setOf('_', '*', '~', '`', '<', '[', ']', '!')

    private fun scheme(url: String): String? = url.substringBefore(':', "").takeIf { it.isNotEmpty() }

    private fun query(url: String, name: String): String? {
        val raw = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        return raw.split('&').firstNotNullOfOrNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts[0] != name) null
            else runCatching { URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") }.getOrNull()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
