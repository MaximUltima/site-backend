package com.code5150.dto

import java.time.LocalDateTime

data class ArticleDTO(
    val id: Long? = null,
    val title: String,
    val date: LocalDateTime,
    val content: String
)
