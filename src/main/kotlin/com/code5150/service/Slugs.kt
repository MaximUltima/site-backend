package com.code5150.service

/**
 * Слаг из заголовка: транслит по упрощённой схеме, как в адресах фикстур
 * сайта («Не сдаёт назад» → ne-sdayot-nazad).
 */
object Slugs {
    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    private val VALID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    const val MAX_LENGTH = 80

    fun fromTitle(title: String): String {
        val latin = buildString {
            for (char in title.lowercase()) {
                append(TRANSLIT[char] ?: if (char in 'a'..'z' || char in '0'..'9') char.toString() else "-")
            }
        }
        val slug = latin.replace(Regex("-+"), "-").trim('-')
        return slug.take(MAX_LENGTH).trimEnd('-').ifEmpty { "material" }
    }

    fun isValid(slug: String): Boolean = slug.length <= MAX_LENGTH * 2 && VALID.matches(slug)
}
