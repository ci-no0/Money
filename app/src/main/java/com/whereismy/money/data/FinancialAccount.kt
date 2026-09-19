package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class FinancialAccount(
    val id: String,
    val workspace_id: String = "",
    val name: String,
    val account_type: String,
    val currency: String = "PHP",
    val starting_balance: JsonElement = JsonPrimitive("0.00"),
    val is_active: Boolean = true,
)