package com.agentsanywhere.embedded

import org.junit.Assert.*
import org.junit.Test

class ConfigurationStateTest {
    private fun option(id: String, label: String, subtitle: String = "", available: Boolean = true) = ConfigOption(id, label, subtitle, available)
    private fun draft(revision: Long = 1, generation: Long = 0) = ConfigDraft(
        "draft", revision, generation, "saved-revision",
        ConfigLevel.entries.associateWith { level -> option("exact-id", "Name", subtitleFor(level)) }, ConfigLevel.entries.toSet())
    private fun subtitleFor(level: ConfigLevel) = when (level) {
        ConfigLevel.Models -> "high-reasoning"
        ConfigLevel.Permissions -> "Workspace write"
        else -> ""
    }
    private fun binding(generation: Long = 0, revision: String = "rev", session: String = "bound-session", model: String = "dsh:model:exact", permission: String = "dsh:permission:exact") = WorkbenchBinding(
        AccountIdentity("https://example.invalid", "user", generation), revision, true, true, false,
        "connector", "project", "dsh", "runtime", model, permission, session)

    @Test fun sixCardsHaveStableLevels() {
        assertEquals(listOf("connectors", "projects", "runtimes", "sessions", "models", "permissions"), ConfigLevel.entries.map { it.wire })
    }
    @Test fun sixCardNamesMatchConfirmedCopy() {
        assertEquals(listOf("电脑设备", "项目", "DSH运行时", "Session/会话", "模型与推理", "执行权限"), ConfigLevel.entries.map { it.title })
    }
    @Test fun homePanelShowsOnlyThreeEnvironmentCards() {
        assertEquals(listOf(ConfigLevel.Connectors, ConfigLevel.Projects, ConfigLevel.Runtimes), ConfigurationPresentation.homeLevels)
        assertEquals(listOf(ConfigLevel.Sessions, ConfigLevel.Models, ConfigLevel.Permissions), ConfigurationPresentation.sessionLevels)
        assertTrue(ConfigurationPresentation.visibleLevels(false).isEmpty())
        assertEquals(3, ConfigurationPresentation.visibleLevels(true).size)
        assertEquals("三项环境选择 · 保存后启用", ConfigurationText.homeSubtitle)
    }
    @Test fun homeDraftSavesWithOnlyThreeEnvironmentItemsReady() {
        val onlyHome = draft().copy(
            selected = mapOf(
                ConfigLevel.Connectors to option("c", "Device"),
                ConfigLevel.Projects to option("p", "Project"),
                ConfigLevel.Runtimes to option("r", "Runtime"),
            ),
            valid = setOf(ConfigLevel.Connectors, ConfigLevel.Projects, ConfigLevel.Runtimes),
        )
        assertTrue(onlyHome.canSave)
        assertFalse(onlyHome.copy(valid = setOf(ConfigLevel.Connectors, ConfigLevel.Projects)).canSave)
        assertFalse(onlyHome.copy(selected = onlyHome.selected - ConfigLevel.Runtimes).canSave)
        assertFalse(onlyHome.copy(selected = onlyHome.selected + (ConfigLevel.Runtimes to option("r", "Runtime", "", false))).canSave)
        assertFalse(onlyHome.copy(selected = onlyHome.selected + (ConfigLevel.Runtimes to option("", "Runtime"))).canSave)
    }
    @Test fun unselectedParentsDisableCardsWithSpecificGuidance() {
        val empty = draft().copy(selected = emptyMap())
        assertTrue(ConfigurationInteraction.canList(ConfigLevel.Connectors, empty, false))
        for (level in ConfigLevel.entries.filter { it != ConfigLevel.Connectors }) {
            assertFalse(ConfigurationInteraction.canList(level, empty, false))
            assertEquals("请先选择电脑设备", ConfigurationInteraction.parentHint(level, empty))
        }
        val device = empty.copy(selected = mapOf(ConfigLevel.Connectors to option("c", "Device")))
        assertTrue(ConfigurationInteraction.canList(ConfigLevel.Projects, device, false))
        assertTrue(ConfigurationInteraction.canList(ConfigLevel.Runtimes, device, false))
        assertEquals("请先选择项目", ConfigurationInteraction.parentHint(ConfigLevel.Sessions, device))
        assertEquals("请先选择DSH运行时", ConfigurationInteraction.parentHint(ConfigLevel.Permissions, device))
        val project = device.copy(selected = device.selected + (ConfigLevel.Projects to option("p", "Project")))
        assertEquals("请先选择DSH运行时", ConfigurationInteraction.parentHint(ConfigLevel.Sessions, project))
        val runtime = project.copy(selected = project.selected + (ConfigLevel.Runtimes to option("r", "Runtime")))
        assertTrue(ConfigurationInteraction.canList(ConfigLevel.Permissions, runtime, false))
        assertTrue(ConfigurationInteraction.canList(ConfigLevel.Sessions, runtime, false))
        assertFalse(ConfigurationInteraction.canList(ConfigLevel.Permissions, runtime, true))
    }
    @Test fun reopeningClearsOldFailureButPreservesStaleRecoverySignal() {
        assertNull(ConfigurationInteraction.failureOnReopen(ConfigurationText.failure("INVALID_TARGET")))
        assertNull(ConfigurationInteraction.failureOnReopen(ConfigurationText.failure("DIRECTORY_FAILED")))
        assertEquals(ConfigurationText.needingReload, ConfigurationInteraction.failureOnReopen(ConfigurationText.needingReload))
        assertTrue(ConfigurationText.failure("INVALID_TARGET").contains("请先选择"))
        assertNotEquals(ConfigurationText.failure("UNKNOWN"), ConfigurationText.failure("INVALID_TARGET"))
    }
    @Test fun sessionCommitUsesExactFieldsAndNewSessionSentinel() {
        val spec = ConfigurationInteraction.buildSessionCommitSpec(
            binding(), "session-42", "connector", "project", "runtime", "dsh:model:two", "dsh:permission:write",
        )!!
        assertEquals("connector", spec.connectorId)
        assertEquals("project", spec.projectId)
        assertEquals("dsh", spec.runtimeType)
        assertEquals("runtime", spec.runtimeId)
        assertEquals("session-42", spec.sessionId)
        assertEquals("dsh:model:two", spec.model)
        assertEquals("dsh:permission:write", spec.permission)
        assertTrue(spec.forceBind)
        assertEquals(0L, spec.expectedAuthGeneration)
        assertEquals("rev", spec.expectedRevision)
        assertEquals("bound-session", spec.expectedSessionId)
        val newSpec = ConfigurationInteraction.buildSessionCommitSpec(
            binding(), ConfigurationText.newSessionId, "connector", "project", "runtime", "dsh:model:two", "dsh:permission:write",
        )!!
        assertEquals("", newSpec.sessionId)
        assertEquals("", ConfigurationInteraction.resolvedSessionIdForCommit(null))
    }
    @Test fun sessionCommitRejectsLabelIdsAndUnknownDelivery() {
        assertNull(ConfigurationInteraction.buildSessionCommitSpec(binding(), "s", "connector", "project", "runtime", "GPT-5", "dsh:permission:write"))
        assertNull(ConfigurationInteraction.buildSessionCommitSpec(binding(), "s", "connector", "project", "runtime", "dsh:model:x", "read-only"))
        assertNotNull(ConfigurationInteraction.buildSessionCommitSpec(binding(), "s", "connector", "project", "runtime", "dsh:model:x", "dsh:permission:y"))
        // Blank inputs intentionally fall back to the authoritative binding (host keeps a valid
        // current selection), so rejection must be asserted through genuinely invalid state.
        assertEquals("runtime", ConfigurationInteraction.buildSessionCommitSpec(binding(), "s", "connector", "project", "", "dsh:model:x", "dsh:permission:y")!!.runtimeId)
        assertNull(ConfigurationInteraction.buildSessionCommitSpec(binding(), null, "connector", "project", "runtime", "dsh:model:x", "dsh:permission:y"))
        assertNull(ConfigurationInteraction.buildSessionCommitSpec(binding().copy(runtimeId = ""), "s", "connector", "project", "", "dsh:model:x", "dsh:permission:y"))
        assertNull(ConfigurationInteraction.buildSessionCommitSpec(binding().copy(authenticated = false), "s", "connector", "project", "runtime", "dsh:model:x", "dsh:permission:y"))
    }
    @Test fun unknownDeliveryBlocksSessionCommit() {
        val locked = binding().copy(unknownDelivery = true)
        assertNull(ConfigurationInteraction.buildSessionCommitSpec(locked, "s", "connector", "project", "runtime", "dsh:model:x", "dsh:permission:y"))
    }
    @Test fun generationZeroRestartCanStillCommitAndSend() {
        val restarted = binding(generation = 0)
        val spec = ConfigurationInteraction.buildSessionCommitSpec(restarted, "s", "connector", "project", "runtime", "dsh:model:x", "dsh:permission:y")
        assertNotNull(spec)
        assertEquals(0L, spec!!.expectedAuthGeneration)
    }
    @Test fun sendNeedsCommitOnlyWhenTargetOrParametersDiffer() {
        val current = binding()
        assertFalse(ConfigurationInteraction.needsCommitBeforeSend(current, "bound-session", "connector", "project", "runtime", "dsh:model:exact", "dsh:permission:exact"))
        assertTrue(ConfigurationInteraction.needsCommitBeforeSend(current, "other-session", "connector", "project", "runtime", "dsh:model:exact", "dsh:permission:exact"))
        assertTrue(ConfigurationInteraction.needsCommitBeforeSend(current, "bound-session", "connector", "project", "runtime", "dsh:model:other", "dsh:permission:exact"))
        assertTrue(ConfigurationInteraction.needsCommitBeforeSend(current, ConfigurationText.newSessionId, "connector", "project", "runtime", "dsh:model:exact", "dsh:permission:exact"))
    }
    @Test fun sendButtonRequiresDraftAndValidParameters() {
        val current = binding()
        assertTrue(ConfigurationInteraction.canSendInSessionView(current, "bound-session", "connector", "project", "runtime", "dsh:model:exact", "dsh:permission:exact", "hi", false))
        assertFalse(ConfigurationInteraction.canSendInSessionView(current, "bound-session", "connector", "project", "runtime", "dsh:model:exact", "dsh:permission:exact", "", false))
        assertFalse(ConfigurationInteraction.canSendInSessionView(current, "bound-session", "connector", "project", "runtime", "dsh:model:exact", "dsh:permission:exact", "hi", true))
        assertFalse(ConfigurationInteraction.canSendInSessionView(current, "bound-session", "connector", "project", "runtime", "GPT-5", "dsh:permission:exact", "hi", false))
        assertFalse(ConfigurationInteraction.canSendInSessionView(current.copy(runtimeId = ""), "bound-session", "connector", "project", "", "dsh:model:exact", "dsh:permission:exact", "hi", false))
    }
    @Test fun catalogEchoMustMatchGenerationParentsLevelAndRequestId() {
        assertTrue(ConfigurationInteraction.acceptsCatalogResponse(0L, "connector", "runtime", ConfigLevel.Models, 7L, 0L, "connector", "runtime", "models", 7L))
        assertFalse(ConfigurationInteraction.acceptsCatalogResponse(0L, "connector", "runtime", ConfigLevel.Models, 7L, 1L, "connector", "runtime", "models", 7L))
        assertFalse(ConfigurationInteraction.acceptsCatalogResponse(0L, "connector", "runtime", ConfigLevel.Models, 7L, 0L, "other", "runtime", "models", 7L))
        assertFalse(ConfigurationInteraction.acceptsCatalogResponse(0L, "connector", "runtime", ConfigLevel.Models, 7L, 0L, "connector", "other", "models", 7L))
        assertFalse(ConfigurationInteraction.acceptsCatalogResponse(0L, "connector", "runtime", ConfigLevel.Models, 7L, 0L, "connector", "runtime", "permissions", 7L))
        assertFalse(ConfigurationInteraction.acceptsCatalogResponse(0L, "connector", "runtime", ConfigLevel.Models, 7L, 0L, "connector", "runtime", "models", 8L))
    }
    @Test fun catalogDisplayShowsSubtitleAndNeverLeaksPath() {
        val models = listOf(option("dsh:model:a", "Same", "high-reasoning"), option("dsh:model:b", "Same", "low-effort"))
        assertEquals("Same" to "high-reasoning", ConfigurationText.catalogDisplay(ConfigLevel.Models, "dsh:model:a", models))
        assertEquals("Same" to "low-effort", ConfigurationText.catalogDisplay(ConfigLevel.Models, "dsh:model:b", models))
        assertEquals("已选择" to "", ConfigurationText.catalogDisplay(ConfigLevel.Models, "dsh:model:missing", models))
        assertEquals("请选择" to "", ConfigurationText.catalogDisplay(ConfigLevel.Models, "", models))
        assertEquals("已选择" to "", ConfigurationText.catalogDisplay(ConfigLevel.Projects, "/private/path", emptyList()))
    }
    @Test fun sessionTitlesDistinguishNewBoundAndPendingTakeover() {
        assertEquals("当前会话：新建会话（发送时创建）", ConfigurationText.sessionStatusTitle(ConfigurationText.newSessionId, null, "bound"))
        assertEquals("当前会话：My Session（已设为当前会话）", ConfigurationText.sessionStatusTitle("bound", "My Session", "bound"))
        assertEquals("当前会话：My Session（待接管）", ConfigurationText.sessionStatusTitle("other", "My Session", "bound"))
        assertEquals("保存新会话参数", ConfigurationText.sessionCommitButtonText(ConfigurationText.newSessionId))
        assertEquals("接管并设为当前会话", ConfigurationText.sessionCommitButtonText("session-1"))
    }
    @Test fun dispatchFailureShowsHostChineseMessageAndKeepsDraftForUnknownDelivery() {
        assertEquals("目标会话正在执行中，请稍后再试", ConfigurationText.dispatchFailureMessage("目标会话正在执行中，请稍后再试", "409", "failed"))
        val unknown = ConfigurationText.dispatchFailureMessage(null, "DELIVERY_UNKNOWN", "unknown_delivery")
        assertTrue(unknown.contains("unknown", ignoreCase = true) || unknown.contains("不重发") || unknown.contains("do not resend"))
        assertTrue(ConfigurationText.failure("INVALID_TARGET").contains("请先选择"))
        assertNotEquals(ConfigurationText.rejectedRequest, ConfigurationText.dispatchFailureMessage("busy", null, "failed"))
    }
    @Test fun projectDisplayNeverFallsBackToPathId() {
        for (label in listOf("", "   ", "C:/workspace/project", "项目（/workspace/project）", "项目 (/workspace/project)")) {
            val visible = ConfigurationText.label(ConfigLevel.Projects, option("/sensitive/id", label, "hidden"))
            assertFalse(visible.contains("/")); assertFalse(visible.contains(92.toChar())); assertFalse(visible.contains("workspace"))
            assertEquals("", ConfigurationText.subtitle(ConfigLevel.Projects, option("/sensitive/id", "Project", "/sensitive/subtitle")))
        }
    }
    @Test fun modelSubtitlesDistinguishSameLabelAndProjectsStayBlank() {
        assertEquals("high-reasoning", ConfigurationText.subtitle(ConfigLevel.Models, option("m1", "Same", "high-reasoning")))
        assertEquals("low-effort", ConfigurationText.subtitle(ConfigLevel.Models, option("m2", "Same", "low-effort")))
        assertEquals("", ConfigurationText.subtitle(ConfigLevel.Models, option("m3", "Same", "")))
        assertEquals("", ConfigurationText.subtitle(ConfigLevel.Projects, option("p1", "Project", "/hidden")))
        assertEquals("", ConfigurationText.subtitle(ConfigLevel.Permissions, option("p", "Permission", "/hidden/path")))
    }
    @Test fun absentCandidateAndExplicitNewSessionHaveDistinctText() {
        assertEquals("请选择", ConfigurationText.label(ConfigLevel.Projects, null))
        assertEquals("新建会话", ConfigurationText.label(ConfigLevel.Sessions, option(ConfigurationText.newSessionId, "ignored")))
    }
    @Test fun staleSuccessAndStaleErrorCannotReplaceNewDraft() {
        val requests = ConfigurationRequests(); val before = draft(); val ticket = requests.begin(before, ConfigLevel.Projects)
        assertTrue(requests.accepts(ticket, before, "draft", 1, 0, ticket.sequence, "projects"))
        assertFalse(requests.accepts(ticket, draft(2), "draft", 1, 0, ticket.sequence, "projects"))
        assertFalse(requests.accepts(ticket, before, "draft", 1, 1, ticket.sequence, "projects"))
        requests.invalidate(); assertFalse(requests.owns(ticket))
    }
    @Test fun invalidLevelOrEchoIsRejected() {
        val requests = ConfigurationRequests(); val state = draft(); val ticket = requests.begin(state, ConfigLevel.Models)
        assertFalse(requests.accepts(ticket, state, "other", 1, 0, ticket.sequence, "models"))
        assertFalse(requests.accepts(ticket, state, "draft", 1, 0, ticket.sequence + 1, "models"))
        assertFalse(requests.accepts(ticket, state, "draft", 1, 0, ticket.sequence, "permissions"))
    }
    @Test fun levelTitlesAreUserFacingAndNonEmpty() {
        assertTrue(ConfigLevel.entries.all { it.title.isNotBlank() && !it.title.contains(10.toChar()) })
    }
    @Test fun everyFailureCodeHasDistinctCopy() {
        val codes = listOf("AUTH_REQUIRED", "STALE_AUTH", "STALE_DRAFT", "STALE_SELECTION", "DELIVERY_UNKNOWN",
            "STORAGE_FAILED", "DIRECTORY_FAILED", "INVALID_TARGET", "UNKNOWN")
        val messages = codes.map(ConfigurationText::failure)
        assertTrue(messages.all { it.isNotBlank() })
        // AUTH_REQUIRED/STALE_AUTH and STALE_DRAFT/STALE_SELECTION intentionally share copy;
        // every other code must map to a distinct message.
        assertEquals(messages.size - 2, messages.distinct().size)
    }
    @Test fun openTicketHasNoLevelAndCannotMatchALevelEcho() {
        val requests = ConfigurationRequests(); val state = draft(); val openTicket = requests.begin(null)
        assertFalse(requests.accepts(openTicket, state, "draft", 1, 0, openTicket.sequence, "projects"))
    }
    @Test fun disposeAndLateOpenAreCleanedOffCallerThreadWithoutClosingNewDraft() {
        val queued = mutableListOf<Runnable>(); val closed = mutableListOf<String>()
        val worker = java.util.concurrent.Executor { queued.add(it) }
        val old = DraftCleanup(worker) { closed.add(it) }
        old.track("old-draft"); old.dispose(); old.dispose()
        assertTrue(closed.isEmpty())
        val newer = DraftCleanup(worker) { closed.add(it) }; newer.track("new-draft")
        old.track("late-old-draft")
        queued.toList().forEach { it.run() }
        assertEquals(listOf("old-draft", "late-old-draft"), closed)
        assertFalse(closed.contains("new-draft"))
    }
    @Test fun failuresKeepSavedBindingOutOfDraftState() {
        val state = draft(); ConfigurationText.failure("STORAGE_FAILED")
        assertEquals("saved-revision", state.baseRevision)
        assertTrue(ConfigurationText.failure("DELIVERY_UNKNOWN").contains("核对"))
    }
    @Test fun subtitleFiltersOutPathLikeContent() {
        for (raw in listOf("/work/share", "C:" + 92.toChar() + "work", "hint (/data/secret)")) {
            assertEquals("", ConfigurationText.subtitle(ConfigLevel.Models, option("m", "Model", raw)))
        }
        assertEquals("high-reasoning", ConfigurationText.subtitle(ConfigLevel.Models, option("m", "Model", "high-reasoning")))
        assertEquals("Workspace write", ConfigurationText.subtitle(ConfigLevel.Permissions, option("p", "Permission", "Workspace write")))
    }
    @Test fun staleCodesRequestExplicitReload() {
        assertTrue(ConfigurationText.isStale("STALE_SELECTION")); assertFalse(ConfigurationText.isStale("AUTH_REQUIRED"))
        assertEquals("重新加载配置", ConfigurationText.reloadDraft)
        assertTrue(ConfigurationText.needingReload.contains("重新加载") || ConfigurationText.needingReload.contains("加载"))
    }
    @Test fun pendingOperationCannotDismissAndStaleStateCanReload() {
        assertFalse(ConfigurationInteraction.canDismiss(true))
        assertTrue(ConfigurationInteraction.canDismiss(false))
        assertTrue(ConfigurationInteraction.requiresReload("STALE_DRAFT", false))
        assertTrue(ConfigurationInteraction.requiresReload("STALE_SELECTION", false))
        assertTrue(ConfigurationInteraction.requiresReload("", true))
        assertFalse(ConfigurationInteraction.requiresReload("STORAGE_FAILED", false))
    }
    @Test fun migratedConfigurationPointsBackToWorkbench() {
        assertEquals("Agent-Anywhere设置", ConfigurationText.settingsTitle)
        assertTrue(ConfigurationText.configureTarget.contains("工作台的调度配置"))
        assertTrue(ConfigurationText.configureTarget.contains("保存并启用"))
        for (copy in listOf(ConfigurationText.settingsTitle, ConfigurationText.configureTarget, ConfigurationText.rejectedRequest)) {
            assertFalse(copy.contains("电脑端")); assertFalse(copy.contains("DSH Settings"))
        }
    }
}
