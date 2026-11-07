package com.code5150

import com.code5150.service.contentJson
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

@QuarkusTest
class ApiTest {
    private fun admin(): RequestSpecification =
        given().header("Authorization", "Bearer test-token").contentType(ContentType.JSON)

    private fun slug(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun save(
        kind: String = "news",
        slug: String = slug(kind),
        title: String = "Материал $slug",
        status: String = "PUBLISHED",
        lang: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = admin().body(
        mapOf(
            "slug" to slug, "title" to title, "status" to status, "lang" to lang,
            "publishedAt" to OffsetDateTime.now().minusHours(1).toString(),
            "announce" to "Анонс $title", "body" to "Текст $title с поисковым словом",
        ) + extra,
    ).post("/api/admin/v1/$kind").then().statusCode(201).extract().jsonPath().getMap("")

    @Test
    fun `admin закрыт, public и health открыты`() {
        given().get("/api/admin/v1/docs/news").then().statusCode(401).body("error", equalTo("unauthorized"))
        given().get("/health").then().statusCode(200).body("ok", equalTo(true))
        given().get("/api/v1/home").then().statusCode(200)
    }

    @Test
    fun `версии образуют цепочку без пропусков и отдаются журналу с data и hashes`() {
        val first = save()
        val second = save(status = "DRAFT")
        val firstId = (first["id"] as Number).toLong()
        val secondId = (second["id"] as Number).toLong()
        assert(secondId > firstId)
        admin().get("/api/admin/v1/last-id").then().statusCode(200).body("id", greaterThan(secondId.toInt() - 1))
        admin().get("/api/admin/v1/versions?since=$firstId&limit=1").then().statusCode(200)
            .body("items", hasSize<Any>(1)).body("items[0].id", equalTo(secondId.toInt()))
            .body("items[0].data.slug", equalTo(second["slug"]))
            .body("items[0].prev_hash", equalTo(first["hash"]))
            .body("items[0].hash", equalTo(second["hash"]))
        admin().get("/api/admin/v1/journal/verify").then().statusCode(200)
            .body("broken", hasSize<Any>(0)).body("gaps", hasSize<Any>(0)).body("last", notNullValue())
    }

    @Test
    fun `draft не закрывает live, publish и rollback создают новые версии`() {
        val slug = slug("rollback")
        val original = save(slug = slug, title = "Первая публикация")
        val draft = save(slug = slug, title = "Черновик", status = "DRAFT")
        given().get("/api/v1/news/$slug").then().statusCode(200).body("title", equalTo("Первая публикация"))
        admin().get("/api/admin/v1/docs/news").then().statusCode(200)
            .body("items.find { it.slug == '$slug' }.status", equalTo("draft"))
            .body("items.find { it.slug == '$slug' }.live", equalTo(true))

        admin().post("/api/admin/v1/versions/${draft["id"]}/publish").then().statusCode(200)
            .body("status", equalTo("published")).body("data.title", equalTo("Черновик"))
        given().get("/api/v1/news/$slug").then().body("title", equalTo("Черновик"))

        admin().post("/api/admin/v1/versions/${original["id"]}/publish").then().statusCode(200)
        given().get("/api/v1/news/$slug").then().body("title", equalTo("Первая публикация"))
            .body("updatedAt", notNullValue())
        admin().get("/api/admin/v1/news/$slug/versions").then().statusCode(200).body("status", hasItem("published"))
    }

    @Test
    fun `fallback и перевод не зависят от нового черновика`() {
        val slug = slug("lang")
        save(slug = slug, title = "Русский")
        given().get("/api/v1/news/$slug?lang=en").then().statusCode(200)
            .body("title", equalTo("Русский")).body("fallback", equalTo(true)).body("lang", equalTo("ru"))
        save(slug = slug, title = "English draft", status = "DRAFT", lang = "en")
        given().get("/api/v1/news/$slug?lang=en").then().body("fallback", equalTo(true))
        save(slug = slug, title = "English", lang = "en")
        given().get("/api/v1/news/$slug?lang=en").then().body("title", equalTo("English"))
            .body("fallback", nullValue()).body("readingTime", greaterThan(0))
        given().get("/api/v1/news/$slug?lang=de").then().statusCode(400).body("error", equalTo("bad_lang"))
    }

    @Test
    fun `unpublish скрывает материал новой версией`() {
        val slug = slug("off")
        save(slug = slug)
        admin().body("{}").post("/api/admin/v1/news/$slug/unpublish").then().statusCode(200)
            .body("status", equalTo("unpublished"))
        given().get("/api/v1/news/$slug").then().statusCode(404)
        admin().get("/api/admin/v1/one/news/$slug").then().statusCode(200)
            .body("data", nullValue()).body("doc.off", equalTo(true))
    }

    @Test
    fun `source повторяется идемпотентно, review видит только черновики`() {
        val slug = slug("source")
        val body = mapOf("slug" to slug, "title" to "Импорт", "source" to "telegram:militant/999", "review" to true)
        val first = admin().body(body).post("/api/admin/v1/news").then().statusCode(201).extract().jsonPath().getMap<String, Any?>("")
        admin().body(body).post("/api/admin/v1/news").then().statusCode(200)
            .body("id", equalTo((first["id"] as Number).toInt())).body("idempotent", equalTo(true))
        admin().get("/api/admin/v1/review").then().statusCode(200)
            .body("items.slug", hasItem(slug)).body("items.kind", everyItem(oneOf("news", "articles", "newspaper", "books", "magazine", "videos", "support", "categories", "authors")))
        admin().body(body + ("source" to "telegram:militant/bad key")).post("/api/admin/v1/news").then().statusCode(400)
            .body("error", equalTo("bad_source"))
    }

    @Test
    fun `правка и публикация импортированного материала с source создают версии`() {
        val slug = slug("source-edit")
        val base = mapOf(
            "slug" to slug, "title" to "Импорт", "source" to "telegram:militant/1000",
            "announce" to "Анонс", "body" to "Текст для чтения",
        )
        admin().body(base + ("status" to "DRAFT")).post("/api/admin/v1/news").then().statusCode(201)
        admin().body(base + ("status" to "DRAFT") + ("title" to "Поправлено"))
            .post("/api/admin/v1/news").then().statusCode(201).body("data.title", equalTo("Поправлено"))
        admin().body(base + ("status" to "PUBLISHED") + ("title" to "Поправлено"))
            .post("/api/admin/v1/news").then().statusCode(201).body("status", equalTo("published"))
        given().get("/api/v1/news/$slug").then().statusCode(200).body("title", equalTo("Поправлено"))
    }

    @Test
    fun `schedule публикует due один раз и unschedule отменяет`() {
        val slug = slug("schedule")
        save(slug = slug, status = "DRAFT")
        admin().body(mapOf("at" to OffsetDateTime.now().minusMinutes(1).toString()))
            .post("/api/admin/v1/news/$slug/schedule").then().statusCode(200).body("lang", equalTo("ru"))
        admin().get("/api/admin/v1/scheduled").then().statusCode(200).body("items.slug", hasItem(slug))
        admin().post("/api/admin/v1/schedule/runDue").then().statusCode(200).body("items.version.data.slug", hasItem(slug))
        given().get("/api/v1/news/$slug").then().statusCode(200)
        admin().post("/api/admin/v1/schedule/runDue").then().statusCode(200).body("items", hasSize<Any>(0))

        val canceled = slug("canceled")
        save(slug = canceled, status = "DRAFT")
        admin().body(mapOf("at" to OffsetDateTime.now().plusDays(1).toString()))
            .post("/api/admin/v1/news/$canceled/schedule").then().statusCode(200)
        admin().post("/api/admin/v1/news/$canceled/unschedule").then().statusCode(200).body("unscheduled", equalTo(true))
        admin().post("/api/admin/v1/news/$canceled/unschedule").then().statusCode(404)
    }

    @Test
    fun `public lists full taxon related next search и slugs`() {
        val category = slug("category")
        val author = slug("author")
        save("categories", category, "Рубрика", extra = mapOf("position" to 1))
        save("authors", author, "Автор", extra = mapOf("name" to "Автор"))
        val first = slug("article-one")
        val second = slug("article-two")
        save("articles", first, "Первая статья", extra = mapOf("tags" to listOf("труд"), "categories" to listOf(category), "authors" to listOf(author)))
        save("articles", second, "Вторая статья", extra = mapOf("tags" to listOf("труд"), "categories" to listOf(category), "authors" to listOf(author)))

        given().get("/api/v1/articles/$first").then().statusCode(200)
            .body("categories[0].title", equalTo("Рубрика")).body("authors[0].name", equalTo("Автор"))
            .body("annotations", nullValue())
        given().get("/api/v1/articles?tag=труд&size=1").then().statusCode(200)
            .body("items", hasSize<Any>(1)).body("size", equalTo(1)).body("total", greaterThan(1))
        given().get("/api/v1/categories/$category").then().statusCode(200)
            .body("items.slug", hasItem(first)).body("news", notNullValue())
        given().get("/api/v1/authors/$author").then().statusCode(200).body("items.slug", hasItem(second))
        given().get("/api/v1/related/$first?kind=article").then().statusCode(200)
            .body("slug", hasItem(second)).body("slug", not(hasItem(first)))
        given().get("/api/v1/next/$first?kind=article").then().statusCode(200)
            .body("kind", equalTo("article")).body("slug", notNullValue())
        given().get("/api/v1/search?q=поисковым&kind=articles").then().statusCode(200)
            .body("items.slug", hasItem(first)).body("items[0].kind", equalTo("article"))
            .body("items[0].fragment.hit", hasItem(true))
        given().get("/api/v1/slugs?lang=en").then().statusCode(200)
            .body("articles", hasItem(first)).body("categories", hasItem(category))
            .body("untranslated.articles", hasItem(first)).body("modified.articles.$first", notNullValue())
        save("news", slug("home-news"), "Новость для главной")
        given().get("/api/v1/home").then().statusCode(200)
            .body("news.size()", greaterThan(0)).body("articles.size()", greaterThan(0))
    }

    @Test
    fun `ручная подборка related задаёт порядок, пропускает недоступное и не режется limit`() {
        val older = slug("rel-older")
        val newer = slug("rel-newer")
        val middle = slug("rel-middle")
        save("articles", older, "Старая", extra = mapOf("publishedAt" to OffsetDateTime.now().minusDays(3).toString()))
        save("news", newer, "Новая")
        save("articles", middle, "Средняя")
        val draft = slug("rel-draft")
        save("articles", draft, "Черновик", status = "DRAFT")
        val off = slug("rel-off")
        save("articles", off, "Снятая")
        admin().post("/api/admin/v1/articles/$off/unpublish").then().statusCode(200)

        val source = slug("rel-source")
        save(
            "articles", source, "Источник",
            extra = mapOf(
                "related" to listOf(
                    mapOf("kind" to "articles", "slug" to "$source-missing"),
                    mapOf("kind" to "articles", "slug" to draft),
                    mapOf("kind" to "articles", "slug" to off),
                    mapOf("kind" to "articles", "slug" to older),
                    mapOf("kind" to "news", "slug" to newer),
                    mapOf("kind" to "articles", "slug" to middle),
                    mapOf("kind" to "articles", "slug" to source),
                    mapOf("url" to "https://t.me/militant_SWP/9990", "title" to "Внешняя первая"),
                    mapOf("url" to "https://t.me/militant_SWP/9986", "title" to "Внешняя вторая"),
                ),
            ),
        )

        // Порядок ручной (не по дате), недоступное пропущено. Ручную подборку limit не обрезает:
        // редактор задал её сам, на сайте показываем всё (до десяти пунктов).
        given().get("/api/v1/related/$source?kind=article&limit=2").then().statusCode(200)
            .body("size()", equalTo(5))
            .body("[0].slug", equalTo(older)).body("[0].kind", equalTo("article"))
            .body("[1].slug", equalTo(newer)).body("[1].kind", equalTo("news"))
            .body("[2].slug", equalTo(middle))
            .body("[3].kind", equalTo("link")).body("[3].url", equalTo("https://t.me/militant_SWP/9990"))
            .body("[4].kind", equalTo("link")).body("[4].title", equalTo("Внешняя вторая"))
        // И без kind тоже.
        given().get("/api/v1/related/$source").then().statusCode(200).body("size()", equalTo(5))
        // Внутреннее поле не утекает в публичную статью.
        given().get("/api/v1/articles/$source").then().statusCode(200).body("related", nullValue())
        admin().get("/api/admin/v1/one/articles/$source").then().statusCode(200)
            .body("data.related.size()", equalTo(9))
    }

    @Test
    fun `related в админском API проверяется на форму, лимит, повторы и чужой вид`() {
        fun bad(extra: Map<String, Any?>) {
            admin().body(
                mapOf("slug" to slug("rel-bad"), "title" to "Плохая", "status" to "PUBLISHED") + extra,
            ).post("/api/admin/v1/articles").then().statusCode(400).body("error", equalTo("bad_related"))
        }
        bad(mapOf("related" to "строка"))
        bad(mapOf("related" to listOf(mapOf("kind" to "videos", "slug" to "x"))))
        bad(mapOf("related" to listOf(mapOf("kind" to "articles", "slug" to "Плохой Slug"))))
        bad(
            mapOf(
                "related" to listOf(
                    mapOf("kind" to "articles", "slug" to "a"),
                    mapOf("kind" to "articles", "slug" to "a"),
                ),
            ),
        )
        bad(mapOf("related" to (1..11).map { mapOf("kind" to "articles", "slug" to "s$it") }))
        bad(mapOf("related" to listOf(mapOf("url" to "http://example.org/x", "title" to "Ссылка"))))
        bad(mapOf("related" to listOf(mapOf("url" to "https://example.org/x"))))
        bad(mapOf("related" to listOf(mapOf("url" to "https://example.org/x", "title" to "Ссылка", "announce" to "a".repeat(1001)))))
    }

    @Test
    fun `внешние ссылки в related и поиск материалов для админки`() {
        val target = slug("ext-target")
        save("articles", target, "Целевая статья про профсоюзы")
        val draft = slug("ext-draft")
        save("articles", draft, "Черновик про профсоюзы", status = "DRAFT")

        val found = admin().get("/api/admin/v1/search?q=профсоюзы").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any?>>("")
        assert(found.any { it["slug"] == target && it["live"] == true })
        assert(found.any { it["slug"] == draft && it["status"] == "draft" })
        assert(found.first()["slug"] == target) { "опубликованное должно быть выше черновика" }
        admin().get("/api/admin/v1/search?q=").then().statusCode(200).body("size()", equalTo(0))
        admin().get("/api/admin/v1/search?q=профсоюзы&limit=1").then().statusCode(200).body("size()", equalTo(1))

        val source = slug("ext-source")
        save(
            "articles", source, "Источник со ссылкой",
            extra = mapOf(
                "related" to listOf(
                    mapOf("kind" to "articles", "slug" to target),
                    mapOf("url" to "https://example.org/report", "title" to "Доклад", "announce" to "Внешний материал"),
                ),
            ),
        )
        given().get("/api/v1/related/$source?kind=article").then().statusCode(200)
            .body("[0].slug", equalTo(target))
            .body("[1].kind", equalTo("link")).body("[1].url", equalTo("https://example.org/report"))
            .body("[1].title", equalTo("Доклад")).body("[1].announce", equalTo("Внешний материал"))
    }

    @Test
    fun `preview принимает только верный HMAC и отдаёт draft`() {
        val slug = slug("preview")
        val version = save(slug = slug, title = "Секретный черновик", status = "DRAFT")
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            contentJson.writeValueAsBytes(
                mapOf(
                    "v" to version["id"], "k" to "news", "s" to slug, "l" to "ru",
                    "e" to System.currentTimeMillis() + 60_000,
                ),
            ),
        )
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("preview-secret".toByteArray(), "HmacSHA256")) }
                .doFinal("preview:$payload".toByteArray()),
        )
        given().header("x-preview-token", "$payload.$signature").get("/api/v1/preview").then().statusCode(200)
            .header("Cache-Control", "no-store").body("title", equalTo("Секретный черновик"))
        given().header("x-preview-token", "$payload.bad").get("/api/v1/preview").then().statusCode(404)
    }

    @Test
    fun `stats агрегирует view read и отдаёт все admin формы`() {
        val slug = slug("stats")
        save(slug = slug)
        given().contentType(ContentType.JSON).body(mapOf("kind" to "news", "slug" to slug, "lang" to "ru", "signal" to "view"))
            .post("/api/v1/view").then().statusCode(204)
        given().contentType(ContentType.JSON).body(mapOf("kind" to "news", "slug" to slug, "lang" to "ru", "signal" to "read"))
            .post("/api/v1/view").then().statusCode(204)
        val filter = "from=2000-01-01&to=2100-01-01&kind=news&slug=$slug"
        admin().get("/api/admin/v1/stats/totals?$filter").then().statusCode(200)
            .body("views", equalTo(1)).body("reads", equalTo(1))
        admin().get("/api/admin/v1/stats/series?$filter").then().statusCode(200)
            .body("views", hasItem(1)).body("reads", hasItem(1))
        admin().get("/api/admin/v1/stats/top?$filter&order=rate").then().statusCode(200).body("slug", hasItem(slug))
        admin().get("/api/admin/v1/stats/spark?$filter&items=news/$slug").then().statusCode(200)
            .body("'news/$slug'.size()", greaterThan(0))
        admin().get("/api/admin/v1/stats/first-day").then().statusCode(200)
    }

    @Test
    fun `image file meta hide live-file и Range security headers`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val image = given().header("Authorization", "Bearer test-token").contentType(ContentType.BINARY).body(png)
            .post("/api/admin/v1/image").then().statusCode(201)
            .body("width", equalTo(40)).body("height", equalTo(30)).extract().jsonPath().getMap<String, Any?>("")
        given().get("/media/${image["name"]}").then().statusCode(200)
            .header("X-Content-Type-Options", "nosniff").header("Cache-Control", containsString("immutable"))
        given().head("/media/${image["name"]}").then().statusCode(200)

        val bytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray()
        val file = given().header("Authorization", "Bearer test-token").contentType(ContentType.BINARY).body(bytes)
            .post("/api/admin/v1/file?name=paper&license=own&source=archive").then().statusCode(201)
            .body("ext", equalTo("pdf")).body("size", equalTo(bytes.size)).extract().jsonPath().getMap<String, Any?>("")
        val id = (file["id"] as Number).toLong()
        admin().get("/api/admin/v1/live-file/$id").then().statusCode(404)
        save("articles", extra = mapOf("files" to listOf(mapOf("id" to id, "title" to "PDF"))))
        admin().get("/api/admin/v1/file/$id").then().statusCode(200).body("uploaded_by", equalTo("api"))
        admin().get("/api/admin/v1/live-file/$id").then().statusCode(200)
        given().get("/files/$id/paper.pdf").then().statusCode(200)
            .header("Content-Disposition", containsString("attachment"))
            .header("Content-Security-Policy", containsString("sandbox"))
        given().header("Range", "bytes=0-4").get("/files/$id/paper.pdf").then().statusCode(206)
            .header("Content-Range", equalTo("bytes 0-4/${bytes.size}")).body(equalTo("%PDF-"))
        given().header("Range", "bytes=99999-").get("/files/$id/paper.pdf").then().statusCode(416)
        admin().get("/api/admin/v1/stats/downloads-of/$id").then().statusCode(200).body(equalTo("2"))
        admin().body(mapOf("license" to "cc", "source" to "new archive")).post("/api/admin/v1/file/$id/meta")
            .then().statusCode(200).body("license", equalTo("cc"))
        admin().body(mapOf("reason" to "test")).post("/api/admin/v1/file/$id/hide").then().statusCode(200)
            .body("status", equalTo("hidden"))
        admin().body("{}").post("/api/admin/v1/file/$id/hide").then().statusCode(409).body("error", equalTo("already_hidden"))
        given().get("/files/$id/paper.pdf").then().statusCode(404)
    }

    @Test
    fun `support сохраняется versioned JSON и публикуется сразу`() {
        admin().body(mapOf("wallets" to listOf(mapOf("network" to "BTC", "address" to "bc1qtest")), "shopUrl" to "https://shop.example"))
            .put("/api/admin/v1/support").then().statusCode(200).body("status", equalTo("published"))
        given().get("/api/v1/support").then().statusCode(200)
            .body("wallets[0].network", equalTo("BTC")).body("shopUrl", equalTo("https://shop.example"))
        admin().get("/api/admin/v1/docs/support").then().statusCode(200).body("items[0].live", equalTo(true))
    }
}
