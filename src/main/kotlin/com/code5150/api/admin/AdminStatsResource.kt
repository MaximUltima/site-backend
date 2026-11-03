package com.code5150.api.admin

import com.code5150.service.StatsFilter
import com.code5150.service.StatsService
import com.code5150.service.VersionedContentStore
import com.code5150.service.contentJson
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.LocalDate

@Path("/api/admin/v1/stats")
@Produces(MediaType.APPLICATION_JSON)
class AdminStatsResource(private val stats: StatsService) {
    @GET
    @Path("/totals")
    suspend fun totals(
        @QueryParam("from") from: String?, @QueryParam("to") to: String?,
        @QueryParam("kind") kind: String?, @QueryParam("lang") lang: String?, @QueryParam("slug") slug: String?,
    ) = stats.totals(filter(from, to, kind, lang, slug))

    @GET
    @Path("/series")
    suspend fun series(
        @QueryParam("from") from: String?, @QueryParam("to") to: String?,
        @QueryParam("kind") kind: String?, @QueryParam("lang") lang: String?, @QueryParam("slug") slug: String?,
    ) = stats.series(filter(from, to, kind, lang, slug))

    @GET
    @Path("/top")
    suspend fun top(
        @QueryParam("from") from: String?, @QueryParam("to") to: String?,
        @QueryParam("kind") kind: String?, @QueryParam("lang") lang: String?, @QueryParam("slug") slug: String?,
        @QueryParam("order") order: String?, @QueryParam("sort") sort: String?, @QueryParam("limit") limit: Int?,
    ) = stats.top(filter(from, to, kind, lang, slug), order ?: sort ?: "views", (limit ?: 50).coerceIn(1, 500))

    @GET
    @Path("/spark")
    suspend fun spark(
        @QueryParam("from") from: String?, @QueryParam("to") to: String?,
        @QueryParam("kind") kind: String?, @QueryParam("lang") lang: String?, @QueryParam("slug") slug: String?,
        @QueryParam("items") items: String?,
    ): Map<String, Map<String, Long>> {
        val keys = items.orEmpty().split(',', ';').map { it.trim() }.filter { it.matches(Regex("^(articles|news)/[a-z0-9-]+$")) }.toSet()
        return stats.spark(filter(from, to, kind, lang, slug), keys)
    }

    @GET
    @Path("/first-day")
    suspend fun firstDay(): Response = Response.ok(contentJson.valueToTree<com.fasterxml.jackson.databind.JsonNode>(stats.firstDay())).build()

    @GET
    @Path("/downloads")
    suspend fun downloads(@QueryParam("from") from: String?, @QueryParam("to") to: String?) =
        stats.downloads(filter(from, to, null, null, null))

    @GET
    @Path("/downloads-of")
    suspend fun downloadsOf(@QueryParam("id") id: Long) = stats.downloadsOf(id)

    @GET
    @Path("/downloads-of/{id}")
    suspend fun downloadsOfPath(@PathParam("id") id: Long) = stats.downloadsOf(id)

    private fun filter(from: String?, to: String?, kind: String?, lang: String?, slug: String?) = StatsFilter(
        from = from?.takeIf { it.isNotBlank() } ?: "1970-01-01",
        to = to?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString(),
        kind = kind?.takeIf { it in StatsService.STAT_KINDS }.orEmpty(),
        lang = lang?.takeIf { it in VersionedContentStore.LANGS }.orEmpty(),
        slug = slug.orEmpty(),
    )
}
