package ai.opencode.feature.voice

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/** Voice-local port of Diagnostics' category; :voice cannot depend on :devtools. */
sealed class DiagnosticsErrorCategory(val description: String) {
    data object unauthorized : DiagnosticsErrorCategory("unauthorized")
    data object notFound : DiagnosticsErrorCategory("notFound")
    data object timeout : DiagnosticsErrorCategory("timeout")
    data object offline : DiagnosticsErrorCategory("offline")
    data object cancelled : DiagnosticsErrorCategory("cancelled")
    data class server(val status: Int) : DiagnosticsErrorCategory("server($status)")
    data object transport : DiagnosticsErrorCategory("transport")

    final override fun toString() = description

    companion object {
        fun of(status: Int): DiagnosticsErrorCategory = when (status) {
            401 -> unauthorized
            404 -> notFound
            else -> server(status)
        }

        fun of(error: Throwable): DiagnosticsErrorCategory = when (error) {
            is CancellationException -> cancelled
            is SocketTimeoutException -> timeout
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> offline
            is SocketException -> when {
                error.message.orEmpty().contains("ETIMEDOUT", ignoreCase = true) -> timeout
                offlineSocket.containsMatchIn(error.message.orEmpty()) -> offline
                else -> transport
            }
            is InterruptedIOException -> if (error.message.equals("timeout", ignoreCase = true)) timeout else transport
            is IOException -> if (error.message.equals("Canceled", ignoreCase = true)) cancelled else transport
            else -> transport
        }

        /** Persisted iOS diagnostics keep their original NSError domains/codes. */
        fun of(domain: String, code: Int): DiagnosticsErrorCategory = when (domain) {
            "NSURLErrorDomain" -> when (code) {
                -1001 -> timeout
                -1003, -1004, -1005, -1006, -1009, -1018, -1020 -> offline
                -999 -> cancelled
                else -> transport
            }
            "NSPOSIXErrorDomain" -> when (code) {
                60 -> timeout
                57, 54, 53, 32, 50, 51, 64, 65 -> offline
                else -> transport
            }
            else -> transport
        }

        private val offlineSocket = Regex(
            "ENOTCONN|ECONNRESET|ECONNABORTED|EPIPE|ENETDOWN|ENETUNREACH|EHOSTDOWN|EHOSTUNREACH|" +
                "connection reset|connection aborted|broken pipe|not connected|network is (?:down|unreachable)|no route to host",
            RegexOption.IGNORE_CASE,
        )
    }
}
