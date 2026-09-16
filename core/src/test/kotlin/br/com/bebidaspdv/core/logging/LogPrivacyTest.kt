package br.com.bebidaspdv.core.logging

import java.io.StringWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogPrivacyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun masksSecretsInFreeTextAndQuotedJsonAssignments() {
        val raw = "senha=super-secreta password='also secret' access_token=abcdsecret " +
            "Authorization: Bearer xxxxxx {\"refresh_token\":\"refresh-value\",\"cvv\":\"123\",\"api_key\":\"key-value\"}"
        val safe = LogPrivacy.text(raw).orEmpty()
        listOf("super-secreta", "also secret", "abcdsecret", "xxxxxx", "refresh-value", "123", "key-value").forEach {
            assertFalse("Leaked $it in $safe", safe.contains(it))
        }
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun masksEmailsPhoneNumbersCardsJwtAndPrivateKeys() {
        val raw = "nome@example.com +55 (11) 98888-7777 4111 1111 1111 1111 " +
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.signature " +
            "-----BEGIN PRIVATE KEY-----\nprivate-data\n-----END PRIVATE KEY-----"
        val safe = LogPrivacy.text(raw).orEmpty()
        assertTrue(safe.contains("n***@example.com"))
        listOf("98888", "4111", "eyJhbGci", "private-data").forEach { assertFalse(safe.contains(it)) }
    }

    @Test fun serviceAccountPayloadIsNeverRetained() {
        val raw = "{\"type\":\"service_account\",\"project_id\":\"private-project\",\"private_key\":\"sensitive\"}"
        assertEquals("[SERVICE ACCOUNT REDACTED]", LogPrivacy.text(raw))
    }

    @Test fun metadataRejectsCustomerInformationAndPayloadsAndLimitsEntries() {
        val raw = linkedMapOf("customer_name" to "Maria Particular", "telefone" to "11987654321", "payload" to "whole-request",
            "password" to "secret", "attempt" to "3", "email" to "nome@dominio.com")
        val safe = LogPrivacy.metadata(raw)
        listOf("customer_name", "telefone", "payload", "password").forEach { assertEquals("[REDACTED]", safe[it]) }
        assertEquals("3", safe["attempt"])
        assertEquals("n***@dominio.com", safe["email"])
        assertEquals(24, LogPrivacy.metadata((1..100).associate { "field$it" to "x".repeat(10_000) }).size)
        assertTrue(LogPrivacy.metadata(mapOf("description" to "word ".repeat(500))).values.single().length <= 320)
    }

    @Test fun UUIDWithAllNumericFinalGroupIsPreservedForExactTraceSearch() {
        val uuid = "12345678-abcd-4321-1234-123456789012"
        val record = LogEntry(uuid, 1, LogLevel.INFO, LogCategory.APP, "EVENT", "Ok", traceId = uuid, operationId = uuid, entityId = uuid, userId = uuid, deviceId = uuid)
        val safe = LogPrivacy.entry(record)
        assertEquals(uuid, safe.id)
        assertEquals(uuid, safe.traceId)
        assertEquals(uuid, safe.operationId)
        assertEquals(uuid, safe.entityId)
        assertEquals(uuid, safe.userId)
        assertEquals(uuid, safe.deviceId)
    }

    @Test fun exceptionCausesAreBoundedEvenWithCyclesAndSecretMessages() {
        val first = IllegalStateException("password=first-secret")
        val second = IllegalArgumentException("token=second-secret")
        first.initCause(second)
        second.initCause(first)
        val safe = LogPrivacy.stackTrace(first)
        assertTrue(safe.length <= 6_000)
        assertTrue(safe.contains("Caused by:"))
        assertFalse(safe.contains("first-secret"))
        assertFalse(safe.contains("second-secret"))
    }

    @Test fun sensitiveValuesAreAbsentFromLocalDiskAndExportNotJustHiddenByUi() = runBlocking {
        val directory = temporary.newFolder()
        LocalAppLogger(directory).use { logger ->
            logger.log(LogLevel.ERROR, LogCategory.AUTH, "LOGIN_FAILED", "email pessoa@exemplo.com password=password-secret",
                error = IllegalStateException("access_token=token-secret"), metadata = mapOf("customer_name" to "Nome Sensivel", "cvv" to "123"))
            assertTrue(logger.flush())
            val exported = StringWriter()
            logger.store.export(writer = exported)
            val disk = directory.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            listOf(disk, exported.toString()).forEach { output ->
                listOf("pessoa@exemplo.com", "password-secret", "token-secret", "Nome Sensivel").forEach { assertFalse(output.contains(it)) }
                assertTrue(output.contains("p***@exemplo.com"))
                assertTrue(output.contains("[REDACTED]"))
            }
        }
    }
}
