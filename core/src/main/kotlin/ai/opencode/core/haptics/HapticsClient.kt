package ai.opencode.core.haptics

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Port of iOS `Haptic` (`OpenCode/Core/HapticsClient.swift`): every haptic the app plays,
 * named by what it means. Same vocabulary, same rules:
 *
 * - [tap] a control acted (Send, Copy, mic press, a menu opening). Copies play `tap`.
 * - [selection] a choice changed (toggle, picker, pin, mute, read, a row reached by sliding).
 * - [confirm] a firm commit (start a call, confirmed delete, Stop Shell).
 * - [cancel] backing out (End, Close, cancel dictation, Stop).
 * - [threshold] a drag crossed its act point (Catch Up card, held mic's lock/cancel).
 * - [snap] the session drawer committed to opening or closing.
 * - [attention] a request docked (top screen only, not during a call).
 * - [success], [warning], [error].
 *
 * Answer the finger on the press, not the animation. Navigation (except opening the session
 * sheet from the title, which taps) and closing stay quiet; no-ops play nothing. Play only
 * through [HapticsClient] (reducers) or [HapticEffect] (composables); never touch
 * `Vibrator` or `View.performHapticFeedback` elsewhere.
 */
enum class Haptic {
    tap, selection, confirm, cancel, threshold, snap, attention, success, warning, error;

    /** How each feels. iOS's generator/intensity table, mapped to Android composition primitives. */
    val feedback: HapticFeedback
        get() = when (this) {
            tap -> HapticFeedback.Impact(HapticFeedback.Style.Light)
            selection -> HapticFeedback.Selection
            confirm -> HapticFeedback.Impact(HapticFeedback.Style.Medium, 0.75f)
            cancel -> HapticFeedback.Impact(HapticFeedback.Style.Soft, 0.85f)
            threshold -> HapticFeedback.Impact(HapticFeedback.Style.Light)
            snap -> HapticFeedback.Impact(HapticFeedback.Style.Soft, 0.7f)
            attention -> HapticFeedback.Impact(HapticFeedback.Style.Rigid, 0.7f)
            success -> HapticFeedback.Notification(HapticFeedback.Kind.Success)
            warning -> HapticFeedback.Notification(HapticFeedback.Kind.Warning)
            error -> HapticFeedback.Notification(HapticFeedback.Kind.Error)
        }
}

/** iOS's `HapticFeedback`: which UIKit generator and how strongly. */
sealed interface HapticFeedback {
    enum class Style { Light, Medium, Heavy, Soft, Rigid }
    enum class Kind { Success, Warning, Error }

    data object Selection : HapticFeedback
    data class Impact(val style: Style, val intensity: Float = 1f) : HapticFeedback
    data class Notification(val kind: Kind) : HapticFeedback
}

/** Port of `HapticsClient` (`@Dependency(\.haptics)`). Voice call cues live in `:feature:voice`. */
class HapticsClient(
    val play: (Haptic) -> Unit = {},
    /** A touch that may end in this haptic began (iOS warms its generator; Android needs nothing). */
    val prepare: (Haptic) -> Unit = {},
) {
    companion object Key : DependencyKey<HapticsClient> {
        override val liveValue: HapticsClient by lazy {
            val live = LiveHaptics(dependency(AppContext))
            HapticsClient(play = live::play, prepare = {})
        }
        override val testValue: HapticsClient = HapticsClient()
    }
}

/**
 * The views' way into the vocabulary (iOS `.haptic(_:trigger:condition:)`): plays [haptic]
 * when [trigger] changes and [condition] holds.
 */
@Composable
fun <T> HapticEffect(haptic: Haptic, trigger: T, condition: (old: T, new: T) -> Boolean = { _, _ -> true }) {
    val haptics = remember { dependency(HapticsClient) }
    val previous = remember { arrayOfNulls<Any?>(1).also { it[0] = trigger } }
    LaunchedEffect(trigger) {
        @Suppress("UNCHECKED_CAST")
        val old = previous[0] as T
        previous[0] = trigger
        if (old != trigger && condition(old, trigger)) haptics.play(haptic)
    }
}

/**
 * The app's only vibrator user. Plays nothing while the app isn't in the foreground
 * (requests and notices also arrive in the background) or when the system's touch
 * feedback setting is off (iOS's System Haptics switch).
 */
private class LiveHaptics(context: Context) {
    private val context = context.applicationContext
    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    private val primitives = vibrator.areAllPrimitivesSupported(
        Composition.PRIMITIVE_CLICK,
        Composition.PRIMITIVE_TICK,
        Composition.PRIMITIVE_LOW_TICK,
    )

    fun play(haptic: Haptic) {
        if (!isActive() || !systemHapticsEnabled() || !vibrator.hasVibrator()) return
        runCatching { vibrator.vibrate(effect(haptic.feedback)) }
            .onFailure { AppLog.app.error { "Haptic ${haptic.name} failed: ${it::class.java.simpleName}" } }
    }

    private fun isActive(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun systemHapticsEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun effect(feedback: HapticFeedback): VibrationEffect {
        if (!primitives) return fallback(feedback)
        val composition = VibrationEffect.startComposition()
        when (feedback) {
            HapticFeedback.Selection -> composition.addPrimitive(Composition.PRIMITIVE_TICK, 0.6f)
            is HapticFeedback.Impact -> {
                val (primitive, base) = when (feedback.style) {
                    HapticFeedback.Style.Light -> Composition.PRIMITIVE_CLICK to 0.5f
                    HapticFeedback.Style.Medium -> Composition.PRIMITIVE_CLICK to 0.8f
                    HapticFeedback.Style.Heavy -> Composition.PRIMITIVE_CLICK to 1f
                    HapticFeedback.Style.Soft -> Composition.PRIMITIVE_LOW_TICK to 0.9f
                    HapticFeedback.Style.Rigid -> Composition.PRIMITIVE_TICK to 1f
                }
                composition.addPrimitive(primitive, (base * feedback.intensity).coerceIn(0f, 1f))
            }
            is HapticFeedback.Notification -> when (feedback.kind) {
                // UINotificationFeedbackGenerator's patterns: two rising taps, two even, three falling.
                HapticFeedback.Kind.Success -> composition
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.5f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 90)
                HapticFeedback.Kind.Warning -> composition
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.9f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.9f, 140)
                HapticFeedback.Kind.Error -> composition
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 1f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f, 70)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.6f, 70)
            }
        }
        return composition.compose()
    }

    private fun fallback(feedback: HapticFeedback): VibrationEffect = VibrationEffect.createPredefined(
        when (feedback) {
            HapticFeedback.Selection -> VibrationEffect.EFFECT_TICK
            is HapticFeedback.Impact -> if (feedback.style == HapticFeedback.Style.Heavy) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
            is HapticFeedback.Notification -> if (feedback.kind == HapticFeedback.Kind.Success) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK
        },
    )
}
