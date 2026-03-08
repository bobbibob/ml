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

    @POST("google_login")
    suspend fun googleLogin(@Body request: GoogleLoginRequest): GoogleLoginResponse

    @POST("register.php")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    @POST("login.php")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("me.php")
    suspend fun me(): MeResponse

    @GET("users_list")
    suspend fun getUsers(): UsersResponse

    @POST("create_task")
    suspend fun createTask(@Body request: CreateTaskRequest): CreateTaskResponse

    @GET("my_tasks")
    suspend fun getMyTasks(): TasksResponse

    @GET("all_tasks")
    suspend fun getAllTasks(): TasksResponse

    @POST("complete_task")
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
