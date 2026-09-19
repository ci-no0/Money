package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val workspace_type: String,
    val base_currency: String = "PHP",
    val timezone: String = "Asia/Manila",
)