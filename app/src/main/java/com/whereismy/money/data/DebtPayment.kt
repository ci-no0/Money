package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class DebtPayment(
    val id: String,
    val debt_id: String,
    val amount: JsonElement = JsonPrimitive("0.00"),
    val currency: String = "PHP",
    val reversed_at: String? = null,
)
