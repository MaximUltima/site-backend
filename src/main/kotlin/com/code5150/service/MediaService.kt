package com.code5150.service

import com.code5150.dto.MediaDto
import com.code5150.dto.MediaMetaForm
import com.code5150.dto.PageDto
import com.code5150.entity.Media
import com.code5150.repository.MediaRepository
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.quarkus.panache.common.Sort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream

/**
 * Медиатека: файлы кладутся в media.dir/ГГГГ/ММ/<uuid>.<расширение>,
 * раздаёт их прокси по адресу media.url (docs/api.md, «Картинки»).
 * Имена неизменяемые, поэтому прокси может кэшировать их навсегда.
 */
@ApplicationScoped
class MediaService(
    private val repository: MediaRepository,
    @ConfigProperty(name = "media.dir") private val dir: String,
    @ConfigProperty(name = "media.url") private val baseUrl: String,
) {
    /** Что принимаем и с каким расширением сохраняем. */
    private val types = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/avif" to "avif",
        "application/pdf" to "pdf",
    )

    suspend fun upload(
        source: Path,
        declaredType: String?,
        fileName: String?,
        meta: MediaMetaForm,
        width: Int?,
        height: Int?,
    ): MediaDto {
        val mime = detectType(declaredType, fileName)
            ?: throw BadRequestException("Неподдерживаемый тип файла: принимаем JPEG, PNG, GIF, WebP, AVIF и PDF")
        val extension = types.getValue(mime)

        // Файловые операции блокирующие — уводим их с event loop.
        val stored = withContext(Dispatchers.IO) {
            val size = Files.size(source)
            if (size == 0L) throw BadRequestException("Пустой файл")
            val (w, h) = measure(source, mime) ?: (width to height)
            val month = LocalDate.now()
            val relative = "%d/%02d/%s.%s".format(month.year, month.monthValue, UUID.randomUUID(), extension)
            val target = Path.of(dir).resolve(relative)
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            Stored(relative, size, w, h)
        }

        // PDF без размеров — это нормально; картинка без размеров — нет.
        if (mime.startsWith("image/") && (stored.width == null || stored.height == null)) {
            Files.deleteIfExists(Path.of(dir).resolve(stored.path))
            throw BadRequestException("Не удалось определить размеры картинки: передайте width и height")
        }

        val media = Media().apply {
            path = stored.path
            this.mime = mime
            this.width = stored.width ?: 0
            this.height = stored.height ?: 0
            sizeBytes = stored.size
            alt = meta.alt.trim()
            caption = meta.caption?.trim()?.takeIf { it.isNotEmpty() }
            credit = meta.credit?.trim()?.takeIf { it.isNotEmpty() }
        }
        return Panache.withTransaction { repository.persist(media) }.awaitSuspending().toDto()
    }

    suspend fun list(page: Int?, size: Int?): PageDto<MediaDto> =
        paginate({ repository.findAll(Sort.descending("id")) }, page, size, 50) { it.toDto() }

    suspend fun updateMeta(id: Long, meta: MediaMetaForm): MediaDto =
        Panache.withTransaction {
            repository.findById(id).onItem().ifNull().failWith { NotFoundException("Нет файла $id") }
                .map { media ->
                    media!!.alt = meta.alt.trim()
                    media.caption = meta.caption?.trim()?.takeIf { it.isNotEmpty() }
                    media.credit = meta.credit?.trim()?.takeIf { it.isNotEmpty() }
                    media
                }
        }.awaitSuspending().toDto()

    /**
     * Удаляет запись и файл. Материалы, где картинка уже вставлена,
     * хранят свою копию адреса — у них она просто перестанет открываться.
     */
    suspend fun delete(id: Long) {
        val media = read { repository.findById(id).orNull() } ?: throw NotFoundException("Нет файла $id")
        Panache.withTransaction { repository.deleteById(id) }.awaitSuspending()
        withContext(Dispatchers.IO) { Files.deleteIfExists(Path.of(dir).resolve(media.path)) }
    }

    fun Media.toDto() = MediaDto(
        id = id,
        src = baseUrl.trimEnd('/') + "/" + path,
        alt = alt,
        width = width,
        height = height,
        caption = caption,
        credit = credit,
        mime = mime,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
    )

    private fun detectType(declared: String?, fileName: String?): String? {
        val clean = declared?.substringBefore(';')?.trim()?.lowercase()
        if (clean != null && clean in types) return clean
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return types.entries.firstOrNull { it.value == ext || (ext == "jpeg" && it.value == "jpg") }?.key
    }

    /** Размеры картинки. WebP и AVIF ImageIO не читает — для них размеры передаёт админка. */
    private fun measure(file: Path, mime: String): Pair<Int?, Int?>? {
        if (!mime.startsWith("image/")) return null
        return try {
            FileImageInputStream(file.toFile()).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class Stored(val path: String, val size: Long, val width: Int?, val height: Int?)
}
