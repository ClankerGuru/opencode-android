package ai.opencode.feature.session

import ai.opencode.feature.composer.MicPressCallbacks
import ai.opencode.feature.composer.RequestBar
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.feature.session.seams.REQUEST_DOCK_EDGE
import ai.opencode.feature.session.seams.SELECTION_MARK_WIDTH
import ai.opencode.feature.session.seams.SelectionMark
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

// Port of iOS `Session/SessionView+ComposerControls.swift`: the composer's leading and trailing
// controls, the request bar's controls, the mic's hold-to-dictate wiring and the dictation row.

/** iOS's 44 pt control slot; Android's targets are 48 dp. */
private const val SLOT = 48f

/**
 * iOS `composerLeadingControls(expanded:secondaryMic:recording:fading:)`: + (the recording's ×
 * while dictating), then the expanded row's model button and the leading mic.
 */
@Composable
internal fun SessionComposerEnv.ComposerLeadingControls(
    expanded: Boolean,
    secondaryMic: ComposerControls.MicPlacement,
    recording: Boolean,
    rowAlpha: Float,
) {
    // 9 dp from the + leaves as much air after its ink as the mic leaves before the trailing circle.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ComposerTunables.leadingSpacing.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // While dictating, the same + turns into the recording's ×.
        AttachmentButton()
        ComposerAccessoryRow(
            isExpanded = expanded,
            screen = s.navigationID,
            recording = recording,
            modifier = Modifier.weight(1f).alpha(rowAlpha),
            revealed = {
                Row(horizontalArrangement = Arrangement.spacedBy(ComposerTunables.leadingSpacing.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModelButton(enabled = expanded && !recording)
                }
            },
            trailing = {
                if (secondaryMic == ComposerControls.MicPlacement.leading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { SecondaryMicButton() }
                }
            },
        )
    }
}

/**
 * The model control: Model Picker › Today shows the full name, › Sheet the short name and its level;
 * › Grow from Composer is VIEW-PICKERS' fused chip ([SessionComposerHost.fusedModelChip]).
 */
@Composable
private fun SessionComposerEnv.ModelButton(enabled: Boolean) {
    val fused = host.fusedModelChip
    if (modelPickerStyle == ModelPickerStyle.growFromComposer && fused != null) {
        ComposerHidden(foldsComposerControls) { fused(enabled) }
        return
    }
    val usesSheet = modelPickerStyle == ModelPickerStyle.sheet
    val value = if (usesSheet) listOfNotNull(s.modelShortName, s.chipLevelLabel).joinToString(", ") else s.modelLabel
    val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)
    ComposerHidden(foldsComposerControls) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, onClickLabel = null, role = Role.Button) {
                    // Put away, or a row's level menu raises the composer's keyboard over the sheet.
                    if (usesSheet) dismissKeyboard()
                    store.send(Action.ModelButtonTapped)
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Choose model"
                    stateDescription = value
                    testTag = "composer-model"
                },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (usesSheet) {
                Text(s.modelShortName, style = style, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val level = s.chipLevelLabel
                if (level != null) Text(level, style = style, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, softWrap = false)
            } else {
                Text(
                    s.modelLabel,
                    style = style,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = 12.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * iOS `attachmentButton` (`AttachmentPickerButton`): + opens the attachment menu (VIEW-PICKERS,
 * [SessionComposerHost.onPlusTapped]); turned 45° it's the menu's close and, on its tinted circle,
 * the recording's × (red while sliding has armed cancel).
 */
@Composable
private fun SessionComposerEnv.AttachmentButton() {
    val hold = s.dictationHold
    val pose = hold?.let { dictationPose(it) }
    val isDictating = hold != null
    val cancelArmed = pose?.cancelArmed == true
    val isEnabled = if (isDictating) pose?.isFinishing != true else !host.isImportingAttachments
    val showsX = host.isAttachmentMenuOpen || isDictating
    val isLoading = host.isImportingAttachments && !isDictating
    val spring = if (reducedMotion) Motion.easeInOut<Float>(0.15) else MotionSpring(0.22, 0.25).spec()
    val turn by animateFloatAsState(if (showsX) 45f else 0f, if (reducedMotion) Motion.easeInOut(0.2) else Motion.morphOpen.spec(), label = "plusTurn")
    val circle by animateFloatAsState(if (isDictating) 1f else 0f, if (reducedMotion) Motion.easeInOut(0.2) else Motion.morphOpen.spec(), label = "plusCircle")
    val armed by animateFloatAsState(if (cancelArmed) 1f else 0f, spring, label = "plusArmed")
    val diameter = min(34f * LocalDensity.current.fontScale * (ComposerTunables.buttonDiameter / 34f), 44f)
    val label = when {
        isDictating -> "Cancel dictation"
        isLoading -> "Adding attachment"
        host.isAttachmentMenuOpen -> "Close"
        else -> "Add attachment"
    }
    val action: () -> Unit = {
        if (isDictating) {
            if (isEnabled) store.send(Action.micPressCancelled)
        } else if (!host.isImportingAttachments) {
            host.onPlusTapped?.invoke()
        }
    }
    Box(
        Modifier
            .size(SLOT.dp)
            .clickable(
                enabled = isEnabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 22.dp),
                role = Role.Button,
                onClick = action,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                testTag = "attachment-menu-button"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter.dp)
                .scale(if (reducedMotion) 1f else 0.6f + 0.4f * circle + 0.1f * armed)
                .alpha(circle)
                .background(if (cancelArmed) OpenCodeTheme.colors.destructive else OpenCodeTheme.colors.variant.surfaceStrong.color, OpenCodeShape.circle),
        )
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            OpenCodeIcon(
                SFSymbol.Plus,
                contentDescription = null,
                size = (ComposerTunables.plusScale + 5).dp,
                tint = if (cancelArmed) Color.White else OpenCodeTheme.colors.text,
                modifier = Modifier
                    .offset(x = if (isDictating) 0.dp else ComposerTunables.plusInkOffset.dp)
                    .rotate(turn)
                    .scale(1f + 0.1f * armed),
            )
        }
    }
}

/**
 * iOS `answerLeadingControls(_:recording:fading:)`: answering a request, your own answer's mark
 * takes +'s place. While dictating, + comes back as the recording's ×.
 */
@Composable
internal fun SessionComposerEnv.AnswerLeadingControls(bar: RequestBar, recording: Boolean, rowAlpha: Float) {
    val xAlpha by animateFloatAsState(
        if (recording) 1f else 0f,
        if (recording) Motion.easeOut(Motion.fadeStandard, if (reducedMotion) 0.0 else Motion.contentDelay, reducedMotion)
        else Motion.easeOut(Motion.fadeFast, reduceMotion = reducedMotion),
        label = "answerX",
    )
    Box(Modifier.size(SLOT.dp), contentAlignment = Alignment.Center) {
        // Hidden until a recording starts: then it fades in as the ×.
        ComposerHidden(!recording && settledRecording == false, fadesOnReveal = false, modifier = Modifier.alpha(xAlpha)) {
            Box(if (recording) Modifier else Modifier.blockTouches()) { AttachmentButton() }
        }
        if (bar is RequestBar.Answer) {
            val draftEmpty = s.draft.isBlank()
            ComposerHidden(foldsComposerControls) {
                Box(
                    Modifier
                        .size(SLOT.dp)
                        .alpha(rowAlpha)
                        .then(
                            if (recording) {
                                Modifier.clearAndSetSemantics { }.blockTouches()
                            } else {
                                Modifier
                                    .clickable(role = Role.Button, onClickLabel = null) {
                                        if (s.draft.isBlank()) focus() else store.send(Action.requestCustomMarkTapped)
                                    }
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "Your own answer"
                                        selected = bar.isCustom
                                        stateDescription = when {
                                            draftEmpty -> "Type or dictate your own answer"
                                            bar.isCustom -> "Sets your words aside"
                                            else -> "Answers with your words"
                                        }
                                        testTag = "request-custom"
                                    }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // On the choices' marks, 20 dp in from the surface.
                    SelectionMark(
                        selected = bar.isCustom,
                        multiple = bar.multiple,
                        modifier = Modifier.offset(x = (REQUEST_DOCK_EDGE - 6 - (SLOT - SELECTION_MARK_WIDTH) / 2).dp),
                    )
                }
            }
        }
    }
}

/**
 * iOS `answerTrailingControls(_:)`: answering a request, the composer's mic, then Send (quiet until
 * the page has an answer), Next on an earlier page, or Deny/Dismiss.
 */
@Composable
internal fun SessionComposerEnv.AnswerTrailingControls(bar: RequestBar) {
    val responding = s.dockResponding
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryMicButton()
        when (bar) {
            is RequestBar.Decline -> ComposerHidden(foldsComposerControls) {
                Box(
                    Modifier
                        .padding(end = 5.dp)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            dismissKeyboard()
                            store.send(Action.SendTapped)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = bar.verb.title
                            if (s.draft.isNotEmpty()) stateDescription = "Tells the agent what to do instead"
                            testTag = if (bar.verb == RequestBar.Verb.Deny) "request-deny" else "request-decline"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .widthIn(min = 64.dp)
                            .heightIn(min = 34.dp)
                            .background(OpenCodeTheme.colors.variant.surfaceStrong.color, OpenCodeShape.capsule)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (responding) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(bar.verb.title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.text)
                        }
                    }
                }
            }
            is RequestBar.Answer, is RequestBar.Choose -> {
                val ready = bar.isReady
                val control = ComposerTrailingControl.Send(enabled = ready)
                val enabled = ready && !responding
                ComposerHidden(foldsComposerControls) {
                    Box(
                        Modifier
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = false, radius = 22.dp),
                                role = Role.Button,
                            ) {
                                if (bar.isLast) dismissKeyboard()
                                store.send(Action.SendTapped)
                            }
                            .openCodeTapTarget(accessibility = false)
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (bar.isLast) "Send" else "Next"
                                if (!ready) stateDescription = "Choose an answer or type your own"
                                testTag = if (bar.isLast) "request-send" else "request-next"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        ComposerControlLabel(
                            systemImage = if (bar.isLast) "arrow.up" else "chevron.right",
                            showsProgress = responding,
                            foreground = control.foreground(),
                            background = if (ready) control.background() else OpenCodeTheme.colors.variant.surfaceStrong.color,
                        )
                    }
                }
            }
        }
    }
}

/** iOS `secondaryMicButton`: dictation beside the call or Stop. */
@Composable
internal fun SessionComposerEnv.SecondaryMicButton() {
    ComposerHidden(foldsHeldMic) {
        HoldToDictate(label = "Dictate message") {
            ComposerControlLabel(
                systemImage = ComposerTunables.micSymbol,
                showsProgress = false,
                foreground = OpenCodeTheme.colors.secondaryText,
                background = Color.Transparent,
                glyphSize = 22.dp,
            )
        }
    }
}

/**
 * iOS `holdToDictateButton(label:)`: the mic's touch handling. While recording, the composer row is
 * hidden under [DictationScopeBar], but this stays mounted beneath because it owns the finger until
 * it lifts. TalkBack's activation locks a recording (or, dictating, puts the words in).
 */
@Composable
private fun SessionComposerEnv.HoldToDictate(label: String, content: @Composable () -> Unit) {
    val callbacks = MicPressCallbacks(
        onBegan = { beginDictationHold() },
        onDrag = { view.dictationDrag = it },
        onArmed = { store.send(Action.micPressArmed(it)) },
        onReleased = { store.send(Action.micPressReleased) },
        onLocked = { store.send(Action.micPressLocked) },
        onInterrupted = { store.send(Action.micPressInterrupted) },
    )
    HoldToDictateButton(
        isEnabled = s.dictationHold?.isLocked != true,
        callbacks = callbacks,
        modifier = Modifier
            .onGloballyPositioned { view.dictationMicFrame = it.boundsInRoot() }
            .openCodeTapTarget(accessibility = false)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                stateDescription = "Tap to dictate into your message, or touch and hold to record and send"
                onClick(label = null) {
                    if (store.state.isDictating) {
                        store.send(Action.dictationInsertTapped)
                    } else {
                        beginDictationHold()
                        store.send(Action.micPressLocked)
                    }
                    true
                }
            },
        label = content,
    )
}

/**
 * iOS `trailingButton(_:)`: Send (or, mid-run, Steer with a hold for Queue), Stop (held: a call),
 * the mic, or Call. Each control is its own composable, so a press can't change meaning when a
 * run starts or ends under the finger.
 */
@Composable
internal fun SessionComposerEnv.TrailingButton(control: ComposerTrailingControl) {
    val steers = s.isRunning && control == ComposerTrailingControl.Send(enabled = true)
    val visualOffset = (ComposerTunables.innerHorizontal + (44 - ComposerTunables.buttonDiameter) / 2) - ComposerTunables.trailingGap
    val disabled = (host.isImportingAttachments && control == ComposerTrailingControl.Send(enabled = true)) ||
        (control is ComposerTrailingControl.Send && !control.enabled)
    val accessibilityLabel = when {
        s.isWaitingForUploads -> "Sending after uploads finish"
        steers -> "Steer message"
        else -> control.accessibilityLabel
    }
    val label: @Composable () -> Unit = {
        ComposerControlLabel(
            systemImage = control.symbolName,
            showsProgress = s.isWaitingForUploads || (control == ComposerTrailingControl.Stop && s.isInterrupting),
            foreground = control.foreground(),
            background = control.background(),
            glyphSize = when (control) {
                ComposerTrailingControl.Stop -> 12.dp
                ComposerTrailingControl.Mic -> 22.dp
                else -> 18.dp
            },
            visualOffsetX = visualOffset,
            // The mic's stand and base weigh its mass low: lifted, its optical centre sits on the end-cap's centre (OC-477).
            glyphRise = if (control == ComposerTrailingControl.Mic) ComposerTunables.micInkRise else 0f,
        )
    }
    val semantics = Modifier.semantics(mergeDescendants = true) {
        contentDescription = accessibilityLabel
        testTag = control.accessibilityLabel
        if (steers) stateDescription = "Steers at the next step. Touch and hold to queue after this response."
        if (control == ComposerTrailingControl.Stop && s.isInterrupting) stateDescription = "Stopping"
        if (disabled) disabled()
        if (control == ComposerTrailingControl.Stop && holdingStopStartsCall) {
            customActions = listOf(CustomAccessibilityAction("Start Voice Call") { store.send(Action.StopLongPressed); true })
        }
    }
    val hidden = if (control == ComposerTrailingControl.Mic) foldsHeldMic else foldsComposerControls
    ComposerHidden(hidden) {
        when {
            steers -> DeliveryMenu(
                onSend = { sendMessage(if (it == ai.opencode.server.model.OpenCodeDelivery.Queue) Action.QueueTapped else Action.SendTapped) },
                enabled = !disabled,
            ) { menuModifier ->
                Box(menuModifier.openCodeTapTarget(accessibility = false).then(semantics), contentAlignment = Alignment.Center) { label() }
            }
            control == ComposerTrailingControl.Mic -> HoldToDictate(label = "Dictate message") { label() }
            control == ComposerTrailingControl.Stop -> ComposerStopButton(
                holdStartsCall = holdingStopStartsCall,
                onStop = { store.send(Action.InterruptTapped) },
                onHold = { store.send(Action.StopLongPressed) },
                modifier = semantics,
            ) { label() }
            control == ComposerTrailingControl.Call -> Box(
                Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 22.dp),
                        role = Role.Button,
                    ) {
                        // Only ever starts a call; End is the call bar's.
                        if (!s.hasCall) store.send(Action.VoiceTapped)
                    }
                    .openCodeTapTarget(accessibility = false)
                    .then(semantics),
                contentAlignment = Alignment.Center,
            ) { label() }
            else -> Box(
                Modifier
                    .clickable(
                        enabled = !disabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 22.dp),
                        role = Role.Button,
                    ) { sendMessage() }
                    .openCodeTapTarget(accessibility = false)
                    .then(semantics),
                contentAlignment = Alignment.Center,
            ) { label() }
        }
    }
}

/** iOS `dictationTrailingControls(_:)`: dictating into the draft (not held): Stop and Send. */
@Composable
internal fun SessionComposerEnv.DictationTrailingControls(controls: ComposerControls.DictationControls) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clickable(enabled = !controls.isFinishing, role = Role.Button) {
                    focus()
                    store.send(Action.micTapped)
                }
                .openCodeTapTarget(accessibility = false)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (controls.isFinishing) "Finishing dictation" else "Stop dictation"
                },
            contentAlignment = Alignment.Center,
        ) {
            ComposerControlLabel(
                systemImage = "stop.fill",
                showsProgress = controls.isFinishing,
                foreground = OpenCodeTheme.colors.text,
                background = OpenCodeTheme.colors.variant.surfaceStrong.color,
                glyphSize = 12.dp,
            )
        }
        val sendEnabled = !controls.isFinishing && canSend && !host.isImportingAttachments
        Box(
            Modifier
                .clickable(enabled = sendEnabled, role = Role.Button) {
                    focus()
                    store.send(Action.dictationSendTapped)
                }
                .openCodeTapTarget(accessibility = false)
                .semantics(mergeDescendants = true) { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) {
            ComposerControlLabel(
                systemImage = "arrow.up",
                showsProgress = false,
                foreground = OpenCodeTheme.colors.sendButtonForeground,
                background = OpenCodeTheme.colors.sendButtonBackground,
            )
        }
    }
}
