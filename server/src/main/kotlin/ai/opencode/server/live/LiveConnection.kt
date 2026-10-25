package ai.opencode.server.live

import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

internal suspend fun requestHealth(server: OpenCodeServer) {
    // Probes race every address of a server and the best reachable one wins; retrying a
    // dead address would hold that choice up, so a probe is sent once.
    request<StatusPayload>("/api/info", retry = RetryPolicy.once, isConnectionProbe = true, server = server)
}

internal suspend fun requestRedeemPairing(link: OpenCodePairingLink): OpenCodeServer {
    // Which address the phone can reach depends on its network, so every listed one is
    // tried at once. The code is single-use: at most one redemption succeeds.
    val candidates = link.redeemOrigins
    val outcome: Result<OpenCodeServer> = coroutineScope {
        val results = Channel<Result<OpenCodeServer>>(Channel.UNLIMITED)
        for (origin in candidates) {
            launch {
                // The connect route is unauthenticated; the single-use code is the credential.
                val anonymous = OpenCodeServer(url = origin, username = "opencode", password = "")
                val result = try {
                    // The server spends the code as the request arrives, so giving up early leaves
                    // only a used code. A GET, but it spends the code: never sent twice, never redirected.
                    val response = perform(
                        "/auth/connect/${link.code}", timeout = 15.seconds, idempotency = RequestIdempotency.Unsafe,
                        retry = RetryPolicy.once, followsRedirects = false, isConnectionProbe = true, server = anonymous,
                    )
                    val session = decoded(response.data, decode = PairingSessionPayload::decode)
                    Result.success(OpenCodeServer(url = origin, username = "opencode", password = session.token, name = session.deviceName))
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
                results.send(result)
            }
        }
        val failures = mutableListOf<OpenCodeClientError>()
        repeat(candidates.size) {
            val result = results.receive()
            if (result.isSuccess) {
                coroutineContext.cancelChildren()
                return@coroutineScope result
            }
            failures += result.exceptionOrNull() as OpenCodeClientError
        }
        Result.failure(pairingFailure(failures))
    }
    val server = outcome.getOrElse { throw it }
    // Other reachable addresses (LAN, tailnet) let the phone reconnect when it changes
    // networks. The server's own list is optional: an older server without it still pairs.
    val reported = try { request<InfoPayload>("/api/info", server = server).urls ?: emptyList() } catch (_: OpenCodeClientError) { emptyList() }
    val seen = hashSetOf(server.url)
    val alternates = (candidates + reported)
        .map { it.trim('/') }
        .filter { !OpenCodeServer.isLoopback(it) && OpenCodeServer(url = it, username = "", password = "").endpoint("/") != null }
        .filter { seen.add(it) }
    return server.copy(alternateURLs = alternates)
}

/** The most telling reason no address redeemed the code: a server that answered beats one that couldn't be reached. */
private fun pairingFailure(failures: List<OpenCodeClientError>): OpenCodeClientError {
    val details = failures.flatMap { it.connectionFailures }
    if (failures.any { it.statusCode == 401 }) {
        return OpenCodeClientError(
            "This pairing code expired or was already used. Run `opencode pair --remote` again.",
            statusCode = 401, connectionFailures = details,
        )
    }
    if (failures.any { it.statusCode == 404 }) {
        return OpenCodeClientError(
            "This server doesn’t support pairing links. Update OpenCode on your computer, or enter the server manually.",
            statusCode = 404, connectionFailures = details,
        )
    }
    val failure = failures.lastOrNull() ?: OpenCodeClientError("Couldn’t reach the OpenCode server")
    return failure.copy(connectionFailures = details)
}

internal suspend fun requestNotifyPluginStatus(server: OpenCodeServer): OpenCodeNotifyPluginStatus {
    val payload = request<DataPayload<List<PluginPayload>>>("/api/plugin", server = server)
    val plugin = payload.data.firstOrNull { it.id == OpenCodeNotifyPluginStatus.PLUGIN_ID } ?: return OpenCodeNotifyPluginStatus.Missing
    return if (plugin.state?.status == "active") OpenCodeNotifyPluginStatus.Active else OpenCodeNotifyPluginStatus.Inactive
}
