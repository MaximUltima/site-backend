package com.code5150.service

import com.code5150.dto.ArticleDTO
import com.code5150.dto.ArticleSearchResult
import com.code5150.entity.Article
import com.code5150.mapper.toDto
import com.code5150.mapper.toEntity
import com.code5150.repository.ArticleRepository
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.quarkus.panache.common.Page
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import kotlin.math.ceil

@ApplicationScoped
class ArticleService(
    private val repository: ArticleRepository
) {

    suspend fun addArticle(article: ArticleDTO): Article {
        return Panache.withTransaction {
            repository.persist(article.toEntity())
        }.awaitSuspending()
    }

    suspend fun findByTitle(title: String): Article? {
        return Panache.withTransaction {
            repository.findByTitle(title)
        }.awaitSuspending()
    }

    suspend fun findById(id: Long): Article? {
        return Panache.withTransaction { repository.findById(id) }.awaitSuspending()
    }

    /**
     * Регистронезависимый поиск статей по подстроке в [query] с пагинацией.
     * Невалидные [page]/[size] нормализуются: page<0 -> 0, size<=0 -> 20, size>100 -> 100.
     */
    suspend fun search(query: String, page: Int, size: Int): ArticleSearchResult {
        val safePage = if (page < 0) 0 else page
        val safeSize = when {
            size <= 0 -> DEFAULT_PAGE_SIZE
            size > MAX_PAGE_SIZE -> MAX_PAGE_SIZE
            else -> size
        }
        val panacheQuery = repository.search(query, Page.of(safePage, safeSize))
        return Panache.withTransaction {
            panacheQuery.count().flatMap { total ->
                panacheQuery.list().map { items ->
                    val totalPages = if (total == 0L) 0 else ceil(total.toDouble() / safeSize).toInt()
                    ArticleSearchResult(items.map { it.toDto() }, safePage, safeSize, total, totalPages)
                }
            }
        }.awaitSuspending()
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }
}
