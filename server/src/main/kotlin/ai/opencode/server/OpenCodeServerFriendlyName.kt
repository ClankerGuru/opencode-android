package ai.opencode.server

import java.net.URI

/**
 * Port of iOS `OpenCodeServer.friendlyName` (`OpenCode/Drawer/ServerSwitcher.swift`), moved down
 * to `:server` because App and Drawer both name servers with it. A name to show, never an
 * address: the user's name, else the machine's from a tailnet or Bonjour address, else the address
 * in use when it's a name ("my-mac" → "My Mac"). An IP the server hasn't named reads as "Server"
 * and its last part, as a last resort.
 */
val OpenCodeServer.friendlyName: String
    get() {
        val chosen = name?.trim().orEmpty()
        if (chosen.isNotEmpty()) return chosen
        val named = candidateURLs.asSequence()
            .mapNotNull { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            .firstOrNull { it.endsWith(".ts.net") || it.endsWith(".local") }
        return friendlyServerName(named ?: url)
    }

/** A friendly name for an address: a URL, `host`, `host:port`, `[v6]:port` or a bare IPv6 address. */
fun friendlyServerName(address: String): String {
    val trimmed = address.trim()
    val parsed = runCatching { URI(if (trimmed.contains("://")) trimmed else "http://$trimmed").host }.getOrNull()
    // A bare IPv6 address doesn't parse as an authority; its colons say what it is.
    var host = (parsed ?: trimmed).trim('[', ']')
    host.indexOf('%').takeIf { it >= 0 }?.let { host = host.substring(0, it) }
    if (host.contains(":")) {
        val last = host.split(":").lastOrNull().orEmpty()
        return if (last.isEmpty()) "Server" else "Server $last"
    }
    val labels = host.split(".").filter { it.isNotEmpty() }
    if (labels.size == 4 && labels.all { label -> label.all(Char::isDigit) }) return "Server ${labels[3]}"
    val first = labels.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return "Server"
    return first.split('-', '_').filter { it.isNotEmpty() }
        .joinToString(" ") { it.substring(0, 1).uppercase() + it.substring(1) }
}
