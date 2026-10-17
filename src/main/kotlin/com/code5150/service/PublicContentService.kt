package com.code5150.service

import com.code5150.dto.ArticleDto
import com.code5150.dto.ArticleSummaryDto
import com.code5150.dto.BookDto
import com.code5150.dto.HomeDto
import com.code5150.dto.MagazineIssueDto
import com.code5150.dto.NewsItemDto
import com.code5150.dto.NewsSummaryDto
import com.code5150.dto.NewspaperIssueDto
import com.code5150.dto.PageDto
import com.code5150.dto.RelatedItemDto
import com.code5150.dto.SlugsDto
import com.code5150.dto.SupportDto
import com.code5150.dto.VideoDto
import com.code5150.dto.toDto
import com.code5150.dto.toSummary
import com.code5150.entity.Setting
import com.code5150.repository.ArticleRepository
import com.code5150.repository.BookRepository
import com.code5150.repository.MagazineIssueRepository
import com.code5150.repository.NewsRepository
import com.code5150.repository.NewspaperIssueRepository
import com.code5150.repository.SettingRepository
import com.code5150.repository.VideoRepository
import com.code5150.repository.WalletRepository
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheQuery
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import kotlin.math.ceil

/**
 * Публичная часть: только опубликованное с наступившей датой.
 * Каждый метод — один эндпоинт из docs/api.md, раздел 3.
 */
@ApplicationScoped
class PublicContentService(
    private val articles: ArticleRepository,
    private val news: NewsRepository,
    private val newspaper: NewspaperIssueRepository,
    private val books: BookRepository,
    private val magazine: MagazineIssueRepository,
    private val videos: VideoRepository,
    private val wallets: WalletRepository,
    private val settings: SettingRepository,
) {

    suspend fun home(): HomeDto = HomeDto(
        news = read { news.published().range(0, 2).list() }.map { it.toSummary() },
        articles = read { articles.published().range(0, 3).list() }.map { it.toSummary() },
        issues = read { newspaper.published().range(0, 2).list() }.map { it.toDto() },
        videos = read { videos.published().range(0, 1).list() }.map { it.toDto() },
    )

    suspend fun newsList(page: Int?, size: Int?): PageDto<NewsSummaryDto> =
        paginate(news.published(), page, size, DEFAULT_FEED_SIZE) { it.toSummary() }

    suspend fun newsItem(slug: String): NewsItemDto? =
        read { news.findPublishedBySlug(slug) }?.toDto()

    suspend fun articleList(page: Int?, size: Int?, tag: String?): PageDto<ArticleSummaryDto> {
        if (tag.isNullOrBlank()) {
            return paginate(articles.published(), page, size, DEFAULT_FEED_SIZE) { it.toSummary() }
        }
        // Теги лежат JSON-строкой, поэтому фильтр — по подстроке с кавычками: "тег".
        val quoted = "%" + columnJsonString(tag) + "%"
        val query = articles.find(
            "status = ?1 and publishedAt <= ?2 and tags like ?3",
            Sort.descending("publishedAt"),
            com.code5150.entity.Status.PUBLISHED,
            java.time.OffsetDateTime.now(),
            quoted,
        )
        return paginate(query, page, size, DEFAULT_FEED_SIZE) { it.toSummary() }
    }

    suspend fun article(slug: String): ArticleDto? =
        read { articles.findPublishedBySlug(slug) }?.toDto()

    suspend fun search(q: String?, page: Int?, size: Int?): PageDto<ArticleSummaryDto> =
        paginate(articles.searchPublished(q.orEmpty()), page, size, DEFAULT_FEED_SIZE) { it.toSummary() }

    /**
     * «Читайте также»: свежие статьи и новости, кроме текущего материала.
     * Если у текущей статьи есть теги, вперёд идут материалы с общими тегами.
     */
    suspend fun related(slug: String, limit: Int?): List<RelatedItemDto> {
        val count = (limit ?: 3).also { if (it !in 1..10) throw BadRequestException("limit должен быть от 1 до 10") }
        val source = read { articles.findPublishedBySlug(slug) }
        val sourceTags = source?.tags.orEmpty().toSet()

        val candidates = read { articles.published().range(0, POOL - 1).list() }
            .filter { it.slug != slug }
            .map { Candidate(RelatedItemDto("article", it.slug, it.title, it.announce), it.publishedAt, it.tags.orEmpty()) } +
            read { news.published().range(0, POOL - 1).list() }
                .filter { it.slug != slug }
                .map { Candidate(RelatedItemDto("news", it.slug, it.title, it.announce), it.publishedAt, emptyList()) }

        return candidates
            .sortedWith(
                compareByDescending<Candidate> { c -> c.tags.count { it in sourceTags } }
                    .thenByDescending { it.publishedAt },
            )
            .take(count)
            .map { it.item }
    }

    suspend fun newspaperList(page: Int?, size: Int?): PageDto<NewspaperIssueDto> =
        paginate(newspaper.published(), page, size, DEFAULT_NEWSPAPER_SIZE) { it.toDto() }

    suspend fun newspaperIssue(slug: String): NewspaperIssueDto? =
        read { newspaper.findPublishedBySlug(slug) }?.toDto()

    suspend fun bookList(): List<BookDto> =
        read { books.published(Sort.ascending("position").and("id")).list() }.map { it.toDto() }

    suspend fun magazineList(): List<MagazineIssueDto> =
        read { magazine.published().list() }.map { it.toDto() }

    suspend fun videoList(): List<VideoDto> =
        read { videos.published().list() }.map { it.toDto() }

    suspend fun support(): SupportDto = SupportDto(
        wallets = read { wallets.listAll(Sort.ascending("position").and("id")) }.map { it.toDto() },
        shopUrl = read { settings.findById(Setting.SHOP_URL) }?.value?.takeIf { it.isNotBlank() },
    )

    suspend fun slugs(): SlugsDto = SlugsDto(
        news = read { news.publishedSlugs() },
        articles = read { articles.publishedSlugs() },
        newspaper = read { newspaper.publishedSlugs() },
    )

    private data class Candidate(
        val item: RelatedItemDto,
        val publishedAt: java.time.OffsetDateTime,
        val tags: List<String>,
    )

    companion object {
        const val DEFAULT_FEED_SIZE = 20
        const val DEFAULT_NEWSPAPER_SIZE = 10
        const val MAX_PAGE_SIZE = 100

        /** Сколько свежих материалов каждого вида перебирать для «Читайте также». */
        private const val POOL = 30
    }
}

/** Запрос в отдельной сессии: публичные чтения не меняют данные. */
internal suspend fun <T> read(block: () -> Uni<T>): T = Panache.withSession { block() }.awaitSuspending()

/** Тег в том виде, как он лежит внутри JSON-массива: в кавычках и с экранированием. */
private fun columnJsonString(value: String): String =
    com.code5150.entity.columnJson.writeValueAsString(value)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Страница списка по контракту: page с единицы, size от 1 до 100,
 * за пределами списка — пустая страница, totalPages не меньше 1.
 */
internal suspend fun <E : Any, D> paginate(
    query: PanacheQuery<E>,
    page: Int?,
    size: Int?,
    defaultSize: Int,
    map: (E) -> D,
): PageDto<D> {
    val safePage = page ?: 1
    val safeSize = size ?: defaultSize
    if (safePage < 1) throw BadRequestException("page считается с единицы")
    if (safeSize !in 1..PublicContentService.MAX_PAGE_SIZE) {
        throw BadRequestException("size должен быть от 1 до ${PublicContentService.MAX_PAGE_SIZE}")
    }
    val (items, total) = Panache.withSession {
        query.page(Page.of(safePage - 1, safeSize)).list().flatMap { list ->
            query.count().map { total -> list to total }
        }
    }.awaitSuspending()
    val totalPages = maxOf(1, ceil(total.toDouble() / safeSize).toInt())
    return PageDto(items.map(map), safePage, safeSize, total, totalPages)
}
