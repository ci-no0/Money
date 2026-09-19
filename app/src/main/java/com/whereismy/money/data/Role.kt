package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class Role(
    val id: String,
    val workspace_id: String,
    val name: String,
    val is_admin: Boolean = false,
)
