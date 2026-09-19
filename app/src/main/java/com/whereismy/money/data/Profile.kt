package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val display_name: String? = null,
    val timezone: String = "Asia/Manila",
)