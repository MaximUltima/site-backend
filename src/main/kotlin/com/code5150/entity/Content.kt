package com.code5150.entity

import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import java.time.OffsetDateTime

/** Статус материала. Публичное API отдаёт только PUBLISHED с наступившей датой. */
enum class Status { DRAFT, PUBLISHED, ARCHIVED }

/**
 * Общее для всего контента сайта: адрес, статус, дата публикации,
 * откуда импортировано, служебные даты.
 */
@MappedSuperclass
abstract class Content {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "slug", nullable = false)
    lateinit var slug: String

    @Column(name = "title", nullable = false)
    lateinit var title: String

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: Status = Status.DRAFT

    /** Дата на сайте. В будущем — материал появится сам, когда она наступит. */
    @Column(name = "published_at", nullable = false)
    var publishedAt: OffsetDateTime = OffsetDateTime.now()

    /** Ссылка на оригинал при импорте из Телеграма или Телеграфа — защита от дублей. */
    @Column(name = "source_url")
    var sourceUrl: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime? = null

    @PrePersist
    fun onCreate() {
        createdAt = OffsetDateTime.now()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}
