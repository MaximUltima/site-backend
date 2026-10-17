package com.code5150.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "articles")
class Article : Content() {
    @Column(name = "announce", nullable = false)
    var announce: String = ""

    @Convert(converter = StringListConverter::class)
    @Column(name = "summary")
    var summary: List<String>? = null

    @Column(name = "body", nullable = false)
    var body: String = ""

    @Convert(converter = StringListConverter::class)
    @Column(name = "reference_list")
    var references: List<String>? = null

    @Convert(converter = StringListConverter::class)
    @Column(name = "tags")
    var tags: List<String>? = null

    @Convert(converter = MediaRefConverter::class)
    @Column(name = "cover")
    var cover: MediaRef? = null
}

@Entity
@Table(name = "news")
class News : Content() {
    @Column(name = "announce", nullable = false)
    var announce: String = ""

    @Column(name = "lead")
    var lead: String? = null

    @Column(name = "body", nullable = false)
    var body: String = ""

    @Convert(converter = MediaRefConverter::class)
    @Column(name = "cover")
    var cover: MediaRef? = null
}

@Entity
@Table(name = "newspaper_issues")
class NewspaperIssue : Content() {
    /** Номер, как он печатается: «№34». */
    @Column(name = "number", nullable = false)
    var number: String = ""

    @Convert(converter = IssueEntryListConverter::class)
    @Column(name = "contents")
    var contents: List<IssueEntry>? = null

    @Convert(converter = MediaRefConverter::class)
    @Column(name = "cover")
    var cover: MediaRef? = null

    @Column(name = "pdf_url")
    var pdfUrl: String? = null
}

@Entity
@Table(name = "books")
class Book : Content() {
    @Column(name = "description", nullable = false)
    var description: String = ""

    @Convert(converter = MediaRefConverter::class)
    @Column(name = "cover")
    var cover: MediaRef? = null

    @Column(name = "buy_url")
    var buyUrl: String? = null

    /** Порядок на странице «Литература» — его задаёт редакция. */
    @Column(name = "position", nullable = false)
    var position: Int = 0
}

@Entity
@Table(name = "magazine_issues")
class MagazineIssue : Content() {
    @Convert(converter = MediaRefConverter::class)
    @Column(name = "cover")
    var cover: MediaRef? = null

    @Convert(converter = SpecListConverter::class)
    @Column(name = "specs")
    var specs: List<Spec>? = null

    /** «Содержание» — markdown. */
    @Column(name = "contents", nullable = false)
    var contents: String = ""

    @Column(name = "buy_url")
    var buyUrl: String? = null
}

@Entity
@Table(name = "videos")
class Video : Content() {
    @Column(name = "url", nullable = false)
    var url: String = ""

    @Convert(converter = MediaRefConverter::class)
    @Column(name = "preview")
    var preview: MediaRef? = null
}

/** Загруженный файл медиатеки. Адрес собирается из media.url и path. */
@Entity
@Table(name = "media")
class Media {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "path", nullable = false)
    lateinit var path: String

    @Column(name = "mime", nullable = false)
    lateinit var mime: String

    @Column(name = "width", nullable = false)
    var width: Int = 0

    @Column(name = "height", nullable = false)
    var height: Int = 0

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0

    @Column(name = "alt", nullable = false)
    var alt: String = ""

    @Column(name = "caption")
    var caption: String? = null

    @Column(name = "credit")
    var credit: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()
}

@Entity
@Table(name = "wallets")
class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "network", nullable = false)
    lateinit var network: String

    @Column(name = "label")
    var label: String? = null

    @Column(name = "address", nullable = false)
    lateinit var address: String

    @Column(name = "position", nullable = false)
    var position: Int = 0
}

@Entity
@Table(name = "settings")
class Setting {
    @Id
    @Column(name = "key")
    lateinit var key: String

    @Column(name = "value")
    var value: String? = null

    companion object {
        const val SHOP_URL = "shop_url"
    }
}
