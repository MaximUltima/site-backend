package com.code5150.dto

import com.code5150.entity.IssueEntry
import com.code5150.entity.MediaRef
import com.code5150.entity.Spec
import com.code5150.entity.Status
import java.time.OffsetDateTime

/*
 * Формы админского API: одна и та же форма приходит на создание и правку
 * и уходит в ответ. id, createdAt и updatedAt заполняет сервер.
 * slug можно не задавать — сервер сделает его из заголовка.
 */

/** Общие поля всех материалов. */
interface ContentForm {
    val id: Long?
    val slug: String?
    val title: String
    val status: Status
    val publishedAt: OffsetDateTime?
    val sourceUrl: String?
}

data class NewsForm(
    override val id: Long? = null,
    override val slug: String? = null,
    override val title: String,
    override val status: Status = Status.DRAFT,
    override val publishedAt: OffsetDateTime? = null,
    override val sourceUrl: String? = null,
    val announce: String = "",
    val lead: String? = null,
    val body: String = "",
    val cover: MediaRef? = null,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) : ContentForm

data class ArticleForm(
    override val id: Long? = null,
    override val slug: String? = null,
    override val title: String,
    override val status: Status = Status.DRAFT,
    override val publishedAt: OffsetDateTime? = null,
    override val sourceUrl: String? = null,
    val announce: String = "",
    val summary: List<String>? = null,
    val body: String = "",
    val references: List<String>? = null,
    val tags: List<String>? = null,
    val cover: MediaRef? = null,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) : ContentForm

data class NewspaperIssueForm(
    override val id: Long? = null,
    override val slug: String? = null,
    override val title: String,
    override val status: Status = Status.DRAFT,
    override val publishedAt: OffsetDateTime? = null,
    override val sourceUrl: String? = null,
    val number: String,
    val contents: List<IssueEntry> = emptyList(),
    val cover: MediaRef? = null,
    val pdfUrl: String? = null,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) : ContentForm

data class BookForm(
    override val id: Long? = null,
    override val slug: String? = null,
    override val title: String,
    override val status: Status = Status.DRAFT,
    override val publishedAt: OffsetDateTime? = null,
    override val sourceUrl: String? = null,
    val description: String = "",
    val cover: MediaRef? = null,
    val buyUrl: String? = null,
    val position: Int = 0,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) : ContentForm

data class MagazineIssueForm(
    override val id: Long? = null,
    override val slug: String? = null,
    override val title: String,
    override val status: Status = Status.DRAFT,
    override val publishedAt: OffsetDateTime? = null,
    override val sourceUrl: String? = null,
    val cover: MediaRef? = null,
    val specs: List<Spec> = emptyList(),
    val contents: String = "",
    val buyUrl: String? = null,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) : ContentForm

data class VideoForm(
    override val id: Long? = null,
    override val slug: String? = null,
    override val title: String,
    override val status: Status = Status.DRAFT,
    override val publishedAt: OffsetDateTime? = null,
    override val sourceUrl: String? = null,
    val url: String,
    val preview: MediaRef? = null,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
) : ContentForm

/** Страница «Поддержать» целиком: список кошельков заменяется полностью. */
data class SupportForm(
    val wallets: List<WalletDto> = emptyList(),
    val shopUrl: String? = null,
)

/** Файл медиатеки: и ответ на загрузку, и форма правки подписей. */
data class MediaDto(
    val id: Long?,
    val src: String,
    val alt: String,
    val width: Int,
    val height: Int,
    val caption: String?,
    val credit: String?,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: OffsetDateTime?,
)

data class MediaMetaForm(val alt: String = "", val caption: String? = null, val credit: String? = null)
