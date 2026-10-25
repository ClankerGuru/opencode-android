package ai.opencode.feature.system.share

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.intents.SystemActionActivity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat

/**
 * Port of iOS `ShareSuggestion`: recent sessions in the system share sheet's top row. Each
 * session the app opens or sends to, and each one the share sheet sends to, is pushed as a
 * long-lived sharing shortcut (iOS: a donated outgoing `INSendMessageIntent`) whose ID is
 * `share:` + the session's conversation (`ShareConversation.identifier`). Its category
 * matches the `<share-target>` in `res/xml/shortcuts.xml`, so choosing one opens
 * [ShareActivity] pointed there. They're excluded from the launcher, whose quick actions
 * (`qa:` IDs) stay the app's own; nothing here ever replaces or clears every shortcut.
 */
object ShareSuggestion {
    const val CATEGORY = "ai.opencode.mobilecode.category.SHARE_SESSION"
    const val ID_PREFIX = "share:"

    fun shortcutID(serverKey: String, sessionID: String): String =
        ID_PREFIX + ShareConversation.identifier(serverKey, sessionID)

    /** The conversation a sharing shortcut's ID names (`share:<serverKey>:<sessionID>`). */
    fun conversation(shortcutID: String?): String? =
        shortcutID?.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

    fun shortcut(context: Context, serverKey: String, sessionID: String, title: String): ShortcutInfoCompat {
        val name = title.trim()
        val label = name.ifEmpty { "New session" }
        val action = SystemAction(serverKey, SystemAction.Destination.Session(sessionID))
        return ShortcutInfoCompat.Builder(context, shortcutID(serverKey, sessionID))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithBitmap(monogram(name)))
            .setIntent(SystemActionActivity.intent(context, action))
            .setCategories(setOf(CATEGORY))
            .setLongLived(true)
            .setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
            .build()
    }

    /** Blocking (a system call); run off the main thread. */
    fun donate(context: Context, serverKey: String, sessionID: String, title: String) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context, serverKey, sessionID, title)) }
            .onFailure { AppLog.app.error { "Couldn’t donate share suggestion: ${it::class.java.simpleName}" } }
    }

    /** Takes deleted sessions out of the suggestions. */
    fun remove(context: Context, serverKey: String, sessionIDs: List<String>) {
        if (sessionIDs.isEmpty()) return
        runCatching {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, sessionIDs.map { shortcutID(serverKey, it) })
        }
    }

    /** Every suggestion (iOS `INInteraction.deleteAll()`); quick actions stay. */
    fun removeAll(context: Context) {
        runCatching {
            val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(ID_PREFIX) }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }
    }

    /** The session's initials on a quiet circle; the system badges it with the app's icon. */
    fun monogram(title: String): Bitmap {
        val letters = title.split(Regex("\\s+"))
            .mapNotNull { word -> word.firstOrNull { it.isLetter() } }
            .take(2)
            .joinToString("") { it.uppercase() }
        val side = 120
        val bitmap = createBitmap(side, side)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B6B6B.toInt() }
        canvas.drawOval(0f, 0f, side.toFloat(), side.toFloat(), fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = side * 0.4f
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            textAlign = Paint.Align.CENTER
        }
        val label = letters.ifEmpty { "OC" }
        val baseline = side / 2f - (text.ascent() + text.descent()) / 2f
        canvas.drawText(label, side / 2f, baseline, text)
        return bitmap
    }
}
