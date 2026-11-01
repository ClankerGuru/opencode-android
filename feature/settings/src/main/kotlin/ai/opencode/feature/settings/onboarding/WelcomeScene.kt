package ai.opencode.feature.settings.onboarding

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.TunableSpring
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** The welcome's knobs (iOS `WelcomeTunables`); the story's own are with the story, not ported yet. */
object WelcomeTunables {
    val devices = TunableSpring("welcome.motion.devices", "Devices settle", duration = 0.8, bounce = 0.12)
    val intro = TunableSpring("welcome.motion.intro", "Copy entrance", duration = 0.6, bounce = 0.0)
    val step = TunableSpring("welcome.motion.step", "Next step", duration = 0.55, bounce = 0.0)

    fun register() {
        val store = TuningStore.shared
        for (spring in listOf(devices, intro, step)) store.register(spring)
    }
}

/** iOS `TuningContext.welcome`. */
val TuningContext.Companion.welcome: TuningContext get() = TuningContext("welcome", label = "Welcome")

/** The desktop's logical screen (iOS `WelcomeDesktop.size`). */
internal object WelcomeDesktop {
    val size = Size(720f, 450f)
}

/** The phone's logical screen (iOS `WelcomePhone.size`). */
internal object WelcomePhone {
    val size = Size(440f, 956f)
}

/**
 * Where the laptop and phone sit (iOS `WelcomeSceneLayout`): the phone stands in front of the
 * lid's right edge, a little taller than the laptop and centred on it, so neither leaves a dead
 * band beside the other. Everything scales with the width it's given, capped by `maxHeight`.
 * Values are in dp.
 */
data class WelcomeSceneLayout(
    val size: Size,
    val lid: Rect,
    val laptopScreen: Rect,
    val base: Rect,
    val phone: Rect,
    val phoneScreen: Rect,
) {
    val laptopScale: Float get() = laptopScreen.width / WelcomeDesktop.size.width
    val phoneScale: Float get() = phoneScreen.width / WelcomePhone.size.width

    /** A point in the desktop's logical space, in the scene. */
    fun desktop(point: Offset): Offset =
        Offset(laptopScreen.left + point.x * laptopScale, laptopScreen.top + point.y * laptopScale)

    /** A point in the phone's logical space, in the scene. */
    fun phonePoint(point: Offset): Offset =
        Offset(phoneScreen.left + point.x * phoneScale, phoneScreen.top + point.y * phoneScale)

    companion object {
        /** The phone's height over the laptop's. */
        const val PHONE_RATIO = 1.45f

        /** How much of the phone's width stands over the laptop. */
        const val OVERLAP = 0.8f

        operator fun invoke(width: Float, maxHeight: Float): WelcomeSceneLayout {
            // Laid out for a lid 1 dp wide, then scaled.
            val lidBezel = 0.016f
            val screen = Size(1 - 2 * lidBezel, (1 - 2 * lidBezel) * WelcomeDesktop.size.height / WelcomeDesktop.size.width)
            val lidHeight = screen.height + 2 * lidBezel
            val baseOverhang = 0.043f
            val baseHeight = 0.024f
            val laptopWidth = 1 + 2 * baseOverhang
            val laptopHeight = lidHeight + baseHeight
            val phoneHeight = laptopHeight * PHONE_RATIO
            val phoneBezelRatio = 0.026f
            val phoneAspect = WelcomePhone.size.height / WelcomePhone.size.width
            val phoneWidth = phoneHeight / ((1 - 2 * phoneBezelRatio) * phoneAspect + 2 * phoneBezelRatio)
            val unit = Size(laptopWidth + phoneWidth * (1 - OVERLAP), phoneHeight)
            val scale = max(0f, min(width / unit.width, maxHeight / unit.height))

            val size = Size(unit.width * scale, unit.height * scale)
            val laptopTop = (phoneHeight - laptopHeight) / 2 * scale
            val lid = Rect(Offset(baseOverhang * scale, laptopTop), Size(scale, lidHeight * scale))
            val laptopScreen = Rect(
                Offset(lid.left + lidBezel * scale, lid.top + lidBezel * scale),
                Size(screen.width * scale, screen.height * scale),
            )
            val base = Rect(Offset(0f, lid.bottom - 0.5f), Size(laptopWidth * scale, baseHeight * scale))
            val phone = Rect(Offset(size.width - phoneWidth * scale, 0f), Size(phoneWidth * scale, phoneHeight * scale))
            val phoneBezel = phoneWidth * phoneBezelRatio * scale
            val phoneScreen = phone.deflate(phoneBezel)
            return WelcomeSceneLayout(size, lid, laptopScreen, base, phone, phoneScreen)
        }
    }
}

private fun hex(value: Long, alpha: Float = 1f): Color = Color(0xFF000000 or value).copy(alpha = alpha)

/**
 * The welcome's illustration (iOS `WelcomeScene`): the laptop running OpenCode and the phone
 * steering it. iOS tells `WelcomeStory` on a loop (dictation, the reply streaming, the screenshot
 * flying back); that story isn't ported yet, so this draws its resting frame: the devices settle
 * in on the iOS springs, and the pairing step cross-fades both screens to Settings › Pairing and
 * the camera finding its code.
 */
@Composable
fun WelcomeScene(layout: WelcomeSceneLayout, pairing: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReduceMotion()
    val laptopIn = remember { Animatable(if (reduced) 1f else 0f) }
    val phoneIn = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        coroutineScope {
            launch { laptopIn.animateTo(1f, WelcomeTunables.devices.animation(reduceMotion = false, delaySeconds = 0.36)) }
            launch { phoneIn.animateTo(1f, WelcomeTunables.devices.animation(reduceMotion = false, delaySeconds = 0.56)) }
        }
    }
    val pairingProgress by animateFloatAsState(
        if (pairing) 1f else 0f,
        animationSpec = WelcomeTunables.step.animation(reduced),
        label = "welcome-pairing",
    )
    val label = if (pairing) {
        "OpenCode’s pairing code on a laptop, and a phone’s camera scanning it"
    } else {
        "Someone dictates a task on a phone, OpenCode does it on a laptop and takes a screenshot, and the screenshot comes back to the phone"
    }
    Box(
        modifier
            .size(layout.size.width.dp, layout.size.height.dp)
            .semantics {
                contentDescription = label
                role = Role.Image
            },
    ) {
        Laptop(layout, pairingProgress, Modifier.welcomeSettle(laptopIn.value, 26.dp, reduced))
        Phone(layout, pairingProgress, Modifier.welcomeSettle(phoneIn.value, 54.dp, reduced))
    }
}

/** A device settling in: it rises on a spring and fades in early (iOS `welcomeSettle`). */
private fun Modifier.welcomeSettle(p: Float, dy: Dp, reduced: Boolean): Modifier = graphicsLayer {
    alpha = (p * 1.8f).coerceIn(0f, 1f)
    val scale = if (reduced) 1f else 0.965f + 0.035f * p
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 1f)
    translationY = if (reduced) 0f else dy.toPx() * (1 - p)
}

/** Places [content] at [rect] (dp) inside the scene, at exactly that size. */
@Composable
private fun Placed(rect: Rect, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .offset(rect.left.dp, rect.top.dp)
            .requiredSize(rect.width.dp, rect.height.dp),
    ) { content() }
}

/** [content] laid out at `logical` size and scaled by [scale] from the top-left (iOS `.scaleEffect(_, anchor: .topLeading)`). */
@Composable
private fun ScaledScreen(logical: Size, scale: Float, content: @Composable () -> Unit) {
    Layout(
        content = {
            Box(
                Modifier
                    .requiredSize(logical.width.dp, logical.height.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) { content() }
        },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            // requiredSize centres an oversized child; undo that so it scales from the corner.
            placeable.place(0, 0)
        }
    }
}

@Composable
private fun Laptop(layout: WelcomeSceneLayout, pairing: Float, modifier: Modifier) {
    val lid = layout.lid
    val screen = layout.laptopScreen
    val base = layout.base
    val corner = lid.width * 0.035f
    val lidShape = RoundedCornerShape(corner.dp, corner.dp, (corner * 0.22f).dp, (corner * 0.22f).dp)
    val screenShape = RoundedCornerShape((corner * 0.56f).dp, (corner * 0.56f).dp, 0.dp, 0.dp)
    val baseShape = RoundedCornerShape(1.2.dp, 1.2.dp, (base.height * 0.92f).dp, (base.height * 0.92f).dp)
    Box(modifier.size(layout.size.width.dp, layout.size.height.dp)) {
        Placed(lid) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(22.dp, lidShape, ambientColor = Color.Black.copy(alpha = 0.42f), spotColor = Color.Black.copy(alpha = 0.42f))
                    .background(Brush.verticalGradient(listOf(hex(0x141416), hex(0x060607))), lidShape)
                    .border(
                        0.9.dp,
                        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.35f to Color.White.copy(alpha = 0.11f), 1f to Color.White.copy(alpha = 0.04f)),
                        lidShape,
                    ),
            )
        }
        Placed(screen) {
            Box(Modifier.fillMaxSize().clip(screenShape).border(0.5.dp, Color.White.copy(alpha = 0.06f), screenShape)) {
                ScaledScreen(WelcomeDesktop.size, layout.laptopScale) { DesktopScreen(pairing) }
            }
            // The camera notch.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size((lid.width * 0.1f).dp, max(2.2f, lid.width * 0.016f).dp)
                    .background(hex(0x050506), RoundedCornerShape(0.dp, 0.dp, 2.5.dp, 2.5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val dot = max(1.6f, lid.width * 0.0065f).dp
                Box(Modifier.size(dot).background(hex(0x182238), RoundedCornerShape(50)))
            }
        }
        Placed(base) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(14.dp, baseShape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.55f))
                    .background(Brush.verticalGradient(0f to hex(0x4F4F55), 0.5f to hex(0x2E2E33), 1f to hex(0x151518)), baseShape)
                    .clip(baseShape)
                    .background(
                        // Cylindrical falloff at the curved aluminum ends.
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.36f),
                            0.07f to Color.Transparent,
                            0.93f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.36f),
                        ),
                    ),
            ) {
                // Hinge seam and the machined lip highlight.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.65.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.34f), Color.Transparent))),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .size((base.width * 0.145f).dp, (base.height * 0.42f).dp)
                        .background(hex(0x1F1F23), RoundedCornerShape(0.dp, 0.dp, 3.dp, 3.dp)),
                )
            }
        }
    }
}

@Composable
private fun Phone(layout: WelcomeSceneLayout, pairing: Float, modifier: Modifier) {
    val phone = layout.phone
    val screen = layout.phoneScreen
    val corner = phone.width * 0.174f
    val outerShape = RoundedCornerShape(corner.dp)
    val innerShape = RoundedCornerShape(max(0f, corner - (screen.left - phone.left)).dp)
    Box(modifier.size(layout.size.width.dp, layout.size.height.dp)) {
        Placed(phone) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(22.dp, outerShape, ambientColor = Color.Black.copy(alpha = 0.52f), spotColor = Color.Black.copy(alpha = 0.55f))
                    .background(
                        Brush.linearGradient(0f to hex(0x525257), 0.38f to hex(0x252529), 0.78f to hex(0x3B3B40), 1f to hex(0x1E1E22)),
                        outerShape,
                    )
                    .border(
                        0.85.dp,
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.2f))),
                        outerShape,
                    ),
            )
        }
        Placed(screen) {
            Box(Modifier.fillMaxSize().clip(innerShape).border(0.5.dp, Color.White.copy(alpha = 0.06f), innerShape)) {
                ScaledScreen(WelcomePhone.size, layout.phoneScale) { PhoneScreen(pairing) }
            }
        }
    }
}

// MARK: Screens (the story's resting frame; iOS `WelcomeDesktop`, `WelcomePhone`)

private val deskBackground = hex(0x0B0B0C)
private val deskText = hex(0xEDEDED)
private val deskDim = hex(0x8A8A8A)

/** One of two screens sharing a place: it fades and sharpens in as the other leaves. */
private fun Modifier.screenSwap(shown: Float, grows: Float = 0f): Modifier = graphicsLayer {
    alpha = shown
    val scale = 1f + grows * (1 - shown)
    scaleX = scale
    scaleY = scale
}

@Composable
private fun DesktopScreen(pairing: Float) {
    Box(Modifier.fillMaxSize().background(deskBackground)) {
        DesktopSession(Modifier.screenSwap(1 - pairing))
        DesktopPairing(Modifier.screenSwap(pairing, grows = -0.04f))
    }
}

/** OpenCode on the laptop, the task done: the prompt, its tools, and the push. */
@Composable
private fun DesktopSession(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hero section", color = deskText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("···", color = deskDim, fontSize = 15.sp)
        }
        Box(Modifier.align(Alignment.End).background(hex(0x1C1C1F), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Make the hero bolder and send me a screenshot", color = deskText, fontSize = 14.sp)
        }
        DeskToolRow("Explored", "1 read, 1 search")
        DeskToolRow("Browser", "localhost:3000")
        DeskToolRow("Edit", "Hero.tsx", added = "+6", removed = "−1")
        DeskToolRow("Screenshot", "opencode.png")
        DeskToolRow("Running commands", "git push", monospaced = true)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(64.dp).background(hex(0x161618), RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("Big Pickle", color = deskDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun DeskToolRow(title: String, detail: String, added: String? = null, removed: String? = null, monospaced: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(deskDim, RoundedCornerShape(50)))
        Text(title, color = deskText, fontSize = 13.sp)
        Text(detail, color = deskDim, fontSize = 13.sp, fontFamily = if (monospaced) OpenCodeFonts.berkeleyMono else null)
        if (added != null) Text(added, color = hex(0x3FB950), fontSize = 13.sp)
        if (removed != null) Text(removed, color = hex(0xF0605A), fontSize = 13.sp)
    }
}

/** Settings › Pairing on the laptop, with its code. */
@Composable
private fun DesktopPairing(modifier: Modifier) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.width(170.dp).fillMaxSize().background(hex(0x111113)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (title in listOf("General", "Models", "Providers", "Pairing")) {
                Text(title, color = if (title == "Pairing") deskText else deskDim, fontSize = 13.sp)
            }
        }
        Column(Modifier.weight(1f).padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pairing", color = deskText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Connect another device to this machine’s OpenCode server.", color = deskDim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().background(hex(0x161618), RoundedCornerShape(12.dp)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pair a device", color = deskText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Scan the code with the OpenCode app on another device, or copy the link.", color = deskDim, fontSize = 11.sp)
                MockCode(Modifier.size(150.dp))
                Text("Copy link", color = deskText, fontSize = 12.sp)
                Text("Expires in 4:52 · New code", color = deskDim, fontSize = 11.sp)
            }
        }
    }
}

/** A drawn stand-in for a pairing code: fixed modules, not a readable code. */
@Composable
private fun MockCode(modifier: Modifier) {
    Canvas(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp)) {
        val cells = 21
        val cell = size.minDimension / cells
        fun finder(x: Int, y: Int) {
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(7 * cell, 7 * cell))
            drawRect(Color.White, Offset((x + 1) * cell, (y + 1) * cell), Size(5 * cell, 5 * cell))
            drawRect(Color.Black, Offset((x + 2) * cell, (y + 2) * cell), Size(3 * cell, 3 * cell))
        }
        finder(0, 0)
        finder(cells - 7, 0)
        finder(0, cells - 7)
        for (y in 0 until cells) for (x in 0 until cells) {
            val inFinder = (x < 8 && y < 8) || (x >= cells - 8 && y < 8) || (x < 8 && y >= cells - 8)
            if (!inFinder && ((x * 7 + y * 13 + x * y) % 5 < 2)) {
                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun PhoneScreen(pairing: Float) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PhoneSession(Modifier.screenSwap(1 - pairing))
        PhoneViewfinder(Modifier.screenSwap(pairing, grows = 0.04f))
    }
}

/** The app on the phone: the screenshot came back under the reply. */
@Composable
private fun PhoneSession(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("9:41", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp))
        Spacer(Modifier.height(40.dp))
        Box(Modifier.align(Alignment.End).background(hex(0x1C1C1F), RoundedCornerShape(26.dp)).padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text("Make the hero bolder and send me a screenshot", color = Color.White, fontSize = 24.sp)
        }
        Text("Done. The hero is bolder and pushed; here it is.", color = Color.White, fontSize = 24.sp)
        Box(Modifier.fillMaxWidth().height(240.dp).background(Brush.verticalGradient(listOf(hex(0x2A2A30), hex(0x16161A))), RoundedCornerShape(22.dp)))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(96.dp).background(hex(0x1C1C1F), RoundedCornerShape(48.dp)).padding(horizontal = 32.dp), contentAlignment = Alignment.CenterStart) {
            Text("Message", color = deskDim, fontSize = 24.sp)
        }
    }
}

/** The pairing step: the camera finds the laptop's code (iOS `WelcomeViewfinderCorners`). */
@Composable
private fun PhoneViewfinder(modifier: Modifier) {
    Box(modifier.fillMaxSize().background(hex(0x101012)), contentAlignment = Alignment.Center) {
        MockCode(Modifier.size(200.dp).alpha(0.9f))
        Canvas(Modifier.size(280.dp)) {
            val length = size.minDimension * 0.18f
            val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            val w = size.width
            val h = size.height
            for ((corner, dx, dy) in listOf(Triple(Offset(0f, 0f), 1f, 1f), Triple(Offset(w, 0f), -1f, 1f), Triple(Offset(0f, h), 1f, -1f), Triple(Offset(w, h), -1f, -1f))) {
                drawLine(Color.White, corner, corner + Offset(dx * length, 0f), stroke.width, stroke.cap)
                drawLine(Color.White, corner, corner + Offset(0f, dy * length), stroke.width, stroke.cap)
            }
        }
        Box(Modifier.wrapContentSize())
    }
}
