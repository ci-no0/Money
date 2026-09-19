package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class LedgerAccount(
    val id: String,
    val financial_account_id: String? = null,
    val account_type: String = "asset",
    val currency: String = "PHP",
)
