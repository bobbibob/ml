package com.ml.app.data.remote.dto

data class HistoryItemDto(
    val log_id: Long,
    val entity_type: String,
    val entity_id: String,
    val action_type: String,
    val actor_user_id: String,
    val actor_name: String,
    val created_at: String,
    val details: Map<String, Any>?
)
