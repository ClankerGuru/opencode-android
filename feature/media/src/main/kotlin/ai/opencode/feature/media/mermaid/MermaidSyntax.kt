package ai.opencode.feature.media

import java.security.MessageDigest
import kotlin.math.roundToInt

/** Port of iOS `MermaidTheme` (`Media/Mermaid/MermaidSyntax.swift`). */
enum class MermaidTheme(val rawValue: String) {
    Light("light"),
    Dark("dark"),
}

/**
 * What a rendered diagram is cached under: its source, its theme, and the width it was drawn
 * for (0 for the diagram itself, before any drawing). Port of iOS `MermaidCacheKey`; [width] is
 * in dp, rounded like iOS points.
 */
data class MermaidCacheKey private constructor(
    val sourceHash: String,
    val theme: MermaidTheme,
    val width: Int,
) {
    constructor(source: String, theme: MermaidTheme, width: Float = 0f) :
        this(MermaidSyntax.hash(source), theme, width.roundToInt())

    val diagram: MermaidCacheKey get() = MermaidCacheKey(sourceHash, theme, 0)

    val string: String get() = "$sourceHash|${theme.rawValue}|$width"
}

/** Port of iOS `MermaidSyntax`. */
object MermaidSyntax {
    /** Hashes by source; emptied when full. */
    private val hashes = HashMap<String, String>()

    /** SHA-256s worked out, for tests. */
    @Volatile
    var hashCount = 0
        private set

    /**
     * The first 16 bytes of the source's SHA-256, hex, worked out once per source: a card asks
     * for its keys on every recomposition.
     */
    fun hash(source: String): String {
        synchronized(hashes) {
            hashes[source]?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (index in 0 until 16) {
            val byte = digest[index].toInt() and 0xFF
            hex.append(HEX_DIGITS[byte shr 4])
            hex.append(HEX_DIGITS[byte and 0x0F])
        }
        val hash = hex.toString()
        synchronized(hashes) {
            if (hashes.size >= 128) hashes.clear()
            hashes[source] = hash
            hashCount += 1
        }
        return hash
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * The diagram's kind from its first statement, for TalkBack: “Flowchart”, “Sequence
     * diagram”. Null when the source doesn't name a known kind.
     */
    fun diagramType(source: String): String? {
        var inFrontMatter = false
        for ((index, line) in source.split("\n").withIndex()) {
            val trimmed = line.trim(' ', '\t')
            if (trimmed == "---") {
                if (index == 0 || inFrontMatter) {
                    inFrontMatter = !inFrontMatter
                    continue
                }
            }
            if (inFrontMatter || trimmed.isEmpty() || trimmed.startsWith("%%")) continue
            val keyword = trimmed.takeWhile { !it.isWhitespace() && it != ';' && it != ':' && it != '{' }
            return names[keyword.lowercase()]
        }
        return null
    }

    private val names: Map<String, String> = mapOf(
        "flowchart" to "Flowchart", "flowchart-elk" to "Flowchart", "graph" to "Flowchart",
        "sequencediagram" to "Sequence diagram",
        "classdiagram" to "Class diagram", "classdiagram-v2" to "Class diagram",
        "statediagram" to "State diagram", "statediagram-v2" to "State diagram",
        "erdiagram" to "Entity relationship diagram",
        "journey" to "User journey",
        "gantt" to "Gantt chart",
        "pie" to "Pie chart",
        "quadrantchart" to "Quadrant chart",
        "requirementdiagram" to "Requirement diagram",
        "gitgraph" to "Git graph",
        "c4context" to "C4 diagram", "c4container" to "C4 diagram", "c4component" to "C4 diagram",
        "c4dynamic" to "C4 diagram", "c4deployment" to "C4 diagram",
        "mindmap" to "Mind map",
        "timeline" to "Timeline",
        "zenuml" to "Sequence diagram",
        "sankey" to "Sankey diagram", "sankey-beta" to "Sankey diagram",
        "xychart" to "XY chart", "xychart-beta" to "XY chart",
        "block" to "Block diagram", "block-beta" to "Block diagram",
        "packet" to "Packet diagram", "packet-beta" to "Packet diagram",
        "kanban" to "Kanban board",
        "architecture" to "Architecture diagram", "architecture-beta" to "Architecture diagram",
        "radar-beta" to "Radar chart",
        "treemap-beta" to "Treemap",
    )
}
