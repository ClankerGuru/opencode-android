package ai.opencode.server

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.DiagnosticsInstrumentation.recordingDiagnostics
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The OkHttp client pools behind the live client (iOS `URLSession`s). Under tests every pool
 * runs behind [TestNetworkBlock] (fixtures register hosts; unmocked network fails).
 * Per-request timeouts are applied on top with `newBuilder()`, sharing these pools.
 */
class OpenCodeTransport(
    val request: OkHttpClient,
    val measuredRequest: OkHttpClient,
    val longPoll: OkHttpClient,
    val eventStream: OkHttpClient,
) {
    companion object Key : DependencyKey<OpenCodeTransport> {
        override val liveValue: OpenCodeTransport by lazy {
            // OkHttp caps calls per host at 5 by default; the event stream and a `/wait` long poll
            // would hold two of them, and family/ancestry fan-out (six lookups plus active status)
            // needs more. iOS's URLSession has no such cap on in-flight requests.
            val dispatcher = Dispatcher().apply { maxRequestsPerHost = maxRequests }
            val base = OkHttpClient.Builder().dispatcher(dispatcher).build()
            // Requests feed the diagnostics timeline (inert while recording is off); the event
            // stream records its own connect/disconnect marks instead.
            val recorded = base.newBuilder().recordingDiagnostics().build()
            OpenCodeTransport(
                request = recorded,
                measuredRequest = recorded.newBuilder().build(),
                longPoll = recorded.newBuilder().build(),
                // Server heartbeats reset the idle interval, not a total stream deadline.
                eventStream = base.newBuilder()
                    .connectTimeout(90, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(90, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /**
         * The same pools behind [TestNetworkBlock]: a test reaches only the hosts its fixtures
         * register (iOS `WireFixture`) and loopback (`MockWebServer`); anything else fails.
         */
        override val testValue: OpenCodeTransport by lazy {
            fun OkHttpClient.blocked() = newBuilder().addInterceptor(TestNetworkBlock.interceptor).build()
            val live = liveValue
            OpenCodeTransport(
                request = live.request.blocked(),
                measuredRequest = live.measuredRequest.blocked(),
                longPoll = live.longPoll.blocked(),
                eventStream = live.eventStream.blocked(),
            )
        }
    }
}
