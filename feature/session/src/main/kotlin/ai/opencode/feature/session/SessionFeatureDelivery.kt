package ai.opencode.feature.session

// Ported by group SEND (iOS `SessionFeature+Delivery.swift`).

/** iOS `SessionFeature.FailedSend`: a send the server never took, kept dimmed until Retry, Edit or Delete. */
data class FailedSend(
    val pending: PendingPrompt,
    val reason: String,
) {
    val id: String get() = pending.messageID
}

/** iOS `SessionFeature.DeliveryNote`: why a send hasn't reached the server yet, when the reader needs to know. */
enum class DeliveryNote {
    /** An earlier send failed, and this one waits behind it. */
    waiting;

    val label: String
        get() = when (this) {
            waiting -> "Waiting to send"
        }
}

/** iOS `IdentifiedArrayOf<FailedSend>.ids`. */
val List<FailedSend>.ids: List<String> get() = map { it.id }

/** iOS `failedSends[id: id]`. */
fun List<FailedSend>.withID(id: String): FailedSend? = firstOrNull { it.id == id }
