package com.code5150.repository

import com.code5150.entity.Article
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ArticleRepository: PanacheRepository<Article> {

    fun findByTitle(title: String): Uni<Article?> {
        return find("title", title).firstResult()
    }

    /**
     * Регистронезависимый подстрочный поиск по [title] или [content] с пагинацией.
     * Символы LIKE (%, _, \) экранируются, поэтому ищутся буквально.
     * Пустая/пробельная [query] возвращает все статьи на странице.
     */
    fun search(query: String, page: Page): PanacheQuery<Article> {
        val pattern = "%${escapeLike(query.trim().lowercase())}%"
        return find(
            "lower(title) like ?1 escape '\\' or lower(content) like ?2 escape '\\'",
            pattern,
            pattern
        ).page(page)
    }

    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

}
