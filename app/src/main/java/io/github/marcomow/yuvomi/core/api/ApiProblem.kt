package io.github.marcomow.yuvomi.core.api

import java.io.IOException

/** Everything a call to the household's server can fail with. */
sealed class ApiProblem(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The server answered with an error. [reason] is upstream's stable machine
     * reason (`tasks_task_not_found`, `task_locked`, ...); [serverMessage] is its
     * English `error` text, never shown to the user (ARCHITECTURE.md, rule 4).
     * [params] are the whole numbers of the answer's `params` (a limit, a status), which a
     * sentence may name ([FriendlyError]).
     */
    class Http(val status: Int, val reason: String?, val serverMessage: String?, val params: Map<String, Long> = emptyMap()) :
        ApiProblem("HTTP $status ${reason ?: ""} ${serverMessage ?: ""}".trim()) {

        /** A 405, or a 404 without a reason: the operation itself is gone (ARCHITECTURE.md, rule 6). */
        val operationUnsupported: Boolean get() = status == 405 || (status == 404 && reason == null)
    }

    /** No answer at all: offline, DNS, TLS, timeout. */
    class Network(cause: IOException) : ApiProblem(cause.message, cause) {
        val isCertificate: Boolean get() = generateSequence(cause) { it.cause }.any { it is javax.net.ssl.SSLException }
    }

    /** The session ended on the server (a 401 outside sign-in). */
    class SessionExpired : ApiProblem("session expired")

    /** The answer did not have the expected shape. */
    class Malformed(detail: String, cause: Throwable? = null) : ApiProblem(detail, cause)
}
