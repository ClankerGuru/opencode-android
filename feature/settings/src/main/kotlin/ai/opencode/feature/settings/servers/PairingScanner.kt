package ai.opencode.feature.settings.servers

import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeButtonDefaults
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.core.ui.rememberAppAppearance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Port of iOS `Settings/PairingScanner.swift`: the camera scanner for the QR code `opencode pair`
 * prints, presented full screen by `:app` (“Scan Pairing Code”).
 *
 * Honest about the camera, with a paste path every time: without a camera it asks for the pairing
 * link to be pasted; with access off it offers Open Settings (when Android would no longer ask) and
 * Paste Pairing Link. Otherwise it asks for access once, then shows the camera under a viewfinder,
 * with Paste in the bar. One code per presentation.
 *
 * The scanner doesn't validate: [onScanned] gets the raw text (a scanned code or the clipboard's
 * non-blank text: `{"code","urls"}` JSON, an `opencode.ai/pair` page, a deep link or a single
 * `…/auth/connect/…` https URL), which `:app` decodes with `OpenCodePairingLink.decode`, plays
 * its success or error haptic for, and redeems. Never log it.
 *
 * Place it where a [CameraPermissionHost] can register (this view adds its own).
 *
 * @param onScanned the code's or clipboard's text.
 * @param onCancel Cancel in the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScannerView(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraPermissionHost()
    val camera = remember { dependency(ServerCameraClient) }
    val pasteboard = remember { dependency(PasteboardClient) }
    val isCameraAvailable = remember { camera.isAvailable() }
    var cameraAccess by remember { mutableStateOf(camera.authorization()) }
    var pasteFailed by rememberSaveable { mutableStateOf(false) }
    val isAccessDenied = cameraAccess == CameraAuthorization.denied
    // Coming back from system Settings with access turned on shows the camera (iOS relaunches).
    LifecycleResumeEffect(Unit) {
        if (cameraAccess == CameraAuthorization.denied && camera.authorization() == CameraAuthorization.authorized) {
            cameraAccess = CameraAuthorization.authorized
        }
        onPauseOrDispose {}
    }

    fun paste() {
        val text = pasteboard.string()
        if (text == null || text.isBlank()) {
            pasteFailed = true
            return
        }
        onScanned(text)
    }

    // The capture session starts only once access is granted: asked while the prompt is up, it
    // wouldn't start after Allow (iOS), so the camera waits for the answer.
    LaunchedEffect(Unit) {
        if (cameraAccess != CameraAuthorization.notDetermined || !isCameraAvailable) return@LaunchedEffect
        camera.authorize()
        cameraAccess = camera.authorization()
    }

    // VoiceOver starts on the navigation title, a header (iOS `.navigationTitle`), and comes back to
    // it once the camera prompt is answered; TalkBack would start on Cancel (QA OB-B-04).
    val titleFocus = rememberAccessibilityFocusRequest(cameraAccess)

    val appearance = rememberAppAppearance()
    OpenCodeTheme(theme = appearance.theme, appearance = AppearanceColorScheme.Dark) {
        Scaffold(
            modifier = modifier,
            containerColor = Color.Black,
            topBar = {
                OpenCodeTopBar(
                    centerTitle = true,
                    hairline = false,
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    title = {
                        Text(
                            "Scan Pairing Code",
                            style = OpenCodeTextStyles.headline,
                            modifier = Modifier.accessibilityFocusRequest(titleFocus).semantics { heading() },
                        )
                    },
                    navigation = { TextButton(onClick = onCancel) { Text("Cancel") } },
                    actions = {
                        if (isCameraAvailable && !isAccessDenied) {
                            TextButton(onClick = ::paste) { Text("Paste") }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                when {
                    !isCameraAvailable -> PairingScannerFallback(
                        symbol = "camera.metering.unknown",
                        title = "No Camera Available",
                        message = "Copy the link that `opencode pair --remote` prints on your computer, then paste it here.",
                        settings = false,
                        onOpenSettings = camera.openSettings,
                        onPaste = ::paste,
                        modifier = Modifier.padding(padding),
                    )
                    isAccessDenied -> PairingScannerFallback(
                        symbol = "camera.fill",
                        title = "Camera Access Is Off",
                        message = "Allow camera access in Settings to scan the QR code, or paste the link that `opencode pair --remote` prints.",
                        settings = true,
                        onOpenSettings = camera.openSettings,
                        onPaste = ::paste,
                        modifier = Modifier.padding(padding),
                    )
                    else -> {
                        if (cameraAccess == CameraAuthorization.authorized) {
                            QRCameraPreview(isRunning = true, once = true, onScanned = onScanned, modifier = Modifier.fillMaxSize())
                        }
                        PairingViewfinder(Modifier.padding(padding))
                    }
                }
            }
        }
        if (pasteFailed) {
            OpenCodeAlertDialog(
                onDismissRequest = { pasteFailed = false },
                confirmButton = { TextButton(onClick = { pasteFailed = false }) { Text("OK") } },
                title = { Text("Nothing to Paste") },
                text = {
                    Text("Your clipboard doesn’t have a pairing link. Copy the link that opencode pair --remote prints, then try again.")
                },
            )
        }
    }
}

@Composable
private fun PairingViewfinder(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(250.dp)
                .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(28.dp))
                .clearAndSetSemantics {},
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Point your camera at the QR code", style = OpenCodeTextStyles.headline, color = Color.White, textAlign = TextAlign.Center)
            Text(
                buildAnnotatedString {
                    append("Run ")
                    withStyle(SpanStyle(fontFamily = OpenCodeFonts.berkeleyMono, fontSize = 15.sp)) { append("opencode pair --remote") }
                    append(" on your computer to show it.")
                },
                style = OpenCodeTextStyles.subheadline,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PairingScannerFallback(
    symbol: String,
    title: String,
    message: String,
    settings: Boolean,
    onOpenSettings: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 34.dp, tint = Color.White.copy(alpha = 0.75f))
        Text(title, style = OpenCodeTextStyles.headline, color = Color.White, textAlign = TextAlign.Center)
        Text(
            inlineCode(message),
            style = OpenCodeTextStyles.subheadline,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Column(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings) {
                PasteButton(prominent = true, onClick = onOpenSettings) { Text("Open Settings") }
            }
            PasteButton(prominent = !settings, onClick = onPaste) {
                OpenCodeIcon("doc.on.clipboard", contentDescription = null, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("Paste Pairing Link")
            }
        }
    }
}

/** iOS `PasteButtonStyle`: prominent is a white primary button with black text; otherwise secondary. */
@Composable
private fun PasteButton(prominent: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (prominent) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = OpenCodeButtonDefaults.height),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            contentPadding = OpenCodeButtonDefaults.contentPadding,
            content = content,
            shape = OpenCodeShape.control,
        )
    } else {
        OpenCodeSecondaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** Markdown's `code` spans (iOS `LocalizedStringKey`) in monospace. */
internal fun inlineCode(text: String): AnnotatedString = buildAnnotatedString {
    text.split('`').forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontFamily = OpenCodeFonts.monospace, fontWeight = FontWeight.Normal)) { append(part) }
        else append(part)
    }
}
