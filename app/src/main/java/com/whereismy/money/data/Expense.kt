package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Expense(
    val id: String,
    val workspace_id: String,
    val category_id: String,
    val financial_account_id: String = "",
    val amount: JsonElement = JsonPrimitive("0.00"),
    val currency: String = "PHP",
    val transaction_date: String? = null,
    val note: String? = null,
)
