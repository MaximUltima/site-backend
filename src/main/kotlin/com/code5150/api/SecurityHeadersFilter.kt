package com.code5150.api

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import org.jboss.resteasy.reactive.server.ServerResponseFilter

class SecurityHeadersFilter {
    @ServerResponseFilter
    fun headers(request: ContainerRequestContext, response: ContainerResponseContext) {
        response.headers.putSingle("X-Content-Type-Options", "nosniff")
        if (request.uriInfo.path.startsWith("/api/")) {
            response.headers.putSingle("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        }
    }
}
