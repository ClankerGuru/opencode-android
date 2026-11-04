package ai.opencode.feature.voice

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Port of iOS `Voice/VoiceCallCenterButton.swift`: the call bar's centre button, the captions
 * backdrop and the weak-connection badge, moved out of the Call Tray's views when it was deleted.
 */

/**
 * Centre status and caption-toggle target of [VoiceCallComposer]: the needs-input hand, the
 * weak-connection note or the paused glyph over the scope, and a tap that shows or hides the
 * call's transcript. TalkBack: “Voice call”, its status as the state.
 */
@Composable
fun RowScope.VoiceCallCenterButton(
    store: VoiceStoreOf?,
    canToggleCaptions: Boolean,
    animation: FiniteAnimationSpec<Float>,
) {
    val showsCaptionsState = store.watch { it.showsCaptions } == true
    val showsCaptions = canToggleCaptions && showsCaptionsState
    val needsInput = store.watch { it.targetNeedsInput } == true
    val poor = store.watch { it.showsPoorConnection } == true
    val interrupted = store.watch { it.isAudioInterrupted } == true
    val status = store.watch { it.statusText } ?: ""
    val toggle = {
        if (canToggleCaptions && store != null) {
            store.send(if (store.state.showsCaptions) VoiceFeature.Action.CaptionsDismissed else VoiceFeature.Action.CaptionsTapped)
        }
    }
    val hint = if (canToggleCaptions) (if (showsCaptions) "Hide the call transcript" else "Show the call transcript") else null
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle)
            .clearAndSetSemantics {
                contentDescription = "Voice call"
                stateDescription = status
                role = Role.Button
                onClick(label = hint) { toggle(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            needsInput -> OpenCodeIcon(SFSymbol.HandRaisedFill, null, tint = VoiceCallSurfaceDefaults.orange)
            else -> {
                // Speech may not be getting through: say so, over the scope.
                PoorConnectionBadgeVisibility(poor, animation)
                if (!poor && interrupted) {
                    // Another app or call has the audio; the detail is in captions.
                    OpenCodeIcon(SFSymbol.PauseFill, null, tint = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun PoorConnectionBadgeVisibility(visible: Boolean, animation: FiniteAnimationSpec<Float>) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animation) + scaleIn(animation, initialScale = 0.9f),
        exit = fadeOut(animation) + scaleOut(animation, targetScale = 0.9f),
    ) { PoorConnectionBadge() }
}

/** Invisible backdrop that dismisses the call's captions on tap (4,000 dp square, centred on its host). */
@Composable
fun VoiceCaptionsDismissBackdrop(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    Box(
        modifier
            .wrapContentSize(unbounded = true)
            .requiredSize(4_000.dp)
            .clickable(interactionSource = null, indication = null) { store.send(VoiceFeature.Action.CaptionsDismissed) }
            .semantics { hideFromAccessibility() },
    )
}

/**
 * Shown on the call bar while microphone audio is backing up on the way to the voice model,
 * so a call that can't hear you doesn't look fine. Quiet by design: a small amber glyph and
 * secondary text over the dimmed scope. Hidden from TalkBack (the bar's status says it).
 */
@Composable
fun PoorConnectionBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(SFSymbol.WifiExclamationmark, null, tint = VoiceCallSurfaceDefaults.orange, size = 16.dp)
        Text(
            "Weak connection",
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
