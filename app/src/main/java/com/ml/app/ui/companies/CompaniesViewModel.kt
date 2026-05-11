package com.ml.app.ui.companies

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.data.companies.Company
import com.ml.app.data.companies.CompaniesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CompaniesUiState(
    val companies: List<Company> = emptyList(),
    val isLoading: Boolean = false,
    val error: String = "",
    val successMessage: String = ""
)

class CompaniesViewModel(private val repository: CompaniesRepository) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CompaniesUiState())
    val uiState: StateFlow<CompaniesUiState> = _uiState.asStateFlow()
    
    init {
        loadCompanies()
    }
    
    fun loadCompanies() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val companies = repository.getAllCompanies()
            _uiState.value = _uiState.value.copy(
                companies = companies,
                isLoading = false
            )
        }
    }
    
    fun addCompany(name: String, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = "")
            
            // Проверяем дубликаты
            val (isDuplicate, message) = repository.isDuplicate(name, apiKey)
            if (isDuplicate) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = message
                )
                return@launch
            }
            
            val result = repository.addCompany(name, apiKey)
            result.onSuccess {
                loadCompanies()
                _uiState.value = _uiState.value.copy(
                    successMessage = "Компания добавлена"
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = it.message ?: "Ошибка при добавлении"
                )
            }
        }
    }
    
    fun updateCompany(id: String, name: String, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = "")
            
            // Проверяем дубликаты (исключая текущую компанию)
            val (isDuplicate, message) = repository.isDuplicate(name, apiKey, excludeId = id)
            if (isDuplicate) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = message
                )
                return@launch
            }
            
            val result = repository.updateCompany(id, name, apiKey)
            result.onSuccess {
                loadCompanies()
                _uiState.value = _uiState.value.copy(
                    successMessage = "Компания обновлена"
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = it.message ?: "Ошибка при обновлении"
                )
            }
        }
    }
    
    fun deleteCompany(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.deleteCompany(id)
            result.onSuccess {
                loadCompanies()
                _uiState.value = _uiState.value.copy(
                    successMessage = "Компания удалена"
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    error = it.message ?: "Ошибка при удалении"
                )
            }
        }
    }
    
    fun getCompanyByApiKey(apiKey: String): Company? {
        return repository.getCompanyByApiKey(apiKey)
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = "")
    }
    
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = "")
    }
    
    /**
     * Проверить что компания с таким API ключом существует
     */
    fun isCompanyExists(apiKey: String): Boolean {
        return repository.getCompanyByApiKey(apiKey) != null
    }
    
    /**
     * Получить всех компаний
     */
    fun getAllCompanies(): List<Company> {
        return _uiState.value.companies
    }
}
