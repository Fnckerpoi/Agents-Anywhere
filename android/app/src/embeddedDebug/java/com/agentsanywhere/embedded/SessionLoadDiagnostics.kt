package com.agentsanywhere.embedded

import android.util.Log
import com.agentsanywhere.app.api.ApiException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class SessionLoadStage {
    AUTH_SNAPSHOT, AUTH_APPLY, SESSION_REQUEST, AUTH_RECHECK, IDENTITY_CHECK, SESSION_MAPPING,
}

internal enum class SessionLoadFailure {
    HTTP, DNS, TLS, TIMEOUT, CONNECTION, IO, JSON, INVALID_ARGUMENT, API, OTHER,
}

internal class SessionLoadDiagnostics(
    private val emit: (String) -> Unit = { Log.w("AA.SessionLoad", it); Unit },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun <T> at(stage: SessionLoadStage, block: suspend () -> T): T {
        val started = nanoTime()
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Diagnostics must neither mask the original failure nor print exception-controlled text.
            try {
                val api = failure as? ApiException
                val status = api?.statusCode?.takeIf { it in 100..599 }
                val category = if (status != null) SessionLoadFailure.HTTP else classify(api?.cause ?: failure)
                val elapsed = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                emit("stage=${stage.name} category=${category.name} http_status=${status ?: "none"} elapsed_ms=$elapsed")
            } catch (_: Exception) {
                // Logging is best effort; preserve the load's original exception.
            }
            throw failure
        }
    }

    private fun classify(failure: Throwable): SessionLoadFailure = when (failure) {
        is UnknownHostException -> SessionLoadFailure.DNS
        is SSLException -> SessionLoadFailure.TLS
        is SocketTimeoutException -> SessionLoadFailure.TIMEOUT
        is ConnectException -> SessionLoadFailure.CONNECTION
        is IOException -> SessionLoadFailure.IO
        is JSONException -> SessionLoadFailure.JSON
        is IllegalArgumentException -> SessionLoadFailure.INVALID_ARGUMENT
        is ApiException -> SessionLoadFailure.API
        else -> SessionLoadFailure.OTHER
    }
}
