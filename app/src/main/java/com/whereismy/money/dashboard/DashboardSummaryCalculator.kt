package com.whereismy.money.dashboard

import java.math.BigDecimal

object DashboardSummaryCalculator {
    fun accountBalance(
        startingBalance: BigDecimal,
        debit: BigDecimal = BigDecimal.ZERO,
        credit: BigDecimal = BigDecimal.ZERO,
    ): BigDecimal = startingBalance + debit - credit

    fun totalCash(accountBalances: List<BigDecimal>): BigDecimal =
        accountBalances.fold(BigDecimal.ZERO) { total, balance -> total + balance }

    fun totalDebts(debtAmounts: List<BigDecimal>): BigDecimal =
        debtAmounts.fold(BigDecimal.ZERO) { total, amount -> total + amount }

    fun netWorth(totalCash: BigDecimal, totalDebts: BigDecimal): BigDecimal =
        totalCash - totalDebts
}
