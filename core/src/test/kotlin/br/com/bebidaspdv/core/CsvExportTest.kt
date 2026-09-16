package br.com.bebidaspdv.core

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CsvExportTest {
    private val product = Product(UUID.randomUUID().toString(), "Água; \"gelada\"", "0012345678905", 12, 334, 500,
        packageSize = 3, purchaseByPackage = true, packageCostCents = 1001)

    @Test fun `stock separates base quantity from money and preserves box cost and barcode text`() {
        val csv = CsvExport.products(listOf(product, product.copy(id = "excluded", active = false)))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"Água; \"\"gelada\"\"\""))
        assertTrue(csv.contains("\"'0012345678905\""))
        assertTrue(csv.contains("\"12\";\"3\";\"3,34\";\"CAIXA\";\"10,01\";\"5,00\";\"15,00\""))
        assertFalse(csv.contains("excluded"))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test fun `all untrusted fields escape spreadsheet formula prefixes even behind whitespace`() {
        listOf("=1+1", "+SUM(1;1)", "-1+2", "@SUM(A1)", "  =2+2", "\t=1+1", "\r=1+1", "\uFEFF=1+1").forEach { dangerous ->
            val csv = CsvExport.customers(listOf(Customer("customer-id", dangerous, dangerous)))
            assertTrue("Unsafe prefix was exported: $dangerous", csv.contains("\"'$dangerous\""))
        }
    }

    @Test fun `negative financial amounts stay numeric and names cannot create extra CSV columns`() {
        val sale = Movement("sale-id", Instant.parse("2026-09-10T02:00:00Z"), MovementType.SAIDA,
            listOf(SaleItem("product-id", "Produto", 2, 200, 300)), totalCents = 400, profitCents = -200,
            customerName = "Cliente;\"Nome\"\nOutro", payments = listOf(Payment(PaymentMethod.CASH, 500)), changeCents = 100)
        val csv = CsvExport.sales(listOf(sale, sale.copy(id = "stock-entry", type = MovementType.ENTRADA)))
        assertTrue(csv.contains("\"09/09/2026 23:00:00\""))
        assertTrue(csv.contains("\"Cliente;\"\"Nome\"\"\nOutro\""))
        assertTrue(csv.contains("\"2\";\"4,00\";\"6,00\";\"-2,00\";\"4,00\";\"0,00\";\"4,00\""))
        assertFalse(csv.contains("\"'-2,00\""))
        assertFalse(csv.contains("stock-entry"))
    }

    @Test fun `receivables export contains only outstanding amounts with customer telephone`() {
        val csv = CsvExport.receivables(listOf(
            ReceivableAccount("open-sale", "customer-id", 7000, 2000, LocalDate.of(2026, 9, 30).toEpochDay(), 1000),
            ReceivableAccount("paid-sale", "customer-id", 7000, 7000),
        ), listOf(Customer("customer-id", "Maria", "+55 11 99999-9999")))
        assertTrue(csv.contains("\"Maria\";\"'+55 11 99999-9999\";\"70,00\";\"20,00\";\"10,00\";\"40,00\";\"30/09/2026\""))
        assertFalse(csv.contains("paid-sale"))
    }

    @Test fun `legacy sales leave unknown payment methods blank and keep original fiado separate`() {
        val sale = Movement("sale-id", Instant.parse("2026-09-10T12:00:00Z"), MovementType.SAIDA,
            listOf(SaleItem("product-id", "Produto", 1, 7000, 4000)), 7000, 3000, receivableCents = 2000)
        val csv = CsvExport.sales(listOf(sale))
        assertTrue(csv.contains("\"50,00\";\"20,00\";\"\";\"\";\"\";\"\";\"0,00\""))
    }
}
