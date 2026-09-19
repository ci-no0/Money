package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class DashboardSummary(
    val total_cash: JsonElement = JsonPrimitive("0.00"),
    val total_debts: JsonElement = JsonPrimitive("0.00"),
    val net_worth: JsonElement = JsonPrimitive("0.00"),
)
