package com.whereismy.money.ui.reports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentReportBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        loadReportSummary()
        return binding.root
    }

    private fun loadReportSummary() {
        binding.reportStatus.text = "Loading reports..."
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
                val summaryRows = when (dashboard) {
                    is List<*> -> dashboard
                    is Map<*, *> -> listOf(dashboard)
                    else -> emptyList<Any?>()
                }
                val summary = summaryRows.firstOrNull() as? Map<*, *> ?: error("Dashboard summary is empty")

                val totalCash = parseNumeric(summary["total_cash"])
                val totalDebts = parseNumeric(summary["total_debts"])
                val totalExpenses = parseNumeric(summary["total_expenses"])
                val netWorth = parseNumeric(summary["net_worth"])
                val cashFlow = parseNumeric(summary["cash_flow"])

                workspace to Triple(totalCash, totalDebts, Triple(totalExpenses, netWorth, cashFlow))
            }.onSuccess { (workspace, values) ->
                val (totalCash, totalDebts, metrics) = values
                val (totalExpenses, netWorth, cashFlow) = metrics

                binding.reportTitle.text = "Profit & Reports"
                binding.reportWorkspace.text = workspace.name
                binding.reportCashValue.text = "${workspace.base_currency} ${totalCash}"
                binding.reportDebtValue.text = "${workspace.base_currency} ${totalDebts}"
                binding.reportExpenseValue.text = "${workspace.base_currency} ${totalExpenses}"
                binding.reportNetWorthValue.text = "${workspace.base_currency} ${netWorth}"
                binding.reportCashFlowValue.text = "${workspace.base_currency} ${cashFlow}"
                binding.reportStatus.text = getString(R.string.report_ready)
            }.onFailure {
                showError(it)
            }
        }
    }

    private fun parseNumeric(value: Any?, fallback: BigDecimal = BigDecimal.ZERO): BigDecimal {
        val raw = when (value) {
            is Number -> value.toString()
            is String -> value
            is Map<*, *> -> value.values.firstOrNull()?.toString() ?: fallback.toPlainString()
            else -> value?.toString() ?: fallback.toPlainString()
        }
        return runCatching { BigDecimal(raw) }.getOrDefault(fallback)
    }

    private fun showError(error: Throwable) {
        binding.reportStatus.text = error.message ?: getString(R.string.report_error)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
