package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shared setup for the ports of iOS `VoiceFeatureTests` (and its `+Delegation`,
 * `+CallLog`, `+Screen` extensions), `VoiceResumeTests` and `VoiceLatencyBudgetTests`.
 * Everything lives in this object so it never clashes with other test files' helpers.
 */
internal object VoiceFeatureTestSupport {
    val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    /** Swift's `Date.distantPast`. */
    val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
    val session = OpenCodeSession(id = "ses_voice", title = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)

    fun session(id: String, title: String, directory: String) =
        OpenCodeSession(id = id, title = title, projectID = "p", directory = directory, updatedAt = distantPast)

    fun date(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    /** iOS `LockIsolated`. */
    class LockIsolated<T>(initial: T) {
        @Volatile private var current: T = initial
        val value: T get() = synchronized(this) { current }
        fun setValue(value: T) = synchronized(this) { current = value }
        fun <R> withValue(block: (T) -> Pair<T, R>): R = synchronized(this) {
            val (next, result) = block(current)
            current = next
            result
        }
        fun update(block: (T) -> T) = synchronized(this) { current = block(current) }
    }

    fun <T> LockIsolated<List<T>>.append(value: T) = update { it + value }
    fun LockIsolated<Int>.increment(): Int = withValue { (it + 1) to (it + 1) }

    /** iOS `.object(["text": .string(text)])`. */
    fun arguments(text: String? = null): RealtimeJSON =
        JsonObject(if (text == null) emptyMap() else mapOf("text" to JsonPrimitive(text)))

    fun delegateEvent(id: String = "call_1", text: String? = null): VoiceCallEvent =
        VoiceCallEvent.FunctionCall(callID = id, name = "delegate", arguments = arguments(text))

    /** iOS `AsyncThrowingStream.makeStream()`: a call's events the test yields and finishes. */
    class EventStream {
        private val channel = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val events: Flow<VoiceCallEvent> = channel.receiveAsFlow()
        fun yield(event: VoiceCallEvent) {
            channel.trySend(event)
        }
        fun finish(throwing: Throwable? = null) {
            channel.close(throwing)
        }
    }

    /** iOS `AsyncStream<Void>` used as a gate: suspends until the test yields or finishes it. */
    class Gate {
        private val channel = Channel<Unit>(Channel.UNLIMITED)
        suspend fun await() {
            channel.receiveCatching()
        }
        /** iOS `for await _ in stream {}`: returns only once the stream finishes. */
        suspend fun awaitFinish() {
            for (unit in channel) Unit
        }
        fun yield() {
            channel.trySend(Unit)
        }
        fun finish() {
            channel.close()
        }
    }

    /** Lets launched effects run (iOS effects start eagerly on the main actor). */
    suspend fun settle(times: Int = 50) = repeat(times) { yield() }

    /** iOS `while condition { await Task.yield() }`, bounded. */
    suspend fun eventually(limit: Int = 2_000, condition: () -> Boolean) {
        var tries = 0
        while (!condition() && tries < limit) {
            yield()
            if (tries > 200) Thread.sleep(1)
            tries++
        }
    }

    /**
     * One test's dependency world: an in-memory shared store the states (built before
     * the store, as iOS does) and the store share, and the scope effects run in. The
     * store's scope is not a child of the test, so effects a test leaves running are
     * cancelled by [skipInFlightEffects] (iOS `store.skipInFlightEffects()`).
     */
    class Harness(scope: CoroutineScope) {
        val sharedStore: SharedStore = SharedStore.inMemory()

        /** Installed in every store: keeps the Android fallback haptics off background threads. */
        val haptics = HapticsRecorder()
        private val job = Job()
        val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + job)

        private val values: DependencyValues
            get() = DependencyValues.testing().update {
                this[SharedStore] = sharedStore
                this[DateClient] = DateClient.constant(Instant.EPOCH)
            }

        /** Builds a value that reads shared keys (a `VoiceFeature.State`) in this world. */
        fun <R> state(block: () -> R): R = DependencyValues.withValues(values, block)

        fun store(
            state: VoiceFeature.State,
            dependencies: DependencyValues.Builder.() -> Unit = {},
        ): TestStore<VoiceFeature.State, VoiceFeature.Action> = TestStore(state, VoiceFeature(), scope) {
            this[SharedStore] = sharedStore
            haptics.install(this)
            // Android: every start mints the call's notification identity (iOS's Live Activity is Home's).
            this[UuidClient] = UuidClient.incrementing()
            dependencies()
        }

        fun skipInFlightEffects() = job.cancel()
    }

    /** `runBlocking`, not `runTest`: delegator tools run on real dispatchers, so receive timeouts must be real time. */
    fun voiceTest(block: suspend CoroutineScope.(Harness) -> Unit) = runBlocking {
        val harness = Harness(this)
        try {
            block(harness)
        } finally {
            harness.skipInFlightEffects()
        }
    }

    /** iOS `withDependencies { … } operation: { … }` in a test: a test container with these overrides. */
    suspend fun <R> withTestDependencies(update: DependencyValues.Builder.() -> Unit, block: suspend () -> R): R =
        withContext(DependencyValues.testing().update(update).asContextElement()) { block() }

    fun TestStore<VoiceFeature.State, VoiceFeature.Action>.setAPIKey(key: String?) =
        withDependencies { shared(SharedKeys.openAIAPIKey).set(key) }

    fun TestStore<VoiceFeature.State, VoiceFeature.Action>.transcripts(): Map<String, VoiceTranscriptRecord> =
        withDependencies { shared(VoiceSharedKeys.voiceTranscripts).value }

    /** iOS `store.receive(\.case)`. */
    suspend fun <T : VoiceFeature.Action> TestStore<VoiceFeature.State, VoiceFeature.Action>.receive(
        case: KClass<T>,
        timeout: Duration = 5.seconds,
        update: ((VoiceFeature.State) -> VoiceFeature.State)? = null,
    ) = receive({ case.isInstance(it) }, case.simpleName ?: "action", timeout, update)

    suspend fun TestStore<VoiceFeature.State, VoiceFeature.Action>.receiveEnded() =
        receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended), timeout = 5.seconds)

    /** iOS `voice.phase = .live` (its `didSet` keeps the reply watch in step). */
    fun VoiceFeature.State.live(): VoiceFeature.State = withPhase(VoiceFeature.State.Phase.Live)

    /** A copy of the call client with some closures replaced (iOS `$0.voiceCall.x = …`). */
    fun VoiceCallClient.copy(
        start: suspend (String, RealtimeSessionConfig) -> Flow<VoiceCallEvent> = this.start,
        completeFunction: suspend (String, String) -> Unit = this.completeFunction,
        noteProgress: suspend (String, String) -> Unit = this.noteProgress,
        announce: suspend (String) -> Unit = this.announce,
        announceSession: (suspend (String, List<String>, String) -> Unit)? = this.announceSession,
        cancelPendingAnnouncements: suspend (List<String>) -> Unit = this.cancelPendingAnnouncements,
        abandonDelegation: suspend (String) -> Unit = this.abandonDelegation,
        retarget: suspend () -> Unit = this.retarget,
        setMuted: suspend (Boolean) -> Unit = this.setMuted,
        setAtEar: suspend (Boolean) -> Unit = this.setAtEar,
        updateInstructions: suspend (String, String?) -> Unit = this.updateInstructions,
        end: suspend () -> Unit = this.end,
        delegatorRound: suspend (String) -> Result<VoiceDelegator.Response> = this.delegatorRound,
        submitDelegatorInput: suspend (String, List<VoiceDelegator.InputItem>) -> Boolean = this.submitDelegatorInput,
        updateDelegatorInstructions: suspend (String) -> Unit = this.updateDelegatorInstructions,
    ) = VoiceCallClient(
        start, completeFunction, noteProgress, announce, announceSession, cancelPendingAnnouncements, abandonDelegation,
        retarget, setMuted, setAtEar, updateInstructions, end, delegatorRound, submitDelegatorInput, updateDelegatorInstructions,
    )

    /** A copy of the work client with some closures replaced (iOS `$0.voiceWork.x = …`). */
    fun VoiceWorkClient.copy(
        submit: suspend (VoiceWorkTarget, String) -> VoiceWorkAdmission = this.submit,
        awaitReply: suspend (OpenCodeServer, String, String) -> String? = this.awaitReply,
        fetchReply: suspend (OpenCodeServer, String, String) -> String? = this.fetchReply,
        fetchRunReply: suspend (OpenCodeServer, String, String) -> String? = this.fetchRunReply,
        checkSessionActive: suspend (OpenCodeServer, String) -> Boolean? = this.checkSessionActive,
        activeSessionIDs: suspend (OpenCodeServer) -> Set<String> = this.activeSessionIDs,
        noteSessionSettled: suspend (String) -> Unit = this.noteSessionSettled,
        noteActiveSessions: suspend (Set<String>) -> Unit = this.noteActiveSessions,
        recentMessages: suspend (OpenCodeServer, String, Int) -> List<String> = this.recentMessages,
    ) = VoiceWorkClient(submit, awaitReply, fetchReply, fetchRunReply, checkSessionActive, activeSessionIDs, noteSessionSettled, noteActiveSessions, recentMessages)

    fun DependencyValues.Builder.voiceCall(update: VoiceCallClient.() -> VoiceCallClient) {
        this[VoiceCallClient] = this[VoiceCallClient].update()
    }

    fun DependencyValues.Builder.voiceWork(update: VoiceWorkClient.() -> VoiceWorkClient) {
        this[VoiceWorkClient] = this[VoiceWorkClient].update()
    }

    /** iOS `VoiceDelegatorClient(isEnabled: true) { key, input, previous in … }`. */
    fun delegator(respond: suspend (String, List<VoiceDelegator.InputItem>, String?) -> VoiceDelegator.Response) =
        VoiceDelegatorClient(isEnabled = true, respond = respond)

    fun response(id: String, calls: List<VoiceDelegator.ToolCall> = emptyList(), text: String = "") =
        VoiceDelegator.Response(id = id, calls = calls, text = text)

    fun call(callID: String, name: String, arguments: String) = VoiceDelegator.ToolCall(callID = callID, name = name, arguments = arguments)

    /**
     * iOS `HapticsClient.recording(_:)` and `haptics.voice`: Android routes the voice cues
     * through [VoiceCallSounds] (a call-scoped clock) and the vocabulary through
     * [HapticsClient]. This records both on one step clock, so [log] keeps their order:
     * `"voice.<cue>"` for a voice cue, the haptic's name for the rest. An engine that plays
     * every pattern keeps the Android fallbacks out, except `callStarted`'s `confirm`, which
     * is the cue itself here and is folded into it.
     */
    class HapticsRecorder {
        private class StepClock : Clock {
            private var time = Duration.ZERO
            override fun now(): Duration = synchronized(this) { time += 1_000.nanoseconds; time }
            override suspend fun sleep(duration: Duration) {
                synchronized(this) { time += duration }
            }
        }

        private val clock = StepClock()
        private val plain = mutableListOf<Pair<Duration, String>>()
        private var cut = Duration.ZERO
        private val engine = object : VoiceHapticPlaying {
            override val isCallActive = true
            override fun prepare() {}
            override fun play(pattern: VoiceHapticPattern, at: Duration, kind: VoiceHapticEngine.Kind) = true
            override fun cancel(kind: VoiceHapticEngine.Kind) {}
            override fun endCall() {}
            override suspend fun finish() {}
        }
        val sounds = VoiceCallSounds(clock = clock, haptics = engine)
        val haptics = HapticsClient(play = { haptic -> synchronized(plain) { plain += clock.now() to haptic.name } })

        fun install(builder: DependencyValues.Builder) {
            builder[VoiceCallSounds] = sounds
            builder[HapticsClient] = haptics
        }

        /** iOS `haptics.voice`'s recording. */
        val voice: List<VoiceCallHaptic> get() = sounds.snapshot().hapticEvents.map { it.haptic }

        /** iOS `log.setValue([])`. */
        fun reset() {
            cut = clock.now()
        }

        val log: List<String>
            get() {
                // Recorded haptic times are seconds since the sounds' clock origin (1 µs in).
                val origin = 1_000.nanoseconds
                val cues = sounds.snapshot().hapticEvents.map { (origin + Math.round(it.time * 1e9).nanoseconds) to "voice.${it.haptic.name}" }
                val merged = (cues + synchronized(plain) { plain.toList() }).sortedBy { it.first }.filter { it.first > cut }.map { it.second }
                val result = mutableListOf<String>()
                for (entry in merged) {
                    if (entry == Haptic.confirm.name && result.lastOrNull() == "voice.${VoiceCallHaptic.callStarted.name}") continue
                    result += entry
                }
                return result
            }
    }
}
