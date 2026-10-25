package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Port of the iOS `VoiceLiveTests.swift` harness. iOS runs the coordinator's actor on real
 * time; here it runs on the test's virtual time ([TestScope.virtualClock]), so the same
 * waits and limits hold exactly and instantly.
 */

/** iOS `ContinuousClock.now` inside `runTest`: virtual time. */
val TestScope.now: Duration get() = testScheduler.currentTime.milliseconds

/** A [Clock] on the test's virtual time. */
fun TestScope.virtualClock(): Clock = object : Clock {
    override fun now(): Duration = testScheduler.currentTime.milliseconds
    override suspend fun sleep(duration: Duration) = delay(duration)
}

/** A [DateClient] on the test's virtual time (rotation deadlines are wall-clock instants). */
fun TestScope.virtualDate(): DateClient = DateClient { Instant.ofEpochMilli(testScheduler.currentTime) }

/** iOS `URLError(.networkConnectionLost)`. */
class NetworkConnectionLost : IOException("The network connection was lost.")

/** iOS `URLError(.notConnectedToInternet)`. */
class NotConnectedToInternet : IOException("The Internet connection appears to be offline.")

/** iOS `JSONDecoder().decode(RealtimeJSON.self, from:)`. */
fun decodeJSON(data: ByteArray): RealtimeJSON = Json.parseToJsonElement(data.decodeToString())

/** Steps through a call's events like iOS's `AsyncThrowingStream` iterator: null once it ends, throws why it dropped. */
class EventIterator(flow: Flow<VoiceCallEvent>, scope: CoroutineScope) {
    private val channel = Channel<VoiceCallEvent>(Channel.UNLIMITED)
    val job: Job = scope.launch {
        try {
            flow.collect { channel.send(it) }
            channel.close()
        } catch (error: CancellationException) {
            channel.close()
            throw error
        } catch (error: Throwable) {
            channel.close(error)
        }
    }

    suspend fun next(): VoiceCallEvent? {
        val result = channel.receiveCatching()
        result.getOrNull()?.let { return it }
        result.exceptionOrNull()?.let { throw it }
        return null
    }

    /** iOS `while let event = try await iterator.next(), event != target {}`. */
    suspend fun skipUntil(target: VoiceCallEvent) {
        while (true) {
            val event = next() ?: return
            if (event == target) return
        }
    }
}

/** iOS `LiveCallCoordinatorTests.FakeLiveSocket`. */
class FakeLiveSocket(val config: LiveSessionConfig) {
    private val serverEvents = Channel<LiveServerEvent>(Channel.UNLIMITED)
    val events: Flow<LiveServerEvent> = serverEvents.consumeAsFlow()
    private val lock = Any()
    private val sent = mutableListOf<LiveCommand>()
    val commands: List<LiveCommand> get() = synchronized(lock) { sent.toList() }

    @Volatile var closed = false
    @Volatile var holdsClose = false
    @Volatile private var closeWaiter: CompletableDeferred<Unit>? = null
    @Volatile var failOnCommentaryIndex: Int? = null
    private var commentaryCount = 0

    /** While set, the first commentary sent stalls inside the socket's send. */
    @Volatile var stallFirstCommentary = false
    @Volatile var commentaryEntered = 0

    /** iOS `server.yield(_:)`. */
    fun yieldEvent(event: LiveServerEvent) {
        serverEvents.trySend(event)
    }

    /** iOS `server.finish(throwing:)`. */
    fun finish(error: Throwable) {
        serverEvents.close(error)
    }

    val isHeldClosing: Boolean get() = closeWaiter != null

    suspend fun send(command: LiveCommand) {
        currentCoroutineContext().ensureActive()
        if (command is LiveCommand.AppendCommentary) {
            val current = synchronized(lock) { commentaryCount++ }
            if (failOnCommentaryIndex == current) throw NetworkConnectionLost()
        }
        synchronized(lock) { sent += command }
    }

    suspend fun close() {
        closed = true
        if (!holdsClose) return
        val waiter = CompletableDeferred<Unit>()
        closeWaiter = waiter
        waiter.await()
    }

    fun releaseClose() {
        closeWaiter?.complete(Unit)
        closeWaiter = null
    }
}

/** iOS `LiveCallCoordinatorTests.Harness`. Build it inside `runTest`. */
class LiveHarness(
    private val test: TestScope,
    timing: VoiceCallTiming = VoiceCallTiming(
        rotateAfter = 3_000.0,
        forceRotateAfter = 3_500.0,
        retryDelays = listOf(0.01, 0.01, 0.01),
        liveDelegationSettle = Duration.ZERO,
        liveTranscriptSettle = 40.milliseconds,
        liveAcknowledgementWait = Duration.ZERO,
    ),
) {
    private val lock = Any()
    private val opened = mutableListOf<FakeLiveSocket>()
    val sockets: List<FakeLiveSocket> get() = synchronized(lock) { opened.toList() }
    @Volatile var failingConnects = 0
    private val openedChannel = Channel<FakeLiveSocket>(Channel.UNLIMITED)
    private val captureChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val audioChannel = Channel<VoiceAudioEvent>(Channel.UNLIMITED)
    @Volatile var flushes = 0
    @Volatile var enqueues = 0
    @Volatile var audioStopped = 0
    private val marks = mutableListOf<String>()
    private val markers = mutableListOf<String>()
    val callLogMarkers: List<String> get() = synchronized(lock) { markers.toList() }

    /** The container the coordinator resolves its dependencies from. */
    val values: DependencyValues = DependencyValues.testing().update {
        this[Clock] = test.virtualClock()
        this[DateClient] = test.virtualDate()
    }
    private val diagnostics = VoiceCallDiagnostics(
        recordMark = { synchronized(lock) { marks += it } },
        recordSocketEvent = { synchronized(lock) { marks += it.name } },
    )
    val coordinator: LiveCallCoordinator = DependencyValues.withValues(values) {
        LiveCallCoordinator(
            timing = timing,
            diagnostics = diagnostics,
            callLogMarker = { marker -> synchronized(lock) { markers += marker } },
            dispatcher = StandardTestDispatcher(test.testScheduler),
        )
    }

    val voiceEvents: List<String> get() = synchronized(lock) { marks.filter { it.startsWith("voice_") } }

    val audioLevels: VoiceAudioLevels get() = values[VoiceAudioLevels]

    /** iOS `harness.opened` iterator's `next()`. */
    suspend fun nextOpened(): FakeLiveSocket = openedChannel.receive()

    /** iOS `harness.capture.yield(_:)`. */
    fun capture(pcm: ByteArray) {
        captureChannel.trySend(pcm)
    }

    /** iOS `harness.audioEvents.yield(_:)`. */
    fun audioEvent(event: VoiceAudioEvent) {
        audioChannel.trySend(event)
    }

    /** iOS `coordinator.pendingAppendCount` (DEBUG-only there; internal state here). */
    suspend fun pendingAppendCount(): Int = coordinator.serialized {
        coordinator.quietAppends.size + coordinator.heldAnnouncements.size + coordinator.undelivered.size
    }

    suspend fun start(instructions: String = "Be brief.", delegation: LiveDelegation = LiveDelegation.client): Flow<VoiceCallEvent> {
        val live = LiveClient { _, config ->
            if (sockets.isNotEmpty() && failingConnects > 0) {
                failingConnects -= 1
                throw NotConnectedToInternet()
            }
            val socket = FakeLiveSocket(config)
            synchronized(lock) { opened += socket }
            openedChannel.trySend(socket)
            LiveConnection(
                events = socket.events,
                send = { command ->
                    yield()
                    if (command is LiveCommand.AppendCommentary && ++socket.commentaryEntered == 1) {
                        while (socket.stallFirstCommentary) delay(5)
                    }
                    socket.send(command)
                },
                close = { socket.close() },
            )
        }
        val audio = VoiceAudioClient(start = {
            val levels = VoiceAudioLevels.shared
            levels.reset()
            VoiceAudioSession(
                capture = captureChannel.consumeAsFlow(),
                events = audioChannel.consumeAsFlow(),
                enqueue = { pcm, _ ->
                    enqueues += 1
                    PCM.playbackBuffer(pcm)?.let { levels.updateAssistant(it) }
                },
                flush = {
                    flushes += 1
                    levels.clearAssistant()
                    0
                },
                setMuted = { muted -> levels.isMuted = muted },
                stop = {
                    audioStopped += 1
                    levels.reset()
                },
            )
        })
        return coordinator.start(
            apiKey = "test",
            config = LiveSessionConfig(instructions = instructions, delegation = delegation),
            live = live,
            audio = audio,
        )
    }
}

/** iOS `waitUntil`: polls every 5 ms for up to 5 s (virtual time). Whether it came true. */
suspend fun waitUntil(timeout: Duration = 5.seconds, condition: () -> Boolean): Boolean {
    var waited = Duration.ZERO
    while (!condition() && waited < timeout) {
        delay(5)
        waited += 5.milliseconds
    }
    return condition()
}

/** Every append's content, in order. */
fun FakeLiveSocket.appendContents(): List<String> = commands.mapNotNull {
    when (it) {
        is LiveCommand.AppendInstructions -> it.content
        is LiveCommand.AppendThinking -> it.content
        is LiveCommand.AppendCommentary -> it.content
        else -> null
    }
}

/** iOS `LiveCallCoordinatorTests.commentary(_:)`. */
fun FakeLiveSocket.commentary(): List<Pair<String?, String>> =
    commands.mapNotNull { (it as? LiveCommand.AppendCommentary)?.let { c -> c.delegationID to c.content } }

/** iOS `LiveCallCoordinatorTests.appends(_:)`. */
fun FakeLiveSocket.appends(): List<Pair<String, String>> = commands.mapNotNull {
    when (it) {
        is LiveCommand.AppendInstructions -> "instructions" to it.content
        is LiveCommand.AppendThinking -> "thinking" to it.content
        is LiveCommand.AppendCommentary -> "commentary" to it.content
        else -> null
    }
}

/** Spoken commentary only. */
fun FakeLiveSocket.spoken(): List<String> = commands.mapNotNull { (it as? LiveCommand.AppendCommentary)?.content }

// MARK: VoiceFeature stores

/**
 * One in-memory `@Shared` store for a test: the state's initializer and the store read the
 * same values (each test container would otherwise get its own).
 */
class VoiceTestShared(val store: SharedStore = SharedStore.inMemory()) {
    fun <R> read(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing().update { this[SharedStore] = store }, block)
    fun <T> set(key: SharedKey<T>, value: T) = read { shared(key).set(value) }
    fun <T> get(key: SharedKey<T>): T = read { shared(key).value }
}

/** A [TestStore] of [VoiceFeature] whose initial state is built with the store's own dependencies. */
fun TestScope.voiceTestStore(
    sharedValues: VoiceTestShared,
    initialState: () -> VoiceFeature.State,
    dependencies: DependencyValues.Builder.() -> Unit = {},
): TestStore<VoiceFeature.State, VoiceFeature.Action> {
    val clock = virtualClock()
    val all: DependencyValues.Builder.() -> Unit = {
        this[SharedStore] = sharedValues.store
        this[DateClient] = DateClient.constant(Instant.EPOCH)
        this[Clock] = clock
        this[UuidClient] = UuidClient.incrementing()
        dependencies()
    }
    val state = DependencyValues.withValues(DependencyValues.testing().update(all), initialState)
    return TestStore(state, VoiceFeature(), this, all)
}

/** iOS's per-closure `$0.voiceCall.x = …` overrides: a client whose unset closures do nothing. */
fun testVoiceCall(
    start: suspend (String, RealtimeSessionConfig) -> Flow<VoiceCallEvent> = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
    completeFunction: suspend (String, String) -> Unit = { _, _ -> },
    noteProgress: suspend (String, String) -> Unit = { _, _ -> },
    cancelPendingAnnouncements: suspend (List<String>) -> Unit = { },
    retarget: suspend () -> Unit = { },
    setAtEar: suspend (Boolean) -> Unit = { },
    updateInstructions: suspend (String, String?) -> Unit = { _, _ -> },
    end: suspend () -> Unit = { },
    delegatorRound: (suspend (String) -> Result<VoiceDelegator.Response>)? = null,
    submitDelegatorInput: suspend (String, List<VoiceDelegator.InputItem>) -> Boolean = { _, _ -> false },
    updateDelegatorInstructions: suspend (String) -> Unit = { },
) = VoiceCallClient(
    start = start,
    completeFunction = completeFunction,
    noteProgress = noteProgress,
    cancelPendingAnnouncements = cancelPendingAnnouncements,
    retarget = retarget,
    setMuted = { },
    setAtEar = setAtEar,
    updateInstructions = updateInstructions,
    end = end,
    delegatorRound = delegatorRound ?: { Result.failure(OpenCodeClientError("The voice call has no delegator.")) },
    submitDelegatorInput = submitDelegatorInput,
    updateDelegatorInstructions = updateDelegatorInstructions,
)

/** iOS's `$0.voiceWork.x = …` overrides. */
fun testVoiceWork(
    submit: suspend (VoiceWorkTarget, String) -> VoiceWorkAdmission = { _, _ -> VoiceWorkAdmission(promptID = "msg_test") },
    awaitReply: suspend (OpenCodeServer, String, String) -> String? = { _, _, _ -> null },
    recentMessages: suspend (OpenCodeServer, String, Int) -> List<String> = { _, _, _ -> emptyList() },
) = VoiceWorkClient(
    submit = submit,
    awaitReply = awaitReply,
    checkSessionActive = { _, _ -> false },
    recentMessages = recentMessages,
)

/** iOS `AsyncThrowingStream.makeStream()` of call events. */
class CallEvents {
    private val channel = Channel<VoiceCallEvent>(Channel.UNLIMITED)
    val flow: Flow<VoiceCallEvent> = channel.consumeAsFlow()
    fun yieldEvent(event: VoiceCallEvent) {
        channel.trySend(event)
    }
    fun finish() {
        channel.close()
    }
}

/** iOS `AsyncStream<String>` a dependency waits on: the first value, or null once finished. */
class Gate<T> {
    private val channel = Channel<T>(Channel.UNLIMITED)
    fun yieldValue(value: T) {
        channel.trySend(value)
    }
    fun finish() {
        channel.close()
    }
    suspend fun first(): T? = channel.receiveCatching().getOrNull()
}

fun isCallEvent(action: VoiceFeature.Action) = action is VoiceFeature.Action.CallEvent
fun isWorkAdmitted(action: VoiceFeature.Action) = action is VoiceFeature.Action.WorkAdmitted
fun isWorkReplied(action: VoiceFeature.Action) = action is VoiceFeature.Action.WorkReplied
fun isWorkFailed(action: VoiceFeature.Action) = action is VoiceFeature.Action.WorkFailed
fun isCallEnded(action: VoiceFeature.Action) = action is VoiceFeature.Action.CallEnded
fun isDelegatorFinished(action: VoiceFeature.Action) = action is VoiceFeature.Action.DelegatorFinished
fun isDelegateEnded(action: VoiceFeature.Action) = action == VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended)
