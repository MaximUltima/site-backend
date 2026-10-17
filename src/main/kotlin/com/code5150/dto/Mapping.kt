package com.code5150.dto

import com.code5150.entity.Article
import com.code5150.entity.Book
import com.code5150.entity.MagazineIssue
import com.code5150.entity.News
import com.code5150.entity.NewspaperIssue
import com.code5150.entity.Video
import com.code5150.entity.Wallet

fun News.toSummary() = NewsSummaryDto(slug, title, announce, publishedAt, cover)

fun News.toDto() = NewsItemDto(slug, title, announce, publishedAt, cover, lead, body)

fun Article.toSummary() = ArticleSummaryDto(slug, title, announce, publishedAt, cover, tags)

fun Article.toDto() = ArticleDto(slug, title, announce, publishedAt, cover, tags, summary, body, references)

fun NewspaperIssue.toDto() =
    NewspaperIssueDto(slug, number, title, publishedAt, cover, contents.orEmpty(), pdfUrl)

fun Book.toDto() = BookDto(slug, title, description, cover, buyUrl)

fun MagazineIssue.toDto() = MagazineIssueDto(slug, title, cover, specs.orEmpty(), contents, buyUrl)

fun Video.toDto() = VideoDto(slug, title, url, preview)

fun Wallet.toDto() = WalletDto(network, label, address)
