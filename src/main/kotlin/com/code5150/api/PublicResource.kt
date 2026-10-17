package com.code5150.api

import com.code5150.service.PublicContentService
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/**
 * Публичное API сайта: только чтение, без авторизации.
 * Контракт — docs/api.md, раздел 3; фронт ходит сюда из src/lib/api/httpSource.ts.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class PublicResource(private val content: PublicContentService) {

    @GET
    @Path("/home")
    suspend fun home() = content.home()

    @GET
    @Path("/news")
    suspend fun news(@QueryParam("page") page: Int?, @QueryParam("size") size: Int?) =
        content.newsList(page, size)

    @GET
    @Path("/news/{slug}")
    suspend fun newsItem(@PathParam("slug") slug: String) =
        content.newsItem(slug) ?: throw NotFoundException("Новости $slug нет")

    @GET
    @Path("/articles")
    suspend fun articles(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("tag") tag: String?,
    ) = content.articleList(page, size, tag)

    @GET
    @Path("/articles/{slug}")
    suspend fun article(@PathParam("slug") slug: String) =
        content.article(slug) ?: throw NotFoundException("Статьи $slug нет")

    @GET
    @Path("/related/{slug}")
    suspend fun related(@PathParam("slug") slug: String, @QueryParam("limit") limit: Int?) =
        content.related(slug, limit)

    @GET
    @Path("/newspaper")
    suspend fun newspaper(@QueryParam("page") page: Int?, @QueryParam("size") size: Int?) =
        content.newspaperList(page, size)

    @GET
    @Path("/newspaper/{slug}")
    suspend fun newspaperIssue(@PathParam("slug") slug: String) =
        content.newspaperIssue(slug) ?: throw NotFoundException("Выпуска $slug нет")

    @GET
    @Path("/books")
    suspend fun books() = content.bookList()

    @GET
    @Path("/magazine")
    suspend fun magazine() = content.magazineList()

    @GET
    @Path("/videos")
    suspend fun videos() = content.videoList()

    @GET
    @Path("/support")
    suspend fun support() = content.support()

    @GET
    @Path("/slugs")
    suspend fun slugs() = content.slugs()

    /** Поиск по статьям — пока не используется сайтом (открытый вопрос 7). */
    @GET
    @Path("/search")
    suspend fun search(
        @QueryParam("q") q: String?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ) = content.search(q, page, size)
}
