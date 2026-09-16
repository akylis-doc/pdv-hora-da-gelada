package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.bebidaspdv.core.Customer

@Composable
internal fun EditCustomerDialog(customer: Customer, canChangeLimit: Boolean, working: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable(customer.id) { mutableStateOf(customer.name) }
    var phone by rememberSaveable(customer.id) { mutableStateOf(customer.phone) }
    var limit by rememberSaveable(customer.id) { mutableStateOf(decimalInput(customer.creditLimitCents)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Editar cliente") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nome") }, enabled = !working, singleLine = true)
            OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("Telefone") }, enabled = !working,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(limit, { limit = it.take(16) }, label = { Text("Limite de fiado (R$)") }, enabled = !working && canChangeLimit,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Zero significa sem limite definido. O administrador pode liberar uma venda acima do limite informando o motivo.", style = MaterialTheme.typography.bodySmall)
            Text("Saldo atual: ${currency(customer.balanceCents)}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { onSave(name, phone, limit) }, enabled = !working && name.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}
