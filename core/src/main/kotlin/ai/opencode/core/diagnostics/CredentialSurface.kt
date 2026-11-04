package ai.opencode.core.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of iOS `CredentialSurface` (`Diagnostics/Traces/TraceTriggers.swift`): counts what's on
 * screen that screenshots must skip. `sheets` show credentials (the server and pairing sheets, the
 * pairing camera) and skip every screenshot; `screens` (Settings, What's New) show server
 * addresses and account details and skip only problem reports, which leave the phone (9436b567).
 * In `:core` so Settings and the app can mark themselves without depending on the developer tools.
 */
object CredentialSurface {
    data class Marks(val sheets: Int = 0, val screens: Int = 0)

    enum class Kind { sheets, screens }

    private val sheets = AtomicInteger(0)
    private val screens = AtomicInteger(0)

    val marks: Marks get() = Marks(sheets = sheets.get(), screens = screens.get())

    /** What problem reports skip on: every marked surface. */
    val visible: Int get() = ScreenshotPolicy.problemReport.markedSurfaces(marks)

    fun appeared(kind: Kind = Kind.screens) {
        counter(kind).incrementAndGet()
    }

    fun disappeared(kind: Kind = Kind.screens) {
        counter(kind).updateAndGet { maxOf(0, it - 1) }
    }

    private fun counter(kind: Kind) = when (kind) {
        Kind.sheets -> sheets
        Kind.screens -> screens
    }
}

/**
 * iOS `ScreenshotPolicy`: who a screenshot is for. Problem reports leave the phone, so they skip
 * every marked surface; the Developer Mode voice tool sends to the person's own session, so it
 * skips only the credential sheets.
 */
enum class ScreenshotPolicy {
    problemReport,
    voice;

    fun markedSurfaces(marks: CredentialSurface.Marks): Int = when (this) {
        problemReport -> marks.sheets + marks.screens
        voice -> marks.sheets
    }
}

/** iOS `.hidesFromProblemReports()`: keeps the screen composing this out of problem report screenshots while it's composed. */
@Composable
fun HidesFromProblemReports() = CredentialMark(CredentialSurface.Kind.screens)

/** iOS `.hidesFromScreenshots()`: keeps this credential view out of every screenshot, voice ones included. */
@Composable
fun HidesFromScreenshots() = CredentialMark(CredentialSurface.Kind.sheets)

/** iOS `.hidesFromScreenshots(while:)`: keeps every screenshot out while [shown] (an alert with no lifecycle of its own). */
@Composable
fun HidesFromScreenshots(shown: Boolean) {
    if (shown) HidesFromScreenshots()
}

@Composable
private fun CredentialMark(kind: CredentialSurface.Kind) {
    DisposableEffect(kind) {
        CredentialSurface.appeared(kind)
        onDispose { CredentialSurface.disappeared(kind) }
    }
}
