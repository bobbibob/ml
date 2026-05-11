package com.ml.app.data.ml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Интерфейс для абстракции источника Mercado Livre данных.
 * Позволяет легко переключаться между парсингом и официальным API.
 */
interface MercadoLivreDataSource {
    
    /**
     * Получить или обновить сессию пользователя
     */
    suspend fun getOrCreateSession(): Result<MlSessionToken>
    
    /**
     * Получить список заказов за период
     */
    suspend fun getOrders(
        dateFrom: LocalDate,
        dateTo: LocalDate = LocalDate.now()
    ): Result<List<MlOrder>>
    
    /**
     * Получить инвентарь (товары в продаже)
     */
    suspend fun getInventory(): Result<List<MlProduct>>
    
    /**
     * Получить аналитику продаж
     */
    suspend fun getSalesAnalytics(
        dateFrom: LocalDate,
        dateTo: LocalDate = LocalDate.now()
    ): Result<MlSalesAnalytics>
    
    /**
     * Получить информацию об объявлениях (для проверки остатков)
     */
    suspend fun getListings(): Result<List<MlListing>>
    
    /**
     * Проверить валидность сессии
     */
    suspend fun isSessionValid(): Boolean
    
    /**
     * Очистить кэш сессии
     */
    suspend fun clearSession()
}

// ==================== DATA MODELS ====================

data class MlSessionToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val userId: String,
    val userEmail: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isExpired(): Boolean {
        val expirationTime = createdAt + (expiresIn * 1000)
        return System.currentTimeMillis() > expirationTime
    }
}

data class MlOrder(
    val id: String,
    val orderId: String,
    val buyerNickname: String,
    val buyerEmail: String,
    val totalAmount: Double,
    val itemCount: Int,
    val orderDate: Long,
    val status: String,
    val items: List<MlOrderItem>,
    val shippingAddress: MlShippingAddress? = null
)

data class MlOrderItem(
    val itemId: String,
    val title: String,
    val sku: String?,
    val quantity: Int,
    val unitPrice: Double,
    val variationId: String? = null,
    val variationAttributes: Map<String, String> = emptyMap()
)

data class MlShippingAddress(
    val receiverName: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String
)

data class MlProduct(
    val id: String,
    val title: String,
    val sku: String,
    val price: Double,
    val listingPrice: Double? = null,
    val availableQuantity: Int,
    val catalogProductId: String? = null,
    val thumbnailUrl: String? = null,
    val status: String,
    val lastUpdate: Long,
    val variations: List<MlVariation> = emptyList()
)

data class MlVariation(
    val id: String,
    val name: String,
    val price: Double,
    val availableQuantity: Int
)

data class MlSalesAnalytics(
    val totalSalesAmount: Double,
    val totalOrders: Int,
    val averageOrderValue: Double,
    val conversionRate: Double?,
    val salesByStatus: Map<String, Int> = emptyMap(),
    val salesByCategory: Map<String, Double> = emptyMap(),
    val period: AnalyticsPeriod
)

data class AnalyticsPeriod(
    val startDate: LocalDate,
    val endDate: LocalDate
)

data class MlListing(
    val id: String,
    val title: String,
    val sku: String,
    val price: Double,
    val availableQuantity: Int,
    val totalQuantity: Int,
    val soldQuantity: Int,
    val status: String,
    val url: String,
    val imageUrl: String? = null
)

// ==================== EXTENSION FUNCTIONS ====================

suspend inline fun <T> MercadoLivreDataSource.withValidSession(
    block: suspend (token: MlSessionToken) -> Result<T>
): Result<T> = withContext(Dispatchers.IO) {
    if (!isSessionValid()) {
        clearSession()
    }
    
    val sessionResult = getOrCreateSession()
    if (sessionResult.isFailure) {
        return@withContext sessionResult.mapCatching { error(it.exceptionOrNull()?.message ?: "Unknown error") }
    }
    
    sessionResult.mapCatching { token ->
        block(token).getOrThrow()
    }
}

// ==================== UTILITIES ====================

sealed class MlDataSourceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthenticationFailed(message: String) : MlDataSourceError(message)
    class RateLimited(val retryAfter: Long) : MlDataSourceError("Rate limited. Retry after $retryAfter ms")
    class InvalidResponse(message: String) : MlDataSourceError(message)
    class NetworkError(cause: Throwable) : MlDataSourceError("Network error", cause)
    class SessionExpired : MlDataSourceError("Session expired")
    class UnknownError(message: String, cause: Throwable? = null) : MlDataSourceError(message, cause)
}

fun <T> resultOf(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block as T)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
