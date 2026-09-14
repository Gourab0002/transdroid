/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.protocol.internal

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.transdroid.protocol.DaemonException

/**
 * Executes [request] asynchronously so coroutine cancellation aborts the socket, translating
 * transport failures to [DaemonException.Connection] and certificate-trust failures to
 * [DaemonException.UntrustedServer]. The caller owns closing the returned response.
 */
internal suspend fun OkHttpClient.executeOnIo(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(mapTransportFailure(request, e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
    }

internal fun mapTransportFailure(request: Request, error: IOException): DaemonException {
    val host = "${request.url.host}:${request.url.port}"
    return if (isCertificateTrustFailure(error)) {
        DaemonException.UntrustedServer(
            "TLS to $host failed; the certificate may be self-signed",
            error,
        )
    } else {
        DaemonException.Connection("Cannot reach $host", error)
    }
}

internal fun isCertificateTrustFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is SSLPeerUnverifiedException, is CertificateException -> return true
            is SSLHandshakeException -> {
                // Handshake failures caused by an untrusted cert nest a CertificateException;
                // protocol/version mismatches do not and must stay connection errors.
                val nested = generateSequence(current.cause) { it.cause }
                if (nested.any { it is CertificateException || it is SSLPeerUnverifiedException }) return true
            }
            is SSLException -> {
                val nested = generateSequence(current.cause) { it.cause }
                if (nested.any { it is CertificateException || it is SSLPeerUnverifiedException }) return true
            }
        }
        current = current.cause
    }
    return false
}

/** Joins an optional base path and an endpoint into a normalized absolute path. */
internal fun joinPath(basePath: String?, endpoint: String): String {
    val base = basePath.orEmpty().trim('/')
    val end = endpoint.trim('/')
    return if (base.isEmpty()) "/$end" else "/$base/$end"
}
