package com.agentsanywhere.embedded

internal enum class ConfigLevel(val wire: String, val title: String) {
    Connectors("connectors", "电脑设备"), Projects("projects", "项目"),
    Runtimes("runtimes", "DSH运行时"), Sessions("sessions", "Session/会话"),
    Models("models", "模型与推理"), Permissions("permissions", "执行权限")
}
internal data class ConfigOption(val id: String, val label: String, val subtitle: String = "", val available: Boolean)
internal data class ConfigDraft(
    val id: String, val revision: Long, val generation: Long, val baseRevision: String,
    val selected: Map<ConfigLevel, ConfigOption>, val valid: Set<ConfigLevel>,
) {
    val canSave: Boolean get() = id.isNotBlank() && ConfigurationPresentation.homeLevels.all {
        it in valid && !selected[it]?.id.isNullOrBlank() && selected[it]?.available == true
    }
}
internal data class ConfigTicket(val sequence: Long, val draftId: String, val revision: Long, val generation: Long, val level: ConfigLevel?)
internal class ConfigurationRequests {
    private var sequence = 0L
    fun invalidate() { sequence++ }
    fun begin(draft: ConfigDraft?, level: ConfigLevel? = null) = ConfigTicket(++sequence, draft?.id.orEmpty(), draft?.revision ?: -1, draft?.generation ?: -1, level)
    fun owns(ticket: ConfigTicket) = sequence == ticket.sequence
    fun accepts(ticket: ConfigTicket, draft: ConfigDraft?, responseId: String, responseRevision: Long, generation: Long, requestId: Long, level: String): Boolean =
        owns(ticket) && draft?.id == ticket.draftId && draft.revision == ticket.revision &&
            draft.generation == ticket.generation && responseId == ticket.draftId &&
            responseRevision == ticket.revision && generation == ticket.generation && requestId == ticket.sequence && level == ticket.level?.wire
}
internal data class SessionCommitSpec(
    val connectorId: String,
    val projectId: String,
    val runtimeType: String,
    val runtimeId: String,
    val sessionId: String,
    val model: String,
    val permission: String,
    val forceBind: Boolean,
    val expectedAuthGeneration: Long,
    val expectedRevision: String,
    val expectedSessionId: String,
)
internal object ConfigurationText {
    const val settingsTitle = "Agent-Anywhere设置"
    const val homeSubtitle = "三项环境选择 · 保存后启用"
    const val newSessionId = "@new-session"
    const val configureTarget = "更改项目或运行时，请返回工作台的调度配置，选择后保存并启用。"
    const val rejectedRequest = "请求未获确认。请核对工作台调度配置与任务状态。"
    fun isStale(code: String?): Boolean = code == "STALE_DRAFT" || code == "STALE_SELECTION"
    const val reloadDraft = "重新加载配置"
    const val needingReload = "配置已变化或与设备失步，请重新加载。"
    fun subtitle(level: ConfigLevel, option: ConfigOption?): String {
        if (level == ConfigLevel.Projects || option == null || option.subtitle.isBlank()) return ""
        val text = option.subtitle.trim()
        if (text.contains("/") || text.contains(92.toChar()) || Regex("^[A-Za-z]:").containsMatchIn(text)) return ""
        return text
    }
    fun label(level: ConfigLevel, option: ConfigOption?): String {
        if (option == null) return "请选择"
        if (level == ConfigLevel.Sessions && option.id == newSessionId) return "新建会话"
        val label = option.label.trim()
        if (label.isBlank()) return "已选择"
        // Project names occasionally include a workspace in parentheses or a secondary line.
        // Show only the name, and never fall back to an id that may itself be a filesystem path.
        if (level == ConfigLevel.Projects) {
            val name = label.lineSequence().first().substringBefore(" (").substringBefore("（").trim()
            if (name.contains('/') || name.contains(92.toChar()) || Regex("^[A-Za-z]:").containsMatchIn(name)) return "已选择项目"
            return name.ifBlank { "已选择项目" }
        }
        return label
    }
    fun catalogDisplay(level: ConfigLevel, chosenId: String, options: List<ConfigOption>): Pair<String, String> {
        val matched = options.firstOrNull { it.id == chosenId }
        if (matched != null) return label(level, matched) to subtitle(level, matched)
        if (chosenId.isNotBlank()) return "已选择" to ""
        return "请选择" to ""
    }
    fun sessionStatusTitle(selected: String?, sessionTitle: String?, boundSessionId: String): String {
        if (selected == newSessionId) return "当前会话：新建会话（发送时创建）"
        val title = sessionTitle?.takeIf { it.isNotBlank() } ?: "已有会话"
        val status = if (!selected.isNullOrBlank() && selected == boundSessionId) "（已设为当前会话）" else "（待接管）"
        return "当前会话：$title$status"
    }
    fun sessionCommitButtonText(selected: String?): String =
        if (selected == newSessionId) "保存新会话参数" else "接管并设为当前会话"
    fun dispatchFailureMessage(message: String?, errorCode: String?, status: String): String {
        if (status == "unknown_delivery" || errorCode == "DELIVERY_UNKNOWN") {
            return "Delivery is unknown. Keep the draft and query status; do not resend."
        }
        val clean = message?.trim().orEmpty()
        if (clean.isNotBlank()) return clean
        if (!errorCode.isNullOrBlank()) return failure(errorCode)
        return rejectedRequest
    }
    fun failure(code: String): String = when (code) {
        "AUTH_REQUIRED", "STALE_AUTH" -> "账户已变化，请重新打开配置。"
        "STALE_DRAFT", "STALE_SELECTION" -> "配置已变化，请重新打开草稿。"
        "DELIVERY_UNKNOWN" -> "任务送达尚未确认，保留当前配置，请先核对状态。"
        "STORAGE_FAILED" -> "保存未完成，保护仍保留，请重试。"
        "DIRECTORY_FAILED" -> "候选加载失败，已保留当前选择。"
        "INVALID_TARGET" -> "选择依赖尚未完成或目标已失效，请先选择电脑设备、项目和DSH运行时。"
        else -> "操作未完成，已保留当前配置。"
    }
}

internal object ConfigurationInteraction {
    fun parentHint(level: ConfigLevel, draft: ConfigDraft?): String? {
        if (level == ConfigLevel.Connectors) return null
        fun missing(parent: ConfigLevel) = draft?.selected?.get(parent)?.id.isNullOrBlank()
        if (missing(ConfigLevel.Connectors)) return "请先选择电脑设备"
        if (level == ConfigLevel.Sessions && missing(ConfigLevel.Projects)) return "请先选择项目"
        if (level in setOf(ConfigLevel.Sessions, ConfigLevel.Models, ConfigLevel.Permissions) && missing(ConfigLevel.Runtimes)) return "请先选择DSH运行时"
        return null
    }
    fun canList(level: ConfigLevel, draft: ConfigDraft?, busy: Boolean): Boolean =
        draft != null && !busy && parentHint(level, draft) == null
    fun failureOnReopen(failure: String?): String? = if (failure == ConfigurationText.needingReload) failure else null
    fun canDismiss(busy: Boolean): Boolean = !busy
    fun requiresReload(code: String?, malformed: Boolean): Boolean = malformed || ConfigurationText.isStale(code)
    fun resolvedSessionIdForCommit(selected: String?): String =
        if (selected == null || selected == ConfigurationText.newSessionId) "" else selected
    fun canCommitSessionParameters(
        binding: WorkbenchBinding,
        connectorId: String,
        projectId: String,
        runtimeId: String,
        modelId: String,
        permissionId: String,
        selected: String?,
    ): Boolean = binding.authenticated && !binding.unknownDelivery &&
        binding.identity.generation >= 0 && binding.revision.isNotBlank() &&
        !selected.isNullOrBlank() && connectorId.isNotBlank() &&
        projectId.isNotBlank() && runtimeId.isNotBlank() &&
        modelId.startsWith("dsh:model:") && permissionId.startsWith("dsh:permission:")
    fun buildSessionCommitSpec(
        binding: WorkbenchBinding,
        selected: String?,
        connectorId: String,
        projectId: String,
        runtimeId: String,
        modelId: String,
        permissionId: String,
    ): SessionCommitSpec? {
        val cleanConnector = connectorId.ifBlank { binding.connectorId }
        val cleanProject = projectId.ifBlank { binding.projectId }
        val cleanRuntime = runtimeId.ifBlank { binding.runtimeId }
        val cleanModel = modelId.ifBlank { binding.model }
        val cleanPermission = permissionId.ifBlank { binding.permission }
        if (!canCommitSessionParameters(binding, cleanConnector, cleanProject, cleanRuntime, cleanModel, cleanPermission, selected)) {
            return null
        }
        return SessionCommitSpec(
            connectorId = cleanConnector,
            projectId = cleanProject,
            runtimeType = "dsh",
            runtimeId = cleanRuntime,
            sessionId = resolvedSessionIdForCommit(selected),
            model = cleanModel,
            permission = cleanPermission,
            forceBind = true,
            expectedAuthGeneration = binding.identity.generation,
            expectedRevision = binding.revision,
            expectedSessionId = binding.sessionId,
        )
    }
    fun needsCommitBeforeSend(
        binding: WorkbenchBinding,
        selected: String?,
        connectorId: String,
        projectId: String,
        runtimeId: String,
        modelId: String,
        permissionId: String,
    ): Boolean {
        val targetSessionId = resolvedSessionIdForCommit(selected)
        val cleanConnector = connectorId.ifBlank { binding.connectorId }
        val cleanProject = projectId.ifBlank { binding.projectId }
        val cleanRuntime = runtimeId.ifBlank { binding.runtimeId }
        val cleanModel = modelId.ifBlank { binding.model }
        val cleanPermission = permissionId.ifBlank { binding.permission }
        return !binding.ready ||
            binding.connectorId != cleanConnector ||
            binding.projectId != cleanProject ||
            binding.runtimeId != cleanRuntime ||
            binding.sessionId != targetSessionId ||
            binding.model != cleanModel ||
            binding.permission != cleanPermission
    }
    fun canSendInSessionView(
        binding: WorkbenchBinding,
        selected: String?,
        connectorId: String,
        projectId: String,
        runtimeId: String,
        modelId: String,
        permissionId: String,
        draftText: String,
        busy: Boolean,
    ): Boolean = !busy && draftText.isNotBlank() &&
        buildSessionCommitSpec(binding, selected, connectorId, projectId, runtimeId, modelId, permissionId) != null
    fun acceptsCatalogResponse(
        expectedGeneration: Long,
        expectedConnector: String,
        expectedRuntime: String,
        expectedLevel: ConfigLevel,
        expectedRequestId: Long,
        actualGeneration: Long,
        actualConnector: String,
        actualRuntime: String,
        actualLevel: String,
        actualRequestId: Long,
    ): Boolean = expectedGeneration == actualGeneration &&
        expectedConnector == actualConnector &&
        expectedRuntime == actualRuntime &&
        expectedLevel.wire == actualLevel &&
        expectedRequestId == actualRequestId
}
