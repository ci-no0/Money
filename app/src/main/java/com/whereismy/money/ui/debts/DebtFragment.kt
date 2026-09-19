package com.whereismy.money.ui.debts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.whereismy.money.R
import com.whereismy.money.data.Debt
import com.whereismy.money.data.Workspace
import com.whereismy.money.databinding.FragmentDebtBinding
import com.whereismy.money.supabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

class DebtFragment : Fragment() {

    private var _binding: FragmentDebtBinding? = null
    private val binding get() = _binding!!
    private var activeWorkspaceId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDebtBinding.inflate(inflater, container, false)
        binding.createDebtButton.setOnClickListener { createDebt() }
        loadWorkspaceAndDebts()
        return binding.root
    }

    private fun loadWorkspaceAndDebts() {
        binding.debtStatus.setText(R.string.debt_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val workspace = supabaseClient.from("workspaces")
                    .select().decodeList<Workspace>().firstOrNull()
                    ?: error(getString(R.string.workspace_required))
                val debts = supabaseClient.from("debts")
                    .select().decodeList<Debt>()
                workspace.id to debts.filterNot { it.status == "cancelled" }
            }.onSuccess { (workspaceId, debts) ->
                activeWorkspaceId = workspaceId
                binding.debtList.text = if (debts.isEmpty()) {
                    getString(R.string.no_debts)
                } else {
                    debts.joinToString("\n") {
                        "${it.title}: ${it.currency} ${it.total_payable.jsonPrimitive.content} (${it.status})"
                    }
                }
                binding.debtStatus.setText(R.string.debt_ready)
            }.onFailure { showError(it) }
        }
    }

    private fun createDebt() {
        val workspaceId = activeWorkspaceId
        val title = binding.debtTitleInput.text.toString().trim()
        val principal = parseAmount(binding.debtPrincipalInput.text.toString())
        val interest = parseAmount(binding.debtInterestInput.text.toString()) ?: BigDecimal.ZERO
        val fee = parseAmount(binding.debtFeeInput.text.toString()) ?: BigDecimal.ZERO
        val months = binding.debtMonthsInput.text.toString().trim().toIntOrNull()

        if (workspaceId == null || title.isBlank()) {
            binding.debtStatus.setText(R.string.debt_title_required)
            return
        }
        if (principal == null || principal.signum() <= 0 || interest.signum() < 0 || fee.signum() < 0) {
            binding.debtStatus.setText(R.string.invalid_debt_amount)
            return
        }
        if (months == null || months !in 1..600) {
            binding.debtStatus.setText(R.string.invalid_debt_months)
            return
        }

        binding.createDebtButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                supabaseClient.postgrest.rpc(
                    "create_debt",
                    parameters = buildJsonObject {
                        put("requested_workspace", workspaceId)
                        put("requested_title", title)
                        put("requested_direction", binding.debtDirectionSpinner.selectedItem.toString().lowercase())
                        put("requested_principal", principal.toPlainString())
                        put("requested_interest", interest.toPlainString())
                        put("requested_fee", fee.toPlainString())
                        put("requested_months", months)
                        put("requested_start_date", LocalDate.now().toString())
                    },
                )
            }.onSuccess {
                binding.debtTitleInput.text.clear()
                binding.debtPrincipalInput.text.clear()
                binding.debtInterestInput.text.clear()
                binding.debtFeeInput.text.clear()
                binding.debtMonthsInput.text.clear()
                binding.debtStatus.setText(R.string.debt_created)
                loadWorkspaceAndDebts()
            }.onFailure { showError(it) }
            binding.createDebtButton.isEnabled = true
        }
    }

    private fun parseAmount(value: String): BigDecimal? =
        value.trim().ifBlank { "0" }.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun showError(error: Throwable) {
        binding.debtStatus.text = error.message ?: getString(R.string.debt_error)
        binding.createDebtButton.isEnabled = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
