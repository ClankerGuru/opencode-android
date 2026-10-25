package ai.opencode.feature.voice

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Port of iOS `VoiceQuietGate.swift` (`extension ReplyWatch { struct QuietGate }`).
// The type and its `note…` updates are declared in `ReplyWatch.kt` so it keeps
// the name `ReplyWatch.QuietGate`; its policy lives here.

/** The call's gate: 900 ms quiet, 1.5 s reply wait, 5 s max hold. */
val ReplyWatch.QuietGate.Companion.live: ReplyWatch.QuietGate
    get() = ReplyWatch.QuietGate(
        quietWindow = 900.milliseconds,
        replyWait = 1_500.milliseconds,
        maxHold = 5.seconds,
    )

/** iOS `VoiceCallTiming.quietGate` (VoiceCallClient.swift): the gate for this call's timing. */
val VoiceCallTiming.quietGate: ReplyWatch.QuietGate
    get() = ReplyWatch.QuietGate(
        quietWindow = liveAppendQuiet,
        replyWait = liveAppendReplyWait,
        maxHold = liveAppendMaxHold,
    )

fun ReplyWatch.QuietGate.delay(now: Duration): Duration? {
    if (quietWindow <= Duration.ZERO) return null
    if (assistantSpeaking) {
        return quietWindow
    }
    var mandatoryWait = Duration.ZERO
    lastAssistantSpeechAt?.let { spoke ->
        mandatoryWait = maxOf(mandatoryWait, quietWindow - (now - spoke))
    }
    userSpeechHold(now)?.let { userWait ->
        mandatoryWait = maxOf(mandatoryWait, userWait)
    }
    var fullWait = mandatoryWait
    lastUserSpeechAt?.let { spoke ->
        val awaitingReply = lastAssistantSpeechAt?.let { spoke > it } ?: true
        val window = if (awaitingReply) maxOf(replyWait, quietWindow) else quietWindow
        fullWait = maxOf(fullWait, window - (now - spoke))
    }
    val heldSince = heldSince
    if (maxHold > Duration.ZERO && heldSince != null) {
        val activeAssistantSpeech = assistantSpeechStartedAt?.let { maxOf(Duration.ZERO, now - it) } ?: Duration.ZERO
        val quietElapsed = maxOf(Duration.ZERO, (now - heldSince) - speakingDurationWhileHeld - activeAssistantSpeech)
        val remainingHold = maxOf(Duration.ZERO, maxHold - quietElapsed)
        fullWait = minOf(fullWait, maxOf(mandatoryWait, remainingHold))
    }
    return if (fullWait > Duration.ZERO) fullWait else null
}

/**
 * A delegation's own answer is the reply the user is owed, so it never
 * waits for a reply to start: only for speech in progress and the brief
 * pauses after it. Held, the voice agent fills the gap from older
 * results (a call answered "it was cut off" while the full answer sat
 * here for 23 s). Once the user has asked again ([pressed]), the voice
 * agent talking without the answer holds it at most [pressedHold] (zero:
 * no limit) of that talk; the user's own words always hold it.
 */
fun ReplyWatch.QuietGate.answerDelay(
    now: Duration,
    readyAt: Duration,
    pressed: Boolean,
    pressedHold: Duration,
): Duration? {
    if (quietWindow <= Duration.ZERO) return null
    val userWait = userSpeechHold(now) ?: Duration.ZERO
    var assistantWait = Duration.ZERO
    val spoke = lastAssistantSpeechAt
    if (assistantSpeaking) {
        assistantWait = quietWindow
    } else if (spoke != null) {
        assistantWait = quietWindow - (now - spoke)
    }
    if (pressed && pressedHold > Duration.ZERO && assistantSpeaking) {
        val talking = now - maxOf(readyAt, assistantSpeechRunStartedAt ?: readyAt)
        assistantWait = minOf(assistantWait, maxOf(Duration.ZERO, pressedHold - talking))
    }
    val wait = maxOf(userWait, assistantWait)
    return if (wait > Duration.ZERO) wait else null
}

/**
 * The user is speaking (their words arrived within the brief pause):
 * how much longer to hold, or null. Nothing caps this.
 */
fun ReplyWatch.QuietGate.userSpeechHold(now: Duration): Duration? {
    val spoke = lastUserSpeechAt
    if (quietWindow <= Duration.ZERO || spoke == null) return null
    val left = minOf(quietWindow, userPause) - (now - spoke)
    return if (left > Duration.ZERO) left else null
}

fun ReplyWatch.QuietGate.holdReason(now: Duration): String {
    if (assistantSpeaking) {
        return "assistant speaking"
    }
    val spoke = lastAssistantSpeechAt
    if (spoke != null && now - spoke < quietWindow) {
        return "assistant finished speaking"
    }
    return "waiting after user speech"
}
