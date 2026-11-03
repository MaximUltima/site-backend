package com.code5150.service

import com.code5150.api.ContractException
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.imageio.ImageIO

data class DownloadFile(
    val id: Long,
    val sha256: String,
    val size: Long,
    val ext: String,
    val name: String,
    val license: String?,
    val source: String?,
    val uploadedBy: String,
    val createdAt: String,
    val status: String,
    val hiddenReason: String?,
) {
    fun toMap() = linkedMapOf<String, Any?>(
        "id" to id, "sha256" to sha256, "size" to size, "ext" to ext, "name" to name,
        "license" to license, "source" to source, "uploaded_by" to uploadedBy,
        "created_at" to createdAt, "status" to status, "hidden_reason" to hiddenReason,
    )
}

private fun Row.downloadFile() = DownloadFile(
    getLong("id"), getString("sha256"), getLong("size"), getString("ext"), getString("name"),
    getString("license"), getString("source"), getString("uploaded_by"), getString("created_at"),
    getString("status"), getString("hidden_reason"),
)

@ApplicationScoped
class DownloadService(
    private val pool: PgPool,
    @ConfigProperty(name = "media.dir") private val mediaDir: String,
    @ConfigProperty(name = "media.url") private val mediaUrl: String,
    @ConfigProperty(name = "files.dir") private val filesDir: String,
) {
    suspend fun addImage(bytes: ByteArray): Map<String, Any> {
        if (bytes.size > MAX_IMAGE_BYTES) throw ContractException(413, "too_large", "Картинка больше 5 МБ.")
        val ext = sniffImage(bytes) ?: throw ContractException(400, "bad_image", "Можно загружать только JPG, PNG или WebP.")
        val dimensions = withContext(Dispatchers.IO) { imageDimensions(bytes, ext) }
            ?: throw ContractException(400, "bad_image", "Файл повреждён или это не картинка.")
        if (dimensions.first > 8000 || dimensions.second > 8000 || dimensions.first.toLong() * dimensions.second > 40_000_000) {
            throw ContractException(400, "bad_image", "Картинка слишком большая.")
        }
        val name = withContext(Dispatchers.IO) {
            val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val fileName = "$random.$ext"
            val dir = Path.of(mediaDir)
            Files.createDirectories(dir)
            Files.write(dir.resolve(fileName), bytes, StandardOpenOption.CREATE_NEW)
            fileName
        }
        return mapOf(
            "name" to name, "width" to dimensions.first, "height" to dimensions.second,
            "mediaUrl" to (mediaUrl.trimEnd('/').takeIf { it.isNotEmpty() }?.plus("/$name") ?: "/media/$name"),
        )
    }

    suspend fun media(name: String): Pair<ByteArray, String>? {
        if (!Regex("^[a-f0-9]{32}\\.(jpg|png|webp)$").matches(name)) return null
        val path = Path.of(mediaDir).resolve(name).normalize()
        if (path.parent != Path.of(mediaDir).normalize()) return null
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                if (!Files.isRegularFile(path) || Files.size(path) > MAX_IMAGE_BYTES * 4) null else Files.readAllBytes(path)
            }.getOrNull()
        } ?: return null
        return bytes to when (name.substringAfterLast('.')) {
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "image/webp"
        }
    }

    suspend fun addFile(bytes: ByteArray, requestedName: String, license: String?, source: String?, actor: String): DownloadFile {
        if (bytes.size > MAX_FILE_BYTES) throw ContractException(413, "too_large", "Файл больше 50 МБ.")
        checkLicense(license)
        val ext = sniffFile(bytes) ?: throw ContractException(400, "bad_file", "Допускаются PDF, EPUB, FB2 и TXT.")
        val sha = sha256(bytes)
        val name = Slugs.fromTitle(requestedName.substringBeforeLast('.')).take(80).ifEmpty { "file" }
        withContext(Dispatchers.IO) {
            val path = filePath(sha)
            if (!Files.exists(path)) {
                Files.createDirectories(path.parent)
                val temporary = path.resolveSibling("${path.fileName}.tmp-${Thread.currentThread().id}")
                Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW)
                runCatching { Files.move(temporary, path) }.getOrElse {
                    Files.deleteIfExists(temporary)
                    if (!Files.exists(path)) throw it
                }
            } else if (Files.size(path) != bytes.size.toLong()) {
                throw ContractException(400, "bad_file", "Файл на диске повреждён.")
            }
        }
        pool.preparedQuery(
            "INSERT INTO download_files (sha256, size, ext, name, license, source, uploaded_by, created_at) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (sha256) DO NOTHING",
        ).execute(dbTuple(sha, bytes.size, ext, name, emptyToNull(license), emptyToNull(source), actor, isoNow())).awaitSuspending()
        val file = pool.preparedQuery("SELECT * FROM download_files WHERE sha256 = $1")
            .execute(Tuple.of(sha)).awaitSuspending().first().downloadFile()
        if (license != null || source != null) return setFileMeta(file.id, license ?: file.license, source ?: file.source)
        return file
    }

    suspend fun file(id: Long): DownloadFile? = pool.preparedQuery("SELECT * FROM download_files WHERE id = $1")
        .execute(Tuple.of(id)).awaitSuspending().firstOrNull()?.downloadFile()

    suspend fun liveFile(id: Long): DownloadFile? = pool.preparedQuery(
        "SELECT f.* FROM download_files f WHERE f.id = $1 AND f.status = 'active' AND f.license IS NOT NULL AND f.source IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM content_versions v JOIN (SELECT DISTINCT ON (kind, slug, lang) id FROM content_versions " +
            "WHERE status <> 'draft' ORDER BY kind, slug, lang, id DESC) current ON current.id = v.id " +
            "WHERE v.status = 'published' AND EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(v.data::jsonb -> 'files', '[]'::jsonb)) entry " +
            "WHERE entry ->> 'id' = f.id::text))",
    ).execute(Tuple.of(id)).awaitSuspending().firstOrNull()?.downloadFile()

    suspend fun setFileMeta(id: Long, license: String?, source: String?): DownloadFile {
        checkLicense(license)
        if (file(id) == null) throw ContractException(404, "not_found")
        return pool.preparedQuery("UPDATE download_files SET license = $1, source = $2 WHERE id = $3 RETURNING *")
            .execute(Tuple.of(emptyToNull(license), emptyToNull(source), id)).awaitSuspending().first().downloadFile()
    }

    suspend fun hideFile(id: Long, reason: String): DownloadFile {
        if (file(id) == null) throw ContractException(404, "not_found")
        val rows = pool.preparedQuery(
            "UPDATE download_files SET status = 'hidden', hidden_reason = $1 WHERE id = $2 AND status = 'active' RETURNING *",
        ).execute(Tuple.of(reason.take(200).ifEmpty { "скрыт через API" }, id)).awaitSuspending()
        if (rows.rowCount() == 0) throw ContractException(409, "already_hidden")
        return rows.first().downloadFile()
    }

    suspend fun bytes(file: DownloadFile): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val path = filePath(file.sha256)
            if (!Files.isRegularFile(path) || Files.size(path) != file.size) null else Files.readAllBytes(path)
        }.getOrNull()
    }

    fun filePath(sha: String): Path = Path.of(filesDir).resolve(sha.take(2)).resolve(sha)

    companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
        val LICENSES = mapOf(
            "own" to "Своё", "author" to "Разрешение автора", "cc" to "Creative Commons",
            "public" to "Общественное достояние", "other" to "Иное",
        )
        val TYPES = mapOf(
            "pdf" to ("application/pdf" to "PDF"), "epub" to ("application/epub+zip" to "EPUB"),
            "fb2" to ("application/x-fictionbook+xml" to "FB2"), "txt" to ("text/plain; charset=utf-8" to "TXT"),
        )

        fun checkLicense(license: String?) {
            if (!license.isNullOrEmpty() && license !in LICENSES) {
                throw ContractException(400, "bad_license", "Неизвестное основание публикации")
            }
        }

        private fun emptyToNull(value: String?) = value?.takeIf { it.isNotEmpty() }

        private fun sniffImage(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }

        private fun imageDimensions(bytes: ByteArray, ext: String): Pair<Int, Int>? {
            if (ext != "webp") return runCatching {
                ByteArrayInputStream(bytes).use { ImageIO.read(it) }?.let { it.width to it.height }
            }.getOrNull()
            return runCatching {
                val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
                when (chunk) {
                    "VP8X" -> {
                        val b = ByteBuffer.wrap(bytes, 24, 6).order(ByteOrder.LITTLE_ENDIAN)
                        (1 + (b.get().toInt() and 255 or ((b.get().toInt() and 255) shl 8) or ((b.get().toInt() and 255) shl 16))) to
                            (1 + (b.get().toInt() and 255 or ((b.get().toInt() and 255) shl 8) or ((b.get().toInt() and 255) shl 16)))
                    }
                    "VP8 " -> {
                        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        (b.getShort(26).toInt() and 0x3fff) to (b.getShort(28).toInt() and 0x3fff)
                    }
                    "VP8L" -> {
                        val bits = ByteBuffer.wrap(bytes, 21, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        (1 + (bits and 0x3fff)) to (1 + ((bits shr 14) and 0x3fff))
                    }
                    else -> null
                }
            }.getOrNull()
        }

        private fun sniffFile(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            if (String(bytes, 0, minOf(bytes.size, 5), Charsets.US_ASCII) == "%PDF-") return "pdf"
            if (bytes.size >= 80 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "PK\u0003\u0004" &&
                littleShort(bytes, 26) == 8 && String(bytes, 30, 8, Charsets.US_ASCII) == "mimetype"
            ) {
                val at = 38 + littleShort(bytes, 28)
                if (at >= 0 && at + 20 <= bytes.size && String(bytes, at, 20, Charsets.US_ASCII) == "application/epub+zip") return "epub"
            }
            val text = decodeUtf8(bytes)
            if (text != null && bytes.none { it == 0.toByte() }) {
                val head = text.take(4096)
                if (Regex("<FictionBook[\\s>]").containsMatchIn(head) &&
                    !Regex("<!(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(head) &&
                    !Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(text)
                ) return "fb2"
                if (!Regex("[\\u0000-\\u0008\\u000b\\u000e-\\u001f\\u007f]").containsMatchIn(text) &&
                    !Regex("<\\s*(!doctype|html|head|body|script|svg|iframe|object|embed|meta|\\?xml)\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(head)
                ) return "txt"
            }
            return null
        }

        private fun littleShort(bytes: ByteArray, offset: Int) =
            (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
