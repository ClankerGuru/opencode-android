package ai.opencode.feature.session.sheet

import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.CancellationException

/** Runs [block], returning the client error it threw (iOS `do throws(OpenCodeClientError)`), or null. */
internal suspend fun clientError(block: suspend () -> Unit): OpenCodeClientError? = try {
    block()
    null
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    error.asClientError()
}

/** [clientError] for a call with a value. */
internal suspend fun <T> clientResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error.asClientError())
}

internal fun Throwable.asClientError(): OpenCodeClientError =
    this as? OpenCodeClientError ?: OpenCodeClientError(message ?: "Something went wrong.")
