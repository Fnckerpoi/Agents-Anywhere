package com.agentsanywhere.embedded

import android.os.Bundle

internal fun Bundle.configDraft(): ConfigDraft? {
    val id = getString("draftId").orEmpty()
    if (id.isBlank() || !containsKey("draftRevision") || !containsKey("authGeneration")) return null
    val selection = getBundle("selected") ?: return null
    val validity = getBundle("valid") ?: return null
    val selected = ConfigLevel.entries.mapNotNull { level ->
        selection.getBundle(level.wire)?.configOption()?.let { level to it }
    }.toMap()
    return ConfigDraft(id, getLong("draftRevision"), getLong("authGeneration"), getString("baseRevision").orEmpty(),
        selected, ConfigLevel.entries.filter { validity.getBoolean(it.wire) }.toSet())
}
internal fun Bundle.configOption(): ConfigOption? {
    val id = getString("id").orEmpty()
    if (id.isBlank() || !containsKey("available")) return null
    return ConfigOption(id, getString("label").orEmpty(), getString("subtitle").orEmpty(), getBoolean("available"))
}
internal fun ConfigDraft.request() = Bundle().apply {
    putString("draftId", id); putLong("expectedDraftRevision", revision)
}
internal fun Bundle.matchesDraft(ticket: ConfigTicket): Boolean =
    getString("draftId") == ticket.draftId && getLong("requestDraftRevision", -1) == ticket.revision &&
        getLong("authGeneration", -1) == ticket.generation
internal fun Bundle.matchesParents(draft: ConfigDraft): Boolean {
    val parents = getBundle("parents") ?: return false
    return parents.getString("connectorId").orEmpty() == draft.selected[ConfigLevel.Connectors]?.id.orEmpty() &&
        parents.getString("projectId").orEmpty() == draft.selected[ConfigLevel.Projects]?.id.orEmpty() &&
        parents.getString("runtimeId").orEmpty() == draft.selected[ConfigLevel.Runtimes]?.id.orEmpty()
}
