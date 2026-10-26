package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.testing.TestIsolationRule
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Port of the `:feature:system` suites of iOS `AppIconBadgeTests.swift`: pushes against the
 * ledger, its storage and the servers' refs. Home's publishing is `:feature:drawer`'s, the
 * AppFeature identity-forgetting tests `:app`'s. Dropped: `withoutAnAppGroupTheLedgerIsNeitherReadNorWritten`
 * (the ledger lives in the app's own preferences; there is no App Group), `badgesAreAddedOnlyToAFullGrantWithoutThem`,
 * `aPermissionGrantedLaterInTheSameRunStillGetsBadges`, `callsWhileOneRunsShareIt` (`BadgeAuthorization`:
 * Android has no separate badge authorization; the notification permission covers the badge).
 */
class AppIconBadgeTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun ledger() = AppBadgeLedger(
        serverKey = "0123456789ab", ref = "ref", sessionIDs = listOf("a"), mutedSessionIDs = listOf("m"), viewingSessionIDs = listOf("v"),
    )

    @Test
    fun aPushCountsItsSessionOnce() {
        var ledger = ledger()
        fun push(id: String, ref: String): Int? = ledger.badgeAfterPush(id, ref)?.let { (next, badge) -> ledger = next; badge }
        assertEquals(2, push("b", "ref"))
        assertEquals(2, push("b", "ref"))
        assertEquals("Already counted", 2, push("a", "ref"))
        assertEquals(listOf("a", "b"), ledger.sessionIDs)
    }

    @Test
    fun pushesThatDontCountLeaveTheBadge() {
        var ledger = ledger()
        fun push(id: String, ref: String): Int? = ledger.badgeAfterPush(id, ref)?.let { (next, badge) -> ledger = next; badge }
        assertNull(push("b", "another server"))
        assertNull("Muted", push("m", "ref"))
        assertNull("On screen", push("v", "ref"))
        ledger = ledger.copy(ref = null)
        assertNull("No ref minted yet", push("b", "ref"))
        assertEquals(listOf("a"), ledger.sessionIDs)
    }

    /** An in-memory `SharedPreferences` (iOS's per-test `UserDefaults(suiteName:)`). */
    private class MemoryPreferences : SharedPreferences {
        val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = HashMap<String, Any?>()
            val removals = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { removals += values.keys }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                removals.forEach(values::remove)
                values.putAll(changes)
            }
        }
    }

    @Test
    fun theLedgerIsSavedUnderAHashOnly() {
        val defaults = MemoryPreferences()
        val persistenceID = "kit|http://192.168.1.20:4096|https://box.tailnet.ts.net"
        val ledger = ledger().copy(serverKey = AppBadgeLedger.serverKey(persistenceID))
        ledger.save(defaults)
        val text = defaults.getString(AppBadgeLedger.KEY, null)!!
        assertFalse(text.contains("kit|") || text.contains("192.168") || text.contains("tailnet"))
        assertEquals(ledger, AppBadgeLedger.load(defaults))

        // A plaintext key is never written.
        val plaintext = ledger.copy(serverKey = persistenceID)
        AppBadgeLedger.remove(defaults)
        plaintext.save(defaults)
        assertNull(defaults.getString(AppBadgeLedger.KEY, null))
    }

    @Test
    fun aLedgerLeftWithAPlaintextKeyIsRemovedOnLoad() {
        val defaults = MemoryPreferences()
        val legacy = ledger().copy(serverKey = "kit|http://192.168.1.20:4096")
        defaults.edit().putString(AppBadgeLedger.KEY, Json.encodeToString(AppBadgeLedger.serializer(), legacy)).apply()
        assertNull(AppBadgeLedger.load(defaults))
        assertNull("Migrated by clearing", defaults.getString(AppBadgeLedger.KEY, null))
    }

    @Test
    fun aServersRefIsMadeOnceAndKept() {
        val values = DependencyValues.testing().update { this[SharedStore] = SharedStore.inMemory() }
        DependencyValues.withValues(values) {
            val notifications = SessionNotifications { _, _ -> }
            val first = notifications.ref("server")
            // Saved as soon as it's made: another instance over the same storage reads it back.
            assertEquals("Saved as soon as it's made, before any relay registration", first, SessionNotifications { _, _ -> }.ref("server"))
            assertEquals("The callback minted later seals the same one", first, notifications.ref("server"))
            assertNotEquals(first, notifications.ref("other"))
            UUID.fromString(first)
        }
    }
}
