package com.code5150

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Публичное и админское API по контракту docs/api.md.
 * База — PostgreSQL из профиля %test, очищается перед прогоном.
 * Тесты не зависят друг от друга: у каждого свои уникальные заголовки.
 */
@QuarkusTest
class ApiTest {

    private fun admin(): RequestSpecification =
        given().header("Authorization", "Bearer test-token").contentType(ContentType.JSON)

    private fun unique(prefix: String) = "$prefix ${UUID.randomUUID().toString().take(8)}"

    private fun createNews(
        title: String,
        status: String = "PUBLISHED",
        publishedAt: OffsetDateTime = OffsetDateTime.now().minusHours(1),
        slug: String? = null,
    ): Map<String, Any?> =
        admin()
            .body(
                mapOf(
                    "title" to title,
                    "slug" to slug,
                    "status" to status,
                    "publishedAt" to publishedAt.toString(),
                    "announce" to "Анонс: $title",
                    "body" to "Текст **$title**",
                ),
            )
            .post("/api/admin/v1/news")
            .then().statusCode(201)
            .extract().jsonPath().getMap("")

    // Доступ

    @Test
    fun `админское API без токена закрыто`() {
        given().get("/api/admin/v1/news").then().statusCode(401).body("error", equalTo("unauthorized"))
        given().header("Authorization", "Bearer wrong-token").get("/api/admin/v1/news").then().statusCode(401)
    }

    @Test
    fun `публичное API открыто, служебных заготовок больше нет`() {
        given().get("/api/v1/home").then().statusCode(200)
        given().get("/hello").then().statusCode(404)
    }

    // Публикация

    @Test
    fun `сайт видит только опубликованное с наступившей датой`() {
        val visible = createNews(unique("Видно"))
        val draft = createNews(unique("Черновик"), status = "DRAFT")
        val future = createNews(unique("Завтра"), publishedAt = OffsetDateTime.now().plusDays(1))

        given().get("/api/v1/news/${visible["slug"]}").then().statusCode(200)
            .body("title", equalTo(visible["title"]))
            .body("body", containsString("**"))
            .body("cover", nullValue())
        given().get("/api/v1/news/${draft["slug"]}").then().statusCode(404).body("error", equalTo("not_found"))
        given().get("/api/v1/news/${future["slug"]}").then().statusCode(404)

        given().get("/api/v1/slugs").then().statusCode(200)
            .body("news", hasItem(visible["slug"]))
            .body("news", not(hasItem(draft["slug"])))
            .body("news", not(hasItem(future["slug"])))
    }

    @Test
    fun `слаг делается из заголовка транслитом и не повторяется`() {
        val title = "Не сдаёт назад ${UUID.randomUUID().toString().take(4)}"
        val first = createNews(title)
        val second = createNews(title)
        assert((first["slug"] as String).startsWith("ne-sdayot-nazad-")) { first["slug"].toString() }
        assert(second["slug"] == "${first["slug"]}-2") { second["slug"].toString() }
    }

    @Test
    fun `занятый явный слаг — 409, кривой — 400`() {
        val slug = "zanyat-${UUID.randomUUID().toString().take(8)}"
        createNews(unique("Первый"), slug = slug)
        admin().body(mapOf("title" to "Второй", "slug" to slug)).post("/api/admin/v1/news")
            .then().statusCode(409).body("error", equalTo("conflict"))
        admin().body(mapOf("title" to "Кривой", "slug" to "Не Латиница")).post("/api/admin/v1/news")
            .then().statusCode(400).body("error", equalTo("bad_request"))
    }

    @Test
    fun `правка и удаление`() {
        val created = createNews(unique("Правка"))
        val id = created["id"]
        admin().body(created + mapOf("title" to "Исправлено", "lead" to "Подзаголовок"))
            .put("/api/admin/v1/news/$id")
            .then().statusCode(200).body("title", equalTo("Исправлено")).body("slug", equalTo(created["slug"]))
        given().get("/api/v1/news/${created["slug"]}").then().body("lead", equalTo("Подзаголовок"))

        admin().delete("/api/admin/v1/news/$id").then().statusCode(204)
        admin().get("/api/admin/v1/news/$id").then().statusCode(404)
        given().get("/api/v1/news/${created["slug"]}").then().statusCode(404)
    }

    // Пагинация

    @Test
    fun `страницы считаются с единицы, за пределами — пусто`() {
        repeat(3) { createNews(unique("Лента")) }
        given().get("/api/v1/news?page=1&size=2").then().statusCode(200)
            .body("items", hasSize<Any>(2))
            .body("page", equalTo(1))
            .body("size", equalTo(2))
        val total = given().get("/api/v1/news?size=2").then().extract().path<Int>("total")
        val pages = (total + 1) / 2
        given().get("/api/v1/news?page=${pages + 5}&size=2").then().statusCode(200)
            .body("items", hasSize<Any>(0))
            .body("totalPages", equalTo(pages))
        given().get("/api/v1/news?page=0").then().statusCode(400)
        given().get("/api/v1/news?size=101").then().statusCode(400)
        given().get("/api/v1/news?page=abc").then().statusCode(400).body("error", equalTo("bad_request"))
    }

    @Test
    fun `нет материала по id в админке — 404`() {
        given().header("Authorization", "Bearer test-token").get("/api/admin/v1/news/999999")
            .then().statusCode(404).body("error", equalTo("not_found"))
        given().header("Authorization", "Bearer test-token").delete("/api/admin/v1/media/999999")
            .then().statusCode(404)
    }

    // Статьи и «Читайте также»

    @Test
    fun `статья целиком и «Читайте также» без неё самой`() {
        val cover = mapOf("src" to "https://media.test/a.jpg", "alt" to "Обложка", "width" to 1032, "height" to 688)
        val article = admin()
            .body(
                mapOf(
                    "title" to unique("Статья"),
                    "status" to "PUBLISHED",
                    "publishedAt" to OffsetDateTime.now().minusMinutes(5).toString(),
                    "announce" to "Анонс",
                    "summary" to listOf("Первый абзац", "Второй"),
                    "body" to "Текст\n\n:::quote\nЦитата\n:::\n",
                    "references" to listOf("Маркс К. Капитал"),
                    "tags" to listOf("курьеры", "забастовки"),
                    "cover" to cover,
                ),
            )
            .post("/api/admin/v1/articles").then().statusCode(201).extract().jsonPath().getMap<String, Any?>("")

        given().get("/api/v1/articles/${article["slug"]}").then().statusCode(200)
            .body("summary", hasSize<Any>(2))
            .body("references[0]", equalTo("Маркс К. Капитал"))
            .body("tags", hasItem("курьеры"))
            .body("cover.width", equalTo(1032))
            .body("publishedAt", notNullValue())

        given().get("/api/v1/articles?tag=курьеры").then().statusCode(200)
            .body("items.slug", hasItem(article["slug"]))

        given().get("/api/v1/related/${article["slug"]}?limit=3").then().statusCode(200)
            .body("slug", not(hasItem(article["slug"])))
            .body("kind", everyItem(org.hamcrest.Matchers.oneOf("news", "article")))
    }

    @Test
    fun `обложка без размеров не принимается`() {
        admin().body(mapOf("title" to "Без размеров", "cover" to mapOf("src" to "https://media.test/x.jpg")))
            .post("/api/admin/v1/articles")
            .then().statusCode(400)
    }

    // Газета, книги, журнал, видео

    @Test
    fun `выпуск газеты с материалами`() {
        val slug = "${(1000..9999).random()}"
        admin().body(
            mapOf(
                "title" to "Их победа и наша",
                "slug" to slug,
                "number" to "№$slug",
                "status" to "PUBLISHED",
                "publishedAt" to OffsetDateTime.now().minusDays(1).toString(),
                "contents" to listOf(
                    mapOf("title" to "Курьеры", "href" to "/articles/kurery"),
                    mapOf("title" to "Без ссылки"),
                ),
            ),
        ).post("/api/admin/v1/newspaper").then().statusCode(201)

        given().get("/api/v1/newspaper/$slug").then().statusCode(200)
            .body("number", equalTo("№$slug"))
            .body("contents", hasSize<Any>(2))
            .body("contents[0].href", equalTo("/articles/kurery"))
        given().get("/api/v1/newspaper?size=10").then().statusCode(200).body("size", equalTo(10))
    }

    @Test
    fun `книги по порядку, журнал и видео`() {
        val suffix = UUID.randomUUID().toString().take(6)
        admin().body(mapOf("title" to "Вторая $suffix", "status" to "PUBLISHED", "position" to 2)).post("/api/admin/v1/books").then().statusCode(201)
        admin().body(mapOf("title" to "Первая $suffix", "status" to "PUBLISHED", "position" to 1, "buyUrl" to "https://avito.ru/x")).post("/api/admin/v1/books").then().statusCode(201)
        val titles = given().get("/api/v1/books").then().statusCode(200).extract().path<List<String>>("title")
        assert(titles.indexOf("Первая $suffix") < titles.indexOf("Вторая $suffix")) { titles.toString() }

        admin().body(mapOf("title" to "Плохая ссылка", "buyUrl" to "avito")).post("/api/admin/v1/books").then().statusCode(400)

        admin().body(
            mapOf(
                "title" to "Журнал $suffix",
                "status" to "PUBLISHED",
                "specs" to listOf(mapOf("label" to "Год издания", "value" to "2025")),
                "contents" to "В номере:\n\n- Курьеры",
            ),
        ).post("/api/admin/v1/magazine").then().statusCode(201)
        given().get("/api/v1/magazine").then().statusCode(200).body("specs.flatten().label", hasItem("Год издания"))

        admin().body(mapOf("title" to "Ролик $suffix", "status" to "PUBLISHED", "url" to "https://youtu.be/dQw4w9WgXcQ"))
            .post("/api/admin/v1/videos").then().statusCode(201)
        given().get("/api/v1/videos").then().statusCode(200).body("url", hasItem("https://youtu.be/dQw4w9WgXcQ"))
        admin().body(mapOf("title" to "Без ссылки", "url" to "")).post("/api/admin/v1/videos").then().statusCode(400)
    }

    @Test
    fun `главная — по нескольку свежих материалов каждого вида`() {
        repeat(4) { createNews(unique("Главная")) }
        given().get("/api/v1/home").then().statusCode(200)
            .body("news", hasSize<Any>(3))
            .body("articles", notNullValue())
            .body("issues", notNullValue())
            .body("videos", notNullValue())
    }

    // Поддержка

    @Test
    fun `кошельки заменяются целиком, порядок сохраняется`() {
        admin().body(
            mapOf(
                "wallets" to listOf(
                    mapOf("network" to "BTC", "label" to "Биткоин", "address" to "bc1qtest"),
                    mapOf("network" to "XMR", "address" to "44test"),
                ),
                "shopUrl" to "https://www.avito.ru/militant",
            ),
        ).put("/api/admin/v1/support").then().statusCode(200)

        given().get("/api/v1/support").then().statusCode(200)
            .body("wallets.network", equalTo(listOf("BTC", "XMR")))
            .body("wallets[0].label", equalTo("Биткоин"))
            .body("shopUrl", equalTo("https://www.avito.ru/militant"))

        admin().body(mapOf("wallets" to listOf(mapOf("network" to "BTC", "address" to "с пробелом"))))
            .put("/api/admin/v1/support").then().statusCode(400)
    }

    // Медиатека

    @Test
    fun `загрузка картинки — размеры считаются сами, адрес от media url`() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        given().header("Authorization", "Bearer test-token")
            .multiPart("file", "cover.png", png, "image/png")
            // RestAssured по умолчанию шлёт текстовые части в ISO-8859-1, браузер — в UTF-8
            .multiPart("alt", "Обложка", "text/plain; charset=UTF-8")
            .post("/api/admin/v1/media")
            .then().statusCode(201)
            .body("width", equalTo(40))
            .body("height", equalTo(30))
            .body("alt", equalTo("Обложка"))
            .body("src", org.hamcrest.Matchers.startsWith("https://media.test/"))

        given().header("Authorization", "Bearer test-token")
            .multiPart("file", "note.txt", "текст".toByteArray(), "text/plain")
            .post("/api/admin/v1/media")
            .then().statusCode(400)
    }
}
