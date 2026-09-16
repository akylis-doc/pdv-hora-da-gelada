package br.com.bebidaspdv.ui

/** Keep the user's entered box cost intact; conversion belongs to the use case. */
data class ProductForm(
    val name: String,
    val barcode: String,
    val cost: String,
    val price: String,
    val imageKey: String,
    val lowStockThreshold: String,
    val packageSize: String,
    val packagePrice: String,
    val category: String = "OUTROS",
    val unitLabel: String = "UNIDADE",
    val purchaseByPackage: Boolean = false,
)

internal fun productCategoryLabel(value: String): String = when (value) {
    "BEBIDA" -> "Bebidas"
    "ALIMENTO" -> "Alimentos"
    "CIGARRO" -> "Cigarros"
    else -> "Outros"
}

internal fun productUnitLabel(value: String, plural: Boolean = false): String = when (value) {
    "MACO" -> if (plural) "maços" else "maço"
    "PACOTE" -> if (plural) "pacotes" else "pacote"
    else -> if (plural) "unidades" else "unidade"
}

internal fun productUnitShort(value: String): String = when (value) {
    "MACO" -> "maço(s)"
    "PACOTE" -> "pct."
    else -> "un."
}
