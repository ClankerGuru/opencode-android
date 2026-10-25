package ai.opencode.core.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of iOS `CredentialSurface` (`Diagnostics/Traces/TraceTriggers.swift`): counts what's on
 * screen that shows credentials (Settings, with its API key field). Problem report screenshots
 * skip while any is visible. In `:core` so Settings can mark itself without depending on the
 * developer tools.
 */
object CredentialSurface {
    private val count = AtomicInteger(0)

    val visible: Int get() = count.get()

    fun appeared() {
        count.incrementAndGet()
    }

    fun disappeared() {
        count.updateAndGet { maxOf(0, it - 1) }
    }
}

/** iOS `.hidesFromProblemReports()`: keeps the screen composing this out of problem report screenshots while it's composed. */
@Composable
fun HidesFromProblemReports() {
    DisposableEffect(Unit) {
        CredentialSurface.appeared()
        onDispose { CredentialSurface.disappeared() }
    }
}
