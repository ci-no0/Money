package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Debt(
    val id: String,
    val title: String,
    val direction: String,
    val original_amount: JsonElement = JsonPrimitive("0.00"),
    val total_payable: JsonElement = JsonPrimitive("0.00"),
    val currency: String = "PHP",
    val status: String = "pending",
)
