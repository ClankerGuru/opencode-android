package ai.opencode.server.live

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeShellOutputPage
import ai.opencode.server.model.PromptContext
import ai.opencode.server.model.nonEmpty
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/** A location-scoped read boots that location's service graph (MCP processes too) on first touch. */
internal val LOCATION_BOOT_TIMEOUT = 60.seconds

internal suspend fun requestFamilyPendingRequests(server: OpenCodeServer, sessionIDs: List<String>, directory: String): OpenCodePendingRequests {
    val requests = requestLocationPendingRequests(server, directory)
    val family = sessionIDs.toSet()
    return OpenCodePendingRequests(
        permissions = requests.permissions.filter { it.sessionID in family },
        forms = requests.forms.filter { it.sessionID == "global" || it.sessionID in family },
    )
}

private suspend fun requestLocationPendingRequests(server: OpenCodeServer, directory: String): OpenCodePendingRequests {
    val query = locationQuery(directory)
    // Booting a location routinely exceeds the default 10 s: treat that as slow, not unreachable.
    val (permissions, forms) = coroutineScope {
        val permissions = async {
            capturedRequest<DataPayload<List<OpenCodePermissionRequest>>>("/api/permission/request", query, LOCATION_BOOT_TIMEOUT, server)
        }
        val forms = async { capturedRequest<DataPayload<List<OpenCodeFormRequest>>>("/api/form", query, LOCATION_BOOT_TIMEOUT, server) }
        permissions.await() to forms.await()
    }
    return OpenCodePendingRequests(permissions.getOrThrow().data, forms.getOrThrow().data)
}

internal suspend fun requestHomeAttention(server: OpenCodeServer, roots: List<OpenCodeSession>, directories: List<String>): Set<String> {
    val pendingSessionIDs = LinkedHashSet<String>()
    for (directory in directories) {
        val requests = requestLocationPendingRequests(server, directory)
        pendingSessionIDs += requests.permissions.map { it.sessionID }
        pendingSessionIDs += requests.forms.map { it.sessionID }.filter { it != "global" }
    }
    val attentionRoots = LinkedHashSet<String>()
    val knownRoots = roots.associate { it.id to it.id }.toMutableMap()
    for (sessionID in pendingSessionIDs) attentionRoots += requestRootSessionID(server, sessionID, knownRoots)
    return attentionRoots
}

private suspend fun requestRootSessionID(server: OpenCodeServer, sessionID: String, knownRoots: MutableMap<String, String>): String {
    var currentID = sessionID
    val visited = HashSet<String>()
    val lineage = mutableListOf<String>()
    while (visited.add(currentID)) {
        knownRoots[currentID]?.let { rootID ->
            for (id in lineage) knownRoots[id] = rootID
            return rootID
        }
        lineage += currentID
        val payload = request<DataPayload<SessionPayload>>("/api/session/$currentID", server = server)
        val parentID = payload.data.parentID
        if (parentID == null) {
            for (id in lineage) knownRoots[id] = payload.data.id
            return payload.data.id
        }
        currentID = parentID
    }
    return sessionID
}

@Serializable private data class PermissionReplyBody(val decision: OpenCodePermissionReply, val message: String? = null)
@Serializable private data class FormReplyBody(val answer: Map<String, OpenCodeFormValue>)

internal suspend fun requestReplyPermission(
    server: OpenCodeServer,
    sessionID: String,
    requestID: String,
    reply: OpenCodePermissionReply,
    message: String?,
) {
    perform(
        "/api/session/$sessionID/permission/$requestID/reply",
        method = "POST",
        body = encodedBody(PermissionReplyBody(reply, message?.nonEmpty), "permission response"),
        server = server,
    )
}

internal suspend fun requestReplyForm(server: OpenCodeServer, sessionID: String, formID: String, answer: Map<String, OpenCodeFormValue>) {
    perform(
        "/api/session/$sessionID/form/$formID/reply",
        query = if (sessionID == "global") locationQuery(PromptContext.formLocationDirectory.current()) else emptyList(),
        method = "POST",
        body = encodedBody(FormReplyBody(answer), "form response"),
        server = server,
    )
}

internal suspend fun requestCancelForm(server: OpenCodeServer, sessionID: String, formID: String, message: String?) {
    val query = (if (sessionID == "global") locationQuery(PromptContext.formLocationDirectory.current()) else emptyList()).toMutableList()
    message?.nonEmpty?.let { query += "message" to it }
    perform("/api/session/$sessionID/form/$formID", query = query, method = "DELETE", server = server)
}

// Shells (iOS `OpenCodeClient+Shells.swift`).

internal suspend fun requestRunningShells(server: OpenCodeServer, directory: String): List<OpenCodeShell> =
    request<DataPayload<List<ShellInfo>>>("/api/shell", query = locationQuery(directory), server = server)
        .data.filter { it.status == "running" }.map { it.shell(isListed = true) }

internal suspend fun requestShell(server: OpenCodeServer, directory: String, shellID: String): OpenCodeShell =
    request<DataPayload<ShellInfo>>("/api/shell/$shellID", query = locationQuery(directory), server = server).data.shell(isListed = true)

internal suspend fun requestShellOutput(server: OpenCodeServer, directory: String, shellID: String, cursor: Int, limit: Int): OpenCodeShellOutputPage =
    request<DataPayload<OpenCodeShellOutputPage>>(
        "/api/shell/$shellID/output",
        query = locationQuery(directory) + listOf("cursor" to cursor.toString(), "limit" to limit.toString()),
        timeout = 20.seconds,
        server = server,
    ).data

internal suspend fun requestStopShell(server: OpenCodeServer, directory: String, shellID: String) {
    perform("/api/shell/$shellID", query = locationQuery(directory), method = "DELETE", server = server)
}
