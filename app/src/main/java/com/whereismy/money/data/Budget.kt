package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Budget(
    val id: String,
    val workspace_id: String,
    val name: String,
    val category: String,
    val amount: JsonElement = JsonPrimitive("0.00"),
    val currency: String = "PHP",
    val budget_month: String? = null,
)
