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
    /** The first 16 bytes of the source's SHA-256, hex. */
    fun hash(source: String): String =
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

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
