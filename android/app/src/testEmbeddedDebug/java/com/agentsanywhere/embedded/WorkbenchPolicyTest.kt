package com.agentsanywhere.embedded

import org.junit.Assert.*
import org.junit.Test

class WorkbenchPolicyTest {
    private fun binding(generation: Long = 0, revision: String = "before", session: String = "A") = WorkbenchBinding(
        AccountIdentity("https://example.invalid", "user", generation), revision, true, true, false,
        "connector", "project", "dsh", "runtime", "dsh:model:exact", "dsh:permission:exact", session)

    @Test fun restartAtGenerationZeroIsReady() { assertTrue(binding().canAct("A")) }
    @Test fun browsingBDoesNotSendToA() { assertFalse(binding().canAct("B")); assertFalse(binding(session = "").canAct("A")) }
    @Test fun bindingUsesExactParentsAndCatalogIds() {
        assertTrue(binding().canBind("connector", "project", "runtime", "dsh"))
        assertFalse(binding().canBind("other", "project", "runtime", "dsh"))
        assertFalse(binding().canBind("connector", "other", "runtime", "dsh"))
        assertFalse(binding().canBind("connector", "project", "other", "dsh"))
        assertFalse(binding().copy(model = "label").ready)
        assertFalse(binding().copy(permission = "label").ready)
        assertFalse(binding().copy(unknownDelivery = true).canBind("connector", "project", "runtime", "dsh"))
    }
    @Test fun legitimateDispatchRevisionChangeIsAccepted() {
        assertTrue(WorkbenchPolicy.acceptsResult(binding(), "before", 0, "A", 0, "A", "after", binding(revision = "after")))
    }
    @Test fun externalAccountOrSessionChangeIsRejected() {
        assertFalse(WorkbenchPolicy.acceptsResult(binding(), "before", 0, "A", 1, "A", "before", binding(generation = 1)))
        assertFalse(WorkbenchPolicy.acceptsResult(binding(), "before", 0, "A", 0, "B", "before", binding()))
        assertFalse(WorkbenchPolicy.acceptsResult(binding(), "wrong", 0, "A", 0, "A", "before", binding()))
        assertFalse(WorkbenchPolicy.acceptsResult(binding(), "before", 0, "B", 0, "A", "before", binding()))
    }
    @Test fun newerRequestOwnsSuccessErrorAndFinally() {
        val epoch = RequestEpoch(); val a = epoch.begin(); val b = epoch.begin()
        assertFalse(epoch.owns(a)); assertTrue(epoch.owns(b))
        epoch.begin(); assertFalse(epoch.owns(b))
    }
    @Test fun draftIsPreservedUntilExplicitSubmission() {
        assertTrue(WorkbenchPolicy.clearDraft(true, "submitted", "draft", "draft"))
        assertFalse(WorkbenchPolicy.clearDraft(false, "submitted", "draft", "draft"))
        assertFalse(WorkbenchPolicy.clearDraft(true, "unknown_delivery", "draft", "draft"))
        assertFalse(WorkbenchPolicy.clearDraft(true, "running", "draft", "draft"))
        assertFalse(WorkbenchPolicy.clearDraft(true, "submitted", "old", "edited"))
    }
    @Test fun newTaskRequiresExplicitEmptyAnchor() {
        assertTrue(binding(session = "").canCreate())
        assertFalse(binding().canCreate())
        assertFalse(binding(session = "").copy(unknownDelivery = true).canCreate())
        assertFalse(binding(session = "").canAct("A"))
    }
    @Test fun submittedDoesNotMeanCompleted() {
        assertTrue(WorkbenchPolicy.accepted("submitted")); assertFalse(WorkbenchPolicy.completed("submitted"))
        assertTrue(WorkbenchPolicy.completed("completed")); assertTrue(WorkbenchPolicy.unknownDelivery("unknown_delivery"))
    }
}
