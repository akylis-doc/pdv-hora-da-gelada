package br.com.bebidaspdv.core.logging

import java.util.Collections
import java.util.IdentityHashMap

/** Defense in depth, applied before disk and export. Do not pass business payloads to the logger. */
object LogPrivacy {
    private const val SECRET_NAMES = "password|passwd|senha|(?:refresh[_ -]?|access[_ -]?|id[_ -]?|firebase[_ -]?)?token|authorization|credential|credencial|secret|api[_ -]?key|private[_ -]?key|service[_ -]?account|cvv|cvc|card[_ -]?number|numero[_ -]?cartao|cookie"
    private const val SENSITIVE_NAMES = "$SECRET_NAMES|payload|customer[_ -]?name|client[_ -]?name|nome[_ -]?cliente|phone|telefone|address|endereco|cpf|cnpj"
    private val secrets = Regex("(?i)($SENSITIVE_NAMES)")
    private val email = Regex("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val quotedAssignment = Regex("(?i)([\"']?(?:$SENSITIVE_NAMES)[\"']?\\s*[:=]\\s*)(?:\"(?:\\\\.|[^\"\\\\])*(?:\"|$)|'(?:\\\\.|[^'\\\\])*(?:'|$)|[^\\s,;}]+)")
    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;\"']+")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?")
    private val privateKey = Regex("-----BEGIN[^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END[^-]*PRIVATE KEY-----|$)")
    private val serviceAccount = Regex("(?is)\\{\\s*\"type\"\\s*:\\s*\"service_account\".*")
    private val longOpaque = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_+/=-]{48,}(?![A-Za-z0-9])")
    private val numbers = Regex("(?<!\\d)(?:\\d[ -]?){10,19}(?!\\d)")
    private val formattedPhone = Regex("(?<![A-Za-z0-9])(?:\\+?55[ .-]?)?\\(?[1-9]\\d\\)?[ .-]?9?\\d{4}[ .-]?\\d{4}(?!\\d)")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** UUID groups can contain 12 decimal digits; they are identifiers, not telephone/card numbers. */
    fun identifier(value: String?): String? = if (value != null && uuid.matches(value)) value else text(value, 128)

    fun text(value: String?, limit: Int = 2_000): String? = value?.take(20_000)?.let { source ->
        var safe = serviceAccount.replace(source, "[SERVICE ACCOUNT REDACTED]")
        safe = privateKey.replace(safe, "[PRIVATE KEY REDACTED]")
        safe = bearer.replace(safe, "Bearer [REDACTED]")
        safe = quotedAssignment.replace(safe) { it.groupValues[1] + "[REDACTED]" }
        safe = jwt.replace(safe, "[TOKEN REDACTED]")
        safe = longOpaque.replace(safe, "[OPAQUE VALUE REDACTED]")
        safe = formattedPhone.replace(safe, "[PHONE REDACTED]")
        safe = numbers.replace(safe, "[NUMBER REDACTED]")
        safe = email.replace(safe) { it.groupValues[1] + "***@" + it.groupValues[2] }
        safe.replace('\u0000', ' ').take(limit.coerceAtLeast(0))
    }

    fun metadata(input: Map<String, String>): Map<String, String> = input.entries.take(24).associate { (key, value) ->
        (text(key, 64) ?: "field") to if (secrets.containsMatchIn(key)) "[REDACTED]" else text(value, 320).orEmpty()
    }

    fun entry(log: LogEntry): LogEntry = log.copy(
        id = identifier(log.id).orEmpty(), traceId = identifier(log.traceId), operationId = identifier(log.operationId),
        message = text(log.message).orEmpty(), event = text(log.event, 120).orEmpty(),
        entityId = identifier(log.entityId), userId = identifier(log.userId), deviceId = identifier(log.deviceId).orEmpty(),
        screen = text(log.screen, 120), component = text(log.component, 160), function = text(log.function, 120),
        errorCode = text(log.errorCode, 160), exceptionType = text(log.exceptionType, 240),
        stackTrace = text(log.stackTrace, 6_000), metadata = metadata(log.metadata),
        appVersion = text(log.appVersion, 40).orEmpty(), buildVersion = text(log.buildVersion, 40).orEmpty(),
        deviceInformation = text(log.deviceInformation, 240),
    )

    /** No unbounded printStackTrace(), business object serialization or custom Throwable.toString(). */
    fun stackTrace(error: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = StringBuilder()
        fun appendFailure(failure: Throwable, prefix: String, depth: Int) {
            if (depth >= 8 || !seen.add(failure) || result.length >= 6_000) return
            result.append(prefix).append(failure.javaClass.name).append(": ")
                .append(text(runCatching { failure.message }.getOrNull(), 600)).append('\n')
            runCatching { failure.stackTrace }.getOrNull()?.take(32)?.forEach { frame ->
                if (result.length < 6_000) result.append("  at ").append(text(frame.toString(), 300)).append('\n')
            }
            runCatching { failure.suppressed }.getOrNull()?.take(2)?.forEach { appendFailure(it, "Suppressed: ", depth + 1) }
            runCatching { failure.cause }.getOrNull()?.let { appendFailure(it, "Caused by: ", depth + 1) }
        }
        appendFailure(error, "", 0)
        return text(result.toString(), 6_000).orEmpty()
    }
}
