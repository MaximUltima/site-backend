package com.code5150.entity

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/*
 * Вложенные структуры (списки, картинка, характеристики) хранятся в text-колонках
 * как JSON через обычные AttributeConverter: без зависимости от того, как Hibernate
 * Reactive обращается с jsonb и массивами, и без отдельных таблиц ради списков строк.
 */

internal val columnJson: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** Картинка в том виде, в каком её отдаёт API (docs/api.md, «Картинки»). */
data class MediaRef(
    val src: String,
    val alt: String = "",
    val width: Int,
    val height: Int,
    val caption: String? = null,
    val credit: String? = null,
)

/** Материал в выпуске газеты. */
data class IssueEntry(val title: String, val href: String? = null)

/** Строка характеристик журнала. */
data class Spec(val label: String, val value: String)

abstract class JsonConverter<T : Any>(private val type: TypeReference<T>) : AttributeConverter<T?, String?> {
    override fun convertToDatabaseColumn(attribute: T?): String? =
        attribute?.let { columnJson.writeValueAsString(it) }

    override fun convertToEntityAttribute(dbData: String?): T? =
        dbData?.takeIf { it.isNotBlank() }?.let { columnJson.readValue(it, type) }
}

@Converter
class StringListConverter : JsonConverter<List<String>>(object : TypeReference<List<String>>() {})

@Converter
class MediaRefConverter : JsonConverter<MediaRef>(object : TypeReference<MediaRef>() {})

@Converter
class IssueEntryListConverter : JsonConverter<List<IssueEntry>>(object : TypeReference<List<IssueEntry>>() {})

@Converter
class SpecListConverter : JsonConverter<List<Spec>>(object : TypeReference<List<Spec>>() {})
