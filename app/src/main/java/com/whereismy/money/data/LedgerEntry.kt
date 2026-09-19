package com.whereismy.money.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class LedgerEntry(
    val id: String,
    val ledger_transaction_id: String,
    val ledger_account_id: String,
    val debit: JsonElement = JsonPrimitive("0.00"),
    val credit: JsonElement = JsonPrimitive("0.00"),
)
