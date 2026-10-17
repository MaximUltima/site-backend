package com.code5150.api

import com.code5150.dto.ErrorDto
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import java.security.MessageDigest
import java.util.Optional

/**
 * Доступ к /api/admin/v1: заголовок Authorization: Bearer <токен>,
 * токены — в admin.tokens (ADMIN_TOKENS). Временная схема до входа
 * по логину, паролю и TOTP (docs/admin-plan.md, этап A2).
 */
class AdminAuthFilter(
    @ConfigProperty(name = "admin.tokens") private val tokens: Optional<List<String>>,
) {
    private val allowed: List<ByteArray> =
        tokens.orElse(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.map { it.toByteArray() }

    @ServerRequestFilter
    fun check(context: ContainerRequestContext): Response? {
        if (!context.uriInfo.path.startsWith("/api/admin/")) return null
        val header = context.getHeaderString(HttpHeaders.AUTHORIZATION).orEmpty()
        val token = header.removePrefix("Bearer ").trim().toByteArray()
        // Сравнение за постоянное время: не подсказывать токен по времени ответа.
        val ok = header.startsWith("Bearer ") && allowed.any { MessageDigest.isEqual(it, token) }
        return if (ok) {
            null
        } else {
            Response.status(401)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorDto("unauthorized", "Нужен токен администратора"))
                .build()
        }
    }
}
