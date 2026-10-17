package com.code5150.dto

import com.code5150.entity.IssueEntry
import com.code5150.entity.MediaRef
import com.code5150.entity.Spec
import java.time.OffsetDateTime

/*
 * Ответы публичного API — поле в поле как в docs/api.md
 * и frontend/src/lib/api/types.ts. Меняются только вместе с контрактом.
 */

/** Страница списка. page — с единицы. */
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
    val totalPages: Int,
)

data class NewsSummaryDto(
    val slug: String,
    val title: String,
    val announce: String,
    val publishedAt: OffsetDateTime,
    val cover: MediaRef?,
)

data class NewsItemDto(
    val slug: String,
    val title: String,
    val announce: String,
    val publishedAt: OffsetDateTime,
    val cover: MediaRef?,
    val lead: String?,
    val body: String,
)

data class ArticleSummaryDto(
    val slug: String,
    val title: String,
    val announce: String,
    val publishedAt: OffsetDateTime,
    val cover: MediaRef?,
    val tags: List<String>?,
)

data class ArticleDto(
    val slug: String,
    val title: String,
    val announce: String,
    val publishedAt: OffsetDateTime,
    val cover: MediaRef?,
    val tags: List<String>?,
    val summary: List<String>?,
    val body: String,
    val references: List<String>?,
)

data class NewspaperIssueDto(
    val slug: String,
    val number: String,
    val title: String,
    val publishedAt: OffsetDateTime,
    val cover: MediaRef?,
    val contents: List<IssueEntry>,
    val pdfUrl: String?,
)

data class BookDto(
    val slug: String,
    val title: String,
    val description: String,
    val cover: MediaRef?,
    val buyUrl: String?,
)

data class MagazineIssueDto(
    val slug: String,
    val title: String,
    val cover: MediaRef?,
    val specs: List<Spec>,
    val contents: String,
    val buyUrl: String?,
)

data class VideoDto(
    val slug: String,
    val title: String,
    val url: String,
    val preview: MediaRef?,
)

data class WalletDto(val network: String, val label: String?, val address: String)

data class SupportDto(val wallets: List<WalletDto>, val shopUrl: String?)

data class RelatedItemDto(val kind: String, val slug: String, val title: String, val announce: String)

data class SlugsDto(val news: List<String>, val articles: List<String>, val newspaper: List<String>)

data class HomeDto(
    val news: List<NewsSummaryDto>,
    val articles: List<ArticleSummaryDto>,
    val issues: List<NewspaperIssueDto>,
    val videos: List<VideoDto>,
)

/** Тело ошибки: { error, message }. */
data class ErrorDto(val error: String, val message: String)
