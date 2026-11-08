package ai.opencode.core.sharing

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Port of swift-sharing's `SharedKey` as the iOS app declares them (`OpenCode/Core/SharedKeys.swift`):
 * an identity, a default, a serializer and where the value lives.
 *
 * ```kotlin
 * val returnKeySends = SharedKey.appStorage("return-key-sends", false)
 * ```
 *
 * Keep iOS's names (the `id` strings) and defaults. Values whose types live above `:core`
 * declare their keys in the module that owns the type (e.g. `ServerSharedKeys` in `:server`).
 */
class SharedKey<T>(
    val id: String,
    val default: T,
    val serializer: KSerializer<T>,
    val storage: SharedStorageKind,
) {
    override fun toString(): String = "SharedKey($storage:$id)"

    companion object {
        /** iOS `.appStorage(name)` (UserDefaults) → DataStore Preferences. Non-secret preferences only. */
        inline fun <reified T> appStorage(name: String, default: T) =
            SharedKey(name, default, kotlinx.serialization.serializer<T>(), SharedStorageKind.AppStorage)

        /** iOS `.fileStorage(dir/name)` → a JSON file under the matching Android directory. */
        inline fun <reified T> fileStorage(directory: FileDirectory, fileName: String, default: T) =
            SharedKey(fileName, default, kotlinx.serialization.serializer<T>(), SharedStorageKind.File(directory))

        /** iOS `.keychain(name)` → an Android Keystore-encrypted, no-backup file. Anything carrying a secret. */
        inline fun <reified T> keychain(name: String, default: T) =
            SharedKey(name, default, kotlinx.serialization.serializer<T>(), SharedStorageKind.Keychain)

        /** iOS `.inMemory(name)`: process-lifetime only. */
        inline fun <reified T> inMemory(name: String, default: T) =
            SharedKey(name, default, kotlinx.serialization.serializer<T>(), SharedStorageKind.InMemory)
    }
}

sealed interface SharedStorageKind {
    data object AppStorage : SharedStorageKind
    data class File(val directory: FileDirectory) : SharedStorageKind
    data object Keychain : SharedStorageKind
    data object InMemory : SharedStorageKind
}

/** iOS's `.documentsDirectory` / `.cachesDirectory` / `.applicationSupportDirectory`. */
enum class FileDirectory {
    /** `filesDir/documents` (iOS Documents). */
    Documents,
    /** `cacheDir` (iOS Caches: the system may clear it). */
    Caches,
    /** `filesDir/support` (iOS Application Support). */
    ApplicationSupport,
}

/**
 * Port of `@Shared(.key) var value`: a reference to one persisted value. Reads are
 * synchronous ([value]); writes persist in the background.
 *
 * Rule (iOS AGENTS.md): mutate shared keys only inside reducers, and only when the value
 * actually changes ([set] and [update] skip unchanged values, so routine refreshes never
 * rewrite storage). Views read [value]/[flow]; effects observe [flow] and send actions.
 *
 * Equality is by key identity, so a `Shared` may sit in reducer `State` without making
 * state comparisons depend on persisted contents; assert shared values directly in tests.
 */
class Shared<T> internal constructor(
    val key: SharedKey<T>,
    private val reference: SharedReference<T>,
) {
    val value: T get() = reference.state.value
    val flow: StateFlow<T> get() = reference.state.asStateFlow()

    /** Writes [newValue] if it differs (iOS `$shared.withLock { $0 = newValue }`). */
    fun set(newValue: T) = reference.update { newValue }

    /** iOS `$shared.withLock { ... }`: an atomic read-modify-write; unchanged results are not saved. */
    fun update(transform: (T) -> T) = reference.update(transform)

    override fun equals(other: Any?): Boolean = other is Shared<*> && other.reference === reference
    override fun hashCode(): Int = System.identityHashCode(reference)
    override fun toString(): String = "Shared(${key.id}=$value)"
}

/** Resolves `@Shared(key)` against the current [SharedStore] dependency. */
fun <T> shared(key: SharedKey<T>): Shared<T> = dependency(SharedStore).reference(key)

/** One live value per (store, key): every `shared(key)` in a process sees the same reference. */
internal class SharedReference<T>(
    private val key: SharedKey<T>,
    private val bytes: SharedBytes,
    private val json: Json,
) {
    val state: MutableStateFlow<T> = MutableStateFlow(load())
    private val lock = Any()

    private fun load(): T {
        val data = bytes.read(key.storage, key.id) ?: return key.default
        return runCatching { json.decodeFromString(key.serializer, data.decodeToString()) }.getOrElse { key.default }
    }

    fun update(transform: (T) -> T) = synchronized(lock) {
        val old = state.value
        val new = transform(old)
        if (new == old) return
        state.value = new
        // Inside the lock (it only enqueues onto the serial writer), so writes keep transition order.
        // Encoded by the writer: a large file value (Home's snapshots) isn't encoded on the main
        // thread (iOS 24057173).
        bytes.writeEncoded(key.storage, key.id) { json.encodeToString(key.serializer, new).encodeToByteArray() }
    }

    /** Drops the saved value and goes back to the default ([SharedStore.remove]). */
    fun remove() = synchronized(lock) {
        state.value = key.default
        bytes.write(key.storage, key.id, null)
    }
}

/**
 * Where shared values persist. Live: DataStore/file/Keystore backends on disk. Test: a fresh
 * in-memory store per test container, so tests never read or clobber real settings or secrets
 * (iOS `KeychainStorage.inMemory()`).
 */
class SharedStore internal constructor(private val bytes: SharedBytes) {
    private val references = HashMap<Pair<SharedStorageKind, String>, SharedReference<*>>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> reference(key: SharedKey<T>): Shared<T> {
        val ref = synchronized(references) {
            references.getOrPut(key.storage to key.id) { SharedReference(key, bytes, json) } as SharedReference<T>
        }
        return Shared(key, ref)
    }

    /** Waits for every pending write (backgrounding, tests). */
    fun flush() = bytes.flush()

    /**
     * Whether [key] has a saved value, not just its default (iOS `defaults.object(forKey:) !=
     * nil`). For one-time migrations of keys earlier builds wrote.
     */
    fun contains(key: SharedKey<*>): Boolean = bytes.read(key.storage, key.id) != null

    /**
     * Removes [key]'s saved value (iOS `defaults.removeObject(forKey:)`); a reference already
     * open falls back to the key's default. For one-time migrations of retired keys.
     */
    fun remove(key: SharedKey<*>) {
        val ref = synchronized(references) { references[key.storage to key.id] }
        if (ref != null) ref.remove() else bytes.write(key.storage, key.id, null)
    }

    companion object Key : DependencyKey<SharedStore> {
        override val liveValue: SharedStore by lazy { SharedStore(LiveSharedBytes.create()) }
        override val testValue: SharedStore get() = inMemory()

        fun inMemory(): SharedStore = SharedStore(InMemorySharedBytes())
    }
}

/** Byte-level persistence behind [SharedStore]. */
internal interface SharedBytes {
    fun read(kind: SharedStorageKind, id: String): ByteArray?
    fun write(kind: SharedStorageKind, id: String, data: ByteArray?)

    /** [write] with the bytes made by [encode], which a live store may run off the caller's thread. */
    fun writeEncoded(kind: SharedStorageKind, id: String, encode: () -> ByteArray) = write(kind, id, encode())
    fun flush() {}
}

internal class InMemorySharedBytes : SharedBytes {
    private val values = HashMap<Pair<SharedStorageKind, String>, ByteArray>()
    override fun read(kind: SharedStorageKind, id: String): ByteArray? = synchronized(values) { values[kind to id] }
    override fun write(kind: SharedStorageKind, id: String, data: ByteArray?) {
        synchronized(values) { if (data == null) values.remove(kind to id) else values[kind to id] = data }
    }
}
