package ai.opencode.app

import ai.opencode.core.store.StoreOf
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.store.rememberScope
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeView
import ai.opencode.server.OpenCodeServer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Port of iOS `AppView` (`OpenCode/App/AppView.swift`): Home when a server is connected,
 * else the connection screen; the New Server / Edit Server sheet over either.
 */
@Composable
fun AppView(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    LaunchedEffect(store) { store.send(AppFeature.Action.Task) }
    val hasHome by store.observe { it.home != null }
    val isEditingServer by store.observe { it.isEditingServer }
    OpenCodeAppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (hasHome) {
                val home = store.rememberScope<AppFeature.State, AppFeature.Action, HomeFeature.State, HomeFeature.Action>(
                    state = { it.home ?: HomeFeature.State.placeholder(OpenCodeServer(url = "", username = "", password = "")) },
                    action = AppFeature.Action::Home,
                )
                HomeView(home)
            } else {
                ConnectionView(store)
            }
            if (isEditingServer) ServerFormSheet(store)
        }
    }
}

/** iOS `legacyConnectionView` (the default while the Title-Is-the-Switch experiment is off). */
@Composable
private fun ConnectionView(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val savedServers by store.observe { it.savedServers.value }
    val errorMessage by store.observe { it.errorMessage }
    val progress by store.observe { it.connectionProgressLabel }
    val connectingID by store.observe { it.connectingServerID }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = if (savedServers.isEmpty()) 24.dp else 0.dp)) {
                Image(
                    painter = painterResource(R.drawable.opencode_wordmark),
                    contentDescription = "OpenCode",
                    modifier = Modifier.size(width = 200.dp, height = 36.dp),
                    alignment = Alignment.CenterStart,
                )
                Text(
                    if (savedServers.isEmpty()) "Keep your coding sessions moving from your phone."
                    else "Pick up where you left off, or pair another computer.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errorMessage?.let { ConnectionErrorCard(store, it) }
            if (savedServers.isEmpty()) {
                SetupSteps(store)
            } else {
                savedServers.forEach { server ->
                    SavedServerRow(server, isConnecting = connectingID == server.id, progress = progress) {
                        store.send(AppFeature.Action.ServerTapped(server))
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = { store.send(AppFeature.Action.AddServerTapped) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Enter a Server Manually") }
            Button(
                onClick = { store.send(AppFeature.Action.ScanPairingCodeTapped) },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (progress != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(progress!!)
                } else {
                    Text("Scan Pairing Code")
                }
            }
        }
    }
}

/** What a new user needs on their computer before the scan can work. */
@Composable
private fun SetupSteps(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "On your computer, open OpenCode and go to Settings → Pairing, or type /pair in OpenCode in your terminal. Then scan the code with this phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Don’t have OpenCode yet?", style = MaterialTheme.typography.titleMedium)
        CopyCommand("curl -fsSL https://opencode.ai/v2/install | bash") { store.send(AppFeature.Action.CopyTextTapped(it)) }
        Text("Or run this in a terminal", style = MaterialTheme.typography.titleMedium)
        CopyCommand("opencode pair --remote") { store.send(AppFeature.Action.CopyTextTapped(it)) }
        Text("It works from any network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopyCommand(command: String, onCopy: (String) -> Unit) {
    Surface(
        onClick = { onCopy(command) },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Copy $command" },
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Text(command, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = OpenCodeFonts.berkeleyMono))
        }
    }
}

@Composable
private fun SavedServerRow(server: OpenCodeServer, isConnecting: Boolean, progress: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (isConnecting && progress != null) {
                    Text(progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (server.displayName != server.host) {
                    Text(server.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (isConnecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ConnectionErrorCard(store: StoreOf<AppFeature.State, AppFeature.Action>, message: String) {
    val phase by store.observe { it.errorPhase }
    val context by store.observe { it.errorContext }
    val title = when (phase) {
        AppFeature.FailureContext.Phase.Pair -> "Couldn’t Pair"
        AppFeature.FailureContext.Phase.LoadSessions -> "Couldn’t Load Sessions"
        else -> "Couldn’t connect to ${context?.friendlyName ?: "server"}"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            context?.takeIf { it.details.isNotEmpty() }?.let {
                Text(it.detailText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                context?.let {
                    Text("Phase: ${it.phase.rawValue}", style = MaterialTheme.typography.bodySmall)
                    Text("Server: ${it.serverLabel}", style = MaterialTheme.typography.bodySmall)
                    Text("Attempt: ${it.attempt}", style = MaterialTheme.typography.bodySmall)
                }
                Text("OpenCode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                store.state.errorReport(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), java.time.Instant.now())
                    ?.let { store.send(AppFeature.Action.CopyTextTapped(it)) }
            }) { Text("Copy Error Report") }
        }
    }
}

/** iOS `credentialForm` in its "New Server" / "Edit Server" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormSheet(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val state by store.observe { it }
    var revealsPassword by remember { mutableStateOf(false) }
    val isConnectDisabled = state.connectionProgressLabel != null || state.serverURL.isBlank() || state.headerProblem != null
    ModalBottomSheet(
        onDismissRequest = { store.send(AppFeature.Action.CancelEditingTapped) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { store.send(AppFeature.Action.CancelEditingTapped) }) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Text(if (state.editingSavedServer == null) "New Server" else "Edit Server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(width = 72.dp, height = 1.dp))
            }
            state.errorMessage?.let { ConnectionErrorCard(store, it) }
            OutlinedTextField(
                value = state.serverName,
                onValueChange = { store.send(AppFeature.Action.ServerNameChanged(it)) },
                label = { Text("Name") },
                placeholder = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.serverURL,
                onValueChange = { store.send(AppFeature.Action.ServerURLChanged(it)) },
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.20:4096") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = { store.send(AppFeature.Action.UsernameChanged(it)) },
                label = { Text("Username") },
                placeholder = { Text("opencode") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = { store.send(AppFeature.Action.PasswordChanged(it)) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (revealsPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!isConnectDisabled) store.send(AppFeature.Action.ConnectButtonTapped) }),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { revealsPassword = !revealsPassword }) {
                        OpenCodeIcon(
                            if (revealsPassword) SFSymbol.EyeSlash else SFSymbol.Eye,
                            contentDescription = if (revealsPassword) "Hide Password" else "Show Password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { store.send(AppFeature.Action.ConnectButtonTapped) },
                enabled = !isConnectDisabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (state.connectionProgressLabel != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                }
                Text(state.connectionProgressLabel ?: if (state.editingSavedServer == null) "Connect" else "Save and connect")
            }
            Text(
                if (state.editingSavedServer == null) {
                    "Paste the link opencode pair --remote prints to pair, or use the address and password your OpenCode server runs with. The username is usually opencode."
                } else {
                    "Changes are saved once this server connects."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
