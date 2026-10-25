package ai.opencode.feature.voice

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// iOS `VoiceFeature+Start.swift`: starting a call on its route — Server Voice's probe, the
// phone key's preflight, then the session.

fun VoiceFeature.State.setActiveRoute(route: VoiceRoute, usesDelegator: Boolean): VoiceFeature.State = copy(
    activeRoute = route,
    activeEngine = route.engine,
    activeProvider = route.provider,
    usesDelegator = usesDelegator,
)

internal fun VoiceFeature.startCall(state: VoiceFeature.State): Reduced<VoiceFeature.State, VoiceFeature.Action> {
    var next = state
    val target = next.target
    val broker = VoiceBroker(
        serverVoice = dependency(ExperimentalFeatures)(ExperimentalFeature.serverVoice),
        choice = next.voiceServer,
        connected = target.server,
        saved = { VoiceFeature.savedServers },
    )
    val serverVoiceEnabled = broker.isEnabled
    // Only the broker moves; the call's session, context and tools stay on its own server.
    val brokerServer = broker.server ?: target.server
    if (serverVoiceEnabled && brokerServer.persistenceID != next.supportServer.persistenceID) {
        // What the call knew of another server's support says nothing of this one's.
        next = next.copy(
            serverVoiceBroker = if (brokerServer.persistenceID == target.server.persistenceID) null else brokerServer,
            serverVoiceSupport = ServerVoiceSupport.unknown,
        )
    }
    val selectedEngine = next.resolvedEngine
    val selectedProvider = next.serverVoiceProvider
    val openAIKey = next.callAPIKey(VoiceEngine.gptLive)?.trim() ?: ""
    val xaiKey = next.callAPIKey(VoiceEngine.grok)?.trim() ?: ""
    val previousSupport = next.serverVoiceSupport
    val previousCached = next.serverVoiceSupportByServer[brokerServer.persistenceID]
    val initialSupport = if (previousSupport != ServerVoiceSupport.unknown) previousSupport else previousCached ?: ServerVoiceSupport.unknown
    val resolve: (ServerVoiceSupport) -> VoiceRoute = { support ->
        VoiceRoute.resolve(
            selectedEngine = selectedEngine,
            serverVoiceEnabled = serverVoiceEnabled,
            serverVoiceProvider = selectedProvider,
            serverVoiceSupport = support,
            hasOpenAIKey = openAIKey.isNotEmpty(),
            hasXAIKey = xaiKey.isNotEmpty(),
        )
    }
    val route = resolve(initialSupport)
    val shouldProbe = serverVoiceEnabled && selectedEngine != VoiceEngine.grok && initialSupport != ServerVoiceSupport.supported
    if (route is VoiceRoute.Unavailable && !shouldProbe) {
        return Reduced(next.withPhase(VoiceFeature.State.Phase.Failed(route.reason.message)))
    }
    val key = if (selectedEngine == VoiceEngine.grok) xaiKey else openAIKey
    val sounds = dependency(VoiceCallSounds)
    sounds.voice(VoiceCallHaptic.callStarted)
    val voiceCallLog = dependency(VoiceCallLogClient)
    val uuid = dependency(UuidClient)
    next = next.copy(callLogID = if (voiceCallLog.recorder == null) null else uuid.next())
        .withPhase(VoiceFeature.State.Phase.Connecting)
    AppLog.voice.notice("Call starting")
    val voiceDelegator = dependency(VoiceDelegatorClient)
    val delegatorEnabled = voiceDelegator.isEnabled && key != "lab"
    // Every call opens a fresh, unmuted microphone.
    next = next.copy(
        isMuted = false,
        isAudioInterrupted = false,
        isReconnecting = false,
        isAssistantSpeaking = false,
        connectedAt = null,
    ).clearLiveReply().copy(
        adoptedDraftSessionID = null,
        // A new call explains itself to each session again.
        handoffs = emptyMap(),
        // What this conversation said so far is an earlier call's now.
        previousCallTail = next.tail.lines,
        visitedSessionIDs = emptyList(),
        lastMove = null,
        startedTarget = target,
        startedViewing = next.viewing,
    ).setActiveRoute(route, usesDelegator = route.engine == VoiceEngine.gptLive && delegatorEnabled)
    val voice = next.callVoice.resolved(selectedEngine)
    // Through the server, OpenAI runs the delegator on the server's key
    // (Responses delegation); on the phone's key, the phone runs it.
    next = next.copy(
        activeVoice = voice,
        delegatorResponseID = null,
        // Android: the call's ongoing notification, started with the call (iOS's Home starts
        // its Live Activity from the same state).
        callPresenceID = uuid.next(),
    )
    val initialContext = next.instructionsContext
    val serverDelegation: LiveDelegation = if (selectedEngine == VoiceEngine.gptLive && delegatorEnabled) {
        LiveDelegation.responses(VoiceDelegator.responsesDelegation(context = delegatorContext(next, otherRequests = 0)))
    } else {
        LiveDelegation.client
    }
    next.atEarBox.current = next.isAtEar
    val atEarBox = next.atEarBox
    // A key Settings already verified for this model skips the preflight
    // (a ~0.7 s round trip, measured); a new one is still checked first.
    val verified = key.isNotEmpty() && next.verifiedKeyFingerprint == VoiceKeyFingerprint.make(key = key, model = selectedEngine.modelID)
    val startsAtEar = next.isAtEar
    val voiceAudio = dependency(VoiceAudioClient)
    val serverVoice = dependency(ServerVoiceClient)
    val realtimePreflight = dependency(RealtimePreflightClient)
    val voiceCall = dependency(VoiceCallClient)

    val effect = Effect.run<VoiceFeature.Action> { send ->
        // The call's audio starts with the tap, alongside the probe, the
        // preflight, and the context below; the call adopts it once set up.
        val preparedAudio = voiceAudio.prepare(startsAtEar)
        var discarded = false
        suspend fun discardPrepared() {
            if (discarded) return
            discarded = true
            withContext(NonCancellable) { voiceAudio.discardPrepared(preparedAudio) }
        }
        try {
            var context = initialContext
            var resolvedRoute = route
            if (shouldProbe) {
                val probed = serverVoice.checkSupport(brokerServer)
                if (!currentCoroutineContext().isActive) {
                    discardPrepared()
                    return@run
                }
                if ((probed == ServerVoiceSupport.supported || probed == ServerVoiceSupport.unsupported) &&
                    (previousSupport != probed || previousCached != probed)
                ) {
                    send(VoiceFeature.Action.ServerVoiceSupportUpdated(probed))
                }
                resolvedRoute = resolve(probed)
                send(VoiceFeature.Action.CallRouteResolved(resolvedRoute, usesDelegator = resolvedRoute.engine == VoiceEngine.gptLive && delegatorEnabled))
                context = context.copy(managesSessions = resolvedRoute.engine == VoiceEngine.gptLive && delegatorEnabled)
            }

            suspend fun startWithPhoneKey(context: VoiceInstructions.Context): Flow<VoiceCallEvent> {
                if (key.isEmpty()) throw RealtimePreflightError.Service(VoiceRoute.Reason.MissingPhoneKey(selectedEngine).message)
                if (!verified) realtimePreflight.verify(key, selectedEngine.modelID)
                currentCoroutineContext().ensureActive()
                val config = VoiceRoute.PhoneKey(selectedEngine).sessionConfig(
                    voice = voice, context = context, isAtEar = atEarBox.current, server = target.server,
                )
                return voiceCall.start(key, config)
            }

            // In parallel when using a phone key: the preflight, and the session's
            // recent messages, which wait at most 0.6 s rather than hold the call back.
            val needsPreflight = resolvedRoute == VoiceRoute.PhoneKey(selectedEngine) && !verified
            val started: Pair<Flow<VoiceCallEvent>?, Exception?> = coroutineScope {
                val preflight = if (needsPreflight) {
                    async {
                        try {
                            realtimePreflight.verify(key, selectedEngine.modelID)
                            null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            error
                        }
                    }
                } else {
                    null
                }
                context = context.copy(recentMessages = fetchRecentMessages(target))
                try {
                    currentCoroutineContext().ensureActive()
                    val events = when (val chosen = resolvedRoute) {
                        is VoiceRoute.Server -> {
                            val config = chosen.sessionConfig(
                                voice = voice, context = context, isAtEar = atEarBox.current,
                                server = brokerServer, delegation = serverDelegation,
                            )
                            try {
                                voiceCall.start("", config)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                if (chosen.provider == ServerVoiceProvider.openAI && error.message == ServerVoiceError.unsupportedServerNotice) {
                                    send(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.unsupported))
                                    val fallback = resolve(ServerVoiceSupport.unsupported)
                                    send(VoiceFeature.Action.CallRouteResolved(fallback, usesDelegator = fallback.engine == VoiceEngine.gptLive && delegatorEnabled))
                                    startWithPhoneKey(context)
                                } else {
                                    throw error
                                }
                            }
                        }
                        is VoiceRoute.PhoneKey -> {
                            preflight?.await()?.let { throw it }
                            currentCoroutineContext().ensureActive()
                            val config = chosen.sessionConfig(voice = voice, context = context, isAtEar = atEarBox.current, server = target.server)
                            voiceCall.start(key, config)
                        }
                        is VoiceRoute.Unavailable -> throw RealtimePreflightError.Service(chosen.reason.message)
                    }
                    events to null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    preflight?.cancel()
                    null to error
                }
            }
            val events = started.first
            if (events == null) {
                val error = started.second
                discardPrepared()
                // Cancelled by a hang-up, which ends it, or by a newer start whose tap must stay.
                if (currentCoroutineContext().isActive) sounds.cancelPendingStart()
                send(VoiceFeature.Action.CallEnded(errorMessage = error?.localizedMessage ?: error?.toString()))
                return@run
            }
            // Adopted by the call; anything left wasn't (a scripted call).
            discardPrepared()
            try {
                events.collect { event -> send(VoiceFeature.Action.CallEvent(event)) }
                send(VoiceFeature.Action.CallEnded(errorMessage = null))
            } catch (error: CancellationException) {
                throw error
            } catch (_: RealtimeClientError.Dropped) {
                send(VoiceFeature.Action.CallDropped)
            } catch (error: Exception) {
                send(VoiceFeature.Action.CallEnded(errorMessage = error.localizedMessage ?: error.toString()))
            }
        } catch (error: CancellationException) {
            discardPrepared()
            throw error
        }
    }.cancellable(VoiceFeature.CancelID.Call, cancelInFlight = true)
    return Reduced(next, effect)
}

/**
 * Existing reducer/route callers share the full debug-only lab policy.
 */
internal typealias VoiceLabFlags = VoiceLabDemo
