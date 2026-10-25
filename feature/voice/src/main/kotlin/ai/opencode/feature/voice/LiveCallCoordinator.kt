package ai.opencode.feature.voice

/**
 * The GPT-Live call coordinator (iOS `LiveCallCoordinator.swift`, `+Appends`, `+Events`).
 * TODO(voice wave 3): the coordinator itself. Only the constants other foundation types
 * read live here for now (from `LiveCallCoordinator+Appends.swift`).
 */
class LiveCallCoordinator {
    companion object {
        const val claimLeadPrefix = "Result of the request you acknowledged with “"
        const val earlierConnectionLead = "Result of an earlier request you delegated: "
    }
}
