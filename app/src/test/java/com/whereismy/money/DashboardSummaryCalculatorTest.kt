package com.whereismy.money

import com.whereismy.money.dashboard.DashboardSummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class DashboardSummaryCalculatorTest {
    @Test
    fun accountBalanceIncludesOpeningBalanceAndLedgerMovements() {
        val result = DashboardSummaryCalculator.accountBalance(
            startingBalance = BigDecimal("1000.00"),
            debit = BigDecimal("250.50"),
            credit = BigDecimal("75.00"),
        )

        assertEquals(BigDecimal("1175.50"), result)
    }

    @Test
    fun netWorthSubtractsDebtObligations() {
        val result = DashboardSummaryCalculator.netWorth(
            totalCash = BigDecimal("15000.00"),
            totalDebts = BigDecimal("5000.00"),
        )

        assertEquals(BigDecimal("10000.00"), result)
    }
}
