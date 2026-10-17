package com.code5150.api.admin

import com.code5150.dto.ArticleForm
import com.code5150.dto.BookForm
import com.code5150.dto.MagazineIssueForm
import com.code5150.dto.NewsForm
import com.code5150.dto.NewspaperIssueForm
import com.code5150.dto.VideoForm
import com.code5150.entity.Status
import com.code5150.service.Adapters
import com.code5150.service.AdminContentService
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.ResponseStatus

/**
 * Админское API материалов: /api/admin/v1/{вид}. Доступ — по токену (AdminAuthFilter).
 *
 *   GET    /{вид}?page&size&status&q   список, включая черновики
 *   GET    /{вид}/{id}                 материал
 *   POST   /{вид}                      создать (201)
 *   PUT    /{вид}/{id}                 сохранить
 *   DELETE /{вид}/{id}                 удалить (204)
 *
 * Методы расписаны по видам явно, а не обобщённым классом: так у каждого
 * тела запроса конкретный тип, и OpenAPI получается честный.
 */
@Path("/api/admin/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AdminContentResource(
    private val service: AdminContentService,
    private val adapters: Adapters,
) {

    // news

    @GET
    @Path("/news")
    suspend fun listNews(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("status") status: String?,
        @QueryParam("q") q: String?,
    ) = service.list(adapters.news, page, size, parseStatus(status), q)

    @GET
    @Path("/news/{id}")
    suspend fun getNews(@PathParam("id") id: Long) = service.get(adapters.news, id)

    @POST
    @Path("/news")
    @ResponseStatus(201)
    suspend fun createNews(form: NewsForm) = service.create(adapters.news, form)

    @PUT
    @Path("/news/{id}")
    suspend fun updateNews(@PathParam("id") id: Long, form: NewsForm) = service.update(adapters.news, id, form)

    @DELETE
    @Path("/news/{id}")
    @ResponseStatus(204)
    suspend fun deleteNews(@PathParam("id") id: Long) = service.delete(adapters.news, id)

    // articles

    @GET
    @Path("/articles")
    suspend fun listArticle(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("status") status: String?,
        @QueryParam("q") q: String?,
    ) = service.list(adapters.articles, page, size, parseStatus(status), q)

    @GET
    @Path("/articles/{id}")
    suspend fun getArticle(@PathParam("id") id: Long) = service.get(adapters.articles, id)

    @POST
    @Path("/articles")
    @ResponseStatus(201)
    suspend fun createArticle(form: ArticleForm) = service.create(adapters.articles, form)

    @PUT
    @Path("/articles/{id}")
    suspend fun updateArticle(@PathParam("id") id: Long, form: ArticleForm) = service.update(adapters.articles, id, form)

    @DELETE
    @Path("/articles/{id}")
    @ResponseStatus(204)
    suspend fun deleteArticle(@PathParam("id") id: Long) = service.delete(adapters.articles, id)

    // newspaper

    @GET
    @Path("/newspaper")
    suspend fun listNewspaperIssue(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("status") status: String?,
        @QueryParam("q") q: String?,
    ) = service.list(adapters.newspaper, page, size, parseStatus(status), q)

    @GET
    @Path("/newspaper/{id}")
    suspend fun getNewspaperIssue(@PathParam("id") id: Long) = service.get(adapters.newspaper, id)

    @POST
    @Path("/newspaper")
    @ResponseStatus(201)
    suspend fun createNewspaperIssue(form: NewspaperIssueForm) = service.create(adapters.newspaper, form)

    @PUT
    @Path("/newspaper/{id}")
    suspend fun updateNewspaperIssue(@PathParam("id") id: Long, form: NewspaperIssueForm) = service.update(adapters.newspaper, id, form)

    @DELETE
    @Path("/newspaper/{id}")
    @ResponseStatus(204)
    suspend fun deleteNewspaperIssue(@PathParam("id") id: Long) = service.delete(adapters.newspaper, id)

    // books

    @GET
    @Path("/books")
    suspend fun listBook(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("status") status: String?,
        @QueryParam("q") q: String?,
    ) = service.list(adapters.books, page, size, parseStatus(status), q)

    @GET
    @Path("/books/{id}")
    suspend fun getBook(@PathParam("id") id: Long) = service.get(adapters.books, id)

    @POST
    @Path("/books")
    @ResponseStatus(201)
    suspend fun createBook(form: BookForm) = service.create(adapters.books, form)

    @PUT
    @Path("/books/{id}")
    suspend fun updateBook(@PathParam("id") id: Long, form: BookForm) = service.update(adapters.books, id, form)

    @DELETE
    @Path("/books/{id}")
    @ResponseStatus(204)
    suspend fun deleteBook(@PathParam("id") id: Long) = service.delete(adapters.books, id)

    // magazine

    @GET
    @Path("/magazine")
    suspend fun listMagazineIssue(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("status") status: String?,
        @QueryParam("q") q: String?,
    ) = service.list(adapters.magazine, page, size, parseStatus(status), q)

    @GET
    @Path("/magazine/{id}")
    suspend fun getMagazineIssue(@PathParam("id") id: Long) = service.get(adapters.magazine, id)

    @POST
    @Path("/magazine")
    @ResponseStatus(201)
    suspend fun createMagazineIssue(form: MagazineIssueForm) = service.create(adapters.magazine, form)

    @PUT
    @Path("/magazine/{id}")
    suspend fun updateMagazineIssue(@PathParam("id") id: Long, form: MagazineIssueForm) = service.update(adapters.magazine, id, form)

    @DELETE
    @Path("/magazine/{id}")
    @ResponseStatus(204)
    suspend fun deleteMagazineIssue(@PathParam("id") id: Long) = service.delete(adapters.magazine, id)

    // videos

    @GET
    @Path("/videos")
    suspend fun listVideo(
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("status") status: String?,
        @QueryParam("q") q: String?,
    ) = service.list(adapters.videos, page, size, parseStatus(status), q)

    @GET
    @Path("/videos/{id}")
    suspend fun getVideo(@PathParam("id") id: Long) = service.get(adapters.videos, id)

    @POST
    @Path("/videos")
    @ResponseStatus(201)
    suspend fun createVideo(form: VideoForm) = service.create(adapters.videos, form)

    @PUT
    @Path("/videos/{id}")
    suspend fun updateVideo(@PathParam("id") id: Long, form: VideoForm) = service.update(adapters.videos, id, form)

    @DELETE
    @Path("/videos/{id}")
    @ResponseStatus(204)
    suspend fun deleteVideo(@PathParam("id") id: Long) = service.delete(adapters.videos, id)

    private fun parseStatus(value: String?): Status? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { Status.valueOf(it.uppercase()) }.getOrElse {
                throw BadRequestException("status: draft, published или archived")
            }
        }
}
