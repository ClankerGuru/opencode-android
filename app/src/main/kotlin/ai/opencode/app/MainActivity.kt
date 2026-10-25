package ai.opencode.app

import ai.opencode.core.store.Store
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel

/**
 * The single activity (iOS's one scene). The root [Store] lives in a retained [RootStore] so
 * configuration changes keep state and in-flight effects; [AppFeature] holds navigation
 * (connection vs Home) as state, like iOS `AppView`.
 */
class MainActivity : ComponentActivity() {
    private val root: RootStore by viewModels()
    private val store get() = root.store

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> store.send(AppFeature.Action.ForegroundChanged(true))
                    Lifecycle.Event.ON_STOP -> store.send(AppFeature.Action.ForegroundChanged(false))
                    else -> Unit
                }
            },
        )
        setContent { AppView(store) }
    }
}

/** Holds the app's root store across configuration changes; cancels its effects when the task ends. */
class RootStore : ViewModel() {
    val store = Store(AppFeature.State.initial(), AppFeature())

    override fun onCleared() = store.close()
}
