package com.code5150.service

import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class StatsService(private val pool: PgPool) {
    private val minute = ConcurrentHashMap<String, Pair<Long, Int>>()

    suspend fun hit(kind: String, slug: String, lang: String, signal: String, now: Instant = Instant.now()): Boolean {
        if (kind !in STAT_KINDS || signal !in SIGNALS) return false
        val key = "$kind\t$slug"
        val currentMinute = now.epochSecond / 60
        var accepted = false
        minute.compute(key) { _, old ->
            if (old == null || old.first != currentMinute) {
                accepted = true
                currentMinute to 1
            } else if (old.second >= CAP_PER_MINUTE) old
            else {
                accepted = true
                old.copy(second = old.second + 1)
            }
        }
        if (!accepted) return false
        val views = if (signal == "view") 1 else 0
        val reads = if (signal == "read") 1 else 0
        pool.preparedQuery(
            "INSERT INTO stats_daily (kind, slug, lang, day, views, reads) VALUES ($1, $2, $3, $4, $5, $6) " +
                "ON CONFLICT (kind, slug, lang, day) DO UPDATE SET views = stats_daily.views + excluded.views, reads = stats_daily.reads + excluded.reads",
        ).execute(Tuple.of(kind, slug, lang, moscowDay(now), views, reads)).awaitSuspending()
        if (minute.size > 10_000) minute.entries.removeIf { it.value.first != currentMinute }
        return true
    }

    suspend fun download(fileId: Long, now: Instant = Instant.now()) {
        pool.preparedQuery(
            "INSERT INTO stats_downloads (file_id, day, downloads) VALUES ($1, $2, 1) " +
                "ON CONFLICT (file_id, day) DO UPDATE SET downloads = stats_downloads.downloads + 1",
        ).execute(Tuple.of(fileId, moscowDay(now))).awaitSuspending()
    }

    suspend fun totals(filter: StatsFilter): Map<String, Long> = pool.preparedQuery(
        "SELECT coalesce(sum(views), 0) AS views, coalesce(sum(reads), 0) AS reads FROM stats_daily WHERE $WHERE",
    ).execute(filter.args()).awaitSuspending().first().let {
        mapOf("views" to it.getLong("views"), "reads" to it.getLong("reads"))
    }

    suspend fun series(filter: StatsFilter): List<Map<String, Any>> = pool.preparedQuery(
        "SELECT day, sum(views) AS views, sum(reads) AS reads FROM stats_daily WHERE $WHERE GROUP BY day ORDER BY day",
    ).execute(filter.args()).awaitSuspending().map {
        mapOf("day" to it.getLocalDate("day").toString(), "views" to it.getLong("views"), "reads" to it.getLong("reads"))
    }

    suspend fun top(filter: StatsFilter, order: String, limit: Int): List<Map<String, Any>> {
        val sort = when (order) {
            "reads" -> "sum(reads) DESC, sum(views) DESC, kind, slug"
            "rate" -> "sum(reads)::double precision / greatest(sum(views), 1) DESC, sum(views) DESC, kind, slug"
            else -> "sum(views) DESC, kind, slug"
        }
        return pool.preparedQuery(
            "SELECT kind, slug, sum(views) AS views, sum(reads) AS reads FROM stats_daily WHERE $WHERE " +
                "GROUP BY kind, slug ORDER BY $sort LIMIT $6",
        ).execute(filter.args().addInteger(limit)).awaitSuspending().map {
            mapOf(
                "kind" to it.getString("kind"), "slug" to it.getString("slug"),
                "views" to it.getLong("views"), "reads" to it.getLong("reads"),
            )
        }
    }

    suspend fun spark(filter: StatsFilter, items: Set<String>): Map<String, Map<String, Long>> {
        val out = items.associateWith { mutableMapOf<String, Long>() }.toMutableMap()
        pool.preparedQuery(
            "SELECT kind, slug, day, sum(views) AS views FROM stats_daily WHERE $WHERE GROUP BY kind, slug, day",
        ).execute(filter.args()).awaitSuspending().forEach {
            val key = "${it.getString("kind")}/${it.getString("slug")}"
            out[key]?.put(it.getLocalDate("day").toString(), it.getLong("views"))
        }
        return out
    }

    suspend fun firstDay(): String? = pool.query("SELECT min(day) AS day FROM stats_daily")
        .execute().awaitSuspending().first().getLocalDate("day")?.toString()

    suspend fun downloads(filter: StatsFilter): List<Map<String, Any>> = pool.preparedQuery(
        "SELECT file_id, sum(downloads) AS downloads FROM stats_downloads WHERE day BETWEEN $1 AND $2 " +
            "GROUP BY file_id ORDER BY downloads DESC, file_id",
    ).execute(Tuple.of(LocalDate.parse(filter.from), LocalDate.parse(filter.to))).awaitSuspending().map {
        mapOf("file_id" to it.getLong("file_id"), "downloads" to it.getLong("downloads"))
    }

    suspend fun downloadsOf(id: Long): Long = pool.preparedQuery(
        "SELECT coalesce(sum(downloads), 0) AS n FROM stats_downloads WHERE file_id = $1",
    ).execute(Tuple.of(id)).awaitSuspending().first().getLong("n")

    companion object {
        val SIGNALS = setOf("view", "read")
        val STAT_KINDS = setOf("articles", "news")
        const val CAP_PER_MINUTE = 60
        private const val WHERE = "day BETWEEN $1 AND $2 AND ($3 = '' OR kind = $3) " +
            "AND ($4 = '' OR lang = $4) AND ($5 = '' OR slug = $5)"

        fun moscowDay(now: Instant): LocalDate = LocalDate.ofInstant(now.plusSeconds(3 * 3600), ZoneOffset.UTC)
    }
}

data class StatsFilter(
    val from: String,
    val to: String,
    val kind: String = "",
    val lang: String = "",
    val slug: String = "",
) {
    fun args(): Tuple = Tuple.of(LocalDate.parse(from), LocalDate.parse(to), kind, lang, slug)
}
