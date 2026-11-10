package ai.opencode.feature.composer

import android.os.CancellationSignal
import android.os.OperationCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * One cancellable platform request (a MediaStore thumbnail or image read) that resumes exactly
 * once: the coroutine's cancellation cancels the request through its [CancellationSignal], and a
 * cancelled or failed request reads as null. iOS `OneShotRequestGate` (PhotoKit's request ID, a
 * map snapshotter's `cancel()`) guards a continuation resumed from a callback thread
 * (`arm`/`started`/`finish`); on Android the signal plus structured cancellation are the whole
 * gate, and the map snapshot needs none (`LocationClient.prefetchCompactSnapshot` bounds its own
 * job).
 */
class OneShotRequestGate {
    val signal = CancellationSignal()

    val isCancelled: Boolean get() = signal.isCanceled

    fun cancel() {
        if (!signal.isCanceled) signal.cancel()
    }

    /** Runs [load] on the IO dispatcher, cancelling the platform request if the caller is cancelled. */
    suspend fun <T : Any> run(load: (CancellationSignal) -> T?): T? = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { if (it != null) cancel() }
        try {
            if (isCancelled) null else load(signal)
        } catch (_: OperationCanceledException) {
            null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } finally {
            handle?.dispose()
        }
    }
}
