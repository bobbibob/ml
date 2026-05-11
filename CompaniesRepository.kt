package com.ml.app.data.companies

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// ==================== DATA MODELS ====================

data class Company(
    val id: String = "",
    val name: String = "",
    val apiKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean = name.isNotBlank() && apiKey.isNotBlank()
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("apiKey", apiKey)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): Company {
            return Company(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                apiKey = json.optString("apiKey", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

// ==================== REPOSITORY ====================

class CompaniesRepository(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "ml_companies"
        private const val COMPANIES_KEY = "companies_list"
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Получить все компании
     */
    fun getAllCompanies(): List<Company> {
        val json = prefs.getString(COMPANIES_KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { Company.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Получить компанию по ID
     */
    fun getCompanyById(id: String): Company? {
        return getAllCompanies().find { it.id == id }
    }
    
    /**
     * Проверить есть ли компания с таким API ключом
     */
    fun getCompanyByApiKey(apiKey: String): Company? {
        return getAllCompanies().find { it.apiKey == apiKey }
    }
    
    /**
     * Проверить есть ли компания с таким названием
     */
    fun getCompanyByName(name: String): Company? {
        return getAllCompanies().find { it.name.equals(name, ignoreCase = true) }
    }
    
    /**
     * Добавить новую компанию
     */
    fun addCompany(name: String, apiKey: String): Result<Company> {
        return try {
            val existing = getCompanyByApiKey(apiKey)
            if (existing != null) {
                return Result.failure(Exception("Компания с этим API ключом уже существует"))
            }
            
            val company = Company(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim(),
                apiKey = apiKey.trim()
            )
            
            val companies = getAllCompanies().toMutableList()
            companies.add(company)
            saveCompanies(companies)
            
            Result.success(company)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Обновить компанию
     */
    fun updateCompany(id: String, name: String, apiKey: String): Result<Company> {
        return try {
            val companies = getAllCompanies().toMutableList()
            val index = companies.indexOfFirst { it.id == id }
            
            if (index == -1) {
                return Result.failure(Exception("Компания не найдена"))
            }
            
            // Проверяем что API ключ не занят другой компанией
            val otherWithKey = companies.find { it.id != id && it.apiKey == apiKey.trim() }
            if (otherWithKey != null) {
                return Result.failure(Exception("Этот API ключ уже используется другой компанией"))
            }
            
            val updated = companies[index].copy(
                name = name.trim(),
                apiKey = apiKey.trim(),
                updatedAt = System.currentTimeMillis()
            )
            
            companies[index] = updated
            saveCompanies(companies)
            
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Удалить компанию
     */
    fun deleteCompany(id: String): Result<Unit> {
        return try {
            val companies = getAllCompanies().filter { it.id != id }
            saveCompanies(companies)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Проверить что компания с таким названием или API ключом уже существует
     */
    fun isDuplicate(name: String, apiKey: String, excludeId: String? = null): Pair<Boolean, String> {
        val companies = getAllCompanies()
        
        // Проверяем название
        val sameNameCompany = companies.find { 
            it.id != excludeId && it.name.equals(name.trim(), ignoreCase = true)
        }
        if (sameNameCompany != null) {
            return true to "Компания с таким названием уже существует"
        }
        
        // Проверяем API ключ
        val sameKeyCompany = companies.find {
            it.id != excludeId && it.apiKey == apiKey.trim()
        }
        if (sameKeyCompany != null) {
            return true to "Компания с этим API ключом уже существует"
        }
        
        return false to ""
    }
    
    /**
     * Сохранить список компаний
     */
    private fun saveCompanies(companies: List<Company>) {
        val array = JSONArray()
        companies.forEach { array.put(it.toJson()) }
        prefs.edit().putString(COMPANIES_KEY, array.toString()).apply()
    }
    
    /**
     * Очистить все компании (для тестирования)
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
