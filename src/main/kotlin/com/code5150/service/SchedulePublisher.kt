package com.code5150.service

import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SchedulePublisher(
    private val store: VersionedContentStore,
    private val revalidator: Revalidator,
) {
    @Scheduled(every = "30s", delay = 1)
    fun publishDue(): Uni<Void> = store.runDueUni().onItem().invoke { published ->
        published.forEach { row ->
            val version = row["version"] as? Map<*, *> ?: return@forEach
            val kind = version["kind"].toString()
            val tags = mutableListOf(kind, "$kind:${version["slug"]}")
            if (kind in setOf("news", "articles", "newspaper", "videos")) tags += "home"
            revalidator.revalidate(tags)
        }
    }.replaceWithVoid()
}
