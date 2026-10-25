package ai.opencode.server

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.DiagnosticsInstrumentation.recordingDiagnostics
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The OkHttp client pools behind the live client (iOS `URLSession`s). Tests supply clients
 * with an interceptor or point servers at a `MockWebServer`, without production flags.
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
            val base = OkHttpClient()
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

        /** The same pools: a test reaches only the servers it creates (or intercepts). */
        override val testValue: OpenCodeTransport get() = liveValue
    }
}
