package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class Permission(
    val code: String,
    val description: String,
)
