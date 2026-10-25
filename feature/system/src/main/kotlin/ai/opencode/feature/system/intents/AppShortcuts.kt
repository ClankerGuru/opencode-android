package ai.opencode.feature.system.intents

import ai.opencode.feature.system.SessionDirectory
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeDelivery

/** Port of iOS `DeliveryOption` (Ask OpenCode's Delivery parameter). */
enum class DeliveryOption(val title: String, val symbol: SFSymbol, val delivery: OpenCodeDelivery) {
    Steer("Steer", SFSymbol.ArrowTurnUpRight, OpenCodeDelivery.Steer),
    Queue("Queue", SFSymbol.TextBadgePlus, OpenCodeDelivery.Queue),
    ;

    companion object {
        const val TYPE_DISPLAY_NAME = "Delivery"
    }
}

/** Port of iOS `AskOpenCodeIntent`: sends a typed or dictated message to a session without opening the app. */
object AskOpenCodeIntent {
    const val TITLE = "Ask OpenCode"
    const val DESCRIPTION = "Sends a message to an OpenCode session without opening the app."
    const val MESSAGE_TITLE = "Message"
    const val REQUEST_VALUE_DIALOG = "What should OpenCode do?"
    const val SESSION_TITLE = "Session"
    val defaultDelivery = DeliveryOption.Steer
}

/**
 * Port of iOS `OpenCodeShortcuts` (App Shortcuts: Siri and Spotlight without setup). Android
 * has no phrase-based shortcut provider; each maps to a door in `res/xml/shortcuts.xml`:
 *
 * - Ask OpenCode → [AskOpenCodeActivity] (App Action `actions.intent.CREATE_MESSAGE`).
 * - Start Voice Call, Background Call, New Session, Open Session → App Action
 *   `actions.intent.OPEN_APP_FEATURE` into [SystemActionActivity], whose feature name
 *   [feature] reads back; and the Start Call / Open Session / New Session tiles and widgets.
 *
 * The phrases are kept as the App Action features' names.
 */
object OpenCodeShortcuts {
    data class AppShortcut(val shortTitle: String, val symbol: SFSymbol, val phrases: List<String>)

    val appShortcuts = listOf(
        AppShortcut("Ask OpenCode", SFSymbol.TextBubble, listOf("Ask OpenCode", "Send a message to OpenCode", "Tell OpenCode")),
        AppShortcut(
            "Start Voice Call", SFSymbol.Waveform,
            listOf("Start a voice call in OpenCode", "Call OpenCode", "Talk to OpenCode", "Call OpenCode about <session>"),
        ),
        AppShortcut("Background Call", SFSymbol.ButtonProgrammable, listOf("Start a background call in OpenCode")),
        AppShortcut(
            "New Session", SFSymbol.SquareAndPencil,
            listOf("New OpenCode session", "Start a new session in OpenCode", "New OpenCode session in <project>"),
        ),
        AppShortcut("Open Session", SFSymbol.BubbleLeft, listOf("Open <session> in OpenCode", "Show <session> in OpenCode")),
    )

    sealed interface Feature {
        data object StartVoiceCall : Feature
        data object BackgroundCall : Feature
        data class NewSession(val project: ProjectEntity?) : Feature
        data class OpenSession(val session: SessionEntity) : Feature
    }

    /**
     * Reads an App Action's spoken feature: a call, a background call, a new session
     * (optionally “in <project>”), or a session's name (“Open <session>”), resolved from the
     * directory as Siri resolves an entity.
     */
    fun feature(spoken: String, directory: SessionDirectory): Feature? {
        val text = spoken.trim().lowercase()
        if (text.isEmpty()) return null
        if ("background" in text && "call" in text) return Feature.BackgroundCall
        val aboutSession = Regex("^(?:call|talk to)\\b.*\\babout (.+)$").find(text)?.groupValues?.get(1)
        if (aboutSession != null) {
            directory.sessionEntities(aboutSession).firstOrNull()?.let { return Feature.OpenSession(it) }
        }
        if (text == "call" || text.contains("voice call") || text.startsWith("call") || text.startsWith("talk")) {
            return Feature.StartVoiceCall
        }
        if (text.contains("new session") || text == "new") {
            val project = Regex("\\bin (.+)$").find(text)?.groupValues?.get(1)
                ?.let { directory.projectEntities(it).firstOrNull() }
            return Feature.NewSession(project)
        }
        val name = text.removePrefix("open ").removePrefix("show ").trim()
        return directory.sessionEntities(name).firstOrNull()?.let(Feature::OpenSession)
    }
}
