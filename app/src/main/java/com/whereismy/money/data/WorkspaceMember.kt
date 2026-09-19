package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceMember(
    val id: String,
    val workspace_id: String,
    val user_id: String,
    val role_id: String? = null,
    val status: String = "active",
)