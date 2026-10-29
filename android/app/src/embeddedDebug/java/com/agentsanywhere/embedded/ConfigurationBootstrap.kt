package com.agentsanywhere.embedded

import android.os.Bundle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface BootstrapResult {
    data class Validated(val draft: ConfigDraft) : BootstrapResult
    data class Failed(val code: String) : BootstrapResult
    data object Mismatched : BootstrapResult
    data object Stale : BootstrapResult
    data object Obsolete : BootstrapResult
}

internal suspend fun validateBootstrapLevel(
    captured: ConfigDraft,
    ticket: ConfigTicket,
    requests: ConfigurationRequests,
    currentDraft: () -> ConfigDraft?,
    isActive: () -> Boolean,
    list: suspend (Bundle) -> Bundle,
): BootstrapResult {
    val level = requireNotNull(ticket.level)
    fun current(): Boolean {
        val draft = currentDraft() ?: return false
        return isActive() && requests.owns(ticket) && draft.id == captured.id &&
            draft.revision == captured.revision && draft.generation == captured.generation &&
            ConfigurationPresentation.homeLevels.all { draft.selected[it]?.id == captured.selected[it]?.id }
    }
    repeat(2) { attempt ->
        currentCoroutineContext().ensureActive()
        if (!current()) return BootstrapResult.Obsolete
        val response = list(captured.request().apply {
            putString("level", level.wire)
            putLong("requestId", ticket.sequence)
        })
        currentCoroutineContext().ensureActive()
        if (!current()) return BootstrapResult.Obsolete
        val code = response.getString("errorCode").orEmpty()
        if (!requests.accepts(ticket, currentDraft(), response.getString("draftId").orEmpty(),
                response.getLong("requestDraftRevision", -1), response.getLong("authGeneration", -1),
                response.getLong("requestId", -1), response.getString("level").orEmpty())) {
            return if (ConfigurationText.isStale(code)) BootstrapResult.Stale else BootstrapResult.Mismatched
        }
        // Host error envelopes omit parents; the unchanged local draft anchors that request.
        // If an error does carry parents, it must agree just like a successful response.
        if ((response.getBoolean("ok") || response.containsKey("parents")) && !response.matchesParents(captured)) {
            return BootstrapResult.Stale
        }
        if (!response.getBoolean("ok")) {
            if (ConfigurationText.isStale(code)) return BootstrapResult.Stale
            if (code == "DIRECTORY_FAILED" && attempt == 0) return@repeat
            return BootstrapResult.Failed(code)
        }
        val next = response.configDraft()
        if (next == null || next.id != captured.id || next.generation != captured.generation) {
            return BootstrapResult.Stale
        }
        return BootstrapResult.Validated(next)
    }
    error("Bootstrap retry exhausted without a result")
}
