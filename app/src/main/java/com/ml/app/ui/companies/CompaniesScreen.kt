package com.ml.app.ui.companies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ml.app.data.companies.Company
import com.ml.app.data.companies.CompaniesRepository

@Composable
fun CompaniesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { CompaniesRepository(context) }
    
    var companies by remember { mutableStateOf<List<Company>>(repository.getAllCompanies()) }
    var showAddNew by remember { mutableStateOf(false) }
    var editingCompany by remember { mutableStateOf<Company?>(null) }
    
    var newName by remember { mutableStateOf("") }
    var newApiKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    
    fun refreshCompanies() {
        companies = repository.getAllCompanies()
    }
    
    fun clearInputs() {
        newName = ""
        newApiKey = ""
        errorMessage = ""
        showAddNew = false
        editingCompany = null
    }
    
    fun saveCompany() {
        if (newName.isBlank()) {
            errorMessage = "Введите название компании"
            return
        }
        if (newApiKey.isBlank()) {
            errorMessage = "Введите API ключ"
            return
        }
        
        if (editingCompany != null) {
            // Редактирование
            val result = repository.updateCompany(editingCompany!!.id, newName, newApiKey)
            result.onSuccess {
                refreshCompanies()
                clearInputs()
            }.onFailure {
                errorMessage = it.message ?: "Ошибка при обновлении"
            }
        } else {
            // Добавление новой
            val result = repository.addCompany(newName, newApiKey)
            result.onSuccess {
                refreshCompanies()
                clearInputs()
            }.onFailure {
                errorMessage = it.message ?: "Ошибка при добавлении"
            }
        }
    }
    
    fun deleteCompany(company: Company) {
        repository.deleteCompany(company.id)
        refreshCompanies()
        clearInputs()
    }
    
    fun startEdit(company: Company) {
        editingCompany = company
        newName = company.name
        newApiKey = company.apiKey
        showAddNew = true
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        TopAppBar(
            title = { Text("Управление компаниями") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
        )
        
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Список существующих компаний
            if (companies.isNotEmpty()) {
                Text(
                    "Добавленные компании",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                companies.forEach { company ->
                    CompanyListItem(
                        company = company,
                        onEdit = { startEdit(company) },
                        onDelete = { deleteCompany(company) }
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Форма добавления/редактирования
            if (showAddNew) {
                CompanyForm(
                    name = newName,
                    apiKey = newApiKey,
                    onNameChange = { newName = it },
                    onApiKeyChange = { newApiKey = it },
                    onSave = { saveCompany() },
                    onCancel = { clearInputs() },
                    isEditing = editingCompany != null,
                    errorMessage = errorMessage,
                    isFormValid = newName.isNotBlank() && newApiKey.isNotBlank()
                )
            } else {
                // Кнопка добавить новую компанию
                Button(
                    onClick = { 
                        showAddNew = true
                        editingCompany = null
                        clearInputs()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Добавить компанию")
                }
            }
        }
    }
}

@Composable
private fun CompanyListItem(
    company: Company,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = company.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = company.apiKey.take(15) + "...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Редактировать",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanyForm(
    name: String,
    apiKey: String,
    onNameChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    isEditing: Boolean,
    errorMessage: String,
    isFormValid: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = if (isEditing) "Редактировать компанию" else "Добавить новую компанию",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Название компании
            TextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Название компании") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) }
            )
            
            // API ключ
            TextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API ключ") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                helperText = { Text("Ключ будет скрыт") }
            )
            
            // Сообщение об ошибке
            if (errorMessage.isNotBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            
            // Кнопки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors()
                ) {
                    Text("Отмена")
                }
                
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    enabled = isFormValid
                ) {
                    Text(if (isEditing) "Сохранить" else "Добавить")
                }
            }
        }
    }
}

// Helper: для использования в DropdownMenu
@Composable
fun CompaniesDropdownMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Управление компаниями") },
        onClick = onClick,
        leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) }
    )
}
