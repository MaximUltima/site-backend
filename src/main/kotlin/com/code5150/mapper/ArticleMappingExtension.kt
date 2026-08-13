package com.code5150.mapper

import com.code5150.dto.ArticleDTO
import com.code5150.entity.Article

fun Article.toDto(): ArticleDTO {
    return ArticleDTO(this.id!!, this.title, this.publishDate, this.content)
}

fun ArticleDTO.toEntity(): Article {
    val self = this
    return Article().apply {
        id = self.id
        title = self.title
        publishDate = self.date
        content = self.content
    }
}