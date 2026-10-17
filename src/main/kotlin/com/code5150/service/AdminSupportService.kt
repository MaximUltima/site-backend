package com.code5150.service

import com.code5150.dto.SupportDto
import com.code5150.dto.SupportForm
import com.code5150.entity.Setting
import com.code5150.entity.Wallet
import com.code5150.repository.SettingRepository
import com.code5150.repository.WalletRepository
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import org.jboss.logging.Logger

/**
 * Кошельки и ссылка на магазин. Список кошельков заменяется целиком —
 * так проще, чем править по одному, и порядок задаётся порядком в форме.
 */
@ApplicationScoped
class AdminSupportService(
    private val wallets: WalletRepository,
    private val settings: SettingRepository,
    private val publicContent: PublicContentService,
    private val revalidator: Revalidator,
) {
    private val log = Logger.getLogger(AdminSupportService::class.java)

    suspend fun get(): SupportDto = publicContent.support()

    suspend fun replace(form: SupportForm): SupportDto {
        form.wallets.forEachIndexed { index, wallet ->
            if (wallet.network.isBlank()) throw BadRequestException("wallets[$index].network пустой")
            if (wallet.address.isBlank()) throw BadRequestException("wallets[$index].address пустой")
            if (wallet.address.any { it.isWhitespace() }) {
                throw BadRequestException("wallets[$index].address: в адресе не может быть пробелов")
            }
        }
        val shopUrl = form.shopUrl?.trim()?.takeIf { it.isNotEmpty() }
        shopUrl?.let { AdminContentService.requireUrl("shopUrl", it) }

        Panache.withTransaction {
            wallets.deleteAll().flatMap {
                val entities = form.wallets.mapIndexed { index, w ->
                    Wallet().apply {
                        network = w.network.trim()
                        label = w.label?.trim()?.takeIf { it.isNotEmpty() }
                        address = w.address.trim()
                        position = index
                    }
                }
                if (entities.isEmpty()) Uni.createFrom().voidItem() else wallets.persist(entities)
            }.flatMap {
                settings.findById(Setting.SHOP_URL).flatMap { existing ->
                    val setting = existing ?: Setting().apply { key = Setting.SHOP_URL }
                    setting.value = shopUrl
                    settings.persist(setting).replaceWithVoid()
                }
            }
        }.awaitSuspending()

        // Адреса для денег меняются редко и важно: оставляем след в логе.
        log.infof("Кошельки обновлены: %d шт., магазин: %s", form.wallets.size, shopUrl ?: "—")
        revalidator.revalidate(listOf("support"))
        return get()
    }
}
