package com.code5150.repository

import com.code5150.entity.Article
import com.code5150.entity.Book
import com.code5150.entity.Content
import com.code5150.entity.MagazineIssue
import com.code5150.entity.Media
import com.code5150.entity.News
import com.code5150.entity.NewspaperIssue
import com.code5150.entity.Setting
import com.code5150.entity.Status
import com.code5150.entity.Video
import com.code5150.entity.Wallet
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Sort
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime

/** Общие запросы для всего контента: опубликованное, по слагу, все слаги. */
interface ContentRepository<E : Content> : PanacheRepository<E> {

    /** Только опубликованное и с наступившей датой — то, что видит сайт. */
    fun published(sort: Sort = Sort.descending("publishedAt").and("id", Sort.Direction.Descending)): PanacheQuery<E> =
        find("status = ?1 and publishedAt <= ?2", sort, Status.PUBLISHED, OffsetDateTime.now())

    fun findPublishedBySlug(slug: String): Uni<E?> =
        find("slug = ?1 and status = ?2 and publishedAt <= ?3", slug, Status.PUBLISHED, OffsetDateTime.now())
            .firstResult()

    fun findBySlug(slug: String): Uni<E?> = find("slug", slug).firstResult()

    fun findBySourceUrl(url: String): Uni<E?> = find("sourceUrl", url).firstResult()

    fun publishedSlugs(): Uni<List<String>> = published().project(SlugOnly::class.java).list().map { rows -> rows.map { it.slug } }
}

/** Проекция для списка слагов: не тянуть тела материалов ради одного поля. */
@io.quarkus.runtime.annotations.RegisterForReflection
data class SlugOnly(val slug: String)

@ApplicationScoped
class ArticleRepository : ContentRepository<Article> {
    /**
     * Регистронезависимый поиск по заголовку и тексту среди опубликованного.
     * Символы LIKE (%, _, \) экранируются и ищутся буквально.
     */
    fun searchPublished(query: String): PanacheQuery<Article> {
        val pattern = "%${escapeLike(query.trim().lowercase())}%"
        return find(
            "status = ?1 and publishedAt <= ?2 and (lower(title) like ?3 escape '\\' or lower(body) like ?3 escape '\\')",
            Sort.descending("publishedAt"),
            Status.PUBLISHED,
            OffsetDateTime.now(),
            pattern,
        )
    }

    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

@ApplicationScoped
class NewsRepository : ContentRepository<News>

@ApplicationScoped
class NewspaperIssueRepository : ContentRepository<NewspaperIssue>

@ApplicationScoped
class BookRepository : ContentRepository<Book>

@ApplicationScoped
class MagazineIssueRepository : ContentRepository<MagazineIssue>

@ApplicationScoped
class VideoRepository : ContentRepository<Video>

@ApplicationScoped
class MediaRepository : PanacheRepository<Media>

@ApplicationScoped
class WalletRepository : PanacheRepository<Wallet>

@ApplicationScoped
class SettingRepository : PanacheRepositoryBase<Setting, String>
