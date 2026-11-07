package com.code5150.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import kotlin.math.ceil

@ApplicationScoped
class PublicContentService(
    private val store: VersionedContentStore,
    private val downloads: DownloadService,
    @ConfigProperty(name = "files.public-base") private val filesPublicBase: String,
) {
    suspend fun home(lang: String): Map<String, Any?> = mapOf(
        "news" to store.list("news", lang).take(3).map { refs(lang, summary(it)) },
        "articles" to store.list("articles", lang).take(4).map { refs(lang, summary(it)) },
        "issues" to store.list("newspaper", lang).take(3).map { clean(it) },
        "videos" to store.list("videos", lang).take(2).map { clean(it) },
    )

    suspend fun list(kind: String, lang: String, page: Int?, size: Int?, tag: String? = null): Map<String, Any?> {
        val p = (page ?: 1).coerceIn(1, 100_000)
        val n = (size ?: if (kind == "newspaper") 10 else 20).coerceIn(1, 100)
        val all = store.list(kind, lang, tag = tag)
        val items = all.drop((p - 1) * n).take(n).map {
            when (kind) {
                "news", "articles" -> refs(lang, summary(it))
                else -> clean(it)
            }
        }
        return mapOf(
            "items" to items, "page" to p, "size" to n, "total" to all.size,
            "totalPages" to maxOf(1, ceil(all.size.toDouble() / n).toInt()),
        )
    }

    suspend fun full(kind: String, data: MutableMap<String, Any?>, lang: String): MutableMap<String, Any?> {
        val actualLang = data["lang"]?.toString() ?: lang
        val publication = store.publicationMeta(kind, data["slug"].toString(), actualLang)
        val files = filesOut(data["files"])
        val out = data.toMutableMap()
        out.remove("files")
        val cleaned = refs(lang, clean(out))
        if (files.isNotEmpty()) cleaned["files"] = files
        if (publication.count > 1) cleaned["updatedAt"] = publication.last
        return cleaned
    }

    suspend fun one(kind: String, slug: String, lang: String): MutableMap<String, Any?>? =
        store.one(kind, slug, lang)?.let { full(kind, it, lang) }

    suspend fun simpleOne(kind: String, slug: String, lang: String): MutableMap<String, Any?>? =
        store.one(kind, slug, lang)?.let { clean(it) }

    suspend fun categories(lang: String): List<MutableMap<String, Any?>> = store.live("categories", lang)
        .sortedBy { (it["position"] as? Number)?.toInt() ?: 0 }.map { clean(it) }

    suspend fun books(lang: String): List<MutableMap<String, Any?>> = store.live("books", lang)
        .sortedBy { (it["position"] as? Number)?.toInt() ?: 0 }.map { clean(it) }

    suspend fun array(kind: String, lang: String): List<MutableMap<String, Any?>> = store.list(kind, lang).map { clean(it) }

    suspend fun support(lang: String): MutableMap<String, Any?> =
        store.one("support", "support", lang)?.let { clean(it) } ?: mutableMapOf("wallets" to emptyList<Any>())

    suspend fun taxon(kind: String, slug: String, lang: String, page: Int?, size: Int?): MutableMap<String, Any?>? {
        val own = simpleOne(kind, slug, lang) ?: return null
        val key = if (kind == "categories") "category" else "author"
        val p = (page ?: 1).coerceIn(1, 100_000)
        val n = (size ?: 20).coerceIn(1, 100)
        val articles = store.list("articles", lang, category = slug.takeIf { key == "category" }, author = slug.takeIf { key == "author" })
        own["items"] = articles.drop((p - 1) * n).take(n).map { refs(lang, summary(it)) }
        own["page"] = p
        own["size"] = n
        own["total"] = articles.size
        own["totalPages"] = maxOf(1, ceil(articles.size.toDouble() / n).toInt())
        own["news"] = store.list("news", lang, category = slug.takeIf { key == "category" }, author = slug.takeIf { key == "author" })
            .take(10).map { refs(lang, summary(it)) }
        return own
    }

    suspend fun related(slug: String, lang: String, kind: String?, limit: Int?): List<Map<String, Any?>> {
        val kinds = if (kind == "article" || kind == "news") listOf(kind) else listOf("article", "news")
        val self = kinds.firstNotNullOfOrNull { k ->
            store.one(relatedKind(k), slug, lang)?.takeIf { it["fallback"] != true }?.let { it to k }
        } ?: return emptyList()
        // Непустое ручное поле related задаёт подборку целиком (автоподборки нет).
        val manual = manualItems(self.first["related"])
        if (manual != null) return manualRelated(manual, self.second, slug, lang, limit)
        val candidates = listOf("article", "news").flatMap { k ->
            store.list(relatedKind(k), lang).filter { it["fallback"] != true && !(k == self.second && it["slug"] == slug) }
                .map { it to k }
        }
        return candidates.map { candidate ->
            val score = 3 * common(candidate.first["categories"], self.first["categories"]) +
                2 * common(candidate.first["authors"], self.first["authors"]) + common(candidate.first["tags"], self.first["tags"])
            Triple(candidate, score, candidate.first["publishedAt"]?.toString().orEmpty())
        }.sortedWith(compareByDescending<Triple<Pair<MutableMap<String, Any?>, String>, Int, String>> { it.second }.thenByDescending { it.third })
            .take((limit ?: 3).coerceIn(1, 10)).map { brief(it.first.first, it.first.second) }
    }

    suspend fun next(slug: String, lang: String, kind: String?): Map<String, Any?>? {
        val kinds = if (kind == "article" || kind == "news") listOf(kind) else listOf("article", "news")
        val self = kinds.firstNotNullOfOrNull { k ->
            store.one(relatedKind(k), slug, lang)?.takeIf { it["fallback"] != true }?.let { it to k }
        } ?: return null
        val list = store.list(relatedKind(self.second), lang)
            .filter { it["fallback"] != true && it["slug"] != slug }
            .sortedByDescending { it["publishedAt"]?.toString().orEmpty() }
        val selfDate = self.first["publishedAt"]?.toString().orEmpty()
        val next = list.firstOrNull { it["publishedAt"]?.toString().orEmpty() < selfDate } ?: list.firstOrNull()
        return next?.let { brief(it, self.second) }
    }

    suspend fun search(q: String?, lang: String, kind: String?, limit: Int?, offset: Int?): Map<String, Any?> {
        val lim = (limit ?: 10).coerceIn(1, 20)
        val off = (offset ?: 0).coerceIn(0, 1000)
        val words = q.orEmpty().take(100).lowercase().replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.distinct().take(8)
        if (words.isEmpty()) return mapOf("items" to emptyList<Any>(), "total" to 0, "limit" to lim, "offset" to off)
        val kinds = if (kind in listOf("articles", "news")) listOf(kind!!) else listOf("articles", "news")
        val found = kinds.flatMap { k ->
            store.list(k, lang).filter { data ->
                val haystack = listOf(data["title"], data["announce"], data["body"], data["tags"])
                    .joinToString(" ").lowercase().replace('ё', 'е')
                words.all { haystack.contains(it) }
            }.map { it to k }
        }.sortedByDescending { it.first["publishedAt"]?.toString().orEmpty() }
        val items = found.drop(off).take(lim).map { (data, k) ->
            linkedMapOf<String, Any?>(
                "slug" to data["slug"], "kind" to if (k == "news") "news" else "article",
                "title" to data["title"], "announce" to data["announce"], "cover" to data["cover"],
                "publishedAt" to data["publishedAt"], "fallback" to (data["fallback"] == true),
                "fragment" to fragment(data["body"]?.toString() ?: data["announce"]?.toString().orEmpty(), words),
            )
        }
        return mapOf("items" to items, "total" to found.size, "limit" to lim, "offset" to off)
    }

    suspend fun slugs(lang: String): Map<String, Any?> {
        val live = VersionedContentStore.KINDS.associateWith { store.live(it, lang) }
        val modified = listOf("news", "articles").associateWith { kind ->
            live.getValue(kind).mapNotNull { data ->
                val slug = data["slug"]?.toString() ?: return@mapNotNull null
                val actualLang = data["lang"]?.toString() ?: lang
                val publication = store.publicationMeta(kind, slug, actualLang)
                val date = if (publication.count > 1) publication.last else data["publishedAt"]?.toString()
                date?.let { slug to it }
            }.toMap()
        }
        val untranslated = listOf("news", "articles").associateWith { kind ->
            live.getValue(kind).filter { it["fallback"] == true }.map { it["slug"] }
        }
        return mapOf(
            "news" to live.getValue("news").map { it["slug"] },
            "articles" to live.getValue("articles").map { it["slug"] },
            "categories" to live.getValue("categories").map { it["slug"] },
            "authors" to live.getValue("authors").map { it["slug"] },
            "newspaper" to live.getValue("newspaper").map { it["slug"] },
            "modified" to modified, "untranslated" to untranslated,
        )
    }

    private suspend fun refs(lang: String, data: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val categories = store.live("categories", lang).associate { it["slug"] to mapOf("slug" to it["slug"], "title" to it["title"]) }
        val authors = store.live("authors", lang).associate { it["slug"] to mapOf("slug" to it["slug"], "name" to it["name"]) }
        data["categories"] = stringList(data["categories"]).mapNotNull { categories[it] }
        data["authors"] = stringList(data["authors"]).mapNotNull { authors[it] }
        return data
    }

    private suspend fun filesOut(value: Any?): List<Map<String, Any?>> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val ref = entry as? Map<*, *> ?: return@mapNotNull null
            val id = (ref["id"] as? Number)?.toLong() ?: ref["id"]?.toString()?.toLongOrNull() ?: return@mapNotNull null
            val file = downloads.file(id) ?: return@mapNotNull null
            if (file.status != "active" || file.license == null || file.source == null) return@mapNotNull null
            mapOf(
                "title" to (ref["title"]?.toString()?.takeIf { it.isNotEmpty() } ?: file.name),
                "name" to "${file.name}.${file.ext}", "type" to (DownloadService.TYPES[file.ext]?.second ?: file.ext),
                "size" to file.size, "sha256" to file.sha256,
                "license" to (DownloadService.LICENSES[file.license] ?: file.license), "source" to file.source,
                "url" to "${filesPublicBase.trimEnd('/')}/${file.id}/${file.name}.${file.ext}",
            )
        }
    }

    private fun summary(source: Map<String, Any?>): MutableMap<String, Any?> = source.toMutableMap().also {
        it.remove("body"); it.remove("status"); it.remove("position"); it.remove("files")
        // related — внутреннее поле: подборку сайт получает только через /related.
        it.remove("related")
    }

    private fun clean(source: Map<String, Any?>): MutableMap<String, Any?> = source.toMutableMap().also {
        it.remove("status"); it.remove("position")
        it.remove("related")
    }

    private fun common(a: Any?, b: Any?): Int = stringList(a).toSet().intersect(stringList(b).toSet()).size
    private fun relatedKind(kind: String) = if (kind == "article") "articles" else "news"
    private fun brief(data: Map<String, Any?>, kind: String) = mapOf(
        "kind" to kind, "slug" to data["slug"], "title" to data["title"],
        "announce" to data["announce"], "publishedAt" to data["publishedAt"],
    )

    private val RELATED_SLUG_RE = Regex("^[a-z0-9][a-z0-9-]{0,119}$")
    private val EXTERNAL_URL_RE = Regex("^https://[^\\s\"'<>`]+$", RegexOption.IGNORE_CASE)

    /**
     * Ручное поле related: ссылки на материалы {kind: articles|news, slug}
     * или внешние {url: https://…, title, announce?}. null — поля нет или оно пустое (автоподборка).
     */
    private fun manualItems(raw: Any?): List<Map<String, Any?>>? {
        val list = raw as? List<*> ?: return null
        if (list.isEmpty()) return null
        val out = ArrayList<Map<String, Any?>>()
        val seen = HashSet<String>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val url = map["url"]?.toString()
            if (url != null) {
                if (!EXTERNAL_URL_RE.matches(url)) continue
                val title = map["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val announce = map["announce"]?.toString()?.take(1000) ?: ""
                if (seen.add("url:$url")) out.add(mapOf("url" to url.take(500), "title" to title.take(300), "announce" to announce))
                continue
            }
            val kind = map["kind"]?.toString() ?: continue
            val slug = map["slug"]?.toString() ?: continue
            if (kind != "articles" && kind != "news") continue
            if (!RELATED_SLUG_RE.matches(slug)) continue
            if (seen.add("$kind/$slug")) out.add(mapOf("kind" to kind, "slug" to slug))
        }
        return out
    }

    /** Ручная подборка: порядок списка, недоступное и self пропускаются, потом уже limit. */
    private suspend fun manualRelated(
        items: List<Map<String, Any?>>,
        selfKind: String,
        selfSlug: String,
        lang: String,
        limit: Int?,
    ): List<Map<String, Any?>> {
        val n = (limit ?: 3).coerceIn(1, 10)
        val out = ArrayList<Map<String, Any?>>()
        for (item in items) {
            if (out.size >= n) break
            val url = item["url"]?.toString()
            if (url != null) {
                out.add(mapOf("kind" to "link", "url" to url, "title" to item["title"], "announce" to item["announce"]))
                continue
            }
            val kind = item["kind"]?.toString() ?: continue
            val slug = item["slug"]?.toString() ?: continue
            if (relatedKind(selfKind) == kind && slug == selfSlug) continue
            val data = store.one(kind, slug, lang) ?: continue
            if (data["fallback"] == true) continue
            out.add(brief(data, if (kind == "articles") "article" else "news"))
        }
        return out
    }

    private fun fragment(body: String, words: List<String>): List<Map<String, Any>> {
        val plain = body.replace(Regex("<[^>]*>"), " ")
            .map { if (it in "`*_#>~|\\[]{}") ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim()
        val index = words.map { plain.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (index - 60).coerceAtLeast(0)
        val text = plain.drop(start).take(180)
        val firstHit = words.map { text.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (firstHit == null) return listOf(mapOf("text" to text, "hit" to false))
        val word = words.first { text.lowercase().indexOf(it) == firstHit }
        return listOfNotNull(
            text.take(firstHit).takeIf { it.isNotEmpty() }?.let { mapOf("text" to it, "hit" to false) },
            mapOf("text" to text.substring(firstHit, firstHit + word.length), "hit" to true),
            text.drop(firstHit + word.length).takeIf { it.isNotEmpty() }?.let { mapOf("text" to it, "hit" to false) },
        )
    }
}
