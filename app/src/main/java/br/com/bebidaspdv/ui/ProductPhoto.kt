package br.com.bebidaspdv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class BundledProductPhoto(val key: String, val fileName: String)

private object ProductPhotoCache {
    val bitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
}

/** Product identifiers never become arbitrary asset paths. Unknown keys stay harmless. */
private val photoKeyPattern = Regex("[a-z0-9_-]{1,100}")

@Composable
internal fun ProductPhoto(imageKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(initialValue = null, imageKey) {
        value = null
        if (photoKeyPattern.matches(imageKey)) {
            value = withContext(Dispatchers.IO) {
                ProductPhotoCache.bitmaps.get(imageKey) ?: runCatching {
                    context.assets.open("product_photos/$imageKey.jpg").use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.also { ProductPhotoCache.bitmaps.put(imageKey, it) }
            }
        }
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            bitmap?.let { photo ->
                Image(photo.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } ?: Icon(Icons.Default.Image, contentDescription = "Produto sem foto nesta versão", modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberProductPhotos(): List<BundledProductPhoto> {
    val context = LocalContext.current.applicationContext
    val catalog by produceState(initialValue = emptyList<BundledProductPhoto>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val json = context.assets.open("product_photos/catalog.json").bufferedReader().use { JSONArray(it.readText()) }
                buildList {
                    for (index in 0 until json.length()) {
                        val entry = json.getJSONObject(index)
                        val key = entry.getString("key")
                        if (photoKeyPattern.matches(key)) add(BundledProductPhoto(key, entry.getString("fileName")))
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
    return catalog
}

@Composable
internal fun ProductPhotoField(imageKey: String, enabled: Boolean, onSelected: (String) -> Unit) {
    val photos = rememberProductPhotos()
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Foto do produto", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductPhoto(imageKey, Modifier.size(72.dp))
            Column(Modifier.weight(1f)) {
                val selected = photos.firstOrNull { it.key == imageKey }
                if (imageKey.isNotBlank()) {
                    Text(selected?.fileName ?: imageKey, style = MaterialTheme.typography.bodySmall)
                    if (selected == null) Text("Foto indisponível nesta versão; sua seleção será mantida.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Escolher foto") }
            }
        }
        if (imageKey.isNotBlank()) TextButton(onClick = { onSelected("") }, enabled = enabled) { Text("Remover foto") }
    }
    if (choosing) {
        ProductPhotoPicker(photos, imageKey, onDismiss = { choosing = false }, onSelected = { onSelected(it); choosing = false })
    }
}

@Composable
private fun ProductPhotoPicker(photos: List<BundledProductPhoto>, selectedKey: String, onDismiss: () -> Unit, onSelected: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(photos, search) {
        photos.filter { it.fileName.contains(search.trim(), ignoreCase = true) || it.key.contains(search.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolher foto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Buscar nome do arquivo") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                Text("Novas fotos ficam disponíveis nas atualizações do aplicativo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    photos.isEmpty() -> Text("Nenhuma foto disponível nesta versão. Você pode salvar o produto sem foto e escolher uma depois.")
                    filtered.isEmpty() -> Text("Nenhuma foto com esse nome.")
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = BundledProductPhoto::key) { photo ->
                            Surface(onClick = { onSelected(photo.key) }, shape = RoundedCornerShape(12.dp),
                                color = if (selectedKey == photo.key) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ProductPhoto(photo.key, Modifier.size(60.dp))
                                    Text(photo.fileName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (selectedKey == photo.key) Icon(Icons.Default.CheckCircle, "Foto selecionada", Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Voltar") } },
    )
}
