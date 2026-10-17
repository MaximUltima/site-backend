package com.code5150.api.admin

import com.code5150.dto.MediaMetaForm
import com.code5150.dto.SupportForm
import com.code5150.service.AdminSupportService
import com.code5150.service.MediaService
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.ResponseStatus
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload

/** Страница «Поддержать»: кошельки и ссылка на магазин. */
@Path("/api/admin/v1/support")
@Produces(MediaType.APPLICATION_JSON)
class AdminSupportResource(private val service: AdminSupportService) {

    @GET
    suspend fun get() = service.get()

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun replace(form: SupportForm) = service.replace(form)
}

/**
 * Медиатека.
 *
 *   POST /media   multipart: file, alt, caption, credit, (width, height — для WebP/AVIF)
 *   GET  /media?page&size
 *   PUT  /media/{id}   { alt, caption, credit }
 *   DELETE /media/{id}
 */
@Path("/api/admin/v1/media")
@Produces(MediaType.APPLICATION_JSON)
class AdminMediaResource(private val service: MediaService) {

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @ResponseStatus(201)
    suspend fun upload(
        @RestForm("file") file: FileUpload?,
        @RestForm("alt") alt: String?,
        @RestForm("caption") caption: String?,
        @RestForm("credit") credit: String?,
        @RestForm("width") width: Int?,
        @RestForm("height") height: Int?,
    ) = service.upload(
        source = file?.uploadedFile() ?: throw BadRequestException("Нужен файл в поле file"),
        declaredType = file.contentType(),
        fileName = file.fileName(),
        meta = MediaMetaForm(alt.orEmpty(), caption, credit),
        width = width,
        height = height,
    )

    @GET
    suspend fun list(@QueryParam("page") page: Int?, @QueryParam("size") size: Int?) = service.list(page, size)

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    suspend fun update(@PathParam("id") id: Long, form: MediaMetaForm) = service.updateMeta(id, form)

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    suspend fun delete(@PathParam("id") id: Long) = service.delete(id)
}
