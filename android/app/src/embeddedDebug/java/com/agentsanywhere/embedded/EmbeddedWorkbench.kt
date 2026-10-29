package com.agentsanywhere.embedded

import android.app.Activity
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentsanywhere.app.api.ApiClient
import com.agentsanywhere.app.api.SessionsApi
import com.agentsanywhere.app.feature.auth.AuthSessionReader
import com.agentsanywhere.app.feature.sessiondetail.SessionDetailController
import com.agentsanywhere.app.feature.sessions.toAgentSession
import com.agentsanywhere.app.model.AgentSession
import com.agentsanywhere.app.ui.screens.home.HomeProjectSessionRow
import com.agentsanywhere.app.ui.screens.sessiondetail.MessageList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun EmbeddedWorkbench(host: Activity, bridge: HostBridge, owner: PayloadOwner) {
    val scope = rememberCoroutineScope()
    val epoch = remember { RequestEpoch() }
    val catalogEpoch = remember { RequestEpoch() }
    var snapshot by remember { mutableStateOf(Bundle()) }
    var sessions by remember { mutableStateOf<List<AgentSession>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<com.agentsanywhere.app.feature.sessiondetail.SessionDetailState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var catalogBusy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var chosenModelId by remember { mutableStateOf("") }
    var chosenPermissionId by remember { mutableStateOf("") }
    var modelOptions by remember { mutableStateOf<List<ConfigOption>>(emptyList()) }
    var permissionOptions by remember { mutableStateOf<List<ConfigOption>>(emptyList()) }
    var catalogPickerLevel by remember { mutableStateOf<ConfigLevel?>(null) }
    val api = remember { SessionsApi(ApiClient()) }
    val binding = snapshot.binding()
    val controller = remember(api) { SessionDetailController(api, Reader { snapshot }) }

    fun applySnapshot(value: Bundle) {
        val next = value.binding()
        if (next.identity != snapshot.binding().identity) {
            sessions = emptyList(); selected = null; detail = null
            draft = ""; statusText = ""; answer = ""
            chosenModelId = ""; chosenPermissionId = ""
            modelOptions = emptyList(); permissionOptions = emptyList()
            catalogPickerLevel = null
        }
        snapshot = value
        if (chosenModelId.isBlank() && next.model.isNotBlank()) chosenModelId = next.model
        if (chosenPermissionId.isBlank() && next.permission.isNotBlank()) chosenPermissionId = next.permission
    }
    fun reload() {
        val ticket = epoch.begin()
        busy = true; problem = null
        scope.launch {
            try {
                val auth = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                if (!epoch.owns(ticket)) return@launch
                applySnapshot(auth)
                if (!auth.getBoolean("authenticated")) { problem = "请在Agent-Anywhere设置中登录后查看会话。"; return@launch }
                val page = withContext(Dispatchers.IO) {
                    api.listSessions(auth.text("origin", "serverOrigin"), auth.text("token", "accessToken"), false)
                }
                val fresh = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                if (!epoch.owns(ticket)) return@launch
                if (fresh.binding().identity != auth.binding().identity) { applySnapshot(fresh); return@launch }
                applySnapshot(fresh)
                sessions = page.sessions.map { it.toAgentSession(emptyMap()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.owns(ticket)) problem = "Could not load sessions. Refresh to try again." }
            finally { if (epoch.owns(ticket)) busy = false }
        }
    }
    fun enterNewSession() {
        epoch.begin()
        selected = ConfigurationText.newSessionId
        detail = null; problem = null; answer = ""; statusText = ""; busy = false
        chosenModelId = binding.model
        chosenPermissionId = binding.permission
    }
    fun select(id: String) {
        if (id == ConfigurationText.newSessionId) { enterNewSession(); return }
        val ticket = epoch.begin()
        val auth = Bundle(snapshot)
        selected = id; detail = null; problem = null; answer = ""; statusText = ""; busy = true
        chosenModelId = binding.model
        chosenPermissionId = binding.permission
        scope.launch {
            try {
                val loaded = controller.loadInitialSnapshot(id, emptyList()).getOrThrow()
                val fresh = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                if (!epoch.owns(ticket) || selected != id) return@launch
                if (fresh.binding().identity != auth.binding().identity) { applySnapshot(fresh); return@launch }
                applySnapshot(fresh); detail = loaded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.owns(ticket) && selected == id) problem = "Could not load session history." }
            finally { if (epoch.owns(ticket)) busy = false }
        }
    }

    val isNewSession = selected == ConfigurationText.newSessionId
    val current = if (isNewSession) null else (detail?.session ?: sessions.firstOrNull { it.id == selected })
    val targetConnectorId = current?.connectorId?.takeIf { it.isNotBlank() } ?: binding.connectorId
    val targetProjectId = current?.projectId?.takeIf { it.isNotBlank() } ?: binding.projectId
    val targetRuntimeId = current?.runtimeId?.takeIf { it.isNotBlank() } ?: binding.runtimeId
    val effectiveModelId = chosenModelId.ifBlank { binding.model }
    val effectivePermissionId = chosenPermissionId.ifBlank { binding.permission }

    suspend fun fetchCatalogLevel(level: ConfigLevel, cId: String, rId: String, gen: Long): Boolean {
        val reqId = catalogEpoch.begin()
        val response = withContext(Dispatchers.IO) {
            bridge.call("selection.catalog", Bundle().apply {
                putString("connectorId", cId)
                putString("runtimeId", rId)
                putString("level", level.wire)
                putLong("expectedAuthGeneration", gen)
                putLong("requestId", reqId)
            })
        }
        if (!catalogEpoch.owns(reqId)) return false
        if (!response.getBoolean("ok")) {
            if (response.getLong("requestId", -1L) == reqId && response.getLong("authGeneration", -1L) == gen) {
                problem = ConfigurationText.failure(response.getString("errorCode").orEmpty())
            }
            return false
        }
        val echoOk = ConfigurationInteraction.acceptsCatalogResponse(
            gen, cId, rId, level, reqId,
            response.getLong("authGeneration", -1L),
            response.getString("connectorId").orEmpty(),
            response.getString("runtimeId").orEmpty(),
            response.getString("level").orEmpty(),
            response.getLong("requestId", -1L),
        )
        if (!echoOk) return false
        @Suppress("DEPRECATION")
        val rows = response.getParcelableArrayList<Bundle>("options") ?: return false
        val parsed = rows.mapNotNull { it.configOption() }
        if (parsed.size != rows.size) return false
        // The host owns defaulting and validation. AA only mirrors the authoritative current
        // selection; it never invents a model or permission id from the candidate list.
        if (level == ConfigLevel.Models) modelOptions = parsed
        else if (level == ConfigLevel.Permissions) permissionOptions = parsed
        return true
    }

    fun openCatalogPicker(level: ConfigLevel) {
        if (targetConnectorId.isBlank() || targetRuntimeId.isBlank()) {
            problem = ConfigurationText.configureTarget
            return
        }
        catalogPickerLevel = level
        val existing = if (level == ConfigLevel.Models) modelOptions else permissionOptions
        if (existing.isEmpty()) {
            val cId = targetConnectorId; val rId = targetRuntimeId; val gen = binding.identity.generation
            catalogBusy = true
            scope.launch {
                try { fetchCatalogLevel(level, cId, rId, gen) }
                finally { catalogBusy = false }
            }
        }
    }

    LaunchedEffect(selected, targetConnectorId, targetRuntimeId, binding.identity) {
        if (selected != null && binding.authenticated && targetConnectorId.isNotBlank() && targetRuntimeId.isNotBlank()) {
            val cId = targetConnectorId; val rId = targetRuntimeId; val gen = binding.identity.generation
            catalogBusy = true
            try {
                fetchCatalogLevel(ConfigLevel.Models, cId, rId, gen)
                fetchCatalogLevel(ConfigLevel.Permissions, cId, rId, gen)
            } finally {
                catalogBusy = false
            }
        }
    }

    fun hostCall(operation: String, request: Bundle = Bundle()) {
        val ticket = epoch.begin()
        val captured = snapshot.binding()
        val selectedAtStart = selected
        val submitted = draft
        busy = true; problem = null
        scope.launch {
            try {
                val args = Bundle(request).apply {
                    putString("expectedRevision", captured.revision)
                    putLong("expectedAuthGeneration", captured.identity.generation)
                    putString("expectedSessionId", captured.sessionId)
                }
                val result = withContext(Dispatchers.IO) { bridge.call(operation, args) }
                val fresh = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                if (!epoch.owns(ticket) || selectedAtStart != selected) return@launch
                if (operation == "selection.open" || operation == "auth.login" || operation == "auth.logout") {
                    applySnapshot(fresh)
                    statusText = "关闭Agent-Anywhere设置后刷新账户与任务状态。"
                    return@launch
                }
                val status = result.getString("status").orEmpty()
                val ok = result.getBoolean("ok")
                if (!ok) {
                    applySnapshot(fresh)
                    statusText = WorkbenchPolicy.statusLabel(status)
                    problem = ConfigurationText.dispatchFailureMessage(result.getString("message"), result.getString("errorCode"), status)
                    return@launch
                }
                val valid = WorkbenchPolicy.acceptsResult(captured,
                    result.getString("requestRevision"), result.getLong("requestAuthGeneration", -1),
                    result.getString("requestSessionId"), result.getLong("authGeneration", -1),
                    result.getString("sessionId"), result.getString("revision"), fresh.binding())
                if (!valid) {
                    applySnapshot(fresh)
                    problem = "The account or target changed. Refresh and check the current binding."
                    return@launch
                }
                applySnapshot(fresh)
                statusText = WorkbenchPolicy.statusLabel(status)
                if (operation == "dsh.status" && ok) {
                    val body = runCatching { JSONObject(result.getString("json").orEmpty()) }.getOrNull()
                    answer = body?.optString("answer").orEmpty()
                }
                if (operation == "dsh.dispatch" && WorkbenchPolicy.clearDraft(ok, status, submitted, draft)) draft = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.owns(ticket)) problem = "The host could not confirm the request. Keep the draft and query status." }
            finally { if (epoch.owns(ticket)) busy = false }
        }
    }

    fun commitSessionParameters() {
        val captured = snapshot.binding()
        val selectedAtStart = selected
        val spec = ConfigurationInteraction.buildSessionCommitSpec(
            captured, selectedAtStart, targetConnectorId, targetProjectId, targetRuntimeId, effectiveModelId, effectivePermissionId,
        ) ?: run {
            problem = ConfigurationText.configureTarget
            return
        }
        val ticket = epoch.begin()
        busy = true; problem = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { bridge.call("selection.commit", spec.toBundle()) }
                val fresh = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                if (!epoch.owns(ticket) || selectedAtStart != selected) return@launch
                val status = result.getString("status").orEmpty()
                val ok = result.getBoolean("ok")
                if (!ok) {
                    applySnapshot(fresh)
                    problem = ConfigurationText.dispatchFailureMessage(result.getString("message"), result.getString("errorCode"), status)
                    return@launch
                }
                val valid = WorkbenchPolicy.acceptsResult(captured,
                    result.getString("requestRevision"), result.getLong("requestAuthGeneration", -1),
                    result.getString("requestSessionId"), result.getLong("authGeneration", -1),
                    result.getString("sessionId"), result.getString("revision"), fresh.binding())
                if (!valid) {
                    applySnapshot(fresh)
                    problem = "账户或绑定已变化，请刷新后核对。"
                    return@launch
                }
                applySnapshot(fresh)
                chosenModelId = fresh.binding().model
                chosenPermissionId = fresh.binding().permission
                statusText = if (selectedAtStart == ConfigurationText.newSessionId) "新会话参数已保存。" else "已接管并设为当前会话。"
                reload()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.owns(ticket)) problem = ConfigurationText.rejectedRequest }
            finally { if (epoch.owns(ticket)) busy = false }
        }
    }

    fun sendInSessionView() {
        val initialBinding = snapshot.binding()
        val selectedAtStart = selected
        val submitted = draft
        val spec = ConfigurationInteraction.buildSessionCommitSpec(
            initialBinding, selectedAtStart, targetConnectorId, targetProjectId, targetRuntimeId, effectiveModelId, effectivePermissionId,
        ) ?: run {
            problem = ConfigurationText.configureTarget
            return
        }
        val needsCommit = ConfigurationInteraction.needsCommitBeforeSend(
            initialBinding, selectedAtStart, targetConnectorId, targetProjectId, targetRuntimeId, effectiveModelId, effectivePermissionId,
        )
        val ticket = epoch.begin()
        busy = true; problem = null
        scope.launch {
            try {
                var activeBinding = initialBinding
                if (needsCommit) {
                    val commitRes = withContext(Dispatchers.IO) { bridge.call("selection.commit", spec.toBundle()) }
                    val afterCommit = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                    if (!epoch.owns(ticket) || selected != selectedAtStart) return@launch
                    val commitStatus = commitRes.getString("status").orEmpty()
                    if (!commitRes.getBoolean("ok")) {
                        applySnapshot(afterCommit)
                        problem = ConfigurationText.dispatchFailureMessage(commitRes.getString("message"), commitRes.getString("errorCode"), commitStatus)
                        return@launch
                    }
                    val commitValid = WorkbenchPolicy.acceptsResult(initialBinding,
                        commitRes.getString("requestRevision"), commitRes.getLong("requestAuthGeneration", -1),
                        commitRes.getString("requestSessionId"), commitRes.getLong("authGeneration", -1),
                        commitRes.getString("sessionId"), commitRes.getString("revision"), afterCommit.binding())
                    if (!commitValid) {
                        applySnapshot(afterCommit)
                        problem = "账户或绑定已变化，请刷新后核对。"
                        return@launch
                    }
                    applySnapshot(afterCommit)
                    activeBinding = afterCommit.binding()
                    chosenModelId = activeBinding.model
                    chosenPermissionId = activeBinding.permission
                }
                val isNew = selectedAtStart == ConfigurationText.newSessionId
                val dispatchArgs = Bundle().apply {
                    putString("task", submitted)
                    if (isNew) putBoolean("newSession", true)
                    putString("expectedRevision", activeBinding.revision)
                    putLong("expectedAuthGeneration", activeBinding.identity.generation)
                    putString("expectedSessionId", if (isNew) "" else activeBinding.sessionId)
                }
                val result = withContext(Dispatchers.IO) { bridge.call("dsh.dispatch", dispatchArgs) }
                val fresh = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                if (!epoch.owns(ticket) || selected != selectedAtStart) return@launch
                val status = result.getString("status").orEmpty()
                val ok = result.getBoolean("ok")
                if (!ok) {
                    applySnapshot(fresh)
                    statusText = WorkbenchPolicy.statusLabel(status)
                    problem = ConfigurationText.dispatchFailureMessage(result.getString("message"), result.getString("errorCode"), status)
                    return@launch
                }
                val valid = WorkbenchPolicy.acceptsResult(activeBinding,
                    result.getString("requestRevision"), result.getLong("requestAuthGeneration", -1),
                    result.getString("requestSessionId"), result.getLong("authGeneration", -1),
                    result.getString("sessionId"), result.getString("revision"), fresh.binding())
                if (!valid) {
                    applySnapshot(fresh)
                    problem = "The account or target changed. Refresh and check the current binding."
                    return@launch
                }
                applySnapshot(fresh)
                statusText = WorkbenchPolicy.statusLabel(status)
                if (WorkbenchPolicy.clearDraft(ok, status, submitted, draft)) draft = ""
                val createdSessionId = result.getString("sessionId")?.takeIf { it.isNotBlank() } ?: fresh.binding().sessionId.takeIf { it.isNotBlank() }
                if (isNew && createdSessionId != null) {
                    select(createdSessionId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch.owns(ticket)) problem = "The host could not confirm the request. Keep the draft and query status." }
            finally { if (epoch.owns(ticket)) busy = false }
        }
    }

    LaunchedEffect(owner.revision) { reload() }
    val canAct = binding.canAct(selected)
    val commitSpec = ConfigurationInteraction.buildSessionCommitSpec(
        binding, selected, targetConnectorId, targetProjectId, targetRuntimeId, effectiveModelId, effectivePermissionId,
    )
    val canSend = ConfigurationInteraction.canSendInSessionView(
        binding, selected, targetConnectorId, targetProjectId, targetRuntimeId, effectiveModelId, effectivePermissionId, draft, busy,
    )
    val modelDisplay = ConfigurationText.catalogDisplay(ConfigLevel.Models, effectiveModelId, modelOptions)
    val permDisplay = ConfigurationText.catalogDisplay(ConfigLevel.Permissions, effectivePermissionId, permissionOptions)

    Column(Modifier.fillMaxSize().background(EditorialBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (selected == null) "Sessions" else if (isNewSession) "新建会话" else (current?.title ?: "Session"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (selected == null) reload() else { epoch.begin(); selected = null; detail = null; catalogPickerLevel = null; busy = false }
            }) { Text(if (selected == null) "Refresh" else "Back") }
        }
        Row {
            TextButton(enabled = !busy, onClick = { hostCall("selection.open") }) { Text(ConfigurationText.settingsTitle) }
            TextButton(enabled = !busy, onClick = { reload() }) { Text("Refresh binding") }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy || catalogBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = EditorialLime)
        if (selected == null) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { key(binding.identity) { ConfigurationPanel(bridge, binding.identity, onSaved = { reload() }) } }
                item {
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = EditorialSurface), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("开始新任务 / 新建会话", style = MaterialTheme.typography.titleMedium, color = EditorialInk)
                            Text("进入会话界面确定模型与推理档位、执行权限并发送新任务", style = MaterialTheme.typography.bodySmall, color = EditorialMuted)
                            Button(
                                enabled = !busy,
                                onClick = { enterNewSession() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                            ) { Text("开始新任务 / 新建会话") }
                        }
                    }
                }
                item { Text("会话", style = MaterialTheme.typography.titleLarge) }
                items(sessions, key = { it.id }) { session ->
                    HomeProjectSessionRow(session, inset = false, onClick = { select(session.id) }, onLongPress = {})
                }
            }
        } else {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = EditorialSurface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            ConfigurationText.sessionStatusTitle(selected, current?.title, binding.sessionId),
                            color = EditorialInk,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!isNewSession) {
                            TextButton(enabled = !busy, onClick = { enterNewSession() }) {
                                Text("切换为新建会话", color = EditorialLime)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedCard(
                            onClick = { openCatalogPicker(ConfigLevel.Models) },
                            enabled = !busy && targetConnectorId.isNotBlank() && targetRuntimeId.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(ConfigLevel.Models.title, color = EditorialMuted, style = MaterialTheme.typography.labelMedium)
                                Text(modelDisplay.first, color = EditorialInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (modelDisplay.second.isNotBlank()) {
                                    Text(modelDisplay.second, color = EditorialMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        OutlinedCard(
                            onClick = { openCatalogPicker(ConfigLevel.Permissions) },
                            enabled = !busy && targetConnectorId.isNotBlank() && targetRuntimeId.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(ConfigLevel.Permissions.title, color = EditorialMuted, style = MaterialTheme.typography.labelMedium)
                                Text(permDisplay.first, color = EditorialInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (permDisplay.second.isNotBlank()) {
                                    Text(permDisplay.second, color = EditorialMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Button(
                        enabled = !busy && commitSpec != null,
                        onClick = { commitSessionParameters() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text(ConfigurationText.sessionCommitButtonText(selected)) }
                }
            }
            val state = detail
            if (!isNewSession && state != null) Box(Modifier.weight(1f)) {
                MessageList(state.messages, true, selected!!, state.session?.cwd, controller, 0, 0, null,
                    hasMore = false, loadingOlder = false, onLoadOlder = {},
                    onPreviewAttachment = {}, onOpenAttachment = {}, onCopyMessage = {}, onShareReply = {}, onOpenFile = {})
            } else if (isNewSession) {
                Spacer(Modifier.weight(1f))
            }
            if (commitSpec == null) Text(ConfigurationText.configureTarget, color = EditorialMuted)
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.fillMaxWidth(),
                label = { Text(if (isNewSession) "输入新会话任务内容" else "Continue this session") },
                maxLines = 4,
            )
            Row {
                TextButton(enabled = canSend, onClick = { sendInSessionView() }) { Text("Send") }
                if (!isNewSession) {
                    TextButton(enabled = !busy && canAct, onClick = { hostCall("dsh.status") }) { Text("Progress / result") }
                    TextButton(enabled = !busy && canAct, onClick = { hostCall("dsh.end_session") }) { Text("End local tracking") }
                }
            }
        }
        if (statusText.isNotBlank()) Text(statusText)
        if (answer.isNotBlank()) Text(answer)
    }

    catalogPickerLevel?.let { level ->
        val itemsList = if (level == ConfigLevel.Models) modelOptions else permissionOptions
        AlertDialog(
            onDismissRequest = { if (!catalogBusy) catalogPickerLevel = null },
            title = { Text("选择" + level.title) },
            text = {
                Column {
                    if (catalogBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = EditorialLime)
                    if (!catalogBusy && itemsList.isEmpty()) Text("暂无可用候选，请核对工作台三项环境配置。")
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(itemsList, key = { it.id }) { option ->
                            TextButton(
                                onClick = {
                                    if (level == ConfigLevel.Models) chosenModelId = option.id
                                    else if (level == ConfigLevel.Permissions) chosenPermissionId = option.id
                                    catalogPickerLevel = null
                                },
                                enabled = option.available && !catalogBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(ConfigurationText.label(level, option))
                                    val sub = ConfigurationText.subtitle(level, option)
                                    if (sub.isNotBlank()) Text(sub, color = EditorialMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                if (!option.available) Text("不可用")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !catalogBusy, onClick = { catalogPickerLevel = null }) { Text("关闭") }
            },
        )
    }
}

private class Reader(private val source: () -> Bundle) : AuthSessionReader {
    override fun readServerUrl(): String = source().text("origin", "serverOrigin")
    override fun readAccessToken(): String = source().text("token", "accessToken")
}
private fun SessionCommitSpec.toBundle() = Bundle().apply {
    putString("connectorId", connectorId)
    putString("projectId", projectId)
    putString("runtimeType", runtimeType)
    putString("runtimeId", runtimeId)
    putString("sessionId", sessionId)
    putString("model", model)
    putString("permission", permission)
    putBoolean("forceBind", forceBind)
    putLong("expectedAuthGeneration", expectedAuthGeneration)
    putString("expectedRevision", expectedRevision)
    putString("expectedSessionId", expectedSessionId)
}
private fun Bundle.text(vararg keys: String): String = keys.firstNotNullOfOrNull { getString(it)?.takeIf(String::isNotBlank) }.orEmpty()
private fun Bundle.binding() = WorkbenchBinding(
    AccountIdentity(text("origin", "serverOrigin"), text("userId"), getLong("authGeneration", -1)),
    text("revision"), getBoolean("authenticated"), getBoolean("enabled"), getBoolean("unknownDelivery"),
    text("connectorId"), text("projectId"), text("runtimeType"), text("runtimeId"), text("model"), text("permission"), text("sessionId"))
