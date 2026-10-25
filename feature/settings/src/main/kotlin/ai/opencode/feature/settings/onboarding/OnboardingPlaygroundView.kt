package ai.opencode.feature.settings.onboarding

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeButtonDefaults
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Action
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.CameraFeed
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Failure
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Phase
import ai.opencode.feature.settings.onboarding.OnboardingPlaygroundFeature.Variant
import ai.opencode.feature.settings.servers.QRCameraPreview
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex

private typealias PlaygroundStore = StoreOf<OnboardingPlaygroundFeature.State, Action>

/**
 * Port of iOS `Settings/Onboarding/OnboardingPlaygroundView.swift`.
 *
 * The Onboarding Playground: the onboarding's pages over the dot field, with a hideable control
 * bar for the variant, every phase, the simulated server's latency and failures, and Replay.
 * Shown full screen from Settings › Advanced › Developer › Playgrounds › Onboarding. It is a
 * sandbox only; iOS has no first-run entry to it (the real first run is `:app`'s connection
 * screen), so there is none here either.
 */
@Composable
fun OnboardingPlaygroundView(
    store: StoreOf<OnboardingPlaygroundFeature.State, OnboardingPlaygroundFeature.Action>,
    onClose: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val reduceMotion = rememberReduceMotion()
    val phase by store.observe { it.phase }
    val variant by store.observe { it.variant }
    val heartbeats by store.observe { it.heartbeats }
    val controlsHidden by store.observe { it.controlsHidden }
    /** The shared centre slot (the camera aperture), in the field's dp. */
    val aperture = remember { mutableStateOf(Rect(0f, 0f, 240f, 240f)) }
    /** Under the first session's header, where Quiet Relay's band lands. */
    val headerLine = remember { mutableFloatStateOf(0f) }
    /** The page the heartbeat wipe is carrying away. */
    val wipedPage = remember { WipedPageTracker() }.update(phase)

    LaunchedEffect(Unit) { store.send(Action.Task) }

    CompositionLocalProvider(LocalContentColor provides colors.text) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .testTag("onboarding-playground"),
        ) {
            SignalFieldView(
                SignalFieldScene(
                    phase = phase,
                    variant = variant,
                    heartbeats = heartbeats,
                    aperture = aperture.value,
                    traceY = if (phase == Phase.session && variant == Variant.quietRelay) headerLine.floatValue else aperture.value.center.y,
                ),
            )
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                OnboardingPages(
                    store = store,
                    wipedPage = wipedPage,
                    onAperture = { aperture.value = it },
                    onHeaderLine = { headerLine.floatValue = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                AnimatedContent(
                    targetState = controlsHidden,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                    transitionSpec = {
                        val fade = OnboardingMotion.layout<Float>(reduceMotion)
                        if (reduceMotion) {
                            fadeIn(fade) togetherWith fadeOut(fade) using SizeTransform(clip = false) { _, _ -> OnboardingMotion.layout(true) }
                        } else {
                            val slide = OnboardingTunables.layout.spec(IntOffset.VisibilityThreshold)
                            (slideInVertically(slide) { it } + fadeIn(fade)) togetherWith
                                (slideOutVertically(slide) { it } + fadeOut(fade)) using
                                SizeTransform(clip = false) { _, _ -> OnboardingTunables.layout.spec(IntSize.VisibilityThreshold) }
                        }
                    },
                    label = "onboardingControls",
                ) { hidden ->
                    if (hidden) {
                        // Tucked away: one small button brings the controls back.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            FilledTonalButton(
                                onClick = { store.send(Action.ControlsHiddenChanged(false)) },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp),
                            ) {
                                OpenCodeIcon(SFSymbol.ChevronUp, contentDescription = "Show Controls", size = OpenCodeIconSize.small)
                            }
                        }
                    } else {
                        OnboardingPlaygroundControls(store, onClose)
                    }
                }
            }
        }
    }
}

/**
 * iOS `.onChange(of: store.phase) { if new == .session, old != .session { wipedPage = old } }`,
 * resolved during composition so the wipe never draws a frame with the wrong page.
 */
private class WipedPageTracker {
    private var last: Phase? = null
    private var wiped: Phase = Phase.connected

    fun update(phase: Phase): Phase {
        val old = last
        if (old != null && phase == Phase.session && old != Phase.session) wiped = old
        last = phase
        return wiped
    }
}

/** The springs the pages share. */
internal object OnboardingMotion {
    /** Reduce Motion: no travel, a short cross-fade. */
    val fade = MotionSpring(0.18, 0.0)

    fun <T> layout(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) fade.spec() else OnboardingTunables.layout.spec()

    /** Entrances stagger; exits run shorter and together. */
    fun entrance(isActive: Boolean, index: Int, reduceMotion: Boolean): FiniteAnimationSpec<Float> {
        if (reduceMotion) return fade.spec()
        val spring = OnboardingTunables.layout
        return if (isActive) {
            spring.animation(reduceMotion = false, delaySeconds = index * OnboardingTunables.stagger)
        } else {
            MotionSpring(spring.duration * 0.8, 0.0).spec()
        }
    }
}

/**
 * One piece of a page entering or leaving: opacity, a little blur and rise, staggered by
 * [index]. Under Reduce Motion, opacity alone.
 */
@Composable
private fun Entrance(isActive: Boolean, index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val progress = animateFloatAsState(
        if (isActive) 1f else 0f,
        animationSpec = OnboardingMotion.entrance(isActive, index, reduceMotion),
        label = "entrance",
    )
    val travel = if (reduceMotion) 0f else 1f
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            val away = 1f - p
            alpha = p
            translationY = (OnboardingTunables.pageRise.toFloat() * travel * away).dp.toPx()
            val scale = 1f - 0.015f * travel * away
            scaleX = scale
            scaleY = scale
            val blur = (OnboardingTunables.pageBlur.toFloat() * travel * away).dp.toPx()
            renderEffect = if (blur > 0.25f) BlurEffect(blur, blur, TileMode.Decal) else null
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Every page stays mounted, so each leaves on its own spring while the next arrives and a
 * reversal mid-flight retargets from where it is.
 */
@Composable
private fun OnboardingPages(
    store: PlaygroundStore,
    wipedPage: Phase,
    onAperture: (Rect) -> Unit,
    onHeaderLine: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val phase by store.observe { it.phase }
    val variant by store.observe { it.variant }
    val failedWith by store.observe { it.failedWith }
    val cameraFeed by store.observe { it.cameraFeed }
    val wipes = variant.wipes && !reduceMotion
    val wipe = animateFloatAsState(
        if (phase == Phase.session && wipes) 1f else 0f,
        animationSpec = OnboardingTunables.landing(variant).spec(),
        label = "wipe",
    )
    fun isShown(page: Phase) = phase == page || (phase == Phase.session && wipes && wipedPage == page)
    val send: (Action) -> Unit = store::send

    Box(modifier) {
        ApertureLayer(phase, cameraFeed, reduceMotion, onAperture, onScanned = { send(Action.CodeScanned(it)) })
        Box(Modifier.fillMaxSize().wipeMask(wipe, revealing = false, active = wipes)) {
            Welcome(isShown(Phase.welcome), send)
            Story(isShown(Phase.story), send)
            Camera(isShown(Phase.camera), send)
            Waiting(isShown(Phase.waiting), send)
            Connected(isShown(Phase.connected), send)
            Failed(isShown(Phase.failed), failedWith, send)
        }
        FirstSessionPage(
            isActive = phase == Phase.session,
            wipes = wipes,
            send = send,
            onHeaderLine = onHeaderLine,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (phase == Phase.session) 1f else 0f)
                .wipeMask(wipe, revealing = true, active = wipes),
        )
    }
}

private val largeTitleBold: TextStyle get() = OpenCodeTextStyles.largeTitle.copy(fontWeight = FontWeight.Bold)

@Composable
private fun secondary(): Color = OpenCodeTheme.colors.secondaryText

@Composable
private fun Welcome(on: Boolean, send: (Action) -> Unit) {
    OnboardingPageLayout(
        isActive = on,
        id = "welcome",
        top = {
            Entrance(on, 0) {
                Text(
                    "opencode",
                    style = TextStyle(fontFamily = OpenCodeFonts.berkeleyMono, fontWeight = FontWeight.Bold, fontSize = 44.sp),
                    modifier = Modifier.semantics { contentDescription = "OpenCode" },
                )
            }
        },
        centre = { Spacer(Modifier.height(120.dp)) },
        below = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Entrance(on, 1) { Text("Your agents.\nWithin reach.", style = largeTitleBold) }
                Entrance(on, 2) {
                    Text(
                        "Pick up a session. Talk through an idea. Keep things moving from your phone.",
                        style = OpenCodeTextStyles.body,
                        color = secondary(),
                    )
                }
            }
        },
        footer = {
            Entrance(on, 3) { PrimaryButton("Get Started", on) { send(Action.GetStartedTapped) } }
            Entrance(on, 4) {
                Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text("Your code stays on your computer", style = OpenCodeTextStyles.footnote, color = secondary())
                }
            }
        },
    )
}

@Composable
private fun Story(on: Boolean, send: (Action) -> Unit) {
    OnboardingPageLayout(
        isActive = on,
        id = "story",
        title = "OpenCode on Android",
        onBack = { send(Action.BackTapped) },
        top = { Entrance(on, 0) { Text("Take the work\nwith you.", style = largeTitleBold) } },
        centre = { Spacer(Modifier.height(120.dp)) },
        below = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Entrance(on, 1) { StoryLine(SFSymbol.ArrowTrianglehead2Clockwise, "Continue a session from anywhere") }
                Entrance(on, 2) { StoryLine(SFSymbol.Waveform, "Talk with your agent on a voice call") }
                Entrance(on, 3) { StoryLine(SFSymbol.BellBadge, "Come back when it needs your input") }
            }
        },
        footer = {
            Entrance(on, 4) { PrimaryButton("Connect Your Server", on) { send(Action.ConnectTapped) } }
            Entrance(on, 5) { SecondaryButton("Back", on) { send(Action.BackTapped) } }
        },
    )
}

@Composable
private fun Camera(on: Boolean, send: (Action) -> Unit) {
    OnboardingPageLayout(
        isActive = on,
        id = "camera",
        title = "Connect Your Server",
        onBack = { send(Action.BackTapped) },
        top = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Entrance(on, 0) { Text("Find your signal.", style = largeTitleBold) }
                Entrance(on, 1) { Text("Run this on your computer:", color = secondary()) }
                Entrance(on, 2) {
                    Text(
                        "opencode pair --remote",
                        style = OpenCodeTextStyles.callout.copy(fontFamily = OpenCodeFonts.berkeleyMono, fontSize = 15.sp),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        },
        centre = { Spacer(Modifier.height(ApertureSide)) },
        below = {
            Entrance(on, 3) { Text("Point your camera at the pairing code", style = OpenCodeTextStyles.subheadline, color = secondary()) }
        },
        footer = {
            Entrance(on, 4) { SecondaryButton("Use Sample Code", on) { send(Action.SampleCodeTapped) } }
        },
    )
}

@Composable
private fun Waiting(on: Boolean, send: (Action) -> Unit) {
    OnboardingPageLayout(
        isActive = on,
        id = "waiting",
        title = "Pairing",
        onBack = { send(Action.BackTapped) },
        top = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Entrance(on, 0) { Text("Code found.", style = largeTitleBold) }
                Entrance(on, 1) { Text("Waiting for your server to answer…", color = secondary()) }
            }
        },
        centre = { Spacer(Modifier.height(ApertureSide)) },
        below = {
            Entrance(on, 2) { Text("The signal stays quiet until it replies", style = OpenCodeTextStyles.subheadline, color = secondary()) }
        },
        footer = {
            Entrance(on, 3) { SecondaryButton("Cancel", on) { send(Action.CancelTapped) } }
        },
    )
}

@Composable
private fun Connected(on: Boolean, send: (Action) -> Unit) {
    OnboardingPageLayout(
        isActive = on,
        id = "connected",
        title = "Connected",
        onBack = { send(Action.BackTapped) },
        top = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Entrance(on, 0) {
                    Box(
                        Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = OpenCodeIconSize.large)
                    }
                }
                Entrance(on, 1) { Text("You’re connected.", style = largeTitleBold) }
                Entrance(on, 2) { Text("My Mac is ready.", color = secondary()) }
            }
        },
        centre = { Spacer(Modifier.height(120.dp)) },
        below = {
            Entrance(on, 3) { Text("Your next session starts here", style = OpenCodeTextStyles.subheadline, color = secondary()) }
        },
        footer = {
            Entrance(on, 4) { PrimaryButton("Start a Session", on) { send(Action.StartSessionTapped) } }
            Entrance(on, 5) { SecondaryButton("Pair Again", on) { send(Action.ScanAgainTapped) } }
        },
    )
}

@Composable
private fun Failed(on: Boolean, failedWith: Failure, send: (Action) -> Unit) {
    OnboardingPageLayout(
        isActive = on,
        id = "failed",
        title = "Pairing",
        onBack = { send(Action.BackTapped) },
        top = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Entrance(on, 0) {
                    Crossfade(failedWith == Failure.wrongCode, label = "failedTitle") { wrongCode ->
                        Text(
                            if (wrongCode) "Couldn’t read that\npairing code" else "Couldn’t connect\nto your server",
                            style = largeTitleBold,
                        )
                    }
                }
                Entrance(on, 1) {
                    Crossfade(failedWith == Failure.wrongCode, label = "failedDetail") { wrongCode ->
                        Text(
                            if (wrongCode) {
                                "Run opencode pair --remote again, then scan the new code."
                            } else {
                                "Check that OpenCode is running on your computer, then try again."
                            },
                            color = secondary(),
                        )
                    }
                }
            }
        },
        centre = { Spacer(Modifier.height(120.dp)) },
        below = {},
        footer = {
            Entrance(on, 2) { PrimaryButton("Try Again", on) { send(Action.TryAgainTapped) } }
            Entrance(on, 3) { SecondaryButton("Scan Again", on) { send(Action.ScanAgainTapped) } }
        },
    )
}

/**
 * A page's frame: a header, a block above the shared centre, the centre (the aperture or the
 * trace), a block below it, and a fixed footer. The centre lands at the same height on every
 * page, so the trace and the aperture stay put while the words around them change.
 *
 * Only the active page takes touches and is read by TalkBack (iOS `allowsHitTesting` and
 * `accessibilityHidden`): it is raised above the others and their controls are switched off.
 */
@Composable
private fun OnboardingPageLayout(
    isActive: Boolean,
    id: String,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    top: @Composable () -> Unit,
    centre: @Composable () -> Unit,
    below: @Composable () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    OnboardingSkeleton(
        modifier = Modifier
            .zIndex(if (isActive) 1f else 0f)
            .then(if (isActive) Modifier.testTag("onboarding-page-$id") else Modifier.clearAndSetSemantics { }),
        header = {
            Entrance(isActive, 0, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (onBack != null) {
                        CircleIconButton(SFSymbol.ChevronBackward, "Back", isActive, onBack, Modifier.align(Alignment.CenterStart))
                    }
                    if (title != null) {
                        Text(title, style = OpenCodeTextStyles.headline, modifier = Modifier.semantics { heading() })
                    }
                }
            }
        },
        top = { CenteredText(top) },
        centre = centre,
        below = { CenteredText(below) },
        footer = footer,
    )
}

@Composable
private fun CenteredText(content: @Composable () -> Unit) {
    ProvideTextStyle(LocalTextStyle.current.merge(OpenCodeTextStyles.body).copy(textAlign = TextAlign.Center), content)
}

/** The layout every page and the aperture share. */
@Composable
private fun OnboardingSkeleton(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    top: @Composable () -> Unit,
    centre: @Composable () -> Unit,
    below: @Composable () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(52.dp)) { header() }
            Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 20.dp), contentAlignment = Alignment.BottomCenter) { top() }
            centre()
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.TopCenter) { below() }
            Column(
                Modifier.fillMaxWidth().heightIn(min = FooterHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = footer,
            )
        }
    }
}

private val FooterHeight = 128.dp
private val ApertureSide = 240.dp

/**
 * The camera aperture: one view for the camera and waiting pages, so it stays put when the code
 * is found. A plain opening. Reports its bounds (in root dp) for the field's scan rings.
 */
@Composable
private fun ApertureLayer(
    phase: Phase,
    cameraFeed: CameraFeed,
    reduceMotion: Boolean,
    onAperture: (Rect) -> Unit,
    onScanned: (String) -> Unit,
) {
    val shownTarget = phase == Phase.camera || phase == Phase.waiting
    val shown = animateFloatAsState(if (shownTarget) 1f else 0f, OnboardingMotion.layout(reduceMotion), label = "aperture")
    val waiting = animateFloatAsState(if (phase == Phase.waiting) 1f else 0f, OnboardingMotion.layout(reduceMotion), label = "found")
    val shape = RoundedCornerShape(32.dp)
    val density = LocalDensity.current.density
    OnboardingSkeleton(
        header = {},
        top = {},
        centre = {
            Box(
                Modifier
                    .size(ApertureSide)
                    .onGloballyPositioned { coordinates ->
                        val b = coordinates.boundsInRoot()
                        onAperture(Rect(b.left / density, b.top / density, b.right / density, b.bottom / density))
                    }
                    .graphicsLayer {
                        val s = shown.value
                        alpha = s
                        val scale = if (reduceMotion) 1f else 1f - 0.04f * (1f - s)
                        scaleX = scale
                        scaleY = scale
                        val blur = if (reduceMotion) 0f else (4f * (1f - s)).dp.toPx()
                        renderEffect = if (blur > 0.25f) BlurEffect(blur, blur, TileMode.Decal) else null
                    }
                    .clip(shape)
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                    .then(
                        if (shownTarget) {
                            Modifier.semantics { contentDescription = if (cameraFeed == CameraFeed.live) "Camera" else "Simulated camera" }
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ),
            ) {
                if (cameraFeed == CameraFeed.live) {
                    QRCameraPreview(isRunning = phase == Phase.camera, modifier = Modifier.fillMaxSize(), onScanned = onScanned)
                } else {
                    SimulatedCameraFeed(isRunning = phase == Phase.camera && !reduceMotion, modifier = Modifier.fillMaxSize())
                }
                // Code found: the feed dims under the mark while the server answers.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f * waiting.value }.background(Color.Black))
                OpenCodeIcon(
                    SFSymbol.Checkmark,
                    contentDescription = null,
                    size = 40.dp,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            val w = waiting.value
                            alpha = w
                            val scale = if (reduceMotion) 1f else 0.9f + 0.1f * w
                            scaleX = scale
                            scaleY = scale
                            val blur = if (reduceMotion) 0f else (3f * (1f - w)).dp.toPx()
                            renderEffect = if (blur > 0.25f) BlurEffect(blur, blur, TileMode.Decal) else null
                        },
                )
            }
        },
        below = {},
        footer = {},
    )
}

/** The first session the pairing opens: a new draft on the paired Mac. */
@Composable
private fun FirstSessionPage(
    isActive: Boolean,
    /** Revealed by the heartbeat wipe rather than faded in. */
    wipes: Boolean,
    send: (Action) -> Unit,
    onHeaderLine: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = isActive || wipes
    val density = LocalDensity.current.density
    Column(
        modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .then(if (isActive) Modifier.testTag("onboarding-page-session") else Modifier.clearAndSetSemantics { }),
    ) {
        Entrance(
            on,
            0,
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .onGloballyPositioned { coordinates -> onHeaderLine(coordinates.boundsInRoot().bottom / density + 10f) },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircleIconButton(SFSymbol.Line3Horizontal, "Open Sessions", isActive, { send(Action.BackTapped) }, Modifier.align(Alignment.CenterStart))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics(mergeDescendants = true) { heading() },
                ) {
                    Text("New Session", style = OpenCodeTextStyles.headline)
                    Text("My Mac", style = OpenCodeTextStyles.caption1, color = secondary())
                }
                CircleIconButton(SFSymbol.Ellipsis, "Session Actions", isActive, {}, Modifier.align(Alignment.CenterEnd))
            }
        }
        Spacer(Modifier.weight(1f))
        Entrance(on, 1, Modifier.fillMaxWidth()) {
            Text("What are we building?", style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.weight(1f))
        Entrance(on, 2, Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(26.dp))
                    .padding(start = 18.dp, end = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Message" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ask anything…", color = secondary(), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).background(OpenCodeTheme.colors.tertiaryText, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.ArrowUp, contentDescription = null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.canvas)
                }
            }
        }
    }
}

@Composable
private fun StoryLine(symbol: SFSymbol, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(symbol, contentDescription = null, tint = secondary())
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = OpenCodeTextStyles.body.copy(textAlign = TextAlign.Start))
    }
}

/** The label-coloured primary (iOS `.openCodePrimaryButton().tint(Color(.label))`). Off while its page is inactive. */
@Composable
private fun PrimaryButton(title: String, active: Boolean, onClick: () -> Unit) {
    val container = OpenCodeTheme.colors.text
    val content = OpenCodeTheme.colors.canvas
    Button(
        onClick = onClick,
        enabled = active,
        modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeButtonDefaults.height),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
        contentPadding = OpenCodeButtonDefaults.contentPadding,
    ) { Text(title, style = OpenCodeTextStyles.headline) }
}

@Composable
private fun SecondaryButton(title: String, active: Boolean, onClick: () -> Unit) {
    val colors = ButtonDefaults.filledTonalButtonColors()
    FilledTonalButton(
        onClick = onClick,
        enabled = active,
        modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeButtonDefaults.height),
        colors = colors.copy(disabledContainerColor = colors.containerColor, disabledContentColor = colors.contentColor),
        contentPadding = OpenCodeButtonDefaults.contentPadding,
    ) { Text(title, style = OpenCodeTextStyles.headline) }
}

/** iOS's large circular glass icon button; off (without dimming) while its page is inactive. */
@Composable
private fun CircleIconButton(symbol: SFSymbol, label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    val content = OpenCodeTheme.colors.text
    FilledTonalIconButton(
        onClick = onClick,
        enabled = active,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
    ) { OpenCodeIcon(symbol, contentDescription = label) }
}

/**
 * The heartbeat wipe's mask, its edge where the field's front is (the shader's `frontX`): the
 * first session shows behind the front, the page before it ahead of it, feathered across the
 * edge. Without the wipe nothing is masked.
 */
private fun Modifier.wipeMask(progress: State<Float>, revealing: Boolean, active: Boolean): Modifier {
    if (!active) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = size.width
            val feather = width * 0.08f
            val edge = (-0.1f + progress.value * 1.2f) * width
            val stops = if (revealing) listOf(Color.Black, Color.Transparent) else listOf(Color.Transparent, Color.Black)
            drawRect(
                Brush.horizontalGradient(stops, startX = edge - feather, endX = edge + feather, tileMode = TileMode.Clamp),
                blendMode = BlendMode.DstIn,
            )
        }
}
