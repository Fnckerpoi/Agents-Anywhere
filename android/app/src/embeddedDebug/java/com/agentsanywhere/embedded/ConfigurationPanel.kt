package com.agentsanywhere.embedded

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ConfigurationPanel(bridge: HostBridge, identity: AccountIdentity, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val requests = remember { ConfigurationRequests() }
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<ConfigDraft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<ConfigLevel?>(null) }
    var options by remember { mutableStateOf<List<ConfigOption>>(emptyList()) }
    var disposed by remember { mutableStateOf(false) }
    val cleanup = remember { DraftCleanup(java.util.concurrent.ForkJoinPool.commonPool()) { id ->
        runCatching { bridge.call("selection.draft.close", Bundle().apply { putString("draftId", id) }) }
    } }

    suspend fun close(id: String) {
        withContext(Dispatchers.IO) { runCatching { bridge.call("selection.draft.close", Bundle().apply { putString("draftId", id) }) } }
    }
    suspend fun dropLocalDraft(reason: String) {
        val id = draft?.id
        draft = null; options = emptyList(); choosing = null; failure = reason
        if (id != null) close(id)
    }
    fun openDraft() {
        val ticket = requests.begin(null)
        busy = true; failure = null
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    bridge.call("selection.draft.open").also { opened ->
                        opened.configDraft()?.let { cleanup.track(it.id) }
                    }
                }
                val opened = response.configDraft()
                if (disposed || !requests.owns(ticket)) { if (opened != null) close(opened.id); return@launch }
                if (!response.getBoolean("ok")) {
                    val code = response.getString("errorCode").orEmpty()
                    if (ConfigurationText.isStale(code)) dropLocalDraft(ConfigurationText.needingReload) else failure = ConfigurationText.failure(code)
                    return@launch
                }
                if (opened == null || opened.generation != identity.generation) {
                    failure = "配置格式不匹配，请重新加载。"; return@launch
                }
                draft = opened
                for (level in ConfigurationPresentation.homeLevels) {
                    val captured = draft ?: break
                    if (captured.selected[level]?.id.isNullOrBlank() || ConfigurationInteraction.parentHint(level, captured) != null) continue
                    var validated = withContext(Dispatchers.IO) { bridge.call("selection.draft.list", captured.request().apply {
                        putString("level", level.wire); putLong("requestId", ticket.sequence)
                    }) }
                    if (disposed || !requests.owns(ticket)) return@launch
                    if (!validated.getBoolean("ok") && validated.getString("errorCode") == "DIRECTORY_FAILED") {
                        // Transient failure during bootstrap: retry once to avoid degrading to raw IDs
                        validated = withContext(Dispatchers.IO) { bridge.call("selection.draft.list", captured.request().apply {
                            putString("level", level.wire); putLong("requestId", ticket.sequence)
                        }) }
                        if (disposed || !requests.owns(ticket)) return@launch
                    }
                    if (!validated.matchesDraft(ConfigTicket(ticket.sequence, captured.id, captured.revision, captured.generation, level)) ||
                        validated.getLong("requestId", -1) != ticket.sequence || validated.getString("level") != level.wire) {
                        if (ConfigurationText.isStale(validated.getString("errorCode"))) dropLocalDraft(ConfigurationText.needingReload) else failure = "配置验证响应不匹配，请重试。"
                        return@launch
                    }
                    if (!validated.getBoolean("ok")) {
                        val code = validated.getString("errorCode").orEmpty()
                        if (ConfigurationText.isStale(code)) dropLocalDraft(ConfigurationText.needingReload) else failure = ConfigurationText.failure(code)
                        return@launch
                    }
                    val next = validated.configDraft()
                    if (next == null || next.id != captured.id || next.generation != captured.generation || !validated.matchesParents(captured)) {
                        dropLocalDraft(ConfigurationText.needingReload); return@launch
                    }
                    draft = next
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!disposed && requests.owns(ticket)) failure = "配置加载失败，请重试。" }
            finally { if (!disposed && requests.owns(ticket)) busy = false }
        }
    }
    fun recover() {
        if (busy || disposed) return
        val pending = draft?.id
        requests.invalidate()
        draft = null; options = emptyList(); choosing = null; failure = null
        if (pending != null) {
            busy = true
            scope.launch {
                try { close(pending) } finally { if (!disposed) { busy = false; openDraft() } }
            }
        } else openDraft()
    }
    fun list(level: ConfigLevel) {
        val captured = draft ?: return
        if (!ConfigurationInteraction.canList(level, captured, busy)) return
        val ticket = requests.begin(captured, level)
        choosing = level; options = emptyList(); failure = null; busy = true
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { bridge.call("selection.draft.list", captured.request().apply {
                    putString("level", level.wire); putLong("requestId", ticket.sequence)
                }) }
                if (disposed || !requests.owns(ticket)) return@launch
                val echoOk = requests.accepts(ticket, draft, response.getString("draftId").orEmpty(),
                    response.getLong("requestDraftRevision", -1), response.getLong("authGeneration", -1),
                    response.getLong("requestId", -1), response.getString("level").orEmpty())
                if (!echoOk) { if (ConfigurationText.isStale(response.getString("errorCode"))) dropLocalDraft(ConfigurationText.needingReload); return@launch }
                if (!response.getBoolean("ok")) {
                    val code = response.getString("errorCode").orEmpty()
                    if (ConfigurationText.isStale(code)) dropLocalDraft(ConfigurationText.needingReload) else failure = ConfigurationText.failure(code)
                    return@launch
                }
                if (!response.matchesParents(captured)) { dropLocalDraft(ConfigurationText.needingReload); return@launch }
                @Suppress("DEPRECATION")
                val rows = response.getParcelableArrayList<Bundle>("options")
                if (rows == null) { dropLocalDraft(ConfigurationText.needingReload); return@launch }
                val parsed = rows.mapNotNull { it.configOption() }
                if (parsed.size != rows.size || parsed.map { it.id }.distinct().size != parsed.size) {
                    dropLocalDraft(ConfigurationText.needingReload); return@launch
                }
                val validated = response.configDraft()
                if (validated == null || validated.id != captured.id || validated.generation != captured.generation) {
                    dropLocalDraft(ConfigurationText.needingReload); return@launch
                }
                draft = validated
                options = parsed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!disposed && requests.owns(ticket)) failure = "候选加载失败，已保留当前选择。" }
            finally { if (!disposed && requests.owns(ticket)) busy = false }
        }
    }
    fun mutate(operation: String, level: ConfigLevel? = null, option: ConfigOption? = null) {
        val captured = draft ?: return
        val ticket = requests.begin(captured, level)
        busy = true; failure = null
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { bridge.call(operation, captured.request().apply {
                    if (level != null) putString("level", level.wire)
                    if (option != null) putString("id", option.id)
                }) }
                if (disposed || !requests.owns(ticket)) return@launch
                if (draft?.revision != ticket.revision || draft?.id != ticket.draftId) { dropLocalDraft(ConfigurationText.needingReload); return@launch }
                if (!response.matchesDraft(ticket)) { dropLocalDraft(ConfigurationText.needingReload); return@launch }
                if (!response.getBoolean("ok")) {
                    val code = response.getString("errorCode").orEmpty()
                    if (ConfigurationText.isStale(code)) dropLocalDraft(ConfigurationText.needingReload) else failure = ConfigurationText.failure(code)
                    return@launch
                }
                val next = response.configDraft()
                if (operation == "selection.draft.save") {
                    val saved = response.getBundle("saved")
                    if (saved == null || saved.getLong("authGeneration", -1) != identity.generation) {
                        failure = "保存响应不匹配，请刷新后核对。"; return@launch
                    }
                    val fresh = withContext(Dispatchers.IO) { bridge.call("auth.snapshot") }
                    if (disposed || !requests.owns(ticket)) return@launch
                    if (fresh.getLong("authGeneration", -1) != identity.generation ||
                        fresh.getString("userId") != identity.userId || fresh.getString("origin") != identity.origin ||
                        fresh.getString("revision") != saved.getString("revision")) {
                        failure = "账户或绑定已变化，请刷新后核对。"; return@launch
                    }
                    draft = next?.takeIf { it.id == captured.id && it.generation == captured.generation }
                    choosing = null; onSaved()
                } else {
                    if (next == null || next.generation != captured.generation || next.id != captured.id) {
                        dropLocalDraft(ConfigurationText.needingReload); return@launch
                    }
                    draft = next; choosing = null; options = emptyList()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!disposed && requests.owns(ticket)) failure = "操作未完成，已保留当前配置。" }
            finally { if (!disposed && requests.owns(ticket)) busy = false }
        }
    }
    DisposableEffect(identity) {
        onDispose {
            disposed = true; requests.invalidate()
            cleanup.dispose()
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = EditorialSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = {
                expanded = !expanded
                if (expanded) {
                    val stale = failure == ConfigurationText.needingReload
                    failure = ConfigurationInteraction.failureOnReopen(failure)
                    if (stale) recover() else if (draft == null) openDraft()
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Column(Modifier.weight(1f)) {
                    Text("调度配置", style = MaterialTheme.typography.titleMedium, color = EditorialInk)
                    Text(ConfigurationText.homeSubtitle, style = MaterialTheme.typography.bodySmall, color = EditorialMuted)
                }
                Text(if (expanded) "收起" else "展开", color = EditorialLime)
            }
            if (expanded) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = EditorialLime)
                for (level in ConfigurationPresentation.visibleLevels(expanded)) {
                    val selected = draft?.selected?.get(level)
                    val parentHint = ConfigurationInteraction.parentHint(level, draft)
                    OutlinedCard(onClick = { list(level) }, enabled = ConfigurationInteraction.canList(level, draft, busy),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(level.title, color = EditorialMuted, style = MaterialTheme.typography.labelMedium)
                                Text(parentHint ?: ConfigurationText.label(level, selected), color = EditorialInk,
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                val subtitle = ConfigurationText.subtitle(level, selected)
                                if (subtitle.isNotBlank()) Text(subtitle, color = EditorialMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("选择", color = EditorialLime)
                        }
                    }
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = !busy, onClick = { recover() }) { Text("刷新配置") }
                if (failure == ConfigurationText.needingReload) {
                    TextButton(enabled = !busy, onClick = { recover() }) { Text(ConfigurationText.reloadDraft, color = EditorialLime) }
                }
                if (draft == null && failure != ConfigurationText.needingReload) TextButton(enabled = !busy, onClick = { openDraft() }) { Text("重试加载配置") }
                Button(onClick = { mutate("selection.draft.save") }, enabled = !busy && draft?.canSave == true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("保存并启用") }
                Text("选择只修改草稿，保存成功后才影响任务目标。", color = EditorialMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    choosing?.let { level ->
        AlertDialog(onDismissRequest = {
            if (ConfigurationInteraction.canDismiss(busy)) { requests.invalidate(); choosing = null; options = emptyList() }
        },
            title = { Text("选择" + level.title) }, text = {
                Column {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (failure == ConfigurationText.needingReload) {
                        TextButton(onClick = { recover() }) { Text(ConfigurationText.reloadDraft, color = EditorialLime) }
                    }
                    if (!busy && failure == null && options.isEmpty()) Text("暂无可用候选，已保留当前选择。")
                    if (!busy && failure == null) LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(options, key = { it.id }) { option ->
                            TextButton(onClick = { mutate("selection.draft.choose", level, option) }, enabled = option.available,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(ConfigurationText.label(level, option))
                                    val subtitle = ConfigurationText.subtitle(level, option)
                                    if (subtitle.isNotBlank()) Text(subtitle, color = EditorialMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                if (!option.available) Text("不可用")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                if (ConfigurationInteraction.canDismiss(busy)) { requests.invalidate(); choosing = null; options = emptyList() }
            }) { Text("关闭") } }
        )
    }
}
