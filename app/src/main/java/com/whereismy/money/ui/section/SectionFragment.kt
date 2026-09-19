package com.whereismy.money.ui.section

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.whereismy.money.R
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
                val first = (dashboard as? List<*>)?.firstOrNull() as? Map<*, *> ?: error("Dashboard summary is empty")
                "Budget overview\n" +
                    "Workspace: ${workspace.name}\n" +
                    "Total Cash: ${first["total_cash"]}\n" +
                    "Total Debts: ${first["total_debts"]}\n" +
                    "Net Worth: ${first["net_worth"]}\n" +
                    "Monthly Cash Flow: ${first["cash_flow"]}"
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
                val first = (dashboard as? List<*>)?.firstOrNull() as? Map<*, *> ?: error("Dashboard summary is empty")
                "Profit & Reports\n" +
                    "Workspace: ${workspace.name}\n" +
                    "Total Expenses: ${first["total_expenses"]}\n" +
                    "Net Worth: ${first["net_worth"]}\n" +
                    "Cash Flow: ${first["cash_flow"]}\n" +
                    "Total Cash: ${first["total_cash"]}"
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
                val first = (dashboard as? List<*>)?.firstOrNull() as? Map<*, *> ?: error("Dashboard summary is empty")
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
