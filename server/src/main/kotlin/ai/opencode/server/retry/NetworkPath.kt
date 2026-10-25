package ai.opencode.server.retry

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's network path, from one shared default-network callback (iOS `NWPathMonitor`).
 * A hint for *when* to retry, never a verdict that a request will fail.
 */
class NetworkPathMonitor private constructor(context: Context) {
    data class Snapshot(
        val isSatisfied: Boolean,
        /** The interface traffic would leave on ("wifi wlan0", "cellular rmnet0"). */
        val primaryInterface: String?,
        /** Metered: cellular or a hotspot (`NWPath.isExpensive`). */
        val isExpensive: Boolean = false,
        /** Data Saver restricts this app (`NWPath.isConstrained`, Low Data Mode). */
        val isConstrained: Boolean = false,
    ) {
        /** A change worth reconnecting for: the path came back, or traffic moved to another interface. */
        fun isReroute(previous: Snapshot?): Boolean {
            if (previous == null || !isSatisfied) return false
            return !previous.isSatisfied || previous.primaryInterface != primaryInterface
        }
    }

    private val lock = Any()
    private var state: Snapshot? = null
    private val observers = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var capabilities: NetworkCapabilities? = null
    private var link: LinkProperties? = null

    init {
        val manager = connectivity
        if (manager != null) {
            if (manager.activeNetwork == null) receive(Snapshot(isSatisfied = false, primaryInterface = null))
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    synchronized(lock) { capabilities = networkCapabilities }
                    publish()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    synchronized(lock) { link = linkProperties }
                    publish()
                }

                override fun onLost(network: Network) {
                    synchronized(lock) {
                        capabilities = null
                        link = null
                    }
                    receive(Snapshot(isSatisfied = false, primaryInterface = null))
                }
            })
        }
    }

    private fun publish() {
        val (caps, properties) = synchronized(lock) { capabilities to link }
        caps ?: return
        val satisfied = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wiredEthernet"
            else -> "other"
        }
        val constrained = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        receive(
            Snapshot(
                isSatisfied = satisfied,
                primaryInterface = if (satisfied) "$type ${properties?.interfaceName ?: ""}".trim() else null,
                isExpensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                isConstrained = constrained,
            ),
        )
    }

    /** Null until the first path arrives. */
    val current: Snapshot? get() = synchronized(lock) { state }

    val isConstrained: Boolean get() = current?.isConstrained == true
    val isExpensive: Boolean get() = current?.isExpensive == true

    /** Non-essential large prefetches defer on Data Saver, or Battery Saver on a metered link. */
    val shouldDeferLargePrefetch: Boolean
        get() = shouldDeferLargePrefetch(current, power?.isPowerSaveMode == true)

    /** Every path from now on, starting with the current one when known. */
    fun updates(): Flow<Snapshot> = callbackFlow {
        val observer: (Snapshot) -> Unit = { trySend(it) }
        val current = synchronized(lock) {
            observers.add(observer)
            state
        }
        current?.let { trySend(it) }
        awaitClose { observers.remove(observer) }
    }.buffer(4, BufferOverflow.DROP_OLDEST)

    suspend fun waitUntilSatisfied() {
        updates().first { it.isSatisfied }
    }

    private fun receive(snapshot: Snapshot) {
        synchronized(lock) {
            if (state == snapshot) return
            state = snapshot
        }
        for (observer in observers) observer(snapshot)
    }

    companion object {
        /** The process-wide monitor; null where no application context is installed (JVM tests). */
        val shared: NetworkPathMonitor? by lazy {
            runCatching { NetworkPathMonitor(dependency(AppContext).applicationContext) }.getOrNull()
        }

        fun shouldDeferLargePrefetch(snapshot: Snapshot?, isLowPowerModeEnabled: Boolean = false): Boolean {
            snapshot ?: return false
            if (snapshot.isConstrained) return true
            return snapshot.isExpensive && isLowPowerModeEnabled
        }
    }
}

enum class AppPhase { Active, Background }

/**
 * The app entering the background or becoming active again (iOS
 * `didEnterBackground`/`didBecomeActive`), from activity start/stop counts.
 * Call [install] from `Application.onCreate` so the first foregrounding is counted;
 * otherwise it installs itself on first use.
 */
object AppPhases {
    private val observers = CopyOnWriteArrayList<(AppPhase) -> Unit>()
    @Volatile private var installed = false
    private var started = 0

    fun install(application: Application) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val becameActive = synchronized(this@AppPhases) { started++ == 0 }
                if (becameActive) observers.forEach { it(AppPhase.Active) }
            }

            override fun onActivityStopped(activity: Activity) {
                val wentBackground = synchronized(this@AppPhases) {
                    started = maxOf(0, started - 1)
                    started == 0
                }
                if (wentBackground) observers.forEach { it(AppPhase.Background) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun phases(): Flow<AppPhase> = callbackFlow {
        if (!installed) {
            runCatching { dependency(AppContext).applicationContext as? Application }.getOrNull()?.let(::install)
        }
        val observer: (AppPhase) -> Unit = { trySend(it) }
        observers.add(observer)
        awaitClose { observers.remove(observer) }
    }
}

/** The network path for reducers: when to reconnect, and whether to wait. */
data class NetworkPathClient(
    /** Yields each time the path comes back or moves to another interface. */
    val reroutes: () -> Flow<Unit>,
    val isOffline: () -> Boolean,
    val isConstrained: () -> Boolean = { false },
    val isExpensive: () -> Boolean = { false },
    /** Returns once a network path is available (or at once if one is). */
    val available: suspend () -> Unit,
    /** Returns the next time the server with this `persistenceID` answers a request, or when cancelled. */
    val serverAnswered: suspend (server: String) -> Unit,
    /** Yields each time the app enters the background or becomes active again. */
    val appPhases: () -> Flow<AppPhase> = { emptyFlow() },
) {
    /** A retry environment over this path, with live jitter. */
    val retryEnvironment: RetryEnvironment
        get() = RetryEnvironment(random = ::liveRandom, isOffline = isOffline, networkAvailable = available)

    companion object Key : DependencyKey<NetworkPathClient> {
        override val liveValue: NetworkPathClient by lazy {
            NetworkPathClient(
                reroutes = {
                    flow {
                        val monitor = NetworkPathMonitor.shared ?: return@flow
                        var previous: NetworkPathMonitor.Snapshot? = null
                        monitor.updates().collect { snapshot ->
                            if (snapshot.isReroute(previous)) emit(Unit)
                            previous = snapshot
                        }
                    }
                },
                isOffline = { NetworkPathMonitor.shared?.current?.isSatisfied == false },
                isConstrained = { NetworkPathMonitor.shared?.isConstrained == true },
                isExpensive = { NetworkPathMonitor.shared?.isExpensive == true },
                available = { NetworkPathMonitor.shared?.waitUntilSatisfied() },
                serverAnswered = { ServerContact.shared.next(it) },
                appPhases = { AppPhases.phases() },
            )
        }

        /** Always online; never reroutes, and never hears from a server. */
        override val testValue: NetworkPathClient = NetworkPathClient(
            reroutes = { emptyFlow() },
            isOffline = { false },
            available = {},
            serverAnswered = { awaitCancellation() },
        )
    }
}
