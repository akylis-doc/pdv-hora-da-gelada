import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.Normalizer
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

fun photoKey(file: File): String = Normalizer.normalize(file.nameWithoutExtension, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9_-]+"), "-")
    .trim('-', '_')

fun jsonString(value: String): String = "\"" + value.flatMap { char ->
    when (char) {
        '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
        else -> if (char.code < 32) "\\u%04x".format(char.code) else char.toString()
    }.toList()
}.joinToString("") + "\""

// Phone cameras commonly store rotation in EXIF instead of rotating the pixels.
// Only the orientation is read; exported JPEGs contain no original EXIF/GPS data.
fun photoOrientation(file: File): Int = runCatching {
    if (file.extension.lowercase(Locale.ROOT) !in setOf("jpg", "jpeg")) return@runCatching 1
    val bytes = file.inputStream().use { it.readNBytes(262_144) }
    fun unsigned(offset: Int) = bytes[offset].toInt() and 255
    var marker = 2
    while (marker + 4 < bytes.size && unsigned(marker) == 255) {
        val type = unsigned(marker + 1)
        if (type == 0xda || type == 0xd9) break
        val length = unsigned(marker + 2) * 256 + unsigned(marker + 3)
        if (length < 2 || marker + 2 + length > bytes.size) break
        val payload = marker + 4
        if (type == 0xe1 && length >= 16 && String(bytes, payload, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
            val tiff = payload + 6
            val end = marker + 2 + length
            val little = unsigned(tiff) == 0x49 && unsigned(tiff + 1) == 0x49
            check(little || (unsigned(tiff) == 0x4d && unsigned(tiff + 1) == 0x4d))
            fun word(at: Int): Int {
                check(at >= tiff && at + 2 <= end)
                return if (little) unsigned(at) + unsigned(at + 1) * 256 else unsigned(at) * 256 + unsigned(at + 1)
            }
            fun integer(at: Int): Long = if (little) word(at).toLong() + (word(at + 2).toLong() shl 16)
                else (word(at).toLong() shl 16) + word(at + 2)
            check(word(tiff + 2) == 42)
            val offset = integer(tiff + 4)
            check(offset in 8..(end - tiff - 2).toLong())
            val directory = tiff + offset.toInt()
            repeat(word(directory)) { index ->
                val entry = directory + 2 + index * 12
                check(entry + 12 <= end)
                if (word(entry) == 0x112 && word(entry + 2) == 3 && integer(entry + 4) == 1L) {
                    return@runCatching word(entry + 8).takeIf { it in 1..8 } ?: 1
                }
            }
        }
        marker += 2 + length
    }
    1
}.getOrDefault(1)

fun compressProductPhoto(source: File, target: File) {
    val decoded = ImageIO.createImageInputStream(source).use { input ->
        checkNotNull(input) { "Não foi possível abrir a foto ${source.name}." }
        val readers = ImageIO.getImageReaders(input)
        check(readers.hasNext()) { "Foto inválida: ${source.name}. Use JPG ou PNG." }
        val reader = readers.next()
        try {
            reader.input = input
            val sample = (maxOf(reader.getWidth(0), reader.getHeight(0)) / 1024).coerceAtLeast(1)
            val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            reader.read(0, parameters)
        } finally { reader.dispose() }
    }
    val scale = minOf(1.0, 512.0 / maxOf(decoded.width, decoded.height))
    val width = (decoded.width * scale).toInt().coerceAtLeast(1)
    val height = (decoded.height * scale).toInt().coerceAtLeast(1)
    val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    resized.createGraphics().apply {
        color = Color.WHITE
        fillRect(0, 0, width, height)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        drawImage(decoded, 0, 0, width, height, null)
        dispose()
    }
    decoded.flush()
    val orientation = photoOrientation(source)
    val result = if (orientation == 1) resized else {
        val rotated = BufferedImage(if (orientation >= 5) height else width, if (orientation >= 5) width else height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) {
            val (outX, outY) = when (orientation) {
                2 -> width - 1 - x to y
                3 -> width - 1 - x to height - 1 - y
                4 -> x to height - 1 - y
                5 -> y to x
                6 -> height - 1 - y to x
                7 -> height - 1 - y to width - 1 - x
                8 -> y to width - 1 - x
                else -> x to y
            }
            rotated.setRGB(outX, outY, resized.getRGB(x, y))
        }
        resized.flush()
        rotated
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
        ImageIO.createImageOutputStream(target).use { output ->
            writer.output = output
            val parameters = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.82f
            }
            writer.write(null, javax.imageio.IIOImage(result, null, null), parameters)
        }
    } finally { writer.dispose(); result.flush() }
}

val sourcePhotos = rootProject.file("fotos-produtos")
val generatedPhotos = layout.buildDirectory.dir("generated/productPhotos/assets")
val prepareProductPhotos by tasks.registering {
    group = "assets"
    description = "Comprime fotos-produtos e prepara o catálogo de imagens offline."
    inputs.dir(sourcePhotos)
    outputs.dir(generatedPhotos)
    doLast {
        val files = sourcePhotos.walkTopDown().filter { it.isFile }.toList()
        val unsupported = files.filter { it.extension.lowercase(Locale.ROOT) in setOf("webp", "heic", "heif", "gif", "bmp", "avif") }
        check(unsupported.isEmpty()) { "Converta estas fotos para JPG ou PNG: ${unsupported.joinToString { it.name }}" }
        val photos = files.filter { it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val entries = photos.map { photo ->
            val key = photoKey(photo)
            check(key.length in 1..100) { "Use um nome de foto com letras/números e até 100 caracteres: ${photo.name}" }
            key to photo
        }
        val duplicates = entries.groupBy { it.first }.filterValues { it.size > 1 }
        check(duplicates.isEmpty()) { "Fotos com nomes equivalentes. Renomeie uma de cada grupo: ${duplicates.values.joinToString { group -> group.joinToString { it.second.name } }}" }
        val output = generatedPhotos.get().asFile
        check(output.canonicalPath.startsWith(layout.buildDirectory.get().asFile.canonicalPath + File.separator))
        delete(output)
        val folder = File(output, "product_photos").apply { mkdirs() }
        entries.forEach { (key, photo) -> compressProductPhoto(photo, File(folder, "$key.jpg")) }
        File(folder, "catalog.json").writeText(entries.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { (key, photo) ->
            "  {\"key\":${jsonString(key)},\"fileName\":${jsonString(photo.name)}}"
        }, Charsets.UTF_8)
        logger.lifecycle("Catálogo offline preparado: ${entries.size} foto(s), lado máximo 512 px.")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareProductPhotos) }
