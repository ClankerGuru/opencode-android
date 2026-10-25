package ai.opencode.server.live

import ai.opencode.core.dependencies.Clock
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.internal.WireJson
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds

internal val WAIT_POLL_TIMEOUT = 25.seconds

/**
 * A long poll re-armed until the run settles. Each `/wait` and each active probe is sent
 * once: this loop is the wait's retry layer. A poll that runs out while the session is
 * active re-arms at once; a fast failure waits the shared session-wait backoff, for at most
 * 12 fast failures. A probe that fails ends the wait. A caller that owns the retries
 * (`NetworkRetry.policy == .once`) gets a fast failure back after its one probe.
 */
internal suspend fun requestWaitForIdle(server: OpenCodeServer, sessionID: String, clock: Clock) {
    val callerOwnsRetries = NetworkRetry.policy.current() == RetryPolicy.once
    val maxPasses = 12
    var passes = 0
    while (currentCoroutineContext().isActive) {
        val started = clock.now()
        try {
            perform(
                "/api/experimental/session/$sessionID/wait",
                method = "POST",
                timeout = WAIT_POLL_TIMEOUT,
                idempotency = RequestIdempotency.Idempotent,
                retry = RetryPolicy.once,
                longPoll = true,
                server = server,
            )
            return
        } catch (waitError: OpenCodeClientError) {
            val fast = clock.now() - started < 20.seconds
            if (fast) passes += 1
            if (passes >= maxPasses || !waitError.isRetryableWaitError || !currentCoroutineContext().isActive) throw waitError
            val isStillActive = try {
                withRetryPolicy(RetryPolicy.once) { requestIsSessionActive(server, sessionID) }
            } catch (_: OpenCodeClientError) {
                throw waitError
            }
            if (!isStillActive) return
            // A long poll that ran out on a live run re-arms: that's waiting.
            if (!fast) continue
            if (callerOwnsRetries) throw waitError
            val delay = RetryPolicy.sessionWait.delay(passes, NetworkRetry.environment.current().random())
            clock.sleep(delay)
        }
    }
}

internal suspend fun requestIsSessionActive(server: OpenCodeServer, sessionID: String): Boolean {
    val active = request<DataPayload<Map<String, ActiveSessionPayload>>>("/api/session/active", server = server)
    return active.data[sessionID] != null
}

internal suspend fun requestInterrupt(server: OpenCodeServer, sessionID: String): Boolean =
    request<InterruptPayload>(
        "/api/session/$sessionID/interrupt",
        query = listOf("resume" to "true"),
        method = "POST",
        server = server,
    ).interrupted

internal suspend fun requestSearchSessions(server: OpenCodeServer, query: String, limit: Int): List<OpenCodeSession> =
    request<DataPayload<List<SessionPayload>>>(
        "/api/session",
        query = listOf("search" to query, "parentID" to "null", "order" to "desc", "limit" to limit.toString()),
        server = server,
    ).data.map(::session)

/** Desktop's mention list: root sessions that aren't archived, newest first. */
internal suspend fun requestMentionSessions(server: OpenCodeServer, search: String, limit: Int): List<OpenCodeSession> {
    val query = mutableListOf("parentID" to "null", "archived" to "false", "order" to "desc", "limit" to limit.toString())
    if (search.isNotEmpty()) query += "search" to search
    return request<DataPayload<List<SessionPayload>>>("/api/session", query = query, server = server).data.map(::session)
}

internal suspend fun requestGetSession(server: OpenCodeServer, sessionID: String): OpenCodeSession =
    session(request<DataPayload<SessionPayload>>("/api/session/$sessionID", server = server).data)

@Serializable private data class TitleBody(val title: String)
@Serializable private data class IdleBody(val idle: Long)
@Serializable private data class MessageIDBody(val messageID: String)
@Serializable private data class CreateBody(val id: String? = null, val location: Location) {
    @Serializable data class Location(val directory: String)
}

internal suspend fun requestRenameSession(server: OpenCodeServer, sessionID: String, title: String) {
    // An empty title has the server generate one before it answers: running the title model
    // again (and billing it again) isn't safe to repeat. A typed title is just set.
    perform(
        "/api/session/$sessionID",
        method = "PATCH",
        body = encodedBody(TitleBody(title)),
        timeout = if (title.isEmpty()) 60.seconds else 10.seconds,
        idempotency = if (title.isEmpty()) RequestIdempotency.Unsafe else RequestIdempotency.Idempotent,
        server = server,
    )
}

internal suspend fun requestSetAlwaysNotify(server: OpenCodeServer, sessionID: String, callbackURL: String?) {
    // PATCH replaces the whole metadata object, so merge into what's there.
    val current = perform("/api/session/$sessionID", server = server)
    val info = runCatching { (WireJson.parseToJsonElement(current.data.decodeToString()) as? JsonObject)?.get("data") as? JsonObject }.getOrNull()
    val metadata = ((info?.get("metadata") as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
    if (callbackURL != null) {
        metadata["notification"] = JsonObject(mapOf("always" to JsonPrimitive(true), "callbackURL" to JsonPrimitive(callbackURL)))
    } else {
        metadata.remove("notification")
    }
    val body = try {
        WireJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("metadata" to JsonObject(metadata)))).toByteArray()
    } catch (_: Exception) {
        throw OpenCodeClientError("Couldn’t update the session")
    }
    // Sets the whole metadata object to the same value each time.
    perform("/api/session/$sessionID", method = "PATCH", body = body, idempotency = RequestIdempotency.Idempotent, server = server)
}

internal suspend fun requestCompactSession(server: OpenCodeServer, sessionID: String) {
    perform("/api/session/$sessionID/compact", method = "POST", body = EMPTY_JSON_OBJECT, server = server)
}

internal suspend fun requestStageRevert(server: OpenCodeServer, sessionID: String, messageID: String) {
    // Staging the same boundary again restores the same snapshot, but a repeat behind a newer
    // one would move it: sent again only if it never left.
    perform("/api/session/$sessionID/revert/stage", method = "POST", body = encodedBody(MessageIDBody(messageID)), server = server)
}

internal suspend fun requestClearRevert(server: OpenCodeServer, sessionID: String) {
    // With nothing staged, clearing again changes nothing.
    perform("/api/session/$sessionID/revert", method = "DELETE", idempotency = RequestIdempotency.Idempotent, server = server)
}

internal suspend fun requestForkSession(server: OpenCodeServer, sessionID: String): OpenCodeSession =
    session(request<DataPayload<SessionPayload>>("/api/session/$sessionID/fork", method = "POST", body = EMPTY_JSON_OBJECT, server = server).data)

internal suspend fun requestExportSession(server: OpenCodeServer, sessionID: String): ByteArray =
    perform("/api/experimental/session/$sessionID/export", timeout = 60.seconds, server = server).data

internal suspend fun requestViewSession(server: OpenCodeServer, sessionID: String, idle: Long) {
    // A read receipt: the server keeps the newest watermark, so it repeats safely.
    perform(
        "/api/session/$sessionID/view", method = "POST", body = encodedBody(IdleBody(idle)),
        idempotency = RequestIdempotency.Idempotent, server = server,
    )
}

internal suspend fun requestCreateSession(server: OpenCodeServer, directory: String, id: String?): OpenCodeSession {
    // With a client-made ID the server returns the existing session on a repeat; without one a retry could create a second.
    val payload = request<DataPayload<SessionPayload>>(
        "/api/session",
        method = "POST",
        body = encodedBody(CreateBody(id, CreateBody.Location(directory))),
        idempotency = if (id == null) RequestIdempotency.Unsafe else RequestIdempotency.Idempotent,
        server = server,
    )
    return session(payload.data)
}

internal suspend fun requestDeleteSession(server: OpenCodeServer, sessionID: String) {
    perform("/api/session/$sessionID", method = "DELETE", server = server)
}

internal suspend fun requestMoveToBackground(server: OpenCodeServer, sessionID: String) {
    perform("/api/session/$sessionID/background", method = "POST", server = server)
}
