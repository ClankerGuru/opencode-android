package ai.opencode.feature.settings.servers

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.settings.SettingsDivider
import ai.opencode.feature.settings.SettingsSection
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.feature.settings.settingsSection
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** iOS `ServerTitleTunables` (`servers.*`), baked defaults. */
object ServerTitleTunables {
    /** Server row height. */
    val rowHeight = 64.dp
    /** Camera height. */
    val cameraHeight = 236.dp
    /** Wrong-code shake, in dp. */
    const val shake = 5f
    /** Space between cards. */
    val sectionSpacing = 24.dp
}

private enum class ConnectField { address, username, password }

/** The header starts fading once scrolled this far, and is gone after [headerFadeLength] more. */
private const val headerFadeStart = 16f
private const val headerFadeLength = 64f

/**
 * Port of iOS `Settings/ServerConnectView.swift`: the Servers sheet's Connect Your Computer page
 * (also the whole first-run screen when the sheet is `connectOnly`). The app mark and title, the
 * `opencode pair --remote` command with Copy, then Scan Code / Enter Address in place: the camera
 * under a viewfinder (Enable Camera until access is granted) with Paste Pairing Link, or an Address,
 * Username and Password form with Connect. Actions show progress in place; a wrong code shakes the
 * camera (not under Reduce Motion) and the reason shows under the action.
 *
 * Everything goes through [store] ([ServerTitleFeature]); scanned and pasted text leaves as
 * `Delegate.Pair`, a typed address as `Delegate.Connect`.
 *
 * @param contentPadding the bar's and system insets, so the header can scroll under the bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerConnectView(
    store: ServerTitleStore,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    CameraPermissionHost()
    val method by store.observe { it.method }
    val connectOnly by store.observe { it.connectOnly }
    val busy by store.observe { it.busy }
    val error by store.observe { it.error }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    var focus by remember { mutableStateOf<ConnectField?>(null) }
    val connectRequester = remember { BringIntoViewRequester() }
    // With the keyboard up the page scrolls the header under the bar: fade it out on the way.
    val headerOpacity by remember {
        derivedStateOf {
            val scrolled = if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE
            else with(density) { listState.firstVisibleItemScrollOffset.toDp().value }
            val opacity = 1 - (scrolled - headerFadeStart) / headerFadeLength
            (opacity.coerceIn(0f, 1f) * 20).roundToInt() / 20f
        }
    }
    LaunchedEffect(method) {
        focusManager.clearFocus()
        focus = null
    }
    // Once the keyboard is up, keep the field and Connect above it.
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        delay(350)
        connectRequester.bringIntoView()
    }
    val connect = {
        focusManager.clearFocus()
        store.send(ServerTitleFeature.Action.Connect)
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize().imePadding(),
    ) {
        item(key = "header") {
            ConnectHeader(connectOnly, Modifier.graphicsLayer { alpha = headerOpacity })
        }
        settingsSection(key = "command") {
            ServerCommandRow("opencode pair --remote", onCopy = { store.send(ServerTitleFeature.Action.Copy(it)) })
        }
        item(key = "method") {
            MethodPicker(method, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                store.send(ServerTitleFeature.Action.MethodChanged(it))
            }
        }
        when (method) {
            ServerTitleFeature.Method.scan -> scanSection(store, busy, error, connectRequester)
            ServerTitleFeature.Method.address -> addressSection(
                store = store,
                busy = busy,
                error = error,
                onFocus = { focus = it },
                onConnect = connect,
                connectRequester = connectRequester,
            )
        }
    }
}

@Composable
private fun ConnectHeader(connectOnly: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            // First run has no bar items, so the header takes the bar's place.
            .padding(top = if (connectOnly) 28.dp else 4.dp, bottom = 12.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ServerMarkTile()
        Text(
            "Connect Your Computer",
            style = OpenCodeTextStyles.title1.copy(fontWeight = FontWeight.Bold),
            color = OpenCodeTheme.colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "Run this in a terminal on your computer.",
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun MethodPicker(method: ServerTitleFeature.Method, modifier: Modifier = Modifier, onChange: (ServerTitleFeature.Method) -> Unit) {
    val options = listOf(ServerTitleFeature.Method.scan to "Scan Code", ServerTitleFeature.Method.address to "Enter Address")
    SingleChoiceSegmentedButtonRow(
        modifier
            .fillMaxWidth()
            .testTag("servers-connection-method")
            .semantics { contentDescription = "Connection method" },
    ) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = method == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = 48.dp),
                icon = {},
            ) { Text(label) }
        }
    }
}

/**
 * Scan Code / Enter Address swap their rows in place, animated as iOS's
 * `$store.method.animation(.smooth(duration: 0.3))`.
 */
private fun Modifier.methodSwap(scope: LazyItemScope): Modifier = with(scope) {
    val smooth = MotionSpring(0.3)
    animateItem(fadeInSpec = smooth.spec(), placementSpec = smooth.spec(IntOffset.VisibilityThreshold), fadeOutSpec = smooth.spec())
}

private fun LazyListScope.scanSection(
    store: ServerTitleStore,
    busy: Boolean,
    error: String?,
    connectRequester: BringIntoViewRequester,
) {
    item(key = "camera") {
        ScanCamera(store, Modifier.methodSwap(this).padding(horizontal = 16.dp, vertical = 8.dp))
    }
    item(key = "paste") {
        ConnectAction(
            modifier = Modifier.methodSwap(this),
            title = "Paste Pairing Link",
            symbol = "clipboard",
            prominent = false,
            busy = busy,
            error = error,
            tag = "servers-paste",
            requester = connectRequester,
        ) { store.send(ServerTitleFeature.Action.Paste) }
    }
}

@Composable
private fun ScanCamera(store: ServerTitleStore, modifier: Modifier = Modifier) {
    val cameraAllowed by store.observe { it.cameraAllowed }
    val method by store.observe { it.method }
    val busy by store.observe { it.busy }
    val failureRevision by store.observe { it.failureRevision }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val reduceMotion = rememberReduceMotion()
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failureRevision) {
        if (failureRevision == 0 || reduceMotion) return@LaunchedEffect
        val distance = ServerTitleTunables.shake
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes {
            durationMillis = 260
            -distance at 60 using LinearEasing
            distance at 120 using LinearEasing
            -distance / 2 at 180 using LinearEasing
        })
    }
    val density = LocalDensity.current
    Box(
        modifier
            .offset { IntOffset(with(density) { shake.value.dp.roundToPx() }, 0) }
            .fillMaxWidth()
            .height(ServerTitleTunables.cameraHeight)
            .clip(RoundedCornerShape(26.dp))
            .background(if (cameraAllowed) Color.Black else OpenCodeTheme.colors.groupedCard),
        contentAlignment = Alignment.Center,
    ) {
        if (cameraAllowed) {
            QRCameraPreview(
                isRunning = method == ServerTitleFeature.Method.scan && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !busy,
                modifier = Modifier.fillMaxSize(),
            ) { store.send(ServerTitleFeature.Action.Scanned(it)) }
            ServerViewfinder(Color.White.copy(alpha = 0.85f), Modifier.size(156.dp))
        } else {
            ServerViewfinder(OpenCodeTheme.colors.tertiaryText, Modifier.size(156.dp))
            FilledTonalButton(onClick = { store.send(ServerTitleFeature.Action.RequestCamera) }, modifier = Modifier.heightIn(min = 48.dp)) {
                OpenCodeIcon("camera", contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.text)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Enable Camera",
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.text,
                )
            }
        }
    }
}

private fun LazyListScope.addressSection(
    store: ServerTitleStore,
    busy: Boolean,
    error: String?,
    onFocus: (ConnectField?) -> Unit,
    onConnect: () -> Unit,
    connectRequester: BringIntoViewRequester,
) {
    item(key = "address-form", contentType = "settings-section") {
        SettingsSection(Modifier.methodSwap(this)) {
            AddressForm(store, onFocus, onConnect)
        }
    }
    item(key = "connect") {
        val address by store.observe { it.address }
        ConnectAction(
            modifier = Modifier.methodSwap(this),
            title = "Connect",
            symbol = null,
            prominent = true,
            busy = busy,
            error = error,
            enabled = address.isNotBlank(),
            tag = "servers-connect",
            requester = connectRequester,
            onClick = onConnect,
        )
    }
}

@Composable
private fun AddressForm(store: ServerTitleStore, onFocus: (ConnectField?) -> Unit, onConnect: () -> Unit) {
    val address by store.observe { it.address }
    val username by store.observe { it.username }
    val password by store.observe { it.password }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    fun Modifier.tracking(field: ConnectField) = onFocusChanged { if (it.isFocused) onFocus(field) }

    ConnectFieldRow("Address") {
        ConnectTextField(
            value = address,
            onValueChange = { store.send(ServerTitleFeature.Action.AddressChanged(it)) },
            placeholder = "my-mac:4096",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            actions = KeyboardActions(onNext = { usernameFocus.requestFocus() }),
            modifier = Modifier.tracking(ConnectField.address).testTag("servers-address"),
        )
    }
    SettingsDivider()
    ConnectFieldRow("Username") {
        ConnectTextField(
            value = username,
            onValueChange = { store.send(ServerTitleFeature.Action.UsernameChanged(it)) },
            placeholder = "Username",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            actions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.focusRequester(usernameFocus).tracking(ConnectField.username),
        )
    }
    SettingsDivider()
    ConnectFieldRow("Password") {
        ConnectTextField(
            value = password,
            onValueChange = { store.send(ServerTitleFeature.Action.PasswordChanged(it)) },
            placeholder = "Optional",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            actions = KeyboardActions(onGo = { onConnect() }),
            password = true,
            modifier = Modifier.focusRequester(passwordFocus).tracking(ConnectField.password),
        )
    }
}

@Composable
private fun ConnectFieldRow(label: String, content: @Composable () -> Unit) {
    val labelWidth = (104 * LocalDensity.current.fontScale).dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.width(labelWidth))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ConnectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    val clear = Color.Transparent
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = OpenCodeTextStyles.body) },
        textStyle = OpenCodeTextStyles.body,
        singleLine = true,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = clear,
            unfocusedContainerColor = clear,
            disabledContainerColor = clear,
            focusedIndicatorColor = clear,
            unfocusedIndicatorColor = clear,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * One of the page's two actions, the width of the cards; it shows the connection in progress in
 * place. Only Connect is prominent: on the scan side the camera leads and Paste stays secondary.
 * The failure shows beneath it, in orange.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectAction(
    title: String,
    symbol: String?,
    prominent: Boolean,
    busy: Boolean,
    error: String?,
    tag: String,
    requester: BringIntoViewRequester,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    Modifier.graphicsLayer { alpha = if (busy) 0f else 1f },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (symbol != null) {
                        OpenCodeIcon(symbol, contentDescription = null, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(title)
                }
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp).semantics { contentDescription = "Connecting" },
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.LocalContentColor.current,
                    )
                }
            }
        }
        val buttonModifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).testTag(tag)
        if (prominent) {
            OpenCodePrimaryButton(onClick = onClick, modifier = buttonModifier, enabled = enabled && !busy, content = content)
        } else {
            OpenCodeSecondaryButton(onClick = onClick, modifier = buttonModifier, enabled = !busy, content = content)
        }
        ConnectFailure(error)
    }
}

@Composable
private fun ConnectFailure(error: String?) {
    AnimatedContent(error, transitionSpec = { fadeIn(MotionSpring(0.3, 0.0).spec()) togetherWith fadeOut(MotionSpring(0.3, 0.0).spec()) }, label = "failure") {
        if (it != null) {
            Text(
                it,
                style = OpenCodeTextStyles.footnote,
                color = SettingsTint.Orange.color(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
        }
    }
}
