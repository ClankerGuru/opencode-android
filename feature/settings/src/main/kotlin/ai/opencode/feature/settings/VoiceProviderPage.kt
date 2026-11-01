package ai.opencode.feature.settings

import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceProviderOption
import ai.opencode.feature.voice.VoiceProviderState
import ai.opencode.feature.voice.VoiceServerChoice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import ai.opencode.core.store.observe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Port of iOS `Settings/VoiceProviderPage.swift` (ce694097, Server Voice experiment): Settings ›
 * Voice's Voice Provider row, its page, and Your OpenAI Account. The tile and status are drawn
 * here (iOS `VoiceProviderTile`/`VoiceProviderStatus` in `Voice/VoiceProviderViews.swift`, not
 * yet ported by voice) with the iOS sizes as constants instead of its tunables.
 */

private object VoiceProviderSizes {
    val rowTile = 52.dp
    val headerTile = 72.dp
    val listTile = 36.dp
    const val CORNER_RATIO = 0.27f
    const val SYMBOL_RATIO = 0.44f
    const val UNAVAILABLE_OPACITY = 0.6f
}

/**
 * Settings › Voice's Voice Provider row (the Server Voice experiment): who runs calls, where, and
 * whether they can now. It opens the provider's page.
 */
@Composable
internal fun VoiceProviderSettingsRow(state: VoiceSettingsFeature.State, onClick: () -> Unit) {
    val active = state.providerCatalog.active
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Voice Provider"
            stateDescription = listOfNotNull(active.name, active.state.label).joinToString(", ")
        },
        onClick = onClick,
    ) {
        Row(
            Modifier.weight(1f).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceProviderTile(active, VoiceProviderSizes.rowTile)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Voice Provider", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                Text(active.name, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text)
                VoiceProviderStatus(active.state)
            }
        }
        SettingsChevron()
    }
}

/**
 * The provider calls use: who it is and where it runs, whether calls may move on when it isn't
 * ready, and the other providers to switch to. [opensAccount] (a failed call's card) pushes Your
 * OpenAI Account on arrival.
 */
@Composable
internal fun VoiceProviderDetailPage(store: SettingsStore, opensAccount: Boolean = false) {
    val voice = rememberVoiceStore(store)
    val local by voice.observe { it }
    @Suppress("UNUSED_VARIABLE") val revision = rememberVoiceSharedRevision()
    val state = local
    val settings = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    val catalog = state.providerCatalog
    val active = catalog.active
    var showsUnavailable by rememberSaveable { mutableStateOf(false) }
    var openedAccount by rememberSaveable { mutableStateOf(false) }
    val openAccount = { store.send(SettingsFeature.Action.PagePushed(SettingsPage.VoiceAccount)) }
    LaunchedEffect(opensAccount) {
        if (opensAccount && !openedAccount) {
            openedAccount = true
            openAccount()
        }
    }

    /** Stores [option] as the person's pick, or Automatic when null. */
    fun use(option: VoiceProviderOption?) {
        if (option == null) {
            if (state.serverVoiceProvider != ServerVoiceProvider.openAI) {
                settings(HomeSettingsFeature.Action.ServerVoiceProviderChanged(ServerVoiceProvider.openAI))
            }
            settings(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Automatic))
            return
        }
        if (option.provider != state.serverVoiceProvider) {
            settings(HomeSettingsFeature.Action.ServerVoiceProviderChanged(option.provider))
        }
        settings(HomeSettingsFeature.Action.VoiceServerChanged(option.choice))
    }

    SettingsPageScaffold(title = "Voice Provider", store = store) {
        item(key = "header") {
            VoiceProviderHeader(
                option = active,
                title = active.title,
                subtitle = if (active.brand == null) null else "On ${active.placeName}",
                state = active.state,
            )
        }
        if (active.place == VoiceProviderOption.Place.Phone) {
            settingsSection(key = "account") {
                SettingsRow("API Key", symbol = null, value = state.keyBadgeTitle, onClick = openAccount)
            }
        }
        settingsSection(
            key = "automatic",
            footer = footerText("When ${active.placeName} isn’t ready, calls use another provider that is."),
        ) {
            SettingsToggleRow(
                "Choose Automatically",
                checked = catalog.isAutomatic,
                onCheckedChange = { use(if (it) null else active) },
            )
        }
        settingsSection(key = "others", header = "Other Providers") {
            var rows = 0
            catalog.others.forEach { other ->
                if (rows++ > 0) SettingsDivider()
                VoiceProviderListRow(other, showsStatus = false) {
                    FilledTonalButton(
                        onClick = { use(other) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Use ${other.name}" },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) { Text("Use", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)) }
                }
            }
            if (active.place != VoiceProviderOption.Place.Phone) {
                if (rows++ > 0) SettingsDivider()
                VoiceProviderListRow(catalog.account, showsStatus = catalog.account.isReady, onClick = openAccount) {
                    if (!catalog.account.isReady) {
                        Text("Set Up", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                    }
                    SettingsChevron()
                }
            }
            if (catalog.unavailable.isNotEmpty()) {
                if (rows++ > 0) SettingsDivider()
                SettingsRowFrame(onClick = { showsUnavailable = !showsUnavailable }) {
                    Text(
                        "${catalog.unavailable.size} Not Available",
                        modifier = Modifier.weight(1f).padding(start = VoiceProviderSizes.listTile + 12.dp),
                        style = OpenCodeTextStyles.body,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                    OpenCodeIcon(
                        SFSymbol.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.rotate(if (showsUnavailable) 90f else 0f),
                        size = 18.dp,
                        tint = OpenCodeTheme.colors.tertiaryText,
                    )
                }
                if (showsUnavailable) {
                    catalog.unavailable.forEach { other ->
                        SettingsDivider()
                        VoiceProviderListRow(other) {}
                    }
                }
            }
        }
    }
}

/** Your OpenAI Account, on this phone: the one place its key appears. */
@Composable
internal fun VoiceProviderAccountPage(store: SettingsStore) {
    val voice = rememberVoiceStore(store)
    val local by voice.observe { it }
    @Suppress("UNUSED_VARIABLE") val revision = rememberVoiceSharedRevision()
    val state = local
    val settings = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    // Picked outright, not by Automatic.
    val isPicked = state.voiceServer == VoiceServerChoice.Phone
    // The key, revealed or about to be, out of every screenshot.
    HidesFromScreenshots()

    SettingsPageScaffold(title = "Your Account", store = store) {
        item(key = "header") {
            VoiceProviderHeader(
                option = null,
                title = "Your OpenAI Account",
                subtitle = "On This Phone",
                state = null,
            )
        }
        settingsSection(
            key = "key",
            footer = footerText("Calls from this phone use your OpenAI account instead of a server."),
        ) {
            VoiceKeyEditor(state, voice, settings)
        }
        if (state.hasOpenAIKey && !isPicked) {
            settingsSection(key = "use") {
                SettingsButtonRow("Use for Calls", onClick = {
                    settings(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Phone))
                })
            }
        }
    }
}

/** A provider page's header: its tile, name, where it runs and its state. [option] null is Your OpenAI Account. */
@Composable
private fun VoiceProviderHeader(option: VoiceProviderOption?, title: String, subtitle: String?, state: VoiceProviderState?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (option != null) VoiceProviderTile(option, VoiceProviderSizes.headerTile) else VoiceProviderTile(TileKind.openAIAccount, VoiceProviderSizes.headerTile)
        Text(
            title,
            modifier = Modifier.padding(top = 14.dp).semantics { heading() },
            style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
            )
        }
        if (state != null && state != VoiceProviderState.unknown) {
            Box(Modifier.padding(top = 12.dp)) { VoiceProviderStatus(state, prominent = true) }
        }
    }
}

/**
 * A provider in a list: its tile, name and where it runs, then a trailing accessory. A server that
 * can't run calls shows its name and why.
 */
@Composable
private fun VoiceProviderListRow(
    option: VoiceProviderOption,
    showsStatus: Boolean = true,
    onClick: (() -> Unit)? = null,
    accessory: @Composable () -> Unit,
) {
    val isAvailable = option.brand != null
    SettingsRowFrame(onClick = onClick, role = if (onClick == null) null else androidx.compose.ui.semantics.Role.Button) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.alpha(if (isAvailable) 1f else VoiceProviderSizes.UNAVAILABLE_OPACITY)) {
                VoiceProviderTile(option, VoiceProviderSizes.listTile)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    option.title,
                    style = OpenCodeTextStyles.body,
                    color = if (isAvailable) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
                )
                if (isAvailable) {
                    Text(option.subtitle, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (showsStatus) VoiceProviderStatus(option.state)
        accessory()
    }
}

/** iOS `VoiceProviderTile.Kind`. */
private enum class TileKind { openAI, openAIAccount, openCode, gemini, device }

private val VoiceProviderOption.tileKind: TileKind
    get() = if (place == VoiceProviderOption.Place.Phone) {
        TileKind.openAIAccount
    } else {
        when (brand) {
            VoiceProviderOption.Brand.openAI -> TileKind.openAI
            VoiceProviderOption.Brand.openAIAccount -> TileKind.openAIAccount
            VoiceProviderOption.Brand.openCode -> TileKind.openCode
            VoiceProviderOption.Brand.gemini -> TileKind.gemini
            null -> TileKind.device
        }
    }

@Composable
private fun VoiceProviderTile(option: VoiceProviderOption, size: Dp) = VoiceProviderTile(option.tileKind, size)

/**
 * A logo-free tile: a waveform for OpenAI, a person for your own account, code brackets for
 * OpenCode, a sparkle for Gemini, the device for a server that can't run calls.
 */
@Composable
private fun VoiceProviderTile(kind: TileKind, size: Dp) {
    val symbol = when (kind) {
        TileKind.openAI -> "waveform"
        TileKind.openAIAccount -> "person.fill"
        TileKind.openCode -> "chevron.left.forwardslash.chevron.right"
        TileKind.gemini -> "sparkle"
        TileKind.device -> "desktopcomputer"
    }
    val colors = when (kind) {
        TileKind.openAI, TileKind.openAIAccount -> listOf(Color(0.13f, 0.72f, 0.56f), Color(0.04f, 0.52f, 0.42f))
        TileKind.openCode -> listOf(Color(0.32f, 0.32f, 0.32f), Color(0.12f, 0.12f, 0.12f))
        TileKind.gemini -> listOf(Color(0.36f, 0.47f, 1f), Color(0.52f, 0.3f, 0.93f))
        TileKind.device -> listOf(Color(0.62f, 0.62f, 0.62f), Color(0.5f, 0.5f, 0.5f))
    }
    val shape = RoundedCornerShape(size * VoiceProviderSizes.CORNER_RATIO)
    Box(
        Modifier
            .size(size)
            .background(Brush.verticalGradient(colors), shape)
            .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = size * VoiceProviderSizes.SYMBOL_RATIO, tint = Color.White)
    }
}

/** A provider's state as a symbol and a word, so it never rests on colour alone. Nothing while the state isn't known. */
@Composable
private fun VoiceProviderStatus(state: VoiceProviderState, prominent: Boolean = false) {
    val label = state.label ?: return
    val symbol = state.symbol ?: return
    val color = when (state) {
        VoiceProviderState.ready -> SettingsTint.Green.color()
        VoiceProviderState.noKey -> SettingsTint.Orange.color()
        VoiceProviderState.needsUpdate -> SettingsTint.Blue.color()
        VoiceProviderState.offline, VoiceProviderState.unknown -> OpenCodeTheme.colors.secondaryText
    }
    if (prominent) {
        Row(
            Modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon(symbol, contentDescription = null, size = 14.dp, tint = color)
            Text(label, style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon(symbol, contentDescription = null, size = 13.dp, tint = color)
            Text(label, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}
