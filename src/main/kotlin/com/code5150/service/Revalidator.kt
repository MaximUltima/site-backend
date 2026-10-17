package com.code5150.service

import com.code5150.entity.columnJson
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/**
 * Сброс кэша сайта после публикации, правки или снятия материала:
 * POST {site.revalidate-url} с секретом и тегами (docs/api.md, раздел 5).
 *
 * Отправляется в фоне: если сайт недоступен, правка в базе всё равно
 * сохранена, а страница обновится сама по истечении кэша (5 минут).
 */
@ApplicationScoped
class Revalidator(
    @ConfigProperty(name = "site.revalidate-url") private val url: Optional<String>,
    @ConfigProperty(name = "site.revalidate-secret") private val secret: Optional<String>,
) {
    private val log = Logger.getLogger(Revalidator::class.java)
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun revalidate(tags: Collection<String>) {
        val target = url.orElse("").trim()
        val token = secret.orElse("").trim()
        if (target.isEmpty() || token.isEmpty() || tags.isEmpty()) return

        val request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(columnJson.writeValueAsString(mapOf("tags" to tags.distinct()))))
            .build()

        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { response, error ->
            when {
                error != null -> log.warnf("Сброс кэша сайта не удался (%s): %s", tags, error.message)
                response.statusCode() !in 200..299 ->
                    log.warnf("Сайт ответил %d на сброс кэша %s", response.statusCode(), tags)
                else -> log.debugf("Кэш сайта сброшен: %s", tags)
            }
        }
    }
}

/** Теги кэша для вида материала (таблица в docs/api.md, раздел 5). */
enum class ContentKind(val path: String, val tag: String, val onHome: Boolean) {
    NEWS("news", "news", true),
    ARTICLES("articles", "articles", true),
    NEWSPAPER("newspaper", "newspaper", true),
    BOOKS("books", "books", false),
    MAGAZINE("magazine", "magazine", false),
    VIDEOS("videos", "videos", true),
    ;

    /** Раздел, сам материал (по старому и новому слагу) и главная, если материал на ней бывает. */
    fun tags(vararg slugs: String?): List<String> = buildList {
        add(tag)
        slugs.filterNotNull().distinct().forEach { add("$tag:$it") }
        if (onHome) add("home")
    }
}
