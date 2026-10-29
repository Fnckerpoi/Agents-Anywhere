package com.agentsanywhere.embedded

import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ConfigurationBootstrapTest {
    private class Fixture {
        val captured = ConfigDraft("draft", 3, 0, "binding",
            ConfigurationPresentation.homeLevels.associateWith { ConfigOption(it.wire + "-id", "Saved", available = true) },
            emptySet())
        var current: ConfigDraft? = captured
        var active = true
        val requests = ConfigurationRequests()
        val ticket = requests.begin(captured, ConfigLevel.Connectors)
        val calls = mutableListOf<Bundle>()

        fun parents() = Bundle().apply {
            putString("connectorId", captured.selected[ConfigLevel.Connectors]!!.id)
            putString("projectId", captured.selected[ConfigLevel.Projects]!!.id)
            putString("runtimeId", captured.selected[ConfigLevel.Runtimes]!!.id)
        }
        fun response(code: String = "DIRECTORY_FAILED") = Bundle().apply {
            putBoolean("ok", code.isEmpty())
            putString("errorCode", code)
            putString("draftId", captured.id)
            putLong("requestDraftRevision", captured.revision)
            putLong("authGeneration", captured.generation)
            putLong("requestId", ticket.sequence)
            putString("level", ticket.level!!.wire)
            if (code.isEmpty()) {
                putLong("draftRevision", captured.revision)
                putString("baseRevision", captured.baseRevision)
                putBundle("parents", parents())
                putBundle("selected", Bundle().apply {
                    captured.selected.forEach { (level, option) ->
                        putBundle(level.wire, Bundle().apply {
                            putString("id", option.id)
                            putString("label", "Resolved " + level.wire)
                            putBoolean("available", true)
                        })
                    }
                })
                putBundle("valid", Bundle().apply { putBoolean(ConfigLevel.Connectors.wire, true) })
            }
        }
        suspend fun validate(reply: suspend (Int) -> Bundle): BootstrapResult = validateBootstrapLevel(
            captured, ticket, requests, { current }, { active },
        ) { args -> calls += Bundle(args); reply(calls.size) }
    }

    @Test fun transientFailureRetriesSameRequestOnceAndResolvesLabels() = runBlocking {
        val f = Fixture()
        val result = f.validate { if (it == 1) f.response() else f.response("") } as BootstrapResult.Validated
        assertEquals(2, f.calls.size)
        f.calls.forEach {
            assertEquals(f.captured.id, it.getString("draftId"))
            assertEquals(f.captured.revision, it.getLong("expectedDraftRevision"))
            assertEquals(f.ticket.sequence, it.getLong("requestId"))
            assertEquals("connectors", it.getString("level"))
            assertEquals(setOf("draftId", "expectedDraftRevision", "requestId", "level"), it.keySet())
        }
        assertEquals("Resolved connectors", result.draft.selected[ConfigLevel.Connectors]!!.label)
        assertTrue(ConfigLevel.Connectors in result.draft.valid)
        assertFalse(result.draft.canSave)
        assertSame(f.captured, f.current)
    }

    @Test fun persistentFailureStopsAfterTwoCallsAndPreservesUnverifiedSelection() = runBlocking {
        val f = Fixture()
        assertEquals(BootstrapResult.Failed("DIRECTORY_FAILED"), f.validate { f.response() })
        assertEquals(2, f.calls.size)
        assertSame(f.captured, f.current)
        assertEquals("binding", f.current!!.baseRevision)
        assertEquals(f.captured.selected, f.current!!.selected)
        assertTrue(f.current!!.valid.isEmpty())
        assertFalse(f.current!!.canSave)
    }

    @Test fun immediateSuccessDoesNotRetry() = runBlocking {
        val f = Fixture()
        assertTrue(f.validate { f.response("") } is BootstrapResult.Validated)
        assertEquals(1, f.calls.size)
    }

    @Test fun everyWrongResponseOwnershipFieldPreventsRetry() = runBlocking {
        val changes: List<Bundle.() -> Unit> = listOf(
            { putString("draftId", "other") }, { putLong("requestDraftRevision", 4) },
            { putLong("authGeneration", 1) }, { putLong("requestId", 99) },
            { putString("level", "projects") }, { remove("draftId") },
            { remove("requestDraftRevision") }, { remove("authGeneration") },
            { remove("requestId") }, { remove("level") },
        )
        changes.forEach { change ->
            val f = Fixture()
            assertEquals(BootstrapResult.Mismatched, f.validate { f.response().apply(change) })
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun retryResponseMustAlsoMatchOwnership() = runBlocking {
        val f = Fixture()
        assertEquals(BootstrapResult.Mismatched, f.validate {
            if (it == 1) f.response() else f.response("").apply { putLong("authGeneration", 9) }
        })
        assertEquals(2, f.calls.size)
    }

    @Test fun errorMayOmitParentsButCannotReturnWrongParents() = runBlocking {
        listOf("connectorId", "projectId", "runtimeId").forEach { parent ->
            val f = Fixture()
            assertEquals(BootstrapResult.Stale, f.validate {
                f.response().apply { putBundle("parents", f.parents().apply { putString(parent, "other") }) }
            })
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun successRequiresParentsAndParsedDraft() = runBlocking {
        val changes: List<Bundle.() -> Unit> = listOf(
            { remove("parents") }, { putBundle("parents", Bundle()) },
            { remove("selected") }, { remove("valid") }, { remove("draftRevision") },
        )
        changes.forEach { change ->
            val f = Fixture()
            assertEquals(BootstrapResult.Stale, f.validate { f.response("").apply(change) })
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun nonDirectoryErrorsNeverRetry() = runBlocking {
        listOf("STALE_DRAFT", "STALE_SELECTION", "STALE_AUTH", "AUTH_REQUIRED", "REQUEST_FAILED", "INVALID_TARGET").forEach { code ->
            val f = Fixture()
            val result = f.validate { f.response(code) }
            if (ConfigurationText.isStale(code)) assertEquals(BootstrapResult.Stale, result)
            else assertEquals(BootstrapResult.Failed(code), result)
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun localChangesWhileRequestRunsPreventRetry() = runBlocking {
        val changes: List<Fixture.() -> Unit> = listOf(
            { requests.invalidate() }, { active = false }, { current = null },
            { current = captured.copy(id = "other") }, { current = captured.copy(revision = 4) },
            { current = captured.copy(generation = 1) },
            { current = captured.copy(selected = captured.selected - ConfigLevel.Projects) },
        )
        changes.forEach { change ->
            val f = Fixture()
            assertEquals(BootstrapResult.Obsolete, f.validate { f.change(); f.response() })
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun displayOnlyChangesDoNotInvalidateRequestOwnership() = runBlocking {
        val f = Fixture()
        assertTrue(f.validate {
            if (it == 1) {
                f.current = f.captured.copy(selected = f.captured.selected.mapValues { (_, option) -> option.copy(label = "New label") })
                f.response()
            } else f.response("")
        } is BootstrapResult.Validated)
        assertEquals(2, f.calls.size)
    }

    @Test fun alreadyObsoleteRequestMakesNoCall() = runBlocking {
        val f = Fixture()
        f.requests.invalidate()
        assertEquals(BootstrapResult.Obsolete, f.validate { error("Must not query") })
        assertTrue(f.calls.isEmpty())
    }

    @Test fun obsoleteRetryResultCannotBeApplied() = runBlocking {
        val f = Fixture()
        assertEquals(BootstrapResult.Obsolete, f.validate {
            if (it == 1) f.response() else { f.active = false; f.response("") }
        })
        assertEquals(2, f.calls.size)
        assertSame(f.captured, f.current)
    }

    @Test fun cancellationDuringEitherAttemptPropagatesWithoutAnotherCall() = runBlocking {
        for (cancelAt in 1..2) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                f.validate {
                    if (it == cancelAt) { entered.complete(Unit); CompletableDeferred<Bundle>().await() }
                    else f.response()
                }
                fail("Cancelled validation returned")
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(cancelAt, f.calls.size)
        }
    }

    @Test fun thrownCancellationIsNotRetried() = runBlocking {
        val f = Fixture()
        try {
            f.validate { throw CancellationException("closed") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, f.calls.size)
        }
    }
}
