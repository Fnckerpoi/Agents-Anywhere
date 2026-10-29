package com.agentsanywhere.embedded

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.IdentityHashMap

object PayloadEntry {
    private val owners = IdentityHashMap<View, PayloadOwner>()

    @JvmStatic fun create(host: Activity, payloadContext: Context, launch: Bundle, hostBridge: Any): View {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = PayloadOwner(launch.getBundle("savedState"))
        val view = ComposeView(payloadContext)
        view.id = 0x00aa0001
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        owners[view] = owner
        view.setContent { EmbeddedTheme { EmbeddedWorkbench(host, HostBridge(hostBridge), owner) } }
        return view
    }

    @JvmStatic fun lifecycle(root: View, event: String, state: Bundle) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = owners[root] ?: return
        when (event) {
            "start" -> owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            "resume" -> { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME); owner.refresh() }
            "pause" -> owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            "stop" -> owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            "refresh" -> owner.refresh()
            "save" -> owner.saved.performSave(state)
            "destroy" -> dispose(root)
            else -> error("Unsupported payload lifecycle event")
        }
    }

    @JvmStatic fun dispose(root: View) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = owners.remove(root) ?: return
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        (root as? ComposeView)?.disposeComposition()
        owner.viewModelStore.clear()
    }
}

internal class PayloadOwner(state: Bundle?) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    val registry = LifecycleRegistry(this)
    val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    init { saved.performAttach(); saved.performRestore(state); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    fun refresh() { revision++ }
}

internal class HostBridge(private val target: Any) {
    private val method = target.javaClass.getMethod("call", String::class.java, Bundle::class.java)
    fun call(operation: String, request: Bundle = Bundle()): Bundle =
        method.invoke(target, operation, request) as Bundle
}
