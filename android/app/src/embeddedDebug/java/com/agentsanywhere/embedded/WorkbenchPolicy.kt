package com.agentsanywhere.embedded

internal data class AccountIdentity(val origin: String, val userId: String, val generation: Long)
internal data class WorkbenchBinding(
    val identity: AccountIdentity, val revision: String, val authenticated: Boolean,
    val enabled: Boolean, val unknownDelivery: Boolean, val connectorId: String,
    val projectId: String, val runtimeType: String, val runtimeId: String,
    val model: String, val permission: String, val sessionId: String,
) {
    val ready: Boolean get() = authenticated && enabled && identity.generation >= 0 &&
        revision.isNotBlank() && connectorId.isNotBlank() && projectId.isNotBlank() &&
        runtimeType == "dsh" && runtimeId.isNotBlank() &&
        model.startsWith("dsh:model:") && permission.startsWith("dsh:permission:")
    val bound: Boolean get() = ready && sessionId.isNotBlank()
    fun canCreate(): Boolean = ready && sessionId.isBlank() && !unknownDelivery
    fun canAct(selected: String?): Boolean = bound && !selected.isNullOrBlank() && selected == sessionId
    fun canBind(connector: String, project: String?, runtime: String, type: String): Boolean =
        ready && !unknownDelivery && connector == connectorId && project == projectId &&
            runtime == runtimeId && type == runtimeType
}
internal class RequestEpoch {
    private var sequence = 0L
    fun begin(): Long = ++sequence
    fun owns(ticket: Long): Boolean = ticket == sequence
}
internal object WorkbenchPolicy {
    /**
     * The request must echo the snapshot the request was built from. A successful operation may
     * legitimately move the host revision forward, so the result revision is compared with the
     * fresh snapshot instead of with the request revision.
     */
    fun acceptsResult(request: WorkbenchBinding, echoRevision: String?, echoGeneration: Long,
                      echoSessionId: String?, resultGeneration: Long, resultSessionId: String?, resultRevision: String?,
                      fresh: WorkbenchBinding): Boolean =
        echoRevision == request.revision && echoGeneration == request.identity.generation &&
            echoSessionId == request.sessionId && resultGeneration == request.identity.generation &&
            resultSessionId == fresh.sessionId && fresh.identity == request.identity &&
            fresh.revision.isNotBlank() && resultRevision == fresh.revision
    fun accepted(status: String): Boolean = status == "submitted"
    fun completed(status: String): Boolean = status == "completed"
    fun unknownDelivery(status: String): Boolean = status == "unknown_delivery"
    fun clearDraft(ok: Boolean, status: String, submitted: String, current: String): Boolean =
        ok && accepted(status) && submitted == current
    fun statusLabel(status: String): String = when (status) {
        "submitted" -> "Message submitted. Query progress to see the result."
        "running" -> "Running"
        "completed" -> "Completed"
        "waiting_approval" -> "Waiting for approval"
        "idle_no_reply" -> "No reply for this message yet"
        "ended" -> "Local tracking ended. The remote task was not cancelled."
        "session_selected" -> "Voice target saved."
        "ok" -> "Done."
        "unknown_delivery" -> "Delivery is unknown. Keep the draft and query status; do not resend."
        else -> "The host could not confirm this request."
    }
}
