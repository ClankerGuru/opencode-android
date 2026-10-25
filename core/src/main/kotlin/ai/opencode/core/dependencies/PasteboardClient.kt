package ai.opencode.core.dependencies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Port of iOS `PasteboardClient` (`OpenCode/Browser/LinkMenu.swift`), moved down to `:core`
 * because the app, the browser, the transcript and the composer all copy and paste.
 * The system clipboard, for reducers (through effects) and views; never touch
 * `ClipboardManager` elsewhere.
 */
class PasteboardClient(
    val setURL: suspend (String) -> Unit,
    val setString: suspend (String) -> Unit = {},
    /** The clipboard's text if it isn't blank, else its URL. Main thread. */
    val string: () -> String? = { null },
    val hasImages: () -> Boolean = { false },
) {
    companion object Key : DependencyKey<PasteboardClient> {
        override val liveValue: PasteboardClient by lazy {
            val context = dependency(AppContext)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            PasteboardClient(
                setURL = { url -> withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newRawUri("URL", android.net.Uri.parse(url))) } },
                setString = { text -> withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newPlainText("Text", text)) } },
                string = {
                    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    item?.text?.toString()?.takeIf { it.isNotBlank() } ?: item?.uri?.toString()
                },
                hasImages = {
                    val description = clipboard.primaryClipDescription
                    description != null && (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
                },
            )
        }
        override val testValue = PasteboardClient(
            setURL = { throw AssertionError("Unimplemented: PasteboardClient.setURL") },
            setString = {},
            string = { null },
            hasImages = { false },
        )
    }
}

