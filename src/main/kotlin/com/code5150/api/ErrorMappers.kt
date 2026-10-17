package com.code5150.api

import com.code5150.dto.ErrorDto
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Ошибки — в форме контракта: { error, message } с кодом 400 / 404 / 409 / 500.
 * Текст 500 не раскрывает внутренностей: подробности — в логе.
 */
class ErrorMappers {
    private val log = Logger.getLogger(ErrorMappers::class.java)

    @ServerExceptionMapper
    fun notFound(e: NotFoundException): Response = error(404, "not_found", e.message ?: "Не найдено")

    @ServerExceptionMapper
    fun badRequest(e: BadRequestException): Response = error(400, "bad_request", e.message ?: "Неверный запрос")

    @ServerExceptionMapper
    fun badJson(e: com.fasterxml.jackson.core.JsonProcessingException): Response =
        error(400, "bad_request", "Неверный JSON: ${e.originalMessage}")

    @ServerExceptionMapper
    fun conflict(e: ConflictException): Response = error(409, "conflict", e.message ?: "Конфликт")

    @ServerExceptionMapper
    fun webApplication(e: WebApplicationException): Response {
        val status = e.response.status
        val code = when (status) {
            401 -> "unauthorized"
            403 -> "forbidden"
            404 -> "not_found"
            405 -> "method_not_allowed"
            415 -> "unsupported_media_type"
            else -> if (status < 500) "bad_request" else "internal"
        }
        return error(status, code, e.message ?: code)
    }

    @ServerExceptionMapper
    fun unexpected(e: Exception): Response {
        log.error("Необработанная ошибка", e)
        return error(500, "internal", "Внутренняя ошибка сервера")
    }

    private fun error(status: Int, code: String, message: String): Response =
        Response.status(status).type(MediaType.APPLICATION_JSON).entity(ErrorDto(code, message)).build()
}

/** Слаг или источник импорта уже заняты. */
class ConflictException(message: String) : RuntimeException(message)
