package com.code5150.dto

/**
 * Результат поиска статей с метаданными пагинации.
 *
 * @param items срез текущей страницы
 * @param page номер страницы (0-based)
 * @param size размер страницы
 * @param total общее число совпадений (без учёта страницы)
 * @param totalPages общее число страниц
 */
data class ArticleSearchResult(
    val items: List<ArticleDTO>,
    val page: Int,
    val size: Int,
    val total: Long,
    val totalPages: Int
)
