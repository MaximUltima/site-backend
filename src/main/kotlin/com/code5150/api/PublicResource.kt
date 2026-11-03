package com.code5150.api

import com.code5150.service.DownloadService
import com.code5150.service.PublicContentService
import com.code5150.service.StatsService
import com.code5150.service.VersionedContentStore
import com.code5150.service.contentJson
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest
import java.util.Base64
import java.util.Optional
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class PublicResource(
    private val content: PublicContentService,
    private val store: VersionedContentStore,
    private val files: DownloadService,
    private val stats: StatsService,
    @ConfigProperty(name = "site.revalidate-secret") private val secret: Optional<String>,
) {
    @GET @Path("/home")
    suspend fun home(@QueryParam("lang") lang: String?) = content.home(lang(lang))

    @GET @Path("/news")
    suspend fun news(@QueryParam("lang") lang: String?, @QueryParam("page") page: Int?, @QueryParam("size") size: Int?) =
        content.list("news", lang(lang), page, size)

    @GET @Path("/news/{slug}")
    suspend fun newsItem(@PathParam("slug") slug: String, @QueryParam("lang") lang: String?) =
        content.one("news", slug, lang(lang)) ?: throw ContractException(404, "not_found")

    @GET @Path("/articles")
    suspend fun articles(
        @QueryParam("lang") lang: String?, @QueryParam("page") page: Int?, @QueryParam("size") size: Int?,
        @QueryParam("tag") tag: String?,
    ) = content.list("articles", lang(lang), page, size, tag)

    @GET @Path("/articles/{slug}")
    suspend fun article(@PathParam("slug") slug: String, @QueryParam("lang") lang: String?) =
        content.one("articles", slug, lang(lang)) ?: throw ContractException(404, "not_found")

    @GET @Path("/categories")
    suspend fun categories(@QueryParam("lang") lang: String?) = content.categories(lang(lang))

    @GET @Path("/categories/{slug}")
    suspend fun category(
        @PathParam("slug") slug: String, @QueryParam("lang") lang: String?,
        @QueryParam("page") page: Int?, @QueryParam("size") size: Int?,
    ) = content.taxon("categories", slug, lang(lang), page, size) ?: throw ContractException(404, "not_found")

    @GET @Path("/authors/{slug}")
    suspend fun author(
        @PathParam("slug") slug: String, @QueryParam("lang") lang: String?,
        @QueryParam("page") page: Int?, @QueryParam("size") size: Int?,
    ) = content.taxon("authors", slug, lang(lang), page, size) ?: throw ContractException(404, "not_found")

    @GET @Path("/related/{slug}")
    suspend fun related(
        @PathParam("slug") slug: String, @QueryParam("lang") lang: String?,
        @QueryParam("kind") kind: String?, @QueryParam("limit") limit: Int?,
    ) = content.related(slug, lang(lang), kind, limit)

    @GET @Path("/next/{slug}")
    suspend fun next(
        @PathParam("slug") slug: String, @QueryParam("lang") lang: String?, @QueryParam("kind") kind: String?,
    ) = content.next(slug, lang(lang), kind) ?: throw ContractException(404, "not_found")

    @GET @Path("/newspaper")
    suspend fun newspaper(@QueryParam("lang") lang: String?, @QueryParam("page") page: Int?, @QueryParam("size") size: Int?) =
        content.list("newspaper", lang(lang), page, size)

    @GET @Path("/newspaper/{slug}")
    suspend fun newspaperIssue(@PathParam("slug") slug: String, @QueryParam("lang") lang: String?) =
        content.simpleOne("newspaper", slug, lang(lang)) ?: throw ContractException(404, "not_found")

    @GET @Path("/books")
    suspend fun books(@QueryParam("lang") lang: String?) = content.books(lang(lang))

    @GET @Path("/magazine")
    suspend fun magazine(@QueryParam("lang") lang: String?) = content.array("magazine", lang(lang))

    @GET @Path("/videos")
    suspend fun videos(@QueryParam("lang") lang: String?) = content.array("videos", lang(lang))

    @GET @Path("/support")
    suspend fun support(@QueryParam("lang") lang: String?) = content.support(lang(lang))

    @GET @Path("/slugs")
    suspend fun slugs(@QueryParam("lang") lang: String?) = content.slugs(lang(lang))

    @GET @Path("/search")
    suspend fun search(
        @QueryParam("q") q: String?, @QueryParam("lang") lang: String?, @QueryParam("kind") kind: String?,
        @QueryParam("limit") limit: Int?, @QueryParam("offset") offset: Int?,
    ) = content.search(q, lang(lang), kind, limit, offset)

    @GET
    @Path("/preview")
    suspend fun preview(@HeaderParam("x-preview-token") token: String?): Response {
        val payload = verifyPreview(secret.orElse(""), token) ?: throw ContractException(404, "not_found")
        val id = (payload["v"] as? Number)?.toLong() ?: throw ContractException(404, "not_found")
        val version = store.version(id) ?: throw ContractException(404, "not_found")
        if (version.kind !in setOf("news", "articles") || version.kind != payload["k"] ||
            version.slug != payload["s"] || version.lang != payload["l"]
        ) throw ContractException(404, "not_found")
        return Response.ok(content.full(version.kind, version.data, version.lang))
            .header("Cache-Control", "no-store").build()
    }

    @POST
    @Path("/view")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun view(data: Map<String, Any?>): Response {
        if (contentJson.writeValueAsBytes(data).size > 512) throw ContractException(400, "error", "Слишком большое тело")
        val kind = data["kind"]?.toString()
        val slug = data["slug"]?.toString()
        val lang = data["lang"]?.toString()
        val signal = data["signal"]?.toString()
        if (kind in StatsService.STAT_KINDS && slug != null && SLUG_RE.matches(slug) &&
            lang in VersionedContentStore.LANGS && signal in StatsService.SIGNALS &&
            store.one(kind!!, slug, lang!!) != null
        ) stats.hit(kind, slug, lang, signal!!)
        return Response.noContent().build()
    }

    private fun lang(value: String?): String {
        VersionedContentStore.checkLang(value)
        return value ?: VersionedContentStore.DEFAULT_LANG
    }

    private fun verifyPreview(secret: String, token: String?): Map<String, Any?>? {
        if (secret.isEmpty() || token == null || token.length > 600) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
                .doFinal("preview:${parts[0]}".toByteArray()),
        )
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[1].toByteArray())) return null
        return runCatching {
            val data = contentJson.readValue(
                Base64.getUrlDecoder().decode(parts[0]),
                contentJson.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Any::class.java),
            ) as Map<String, Any?>
            val now = System.currentTimeMillis()
            val expires = (data["e"] as? Number)?.toLong() ?: return null
            if ((data["v"] as? Number) == null || expires <= now || expires > now + 600_000) null else data
        }.getOrNull()
    }

    companion object {
        private val SLUG_RE = Regex("^[a-z0-9][a-z0-9-]{0,119}$")
    }
}

@Path("/")
class MediaResource(private val files: DownloadService, private val stats: StatsService) {
    @GET @Path("/media/{name}")
    suspend fun media(@PathParam("name") name: String) = mediaResponse(name, false)

    @HEAD @Path("/media/{name}")
    suspend fun mediaHead(@PathParam("name") name: String) = mediaResponse(name, true)

    private suspend fun mediaResponse(name: String, head: Boolean): Response {
        val media = files.media(name) ?: return Response.status(404).entity("not found").type(MediaType.TEXT_PLAIN).build()
        return Response.ok(if (head) null else media.first)
            .type(media.second).header("Content-Length", media.first.size)
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .header("Cross-Origin-Resource-Policy", "cross-origin")
            .header("Content-Disposition", "inline")
            .header("Cache-Control", "public, max-age=31536000, immutable")
            .header("Referrer-Policy", "no-referrer").build()
    }

    @GET @Path("/files/{id}/{name}")
    suspend fun file(@PathParam("id") id: Long, @PathParam("name") name: String, @HeaderParam("Range") range: String?) =
        fileResponse(id, name, range, false)

    @HEAD @Path("/files/{id}/{name}")
    suspend fun fileHead(@PathParam("id") id: Long, @PathParam("name") name: String, @HeaderParam("Range") range: String?) =
        fileResponse(id, name, range, true)

    private suspend fun fileResponse(id: Long, name: String, range: String?, head: Boolean): Response {
        val file = files.liveFile(id) ?: return Response.status(404).build()
        if (name != "${file.name}.${file.ext}" || file.ext !in DownloadService.TYPES) return Response.status(404).build()
        val bytes = files.bytes(file) ?: return Response.status(404).build()
        var start = 0
        var end = bytes.size - 1
        var status = 200
        if (range != null) {
            val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(range)
            if (match != null && (match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty())) {
                val first = match.groupValues[1]
                val last = match.groupValues[2]
                if (first.isNotEmpty()) {
                    start = first.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
                    if (last.isNotEmpty()) end = (last.toLongOrNull() ?: Long.MAX_VALUE).coerceAtMost((bytes.size - 1).toLong()).toInt()
                } else if (last.isNotEmpty()) {
                    start = maxOf(0, bytes.size - (last.toLongOrNull() ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                if (start > end || start >= bytes.size) {
                    return fileHeaders(Response.status(416), file, bytes.size)
                        .header("Content-Range", "bytes */${bytes.size}").build()
                }
                status = 206
            }
        }
        if (!head && start == 0) stats.download(id)
        val body = if (head) null else bytes.copyOfRange(start, end + 1)
        return fileHeaders(Response.status(status), file, bytes.size)
            .entity(body).header("Content-Length", end - start + 1)
            .let { if (status == 206) it.header("Content-Range", "bytes $start-$end/${bytes.size}") else it }.build()
    }

    private fun fileHeaders(builder: Response.ResponseBuilder, file: com.code5150.service.DownloadFile, size: Int) = builder
        .type(DownloadService.TYPES[file.ext]!!.first)
        .header("Content-Disposition", "attachment; filename=\"${file.name}.${file.ext}\"")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header("Cross-Origin-Resource-Policy", "cross-origin")
        .header("Accept-Ranges", "bytes")
}

@Path("/health")
class HealthResource {
    @GET fun health() = mapOf("ok" to true)
}
