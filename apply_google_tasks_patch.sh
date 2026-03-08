#!/data/data/com.termux/files/usr/bin/bash
set -e

cd ~/ml

mkdir -p app/src/main/java/com/ml/app/auth
mkdir -p app/src/main/java/com/ml/app/data/remote/request
mkdir -p app/src/main/java/com/ml/app/data/remote/response
mkdir -p app/src/main/java/com/ml/app/data/remote/api
mkdir -p app/src/main/java/com/ml/app/data/repository
mkdir -p app/src/main/java/com/ml/app/ui

python3 - <<'PY'
from pathlib import Path

# build.gradle.kts
p = Path("app/build.gradle.kts")
t = p.read_text(encoding="utf-8")

deps = [
    'implementation("androidx.credentials:credentials:1.3.0")',
    'implementation("androidx.credentials:credentials-play-services-auth:1.3.0")',
    'implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")',
]

if "dependencies {" in t:
    for dep in deps:
        if dep not in t:
            t = t.replace("dependencies {", "dependencies {\n    " + dep, 1)

p.write_text(t, encoding="utf-8")

# AndroidManifest.xml
p = Path("app/src/main/AndroidManifest.xml")
t = p.read_text(encoding="utf-8")
if 'android:usesCleartextTraffic="true"' not in t:
    t = t.replace("<application", '<application android:usesCleartextTraffic="true"', 1)
p.write_text(t, encoding="utf-8")
PY

cat > app/src/main/java/com/ml/app/auth/GoogleAuthManager.kt <<'EOF2'
package com.ml.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class GoogleAuthManager(
    private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(): String? {
        val googleIdOption = GetSignInWithGoogleOption.Builder(
            serverClientId = "1049013487136-47q0n2q6s3s9itqq3qsf8l4c9dv0frn7.apps.googleusercontent.com"
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(
            context = context,
            request = request
        )

        val credential = result.credential
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
        return googleIdToken.idToken
    }
}
EOF2

cat > app/src/main/java/com/ml/app/data/remote/request/AuthRequests.kt <<'EOF2'
package com.ml.app.data.remote.request

data class RegisterRequest(
    val email: String,
    val password: String,
    val display_name: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class GoogleLoginRequest(
    val id_token: String
)
EOF2

cat > app/src/main/java/com/ml/app/data/remote/response/AuthResponses.kt <<'EOF2'
package com.ml.app.data.remote.response

import com.ml.app.data.remote.dto.UserDto

data class RegisterResponse(
    val ok: Boolean,
    val user: UserDto?
)

data class LoginResponse(
    val ok: Boolean,
    val token: String?,
    val user: UserDto?
)

data class GoogleLoginResponse(
    val ok: Boolean,
    val token: String?,
    val user: UserDto?
)

data class MeResponse(
    val ok: Boolean,
    val user: UserDto?
)
EOF2

cat > app/src/main/java/com/ml/app/data/remote/api/MlApiService.kt <<'EOF2'
package com.ml.app.data.remote.api

import com.ml.app.data.remote.request.CancelTaskRequest
import com.ml.app.data.remote.request.ChangeRoleRequest
import com.ml.app.data.remote.request.CompleteTaskRequest
import com.ml.app.data.remote.request.CreateTaskRequest
import com.ml.app.data.remote.request.GoogleLoginRequest
import com.ml.app.data.remote.request.LoginRequest
import com.ml.app.data.remote.request.ReassignTaskRequest
import com.ml.app.data.remote.request.RegisterRequest
import com.ml.app.data.remote.response.CancelTaskResponse
import com.ml.app.data.remote.response.ChangeRoleResponse
import com.ml.app.data.remote.response.CompleteTaskResponse
import com.ml.app.data.remote.response.CreateTaskResponse
import com.ml.app.data.remote.response.GoogleLoginResponse
import com.ml.app.data.remote.response.HistoryResponse
import com.ml.app.data.remote.response.LoginResponse
import com.ml.app.data.remote.response.MeResponse
import com.ml.app.data.remote.response.ReassignTaskResponse
import com.ml.app.data.remote.response.RegisterResponse
import com.ml.app.data.remote.response.TasksResponse
import com.ml.app.data.remote.response.UsersResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface MlApiService {

    @POST("google_login.php")
    suspend fun googleLogin(@Body request: GoogleLoginRequest): GoogleLoginResponse

    @POST("register.php")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    @POST("login.php")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("me.php")
    suspend fun me(): MeResponse

    @GET("users_list.php")
    suspend fun getUsers(): UsersResponse

    @POST("create_task.php")
    suspend fun createTask(@Body request: CreateTaskRequest): CreateTaskResponse

    @GET("my_tasks.php")
    suspend fun getMyTasks(): TasksResponse

    @GET("all_tasks.php")
    suspend fun getAllTasks(): TasksResponse

    @POST("complete_task.php")
    suspend fun completeTask(@Body request: CompleteTaskRequest): CompleteTaskResponse

    @POST("cancel_task.php")
    suspend fun cancelTask(@Body request: CancelTaskRequest): CancelTaskResponse

    @POST("reassign_task.php")
    suspend fun reassignTask(@Body request: ReassignTaskRequest): ReassignTaskResponse

    @GET("history.php")
    suspend fun getHistory(): HistoryResponse

    @POST("change_role.php")
    suspend fun changeRole(@Body request: ChangeRoleRequest): ChangeRoleResponse
}
EOF2

cat > app/src/main/java/com/ml/app/data/repository/AuthRepository.kt <<'EOF2'
package com.ml.app.data.repository

import com.ml.app.core.network.safeApiCall
import com.ml.app.core.result.AppResult
import com.ml.app.data.remote.api.MlApiService
import com.ml.app.data.remote.dto.UserDto
import com.ml.app.data.remote.request.GoogleLoginRequest
import com.ml.app.data.remote.request.LoginRequest
import com.ml.app.data.remote.request.RegisterRequest
import com.ml.app.data.session.SessionStorage

class AuthRepository(
    private val api: MlApiService,
    private val sessionStorage: SessionStorage
) {

    suspend fun googleLogin(idToken: String): AppResult<UserDto> {
        return when (
            val result = safeApiCall {
                api.googleLogin(GoogleLoginRequest(id_token = idToken))
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val body = result.data
                val token = body.token
                val user = body.user

                if (body.ok && token != null && user != null) {
                    sessionStorage.saveToken(token)
                    sessionStorage.saveUserId(user.user_id)
                    AppResult.Success(user)
                } else {
                    AppResult.Error("Google login failed")
                }
            }
        }
    }

    suspend fun register(
        email: String,
        password: String,
        displayName: String
    ): AppResult<UserDto> {
        return when (
            val result = safeApiCall {
                api.register(
                    RegisterRequest(
                        email = email,
                        password = password,
                        display_name = displayName
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val user = result.data.user
                if (result.data.ok && user != null) {
                    AppResult.Success(user)
                } else {
                    AppResult.Error("Registration failed")
                }
            }
        }
    }

    suspend fun login(
        email: String,
        password: String
    ): AppResult<UserDto> {
        return when (
            val result = safeApiCall {
                api.login(
                    LoginRequest(
                        email = email,
                        password = password
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val body = result.data
                val token = body.token
                val user = body.user

                if (body.ok && token != null && user != null) {
                    sessionStorage.saveToken(token)
                    sessionStorage.saveUserId(user.user_id)
                    AppResult.Success(user)
                } else {
                    AppResult.Error("Login failed")
                }
            }
        }
    }

    suspend fun me(): AppResult<UserDto> {
        return when (val result = safeApiCall { api.me() }) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val user = result.data.user
                if (result.data.ok && user != null) {
                    sessionStorage.saveUserId(user.user_id)
                    AppResult.Success(user)
                } else {
                    AppResult.Error("User not found")
                }
            }
        }
    }

    fun logout() {
        sessionStorage.clearToken()
        sessionStorage.clearUserId()
    }

    fun isLoggedIn(): Boolean = !sessionStorage.getToken().isNullOrBlank()
}
EOF2

cat > app/src/main/java/com/ml/app/ui/TasksViewModel.kt <<'EOF2'
package com.ml.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ml.app.core.network.ApiModule
import com.ml.app.core.result.AppResult
import com.ml.app.data.remote.dto.HistoryItemDto
import com.ml.app.data.remote.dto.TaskDto
import com.ml.app.data.remote.dto.UserDto
import com.ml.app.data.repository.AuthRepository
import com.ml.app.data.repository.TasksRepository
import com.ml.app.data.session.PrefsSessionStorage
import kotlinx.coroutines.launch

data class TasksUiState(
    val loading: Boolean = false,
    val currentUser: UserDto? = null,
    val error: String? = null,
    val info: String? = null,
    val myTasks: List<TaskDto> = emptyList(),
    val allTasks: List<TaskDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val history: List<HistoryItemDto> = emptyList(),
    val selectedTab: String = "my"
)

class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val session = PrefsSessionStorage(app.applicationContext)
    private val api = ApiModule.createApi(
        baseUrl = "http://ml.gamer.gd/api/",
        sessionStorage = session
    )
    private val authRepo = AuthRepository(api, session)
    private val tasksRepo = TasksRepository(api)

    var state by mutableStateOf(TasksUiState())
        private set

    fun init() {
        if (state.currentUser != null) return
        if (!authRepo.isLoggedIn()) return

        viewModelScope.launch {
            when (val res = authRepo.me()) {
                is AppResult.Success -> {
                    state = state.copy(currentUser = res.data, error = null)
                    refreshAll()
                }
                is AppResult.Error -> {
                    authRepo.logout()
                    state = state.copy(currentUser = null, error = res.message)
                }
            }
        }
    }

    fun loginWithGoogleToken(idToken: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = authRepo.googleLogin(idToken)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        currentUser = res.data,
                        info = "Вход выполнен"
                    )
                    refreshAll()
                }
                is AppResult.Error -> {
                    state = state.copy(
                        loading = false,
                        error = res.message
                    )
                }
            }
        }
    }

    fun logout() {
        authRepo.logout()
        state = TasksUiState()
    }

    fun selectTab(tab: String) {
        state = state.copy(selectedTab = tab)
    }

    fun refreshAll() {
        val user = state.currentUser ?: return
        loadMyTasks()
        loadUsers()
        if (user.role == "plus" || user.role == "admin") {
            loadAllTasks()
            loadHistory()
        }
    }

    fun loadMyTasks() {
        viewModelScope.launch {
            when (val res = tasksRepo.getMyTasks()) {
                is AppResult.Success -> state = state.copy(myTasks = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun loadAllTasks() {
        viewModelScope.launch {
            when (val res = tasksRepo.getAllTasks()) {
                is AppResult.Success -> state = state.copy(allTasks = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun loadUsers() {
        viewModelScope.launch {
            when (val res = tasksRepo.getUsers()) {
                is AppResult.Success -> state = state.copy(users = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            when (val res = tasksRepo.getHistory()) {
                is AppResult.Success -> state = state.copy(history = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun createTask(title: String, description: String, assigneeUserId: String) {
        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(error = "Заполни название и исполнителя")
            return
        }

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.createTask(title, description, assigneeUserId)) {
                is AppResult.Success -> {
                    state = state.copy(loading = false, info = "Задача создана")
                    refreshAll()
                }
                is AppResult.Error -> {
                    state = state.copy(loading = false, error = res.message)
                }
            }
        }
    }

    fun completeTask(taskId: String) {
        viewModelScope.launch {
            when (val res = tasksRepo.completeTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(info = "Задача выполнена", error = null)
                    refreshAll()
                }
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }
}
EOF2

cat > app/src/main/java/com/ml/app/ui/TasksScreen.kt <<'EOF2'
package com.ml.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ml.app.auth.GoogleAuthManager
import kotlinx.coroutines.launch

@Composable
fun TasksScreen(
    onBack: () -> Unit,
    vm: TasksViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        vm.init()
    }

    val state = vm.state
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.currentUser?.let { user ->
            Text("${user.display_name} • ${user.role}")
        } ?: run {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val idToken = GoogleAuthManager(ctx).signIn()
                            if (!idToken.isNullOrBlank()) {
                                vm.loginWithGoogleToken(idToken)
                            }
                        } catch (_: Exception) {
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Войти через Google")
            }
        }

        state.error?.let {
            Text("Ошибка: $it")
        }

        state.info?.let {
            Text(it)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = taskTitle,
            onValueChange = { taskTitle = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название задачи") }
        )

        OutlinedTextField(
            value = taskDescription,
            onValueChange = { taskDescription = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Описание") }
        )

        Button(
            onClick = {
                val assigneeId = state.currentUser?.user_id.orEmpty()
                vm.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    assigneeUserId = assigneeId
                )
                taskTitle = ""
                taskDescription = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.currentUser != null
        ) {
            Text("Создать тестовую задачу себе")
        }

        Button(
            onClick = { vm.loadMyTasks() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.currentUser != null
        ) {
            Text("Обновить мои задачи")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.myTasks.isEmpty()) {
            Text("Задач пока нет")
        } else {
            state.myTasks.forEach { task ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    if (!task.description.isNullOrBlank()) {
                        Text(task.description)
                    }
                    Text("Статус: ${task.status}")
                    Text("Исполнитель: ${task.assignee_name}")

                    if (!task.completed_by_name.isNullOrBlank() &&
                        task.completed_by_user_id != task.assignee_user_id
                    ) {
                        Text("Отметил выполненной: ${task.completed_by_name}")
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = { vm.completeTask(task.task_id) },
                        enabled = task.status == "open"
                    ) {
                        Text("Выполнено")
                    }
                }
            }
        }
    }
}
EOF2

chmod +x apply_google_tasks_patch.sh
echo "Patch files written."
