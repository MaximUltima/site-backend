package com.code5150.service

import com.code5150.api.ConflictException
import com.code5150.dto.ArticleForm
import com.code5150.dto.BookForm
import com.code5150.dto.ContentForm
import com.code5150.dto.MagazineIssueForm
import com.code5150.dto.NewsForm
import com.code5150.dto.NewspaperIssueForm
import com.code5150.dto.PageDto
import com.code5150.dto.VideoForm
import com.code5150.entity.Article
import com.code5150.entity.Book
import com.code5150.entity.Content
import com.code5150.entity.MagazineIssue
import com.code5150.entity.MediaRef
import com.code5150.entity.News
import com.code5150.entity.NewspaperIssue
import com.code5150.entity.Status
import com.code5150.entity.Video
import com.code5150.repository.ArticleRepository
import com.code5150.repository.BookRepository
import com.code5150.repository.ContentRepository
import com.code5150.repository.MagazineIssueRepository
import com.code5150.repository.NewsRepository
import com.code5150.repository.NewspaperIssueRepository
import com.code5150.repository.VideoRepository
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.quarkus.panache.common.Sort
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException

/**
 * Правила одного вида материала для админки: как форма ложится на сущность
 * и обратно, как проверяется, в каком порядке выводится список.
 */
class ContentAdapter<E : Content, F : ContentForm>(
    val kind: ContentKind,
    val repository: ContentRepository<E>,
    val create: () -> E,
    val apply: (E, F) -> Unit,
    val toForm: (E) -> F,
    val validate: (F) -> Unit = {},
    val sort: Sort = Sort.descending("publishedAt").and("id", Sort.Direction.Descending),
)

/**
 * Админское API: создание, правка, удаление любого материала.
 * После изменений, которые видны на сайте, сбрасывает кэш фронта.
 */
@ApplicationScoped
class AdminContentService(private val revalidator: Revalidator) {

    suspend fun <E : Content, F : ContentForm> list(
        adapter: ContentAdapter<E, F>,
        page: Int?,
        size: Int?,
        status: Status?,
        q: String?,
    ): PageDto<F> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (status != null) {
            params += status
            conditions += "status = ?${params.size}"
        }
        if (!q.isNullOrBlank()) {
            params += "%" + q.trim().lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            conditions += "(lower(title) like ?${params.size} or lower(slug) like ?${params.size})"
        }
        val query = {
            if (conditions.isEmpty()) {
                adapter.repository.findAll(adapter.sort)
            } else {
                adapter.repository.find(conditions.joinToString(" and "), adapter.sort, *params.toTypedArray())
            }
        }
        return paginate(query, page, size, PublicContentService.DEFAULT_FEED_SIZE, adapter.toForm)
    }

    suspend fun <E : Content, F : ContentForm> get(adapter: ContentAdapter<E, F>, id: Long): F =
        adapter.toForm(find(adapter, id))

    suspend fun <E : Content, F : ContentForm> create(adapter: ContentAdapter<E, F>, form: F): F {
        validateCommon(form)
        adapter.validate(form)
        val slug = resolveSlug(adapter, form, currentId = null)
        checkSource(adapter, form.sourceUrl, currentId = null)

        val entity = adapter.create().also {
            adapter.apply(it, form)
            applyCommon(it, form, slug)
        }
        val saved = write { adapter.repository.persist(entity) }
        if (saved.isVisible()) revalidator.revalidate(adapter.kind.tags(saved.slug))
        return adapter.toForm(saved)
    }

    suspend fun <E : Content, F : ContentForm> update(adapter: ContentAdapter<E, F>, id: Long, form: F): F {
        validateCommon(form)
        adapter.validate(form)
        val before = find(adapter, id)
        val wasVisible = before.isVisible()
        val oldSlug = before.slug
        val slug = resolveSlug(adapter, form, currentId = id)
        checkSource(adapter, form.sourceUrl, currentId = id)

        val saved = write {
            adapter.repository.findById(id).onItem().ifNull().failWith { NotFoundException("Нет материала $id") }
                .map { entity ->
                    adapter.apply(entity!!, form)
                    applyCommon(entity, form, slug)
                    entity
                }
        }
        if (wasVisible || saved.isVisible()) revalidator.revalidate(adapter.kind.tags(oldSlug, saved.slug))
        return adapter.toForm(saved)
    }

    suspend fun <E : Content, F : ContentForm> delete(adapter: ContentAdapter<E, F>, id: Long) {
        val entity = find(adapter, id)
        write { adapter.repository.deleteById(id) }
        if (entity.isVisible()) revalidator.revalidate(adapter.kind.tags(entity.slug))
    }

    private suspend fun <E : Content, F : ContentForm> find(adapter: ContentAdapter<E, F>, id: Long): E =
        read { adapter.repository.findById(id).orNull() } ?: throw NotFoundException("Нет материала $id")

    /** Слаг из формы или из заголовка; занятый явный — ошибка, занятый сгенерированный — с номером. */
    private suspend fun <E : Content, F : ContentForm> resolveSlug(
        adapter: ContentAdapter<E, F>,
        form: F,
        currentId: Long?,
    ): String {
        val explicit = form.slug?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            if (!Slugs.isValid(explicit)) {
                throw BadRequestException("slug: только латиница в нижнем регистре, цифры и дефисы")
            }
            val owner = read { adapter.repository.findBySlug(explicit) }
            if (owner != null && owner.id != currentId) throw ConflictException("slug «$explicit» уже занят")
            return explicit
        }
        val base = Slugs.fromTitle(form.title)
        var candidate = base
        var n = 2
        while (true) {
            val owner = read { adapter.repository.findBySlug(candidate) }
            if (owner == null || owner.id == currentId) return candidate
            candidate = "$base-$n"
            n += 1
        }
    }

    private suspend fun <E : Content, F : ContentForm> checkSource(
        adapter: ContentAdapter<E, F>,
        sourceUrl: String?,
        currentId: Long?,
    ) {
        val url = sourceUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val owner = read { adapter.repository.findBySourceUrl(url) }
        if (owner != null && owner.id != currentId) {
            throw ConflictException("Этот источник уже импортирован: материал ${owner.id}")
        }
    }

    private fun validateCommon(form: ContentForm) {
        if (form.title.isBlank()) throw BadRequestException("title не может быть пустым")
        if (form.title.length > 256) throw BadRequestException("title длиннее 256 знаков")
        form.sourceUrl?.let { requireUrl("sourceUrl", it) }
    }

    private fun applyCommon(entity: Content, form: ContentForm, slug: String) {
        entity.slug = slug
        entity.title = form.title.trim()
        entity.status = form.status
        entity.publishedAt = form.publishedAt ?: entity.publishedAt
        entity.sourceUrl = form.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Виден ли материал на сайте прямо сейчас — от этого зависит, сбрасывать ли кэш. */
    private fun Content.isVisible() = status == Status.PUBLISHED

    /** Запись в транзакции; нарушение уникальности — 409, а не 500. */
    private suspend fun <T> write(block: () -> Uni<T>): T =
        try {
            Panache.withTransaction { block() }.awaitSuspending()
        } catch (e: Throwable) {
            if (generateSequence(e) { it.cause }.any { it.isUniqueViolation() }) {
                throw ConflictException("slug или источник уже заняты")
            }
            throw e
        }

    private fun Throwable.isUniqueViolation(): Boolean =
        (this is io.vertx.pgclient.PgException && sqlState == "23505") ||
            message?.contains("duplicate key value violates unique constraint") == true

    companion object {
        fun requireUrl(field: String, value: String) {
            if (!Regex("^https?://\\S+$").matches(value.trim())) {
                throw BadRequestException("$field: нужна ссылка вида https://…")
            }
        }

        fun requireMedia(field: String, media: MediaRef?) {
            if (media == null) return
            if (media.src.isBlank()) throw BadRequestException("$field.src пустой")
            if (media.width <= 0 || media.height <= 0) {
                throw BadRequestException("$field: width и height обязательны — без них прыгает раскладка")
            }
        }
    }
}

/** Как каждый вид материала ложится на форму. */
@ApplicationScoped
class Adapters(
    articles: ArticleRepository,
    news: NewsRepository,
    newspaper: NewspaperIssueRepository,
    books: BookRepository,
    magazine: MagazineIssueRepository,
    videos: VideoRepository,
) {
    val news = ContentAdapter<News, NewsForm>(
        kind = ContentKind.NEWS,
        repository = news,
        create = ::News,
        apply = { e, f ->
            e.announce = f.announce
            e.lead = f.lead?.takeIf { it.isNotBlank() }
            e.body = f.body
            e.cover = f.cover
        },
        toForm = { e ->
            NewsForm(
                e.id, e.slug, e.title, e.status, e.publishedAt, e.sourceUrl,
                e.announce, e.lead, e.body, e.cover, e.createdAt, e.updatedAt,
            )
        },
        validate = { AdminContentService.requireMedia("cover", it.cover) },
    )

    val articles = ContentAdapter<Article, ArticleForm>(
        kind = ContentKind.ARTICLES,
        repository = articles,
        create = ::Article,
        apply = { e, f ->
            e.announce = f.announce
            e.summary = f.summary?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            e.body = f.body
            e.references = f.references?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            e.tags = f.tags?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }
            e.cover = f.cover
        },
        toForm = { e ->
            ArticleForm(
                e.id, e.slug, e.title, e.status, e.publishedAt, e.sourceUrl,
                e.announce, e.summary, e.body, e.references, e.tags, e.cover, e.createdAt, e.updatedAt,
            )
        },
        validate = { AdminContentService.requireMedia("cover", it.cover) },
    )

    val newspaper = ContentAdapter<NewspaperIssue, NewspaperIssueForm>(
        kind = ContentKind.NEWSPAPER,
        repository = newspaper,
        create = ::NewspaperIssue,
        apply = { e, f ->
            e.number = f.number.trim()
            e.contents = f.contents.filter { it.title.isNotBlank() }
            e.cover = f.cover
            e.pdfUrl = f.pdfUrl?.trim()?.takeIf { it.isNotEmpty() }
        },
        toForm = { e ->
            NewspaperIssueForm(
                e.id, e.slug, e.title, e.status, e.publishedAt, e.sourceUrl,
                e.number, e.contents.orEmpty(), e.cover, e.pdfUrl, e.createdAt, e.updatedAt,
            )
        },
        validate = { f ->
            if (f.number.isBlank()) throw BadRequestException("number не может быть пустым")
            AdminContentService.requireMedia("cover", f.cover)
            f.pdfUrl?.takeIf { it.isNotBlank() }?.let { AdminContentService.requireUrl("pdfUrl", it) }
            f.contents.forEach { entry ->
                entry.href?.let { href ->
                    if (!href.startsWith("/")) throw BadRequestException("contents.href: адрес на сайте, вида /articles/slug")
                }
            }
        },
    )

    val books = ContentAdapter<Book, BookForm>(
        kind = ContentKind.BOOKS,
        repository = books,
        create = ::Book,
        apply = { e, f ->
            e.description = f.description
            e.cover = f.cover
            e.buyUrl = f.buyUrl?.trim()?.takeIf { it.isNotEmpty() }
            e.position = f.position
        },
        toForm = { e ->
            BookForm(
                e.id, e.slug, e.title, e.status, e.publishedAt, e.sourceUrl,
                e.description, e.cover, e.buyUrl, e.position, e.createdAt, e.updatedAt,
            )
        },
        validate = { f ->
            AdminContentService.requireMedia("cover", f.cover)
            f.buyUrl?.takeIf { it.isNotBlank() }?.let { AdminContentService.requireUrl("buyUrl", it) }
        },
        sort = Sort.ascending("position").and("id"),
    )

    val magazine = ContentAdapter<MagazineIssue, MagazineIssueForm>(
        kind = ContentKind.MAGAZINE,
        repository = magazine,
        create = ::MagazineIssue,
        apply = { e, f ->
            e.cover = f.cover
            e.specs = f.specs.filter { it.label.isNotBlank() }
            e.contents = f.contents
            e.buyUrl = f.buyUrl?.trim()?.takeIf { it.isNotEmpty() }
        },
        toForm = { e ->
            MagazineIssueForm(
                e.id, e.slug, e.title, e.status, e.publishedAt, e.sourceUrl,
                e.cover, e.specs.orEmpty(), e.contents, e.buyUrl, e.createdAt, e.updatedAt,
            )
        },
        validate = { f ->
            AdminContentService.requireMedia("cover", f.cover)
            f.buyUrl?.takeIf { it.isNotBlank() }?.let { AdminContentService.requireUrl("buyUrl", it) }
        },
    )

    val videos = ContentAdapter<Video, VideoForm>(
        kind = ContentKind.VIDEOS,
        repository = videos,
        create = ::Video,
        apply = { e, f ->
            e.url = f.url.trim()
            e.preview = f.preview
        },
        toForm = { e ->
            VideoForm(
                e.id, e.slug, e.title, e.status, e.publishedAt, e.sourceUrl,
                e.url, e.preview, e.createdAt, e.updatedAt,
            )
        },
        validate = { f ->
            AdminContentService.requireUrl("url", f.url)
            AdminContentService.requireMedia("preview", f.preview)
        },
    )
}
