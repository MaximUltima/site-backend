package com.code5150

import com.code5150.dto.ArticleDTO
import com.code5150.mapper.toDto
import com.code5150.service.ArticleService
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.RestResponse

@Path("/")
class ArticleController(
    private val articleService: ArticleService
) {

    @GET
    @Path("/hello")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun hello() = "Hello from Quarkus REST"

    @PUT
    @Path("/create-article")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    suspend fun createArticle(article: ArticleDTO): RestResponse<ArticleDTO> {
        return RestResponse.ok(articleService.addArticle(article).toDto())
    }

    @GET
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    suspend fun getArticleById(@PathParam("id") id: Long): RestResponse<ArticleDTO?> {
        return articleService.findById(id)?.let { RestResponse.ok(it.toDto()) } ?: RestResponse.notFound()
    }
}