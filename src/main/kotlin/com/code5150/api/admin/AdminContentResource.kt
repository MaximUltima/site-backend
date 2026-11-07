package com.code5150.api.admin

import com.code5150.api.ContractException
import com.code5150.service.DownloadService
import com.code5150.service.Revalidator
import com.code5150.service.VersionedContentStore
import com.code5150.service.VersionRow
import com.code5150.service.contentJson
import com.code5150.service.isoAt
import com.code5150.service.isoNow
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Path("/api/admin/v1")
@Produces(MediaType.APPLICATION_JSON)
class AdminContentResource(
    private val store: VersionedContentStore,
    private val files: DownloadService,
    private val revalidator: Revalidator,
) {
    @PUT
    @Path("/support")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun support(body: MutableMap<String, Any?>, @QueryParam("actor") actor: String?): Response {
        checkFields(body)
        body["publishedAt"] = publishedAt(body)
        val version = store.save("support", "support", body, "published", null, actor(actor))
        revalidator.revalidate(listOf("support"))
        return Response.ok(version.toMap()).build()
    }

    @POST
    @Path("/{kind}")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun save(
        @PathParam("kind") kind: String,
        body: MutableMap<String, Any?>,
        @QueryParam("actor") actor: String?,
    ): Response {
        VersionedContentStore.checkKind(kind)
        val status = status(body.remove("status"))
        val bodyLang = body.remove("lang")?.toString()
        VersionedContentStore.checkLang(bodyLang)
        val lang = bodyLang ?: VersionedContentStore.DEFAULT_LANG
        val slugValue = body["slug"]
        if (slugValue != null && (slugValue !is String || !SLUG_RE.matches(slugValue))) {
            throw ContractException(400, "bad_slug", "Неверный slug")
        }
        val slug = slugValue as? String ?: UUID.randomUUID().toString().take(8)
        body["slug"] = slug
        val source = body["source"]
        if (source != null && (source !is String || !SOURCE_RE.matches(source))) {
            throw ContractException(400, "bad_source", "Неверный ключ источника")
        }
        validateRelated(kind, body["related"])
        checkFields(body)
        if (source is String) {
            val seen = store.findBySource(kind, slug, lang, source)
            // Идемпотентны только повторные отправки того же материала с тем же статусом:
            // правка черновика или его публикация обязаны создавать новую версию,
            // даже если source (у импортированных материалов) переносится из версии в версию.
            if (seen != null && seen.status == status && sameSubmission(seen, body))
                return Response.ok(seen.toMap().also { it["idempotent"] = true }).build()
        }
        body["publishedAt"] = publishedAt(body)
        if (body.containsKey("review")) body["review"] = body["review"] == true || body["review"] == "true"
        val version = store.save(kind, slug, body, status, lang, actor(actor))
        if (status == "published") revalidate(kind, slug)
        return Response.status(201).entity(version.toMap()).build()
    }

    @GET
    @Path("/{kind}/{slug}/versions")
    suspend fun history(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("lang") lang: String?,
    ): List<Map<String, Any?>> {
        VersionedContentStore.checkLang(lang)
        return store.history(kind, slug, lang ?: VersionedContentStore.DEFAULT_LANG)
    }

    @GET
    @Path("/versions/{id}")
    suspend fun version(@PathParam("id") id: Long): Map<String, Any?> =
        store.version(id)?.toMap() ?: throw ContractException(404, "not_found")

    @POST
    @Path("/versions/{id}/publish")
    suspend fun publish(@PathParam("id") id: Long, @QueryParam("actor") actor: String?): Map<String, Any?> {
        val version = store.publish(id, actor(actor)) ?: throw ContractException(404, "not_found")
        revalidate(version.kind, version.slug)
        return version.toMap()
    }

    @POST
    @Path("/{kind}/{slug}/unpublish")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun unpublish(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("lang") lang: String?,
        @QueryParam("actor") actor: String?,
        body: Map<String, Any?>?,
    ): Map<String, Any?> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        val current = store.one(kind, slug, lang)
        if (current == null || current["fallback"] == true) throw ContractException(404, "not_found")
        val version = store.save(kind, slug, current, "unpublished", lang, actor(actor))
        revalidate(kind, slug)
        return version.toMap()
    }

    /**
     * Удаление материала целиком: новой версией `unpublished` с `data.deleted = true`
     * для каждого языка. Материал пропадает с сайта и из списков админки, но версии
     * остаются в журнале — удаление видно и восстановимо через историю.
     */
    @POST
    @Path("/{kind}/{slug}/delete")
    suspend fun delete(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("actor") actor: String?,
    ): Map<String, Any?> {
        VersionedContentStore.checkKind(kind)
        val result = store.delete(kind, slug, actor(actor))
        revalidate(result.kind, result.slug)
        return linkedMapOf(
            "kind" to result.kind, "slug" to result.slug, "deleted" to true, "langs" to result.langs,
            "versions" to result.versions.map { it.id },
        )
    }

    /** Перенос статьи в новости и наоборот: копии всех версий, надгробие, расписание и ссылки. */
    @POST
    @Path("/{kind}/{slug}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun move(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("actor") actor: String?,
        body: Map<String, Any?>?,
    ): Map<String, Any?> {
        val to = body?.get("to")?.toString()
        if (kind != "articles" && kind != "news")
            throw ContractException(400, "bad_move", "Переносить можно только статьи и новости.")
        if (to != "articles" && to != "news")
            throw ContractException(400, "bad_move", "Целевой раздел — статьи или новости.")
        if (to == kind) throw ContractException(400, "bad_move", "Материал уже в этом разделе.")
        val result = store.move(kind, slug, to, actor(actor))
        revalidate(result.from, result.slug)
        revalidate(result.kind, result.slug)
        return linkedMapOf(
            "from" to result.from, "kind" to result.kind, "slug" to result.slug,
            "moved" to true, "versions" to result.versions.map { it.id },
        )
    }

    @GET
    @Path("/docs/{kind}")
    suspend fun docs(@PathParam("kind") kind: String, @QueryParam("lang") lang: String?): Map<String, Any?> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        return mapOf("lang" to lang, "items" to store.docs(kind, lang))
    }

    @GET
    @Path("/live/{kind}")
    suspend fun live(@PathParam("kind") kind: String, @QueryParam("lang") lang: String?): Map<String, Any?> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        return mapOf("lang" to lang, "items" to store.live(kind, lang))
    }

    @GET
    @Path("/one/{kind}/{slug}")
    suspend fun one(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("lang") lang: String?,
    ): Map<String, Any?> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        val data = store.one(kind, slug, lang)
        val doc = store.docs(kind, lang).firstOrNull { it["slug"] == slug }
        return mapOf("data" to data, "doc" to doc)
    }

    @GET
    @Path("/review")
    suspend fun review(@QueryParam("lang") lang: String?): Map<String, Any?> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        val items = VersionedContentStore.KINDS.flatMap { kind ->
            store.docs(kind, lang).filter { it["status"] == "draft" && (it["data"] as? Map<*, *>)?.get("review") == true }
                .map {
                    linkedMapOf<String, Any?>(
                        "kind" to kind, "slug" to it["slug"], "title" to (it["data"] as? Map<*, *>)?.get("title").orEmptyString(),
                        "id" to it["id"], "at" to it["created_at"], "actor" to null,
                    )
                }
        }
        return mapOf("lang" to lang, "items" to items)
    }

    /**
     * Поиск материалов для подборок («Ещё по теме»): по вхождению в заголовок или анонс,
     * включая черновики. Сначала опубликованные, затем свежие.
     */
    @GET
    @Path("/search")
    suspend fun search(
        @QueryParam("q") q: String?,
        @QueryParam("kind") kind: String?,
        @QueryParam("limit") limit: Int?,
    ): List<Map<String, Any?>> {
        val query = q.orEmpty().trim().lowercase().replace('ё', 'е').take(100)
        if (query.isEmpty()) return emptyList()
        val kinds = if (kind == "articles" || kind == "news") listOf(kind) else listOf("articles", "news")
        val n = (limit ?: 20).coerceIn(1, 50)
        return kinds
            .flatMap { k ->
                store.docs(k, VersionedContentStore.DEFAULT_LANG).mapNotNull { doc ->
                    val data = doc["data"] as? Map<*, *> ?: return@mapNotNull null
                    if (data["deleted"] == true) return@mapNotNull null
                    val haystack = (data["title"].orEmptyString() + " " + data["announce"].orEmptyString())
                        .lowercase().replace('ё', 'е')
                    if (!haystack.contains(query)) return@mapNotNull null
                    mapOf(
                        "kind" to k, "slug" to doc["slug"], "title" to data["title"],
                        "announce" to data["announce"], "status" to doc["status"],
                        "live" to (doc["live"] == true), "id" to doc["id"],
                    )
                }
            }
            .sortedWith(
                compareByDescending<Map<String, Any?>> { it["live"] == true }
                    .thenByDescending { (it["id"] as? Number)?.toLong() ?: 0L },
            )
            .take(n)
    }

    @POST
    @Path("/{kind}/{slug}/schedule")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun schedule(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("lang") lang: String?,
        @QueryParam("actor") actor: String?,
        body: Map<String, Any?>,
    ): Map<String, Any?> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        val at = parseDate(body["at"]?.toString())
        val doc = store.docs(kind, lang).firstOrNull { it["slug"] == slug }
            ?: throw ContractException(404, "not_found", "Сначала сохраните черновик.")
        val schedule = store.schedule((doc["id"] as Number).toLong(), at, actor(actor))
            ?: throw ContractException(409, "not_scheduled")
        return mapOf("kind" to kind, "slug" to slug, "lang" to lang, "at" to schedule.publishAt)
    }

    @POST
    @Path("/{kind}/{slug}/unschedule")
    suspend fun unschedule(
        @PathParam("kind") kind: String,
        @PathParam("slug") slug: String,
        @QueryParam("lang") lang: String?,
    ): Map<String, Any> {
        val lang = lang ?: VersionedContentStore.DEFAULT_LANG
        VersionedContentStore.checkLang(lang)
        if (!store.unschedule(kind, slug, lang)) throw ContractException(404, "not_found")
        return mapOf("unscheduled" to true)
    }

    @GET
    @Path("/scheduled")
    suspend fun scheduled() = mapOf("items" to store.scheduled().map { it.toMap() })

    @POST
    @Path("/schedule/runDue")
    suspend fun runDue(): Map<String, Any?> {
        val published = store.runDue()
        published.forEach {
            val version = (it["version"] as? Map<*, *>) ?: return@forEach
            revalidate(version["kind"].toString(), version["slug"].toString())
        }
        return mapOf("items" to published)
    }

    @GET
    @Path("/last-id")
    suspend fun lastId() = mapOf("id" to store.lastId())

    @GET
    @Path("/versions")
    suspend fun versions(@QueryParam("since") since: Long?, @QueryParam("limit") limit: Int?): Map<String, Any?> {
        val safeLimit = (limit ?: 200).coerceIn(1, 500)
        return mapOf("items" to store.versionsSince(since ?: 0, safeLimit).map { it.toMap() }, "last" to store.lastId())
    }

    @GET
    @Path("/journal/verify")
    suspend fun verify() = store.verifyChain()

    @POST
    @Path("/image")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    suspend fun image(body: ByteArray): Response = Response.status(201).entity(files.addImage(body)).build()

    @POST
    @Path("/file")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    suspend fun file(
        body: ByteArray,
        @QueryParam("name") name: String?,
        @QueryParam("license") license: String?,
        @QueryParam("source") source: String?,
        @QueryParam("actor") actor: String?,
    ): Response {
        val saved = files.addFile(body, name.orEmpty().take(200), license, source?.take(500), actor(actor))
        return Response.status(201).entity(
            mapOf("id" to saved.id, "name" to saved.name, "ext" to saved.ext, "sha256" to saved.sha256, "size" to saved.size),
        ).build()
    }

    @GET
    @Path("/file/{id}")
    suspend fun file(@PathParam("id") id: Long) = files.file(id)?.toMap() ?: throw ContractException(404, "not_found")

    @GET
    @Path("/live-file/{id}")
    suspend fun liveFile(@PathParam("id") id: Long) = files.liveFile(id)?.toMap() ?: throw ContractException(404, "not_found")

    @POST
    @Path("/file/{id}/meta")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun fileMeta(@PathParam("id") id: Long, body: Map<String, Any?>): Map<String, Any?> {
        val old = files.file(id) ?: throw ContractException(404, "not_found")
        val license = if (body.containsKey("license")) body["license"]?.toString() else old.license
        val source = if (body.containsKey("source")) body["source"]?.toString() else old.source
        val saved = files.setFileMeta(id, license, source)
        return mapOf("id" to id, "license" to saved.license, "source" to saved.source)
    }

    @POST
    @Path("/file/{id}/hide")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun hideFile(@PathParam("id") id: Long, body: Map<String, Any?>?): Map<String, Any> {
        val saved = files.hideFile(id, body?.get("reason")?.toString().orEmpty())
        return mapOf("id" to saved.id, "status" to saved.status)
    }

    private fun revalidate(kind: String, slug: String) {
        val tags = mutableListOf(kind, "$kind:$slug")
        if (kind in setOf("news", "articles", "newspaper", "videos")) tags += "home"
        revalidator.revalidate(tags)
    }

    /**
     * Совпадает ли присланное с данными последней версии с тем же source.
     * Сравниваем только ключи запроса: сервис сам добавляет publishedAt, нормализует
     * review и т.п. — всё это на статус «повторной отправки» не влияет.
     */
    private fun sameSubmission(seen: VersionRow, body: Map<String, Any?>): Boolean {
        val stored = seen.data
        return body.all { (key, value) -> stored[key] == value }
    }

    private fun actor(value: String?): String = value?.takeIf { ACTOR_RE.matches(it) } ?: "api"

    private fun status(value: Any?): String = when (value?.toString()?.lowercase()) {
        "published" -> "published"
        "unpublished" -> "unpublished"
        else -> "draft"
    }

    private fun publishedAt(data: Map<String, Any?>): String {
        val value = data["publishedAt"] as? String
        return if (value != null && value.length <= 40 && runCatching { OffsetDateTime.parse(value) }.isSuccess) value else isoNow()
    }

    private fun parseDate(value: String?): Instant {
        val parsed = runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        }
        return parsed ?: throw ContractException(400, "bad_date", "Дата в формате ISO 8601")
    }

    private fun checkFields(data: Map<String, Any?>) {
        for ((key, value) in data) {
            val max = FIELD_MAX[key] ?: FIELD_MAX_DEFAULT
            val length = if (value is String) value.length else contentJson.writeValueAsString(value).length
            if (length > max) throw ContractException(400, "too_long", "Поле «$key» длиннее $max знаков.")
        }
    }

    /**
     * «Ещё по теме»: ручная подборка только у статей и новостей, до MAX_RELATED.
     * Элементы — ссылка на материал {kind: articles|news, slug} или внешняя
     * {url: https://…, title, announce?}; повторов нет. Существование материалов
     * не проверяем: цель может быть ещё черновиком; недоступное отсеет выдача /related.
     */
    private fun validateRelated(kind: String, raw: Any?) {
        if (raw == null) return
        if (kind != "articles" && kind != "news")
            throw ContractException(400, "bad_related", "Поле «related» есть только у статей и новостей.")
        val list = raw as? List<*> ?: throw ContractException(400, "bad_related", "«Ещё по теме»: ожидается список.")
        if (list.size > MAX_RELATED)
            throw ContractException(400, "bad_related", "«Ещё по теме»: не больше $MAX_RELATED материалов.")
        val seen = HashSet<String>()
        for (item in list) {
            val map = item as? Map<*, *>
                ?: throw ContractException(400, "bad_related", "«Ещё по теме»: ссылка вида {kind, slug} или {url, title}.")
            val url = map["url"]
            if (url != null) {
                if (url !is String || url.length > 500 || !EXTERNAL_URL_RE.matches(url))
                    throw ContractException(400, "bad_related", "«Ещё по теме»: внешняя ссылка — только https://.")
                val title = map["title"]
                if (title !is String || title.isBlank() || title.length > 300)
                    throw ContractException(400, "bad_related", "«Ещё по теме»: у внешней ссылки нужно название.")
                val announce = map["announce"]
                if (announce != null && (announce !is String || announce.length > 1000))
                    throw ContractException(400, "bad_related", "«Ещё по теме»: описание слишком длинное.")
                if (!seen.add("url:$url"))
                    throw ContractException(400, "bad_related", "«Ещё по теме»: повторы.")
                continue
            }
            val refKind = map["kind"]?.toString()
            val slug = map["slug"]?.toString()
            if ((refKind != "articles" && refKind != "news") || slug == null || !SLUG_RE.matches(slug))
                throw ContractException(400, "bad_related", "«Ещё по теме»: ссылка вида {kind, slug} или {url, title}.")
            val refTitle = map["title"]
            if (refTitle != null && (refTitle !is String || refTitle.length > 300))
                throw ContractException(400, "bad_related", "«Ещё по теме»: название слишком длинное.")
            val refAnnounce = map["announce"]
            if (refAnnounce != null && (refAnnounce !is String || refAnnounce.length > 1000))
                throw ContractException(400, "bad_related", "«Ещё по теме»: описание слишком длинное.")
            if (!seen.add("$refKind/$slug"))
                throw ContractException(400, "bad_related", "«Ещё по теме»: повторы.")
        }
    }

    private fun Any?.orEmptyString(): String = this?.toString() ?: ""

    companion object {
        private val SLUG_RE = Regex("^[a-z0-9][a-z0-9-]{0,119}$")
        private val EXTERNAL_URL_RE = Regex("^https://[^\\s\"'<>`]+$", RegexOption.IGNORE_CASE)
        private val SOURCE_RE = Regex("^[a-z][a-z0-9:/.\\-_]{2,199}$", RegexOption.IGNORE_CASE)
        private val ACTOR_RE = Regex("^[a-z0-9][a-z0-9._-]{0,31}$", RegexOption.IGNORE_CASE)
        private const val FIELD_MAX_DEFAULT = 40_000
        private const val MAX_RELATED = 10
        private val FIELD_MAX = mapOf(
            "slug" to 120, "title" to 300, "announce" to 1000, "lead" to 1000, "body" to 200_000,
            "name" to 120, "number" to 20, "description" to 3000, "bio" to 1500, "url" to 500,
            "pdfUrl" to 500, "buyUrl" to 500, "shopUrl" to 500, "cover" to 500, "preview" to 500,
            "files" to 400_000, "annotations" to 400_000, "related" to 20_000, "publishedAt" to 40,
        )
    }
}
