package com.ml.app.di

import android.content.Context
import com.ml.app.BuildConfig
import com.ml.app.data.ml.MercadoLivreDataSource
import com.ml.app.data.ml.MercadoLivreApiDataSource
import com.ml.app.data.ml.MercadoLivreParsingDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton провайдер для MercadoLivreDataSource.
 * Автоматически выбирает между API и парсингом на основе BuildConfig.
 */
object MlDataSourceProvider {
    
    private var instance: MercadoLivreDataSource? = null
    
    /**
     * Получить или создать MercadoLivreDataSource
     */
    fun getInstance(
        context: Context,
        apiKey: String? = null,
        apiSecret: String? = null
    ): MercadoLivreDataSource {
        if (instance != null) {
            return instance!!
        }
        
        instance = if (BuildConfig.USE_ML_API) {
            // Используем официальный API
            MercadoLivreApiDataSource(
                apiKey = apiKey ?: System.getenv("ML_API_KEY") ?: "",
                apiSecret = apiSecret ?: System.getenv("ML_API_SECRET") ?: "",
                httpClient = createHttpClient(),
                context = context
            )
        } else {
            // Используем парсинг (текущая реализация)
            MercadoLivreParsingDataSource(context)
        }
        
        return instance!!
    }
    
    /**
     * Сбросить экземпляр (например, при смене учётной записи)
     */
    fun reset() {
        instance = null
    }
    
    /**
     * Переключить источник данных (для тестирования)
     */
    fun setInstance(dataSource: MercadoLivreDataSource) {
        instance = dataSource
    }
    
    private fun createHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Extension функция для быстрого доступа к MercadoLivreDataSource
 */
fun Context.getMercadoLivreDataSource(): MercadoLivreDataSource {
    return MlDataSourceProvider.getInstance(this)
}
