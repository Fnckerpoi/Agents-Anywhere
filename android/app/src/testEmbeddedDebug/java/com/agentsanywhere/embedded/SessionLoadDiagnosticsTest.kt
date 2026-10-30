package com.agentsanywhere.embedded

import com.agentsanywhere.app.api.ApiClient
import com.agentsanywhere.app.api.ApiException
import com.agentsanywhere.app.api.SessionsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class SessionLoadDiagnosticsTest {
    private val secret = "token-secret https://private.invalid/message body-secret\nINJECTED"

    @Test fun successReturnsOriginalValueAndRunsOnceWithoutLogging() = runBlocking {
        val logs = mutableListOf<String>()
        val diagnostics = SessionLoadDiagnostics(logs::add)
        val value = Any()
        var calls = 0
        assertSame(value, diagnostics.at(SessionLoadStage.SESSION_REQUEST) { calls++; value })
        assertEquals(1, calls)
        assertTrue(logs.isEmpty())
    }

    @Test fun failureAtEveryStageIsRethrownUnchangedWithOnlyFixedFields() = runBlocking {
        for (stage in SessionLoadStage.entries) {
            val logs = mutableListOf<String>()
            var now = 0L
            val diagnostics = SessionLoadDiagnostics(logs::add) { now }
            val failure = object : Exception(secret) {
                override fun toString(): String = error("Must not stringify exceptions")
            }
            var calls = 0
            try {
                diagnostics.at(stage) { calls++; now = 12_000_000; throw failure }
                fail("Expected original failure")
            } catch (caught: Exception) { assertSame(failure, caught) }
            assertEquals(1, calls)
            assertEquals(listOf("stage=${stage.name} category=OTHER http_status=none elapsed_ms=12"), logs)
        }
    }

    @Test fun apiWrappedCausesUseAllowlistedCategoriesWithoutMessages() = runBlocking {
        val cases = listOf(
            UnknownHostException(secret) to "DNS",
            SSLException(secret) to "TLS",
            SocketTimeoutException(secret) to "TIMEOUT",
            ConnectException(secret) to "CONNECTION",
            IOException(secret) to "IO",
            JSONException(secret) to "JSON",
            IllegalArgumentException(secret) to "INVALID_ARGUMENT",
            IllegalStateException(secret) to "OTHER",
        )
        for ((cause, category) in cases) {
            val logs = mutableListOf<String>()
            val failure = ApiException(secret, cause = cause, errorCode = secret)
            try {
                SessionLoadDiagnostics(logs::add) { 0 }.at(SessionLoadStage.SESSION_REQUEST) { throw failure }
            } catch (caught: Exception) { assertSame(failure, caught) }
            assertEquals(listOf("stage=SESSION_REQUEST category=$category http_status=none elapsed_ms=0"), logs)
        }
    }

    @Test fun onlyValidHttpStatusNumbersAreLogged() = runBlocking {
        for (status in listOf(null, -1, 0, 99, 100, 200, 401, 503, 599, 600, Int.MAX_VALUE)) {
            val logs = mutableListOf<String>()
            val failure = ApiException(secret, statusCode = status, errorCode = secret)
            try {
                SessionLoadDiagnostics(logs::add) { 0 }.at(SessionLoadStage.SESSION_REQUEST) { throw failure }
            } catch (caught: Exception) { assertSame(failure, caught) }
            val valid = status?.takeIf { it in 100..599 }
            val category = if (valid == null) "API" else "HTTP"
            assertEquals(listOf("stage=SESSION_REQUEST category=$category http_status=${valid ?: "none"} elapsed_ms=0"), logs)
        }
    }

    @Test fun cancellationAtEveryStagePropagatesWithoutFailureLog() = runBlocking {
        for (stage in SessionLoadStage.entries) {
            val logs = mutableListOf<String>()
            val cancelled = CancellationException(secret)
            try {
                SessionLoadDiagnostics(logs::add).at(stage) { throw cancelled }
                fail("Expected cancellation")
            } catch (caught: CancellationException) { assertSame(cancelled, caught) }
            assertTrue(logs.isEmpty())
        }
    }

    @Test fun suspendedLoadCancellationRunsCleanupWithoutFailureLog() = runBlocking {
        val logs = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch {
            try {
                SessionLoadDiagnostics(logs::add).at(SessionLoadStage.SESSION_REQUEST) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            } finally { cleaned = true }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
        assertTrue(logs.isEmpty())
    }

    @Test fun brokenDiagnosticSinkDoesNotReplaceOriginalFailure() = runBlocking {
        val failure = IOException(secret)
        try {
            SessionLoadDiagnostics({ throw IllegalStateException(secret) }).at(SessionLoadStage.AUTH_SNAPSHOT) { throw failure }
            fail("Expected original failure")
        } catch (caught: Exception) { assertSame(failure, caught) }
    }

    @Test fun realSessionsApiSuccessHttpAndMalformedJsonKeepExistingBehavior() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = SessionsApi(ApiClient())
            val logs = mutableListOf<String>()
            val diagnostics = SessionLoadDiagnostics(logs::add) { 0 }
            suspend fun load() = diagnostics.at(SessionLoadStage.SESSION_REQUEST) {
                api.listSessions(server.url("/").toString(), "test-token", false)
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"sessions\":[]}"))
            assertTrue(load().sessions.isEmpty())
            assertTrue(logs.isEmpty())
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"body-secret\"}"))
            try { load(); fail("Expected HTTP failure") } catch (caught: ApiException) { assertEquals(401, caught.statusCode) }
            assertEquals(listOf("stage=SESSION_REQUEST category=HTTP http_status=401 elapsed_ms=0"), logs)
            logs.clear()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{malformed-body-secret"))
            try { load(); fail("Expected JSON failure") } catch (caught: ApiException) { assertTrue(caught.cause is JSONException) }
            assertEquals(listOf("stage=SESSION_REQUEST category=JSON http_status=none elapsed_ms=0"), logs)
            assertEquals(3, server.requestCount)
        }
    }
}
