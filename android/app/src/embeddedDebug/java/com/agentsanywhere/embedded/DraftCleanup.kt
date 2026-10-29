package com.agentsanywhere.embedded

import java.util.concurrent.Executor

/** Owns only the draft ids created by this panel; a retired panel cannot close a newer panel's draft. */
internal class DraftCleanup(private val worker: Executor, private val closeDraft: (String) -> Unit) {
    private var disposed = false
    private var draftId: String? = null
    @Synchronized fun track(id: String) {
        if (disposed) worker.execute { closeDraft(id) } else draftId = id
    }
    @Synchronized fun dispose() {
        if (disposed) return
        disposed = true
        draftId?.let { id -> worker.execute { closeDraft(id) } }
        draftId = null
    }
}

internal object ConfigurationPresentation {
    val homeLevels: List<ConfigLevel> = listOf(
        ConfigLevel.Connectors,
        ConfigLevel.Projects,
        ConfigLevel.Runtimes,
    )
    val sessionLevels: List<ConfigLevel> = listOf(
        ConfigLevel.Sessions,
        ConfigLevel.Models,
        ConfigLevel.Permissions,
    )
    fun visibleLevels(expanded: Boolean): List<ConfigLevel> = if (expanded) homeLevels else emptyList()
}
