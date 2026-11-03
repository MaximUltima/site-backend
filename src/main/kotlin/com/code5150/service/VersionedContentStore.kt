package com.code5150.service

import com.code5150.api.ContractException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

val contentJson: ObjectMapper = jacksonObjectMapper()
private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
fun isoNow(): String = ISO.format(Instant.now())
fun isoAt(instant: Instant): String = ISO.format(instant)
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }
fun dbTuple(vararg values: Any?): Tuple = Tuple.tuple().also { tuple -> values.forEach { tuple.addValue(it) } }

data class VersionRow(
    val id: Long,
    val kind: String,
    val slug: String,
    val lang: String,
    val status: String,
    val rawData: String,
    val actor: String?,
    val createdAt: String,
    val prevHash: String,
    val hash: String,
) {
    val data: MutableMap<String, Any?>
        get() = contentJson.readValue(rawData, contentJson.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Any::class.java))

    fun toMap(includeHashes: Boolean = true): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id,
        "kind" to kind,
        "slug" to slug,
        "lang" to lang,
        "status" to status,
        "data" to data,
        "actor" to actor,
        "created_at" to createdAt,
    ).also {
        if (includeHashes) {
            it["prev_hash"] = prevHash
            it["hash"] = hash
        }
    }
}

private fun Row.version() = VersionRow(
    getLong("id"), getString("kind"), getString("slug"), getString("lang"), getString("status"),
    getString("data"), getString("actor"), getString("created_at"), getString("prev_hash"), getString("hash"),
)

data class PublicationMeta(val first: String?, val last: String?, val count: Long, val actor: String?)
data class ScheduleRow(
    val id: Long,
    val versionId: Long,
    val kind: String,
    val slug: String,
    val lang: String,
    val publishAt: String,
    val byActor: String,
    val createdAt: String,
    val state: String,
    val doneVersion: Long?,
    val doneAt: String?,
) {
    fun toMap() = linkedMapOf<String, Any?>(
        "id" to id, "version_id" to versionId, "kind" to kind, "slug" to slug, "lang" to lang,
        "publish_at" to publishAt, "by_actor" to byActor, "created_at" to createdAt,
        "state" to state, "done_version" to doneVersion, "done_at" to doneAt,
    )
}

private fun Row.schedule() = ScheduleRow(
    getLong("id"), getLong("version_id"), getString("kind"), getString("slug"), getString("lang"),
    getString("publish_at"), getString("by_actor"), getString("created_at"), getString("state"),
    getLong("done_version"), getString("done_at"),
)

@ApplicationScoped
class VersionedContentStore(private val pool: PgPool) {
    suspend fun save(
        kind: String,
        slug: String,
        data: Map<String, Any?>,
        status: String,
        lang: String?,
        actor: String,
    ): VersionRow {
        checkKind(kind)
        checkLang(lang)
        require(status in STATUSES) { "bad status" }
        val raw = contentJson.writeValueAsString(data)
        return pool.withTransaction { connection ->
            append(connection, kind, slug, raw, status, lang ?: DEFAULT_LANG, actor, isoNow())
        }.awaitSuspending()
    }

    suspend fun version(id: Long): VersionRow? = pool.preparedQuery("SELECT * FROM content_versions WHERE id = $1")
        .execute(dbTuple(id)).awaitSuspending().firstOrNull()?.version()

    suspend fun findBySource(kind: String, slug: String, lang: String, source: String): VersionRow? =
        pool.preparedQuery(
            "SELECT * FROM content_versions WHERE kind = $1 AND slug = $2 AND lang = $3 AND data::jsonb ->> 'source' = $4 ORDER BY id DESC LIMIT 1",
        ).execute(dbTuple(kind, slug, lang, source)).awaitSuspending().firstOrNull()?.version()

    suspend fun history(kind: String, slug: String, lang: String): List<Map<String, Any?>> {
        checkKind(kind)
        checkLang(lang)
        return pool.preparedQuery(
            "SELECT id, status, lang, created_at, actor FROM content_versions WHERE kind = $1 AND slug = $2 AND lang = $3 ORDER BY id DESC",
        ).execute(dbTuple(kind, slug, lang)).awaitSuspending().map { row ->
            linkedMapOf<String, Any?>(
                "id" to row.getLong("id"), "status" to row.getString("status"), "lang" to row.getString("lang"),
                "created_at" to row.getString("created_at"), "actor" to row.getString("actor"),
            )
        }
    }

    suspend fun publish(id: Long, actor: String): VersionRow? {
        val old = version(id) ?: return null
        return save(old.kind, old.slug, old.data, "published", old.lang, actor)
    }

    suspend fun publicationMeta(kind: String, slug: String, lang: String): PublicationMeta =
        pool.preparedQuery(
            "SELECT min(created_at) AS first, max(created_at) AS last, count(*) AS n, " +
                "(SELECT actor FROM content_versions WHERE kind = $1 AND slug = $2 AND lang = $3 AND status = 'published' ORDER BY id DESC LIMIT 1) AS actor " +
                "FROM content_versions WHERE kind = $1 AND slug = $2 AND lang = $3 AND status = 'published'",
        ).execute(dbTuple(kind, slug, lang)).awaitSuspending().first().let {
            PublicationMeta(it.getString("first"), it.getString("last"), it.getLong("n"), it.getString("actor"))
        }

    suspend fun liveIn(kind: String, lang: String): List<VersionRow> {
        checkKind(kind)
        checkLang(lang)
        return pool.preparedQuery(
            "SELECT v.* FROM content_versions v JOIN (" +
                "SELECT DISTINCT ON (slug) id FROM content_versions WHERE kind = $1 AND lang = $2 AND status <> 'draft' ORDER BY slug, id DESC" +
                ") current ON current.id = v.id WHERE v.status = 'published' ORDER BY v.id",
        ).execute(dbTuple(kind, lang)).awaitSuspending().map { it.version() }
    }

    suspend fun live(kind: String, lang: String): List<MutableMap<String, Any?>> {
        checkLang(lang)
        val own = liveIn(kind, lang)
        if (lang == DEFAULT_LANG) return own.map { publicData(it) }
        val base = liveIn(kind, DEFAULT_LANG)
        val ownBySlug = own.associateBy { it.slug }
        val out = base.map { original ->
            val translated = ownBySlug[original.slug]
            if (translated != null) publicData(translated, original) else publicData(original).also {
                it["fallback"] = true
                it["lang"] = DEFAULT_LANG
            }
        }.toMutableList()
        val baseSlugs = base.mapTo(mutableSetOf()) { it.slug }
        own.filterNot { it.slug in baseSlugs }.forEach { out += publicData(it) }
        return out
    }

    suspend fun one(kind: String, slug: String, lang: String): MutableMap<String, Any?>? =
        if (kind == "support" && slug == "support") live(kind, lang).firstOrNull()
        else live(kind, lang).firstOrNull { it["slug"] == slug }

    suspend fun list(
        kind: String,
        lang: String,
        slug: String? = null,
        tag: String? = null,
        category: String? = null,
        author: String? = null,
    ): List<MutableMap<String, Any?>> = live(kind, lang).asSequence()
        .filter { slug == null || it["slug"] == slug }
        .filter { tag == null || stringList(it["tags"]).contains(tag) }
        .filter { category == null || stringList(it["categories"]).contains(category) }
        .filter { author == null || stringList(it["authors"]).contains(author) }
        .sortedWith(
            compareByDescending<MutableMap<String, Any?>> { it["fallback"] != true }
                .thenByDescending { it["publishedAt"]?.toString().orEmpty() },
        ).toList()

    suspend fun docs(kind: String, lang: String): List<MutableMap<String, Any?>> {
        checkKind(kind)
        checkLang(lang)
        val rows = pool.preparedQuery(
            "SELECT v.*, (SELECT status FROM content_versions p WHERE p.kind = v.kind AND p.slug = v.slug AND p.lang = v.lang " +
                "AND p.status <> 'draft' ORDER BY p.id DESC LIMIT 1) AS live_status FROM content_versions v JOIN (" +
                "SELECT DISTINCT ON (slug) id FROM content_versions WHERE kind = $1 AND lang = $2 ORDER BY slug, id DESC" +
                ") latest ON latest.id = v.id ORDER BY v.id DESC",
        ).execute(dbTuple(kind, lang)).awaitSuspending()
        return rows.map { row ->
            val version = row.version()
            val liveStatus = row.getString("live_status")
            linkedMapOf<String, Any?>(
                "id" to version.id, "slug" to version.slug, "status" to version.status, "lang" to version.lang,
                "created_at" to version.createdAt, "data" to version.data,
                "live" to (liveStatus == "published"), "off" to (liveStatus == "unpublished"),
            )
        }
    }

    suspend fun lastId(): Long = pool.query("SELECT next_id - 1 AS id FROM content_chain_state WHERE id = 1")
        .execute().awaitSuspending().first().getLong("id")

    suspend fun versionsSince(since: Long, limit: Int): List<VersionRow> = pool.preparedQuery(
        "SELECT * FROM content_versions WHERE id > $1 ORDER BY id LIMIT $2",
    ).execute(dbTuple(since, limit)).awaitSuspending().map { it.version() }

    suspend fun verifyChain(): Map<String, Any?> {
        val rows = pool.query("SELECT * FROM content_versions ORDER BY id").execute().awaitSuspending().map { it.version() }
        var previous = ""
        var lastId = 0L
        val broken = mutableListOf<Long>()
        val gaps = mutableListOf<Long>()
        for (row in rows) {
            if (lastId != 0L && row.id != lastId + 1) gaps += row.id
            if (row.prevHash != previous || row.hash != versionHash(row)) broken += row.id
            previous = row.hash
            lastId = row.id
        }
        return linkedMapOf(
            "rows" to rows.size, "last" to previous, "start" to null, "broken" to broken, "gaps" to gaps,
        )
    }

    suspend fun schedule(versionId: Long, at: Instant, actor: String): ScheduleRow? {
        val version = version(versionId) ?: return null
        return pool.withTransaction { connection ->
            connection.preparedQuery(
                "UPDATE content_schedule SET state = 'canceled' WHERE kind = $1 AND slug = $2 AND lang = $3 AND state = 'pending'",
            ).execute(dbTuple(version.kind, version.slug, version.lang)).flatMap {
                connection.preparedQuery(
                    "INSERT INTO content_schedule (version_id, kind, slug, lang, publish_at, by_actor, created_at) " +
                        "VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *",
                ).execute(dbTuple(versionId, version.kind, version.slug, version.lang, isoAt(at), actor, isoNow()))
            }.map { it.first().schedule() }
        }.awaitSuspending()
    }

    suspend fun unschedule(kind: String, slug: String, lang: String): Boolean =
        pool.preparedQuery(
            "UPDATE content_schedule SET state = 'canceled' WHERE kind = $1 AND slug = $2 AND lang = $3 AND state = 'pending'",
        ).execute(dbTuple(kind, slug, lang)).awaitSuspending().rowCount() > 0

    suspend fun scheduled(): List<ScheduleRow> = pool.query(
        "SELECT * FROM content_schedule WHERE state = 'pending' ORDER BY publish_at, id",
    ).execute().awaitSuspending().map { it.schedule() }

    suspend fun runDue(now: Instant = Instant.now()): List<Map<String, Any?>> = runDueUni(now).awaitSuspending()

    fun runDueUni(now: Instant = Instant.now()): Uni<List<Map<String, Any?>>> {
        val at = isoAt(now)
        return pool.withTransaction { connection ->
            connection.preparedQuery(
                "SELECT * FROM content_schedule WHERE state = 'pending' AND publish_at <= $1 ORDER BY publish_at, id FOR UPDATE",
            ).execute(dbTuple(at)).flatMap { rows ->
                var work = Uni.createFrom().item(mutableListOf<Map<String, Any?>>())
                for (row in rows) {
                    val schedule = row.schedule()
                    work = work.flatMap { result ->
                        connection.preparedQuery("SELECT * FROM content_versions WHERE id = $1")
                            .execute(dbTuple(schedule.versionId)).flatMap { versionRows ->
                                val version = versionRows.firstOrNull()?.version()
                                if (version == null) {
                                    connection.preparedQuery("UPDATE content_schedule SET state = 'canceled' WHERE id = $1")
                                        .execute(dbTuple(schedule.id)).map { result }
                                } else {
                                    connection.preparedQuery(
                                        "SELECT count(*) AS n FROM content_versions WHERE kind = $1 AND slug = $2 AND lang = $3 AND status = 'published'",
                                    ).execute(dbTuple(version.kind, version.slug, version.lang)).flatMap { countRows ->
                                        val data = version.data
                                        if (countRows.first().getLong("n") == 0L) data["publishedAt"] = at
                                        append(
                                            connection, version.kind, version.slug, contentJson.writeValueAsString(data),
                                            "published", version.lang, "по расписанию (${schedule.byActor})", at,
                                        ).flatMap { published ->
                                            connection.preparedQuery(
                                                "UPDATE content_schedule SET state = 'done', done_version = $1, done_at = $2 WHERE id = $3 AND state = 'pending'",
                                            ).execute(dbTuple(published.id, at, schedule.id)).map {
                                                result += schedule.toMap().also { it["version"] = published.toMap() }
                                                result
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
                work.map { it.toList() }
            }
        }
    }

    private fun append(
        connection: SqlConnection,
        kind: String,
        slug: String,
        rawData: String,
        status: String,
        lang: String,
        actor: String,
        createdAt: String,
    ): Uni<VersionRow> = connection.query("SELECT next_id, last_hash FROM content_chain_state WHERE id = 1 FOR UPDATE")
        .execute().flatMap { stateRows ->
            val state = stateRows.first()
            val id = state.getLong("next_id")
            val previous = state.getString("last_hash")
            val hash = versionHash(id, kind, slug, lang, status, actor, createdAt, rawData, previous)
            connection.preparedQuery(
                "INSERT INTO content_versions (id, kind, slug, lang, status, data, actor, created_at, prev_hash, hash) " +
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING *",
            ).execute(dbTuple(id, kind, slug, lang, status, rawData, actor, createdAt, previous, hash)).flatMap { inserted ->
                connection.preparedQuery("UPDATE content_chain_state SET next_id = $1, last_hash = $2 WHERE id = 1")
                    .execute(dbTuple(id + 1, hash)).map { inserted.first().version() }
            }
        }

    companion object {
        val KINDS = setOf("news", "articles", "newspaper", "books", "magazine", "videos", "support", "categories", "authors")
        val LANGS = setOf("ru", "en")
        val STATUSES = setOf("draft", "published", "unpublished")
        const val DEFAULT_LANG = "ru"

        fun checkKind(kind: String) {
            if (kind !in KINDS) throw ContractException(404, "not_found", "Неизвестный вид материала")
        }

        fun checkLang(lang: String?) {
            if (lang != null && lang !in LANGS) throw ContractException(400, "bad_lang", "Неизвестный язык")
        }

        fun publicData(version: VersionRow, shared: VersionRow? = null): MutableMap<String, Any?> {
            val data = version.data
            if (version.kind == "news" || version.kind == "articles") data["readingTime"] = readingMinutes(data)
            if (shared != null) {
                for (key in listOf("categories", "authors")) {
                    if (shared.data.containsKey(key)) data[key] = shared.data[key] else data.remove(key)
                }
            }
            return data
        }

        fun readingMinutes(data: Map<String, Any?>): Int {
            val body = data["body"] as? String ?: return 1
            val images = Regex("(?m)^::figure\\b|!\\[").findAll(body).count()
            val text = body
                .replace(Regex("(?m)^::\\w+(\\[[^]]*])?(\\{[^}]*})?\\s*$"), " ")
                .replace(Regex(":\\w+\\[([^]]*)]\\{[^}]*}"), "$1")
                .replace(Regex("\\[\\^[^]]*]"), " ")
                .replace(Regex("[#>*_`|-]"), " ")
            val words = Regex("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*").findAll(text).count()
            val seconds = Math.round(words / 200.0 * 60 + images * 12)
            return maxOf(1, Math.round(seconds / 60.0).toInt())
        }

        fun versionHash(row: VersionRow): String = versionHash(
            row.id, row.kind, row.slug, row.lang, row.status, row.actor, row.createdAt, row.rawData, row.prevHash,
        )

        private fun versionHash(
            id: Long,
            kind: String,
            slug: String,
            lang: String,
            status: String,
            actor: String?,
            createdAt: String,
            rawData: String,
            previous: String,
        ): String = sha256(previous + contentJson.writeValueAsString(
            listOf(id, kind, slug, lang, status, actor, createdAt, sha256(rawData)),
        ))
    }
}

fun stringList(value: Any?): List<String> = when (value) {
    is List<*> -> value.mapNotNull {
        when (it) {
            is String -> it
            is Map<*, *> -> it["slug"]?.toString()
            else -> null
        }
    }
    else -> emptyList()
}
