package ai.opencode.feature.session

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Server Flow's tunables (iOS `ServerFlowTunables`, `OpenCode/Drawer/ServerFlow.swift`), moved
 * down from the drawer because the session's [ServerConnectionPill] reads the pill's palette;
 * the drawer reads the springs and travels, the app the switch-back timeout. Constants here:
 * Android has no Tuning Panel.
 */
object ServerFlowTunables {
    /** Switch back after (s). */
    const val timeout = 8.0
    /** Name roll. */
    val title = MotionSpring(0.36, 0.14)
    /** Name width. */
    val width = MotionSpring(0.4, 0.1)
    /** Rows in. */
    val list = MotionSpring(0.4, 0.0)
    /** Rows and name out. */
    val exit = MotionSpring(0.26, 0.0)
    /** Row follows the one above. */
    val follow = MotionSpring(0.08, 0.0)
    /** Underline fills. */
    val fill = MotionSpring(0.28, 0.0)
    /** Underline fades. */
    val fade = MotionSpring(0.7, 0.0)
    const val titleTravel = 22f
    const val listTravel = 28f
    const val blur = 2f
    /** The pill's palette: it enters rising, scaled and blurred, and leaves quicker. */
    const val enter = 0.3
    const val leave = 0.24
    const val rise = 6f
    const val scale = 0.94f
    const val enterBlur = 4f
}

/**
 * What a session's pill says while its server isn't connected (iOS `ServerConnectionNotice`,
 * `OpenCode/Drawer/ServerFlow.swift`; here in `:feature:session` because [SessionListContext]
 * carries it). The drawer builds it from Home's connection (`ServerConnectionNotice(status:…)`
 * in `:feature:drawer`'s `ServerFlow.kt`).
 */
data class ServerConnectionNotice(
    val kind: Kind,
    val serverName: String,
    /** Why it failed, when the error says more than the title. */
    val detail: String? = null,
) {
    enum class Kind { reconnecting, failed, signInFailed }

    enum class Action { retry, pairAgain, editServer }

    val title: String
        get() = when (kind) {
            Kind.reconnecting -> "Reconnecting to $serverName…"
            Kind.failed -> "Couldn’t connect to $serverName"
            Kind.signInFailed -> "Couldn’t sign in to $serverName"
        }

    /** The pill's tap: retry, or pair again for a sign-in the server refused. */
    val primary: Action get() = if (kind == Kind.signInFailed) Action.pairAgain else Action.retry

    /** The word at the pill's end, naming its tap. */
    val action: String?
        get() = when (kind) {
            Kind.reconnecting -> null
            Kind.failed -> "Try Again"
            Kind.signInFailed -> "Pair Again"
        }

    val systemImage: SFSymbol
        get() = when (kind) {
            Kind.reconnecting -> SFSymbol.ArrowTriangle2Circlepath
            Kind.failed -> SFSymbol.ExclamationmarkCircleFill
            Kind.signInFailed -> SFSymbol.KeyFill
        }

    companion object {
        /**
         * iOS `init?(status:credentialsRejected:errorMessage:server:isSwitching:)` without Home's
         * status type: null while connected, and while the drawer switches servers (the switch
         * is the drawer's to show).
         */
        fun of(
            isConnected: Boolean,
            isReconnecting: Boolean,
            credentialsRejected: Boolean,
            errorMessage: String?,
            serverName: String,
            isSwitching: Boolean,
        ): ServerConnectionNotice? {
            if (isSwitching || isConnected) return null
            if (isReconnecting) return ServerConnectionNotice(Kind.reconnecting, serverName)
            if (credentialsRejected) return ServerConnectionNotice(Kind.signInFailed, serverName)
            val detail = errorMessage?.takeIf { it.isNotEmpty() && !it.startsWith("Couldn’t") }
            return ServerConnectionNotice(Kind.failed, serverName, detail)
        }
    }
}

/**
 * Server Flow: a pill right above the composer while the session's server isn't connected
 * (iOS `ServerConnectionPill`). It says what's happening, and its tap retries (or pairs again,
 * for a sign-in the server refused); a long press offers Edit Server… then. iOS's glass capsule
 * is an M3 tonal surface here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerConnectionPill(
    notice: ServerConnectionNotice,
    onAction: (ServerConnectionNotice.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when (notice.kind) {
        ServerConnectionNotice.Kind.reconnecting -> OpenCodeTheme.colors.secondaryText
        ServerConnectionNotice.Kind.failed -> OpenCodeTheme.colors.destructive
        ServerConnectionNotice.Kind.signInFailed -> Color(0xFFFF9500)
    }
    val offersEdit = notice.kind == ServerConnectionNotice.Kind.signInFailed
    var menuShown by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    Box(modifier) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { onAction(notice.primary) },
                    onLongClick = if (offersEdit) ({ menuShown = true }) else null,
                )
                .openCodeTapTarget()
                .clearAndSetSemantics {
                    contentDescription = notice.title
                    notice.detail?.let { stateDescription = it }
                    onClick(label = notice.action?.lowercase()) { onAction(notice.primary); true }
                    if (offersEdit) {
                        customActions = listOf(CustomAccessibilityAction("Edit Server…") { onAction(ServerConnectionNotice.Action.editServer); true })
                    }
                }
                .testTag("server-connection-pill"),
        ) {
            Row(
                Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (notice.kind == ServerConnectionNotice.Kind.reconnecting) {
                    CircularProgressIndicator(Modifier.size((16 * fontScale).dp), color = tint, strokeWidth = 2.dp)
                } else {
                    OpenCodeIcon(notice.systemImage, contentDescription = null, size = (18 * fontScale).dp, tint = tint, modifier = Modifier.width((18 * fontScale).dp))
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        notice.title,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    notice.detail?.let {
                        Text(it, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                notice.action?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.accent)
                }
            }
        }
        DropdownMenu(expanded = menuShown && offersEdit, onDismissRequest = { menuShown = false }) {
            DropdownMenuItem(
                text = { Text("Edit Server…") },
                leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                onClick = {
                    menuShown = false
                    onAction(ServerConnectionNotice.Action.editServer)
                },
            )
        }
    }
}

/**
 * iOS `ServerConnectionPill.transition(reducedMotion:)`: the palette's motion. It rises in,
 * scaled, and leaves quicker; a cross-fade under Reduce Motion. [density] converts the rise. (iOS's blur has no
 * enter/exit transition here; the rise and scale carry it.)
 */
class ServerConnectionPillTransition(private val density: Float) {
    fun enter(reducedMotion: Boolean): EnterTransition {
        val ms = (ServerFlowTunables.enter * 1000).roundToInt()
        if (reducedMotion) return fadeIn(tween(ms, easing = LinearOutSlowInEasing))
        val spring = MotionSpring(ServerFlowTunables.enter)
        return fadeIn(spring.spec()) +
            scaleIn(spring.spec(), initialScale = ServerFlowTunables.scale, transformOrigin = TransformOrigin(0.5f, 1f)) +
            slideInVertically(spring.spec(IntOffset.VisibilityThreshold)) { (ServerFlowTunables.rise * density).roundToInt() }
    }

    fun exit(reducedMotion: Boolean): ExitTransition {
        val ms = (ServerFlowTunables.leave * 1000).roundToInt()
        if (reducedMotion) return fadeOut(tween(ms, easing = FastOutLinearInEasing))
        return fadeOut(tween(ms, easing = FastOutLinearInEasing)) +
            scaleOut(tween(ms, easing = FastOutLinearInEasing), targetScale = ServerFlowTunables.scale, transformOrigin = TransformOrigin(0.5f, 1f))
    }
}
