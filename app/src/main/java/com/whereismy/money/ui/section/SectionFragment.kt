package com.whereismy.money.ui.section

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.whereismy.money.R
import com.whereismy.money.data.Budget
import com.whereismy.money.data.Expense
import com.whereismy.money.data.FinancialAccount
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentSectionBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal

class SectionFragment : Fragment() {

    private var _binding: FragmentSectionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSectionBinding.inflate(inflater, container, false)
        binding.sectionTitle.text = findNavController().currentDestination?.label
        when (findNavController().currentDestination?.id) {
            R.id.nav_accounts -> loadAccounts()
            R.id.nav_budgets -> loadBudgetOverview()
            R.id.nav_reports -> loadReportOverview()
            R.id.nav_business -> loadBusinessOverview()
            else -> binding.sectionDescription.setText(R.string.section_planned)
        }
        return binding.root
    }

    private fun loadAccounts() {
        binding.sectionDescription.text = "Loading accounts..."
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.from("financial_accounts")
                    .select()
                    .decodeList<FinancialAccount>()
            }.onSuccess { accounts ->
                binding.sectionDescription.text = if (accounts.isEmpty()) {
                    getString(R.string.no_accounts)
                } else {
                    accounts.joinToString(separator = "\n") { account ->
                        "${account.name}: ${account.currency} ${account.starting_balance.jsonPrimitive.content}"
                    }
                }
            }.onFailure {
                binding.sectionDescription.text = it.message ?: getString(R.string.section_planned)
            }
        }
    }

    private fun parseDashboardSummary(response: Any?): Map<String, Any?> {
        val rows = when (response) {
            is List<*> -> response
            is Map<*, *> -> listOf(response)
            else -> emptyList<Any?>()
        }
        val first = rows.firstOrNull() as? Map<*, *> ?: error("Dashboard summary is empty")
        return first.entries.associate { (key, value) -> key.toString() to value }
    }

    private fun loadBudgetOverview() {
        binding.sectionDescription.text = "Loading budget overview..."
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull() ?: error(getString(R.string.workspace_required))
                val dashboard = supabaseClient.postgrest.rpc(
                    "get_workspace_dashboard",
                    parameters = buildJsonObject { put("requested_workspace", workspace.id) },
                )
                val first = parseDashboardSummary(dashboard)
                val budgets = supabaseClient.from("budgets")
                    .select()
                    .decodeList<Budget>()
                    .filter { it.workspace_id == workspace.id }
                val expenseSummary = supabaseClient.postgrest.rpc(
                    "get_workspace_budget_overview",
                    parameters = buildJsonObject { put("requested_workspace", workspace.id) },
                )
                val rows = when (expenseSummary) {
                    is List<*> -> expenseSummary
                    is Map<*, *> -> listOf(expenseSummary)
                    else -> emptyList<Any?>()
                }
                val overviewText = if (rows.isEmpty()) {
                    "Budget overview\n" +
                        "Workspace: ${workspace.name}\n" +
                        "Budget entries: ${budgets.size}\n" +
                        "Total Cash: ${first["total_cash"]}\n" +
                        "Monthly Cash Flow: ${first["cash_flow"]}"
                } else {
                    rows.joinToString(separator = "\n") { row ->
                        val map = row as? Map<*, *> ?: return@joinToString "Budget row unavailable"
                        val name = map["category"] ?: "Budget"
                        val limit = map["budget_amount"] ?: "0"
                        val spent = map["spent_amount"] ?: "0"
                        val remaining = map["remaining_amount"] ?: "0"
                        "$name: limit=$limit spent=$spent remain=$remaining"
                    }
                }
                overviewText
            }.onSuccess { content ->
                binding.sectionDescription.text = content
            }.onFailure {
                binding.sectionDescription.text = it.message ?: getString(R.string.section_planned)
            }
        }
    }

    private fun loadReportOverview() {
        binding.sectionDescription.text = "Loading profit and report overview..."
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull() ?: error(getString(R.string.workspace_required))
                val dashboard = supabaseClient.postgrest.rpc(
                    "get_workspace_dashboard",
                    parameters = buildJsonObject { put("requested_workspace", workspace.id) },
                )
                val first = parseDashboardSummary(dashboard)
                val expenses = supabaseClient.from("expenses")
                    .select()
                    .decodeList<Expense>()
                    .filter { it.workspace_id == workspace.id }
                val totalExpenses = expenses.fold(BigDecimal.ZERO) { total, expense ->
                    total + BigDecimal(expense.amount.jsonPrimitive.content)
                }
                "Profit & Reports\n" +
                    "Workspace: ${workspace.name}\n" +
                    "Total Expenses: ${first["total_expenses"]}\n" +
                    "Net Worth: ${first["net_worth"]}\n" +
                    "Cash Flow: ${first["cash_flow"]}\n" +
                    "Total Cash: ${first["total_cash"]}\n\n" +
                    "Logged expenses: ${expenses.size}\n" +
                    "Expense ledger total: ${totalExpenses}"
            }.onSuccess { content ->
                binding.sectionDescription.text = content
            }.onFailure {
                binding.sectionDescription.text = it.message ?: getString(R.string.section_planned)
            }
        }
    }

    private fun loadBusinessOverview() {
        binding.sectionDescription.text = "Loading business overview..."
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select()
                    .decodeList<Workspace>()
                    .firstOrNull() ?: error(getString(R.string.workspace_required))
                val dashboard = supabaseClient.postgrest.rpc(
                    "get_workspace_dashboard",
                    parameters = buildJsonObject { put("requested_workspace", workspace.id) },
                )
                val first = parseDashboardSummary(dashboard)
                "Business overview\n" +
                    "Workspace: ${workspace.name} (${workspace.workspace_type})\n" +
                    "Cash: ${first["total_cash"]}\n" +
                    "Debts: ${first["total_debts"]}\n" +
                    "Expenses: ${first["total_expenses"]}\n" +
                    "Net Worth: ${first["net_worth"]}"
            }.onSuccess { content ->
                binding.sectionDescription.text = content
            }.onFailure {
                binding.sectionDescription.text = it.message ?: getString(R.string.section_planned)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
