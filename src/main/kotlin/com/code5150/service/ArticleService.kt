package com.code5150.service

import com.code5150.dto.ArticleDTO
import com.code5150.entity.Article
import com.code5150.mapper.toEntity
import com.code5150.repository.ArticleRepository
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

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
}