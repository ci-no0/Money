package com.whereismy.money.data

import kotlinx.serialization.Serializable

@Serializable
data class FinancialAccount(
    val id: String,
    val name: String,
    val account_type: String,
    val currency: String = "PHP",
    val starting_balance: String = "0.00",
    val is_active: Boolean = true,
)