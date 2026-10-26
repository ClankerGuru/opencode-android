package ai.opencode.feature.settings

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModel
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceKeyFingerprint
import ai.opencode.feature.voice.VoiceKeySource
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `VoiceServerPickerTests.swift` ("Voice Server"), the `:feature:settings` part:
 * the four `VoiceSettingsFeature` tests, `homeSavesTheChoiceAndAsksTheServerItNames`
 * ([HomeSettingsFeature] lives here on Android), and the Settings checks of
 * `everyEntryPointRoutesByTheChosenServersSupport` and `thisIPhoneUsesThePhonesOwnKey` (their
 * `VoiceRoutePreferences` checks and the call are `:feature:voice`'s port, their
 * `SessionFeature.State` checks `:feature:session`'s).
 */
class VoiceServerPickerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")
    private val grokBox = OpenCodeServer(url = "http://grok.local:4096", username = "opencode", password = "grok", name = "Grok Box")
    private val oldBox = OpenCodeServer(url = "http://old.local:4096", username = "opencode", password = "old", name = "Old Box")
    private val gptLive = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = true)
    private val grok = ServerVoiceModel(model = "grok-voice-1", provider = "xai", available = true)
    private val geminiLive = ServerVoiceModel(model = "gemini-3.8-live", provider = "google", available = true)

    /** `servers` saved and `server` the one in use, with Server Voice on and `choice` in Voice Server. */
    private fun SettingsTestShared.save(servers: List<OpenCodeServer>, choice: VoiceServerChoice = VoiceServerChoice.Connected) {
        set(ServerSharedKeys.savedServers, servers)
        set(ServerSharedKeys.savedServer, server)
        set(experimental(ExperimentalFeature.serverVoice), true)
        set(VoiceSharedKeys.voiceServerChoice, choice)
    }

    private fun SettingsTestShared.setSupport(support: Map<String, ServerVoiceSupport>) =
        set(VoiceSharedKeys.serverVoiceSupportByServer, support)

    private fun SettingsTestShared.savePhoneKey() {
        set(SharedKeys.openAIAPIKey, "sk-phone")
        set(SharedKeys.verifiedVoiceKeyFingerprint, VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
    }

    private fun serverVoice(models: suspend (OpenCodeServer) -> List<ServerVoiceModel>?) = ServerVoiceClient(
        checkSupport = { throw AssertionError("Unused") },
        models = models,
        createSession = { _, _ -> throw AssertionError("Unused") },
    )

    /**
     * Settings asks every saved server and lists the others that can broker a call: not a
     * Grok-only server or one without the list. A server that doesn't answer next time keeps its
     * last answer.
     */
    @Test fun settingsAsksEverySavedServerAndListsTheOthersThatCanBrokerACall() = runTest {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio, grokBox, oldBox))
        val clock = TestClock()
        val round = Locked(1)
        val asked = Locked(listOf<String>())
        val order = listOf(server, studio, grokBox, oldBox).map { it.persistenceID }
        val store = TestStore(VoiceSettingsFeature.State(server = server), VoiceSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[Clock] = clock
            this[ServerVoiceClient] = serverVoice { requested ->
                asked.update { it + requested.persistenceID }
                // Each answers a second after the one before it, so answers arrive in order.
                clock.sleep(((order.indexOf(requested.persistenceID).takeIf { it >= 0 } ?: 0) + 1).seconds)
                when (requested.persistenceID) {
                    server.persistenceID -> listOf(gptLive)
                    studio.persistenceID -> if (round.value == 1) listOf(gptLive) else null
                    grokBox.persistenceID -> if (round.value == 1) listOf(grok) else listOf(grok, geminiLive)
                    else -> emptyList()
                }
            }
        }
        store.withDependencies { assertTrue(store.state.voiceServerOptions.isEmpty()) }

        store.send(VoiceSettingsFeature.Action.Task)
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, listOf(gptLive))) {
            it.copy(voiceModelsByServer = it.voiceModelsByServer + (server.persistenceID to listOf(gptLive)))
        }
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(studio.persistenceID, listOf(gptLive))) {
            it.copy(voiceModelsByServer = it.voiceModelsByServer + (studio.persistenceID to listOf(gptLive)))
        }
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(grokBox.persistenceID, listOf(grok))) {
            it.copy(voiceModelsByServer = it.voiceModelsByServer + (grokBox.persistenceID to listOf(grok)))
        }
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(oldBox.persistenceID, emptyList())) {
            it.copy(voiceModelsByServer = it.voiceModelsByServer + (oldBox.persistenceID to emptyList()))
        }
        assertEquals(order.sorted(), asked.value.sorted())
        store.withDependencies { assertEquals(listOf(studio), store.state.voiceServerOptions) }

        round.set(2)
        store.send(VoiceSettingsFeature.Action.Task)
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(server.persistenceID, listOf(gptLive)))
        clock.advance(1.seconds)
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(grokBox.persistenceID, listOf(grok, geminiLive))) {
            it.copy(voiceModelsByServer = it.voiceModelsByServer + (grokBox.persistenceID to listOf(grok, geminiLive)))
        }
        clock.advance(1.seconds)
        store.receive(VoiceSettingsFeature.Action.VoiceModelsLoaded(oldBox.persistenceID, emptyList()))
        assertEquals(8, asked.value.size)
        store.withDependencies {
            assertEquals(listOf(studio, grokBox), store.state.voiceServerOptions)
            assertEquals(VoiceServerChoice.Connected, store.state.voiceServerSelection)
            assertEquals("My Mac", store.state.voiceServerValue)
        }
        store.finish()
    }

    /** With Server Voice off the page asks no one. */
    @Test fun settingsAsksNoServerWithServerVoiceOff() = runTest {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio))
        shared.set(experimental(ExperimentalFeature.serverVoice), false)
        val store = TestStore(VoiceSettingsFeature.State(server = server), VoiceSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[ServerVoiceClient] = serverVoice { throw AssertionError("Server Voice is off") }
        }
        store.send(VoiceSettingsFeature.Action.Task)
        store.withDependencies { assertFalse(store.state.broker.isEnabled) }
        store.finish()
    }

    /**
     * The chosen server stays listed and selected while its check is pending. A choice that's no
     * longer saved, or that is the connected server, shows and brokers as the connected server;
     * This Phone brokers nothing.
     */
    @Test fun theChoiceStaysVisibleAndAStaleOneFallsBackToTheConnectedServer() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(studio))
        val settings = VoiceSettingsFeature.State(server = server)
        shared.read {
            assertEquals(listOf(studio), settings.voiceServerOptions)
            assertEquals(VoiceServerChoice.of(studio), settings.voiceServerSelection)
            assertEquals("Studio", settings.voiceServerValue)
            assertTrue(settings.broker.isEnabled && settings.broker.server == studio)

            val onStudio = VoiceSettingsFeature.State(server = studio)
            assertTrue(onStudio.voiceServerOptions.isEmpty())
            assertEquals(VoiceServerChoice.Connected, onStudio.voiceServerSelection)
            assertEquals("Studio", onStudio.voiceServerValue)
            assertEquals(studio, onStudio.broker.server)
        }

        shared.set(ServerSharedKeys.savedServers, listOf(server))
        shared.read {
            assertTrue(settings.voiceServerOptions.isEmpty())
            assertEquals(VoiceServerChoice.Connected, settings.voiceServerSelection)
            assertEquals("My Mac", settings.voiceServerValue)
            assertEquals(server, settings.broker.server)
        }

        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        shared.read {
            assertEquals(VoiceServerChoice.Phone, settings.voiceServerSelection)
            assertEquals("This Phone", settings.voiceServerValue)
            assertTrue(!settings.broker.isEnabled && settings.broker.server == null)
        }
    }

    /**
     * A broker that lists the call's model as OpenCode's starts it through its OpenCode Console
     * connection, so the key row names OpenCode and the footer the workspace it bills. Any other
     * answer keeps the provider's key.
     */
    @Test fun theKeyRowNamesOpenCodeWhenTheBrokerHostsTheCallThroughIt() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio))
        val hosted = ServerVoiceModel(model = "gpt-live-1", provider = "opencode", available = true)
        var settings = VoiceSettingsFeature.State(server = server)
            .copy(voiceModelsByServer = mapOf(server.persistenceID to listOf(hosted), studio.persistenceID to listOf(gptLive)))
        shared.read {
            assertEquals(VoiceKeySource.OpenCode, settings.keySource)
            assertEquals("Key on Your Server", settings.keyRowTitle)
            assertEquals("OpenCode", settings.keyBadgeTitle)
            assertEquals("Calls are billed to your server’s OpenCode Console workspace.", settings.keySource.footer)

            settings = settings.copy(
                voiceModelsByServer = settings.voiceModelsByServer +
                    (server.persistenceID to listOf(ServerVoiceModel(model = "gpt-live-1", provider = "opencode", available = false))),
            )
            assertEquals("A listing without the connection", VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
            settings = settings.copy(voiceModelsByServer = settings.voiceModelsByServer + (server.persistenceID to listOf(hosted)))
        }

        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.realtime)
        shared.read {
            assertEquals("OpenCode hosts only GPT-Live there", VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
            assertEquals("Calls use your server’s OpenAI key.", settings.keySource.footer)
        }
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)

        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.of(studio))
        shared.read {
            assertEquals("Studio brokers with its own OpenAI key", VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
        }

        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        shared.read { assertEquals(VoiceKeySource.Phone(VoiceProvider.openAI), settings.keySource) }
    }

    /**
     * Home saves Voice Server and asks the server it names whether it brokers calls, keeping the
     * answer under that server: the chosen one, the connected one once the chosen one is gone, and
     * none for This Phone.
     */
    @Test fun homeSavesTheChoiceAndAsksTheServerItNames() = runTest {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio))
        val asked = Locked(listOf<String>())
        val store = TestStore(HomeSettingsFeature.State(server = server), HomeSettingsFeature(), this) {
            this[SharedStore] = shared.store
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { requested ->
                    asked.update { it + requested.persistenceID }
                    ServerVoiceSupport.supported
                },
                models = { throw AssertionError("Unused") },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
        }

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.of(studio)))
        assertEquals(VoiceServerChoice.of(studio), shared.get(VoiceSharedKeys.voiceServerChoice))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(studio.persistenceID, ServerVoiceSupport.supported))
        assertEquals(mapOf(studio.persistenceID to ServerVoiceSupport.supported), shared.get(VoiceSharedKeys.serverVoiceSupportByServer))

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Phone))
        assertEquals(VoiceServerChoice.Phone, shared.get(VoiceSharedKeys.voiceServerChoice))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)

        store.send(HomeSettingsFeature.Action.VoiceServerChanged(VoiceServerChoice.Saved(serverID = "srv_gone")))
        store.send(HomeSettingsFeature.Action.CheckServerVoiceSupport)
        store.receive(HomeSettingsFeature.Action.ServerVoiceSupportLoaded(server.persistenceID, ServerVoiceSupport.supported))
        assertEquals(listOf(studio.persistenceID, server.persistenceID), asked.value)
        assertEquals(
            mapOf(studio.persistenceID to ServerVoiceSupport.supported, server.persistenceID to ServerVoiceSupport.supported),
            shared.get(VoiceSharedKeys.serverVoiceSupportByServer),
        )
        store.finish()
    }

    /** Settings' half of iOS `everyEntryPointRoutesByTheChosenServersSupport`: never the connected server's support. */
    @Test fun everyEntryPointRoutesByTheChosenServersSupport() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(studio))
        shared.savePhoneKey()
        shared.set(SharedKeys.voiceModeEnabled, true)
        val settings = VoiceSettingsFeature.State(server = server)

        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.unsupported))
        shared.read {
            assertEquals(VoiceRoute.PhoneKey(VoiceEngine.gptLive), settings.route)
            assertTrue(settings.showsServerUnsupported)
        }

        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.unsupported, studio.persistenceID to ServerVoiceSupport.supported))
        shared.read {
            assertEquals(VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive), settings.route)
            assertFalse(settings.showsServerUnsupported)
            assertEquals(VoiceKeySource.Server(providerName = "OpenAI"), settings.keySource)
        }
    }

    /** Settings' half of iOS `thisIPhoneUsesThePhonesOwnKey`: This Phone uses the phone's own key, and without one offers no call. */
    @Test fun thisIPhoneUsesThePhonesOwnKey() {
        val shared = SettingsTestShared()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.Phone)
        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.supported))
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.read {
            assertEquals(
                VoiceRoute.Unavailable(VoiceRoute.Reason.MissingPhoneKey(VoiceEngine.gptLive)),
                VoiceSettingsFeature.State(server = server).route,
            )
        }

        shared.savePhoneKey()
        shared.read {
            val settings = VoiceSettingsFeature.State(server = server)
            assertEquals(VoiceRoute.PhoneKey(VoiceEngine.gptLive), settings.route)
            assertEquals(VoiceKeySource.Phone(VoiceProvider.openAI), settings.keySource)
        }
    }
}
